package com.hud.navi

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlin.math.*

/**
 * HUD 导航 v4.0 — 纯矢量路网 + 黑底 + 45° 透视 + 插值平滑
 * 无任何瓦片地图，专为挡风玻璃 HUD 设计
 *
 * 插值引擎：
 * - 位置：在两次 GPS 更新之间做线性插值，消除跳点
 * - 方向：圆形插值（slerp），避免 359°→1° 走 180° 的问题
 * - 渲染：60fps 定时器驱动，GPS 更新间隔内平滑过渡
 */
class MainActivity : AppCompatActivity(), LocationListener, SensorEventListener {

    private lateinit var hudView: HudView
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private val handler = Handler(Looper.getMainLooper())

    // === 插值引擎 ===
    // 上一次 GPS 原始数据
    private var prevLat = 0.0
    private var prevLng = 0.0
    private var prevBearing = 0f
    private var prevSpeed = 0f
    private var prevTime = 0L

    // 最新一次 GPS 原始数据
    private var currLat = 0.0
    private var currLng = 0.0
    private var currBearing = 0f
    private var currSpeed = 0f
    private var currTime = 0L

    // 插值参数
    private val INTERP_DURATION_MS = 800L  // 插值过渡时间（毫秒）
    private val BEARING_SMOOTH_FACTOR = 0.15f  // 航向低通滤波系数（越小越平滑）

    // 60fps 渲染循环
    private val FRAME_INTERVAL_MS = 16L  // ~60fps
    private val renderRunnable = object : Runnable {
        override fun run() {
            updateInterpolation()
            hudView.invalidate()
            handler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }
    private var renderRunning = false

    // 路网刷新
    private var lastFetchLat = 0.0
    private var lastFetchLng = 0.0
    private val FETCH_DISTANCE_M = 300.0
    private var isFetching = false

    // 磁力计
    private var hasCompass = false
    private var compassBearing = 0f
    private val accelerometer = FloatArray(3)
    private val magnetometer = FloatArray(3)

    companion object {
        private const val PERM_REQUEST = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hudView = findViewById(R.id.hudView)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        requestPermissions()

        // 初始化路网缓存
        RoadCache.init(this)
    }

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
        if (requestCode == PERM_REQUEST && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        locationManager.getProvider(LocationManager.GPS_PROVIDER)?.let {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 500L, 2f, this
            )
        }
        locationManager.getProvider(LocationManager.NETWORK_PROVIDER)?.let {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 1000L, 5f, this
            )
        }

        // 磁力计
        val mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val acc = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (mag != null && acc != null) {
            sensorManager.registerListener(this, mag, SensorManager.SENSOR_DELAY_GAME)
            sensorManager.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
            hasCompass = true
        }

