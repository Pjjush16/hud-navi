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
 * HUD 导航 v9.0 — 追逐式插值 + GPS 抗跳变 + 速度分级吸附
 *
 * v8.2 → v9.0:
 * - 插值方法从"固定时长"改为"追逐式"（指数衰减）：
 *   显示位置持续向 GPS 目标追过去，新数据来了只更新目标不重置动画
 *   追到了就停，没追到新目标来了就接着追
 *   半衰期 400ms：每 400ms 追过剩余距离的一半
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

    // === GPS 目标（来自滤波器，onLocationChanged 更新） ===
    private var targetLat = 0.0; private var targetLng = 0.0
    private var targetBearing = 0f; private var targetSpeed = 0f

    // === 显示位置（渲染循环维护，追逐式插值） ===
    private var displayLat = 0.0; private var displayLng = 0.0
    private var displayBearing = 0f
    private var lastFrameTime = 0L
    private val CHASE_HALF_LIFE_MS = 400.0  // 每 400ms 追过剩余距离的一半
    private val FRAME_MS = 16L

    // === 状态 ===
    private var gpsFixCount = 0
    private var lastGpsTime = 0L
    private var lastRoadFetchLat = 0.0
    private var lastRoadFetchLng = 0.0
    private var lastRoadFetchTime = 0L

    // === 传感器 ===
    private var hasCompass = false
    private var compassBearing = 0f
    private val accData = FloatArray(3)
    private val magData = FloatArray(3)

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
    }

    // === 速度分级吸附参数 ===
    // (阈值 m, 混合比例 0~1)
    private fun getSnapParams(speedKmh: Float): Pair<Double, Double> {
        return when {
            speedKmh < 5f  -> Pair(0.0, 0.0)     // 0–5: 不吸附
            speedKmh < 15f -> Pair(30.0, 0.3)     // 5–15: 弱吸附（30m 内，仅偏移 30%）
            speedKmh < 30f -> Pair(25.0, 0.6)     // 15–30: 中等吸附
            else           -> Pair(20.0, 1.0)     // 30+: 正常吸附
        }
    }

    /**
     * 速度分级道路吸附
     * @return 吸附后的 (lat, lng)，null 表示不吸附
     */
    private fun snapToRoadSpeedAware(lat: Double, lng: Double, speedKmh: Float): Pair<Double, Double>? {
        if (!hudView.hasRoads) return null
        val (threshold, blendRatio) = getSnapParams(speedKmh)
        if (threshold <= 0.0) return null  // 0–5 km/h: 不吸附

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

        // 混合吸附：blendRatio=1.0 完全贴路，blendRatio=0.3 只修正 30%
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
        if (mag != null && acc != null) {
            sensorManager.registerListener(this, mag, SensorManager.SENSOR_DELAY_GAME)
            sensorManager.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
            hasCompass = true
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
        ) ?: return  // 被异常点剔除，直接丢弃

        val (filteredLat, filteredLng, filteredSpeedKmh) = filtered

        // ── 只更新目标位置，不碰显示位置 ──
        targetLat = filteredLat
        targetLng = filteredLng
        targetSpeed = filteredSpeedKmh

        val rawBearing = when {
            location.hasBearing() && location.speed > 1f -> location.bearing
            hasCompass -> compassBearing
            else -> 0f
        }
        targetBearing = if (targetLat == 0.0) rawBearing
                        else circularShortest(targetBearing, rawBearing)

        // ── 首次定位：显示位置直接跳到目标 ──
        if (displayLat == 0.0) {
            displayLat = targetLat
            displayLng = targetLng
            displayBearing = targetBearing
            lastFrameTime = now
            hudView.vehicleLat = displayLat
            hudView.vehicleLng = displayLng
            hudView.vehicleBearing = displayBearing
            hudView.vehicleSpeed = targetSpeed
        }

        tryFetchRoads()
    }

    private fun tryFetchRoads() {
        if (targetLat == 0.0) return
        val now = System.currentTimeMillis()
        val dist = RoadFetcher.haversine(targetLat, targetLng, lastRoadFetchLat, lastRoadFetchLng)
        val timeSince = now - lastRoadFetchTime

        if (dist > ROAD_FETCH_DIST || (timeSince > ROAD_FETCH_INTERVAL && !RoadFetcher.isCacheValid(currLat, currLng))) {
            lastRoadFetchLat = currLat; lastRoadFetchLng = currLng
            lastRoadFetchTime = now

            roadFetchJob?.cancel()
            roadFetchJob = scope.launch {
                val segments = RoadFetcher.fetchRoads(currLat, currLng)
                hudView.setRoads(segments, currLat, currLng)
                Log.i(TAG, "Roads: ${segments.size} segments")
            }
        }
    }

    // === 60fps 追逐式渲染 ===
    private fun updateFrame() {
        if (targetLat == 0.0) return

        val now = System.currentTimeMillis()
        val dt = if (lastFrameTime == 0L) 16.0 else (now - lastFrameTime).toDouble().coerceIn(1.0, 100.0)
        lastFrameTime = now

        // ── 指数衰减追逐 ──
        // 每 CHASE_HALF_LIFE_MS 毫秒，追过剩余距离的一半
        // factor=1.0: 瞬间到达  factor≈0: 几乎不动
        val factor = 1.0 - 2.0.pow(-dt / CHASE_HALF_LIFE_MS)

        // 位置追逐
        displayLat += (targetLat - displayLat) * factor
        displayLng += (targetLng - displayLng) * factor

        // 方向追逐（处理 359°→1° 跨越）
        val bearingDiff = ((targetBearing - displayBearing + 540f) % 360f) - 180f
        displayBearing = ((displayBearing + bearingDiff * factor.toFloat()) + 360f) % 360f

        // ── 速度分级道路吸附 ──
        val snapped = snapToRoadSpeedAware(displayLat, displayLng, targetSpeed)
        if (snapped != null) {
            hudView.snappedLat = snapped.first
            hudView.snappedLng = snapped.second
            hudView.isSnapped = true
            hudView.vehicleLat = snapped.first
            hudView.vehicleLng = snapped.second
        } else {
            hudView.isSnapped = false
            hudView.vehicleLat = displayLat
            hudView.vehicleLng = displayLng
        }

        hudView.vehicleBearing = displayBearing
        hudView.vehicleSpeed = targetSpeed
        hudView.invalidate()
    }

    /** 角度最短路径目标值（处理 359°→1° 跨越） */
    private fun circularShortest(from: Float, to: Float): Float {
        val diff = ((to - from + 540f) % 360f) - 180f
        return (from + diff + 360f) % 360f
    }

    private fun circularLerp(from: Float, to: Float, t: Float): Float {
        val diff = ((to - from + 540f) % 360f) - 180f
        return (from + diff * t + 360f) % 360f
    }

    // === 传感器 ===
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> System.arraycopy(event.values, 0, accData, 0, 3)
            Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, magData, 0, 3)
        }
        val R = FloatArray(9); val I = FloatArray(9)
        if (SensorManager.getRotationMatrix(R, I, accData, magData)) {
            val o = FloatArray(3); SensorManager.getOrientation(R, o)
            compassBearing = ((Math.toDegrees(o[0].toDouble()).toFloat() + 360f) % 360f)
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
