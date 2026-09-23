package com.hud.navi

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import android.location.LocationListener
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * HUD 导航主界面
 *
 * 功能：
 * 1. GPS 定位 + 航向跟踪
 * 2. 磁力计获取方向
 * 3. Overpass API 获取矢量路网
 * 4. 45 度透视投影渲染
 */
class MainActivity : AppCompatActivity(), LocationListener, SensorEventListener {

    private lateinit var hudView: HudView
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager

    private val handler = Handler(Looper.getMainLooper())

    // === 路网刷新 ===
    private var lastFetchLat = 0.0
    private var lastFetchLng = 0.0
    private val FETCH_DISTANCE_M = 200.0  // 移动 200m 后重新获取路网
    private var isFetching = false

    // === 磁力计 ===
    private var hasCompass = false
    private var compassBearing = 0f

    companion object {
        private const val PERM_REQUEST = 100
        private const val LOCATION_MIN_TIME_MS = 500L    // 500ms 更新一次
        private const val LOCATION_MIN_DIST_M = 2f       // 2 米更新一次
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hudView = findViewById(R.id.hudView)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        requestPermissions()
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
        // GPS 定位
        val gpsProvider = locationManager.getProvider(LocationManager.GPS_PROVIDER)
        if (gpsProvider != null) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LOCATION_MIN_TIME_MS,
                LOCATION_MIN_DIST_M,
                this
            )
        }

        // 网络定位（备用）
        val netProvider = locationManager.getProvider(LocationManager.NETWORK_PROVIDER)
        if (netProvider != null) {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                LOCATION_MIN_TIME_MS * 2,
                LOCATION_MIN_DIST_M * 2,
                this
            )
        }

        // 磁力计
        val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val accSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (magSensor != null && accSensor != null) {
            sensorManager.registerListener(this, magSensor, SensorManager.SENSOR_DELAY_GAME)
            sensorManager.registerListener(this, accSensor, SensorManager.SENSOR_DELAY_GAME)
            hasCompass = true
        }

        // 尝试获取最后已知位置
        val lastLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        lastLoc?.let { onLocationChanged(it) }
    }

    override fun onLocationChanged(location: Location) {
        val lat = location.latitude
        val lng = location.longitude

        // 更新 HUD 车辆状态
        hudView.vehicleLat = lat
        hudView.vehicleLng = lng

        // 航向：优先用 GPS bearing（运动时有值），否则用磁力计
        if (location.hasBearing() && location.speed > 1f) {
            hudView.vehicleBearing = location.bearing
        } else if (hasCompass) {
            hudView.vehicleBearing = compassBearing
        }

        // 速度（m/s → km/h）
        hudView.vehicleSpeed = location.speed * 3.6f

        // 触发重绘
        hudView.invalidate()

        // 检查是否需要刷新路网
        checkRoadRefresh(lat, lng)
    }

    /**
     * 检查是否需要重新获取路网数据
     */
    private fun checkRoadRefresh(lat: Double, lng: Double) {
        if (isFetching) return

        val dist = RoadFetcher.haversine(lastFetchLat, lastFetchLng, lat, lng)
        if (dist > FETCH_DISTANCE_M || (lastFetchLat == 0.0 && lat != 0.0)) {
            isFetching = true
            lastFetchLat = lat
            lastFetchLng = lng

            // 后台线程获取路网
            Thread {
                val newRoads = RoadFetcher.fetch(lat, lng)
                handler.post {
                    hudView.roads = newRoads
                    hudView.invalidate()
                    isFetching = false
                }
            }.start()
        }
    }

    // === 磁力计处理 ===
    private val accelerometer = FloatArray(3)
    private val magnetometer = FloatArray(3)

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, accelerometer, 0, 3)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magnetometer, 0, 3)
            }
        }

        // 计算方位角
        val R = FloatArray(9)
        val I = FloatArray(9)
        if (SensorManager.getRotationMatrix(R, I, accelerometer, magnetometer)) {
            val orientation = FloatArray(3)
            SensorManager.getOrientation(R, orientation)
            // orientation[0] = azimuth（弧度），转为角度
            val azimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
            compassBearing = ((azimuthDeg + 360f) % 360f)

            // 仅在没有 GPS bearing 时使用磁力计方向
            // (GPS bearing 在运动中更准确)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onResume() {
        super.onResume()
        // 重新注册监听
        startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        locationManager.removeUpdates(this)
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        locationManager.removeUpdates(this)
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
    }
}
