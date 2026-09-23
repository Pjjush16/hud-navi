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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlin.math.*

/**
 * HUD 导航 v3.0 — 纯矢量路网 + 黑底 + 45° 透视
 * 无任何瓦片地图，专为挡风玻璃 HUD 设计
 */
class MainActivity : AppCompatActivity(), LocationListener, SensorEventListener {

    private lateinit var hudView: HudView
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private val handler = Handler(Looper.getMainLooper())

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
        val lat = location.latitude
        val lng = location.longitude

        hudView.vehicleLat = lat
        hudView.vehicleLng = lng
        hudView.vehicleSpeed = location.speed * 3.6f

        // 航向：GPS bearing 优先，磁力计备用
        val bearing = if (location.hasBearing() && location.speed > 1f) {
            location.bearing
        } else if (hasCompass) {
            compassBearing
        } else {
            0f
        }
        hudView.vehicleBearing = bearing

        hudView.invalidate()

        // 路网刷新
        checkRoadRefresh(lat, lng)
    }

    private fun checkRoadRefresh(lat: Double, lng: Double) {
        if (isFetching) return
        val dist = haversine(lastFetchLat, lastFetchLng, lat, lng)
        if (dist > FETCH_DISTANCE_M || (lastFetchLat == 0.0 && lat != 0.0)) {
            isFetching = true
            lastFetchLat = lat; lastFetchLng = lng
            hudView.statusText = "加载路网..."
            hudView.invalidate()

            Thread {
                val result = RoadFetcher.fetch(lat, lng)
                handler.post {
                    hudView.roads = result.segments
                    hudView.statusText = when (result.status) {
                        FetchStatus.SUCCESS -> "路网: ${result.segments.size} 段"
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

    override fun onResume() { super.onResume() }
    override fun onPause() { super.onPause() }
    override fun onDestroy() {
        super.onDestroy()
        locationManager.removeUpdates(this)
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
    }
}
