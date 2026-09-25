package com.hud.navi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.style.expressions.Expression
import com.mapbox.mapboxsdk.style.layers.CircleLayer
import com.mapbox.mapboxsdk.style.layers.FillExtrusionLayer
import com.mapbox.mapboxsdk.style.layers.FillLayer
import com.mapbox.mapboxsdk.style.layers.LineLayer
import com.mapbox.mapboxsdk.style.layers.PropertyFactory
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import com.mapbox.mapboxsdk.style.sources.VectorSource
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlin.math.*

/**
 * HUD 导航 v6.1 — P0 修复版
 *
 * P0 修复清单:
 * 1. HUD 镜像（垂直翻转）开关 — 挡风玻璃投影必须
 * 2. 屏幕常亮 + 前台服务 — 防止熄屏/后台回收
 * 3. 权限被拒 UI 提示与重试
 * 4. 后方路段投影错乱 — MapLibre 3D 相机已自然解决（v6.0 迁移收益）
 * 5. 状态文本真正上屏 — 网络/GPS/定位状态实时可见
 */
class MainActivity : AppCompatActivity(), LocationListener, SensorEventListener {

    private lateinit var mapView: MapView
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private val handler = Handler(Looper.getMainLooper())

    // === UI 组件 ===
    private lateinit var flipContainer: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var speedText: TextView
    private lateinit var btnMirror: TextView
    private lateinit var permDeniedLayout: LinearLayout
    private lateinit var btnRetryPerm: TextView

    private var mapboxMap: com.mapbox.mapboxsdk.maps.MapboxMap? = null
    private var mapReady = false
    private var mirrorEnabled = false

    // === 插值引擎 ===
    private var prevLat = 0.0; private var prevLng = 0.0
    private var prevBearing = 0f; private var prevSpeed = 0f; private var prevTime = 0L
    private var currLat = 0.0; private var currLng = 0.0
    private var currBearing = 0f; private var currSpeed = 0f; private var currTime = 0L
    private val INTERP_MS = 800L
    private val BEARING_SMOOTH = 0.15f
    private val FRAME_MS = 16L

    // === 状态追踪 ===
    private var gpsFixCount = 0
    private var lastGpsTime = 0L
    private var networkAvailable = true

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

        // P0-2: 屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        Mapbox.getInstance(this)
        setContentView(R.layout.activity_main)

        // 绑定 UI 组件
        flipContainer = findViewById(R.id.flipContainer)
        statusText = findViewById(R.id.statusText)
        speedText = findViewById(R.id.speedText)
        btnMirror = findViewById(R.id.btnMirror)
        permDeniedLayout = findViewById(R.id.permDeniedLayout)
        btnRetryPerm = findViewById(R.id.btnRetryPerm)

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        // P0-1: HUD 镜像切换按钮
        btnMirror.setOnClickListener { toggleMirror() }

        // P0-3: 权限重试按钮
        btnRetryPerm.setOnClickListener {
            permDeniedLayout.visibility = View.GONE
            requestPermissions()
        }

        // P0-2: 启动前台服务
        startHudForegroundService()