        // 尝试最后已知位置
        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onLocationChanged(it) }
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { onLocationChanged(it) }
    }

    override fun onLocationChanged(location: Location) {
        val now = System.currentTimeMillis()
        val lat = location.latitude
        val lng = location.longitude

        // 保存前一次作为插值起点
        if (currLat != 0.0) {
            prevLat = currLat
            prevLng = currLng
            prevBearing = currBearing
            prevSpeed = currSpeed
            prevTime = currTime
        }

        // 更新最新一次
        currLat = lat
        currLng = lng
        currSpeed = location.speed * 3.6f

        // 航向：GPS bearing 优先，磁力计备用
        val rawBearing = if (location.hasBearing() && location.speed > 1f) {
            location.bearing
        } else if (hasCompass) {
            compassBearing
        } else {
            0f
        }

        // 航向低通滤波（圆形平滑）
        if (currLat == 0.0) {
            currBearing = rawBearing
        } else {
            currBearing = circularLerp(currBearing, rawBearing, BEARING_SMOOTH_FACTOR)
        }

        currTime = now

        // 如果是首次定位，立即设置
        if (prevLat == 0.0) {
            hudView.vehicleLat = lat
            hudView.vehicleLng = lng
            hudView.vehicleBearing = currBearing
            hudView.vehicleSpeed = currSpeed
        }

        // 路网刷新
        checkRoadRefresh(lat, lng)
    }

    /**
     * 插值更新（每帧调用）
     * 在两次 GPS 更新之间平滑过渡位置和方向
     */
    private fun updateInterpolation() {
        if (currLat == 0.0) return

        val now = System.currentTimeMillis()
        val elapsed = now - currTime

        if (prevLat == 0.0 || elapsed >= INTERP_DURATION_MS) {
            // 没有前一次数据，或已超过插值窗口，直接用最新值
            hudView.vehicleLat = currLat
            hudView.vehicleLng = currLng
            hudView.vehicleBearing = currBearing
            hudView.vehicleSpeed = currSpeed
        } else {
            // 在插值窗口内，做平滑过渡
            val t = (elapsed.toFloat() / INTERP_DURATION_MS).coerceIn(0f, 1f)
            // 使用 easeOut 曲线让过渡更自然（开始快、结束慢）
            val easedT = 1f - (1f - t) * (1f - t)

            // 位置线性插值
            hudView.vehicleLat = prevLat + (currLat - prevLat) * easedT
            hudView.vehicleLng = prevLng + (currLng - prevLng) * easedT

            // 方向圆形插值
            hudView.vehicleBearing = circularLerp(prevBearing, currBearing, easedT)

            // 速度线性插值
            hudView.vehicleSpeed = prevSpeed + (currSpeed - prevSpeed) * easedT
        }
    }

    /**
     * 圆形插值（角度专用）
     * 处理 359°→1° 不走 180° 的问题
     */
    private fun circularLerp(from: Float, to: Float, t: Float): Float {
        var diff = ((to - from + 540f) % 360f) - 180f  // 归一化到 [-180, 180]
        return (from + diff * t + 360f) % 360f
    }

    private fun checkRoadRefresh(lat: Double, lng: Double) {
        if (isFetching) return
        val dist = haversine(lastFetchLat, lastFetchLng, lat, lng)
        if (dist > FETCH_DISTANCE_M || (lastFetchLat == 0.0 && lat != 0.0)) {
            lastFetchLat = lat; lastFetchLng = lng

            // 1. 先查缓存
            val cached = RoadCache.get(lat, lng)
            if (cached != null) {
                hudView.roads = cached
                hudView.statusText = "${RoadCache.stats()}"
                hudView.invalidate()
                Log.d("MainActivity", "路网缓存命中: ${cached.size} 段")

                // 缓存命中但仍可在后台静默刷新（不阻塞渲染）
                silentBackgroundRefresh(lat, lng)
                return
            }

            // 2. 缓存未命中，走网络
            isFetching = true
            hudView.statusText = "加载路网..."
            hudView.invalidate()

            Thread {
                val result = RoadFetcher.fetch(lat, lng)
                handler.post {
                    if (result.status == FetchStatus.SUCCESS) {
                        // 写入缓存
                        RoadCache.put(lat, lng, result.segments)
                    }
                    hudView.roads = result.segments
                    hudView.statusText = when (result.status) {
                        FetchStatus.SUCCESS -> "路网: ${result.segments.size} 段 (${RoadCache.stats()})"
                        FetchStatus.EMPTY -> "该区域无道路"
                        FetchStatus.ALL_FAILED -> "路网加载失败"
                        else -> result.message
                    }
                    hudView.invalidate()
                    isFetching = false
                }
            }.start()
        }
    }

    /**
     * 缓存命中后的后台静默刷新：不阻塞当前渲染，后台更新缓存
     */
    private fun silentBackgroundRefresh(lat: Double, lng: Double) {
        Thread {
            try {
                val result = RoadFetcher.fetch(lat, lng)
                if (result.status == FetchStatus.SUCCESS) {
                    RoadCache.put(lat, lng, result.segments)
                    handler.post {
                        hudView.roads = result.segments
                        hudView.statusText = "${RoadCache.stats()}"
                        hudView.invalidate()
                    }
                }
                // 定期清理过期缓存
                RoadCache.cleanup()
            } catch (e: Exception) {
                Log.w("MainActivity", "静默刷新失败（不影响缓存）: ${e.message}")
            }
        }.start()
    }

    // === 磁力计 ===
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> System.arraycopy(event.values, 0, accelerometer, 0, 3)
            Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, magnetometer, 0, 3)
        }
        val R = FloatArray(9); val I = FloatArray(9)
        if (SensorManager.getRotationMatrix(R, I, accelerometer, magnetometer)) {
            val o = FloatArray(3); SensorManager.getOrientation(R, o)
            compassBearing = ((Math.toDegrees(o[0].toDouble()).toFloat() + 360f) % 360f)
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat/2)*sin(dLat/2) + cos(Math.toRadians(lat1))*cos(Math.toRadians(lat2))*sin(dLng/2)*sin(dLng/2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun startRenderLoop() {
        if (!renderRunning) {
            renderRunning = true
            handler.post(renderRunnable)
        }
    }

    private fun stopRenderLoop() {
        renderRunning = false
        handler.removeCallbacks(renderRunnable)
    }

    override fun onResume() {
        super.onResume()
        startRenderLoop()
    }

    override fun onPause() {
        super.onPause()
        stopRenderLoop()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRenderLoop()
        locationManager.removeUpdates(this)
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
    }
}
