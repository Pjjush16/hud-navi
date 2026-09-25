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
import android.widget.ImageView
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
import kotlin.math.abs

/**
 * HUD 导航 v7.0 — 自研引擎 + 标准上帝视角
 *
 * 架构变化（v6.x → v7.0）:
 * - 移除 MapLibre GL 依赖，纯自研 Canvas 2D 引擎
 * - 倾斜透视（45°/60°）→ 标准上帝视角（正上方俯视）
 * - 保留 Overpass API 路网数据
 * - 地图随航向旋转，车辆始终居中朝上
 *
 * 保留的 P0 功能:
 * 1. HUD 镜像翻转（挡风玻璃投影）
 * 2. 屏幕常亮 + 前台服务
 * 3. 权限拒绝提示 + 重试
 * 4. 状态文本实时显示
 */
class MainActivity : AppCompatActivity(), LocationListener, SensorEventListener {

    private lateinit var hudView: HudView
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private val handler = Handler(Looper.getMainLooper())

    // === UI ===
    private lateinit var flipContainer: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var speedText: TextView
    private lateinit var btnMirror: TextView
    private lateinit var btnZoomIn: TextView
    private lateinit var btnZoomOut: TextView
    private lateinit var zoomLevel: TextView
    private lateinit var directionArrow: ImageView
    private lateinit var permDeniedLayout: LinearLayout
    private lateinit var btnRetryPerm: TextView

    private var mirrorEnabled = false
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
        private const val ROAD_FETCH_DIST = 150.0 // 距上次获取超过此距离才刷新（米）
        private const val ROAD_FETCH_INTERVAL = 5000L // 最小刷新间隔
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        // 绑定 UI
        flipContainer = findViewById(R.id.flipContainer)
        statusText = findViewById(R.id.statusText)
        speedText = findViewById(R.id.speedText)
        btnMirror = findViewById(R.id.btnMirror)
        btnZoomIn = findViewById(R.id.btnZoomIn)
        btnZoomOut = findViewById(R.id.btnZoomOut)
        zoomLevel = findViewById(R.id.zoomLevel)
        directionArrow = findViewById(R.id.directionArrow)
        permDeniedLayout = findViewById(R.id.permDeniedLayout)
        btnRetryPerm = findViewById(R.id.btnRetryPerm)
        hudView = findViewById(R.id.hudView)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        // 事件绑定
        btnMirror.setOnClickListener { toggleMirror() }
        btnZoomIn.setOnClickListener { hudView.zoomLevel++; zoomLevel.text = "${hudView.zoomLevel}" }
        btnZoomOut.setOnClickListener { hudView.zoomLevel--; zoomLevel.text = "${hudView.zoomLevel}" }
        btnRetryPerm.setOnClickListener {
            permDeniedLayout.visibility = View.GONE
            requestPermissions()
        }

        startHudForegroundService()
        requestPermissions()
    }

    // === P0-1: HUD 镜像翻转 ===
    private fun toggleMirror() {
        mirrorEnabled = !mirrorEnabled
        flipContainer.scaleY = if (mirrorEnabled) -1f else 1f
        btnMirror.alpha = if (mirrorEnabled) 1.0f else 0.6f
        updateStatusText()
    }

    // === P0-2: 前台服务 ===
    private fun startHudForegroundService() {
        val intent = Intent(this, HudForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // === P0-5: 状态文本 ===
    private fun updateStatusText() {
        val gpsAge = if (lastGpsTime > 0) (System.currentTimeMillis() - lastGpsTime) / 1000 else -1L
        val parts = mutableListOf<String>()

        when {
            currLat == 0.0 -> parts.add("等待GPS定位...")
            gpsAge > 10 -> parts.add("GPS丢失 (${gpsAge}s)")
            gpsAge > 5 -> parts.add("GPS弱 (${gpsAge}s)")
            else -> parts.add("GPS正常 (${gpsFixCount}次)")
        }

        parts.add("${currSpeed.toInt()} km/h")
        val b = if (hasCompass) compassBearing.toInt() else currBearing.toInt()
        parts.add("航向 ${b}°")
        parts.add("Z${hudView.zoomLevel}")
        if (mirrorEnabled) parts.add("镜像")

        statusText.text = parts.joinToString(" | ")
    }

    // === 权限管理 ===
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
                statusText.text = "需要定位权限才能使用 HUD 导航"
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
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            hasCompass = true
        }
        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onLocationChanged(it) }
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { onLocationChanged(it) }
    }

    // === GPS 回调 ===
    override fun onLocationChanged(location: Location) {
        val now = System.currentTimeMillis()
        gpsFixCount++
        lastGpsTime = now

        if (currLat != 0.0) {
            prevLat = currLat; prevLng = currLng
            prevBearing = currBearing; prevSpeed = currSpeed; prevTime = currTime
        }
        currLat = location.latitude
        currLng = location.longitude
        currSpeed = location.speed * 3.6f
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
        }

        speedText.text = "${currSpeed.toInt()} km/h"
        updateStatusText()

        // 触发路网刷新
        tryFetchRoads()
    }

    /**
     * 智能路网刷新：距离超过阈值或时间超过间隔才请求
     */
    private fun tryFetchRoads() {
        if (currLat == 0.0) return
        val now = System.currentTimeMillis()
        val dist = RoadFetcher.haversine(currLat, currLng, lastRoadFetchLat, lastRoadFetchLng)
        val timeSince = now - lastRoadFetchTime

        if (dist > ROAD_FETCH_DIST || (timeSince > ROAD_FETCH_INTERVAL && !RoadFetcher.isCacheValid(currLat, currLng))) {
            lastRoadFetchLat = currLat
            lastRoadFetchLng = currLng
            lastRoadFetchTime = now

            roadFetchJob?.cancel()
            roadFetchJob = scope.launch {
                val segments = RoadFetcher.fetchRoads(currLat, currLng)
                hudView.setRoads(segments, currLat, currLng)
                Log.i(TAG, "Roads updated: ${segments.size} segments")
            }
        }
    }

    // === 60fps 渲染帧 ===
    private fun updateFrame() {
        if (currLat == 0.0) return

        val elapsed = System.currentTimeMillis() - currTime
        val (iLat, iLng, iBearing) = if (prevLat == 0.0 || elapsed >= INTERP_MS) {
            Triple(currLat, currLng, currBearing)
        } else {
            val t = (elapsed.toFloat() / INTERP_MS).coerceIn(0f, 1f)
            val et = 1f - (1f - t) * (1f - t) // easeOut
            Triple(
                prevLat + (currLat - prevLat) * et,
                prevLng + (currLng - prevLng) * et,
                circularLerp(prevBearing, currBearing, et)
            )
        }

        hudView.vehicleLat = iLat
        hudView.vehicleLng = iLng
        hudView.vehicleBearing = iBearing
        hudView.vehicleSpeed = currSpeed
        hudView.invalidate()

        // 状态文本定时刷新（GPS 断档时）
        if (System.currentTimeMillis() - lastGpsTime > 3000 && gpsFixCount > 0) {
            updateStatusText()
        }
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
        super.onResume()
        startRenderLoop()
        startHudForegroundService()
    }

    override fun onPause() {
        stopRenderLoop()
        super.onPause()
    }

    override fun onDestroy() {
        stopRenderLoop()
        locationManager.removeUpdates(this)
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
        roadFetchJob?.cancel()
        scope.cancel()
        stopService(Intent(this, HudForegroundService::class.java))
        super.onDestroy()
    }
}