        requestPermissions()
        initMap()
    }

    /**
     * P0-1: HUD 镜像翻转（垂直翻转整个地图容器）
     * 挡风玻璃投影时反射画面为反字，必须垂直翻转
     */
    private fun toggleMirror() {
        mirrorEnabled = !mirrorEnabled
        flipContainer.scaleY = if (mirrorEnabled) -1f else 1f
        // 状态文本不受镜像影响（它在 flipContainer 外面）
        btnMirror.alpha = if (mirrorEnabled) 1.0f else 0.6f
        updateStatusText()
    }

    /**
     * P0-2: 启动前台服务（防止系统回收）
     */
    private fun startHudForegroundService() {
        val serviceIntent = Intent(this, HudForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    /**
     * P0-5: 状态文本实时更新
     */
    private fun updateStatusText() {
        val gpsAge = if (lastGpsTime > 0) (System.currentTimeMillis() - lastGpsTime) / 1000 else -1L
        val statusParts = mutableListOf<String>()

        when {
            currLat == 0.0 -> statusParts.add("等待GPS定位...")
            gpsAge > 10 -> statusParts.add("GPS信号丢失 (${gpsAge}s)")
            gpsAge > 5 -> statusParts.add("GPS信号弱 (${gpsAge}s)")
            else -> statusParts.add("GPS正常 (${gpsFixCount}次)")
        }

        statusParts.add("速度: ${currSpeed.toInt()} km/h")
        if (mirrorEnabled) statusParts.add("镜像模式")

        val bearing = if (hasCompass) compassBearing.toInt() else currBearing.toInt()
        statusParts.add("航向: ${bearing}°")

        statusText.text = statusParts.joinToString(" | ")
    }

    private fun initMap() {
        mapView.getMapAsync { map ->
            mapboxMap = map
            map.uiSettings.apply {
                isLogoEnabled = false
                isAttributionEnabled = false
                isCompassEnabled = false
            }

            updateStatusText()

            map.setStyle(buildHudStyle()) { style ->
                addVehicleMarker(style)
                mapReady = true
                Log.i(TAG, "HUD style loaded with ${style.layers.size} layers")
                updateStatusText()
            }
        }
    }

    /**
     * 构建程序化 HUD 矢量风格
     * 使用 MapTiler 免费矢量瓦片（OpenMapTiles 格式）
     */
    private fun buildHudStyle(): Style.Builder {
        val MAPTILER_KEY = "get_your_own_OpIi9IULFDHzALew38wE"
        val tiles = VectorSource("openmaptiles",
            "https://api.maptiler.com/tiles/v3-openmaptiles/tiles.json?key=$MAPTILER_KEY")

        return Style.Builder()
            .withSource(tiles)
            // ── 水体 ──
            .withLayer(FillLayer("water", "openmaptiles")
                .withSourceLayer("water")
                .withProperties(PropertyFactory.fillColor("#0A1628")))
            // ── 陆地覆被 ──
            .withLayer(FillLayer("landuse", "openmaptiles")
                .withSourceLayer("landuse")
                .withProperties(PropertyFactory.fillColor("#0D0D14")))
            // ── 小路与服务道路 ──
            .withLayer(LineLayer("road-minor", "openmaptiles")
                .withSourceLayer("transportation")
                .withFilter(Expression.any(
                    Expression.eq(Expression.get("class"), Expression.literal("service")),
                    Expression.eq(Expression.get("class"), Expression.literal("path")),
                    Expression.eq(Expression.get("class"), Expression.literal("track"))))
                .withProperties(
                    PropertyFactory.lineColor("#2A3540"),
                    PropertyFactory.lineWidth(
                        Expression.interpolate(Expression.linear(), Expression.zoom(),
                            Expression.stop(13, 0.5f),
                            Expression.stop(18, 3f)))))
            // ── 次要道路 ──
            .withLayer(LineLayer("road-secondary", "openmaptiles")
                .withSourceLayer("transportation")
                .withFilter(Expression.any(
                    Expression.eq(Expression.get("class"), Expression.literal("tertiary")),
                    Expression.eq(Expression.get("class"), Expression.literal("secondary")),
                    Expression.eq(Expression.get("class"), Expression.literal("minor"))))
                .withProperties(
                    PropertyFactory.lineColor("#667788"),
                    PropertyFactory.lineWidth(
                        Expression.interpolate(Expression.linear(), Expression.zoom(),
                            Expression.stop(12, 0.8f),
                            Expression.stop(18, 6f))),
                    PropertyFactory.lineCap("round"),
                    PropertyFactory.lineJoin("round")))
            // ── 主要道路（亮白，Hudway 风格） ──
            .withLayer(LineLayer("road-primary", "openmaptiles")
                .withSourceLayer("transportation")
                .withFilter(Expression.any(
                    Expression.eq(Expression.get("class"), Expression.literal("primary")),
                    Expression.eq(Expression.get("class"), Expression.literal("trunk"))))
                .withProperties(
                    PropertyFactory.lineColor("#CCDDEE"),
                    PropertyFactory.lineWidth(
                        Expression.interpolate(Expression.linear(), Expression.zoom(),
                            Expression.stop(10, 1f),
                            Expression.stop(18, 10f))),
                    PropertyFactory.lineCap("round"),
                    PropertyFactory.lineJoin("round")))
            // ── 高速公路（青色高亮） ──
            .withLayer(LineLayer("road-motorway", "openmaptiles")
                .withSourceLayer("transportation")
                .withFilter(Expression.eq(Expression.get("class"), Expression.literal("motorway")))
                .withProperties(
                    PropertyFactory.lineColor("#44DDFF"),
                    PropertyFactory.lineWidth(
                        Expression.interpolate(Expression.linear(), Expression.zoom(),
                            Expression.stop(8, 1.5f),
                            Expression.stop(18, 14f))),
                    PropertyFactory.lineCap("round"),
                    PropertyFactory.lineJoin("round")))
            // ── 3D 建筑拉伸（Hudway 核心效果） ──
            .withLayer(FillExtrusionLayer("hud-buildings", "openmaptiles")
                .withSourceLayer("building")
                .withProperties(
                    PropertyFactory.fillExtrusionColor("#556677"),
                    PropertyFactory.fillExtrusionOpacity(0.9f),
                    PropertyFactory.fillExtrusionBase(0f),
                    PropertyFactory.fillExtrusionHeight(
                        Expression.interpolate(Expression.linear(), Expression.zoom(),
                            Expression.stop(15, 0f),
                            Expression.stop(15.5, Expression.get("render_height"))))))
    }

    /**
     * 添加车辆位置标记（GeoJSON 点 + 双层圆圈）
     */
    private fun addVehicleMarker(style: Style) {
        val geoJson = createPointGeoJson(0.0, 0.0)
        style.addSource(GeoJsonSource("vehicle", geoJson.toString()))

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

    // === P0-3: 权限管理（含拒绝提示） ===
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
                // P0-3: 权限被拒时显示提示和重试按钮
                permDeniedLayout.visibility = View.VISIBLE
                statusText.text = "需要定位权限才能使用 HUD 导航"
                Log.w(TAG, "Location permission denied")
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

        // P0-5: 更新状态文本和速度显示
        updateStatusText()
        speedText.text = "${currSpeed.toInt()} km/h"
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

        // P0-5: 每秒更新一次状态文本（非GPS触发的定时刷新）
        if (System.currentTimeMillis() - lastGpsTime > 3000 && gpsFixCount > 0) {
            updateStatusText()
        }
    }

    private fun updateVehicleMarker(lat: Double, lng: Double) {
        mapboxMap?.getStyle { style ->
            (style.getSource("vehicle") as? GeoJsonSource)?.setGeoJson(createPointGeoJson(lat, lng).toString())
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
        mapView.onResume()
        startRenderLoop()
        // P0-2: 确保前台服务运行中
        startHudForegroundService()
    }

    override fun onPause() {
        // P0-2: onPause 不停止前台服务和 GPS（后台保持定位）
        stopRenderLoop()
        mapView.onPause()
        super.onPause()
    }

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
        // P0-2: 停止前台服务
        stopService(Intent(this, HudForegroundService::class.java))
        mapView.onDestroy()
        super.onDestroy()
    }
}
