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

    // === 路口检测 ===
    private var intersections: List<IntersectionNode> = emptyList()
    private var nearIntersection = false
    private var matchedBranchHeading = Float.NaN
    private var lastIntersectionTime = 0L
    private var branchLockFrames = 0

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
        // v10.11: 地图刷新优化 — 距离阈值降低，间隔增大，减少频繁刷新
        private const val ROAD_FETCH_DIST = 500.0
        private const val ROAD_FETCH_INTERVAL = 15000L
        private const val COMPASS_EMA_ALPHA = 0.08f
        private const val HEADING_DEAD_ZONE = 2.5f
        private const val FREEZE_ACC_THRESHOLD = 0.5f
        private const val FREEZE_SPEED_THRESHOLD = 2f
        private const val INTERSECTION_NODE_DIST = 15.0
        private const val INTERSECTION_DETECT_RADIUS = 50.0
        private const val BRANCH_SAMPLE_DIST = 30.0
        private const val BRANCH_HEADING_TOLERANCE = 35f
        private const val BRANCH_LOCK_MIN_FRAMES = 30
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

    data class IntersectionNode(
        val lat: Double,
        val lng: Double,
        val branches: List<BranchInfo>
    )

    data class BranchInfo(
        val heading: Float,
        val segmentIdx: Int,
        val pointIdx: Int,
        val nextLat: Double,
        val nextLng: Double
    )

    private fun buildIntersections(segments: List<RoadFetcher.RoadSegment>): List<IntersectionNode> {
        if (segments.isEmpty()) return emptyList()

        val endpoints = mutableListOf<Triple<Double, Double, Pair<Int, Int>>>()
        for ((sIdx, seg) in segments.withIndex()) {
            if (seg.points.size < 2) continue
            endpoints.add(Triple(seg.points.first().first, seg.points.first().second, Pair(sIdx, 0)))
            endpoints.add(Triple(seg.points.last().first, seg.points.last().second, Pair(sIdx, seg.points.size - 1)))
        }

        val used = BooleanArray(endpoints.size)
        val clusters = mutableListOf<List<Triple<Double, Double, Pair<Int, Int>>>>()

        for (i in endpoints.indices) {
            if (used[i]) continue
            val cluster = mutableListOf(endpoints[i])
            used[i] = true
            for (j in i + 1 until endpoints.size) {
                if (used[j]) continue
                val d = RoadFetcher.haversine(endpoints[i].first, endpoints[i].second,
                    endpoints[j].first, endpoints[j].second)
                if (d < INTERSECTION_NODE_DIST) {
                    cluster.add(endpoints[j])
                    used[j] = true
                }
            }
            clusters.add(cluster)
        }

        val result = mutableListOf<IntersectionNode>()
        for (cluster in clusters) {
            val distinctSegments = cluster.map { it.third.first }.toSet()
            if (distinctSegments.size < 2) continue

            val centerLat = cluster.map { it.first }.average()
            val centerLng = cluster.map { it.second }.average()

            val branches = mutableListOf<BranchInfo>()
            for (ep in cluster) {
                val sIdx = ep.third.first
                val pIdx = ep.third.second
                val seg = segments[sIdx]

                val forwardIdx = when {
                    pIdx == 0 && seg.points.size > 1 -> 1
                    pIdx == seg.points.size - 1 && seg.points.size > 1 -> pIdx - 1
                    else -> continue
                }

                val (fromLat, fromLng) = seg.points[pIdx]
                val (toLat, toLng) = seg.points[forwardIdx]
                val heading = bearingBetween(fromLat, fromLng, toLat, toLng)
                val finalHeading = if (pIdx > 0 && pIdx == seg.points.size - 1) {
                    (heading + 180f) % 360f
                } else heading

                val headingRad = Math.toRadians(finalHeading.toDouble())
                val sampleLat = centerLat + BRANCH_SAMPLE_DIST * cos(headingRad) / 111111.0
                val sampleLng = centerLng + BRANCH_SAMPLE_DIST * sin(headingRad) / (111111.0 * cos(Math.toRadians(centerLat)))

                branches.add(BranchInfo(finalHeading, sIdx, pIdx, sampleLat, sampleLng))
            }

            if (branches.size >= 2) {
                result.add(IntersectionNode(centerLat, centerLng, branches))
            }
        }

        Log.i(TAG, "Built ${result.size} intersection nodes from ${segments.size} segments")
        return result
    }

    private fun matchBranchAtIntersection(lat: Double, lng: Double, compass: Float): BranchInfo? {
        if (!nearIntersection) return null
        var nearestInt: IntersectionNode? = null
        var nearestDist = Double.MAX_VALUE
        for (inter in intersections) {
            val d = RoadFetcher.haversine(lat, lng, inter.lat, inter.lng)
            if (d < nearestDist) { nearestDist = d; nearestInt = inter }
        }
        if (nearestInt == null || nearestDist > INTERSECTION_DETECT_RADIUS) return null

        var bestBranch: BranchInfo? = null
        var bestDiff = Float.MAX_VALUE
        for (branch in nearestInt.branches) {
            val diff = abs(((branch.heading - compass + 540f) % 360f) - 180f)
            if (diff < bestDiff) { bestDiff = diff; bestBranch = branch }
        }
        if (bestBranch == null || bestDiff > BRANCH_HEADING_TOLERANCE) return null
        return bestBranch
    }

    private fun snapToRoadAtIntersection(
        lat: Double, lng: Double, speedKmh: Float, branch: BranchInfo
    ): Pair<Double, Double>? {
        if (!hudView.hasRoads) return null
        val segment = hudView.roadSegments.getOrNull(branch.segmentIdx) ?: return null
        val points = segment.points
        if (points.size < 2) return null

        var minDist = Double.MAX_VALUE
        var bestLat = lat; var bestLng = lng
        for (i in 0 until points.size - 1) {
            val (lat1, lng1) = points[i]; val (lat2, lng2) = points[i + 1]
            val dx = lng2 - lng1; val dy = lat2 - lat1
            val lenSq = dx * dx + dy * dy
            if (lenSq < 1e-12) continue
            val t = ((lng - lng1) * dx + (lat - lat1) * dy) / lenSq
            val clampedT = t.coerceIn(0.0, 1.0)
            val projLat = lat1 + clampedT * dy; val projLng = lng1 + clampedT * dx
            val dist = RoadFetcher.haversine(lat, lng, projLat, projLng)
            if (dist < minDist) { minDist = dist; bestLat = projLat; bestLng = projLng }
        }
        if (minDist > INTERSECTION_DETECT_RADIUS) return null
        val blendRatio = 0.8
        return Pair(lat + (bestLat - lat) * blendRatio, lng + (bestLng - lng) * blendRatio)
    }

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
        setContentView(R.layout.activity_main)

        flipContainer = findViewById(R.id.flipContainer)
        permDeniedLayout = findViewById(R.id.permDeniedLayout)
        btnRetryPerm = findViewById(R.id.btnRetryPerm)
        hudView = findViewById(R.id.hudView)

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
            intersections = buildIntersections(diskSegments)
            RoadFetcher.markRendered()
            Log.i(TAG, "Loaded ${diskSegments.size} cached segments at startup (marked as rendered)")
        }

        // 双击切换镜像
        // 初始化：镜像默认开启，唤醒反馈面板位置同步（顶部/底部）
        updateWakeFeedbackMirror()

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                hudView.mirrorEnabled = !hudView.mirrorEnabled
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
            })

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
                updateWakeFeedbackMirror()
            }
            command == "MIRROR_OFF" -> {
                hudView.mirrorEnabled = false
                updateWakeFeedbackMirror()
            }
            command == "MAP_ZOOM_IN" -> {
                hudView.zoomOffset = (hudView.zoomOffset + 1.0f).coerceAtMost(3.0f)
                hudView.invalidate()
            }
            command == "MAP_ZOOM_OUT" -> {
                hudView.zoomOffset = (hudView.zoomOffset - 1.0f).coerceAtLeast(-3.0f)
                hudView.invalidate()
            }
            command == "MAP_ZOOM_RESET" -> {
                hudView.zoomOffset = 0f
                hudView.invalidate()
            }
            command.startsWith("🔍") -> {
                wakeFeedbackKeyword.text = command
            }
            else -> {
                wakeFeedbackKeyword.text = command
            }
        }
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

        tryFetchRoads()
    }

    private fun tryFetchRoads() {
        if (targetLat == 0.0) return
        val now = System.currentTimeMillis()
        // v10.11: 地图刷新逻辑优化 — 以路网缓存中心为基准
        // 只有车辆移动到离缓存中心超过 ROAD_FETCH_DIST 时才刷新
        // 避免因 GPS 抖动或缓慢移动频繁触发刷新
        val distFromCenter = RoadFetcher.haversine(targetLat, targetLng, lastRoadFetchLat, lastRoadFetchLng)
        val timeSince = now - lastRoadFetchTime

        // 条件1: 距离缓存中心超过阈值
        // 条件2: 时间间隔已过 且 缓存不再有效（距离超过 800m 缓存边界）
        val shouldFetch = (distFromCenter > ROAD_FETCH_DIST) ||
            (timeSince > ROAD_FETCH_INTERVAL && !RoadFetcher.isCacheValid(targetLat, targetLng))

        if (shouldFetch) {
            lastRoadFetchLat = targetLat; lastRoadFetchLng = targetLng
            lastRoadFetchTime = now

            roadFetchJob?.cancel()
            roadFetchJob = scope.launch {
                val result = RoadFetcher.fetchRoads(targetLat, targetLng)
                // 只有数据变了才重绘，避免无谓的屏幕刷新
                if (result.needRerender) {
                    hudView.setRoads(result.segments, targetLat, targetLng)
                    intersections = buildIntersections(result.segments)
                    RoadFetcher.markRendered()
                    Log.i(TAG, "Roads updated: ${result.segments.size} segments, ${intersections.size} intersections (data changed)")
                } else {
                    Log.i(TAG, "Roads unchanged (hash match), skip rerender")
                }
            }
        }
    }

    private fun updateFrame() {
        if (targetLat == 0.0) return

        val now = System.currentTimeMillis()
        val dt = if (lastFrameTime == 0L) 16L else (now - lastFrameTime).coerceIn(1L, 100L)
        lastFrameTime = now

        detectIntersection()

        // === v10.20: 传递罗盘数据给 EKF ===
        if (hasRotationVector && rvInitialized) {
            ekf.updateCompass(rvHeadingSmooth)
        } else if (compassInitialized) {
            ekf.updateCompass(compassBearing)
        }

        // v10.21: 传递线性加速度幅度给 EKF，用于零速检测（ZUPT）
        val linearAccelMag = kotlin.math.sqrt(
            smoothedWorldAcc[0].toDouble() * smoothedWorldAcc[0].toDouble() +
            smoothedWorldAcc[1].toDouble() * smoothedWorldAcc[1].toDouble() +
            smoothedWorldAcc[2].toDouble() * smoothedWorldAcc[2].toDouble()
        )
        // v13.5: 传入世界坐标系加速度分量（北/东），让 EKF 积分速度
        // smoothedWorldAcc[0]=North, [1]=East, [2]=Up（已通过旋转矩阵转换）
        ekf.predict(dt, vehicleBearing, targetSpeed * 1000f / 3600f, linearAccelMag,
            worldAccN = smoothedWorldAcc[0].toDouble(),
            worldAccE = smoothedWorldAcc[1].toDouble())
        vehicleLat = ekf.lat; vehicleLng = ekf.lng

        val gyroActive = hasGyro && (System.currentTimeMillis() - lastGyroTime) < 500
        val dtSec = dt / 1000.0

        var headingFromGyro = vehicleBearing
        if (gyroActive && abs(gyroHeadingRateSmooth) > 0.3f) {
            headingFromGyro = vehicleBearing + gyroHeadingRateSmooth * dtSec.toFloat()
        }

        val newBearing = when {
            nearIntersection && !matchedBranchHeading.isNaN() -> {
                val diff = ((matchedBranchHeading - headingFromGyro + 540f) % 360f) - 180f
                if (abs(diff) < HEADING_DEAD_ZONE) headingFromGyro
                else headingFromGyro + diff * 0.3f
            }
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
            else -> headingFromGyro
        }
        vehicleBearing = ((newBearing % 360f) + 360f) % 360f

        // 道路吸附
        val ekfUncertainty = ekf.getPositionUncertainty()
        val adaptiveSigma = maxOf(HMM_SIGMA, ekfUncertainty)

        var snapped: Pair<Double, Double>? = null
        if (nearIntersection && !matchedBranchHeading.isNaN()) {
            val branch = matchBranchAtIntersection(vehicleLat, vehicleLng, vehicleBearing)
            if (branch != null) {
                snapped = snapToRoadAtIntersection(vehicleLat, vehicleLng, targetSpeed, branch)
            }
        }
        if (snapped == null) {
            snapped = hmmMapMatchWithSigma(vehicleLat, vehicleLng, targetSpeed, vehicleBearing, adaptiveSigma)
        }

        // === v10.20: 路网吸附仅作为微调注入 EKF（不再控制惯导激活） ===
        if (snapped != null && hmmConfidence > HMM_MIN_CONFIDENCE) {
            ekf.snappedToRoad = true
            // 计算吸附路段走向
            if (matchedSegIdx >= 0 && matchedSegIdx < hudView.roadSegments.size) {
                val seg = hudView.roadSegments[matchedSegIdx]
                if (seg.points.size >= 2) {
                    val midIdx = seg.points.size / 2
                    val (p1Lat, p1Lng) = seg.points[maxOf(0, midIdx - 1)]
                    val (p2Lat, p2Lng) = seg.points[minOf(seg.points.size - 1, midIdx)]
                    ekf.roadHeadingDeg = bearingBetween(p1Lat, p1Lng, p2Lat, p2Lng).toDouble()
                }
            }
            // 路网约束微调（修正 IMU 累积漂移）
            if (hmmConfidence > 0.3) {
                ekf.roadConstrainedUpdate(
                    snapped.first, snapped.second,
                    hmmConfidence, ekf.roadHeadingDeg.toFloat(),
                    System.currentTimeMillis()
                )
            }
        } else {
            ekf.snappedToRoad = false
            ekf.snapConfidence = 0.0
        }

        // === v10.20: 平滑绘制位置（更保守的吸附混合，减少跳变） ===
        if (!snapInit) {
            smoothDrawLat = vehicleLat; smoothDrawLng = vehicleLng; snapInit = true
        }
        // v10.20: 更慢的融入/退出速度，避免突然跳变
        val snapAlphaUp = 0.15    // 吸附融入更慢（~7帧达到95%，约0.12秒）
        val snapAlphaDown = 0.08  // 退出更平滑（~12帧归零，约0.2秒）

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
        hudView.vehicleSpeed = (ekf.speed * 3.6).toFloat()

        hudView.invalidate()
    }

    private fun detectIntersection() {
        if (intersections.isEmpty()) {
            nearIntersection = false; matchedBranchHeading = Float.NaN; return
        }

        var inIntersection = false
        for (inter in intersections) {
            if (RoadFetcher.haversine(vehicleLat, vehicleLng, inter.lat, inter.lng) < INTERSECTION_DETECT_RADIUS) {
                inIntersection = true; break
            }
        }

        if (inIntersection) {
            nearIntersection = true; lastIntersectionTime = System.currentTimeMillis()
            val branch = matchBranchAtIntersection(vehicleLat, vehicleLng, vehicleBearing)
            if (branch != null) {
                matchedBranchHeading = branch.heading; branchLockFrames++
            } else if (branchLockFrames < BRANCH_LOCK_MIN_FRAMES) {
                matchedBranchHeading = Float.NaN
            }
        } else {
            if (nearIntersection && branchLockFrames > 0) {
                branchLockFrames--
                if (branchLockFrames == 0) { nearIntersection = false; matchedBranchHeading = Float.NaN }
            } else {
                nearIntersection = false; matchedBranchHeading = Float.NaN; branchLockFrames = 0
            }
        }
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
                // v14.0: 原始加速度计+陀螺仪传给 EKF 做姿态解算
                if (gyroData[0] != 0f || gyroData[1] != 0f || gyroData[2] != 0f) {
                    ekf.updateIMU(event.values, gyroData, event.timestamp)
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, magData, 0, 3)
            Sensor.TYPE_GYROSCOPE -> {
                System.arraycopy(event.values, 0, gyroData, 0, 3)
                val rawRate = -Math.toDegrees(event.values[2].toDouble()).toFloat()
                gyroHeadingRate = rawRate
                gyroHeadingRateSmooth += GYRO_SMOOTH * (rawRate - gyroHeadingRateSmooth)
                lastGyroTime = System.currentTimeMillis()
                ekf.updateGyroRate(gyroHeadingRateSmooth)
                // v14.0: 也把陀螺仪数据传给 EKF 做姿态解算
                if (accData[0] != 0f || accData[1] != 0f || accData[2] != 0f) {
                    ekf.updateIMU(accData, event.values, event.timestamp)
                }
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
                // v12.5: 旋转矢量航向传给 EKF，低速时比纯罗盘稳定得多
                ekf.updateRotationVector(rvHeadingSmooth)
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
        // v11.0: 使用 VoicePipeline 管理唤醒词监听
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
}
