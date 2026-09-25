package com.hud.navi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.*

/**
 * HUD 导航 v9.2 — 指南针抗抖动（EMA 平滑 + 死区 + GPS 航向优先）
 *
 * v9.1 → v9.2:
 * - 指南针 EMA 低通滤波（alpha=0.08，重型平滑）消除磁场噪音
 * - 航向死区 2.5°：小于此阈值的变化直接忽略，防止地图抽搐
 * - 圆形 EMA：正确处理 359°→1° 跨越（不会反转 358°）
 * - GPS 速度 > 5 km/h 时强制使用 GPS 航向（比指南针稳定得多）
 * - 静止检测增强：加速度 < 0.5 m/s² 且速度 < 2 km/h 时冻结航向
 */
class MainActivity : AppCompatActivity(), LocationListener, SensorEventListener {

    private lateinit var hudView: HudView
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private val handler = Handler(Looper.getMainLooper())

    // === GPS 抗跳变滤波器 ===
    private val gpsFilter = GpsFilter()

    // === UI（仅权限重试） ===
    private lateinit var flipContainer: FrameLayout
    private lateinit var permDeniedLayout: LinearLayout
    private lateinit var btnRetryPerm: TextView

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var roadFetchJob: Job? = null

    // === GPS 目标（GPS 滤波器输出） ===
    private var targetLat = 0.0; private var targetLng = 0.0
    private var targetBearing = 0f; private var targetSpeed = 0f

    // === IMU 惯导状态（替代旧的追逐式插值） ===
    // vehicleLat/vehicleLng 就是当前显示位置，不再区分"目标"和"显示"
    private var vehicleLat = 0.0; private var vehicleLng = 0.0
    private var vehicleBearing = 0f
    private var lastFrameTime = 0L
    private val FRAME_MS = 16L

    // 惯导速度：GPS 到达时锁定，GPS 丢失后指数衰减
    private var imuSpeedKmh = 0f
    private val VELOCITY_DECAY = 0.995f          // GPS 丢失后每帧速度衰减

    // 传感器原始数据
    private val accData = FloatArray(3)
    private val magData = FloatArray(3)
    private var hasCompass = false
    private var compassBearing = 0f              // 指南针原始值（不直接使用）
    private var smoothedCompassBearing = 0f      // EMA 平滑后的指南针
    private var compassInitialized = false       // 首次初始化标记
    private val worldAcc = FloatArray(3)         // 世界坐标系加速度（北/东/上）
    private val smoothedWorldAcc = FloatArray(3) // 平滑后的世界加速度
    private val ACC_SMOOTH = 0.15f               // 加速度 EMA 平滑系数

    // === 状态 ===
    private var gpsFixCount = 0
    private var lastGpsTime = 0L
    private var lastRoadFetchLat = 0.0
    private var lastRoadFetchLng = 0.0
    private var lastRoadFetchTime = 0L

    // === 60fps 渲染循环 ===
    private var renderRunning = false
    private val renderRunnable = object : Runnable {
        override fun run() {
            updateFrame()
            handler.postDelayed(this, FRAME_MS)
        }
    }

    companion object {
        private const val PERM_REQUEST = 100
        private const val TAG = "HudNavi"
        private const val ROAD_FETCH_DIST = 150.0
        private const val ROAD_FETCH_INTERVAL = 5000L
        // === 指南针抗抖动参数 ===
        private const val COMPASS_EMA_ALPHA = 0.08f   // EMA 平滑系数（越小越平滑，0.05~0.15）
        private const val HEADING_DEAD_ZONE = 2.5f    // 航向死区（度）：小于此变化直接忽略
        private const val GPS_BEARING_SPEED = 5f      // GPS 航向优先的速度阈值（km/h）
        private const val FREEZE_ACC_THRESHOLD = 0.5f // 静止冻结的加速度阈值（m/s²）
        private const val FREEZE_SPEED_THRESHOLD = 2f // 静止冻结的速度阈值（km/h）
    }

