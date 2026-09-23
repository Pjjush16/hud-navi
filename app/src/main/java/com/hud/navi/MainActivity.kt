package com.hud.navi

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import android.location.LocationListener
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class MainActivity : AppCompatActivity(), LocationListener, SensorEventListener {

    private lateinit var hudView: HudView
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private val handler = Handler(Looper.getMainLooper())

    // === 路网刷新 ===
    private var lastFetchLat = 0.0
    private var lastFetchLng = 0.0
    private val FETCH_DISTANCE_M = 200.0
    private var isFetching = false
    private var fetchRetryCount = 0
    private val MAX_FETCH_RETRIES = 3

    // === 磁力计 ===
    private var hasCompass = false
    private var compassBearing = 0f

    companion object {
        private const val PERM_REQUEST = 100
        private const val LOCATION_MIN_TIME_MS = 500L
        private const val LOCATION_MIN_DIST_M = 2f
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
        val gpsProvider = locationManager.getProvider(LocationManager.GPS_PROVIDER)
        if (gpsProvider != null) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, LOCATION_MIN_TIME_MS, LOCATION_MIN_DIST_M, this
            )
        }

        val netProvider = locationManager.getProvider(LocationManager.NETWORK_PROVIDER)
        if (netProvider != null) {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, LOCATION_MIN_TIME_MS * 2, LOCATION_MIN_DIST_M * 2, this
            )
        }

        val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val accSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (magSensor != null && accSensor != null) {
            sensorManager.registerListener(this, magSensor, SensorManager.SENSOR_DELAY_GAME)
            sensorManager.registerListener(this, accSensor, SensorManager.SENSOR_DELAY_GAME)
            hasCompass = true
        }

        val lastLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        lastLoc?.let { onLocationChanged(it) }
    }

    override fun onLocationChanged(location: Location) {
        val lat = location.latitude
        val lng = location.longitude

        hudView.vehicleLat = lat
        hudView.vehicleLng = lng

        if (location.hasBearing() && location.speed > 1f) {
            hudView.vehicleBearing = location.bearing
        } else if (hasCompass) {
            hudView.vehicleBearing = compassBearing
        }

        hudView.vehicleSpeed = location.speed * 3.6f
        hudView.invalidate()

        checkRoadRefresh(lat, lng)
    }

    private fun checkRoadRefresh(lat: Double, lng: Double) {
        if (isFetching) return

        val dist = RoadFetcher.haversine(lastFetchLat, lastFetchLng, lat, lng)
        if (dist > FETCH_DISTANCE_M || (lastFetchLat == 0.0 && lat != 0.0)) {
            isFetching = true
            lastFetchLat = lat
            lastFetchLng = lng
            fetchRetryCount = 0

            hudView.statusText = "加载路网中..."
            hudView.invalidate()

            doFetchRoads(lat, lng)
        }
    }

    private fun doFetchRoads(lat: Double, lng: Double) {
        Thread {
            val result = RoadFetcher.fetch(lat, lng)
            handler.post {
                hudView.roads = result.segments

                when (result.status) {
                    FetchStatus.SUCCESS -> {
                        hudView.statusText = "路网: ${result.segments.size} 段"
                        fetchRetryCount = 0
                    }
                    FetchStatus.EMPTY -> {
                        hudView.statusText = "该区域无道路数据"
                        fetchRetryCount = 0
                    }
                    FetchStatus.ALL_FAILED -> {
                        hudView.statusText = "路网加载失败 (重试 ${fetchRetryCount}/${MAX_FETCH_RETRIES})"
                        // 自动重试
                        if (fetchRetryCount < MAX_FETCH_RETRIES) {
                            fetchRetryCount++
                            handler.postDelayed({ doFetchRoads(lat, lng) }, 3000)
                        }
                    }
                    else -> {
                        hudView.statusText = "路网错误: ${result.message}"
                    }
                }

                hudView.invalidate()
                isFetching = false
            }
        }.start()
    }

    // === 磁力计 ===
    private val accelerometer = FloatArray(3)
    private val magnetometer = FloatArray(3)

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> System.arraycopy(event.values, 0, accelerometer, 0, 3)
            Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, magnetometer, 0, 3)
        }
        val R = FloatArray(9)
        val I = FloatArray(9)
        if (SensorManager.getRotationMatrix(R, I, accelerometer, magnetometer)) {
            val orientation = FloatArray(3)
            SensorManager.getOrientation(R, orientation)
            compassBearing = ((Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onResume() {
        super.onResume()
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
