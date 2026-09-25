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
 * HUD 导航 v8.1 — 极简 HUD + GPS 抗跳变
 *
 * v8.0 → v8.1:
 * - 卡尔曼滤波：lat/lng 独立 1D Kalman，平滑噪声
 * - 最小位移阈值：< 3m 视为静止抖动，不更新位置
 * - 异常点剔除：隐含速度 > 300km/h 的跳点直接丢弃
 * - 速度 EMA 平滑：防止速度突变
 * - GPS accuracy 加权：精度差时降低卡尔曼增益
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

    // === GPS 插值 ===
    private var prevLat = 0.0; private var prevLng = 0.0
    private var prevBearing = 0f; private var prevSpeed = 0f; private var prevTime = 0L
    private var currLat = 0.0; private var currLng = 0.0
    private var currBearing = 0f; private var currSpeed = 0f; private var currTime = 0L
    private val INTERP_MS = 800L
    private val BEARING_SMOOTH = 0.15f
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
        private const val SNAP_THRESHOLD_M = 20.0  // 道路吸附距离阈值（米）
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

        if (currLat != 0.0) {
            prevLat = currLat; prevLng = currLng
            prevBearing = currBearing; prevSpeed = currSpeed; prevTime = currTime
        }
        currLat = filteredLat
        currLng = filteredLng
        currSpeed = filteredSpeedKmh
        val rawBearing = when {
            location.hasBearing() && location.speed > 1f -> location.bearing
            hasCompass -> compassBearing
            else -> 0f
        }
        currBearing = if (currLat == 0.0) rawBearing
                      else circularLerp(currBearing, rawBearing, BEARING_SMOOTH)
        currTime = now

        if (prevLat == 0.0) {
            prevLat = currLat; prevLng = currLng
            prevBearing = currBearing; prevSpeed = currSpeed; prevTime = currTime
            hudView.vehicleLat = currLat; hudView.vehicleLng = currLng
            hudView.vehicleBearing = currBearing; hudView.vehicleSpeed = currSpeed
        }

        tryFetchRoads()
    }

    private fun tryFetchRoads() {
        if (currLat == 0.0) return
        val now = System.currentTimeMillis()
        val dist = RoadFetcher.haversine(currLat, currLng, lastRoadFetchLat, lastRoadFetchLng)
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

    /**
     * 道路吸附：找到最近的道路段，将车辆位置投影到该段上
     */
    private fun snapToRoad(lat: Double, lng: Double): Pair<Double, Double>? {
        if (!hudView.hasRoads) return null

        var minDist = Double.MAX_VALUE
        var bestLat = lat
        var bestLng = lng

        // 遍历所有路段，找最近点
        for (segment in hudView.roadSegments) {
            val points = segment.points
            for (i in 0 until points.size - 1) {
                val (lat1, lng1) = points[i]
                val (lat2, lng2) = points[i + 1]

                // 计算点到线段的投影
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

        return if (minDist <= SNAP_THRESHOLD_M) Pair(bestLat, bestLng) else null
    }

    // === 60fps 渲染 ===
    private fun updateFrame() {
        if (currLat == 0.0) return

        val elapsed = System.currentTimeMillis() - currTime
        val (iLat, iLng, iBearing) = if (prevLat == 0.0 || elapsed >= INTERP_MS) {
            Triple(currLat, currLng, currBearing)
        } else {
            val t = (elapsed.toFloat() / INTERP_MS).coerceIn(0f, 1f)
            val et = 1f - (1f - t) * (1f - t)
            Triple(
                prevLat + (currLat - prevLat) * et,
                prevLng + (currLng - prevLng) * et,
                circularLerp(prevBearing, currBearing, et)
            )
        }

        // 道路吸附
        val snapped = snapToRoad(iLat, iLng)
        if (snapped != null) {
            hudView.snappedLat = snapped.first
            hudView.snappedLng = snapped.second
            hudView.isSnapped = true
            hudView.vehicleLat = snapped.first
            hudView.vehicleLng = snapped.second
        } else {
            hudView.isSnapped = false
            hudView.vehicleLat = iLat
            hudView.vehicleLng = iLng
        }

        hudView.vehicleBearing = iBearing
        hudView.vehicleSpeed = currSpeed
        hudView.invalidate()
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