    // === 速度分级吸附参数 ===
    private fun getSnapParams(speedKmh: Float): Pair<Double, Double> {
        return when {
            speedKmh < 5f  -> Pair(0.0, 0.0)     // 0–5: 不吸附
            speedKmh < 15f -> Pair(30.0, 0.3)     // 5–15: 弱吸附
            speedKmh < 30f -> Pair(25.0, 0.6)     // 15–30: 中等吸附
            else           -> Pair(20.0, 1.0)     // 30+: 正常吸附
        }
    }

    private fun snapToRoadSpeedAware(lat: Double, lng: Double, speedKmh: Float): Pair<Double, Double>? {
        if (!hudView.hasRoads) return null
        val (threshold, blendRatio) = getSnapParams(speedKmh)
        if (threshold <= 0.0) return null

        var minDist = Double.MAX_VALUE
        var bestLat = lat
        var bestLng = lng

        for (segment in hudView.roadSegments) {
            val points = segment.points
            for (i in 0 until points.size - 1) {
                val (lat1, lng1) = points[i]
                val (lat2, lng2) = points[i + 1]

                val dx = lng2 - lng1
                val dy = lat2 - lat1
                val lenSq = dx * dx + dy * dy
                if (lenSq < 1e-12) continue

                val t = ((lng - lng1) * dx + (lat - lat1) * dy) / lenSq
                val clampedT = t.coerceIn(0.0, 1.0)

                val projLat = lat1 + clampedT * dy
                val projLng = lng1 + clampedT * dx

                val dist = RoadFetcher.haversine(lat, lng, projLat, projLng)
                if (dist < minDist) {
                    minDist = dist
                    bestLat = projLat
                    bestLng = projLng
                }
            }
        }

        if (minDist > threshold) return null
        val snappedLat = lat + (bestLat - lat) * blendRatio
        val snappedLng = lng + (bestLng - lng) * blendRatio
        return Pair(snappedLat, snappedLng)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        flipContainer = findViewById(R.id.flipContainer)
        permDeniedLayout = findViewById(R.id.permDeniedLayout)
        btnRetryPerm = findViewById(R.id.btnRetryPerm)
        hudView = findViewById(R.id.hudView)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        btnRetryPerm.setOnClickListener {
            permDeniedLayout.visibility = View.GONE
            requestPermissions()
        }

        startHudForegroundService()
        requestPermissions()
    }

