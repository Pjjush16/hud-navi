/*
 * hud-navi - Lightweight HUD navigation with Canvas 2D rendering
 * Copyright (C) 2026 Pjjush16
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

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
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.*
import java.io.File

// v15.0: MapLibre 已移除，使用自研 Canvas 2D 渲染引擎

/**
 * HUD 导航 v9.0 — 回滚地图绘制到气压计之前
 *
 * v9.0 变更：
 * - 移除气压计 + 高架层数 + 隧道判定（地图无法显示的根因）
 * - 新增磁盘缓存（启动秒加载路网）
 * - 新增 HUD 镜像开关（双击切换，挡风玻璃投影）
 * - 保留 EKF 惯导、陀螺仪、旋转矢量、HMM 地图匹配
 */
class MainActivity : AppCompatActivity(), LocationListener, SensorEventListener {

    private lateinit var hudView: HudView
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private val handler = Handler(Looper.getMainLooper())

    private val gpsFilter = GpsFilter()
    private val ekf = EkfDeadReckoning()

    private lateinit var flipContainer: FrameLayout
    private lateinit var permDeniedLayout: LinearLayout
    private lateinit var btnRetryPerm: TextView

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var roadFetchJob: Job? = null

    // === 双击手势（切换镜像） ===
    private lateinit var gestureDetector: GestureDetector

    // === 语音交互管线 (v11.0) ===
    private var voicePipeline: VoicePipeline? = null
    private var wakeWordEnabled = false
    private var pipelineReady = false

    // === OSRM 路线规划 (v13.14) ===
    private var osrmRouter: OsrmRouter? = null

    // === HTTP API 服务器 (v13.14) ===
    private var apiServer: ApiServer? = null

    // === 唤醒反馈 UI（通过 gravity 跟随镜像同步切换位置） ===
    private lateinit var wakeFeedback: FrameLayout
    private lateinit var wakeFeedbackPanel: LinearLayout
    private lateinit var wakeFeedbackKeyword: TextView
    private var wakeFeedbackHideRunnable: Runnable? = null

    // === 语音状态指示 ===
    private var isProcessing = false
    private var isSpeaking = false

    // === GPS 目标 ===
    private var targetLat = 0.0; private var targetLng = 0.0
    private var targetBearing = 0f; private var targetSpeed = 0f
    private var gpsAccuracy = 10f

    // === 车辆状态（EKF 输出） ===
    private var vehicleLat = 0.0; private var vehicleLng = 0.0
    private var vehicleBearing = 0f
    private var lastFrameTime = 0L
    private val FRAME_MS = 16L

    // === 平滑绘制位置（消除吸附/脱吸附抖动） ===
    private var smoothDrawLat = 0.0; private var smoothDrawLng = 0.0
    private var snapBlend = 0.0  // 0=原始GPS, 1=完全吸附
    private var snapInit = false

    // 传感器
    private val accData = FloatArray(3)
    private val magData = FloatArray(3)
    private var hasCompass = false
    private var compassBearing = 0f
    private var smoothedCompassBearing = 0f
    private var compassInitialized = false
    private val worldAcc = FloatArray(3)
    private val smoothedWorldAcc = FloatArray(3)
    private val ACC_SMOOTH = 0.15f

    // === 陀螺仪 ===
    private var hasGyro = false
    private val gyroData = FloatArray(3)
    private var gyroHeadingRate = 0f
    private var gyroHeadingRateSmooth = 0f
    private val GYRO_SMOOTH = 0.3f
    private var lastGyroTime = 0L

    // === 旋转矢量 ===
    private var hasRotationVector = false
    private var rvHeading = 0f
    private var rvHeadingSmooth = 0f
    private var rvInitialized = false

    // === 状态 ===
    private var gpsFixCount = 0
    private var lastGpsTime = 0L
    private var lastRoadFetchLat = 0.0
    private var lastRoadFetchLng = 0.0
    private var lastRoadFetchTime = 0L


