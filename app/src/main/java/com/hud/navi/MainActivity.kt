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
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlin.math.*

class MainActivity : AppCompatActivity(), LocationListener, SensorEventListener {

    private lateinit var map: MapView
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private lateinit var tvStatus: TextView
    private lateinit var tvRoads: TextView
    private lateinit var tvCoord: TextView
    private lateinit var tvSpeed: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var locationOverlay: MyLocationNewOverlay? = null

    // 路网
    private var roadPolylines = mutableListOf<Polyline>()
    private var lastFetchLat = 0.0
    private var lastFetchLng = 0.0
    private val FETCH_DISTANCE_M = 300.0
    private var isFetching = false

    // 磁力计
    private var hasCompass = false
    private var compassBearing = 0f
    private val accelerometer = FloatArray(3)
    private val magnetometer = FloatArray(3)

    // 地图样式
    private enum class MapStyle { SATELLITE, ROAD }
    private var currentStyle = MapStyle.SATELLITE

    // 车辆标记
    private var carMarker: Marker? = null
    private var firstFix = true

    companion object {
        private const val PERM_REQUEST = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        setContentView(R.layout.activity_main)

        map = findViewById(R.id.mapView)
        tvStatus = findViewById(R.id.tvStatus)
        tvRoads = findViewById(R.id.tvRoads)
        tvCoord = findViewById(R.id.tvCoord)
        tvSpeed = findViewById(R.id.tvSpeed)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        setupMap()
        setupButtons()
        requestPermissions()
    }

    private fun setupMap() {
        map.setTileSource(createSatelliteSource())
        map.setMultiTouchControls(true)
        map.isTilesScaledToDpi = true
        map.minZoomLevel = 3.0
        map.maxZoomLevel = 18.0
        map.setBuiltInZoomControls(false)
        map.controller.setZoom(15.0)
        map.controller.setCenter(GeoPoint(39.9, 116.4))
    }

    private fun setupButtons() {
        findViewById<TextView>(R.id.btnSatellite).setOnClickListener { switchStyle(MapStyle.SATELLITE) }
        findViewById<TextView>(R.id.btnRoad).setOnClickListener { switchStyle(MapStyle.ROAD) }
    }

    private fun switchStyle(style: MapStyle) {
        if (style == currentStyle) return
        currentStyle = style
        val btnSat = findViewById<TextView>(R.id.btnSatellite)
        val btnRoad = findViewById<TextView>(R.id.btnRoad)
        when (style) {
            MapStyle.SATELLITE -> {
                map.setTileSource(createSatelliteSource())
                btnSat.setTextColor(0xFF00E5FF.toInt()); btnRoad.setTextColor(0x99FFFFFF.toInt())
            }
            MapStyle.ROAD -> {
                map.setTileSource(createRoadSource())
                btnRoad.setTextColor(0xFF00E5FF.toInt()); btnSat.setTextColor(0x99FFFFFF.toInt())
            }
        }
        map.invalidate()
    }

    private fun createSatelliteSource(): XYTileSource =
        object : XYTileSource("arcgis_sat", 1, 18, 256, ".jpg", arrayOf("https://server.arcgisonline.com")) {
            override fun getTileURLString(p: Long): String {
                val x = MapTileIndex.getX(p); val y = MapTileIndex.getY(p); var z = MapTileIndex.getZoom(p)
                if (z > 18) z = 18
                return "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
            }
        }

    private fun createRoadSource(): XYTileSource =
        object : XYTileSource("osm_standard", 1, 18, 256, ".png", arrayOf("https://tile.openstreetmap.de")) {
            override fun getTileURLString(p: Long): String {
                val x = MapTileIndex.getX(p); val y = MapTileIndex.getY(p); var z = MapTileIndex.getZoom(p)
                if (z > 18) z = 18
                return "https://tile.openstreetmap.de/$z/$x/$y.png"
            }
        }