    private fun startHudForegroundService() {
        val intent = Intent(this, HudForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // === 权限 ===
    private fun requestPermissions() {
        val perms = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (perms.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, perms, PERM_REQUEST)
        } else {
            startLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                permDeniedLayout.visibility = View.GONE
                startLocationUpdates()
            } else {
                permDeniedLayout.visibility = View.VISIBLE
                hudView.statusText = "需要定位权限"
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        locationManager.getProvider(LocationManager.GPS_PROVIDER)?.let {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 2f, this)
        }
        locationManager.getProvider(LocationManager.NETWORK_PROVIDER)?.let {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 5f, this)
        }
        val mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val acc = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val linAcc = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (mag != null && acc != null) {
            sensorManager.registerListener(this, mag, SensorManager.SENSOR_DELAY_GAME)
            sensorManager.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
            hasCompass = true
        }
        linAcc?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onLocationChanged(it) }
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { onLocationChanged(it) }
    }

    override fun onLocationChanged(location: Location) {
        val now = System.currentTimeMillis()
        gpsFixCount++
        lastGpsTime = now

        // ── GPS 抗跳变滤波 ──
        val filtered = gpsFilter.process(
            rawLat = location.latitude,
            rawLng = location.longitude,
            rawSpeedMs = location.speed,
            accuracy = location.accuracy,
            timestampMs = now
        ) ?: return

        val (filteredLat, filteredLng, filteredSpeedKmh) = filtered

        // ── 更新 GPS 目标 ──
        targetLat = filteredLat
        targetLng = filteredLng
        targetSpeed = filteredSpeedKmh

        val rawBearing = when {
            location.hasBearing() && location.speed > 1f -> location.bearing
            hasCompass -> smoothedCompassBearing  // 使用平滑后的指南针
            else -> 0f
        }
        targetBearing = if (vehicleLat == 0.0) rawBearing
                        else circularShortest(vehicleBearing, rawBearing)

        // ── GPS 到达：瞬移到校正位置（无追逐、无指数平滑） ──
        if (vehicleLat == 0.0) {
            // 首次定位
            vehicleLat = targetLat
            vehicleLng = targetLng
            vehicleBearing = targetBearing
            lastFrameTime = now
        } else {
            // 后续 GPS：瞬移修正
            vehicleLat = targetLat
            vehicleLng = targetLng
        }

        // ── 惯导速度锁定到 GPS 速度 ──
        imuSpeedKmh = targetSpeed

        // ── 重置加速度平滑（GPS 来了，惯导重新校准） ──
        smoothedWorldAcc[0] = 0f
        smoothedWorldAcc[1] = 0f
        smoothedWorldAcc[2] = 0f

        hudView.vehicleLat = vehicleLat
        hudView.vehicleLng = vehicleLng
        hudView.vehicleBearing = vehicleBearing
        hudView.vehicleSpeed = targetSpeed

        tryFetchRoads()
    }

    private fun tryFetchRoads() {
        if (targetLat == 0.0) return
        val now = System.currentTimeMillis()
        val dist = RoadFetcher.haversine(targetLat, targetLng, lastRoadFetchLat, lastRoadFetchLng)
        val timeSince = now - lastRoadFetchTime

        if (dist > ROAD_FETCH_DIST || (timeSince > ROAD_FETCH_INTERVAL && !RoadFetcher.isCacheValid(targetLat, targetLng))) {
            lastRoadFetchLat = targetLat; lastRoadFetchLng = targetLng
            lastRoadFetchTime = now

            roadFetchJob?.cancel()
            roadFetchJob = scope.launch {
                val segments = RoadFetcher.fetchRoads(targetLat, targetLng)
                hudView.setRoads(segments, targetLat, targetLng)
                Log.i(TAG, "Roads: ${segments.size} segments")
            }
        }
    }

    // === 60fps 渲染 + IMU 惯导推进 ===
    private fun updateFrame() {
        if (targetLat == 0.0) return

        val now = System.currentTimeMillis()
        val dt = if (lastFrameTime == 0L) 16.0 else (now - lastFrameTime).toDouble().coerceIn(1.0, 100.0)
        lastFrameTime = now

        // ── IMU 惯导推进（GPS 间隔内用指南针航向 + 惯导速度推算位置） ──
        propagateInertial(dt)

        // ── 方向处理（抗抖动核心） ──
        val newBearing = when {
            // GPS 速度足够 → 强制用 GPS 航向（比指南针稳定得多）
            targetSpeed > GPS_BEARING_SPEED -> targetBearing
            // 有指南针 → 用 EMA 平滑后的指南针 + 死区过滤
            hasCompass -> {
                val diff = ((smoothedCompassBearing - vehicleBearing + 540f) % 360f) - 180f
                if (abs(diff) < HEADING_DEAD_ZONE) vehicleBearing  // 死区内：不动
                else vehicleBearing + diff * 0.5f  // 死区外：半速过渡（进一步平滑）
            }
            else -> vehicleBearing
        }
        vehicleBearing = ((newBearing % 360f) + 360f) % 360f

        // ── 道路吸附 ──
        val snapped = snapToRoadSpeedAware(vehicleLat, vehicleLng, targetSpeed)
        if (snapped != null) {
            hudView.snappedLat = snapped.first
            hudView.snappedLng = snapped.second
            hudView.isSnapped = true
            hudView.vehicleLat = snapped.first
            hudView.vehicleLng = snapped.second
        } else {
            hudView.isSnapped = false
            hudView.vehicleLat = vehicleLat
            hudView.vehicleLng = vehicleLng
        }

        hudView.vehicleBearing = vehicleBearing
        hudView.vehicleSpeed = targetSpeed
        hudView.invalidate()
    }

    /**
     * IMU 惯导推进
     *
     * GPS 每 500ms~1s 来一次，中间用惯导填充：
     * - 航向：EMA 平滑后的指南针 + 死区过滤
     * - 速度：GPS 到达时锁定，GPS 丢失后指数衰减
     * - 加速度计：检测运动状态，静止时冻结航向和位置
     */
    private fun propagateInertial(dt: Double) {
        val speedKmh = imuSpeedKmh
        val speedMs = speedKmh / 3.6

        // ── 加速度计运动检测 ──
        val accMag = sqrt(
            worldAcc[0] * worldAcc[0] +
            worldAcc[1] * worldAcc[1] +
            worldAcc[2] * worldAcc[2]
        )
        val isStationary = accMag < FREEZE_ACC_THRESHOLD && speedKmh < FREEZE_SPEED_THRESHOLD

        // 静止时不推进（避免指南针抖动导致位置漂移）
        if (isStationary) return

        // 航向使用 EMA 平滑后的指南针（不是原始值）
        val heading = if (hasCompass) smoothedCompassBearing else vehicleBearing
        val headingRad = Math.toRadians(heading.toDouble())

        // 位移 = 速度 × 时间
        val distMeters = speedMs * (dt / 1000.0)

        // 经纬度增量（小角度近似）
        val dLat = distMeters * cos(headingRad) / 111111.0
        val dLng = distMeters * sin(headingRad) / (111111.0 * cos(Math.toRadians(vehicleLat)))

        vehicleLat += dLat
        vehicleLng += dLng

        // ── GPS 丢失后速度自然衰减（模拟减速） ──
        val timeSinceGps = System.currentTimeMillis() - lastGpsTime
        if (timeSinceGps > 2000) {
            imuSpeedKmh *= VELOCITY_DECAY
        }
    }

    private fun circularShortest(from: Float, to: Float): Float {
        val diff = ((to - from + 540f) % 360f) - 180f
        return (from + diff + 360f) % 360f
    }

    // === 传感器 ===
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, accData, 0, 3)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magData, 0, 3)
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                // 线性加速度（已去除重力），旋转到世界坐标系
                val R = FloatArray(9)
                val I = FloatArray(9)
                if (SensorManager.getRotationMatrix(R, I, accData, magData)) {
                    for (i in 0..2) {
                        var sum = 0f
                        for (j in 0..2) {
                            sum += R[i * 3 + j] * event.values[j]
                        }
                        worldAcc[i] = sum
                    }
                    // EMA 平滑
                    for (i in 0..2) {
                        smoothedWorldAcc[i] += ACC_SMOOTH * (worldAcc[i] - smoothedWorldAcc[i])
                    }
                }
            }
        }

        // 指南针（从加速度计 + 磁力计计算）— EMA 平滑 + 圆形处理
        val R = FloatArray(9); val Im = FloatArray(9)
        if (SensorManager.getRotationMatrix(R, Im, accData, magData)) {
            val o = FloatArray(3); SensorManager.getOrientation(R, o)
            val rawBearing = ((Math.toDegrees(o[0].toDouble()).toFloat() + 360f) % 360f)

            if (!compassInitialized) {
                // 首次：直接赋值，不滤波
                smoothedCompassBearing = rawBearing
                compassInitialized = true
            } else {
                // 圆形 EMA：处理 359°→1° 跨越
                val diff = ((rawBearing - smoothedCompassBearing + 540f) % 360f) - 180f
                smoothedCompassBearing = ((smoothedCompassBearing + COMPASS_EMA_ALPHA * diff) + 360f) % 360f
            }
            compassBearing = rawBearing  // 保留原始值供调试，实际使用 smoothedCompassBearing
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // === 生命周期 ===
    private fun startRenderLoop() {
        if (!renderRunning) { renderRunning = true; handler.post(renderRunnable) }
    }
    private fun stopRenderLoop() {
        renderRunning = false; handler.removeCallbacks(renderRunnable)
    }

    override fun onResume() {
        super.onResume(); startRenderLoop(); startHudForegroundService()
    }
    override fun onPause() {
        stopRenderLoop(); super.onPause()
    }
    override fun onDestroy() {
        stopRenderLoop()
        locationManager.removeUpdates(this)
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
        roadFetchJob?.cancel(); scope.cancel()
        stopService(Intent(this, HudForegroundService::class.java))
        super.onDestroy()
    }
}
