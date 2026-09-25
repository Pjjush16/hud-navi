package com.hud.navi

import android.Manifest
import android.annotation.SuppressLint
import android.graphics.Color
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
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.VectorSource
import kotlin.math.*

/**
 * HUD 导航 v6.0 — MapLibre GL Native 渲染引擎
 *
 * 核心变化（相对 v5.x Canvas 方案）：
 * - 替换自研 Canvas 45° 透视为 MapLibre 原生 3D 相机（pitch=60° + bearing）
 * - 矢量瓦片（OpenMapTiles）替代 Overpass API + 手动路网查询
 * - fill-extrusion 3D 建筑拉伸，实现 Hudway 风格立体街区
 * - 程序化构建 HUD 暗色风格：深黑底 + 白路网 + 灰蓝 3D 建筑
 * - 保留 GPS/磁力计插值引擎，驱动 MapLibre 相机平滑运动
 */
class MainActivity : AppCompatActivity(), LocationListener, SensorEventListener {

    private lateinit var mapView: MapView
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private val handler = Handler(Looper.getMainLooper())

    private var mapboxMap: org.maplibre.android.maps.MapLibreMap? = null
    private var mapReady = false

    // === 插值引擎 ===
    private var prevLat = 0.0; private var prevLng = 0.0
    private var prevBearing = 0f; private var prevSpeed = 0f; private var prevTime = 0L
    private var currLat = 0.0; private var currLng = 0.0
    private var currBearing = 0f; private var currSpeed = 0f; private var currTime = 0L
    private val INTERP_MS = 800L
    private val BEARING_SMOOTH = 0.15f
    private val FRAME_MS = 16L

    private var renderRunning = false
    private val renderRunnable = object : Runnable {
        override fun run() {
            updateInterpolation()
            handler.postDelayed(this, FRAME_MS)
        }
    }

    // === 传感器 ===
    private var hasCompass = false
    private var compassBearing = 0f
    private val accData = FloatArray(3)
    private val magData = FloatArray(3)

    companion object {
        private const val PERM_REQUEST = 100
        private const val TAG = "HudNavi"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        requestPermissions()
        initMap()
    }

    /**
     * 构建程序化 HUD 矢量风格
     * 使用 MapTiler 免费矢量瓦片（OpenMapTiles 格式）
     * 无需 API key，无需外部 style.json
     */
    private fun buildHudStyle(): Style.Builder {
        // OpenMapTiles 矢量瓦片源
        // 免费方案：替换为你自己的 MapTiler key（https://cloud.maptiler.com 免费注册）
        // 或使用任何 OpenMapTiles 兼容的矢量瓦片服务
        val MAPTILER_KEY = "get_your_own_OpIi9IULFDHzALew38wE"
        val tiles = VectorSource("openmaptiles",
            "https://api.maptiler.com/tiles/v3-openmaptiles/tiles.json?key=$MAPTILER_KEY")

        return Style.Builder()
            .withSource(tiles)
            // ── 水体 ──
            .withLayer(FillLayer("water", "openmaptiles").apply {
                sourceLayer = "water"
                setProperties(PropertyFactory.fillColor("#0A1628"))
            })
            // ── 陆地覆被 ──
            .withLayer(FillLayer("landuse", "openmaptiles").apply {
                sourceLayer = "landuse"
                setProperties(PropertyFactory.fillColor("#0D0D14"))
            })
            // ── 道路（由细到粗分层渲染） ──
            // 小路与服务道路
            .withLayer(LineLayer("road-minor", "openmaptiles").apply {
                sourceLayer = "transportation"
                filter = Expression.any(
                    Expression.eq(Expression.get("class"), Expression.literal("service")),
                    Expression.eq(Expression.get("class"), Expression.literal("path")),
                    Expression.eq(Expression.get("class"), Expression.literal("track"))
                )
                setProperties(
                    PropertyFactory.lineColor("#2A3540"),
                    PropertyFactory.lineWidth(
                        Expression.interpolate(Expression.linear(), Expression.zoom(),
                            Expression.stop(13, 0.5f),
                            Expression.stop(18, 3f)))
                )
            })
            // 次要道路
            .withLayer(LineLayer("road-secondary", "openmaptiles").apply {
                sourceLayer = "transportation"
                filter = Expression.any(
                    Expression.eq(Expression.get("class"), Expression.literal("tertiary")),
                    Expression.eq(Expression.get("class"), Expression.literal("secondary")),
                    Expression.eq(Expression.get("class"), Expression.literal("minor"))
                )
                setProperties(
                    PropertyFactory.lineColor("#667788"),
                    PropertyFactory.lineWidth(
                        Expression.interpolate(Expression.linear(), Expression.zoom(),
                            Expression.stop(12, 0.8f),
                            Expression.stop(18, 6f))),
                    PropertyFactory.lineCap("round"),
                    PropertyFactory.lineJoin("round")
                )
            })
            // 主要道路（亮白，Hudway 风格的醒目道路）
            .withLayer(LineLayer("road-primary", "openmaptiles").apply {
                sourceLayer = "transportation"
                filter = Expression.any(
                    Expression.eq(Expression.get("class"), Expression.literal("primary")),
                    Expression.eq(Expression.get("class"), Expression.literal("trunk"))
                )
                setProperties(
                    PropertyFactory.lineColor("#CCDDEE"),
                    PropertyFactory.lineWidth(
                        Expression.interpolate(Expression.linear(), Expression.zoom(),
                            Expression.stop(10, 1f),
                            Expression.stop(18, 10f))),
                    PropertyFactory.lineCap("round"),
                    PropertyFactory.lineJoin("round")
                )
            })
            // 高速公路（最亮，青色高亮）
            .withLayer(LineLayer("road-motorway", "openmaptiles").apply {
                sourceLayer = "transportation"
                filter = Expression.eq(Expression.get("class"), Expression.literal("motorway"))
                setProperties(
                    PropertyFactory.lineColor("#44DDFF"),
                    PropertyFactory.lineWidth(
                        Expression.interpolate(Expression.linear(), Expression.zoom(),
                            Expression.stop(8, 1.5f),
                            Expression.stop(18, 14f))),
                    PropertyFactory.lineCap("round"),
                    PropertyFactory.lineJoin("round")
                )
            })
            // ── 3D 建筑拉伸（Hudway 核心效果） ──
            .withLayer(FillExtrusionLayer("hud-buildings", "openmaptiles").apply {
                sourceLayer = "building"
                setProperties(
                    PropertyFactory.fillExtrusionColor("#556677"),
                    PropertyFactory.fillExtrusionOpacity(0.9f),
                    PropertyFactory.fillExtrusionBase(0f),
                    PropertyFactory.fillExtrusionHeight(
                        Expression.interpolate(Expression.linear(), Expression.zoom(),
                            Expression.stop(15, 0f),
                            Expression.stop(15.5, Expression.get("render_height"))))
                )
            })
    }