    private fun requestPermissions() {
        val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (perms.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, perms, PERM_REQUEST)
        } else {
            startLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        // osmdroid 内置定位叠加层
        val provider = GpsMyLocationProvider(this)
        locationOverlay = MyLocationNewOverlay(provider, map).apply {
            enableMyLocation()
            enableFollowLocation()
            isDrawAccuracyEnabled = true
        }
        map.overlays.add(locationOverlay)

        // 注册自己的 LocationListener 用于 HUD 更新和路网刷新
        locationManager.getProvider(LocationManager.GPS_PROVIDER)?.let {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 2f, this)
        }
        locationManager.getProvider(LocationManager.NETWORK_PROVIDER)?.let {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 5f, this)
        }

        // 磁力计
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
        val lat = location.latitude
        val lng = location.longitude
        val speedKmh = (location.speed * 3.6f).toInt()

        tvSpeed.text = speedKmh.toString()
        tvCoord.text = String.format("%.4f, %.4f", lat, lng)

        // 首次定位居中
        if (firstFix) {
            firstFix = false
            map.controller.animateTo(GeoPoint(lat, lng), 16.0, 800L)
        }

        // 更新车辆标记
        val bearing = if (location.hasBearing() && location.speed > 1f) location.bearing
                      else if (hasCompass) compassBearing else 0f
        updateCarMarker(lat, lng, bearing)

        // 路网刷新
        checkRoadRefresh(lat, lng)
    }

    private fun updateCarMarker(lat: Double, lng: Double, bearing: Float) {
        if (carMarker == null) {
            carMarker = Marker(map).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "车辆"
                icon = resources.getDrawable(android.R.drawable.ic_menu_mylocation, null)
            }
            map.overlays.add(carMarker)
        }
        carMarker?.position = GeoPoint(lat, lng)
        carMarker?.rotation = -bearing
        map.invalidate()
    }

    private fun checkRoadRefresh(lat: Double, lng: Double) {
        if (isFetching) return
        val dist = haversine(lastFetchLat, lastFetchLng, lat, lng)
        if (dist > FETCH_DISTANCE_M || (lastFetchLat == 0.0 && lat != 0.0)) {
            isFetching = true
            lastFetchLat = lat; lastFetchLng = lng
            tvStatus.text = "加载路网..."

            Thread {
                val result = RoadFetcher.fetch(lat, lng)
                handler.post {
                    renderRoads(result)
                    isFetching = false
                }
            }.start()
        }
    }

    private fun renderRoads(result: FetchResult) {
        for (pl in roadPolylines) map.overlays.remove(pl)
        roadPolylines.clear()

        if (result.segments.isEmpty()) {
            tvRoads.text = "路网: 0 段"
            tvStatus.text = if (result.status == FetchStatus.EMPTY) "该区域无道路" else "路网加载失败"
            map.invalidate(); return
        }

        tvStatus.text = "路网: ${result.segments.size} 段"
        tvRoads.text = "路网: ${result.segments.size} 段"

        // 按类型分组渲染
        val grouped = result.segments.groupBy { it.highwayType }
        for ((type, segments) in grouped) {
            val pl = Polyline().apply {
                outlinePaint.color = roadColor(type)
                outlinePaint.strokeWidth = roadWidth(type)
                outlinePaint.isAntiAlias = true
                outlinePaint.alpha = roadAlpha(type)
            }
            val points = mutableListOf<GeoPoint>()
            for (s in segments) { points.add(GeoPoint(s.lat1, s.lng1)); points.add(GeoPoint(s.lat2, s.lng2)) }
            pl.setPoints(points)
            map.overlays.add(pl)
            roadPolylines.add(pl)
        }

        carMarker?.let { map.overlays.remove(it); map.overlays.add(it) }
        map.invalidate()
    }

    private fun roadColor(type: String) = when (type) {
        "motorway", "motorway_link" -> 0xFFFF4444.toInt()
        "trunk", "trunk_link" -> 0xFFFF8833.toInt()
        "primary", "primary_link" -> 0xFF44BBFF.toInt()
        "secondary", "secondary_link" -> 0xFF44DDAA.toInt()
        "tertiary", "tertiary_link" -> 0xFF66CC88.toInt()
        "residential" -> 0xFF5588AA.toInt()
        "service" -> 0xFF445566.toInt()
        else -> 0xFF557799.toInt()
    }

    private fun roadWidth(type: String) = when (type) {
        "motorway", "motorway_link" -> 6f; "trunk", "trunk_link" -> 5f
        "primary", "primary_link" -> 4f; "secondary", "secondary_link" -> 3.5f
        "tertiary", "tertiary_link" -> 3f; "residential" -> 2.5f
        else -> 2f
    }

    private fun roadAlpha(type: String) = when (type) {
        "motorway", "trunk", "primary" -> 230
        "secondary", "tertiary" -> 200
        else -> 160
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

    override fun onResume() { super.onResume(); map.onResume(); locationOverlay?.enableMyLocation() }
    override fun onPause() { super.onPause(); map.onPause(); locationOverlay?.disableMyLocation() }
    override fun onDestroy() {
        super.onDestroy()
        locationManager.removeUpdates(this)
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
    }
}