    // === 渲染循环 ===
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
        private const val COMPASS_EMA_ALPHA = 0.08f
        private const val HEADING_DEAD_ZONE = 2.5f
        private const val FREEZE_ACC_THRESHOLD = 0.5f
        private const val FREEZE_SPEED_THRESHOLD = 2f
    }

    // === HMM 地图匹配 ===
    private var matchedSegIdx = -1
    private var matchedProjLat = 0.0
    private var matchedProjLng = 0.0
    private var hmmConfidence = 0.0
    private val HMM_SIGMA = 10.0
    private val HMM_STAY_BONUS = 3.0
    private val HMM_SEARCH_RADIUS = 50.0
    private val HMM_MIN_CONFIDENCE = 0.1
    private val HMM_SPEED_GATE = 3f

    private fun bearingBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val dLng = lng2 - lng1
        val y = sin(Math.toRadians(dLng)) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(Math.toRadians(dLng))
        return ((Math.toDegrees(atan2(y, x)).toFloat() + 360f) % 360f)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // v14.7.7: 全局异常捕获 — 防止任何子模块崩溃导致闪退
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "FATAL CRASH in ${thread.name}: ${throwable.message}", throwable)
            // 记录崩溃信息到文件，方便排查
            try {
                val crashFile = java.io.File(getExternalFilesDir(null), "crash_log.txt")
                crashFile.appendText("[${java.util.Date()}] ${throwable.javaClass.simpleName}: ${throwable.message}\n")
                throwable.stackTrace.forEach { crashFile.appendText("  at $it\n") }
            } catch (_: Exception) {}
            // 不终止进程 — 让系统默认处理器处理（会显示崩溃对话框但保留日志）
        }

        setContentView(R.layout.activity_main)

        flipContainer = findViewById(R.id.flipContainer)
        // 初始化时设置镜像状态（默认 mirrorEnabled=true，翻转整个容器）
        flipContainer.post {
            try {
                flipContainer.pivotY = flipContainer.height / 2f
                flipContainer.scaleY = if (::hudView.isInitialized && hudView.mirrorEnabled) -1f else 1f
            } catch (e: Exception) {
                Log.w(TAG, "flipContainer init error: ${e.message}")
            }
        }
        permDeniedLayout = findViewById(R.id.permDeniedLayout)
        btnRetryPerm = findViewById(R.id.btnRetryPerm)
        hudView = findViewById(R.id.hudView)

        // v15.0: 自研 Canvas 2D 渲染引擎，无需 MapLibre

        // 唤醒反馈 UI
        wakeFeedback = findViewById(R.id.wakeFeedback)
        wakeFeedbackPanel = findViewById(R.id.wakeFeedbackPanel)
        wakeFeedbackKeyword = findViewById(R.id.wakeFeedbackKeyword)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        // 初始化磁盘缓存
        val cacheDir = File(filesDir, "road_cache")
        RoadFetcher.initCache(cacheDir)

        // 启动时立即加载磁盘缓存（秒加载路网）
        val diskSegments = RoadFetcher.loadDiskCache()
        if (diskSegments.isNotEmpty()) {
            hudView.setRoads(diskSegments, 0.0, 0.0)
            RoadFetcher.markRendered()
            Log.i(TAG, "Loaded ${diskSegments.size} cached segments at startup (marked as rendered)")
        }

        // 双击切换镜像
        // 初始化：镜像默认开启，唤醒反馈面板位置同步（顶部/底部）
        updateWakeFeedbackMirror()

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                hudView.mirrorEnabled = !hudView.mirrorEnabled
                // 翻转整个 flipContainer（HudView 地图+HUD一起翻转）
                flipContainer.pivotY = flipContainer.height / 2f
                flipContainer.scaleY = if (hudView.mirrorEnabled) -1f else 1f
                updateWakeFeedbackMirror()
                val state = if (hudView.mirrorEnabled) "镜像 ON" else "镜像 OFF"
                Toast.makeText(this@MainActivity, state, Toast.LENGTH_SHORT).show()
                Log.i(TAG, "Mirror toggled: ${hudView.mirrorEnabled}")
                return true
            }
        })

        hudView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        btnRetryPerm.setOnClickListener {
            permDeniedLayout.visibility = View.GONE
            requestPermissions()
        }

        startHudForegroundService()

        // 初始化语音唤醒词识别
        initWakeWord()

        requestPermissions()
    }

    private fun initWakeWord() {
        // v13.14: 初始化 OSRM 路线规划
        osrmRouter = OsrmRouter()

        // v13.14: 初始化 HTTP API 服务器
        apiServer = ApiServer(this, object : ApiActionHandler {
            override fun onMapZoomIn() { handler.post { // v10.0: zoomOffset not supported } }
            override fun onMapZoomOut() { handler.post { // v10.0: zoomOffset not supported } }
            override fun onMapZoomReset() { handler.post { // v10.0: zoomOffset not supported } }
            override fun onMirrorToggle() { handler.post {
                hudView.mirrorEnabled = !hudView.mirrorEnabled
                flipContainer.pivotY = flipContainer.height / 2f
                flipContainer.scaleY = if (hudView.mirrorEnabled) -1f else 1f
                updateWakeFeedbackMirror()
            } }
            override fun onMirrorOn() { handler.post {
                hudView.mirrorEnabled = true
                flipContainer.pivotY = flipContainer.height / 2f
                flipContainer.scaleY = -1f
                updateWakeFeedbackMirror()
            } }
            override fun onMirrorOff() { handler.post {
                hudView.mirrorEnabled = false
                flipContainer.pivotY = flipContainer.height / 2f
                flipContainer.scaleY = 1f
                updateWakeFeedbackMirror()
            } }
            override fun onNavigateTo(lat: Double, lng: Double, name: String) {
                handler.post { startNavigationTo(lat, lng, name) }
            }
            override fun onNavigateCancel() { handler.post { cancelNavigation() } }
            override fun onMusicPlay() { Log.i(TAG, "API: music play (placeholder)") }
            override fun onMusicPause() { Log.i(TAG, "API: music pause (placeholder)") }
            override fun onMusicNext() { Log.i(TAG, "API: music next (placeholder)") }
            override fun onMusicPrev() { Log.i(TAG, "API: music prev (placeholder)") }
            override fun onGetStatus(): org.json.JSONObject {
                return org.json.JSONObject().apply {
                    put("vehicle_lat", vehicleLat)
                    put("vehicle_lng", vehicleLng)
                    put("vehicle_speed", hudView.vehicleSpeed)
                    put("vehicle_bearing", vehicleBearing)
                    put("mirror_enabled", hudView.mirrorEnabled)
                    put("zoom_offset", 0f)  // v10.0: no zoomOffset
                    put("navigation_active", false)  // v10.0: no navigation rendering
                    put("navigation_destination", "")  // v10.0: no navigation rendering
                }
            }
        })
        apiServer?.startServer()
        Log.i(TAG, "API server started on port ${apiServer?.getPort()}, key: ${apiServer?.getApiKey()}")

        // v11.0: 使用 VoicePipeline 统一管理语音交互
        try {
            voicePipeline = VoicePipeline(this, object : VoicePipelineCallback {
                override fun onStateChanged(state: PipelineState) {
                    handler.post {
                        isProcessing = state == PipelineState.PROCESSING
                        isSpeaking = state == PipelineState.SPEAKING

                        when (state) {
                            PipelineState.LISTENING -> {
                                wakeFeedbackKeyword.text = "请说话..."
                                wakeFeedback.visibility = View.VISIBLE
                            }
                            PipelineState.PROCESSING -> {
                                wakeFeedbackKeyword.text = "思考中..."
                                wakeFeedback.visibility = View.VISIBLE
                            }
                            PipelineState.IDLE -> {
                                // 回到空闲时隐藏反馈面板
                                hideWakeFeedback()
                            }
                            else -> {}
                        }
                    }
                }

                override fun onWakeDetected(keyword: String) {
                    handler.post { showWakeFeedback(keyword) }
                }

                override fun onAsrResult(text: String, isFinal: Boolean) {
                    handler.post {
                        if (text.isNotBlank()) {
                            // 直接显示 ASR 识别出的文字，不加 emoji 前缀
                            wakeFeedbackKeyword.text = text
                            wakeFeedback.visibility = View.VISIBLE
                        }
                    }
                }

                override fun onIntentResult(result: IntentResult) {
                    Log.i(TAG, "Intent result: ${result.intent}/${result.action}")
                }

                override fun onUiCommand(command: String) {
                    handler.post { handleUiCommand(command) }
                }

                override fun onChatResponse(text: String) {
                    handler.post {
                        wakeFeedbackKeyword.text = text
                    }
                }

                override fun onError(message: String) {
                    handler.post {
                        Log.w(TAG, "Pipeline error: $message")
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }, osrmRouter, apiServer)

            voicePipeline?.init()
            pipelineReady = voicePipeline?.pipelineReady == true

            if (pipelineReady) {
                wakeWordEnabled = true
                Log.i(TAG, "Voice pipeline ready")
                Toast.makeText(this, "语音助手已就绪：说\"哈德\"唤醒", Toast.LENGTH_SHORT).show()
            } else {
                val errMsg = voicePipeline?.initError ?: "部分模块未就绪"
                Log.w(TAG, "Pipeline partial init: $errMsg")
                Toast.makeText(this, "语音助手部分就绪: $errMsg", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline init failed: ${e.message}", e)
            Toast.makeText(this, "语音助手初始化失败", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 处理 UI 指令 — 从 ChatEngine 传过来的命令
     */
    private fun handleUiCommand(command: String) {
        when {
            command == "MIRROR_TOGGLE" -> {
                hudView.mirrorEnabled = !hudView.mirrorEnabled
                flipContainer.pivotY = flipContainer.height / 2f
                flipContainer.scaleY = if (hudView.mirrorEnabled) -1f else 1f
                updateWakeFeedbackMirror()
            }
            command == "MIRROR_OFF" -> {
                hudView.mirrorEnabled = false
                flipContainer.pivotY = flipContainer.height / 2f
                flipContainer.scaleY = 1f
                updateWakeFeedbackMirror()
            }
            command == "MAP_ZOOM_IN" -> {
                // v10.0: zoomOffset not supported
            }
            command == "MAP_ZOOM_OUT" -> {
                // v10.0: zoomOffset not supported
            }
            command == "MAP_ZOOM_RESET" -> {
                // v10.0: zoomOffset not supported
            }
            command.startsWith("🔍") -> {
                wakeFeedbackKeyword.text = command
            }
            else -> {
                wakeFeedbackKeyword.text = command
            }
        }
    }

    /**
     * 启动导航到指定坐标
     */
    private fun startNavigationTo(lat: Double, lng: Double, name: String) {
        Log.i(TAG, "Starting navigation to: $name ($lat, $lng)")
        Thread {
            try {
                val currentLat = vehicleLat
                val currentLng = vehicleLng
                if (currentLat == 0.0 || currentLng == 0.0) {
                    handler.post {
                        Toast.makeText(this, "无法获取当前位置", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                val route = osrmRouter?.route(Pair(currentLat, currentLng), Pair(lat, lng))
                if (route != null && route.success) {
                    handler.post {
                        // v10.0: navigation route rendering not supported
                        // v10.0: navigation route rendering not supported
                        hudView.invalidate()
                        Toast.makeText(this, "导航已启动: $name (${osrmRouter?.formatDistance(route.distance)}, ${osrmRouter?.formatDuration(route.duration)})", Toast.LENGTH_LONG).show()
                    }
                } else {
                    handler.post {
                        Toast.makeText(this, "路线规划失败: ${route?.error ?: "未知错误"}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Navigation failed: ${e.message}", e)
                handler.post {
                    Toast.makeText(this, "导航失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /**
     * 取消导航
     */
    private fun cancelNavigation() {
        Log.i(TAG, "Navigation cancelled")
        // v10.0: navigation route rendering not supported
        // v10.0: navigation route rendering not supported
        hudView.invalidate()
        Toast.makeText(this, "导航已取消", Toast.LENGTH_SHORT).show()
    }

    private fun startHudForegroundService() {
        val intent = Intent(this, HudForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        // 语音唤醒需要录音权限
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // Android 12 以下 RECORD_AUDIO 是普通权限
        }
        perms.add(Manifest.permission.RECORD_AUDIO)
        val permArray = perms.toTypedArray()
        if (permArray.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, permArray, PERM_REQUEST)
        } else {
            startLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                permDeniedLayout.visibility = View.GONE
                startLocationUpdates()
            } else {
                permDeniedLayout.visibility = View.VISIBLE
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
        // 磁力计 + 加速度计
        val mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val acc = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (mag != null && acc != null) {
            sensorManager.registerListener(this, mag, SensorManager.SENSOR_DELAY_GAME)
            sensorManager.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
            hasCompass = true
        }
        // 陀螺仪
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (gyro != null) {
            sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
            hasGyro = true
            Log.i(TAG, "Gyroscope registered")
        }
        // 旋转矢量
        val rv = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rv != null) {
            sensorManager.registerListener(this, rv, SensorManager.SENSOR_DELAY_GAME)
            hasRotationVector = true
            Log.i(TAG, "Rotation vector registered")
        }
        // 线性加速度
        val linAcc = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        linAcc?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }

        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onLocationChanged(it) }
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { onLocationChanged(it) }
    }

    override fun onLocationChanged(location: Location) {
        val now = System.currentTimeMillis()
        gpsFixCount++
        lastGpsTime = now

        val filtered = gpsFilter.process(
            rawLat = location.latitude, rawLng = location.longitude,
            rawSpeedMs = location.speed, accuracy = location.accuracy, timestampMs = now
        ) ?: return

        val (filteredLat, filteredLng, filteredSpeedKmh) = filtered
        targetLat = filteredLat; targetLng = filteredLng
        targetSpeed = filteredSpeedKmh; gpsAccuracy = location.accuracy

        val rawBearing = if (location.hasBearing() && location.speed > 0.5f) location.bearing
                         else vehicleBearing
        targetBearing = if (vehicleLat == 0.0) rawBearing else circularShortest(vehicleBearing, rawBearing)

        if (!ekf.initialized) {
            ekf.initialize(filteredLat, filteredLng, rawBearing, location.speed, now)
            vehicleLat = ekf.lat; vehicleLng = ekf.lng
            vehicleBearing = ekf.heading.toFloat()
            lastFrameTime = now
        } else {
            ekf.update(filteredLat, filteredLng, location.accuracy, now)
        }

        vehicleLat = ekf.lat; vehicleLng = ekf.lng
        hudView.vehicleLat = vehicleLat; hudView.vehicleLng = vehicleLng
        hudView.vehicleBearing = vehicleBearing; hudView.vehicleSpeed = targetSpeed

        // v13.14: 更新 IntentClassifier 的当前位置（供导航使用）
        voicePipeline?.let { pipeline ->
            val classifier = pipeline.javaClass.getDeclaredField("intentClassifier").apply { isAccessible = true }.get(pipeline) as? IntentClassifier
            classifier?.currentLocation = Pair(vehicleLat, vehicleLng)
        }

        tryFetchRoads()
    }

    // LocationListener 必须实现的三个方法（老版本 Android 上是抽象方法，缺失会导致 AbstractMethodError 闪退）
    override fun onProviderDisabled(provider: String) {
        Log.w(TAG, "Location provider disabled: $provider")
    }

    override fun onProviderEnabled(provider: String) {
        Log.i(TAG, "Location provider enabled: $provider")
    }

    override fun onStatusChanged(provider: String, status: Int, extras: android.os.Bundle?) {
        Log.d(TAG, "Location provider status: $provider -> $status")
    }

    private fun tryFetchRoads() {
        if (targetLat == 0.0) return
        val now = System.currentTimeMillis()
        val dist = RoadFetcher.haversine(targetLat, targetLng, lastRoadFetchLat, lastRoadFetchLng)
        val timeSince = now - lastRoadFetchTime

        if (dist > ROAD_FETCH_DIST || (timeSince > ROAD_FETCH_INTERVAL && !RoadFetcher.isCacheValid(targetLat, targetLng))) {
            lastRoadFetchLat = targetLat; lastRoadFetchLng = targetLng
            lastRoadFetchTime = now

            roadFetchJob?.cancel()
            roadFetchJob = scope.launch {
                val segments = RoadFetcher.fetchRoads(targetLat, targetLng)
                hudView.setRoads(segments, targetLat, targetLng)
                intersections = buildIntersections(segments)
                Log.i(TAG, "Roads: ${segments.size} segments, ${intersections.size} intersections")
            }
        }
    }

    private fun updateFrame() {
        if (targetLat == 0.0) return

        val now = System.currentTimeMillis()
        val dt = if (lastFrameTime == 0L) 16L else (now - lastFrameTime).coerceIn(1L, 100L)
        lastFrameTime = now

        ekf.predict(dt, vehicleBearing, targetSpeed * 1000f / 3600f)

        vehicleLat = ekf.lat; vehicleLng = ekf.lng

        val gyroActive = hasGyro && (System.currentTimeMillis() - lastGyroTime) < 500
        val dtSec = dt / 1000.0

        var headingFromGyro = vehicleBearing
        if (gyroActive && abs(gyroHeadingRateSmooth) > 0.3f) {
            headingFromGyro = vehicleBearing + gyroHeadingRateSmooth * dtSec.toFloat()
        }

        val newBearing = when {
            targetSpeed > 1f -> {
                val diff = ((targetBearing - headingFromGyro + 540f) % 360f) - 180f
                val anchorRate = when {
                    targetSpeed > 60f -> 0.04f
                    targetSpeed > 30f -> 0.03f
                    else -> 0.02f
                }
                if (abs(diff) < HEADING_DEAD_ZONE) headingFromGyro
                else headingFromGyro + diff * anchorRate
            }
            hasRotationVector && rvInitialized -> {
                val diff = ((rvHeadingSmooth - headingFromGyro + 540f) % 360f) - 180f
                if (abs(diff) < HEADING_DEAD_ZONE) headingFromGyro
                else headingFromGyro + diff * 0.05f
            }
            compassInitialized -> {
                // 静止且无旋转矢量时，用磁力计罗盘驱动地图旋转
                val diff = ((smoothedCompassBearing - headingFromGyro + 540f) % 360f) - 180f
                if (abs(diff) < HEADING_DEAD_ZONE) headingFromGyro
                else headingFromGyro + diff * 0.05f
            }
            else -> headingFromGyro
        }
        vehicleBearing = ((newBearing % 360f) + 360f) % 360f

        // 道路吸附
        val ekfUncertainty = ekf.getPositionUncertainty()
        val adaptiveSigma = maxOf(HMM_SIGMA, ekfUncertainty)

        var snapped: Pair<Double, Double>? = null
        snapped = hmmMapMatchWithSigma(vehicleLat, vehicleLng, targetSpeed, vehicleBearing, adaptiveSigma)

        // === 平滑绘制位置：消除吸附/脱吸附抖动 ===
        if (!snapInit) {
            smoothDrawLat = vehicleLat; smoothDrawLng = vehicleLng; snapInit = true
        }
        val snapAlphaUp = 0.3
        val snapAlphaDown = 0.02

        if (snapped != null) {
            snapBlend = (snapBlend + snapAlphaUp).coerceAtMost(1.0)
            val targetLat = vehicleLat + (snapped.first - vehicleLat) * snapBlend
            val targetLng = vehicleLng + (snapped.second - vehicleLng) * snapBlend
            smoothDrawLat = targetLat; smoothDrawLng = targetLng
            hudView.snappedLat = smoothDrawLat; hudView.snappedLng = smoothDrawLng
            hudView.isSnapped = snapBlend > 0.5
            hudView.vehicleLat = smoothDrawLat; hudView.vehicleLng = smoothDrawLng
        } else {
            snapBlend = (snapBlend - snapAlphaDown).coerceAtLeast(0.0)
            // snap未完全退出时仍保留部分吸附位置
            if (snapBlend > 0.01) {
                val targetLat = vehicleLat + (smoothDrawLat - vehicleLat) * (snapBlend)
                val targetLng = vehicleLng + (smoothDrawLng - vehicleLng) * (snapBlend)
                smoothDrawLat = targetLat; smoothDrawLng = targetLng
            } else {
                smoothDrawLat = vehicleLat; smoothDrawLng = vehicleLng
            }
            hudView.isSnapped = snapBlend > 0.5
            hudView.vehicleLat = smoothDrawLat; hudView.vehicleLng = smoothDrawLng
        }

        hudView.vehicleBearing = vehicleBearing
        // v15.0: 路口场景用 EKF 的绘制速度（GPS 平滑速度）
        hudView.vehicleSpeed = (ekf.speed * 3.6).toFloat()

        hudView.invalidate()
    }

    private fun hmmMapMatchWithSigma(
        lat: Double, lng: Double, speedKmh: Float, bearing: Float, sigma: Double
    ): Pair<Double, Double>? {
        if (!hudView.hasRoads) { matchedSegIdx = -1; hmmConfidence = 0.0; return null }
        if (speedKmh < HMM_SPEED_GATE) return null

        val effectiveSigma = maxOf(sigma, 5.0)
        val sigma2 = 2.0 * effectiveSigma * effectiveSigma

        data class Candidate(val segIdx: Int, val projLat: Double, val projLng: Double, val dist: Double, val segHeading: Float)

        val candidates = mutableListOf<Candidate>()
        val segments = hudView.roadSegments

        for ((sIdx, seg) in segments.withIndex()) {
            val points = seg.points
            var segMinDist = Double.MAX_VALUE
            var segBestPLat = 0.0; var segBestPLng = 0.0; var segBestHeading = 0f

            for (i in 0 until points.size - 1) {
                val (lat1, lng1) = points[i]; val (lat2, lng2) = points[i + 1]
                val dx = lng2 - lng1; val dy = lat2 - lat1
                val lenSq = dx * dx + dy * dy
                if (lenSq < 1e-12) continue
                val t = ((lng - lng1) * dx + (lat - lat1) * dy) / lenSq
                val clampedT = t.coerceIn(0.0, 1.0)
                val projLat = lat1 + clampedT * dy; val projLng = lng1 + clampedT * dx
                val dist = RoadFetcher.haversine(lat, lng, projLat, projLng)
                if (dist < segMinDist) {
                    segMinDist = dist; segBestPLat = projLat; segBestPLng = projLng
                    segBestHeading = bearingBetween(lat1, lng1, lat2, lng2)
                }
            }
            if (segMinDist <= HMM_SEARCH_RADIUS) {
                candidates.add(Candidate(sIdx, segBestPLat, segBestPLng, segMinDist, segBestHeading))
            }
        }

        if (candidates.isEmpty()) { matchedSegIdx = -1; hmmConfidence = 0.0; return null }

        var bestScore = Double.NEGATIVE_INFINITY
        var bestCandidate: Candidate? = null
        for (cand in candidates) {
            val lnEmit = -(cand.dist * cand.dist) / sigma2
            val lnTransit = when {
                matchedSegIdx == -1 -> 0.0
                cand.segIdx == matchedSegIdx -> HMM_STAY_BONUS
                else -> {
                    val switchDist = RoadFetcher.haversine(matchedProjLat, matchedProjLng, cand.projLat, cand.projLng)
                    -switchDist / 10.0
                }
            }
            val headingDiff = abs(((cand.segHeading - bearing + 540f) % 360f) - 180f)
            val headingBonus = when {
                headingDiff < 45f -> 1.5 * (1.0 - headingDiff / 45.0)
                headingDiff > 135f -> -1.0
                else -> 0.0
            }
            val totalScore = lnEmit + lnTransit + headingBonus
            if (totalScore > bestScore) { bestScore = totalScore; bestCandidate = cand }
        }

        if (bestCandidate == null) { matchedSegIdx = -1; hmmConfidence = 0.0; return null }

        val bestEmit = exp(-(bestCandidate.dist * bestCandidate.dist) / sigma2)
        var totalEmit = 0.0
        for (cand in candidates) { totalEmit += exp(-(cand.dist * cand.dist) / sigma2) }
        hmmConfidence = if (totalEmit > 0) bestEmit / totalEmit else 0.0

        if (hmmConfidence < HMM_MIN_CONFIDENCE) { matchedSegIdx = -1; return null }

        matchedSegIdx = bestCandidate.segIdx
        matchedProjLat = bestCandidate.projLat; matchedProjLng = bestCandidate.projLng

        val distFade = maxOf(0.0, 1.0 - bestCandidate.dist / HMM_SEARCH_RADIUS)
        val blendRatio = (hmmConfidence * distFade).coerceIn(0.0, 1.0)
        return Pair(
            lat + (bestCandidate.projLat - lat) * blendRatio,
            lng + (bestCandidate.projLng - lng) * blendRatio
        )
    }

    private fun circularShortest(from: Float, to: Float): Float {
        val diff = ((to - from + 540f) % 360f) - 180f
        return (from + diff + 360f) % 360f
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, accData, 0, 3)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, magData, 0, 3)
            Sensor.TYPE_GYROSCOPE -> {
                System.arraycopy(event.values, 0, gyroData, 0, 3)
                val rawRate = -Math.toDegrees(event.values[2].toDouble()).toFloat()
                gyroHeadingRate = rawRate
                gyroHeadingRateSmooth += GYRO_SMOOTH * (rawRate - gyroHeadingRateSmooth)
                lastGyroTime = System.currentTimeMillis()
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotMat = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotMat, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotMat, orientation)
                val rawHeading = ((Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f)
                rvHeading = rawHeading
                if (!rvInitialized) { rvHeadingSmooth = rawHeading; rvInitialized = true }
                else {
                    val diff = ((rawHeading - rvHeadingSmooth + 540f) % 360f) - 180f
                    rvHeadingSmooth = ((rvHeadingSmooth + 0.15f * diff) + 360f) % 360f
                }
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val R = FloatArray(9); val I = FloatArray(9)
                if (SensorManager.getRotationMatrix(R, I, accData, magData)) {
                    for (i in 0..2) {
                        var sum = 0f
                        for (j in 0..2) { sum += R[i * 3 + j] * event.values[j] }
                        worldAcc[i] = sum
                    }
                    for (i in 0..2) { smoothedWorldAcc[i] += ACC_SMOOTH * (worldAcc[i] - smoothedWorldAcc[i]) }
                }
            }
        }

        val R = FloatArray(9); val Im = FloatArray(9)
        if (SensorManager.getRotationMatrix(R, Im, accData, magData)) {
            val o = FloatArray(3); SensorManager.getOrientation(R, o)
            val rawBearing = ((Math.toDegrees(o[0].toDouble()).toFloat() + 360f) % 360f)
            if (!compassInitialized) { smoothedCompassBearing = rawBearing; compassInitialized = true }
            else {
                val diff = ((rawBearing - smoothedCompassBearing + 540f) % 360f) - 180f
                smoothedCompassBearing = ((smoothedCompassBearing + COMPASS_EMA_ALPHA * diff) + 360f) % 360f
            }
            compassBearing = rawBearing
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startRenderLoop() { if (!renderRunning) { renderRunning = true; handler.post(renderRunnable) } }
    private fun stopRenderLoop() { renderRunning = false; handler.removeCallbacks(renderRunnable) }

    override fun onResume() {
        super.onResume()
        startRenderLoop()
        startHudForegroundService()
        if (wakeWordEnabled) {
            voicePipeline?.start()
        }
    }

    override fun onPause() {
        stopRenderLoop()
        voicePipeline?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        stopRenderLoop()
        voicePipeline?.release()
        apiServer?.stopServer()
        locationManager.removeUpdates(this)
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
        roadFetchJob?.cancel(); scope.cancel()
        stopService(Intent(this, HudForegroundService::class.java))
        super.onDestroy()
    }

    /**
     * 唤醒词触发后的视觉反馈：
     * 镜像模式：顶部面板下滑；正常模式：底部面板上滑
     */
    private fun showWakeFeedback(keyword: String) {
        // 取消之前的隐藏计时器
        wakeFeedbackHideRunnable?.let { handler.removeCallbacks(it) }

        wakeFeedback.visibility = View.VISIBLE
        wakeFeedbackKeyword.text = keyword

        // 设置起始位置：镜像时在屏幕上方外（-120dp），正常时在屏幕下方外（+120dp）
        val hideOffsetPx = if (hudView.mirrorEnabled) -dpToPx(120) else dpToPx(120)
        wakeFeedbackPanel.translationY = hideOffsetPx

        // 滑入动画：滑到 translationY=0
        wakeFeedbackPanel.animate()
            .translationY(0f)
            .setDuration(300)
            .alpha(1f)
            .withStartAction {
                wakeFeedbackPanel.alpha = 0f
            }
            .start()

        // 不再自动隐藏！面板在整个 唤醒→录音→处理→播报 流程中一直显示
        // 仅在 PipelineState.IDLE 时由 state 回调触发 hideWakeFeedback()
        // 安全兜底：30秒后强制隐藏（防止流程卡死）
        wakeFeedbackHideRunnable = Runnable { hideWakeFeedback() }
        handler.postDelayed(wakeFeedbackHideRunnable!!, 30000)
    }

    private fun hideWakeFeedback() {
        wakeFeedbackHideRunnable?.let { handler.removeCallbacks(it) }
        wakeFeedbackHideRunnable = null

        // 镜像时向上滑出（-120dp），正常时向下滑出（+120dp）
        val hideOffsetPx = if (hudView.mirrorEnabled) -dpToPx(120) else dpToPx(120)

        wakeFeedbackPanel.animate()
            .translationY(hideOffsetPx)
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                wakeFeedback.visibility = View.GONE
            }
            .start()
    }

    /**
     * 镜像模式切换时更新唤醒反馈面板位置
     * 不再使用 scaleY 翻转（会破坏 translationY 动画），
     * 改为动态 gravity：镜像时面板在顶部，正常时在底部
     */
    private fun updateWakeFeedbackMirror() {
        val lp = wakeFeedbackPanel.layoutParams as FrameLayout.LayoutParams
        if (hudView.mirrorEnabled) {
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // 镜像模式：面板在顶部，文字垂直翻转（与 HudView 画面一致）
            wakeFeedbackPanel.scaleY = -1f
        } else {
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // 正常模式：面板在底部，文字正常方向
            wakeFeedbackPanel.scaleY = 1f
        }
        wakeFeedbackPanel.layoutParams = lp
        // 如果面板当前不可见，设置隐藏位置的 translationY
        if (wakeFeedback.visibility != View.VISIBLE) {
            val hideOffsetPx = if (hudView.mirrorEnabled) -dpToPx(120) else dpToPx(120)
            wakeFeedbackPanel.translationY = hideOffsetPx
        }
        Log.i(TAG, "Mirror updated: ${hudView.mirrorEnabled}, panel gravity=${lp.gravity}, scaleY=${wakeFeedbackPanel.scaleY}")
    }

    private fun dpToPx(dp: Int): Float {
        return dp * resources.displayMetrics.density
    }

    // v15.0: MapLibre 已移除，地图渲染由 HudView Canvas 2D 引擎处理
}