    private fun initMap() {
        mapView.getMapAsync { map ->
            mapboxMap = map
            map.uiSettings.apply {
                isLogoEnabled = false
                isAttributionEnabled = false
                isCompassEnabled = false
            }

            // 加载程序化 HUD 风格
            map.setStyle(buildHudStyle()) { style ->
                addVehicleMarker(style)
                mapReady = true
                Log.i(TAG, "HUD style loaded with ${style.layers.size} layers")
            }
        }
    }

    /**
     * 添加车辆位置标记（GeoJSON 点 + 双层圆圈）
     */
    private fun addVehicleMarker(style: Style) {
        val geoJson = createPointGeoJson(0.0, 0.0)
        style.addSource(GeoJsonSource("vehicle", geoJson))

        // 外圈发光（绿色光晕）
        style.addLayer(CircleLayer("vehicle-glow", "vehicle").apply {
            setProperties(
                PropertyFactory.circleRadius(20f),
                PropertyFactory.circleColor("#00FF88"),
                PropertyFactory.circleOpacity(0.25f),
                PropertyFactory.circleBlur(1f)
            )
        })

        // 内圈实心（白色 + 绿色描边）
        style.addLayer(CircleLayer("vehicle-dot", "vehicle").apply {
            setProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleColor(Color.WHITE),
                PropertyFactory.circleStrokeWidth(2.5f),
                PropertyFactory.circleStrokeColor("#00FF88")
            )
        })
    }

    private fun createPointGeoJson(lat: Double, lng: Double): JsonObject {
        return JsonObject().apply {
            addProperty("type", "FeatureCollection")
            add("features", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "Feature")
                    add("geometry", JsonObject().apply {
                        addProperty("type", "Point")
                        add("coordinates", JsonArray().apply { add(lng); add(lat) })
                    })
                    add("properties", JsonObject())
                })
            })
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
        if (requestCode == PERM_REQUEST && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
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
    }

    // === 插值引擎（60fps 驱动 MapLibre 相机） ===
    private fun updateInterpolation() {
        if (currLat == 0.0 || !mapReady) return
        val elapsed = System.currentTimeMillis() - currTime
        val (iLat, iLng, iBearing) = if (prevLat == 0.0 || elapsed >= INTERP_MS) {
            Triple(currLat, currLng, currBearing)
        } else {
            val t = (elapsed.toFloat() / INTERP_MS).coerceIn(0f, 1f)
            val et = 1f - (1f - t) * (1f - t)  // easeOut
            Triple(
                prevLat + (currLat - prevLat) * et,
                prevLng + (currLng - prevLng) * et,
                circularLerp(prevBearing, currBearing, et)
            )
        }
        updateVehicleMarker(iLat, iLng)
        mapboxMap?.cameraPosition = CameraPosition.Builder()
            .target(LatLng(iLat, iLng))
            .bearing(iBearing.toDouble())
            .tilt(60.0)
            .zoom(17.5)
            .build()
    }

    private fun updateVehicleMarker(lat: Double, lng: Double) {
        mapboxMap?.getStyle { style ->
            (style.getSource("vehicle") as? GeoJsonSource)?.setGeoJson(createPointGeoJson(lat, lng))
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

    override fun onResume() { super.onResume(); mapView.onResume(); startRenderLoop() }
    override fun onPause() { stopRenderLoop(); mapView.onPause(); super.onPause() }
    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        stopRenderLoop()
        locationManager.removeUpdates(this)
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
        mapView.onDestroy()
        super.onDestroy()
    }
}
