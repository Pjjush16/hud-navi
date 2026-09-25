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
import kotlin.math.*

/**
 * HUD 导航 v9.3 — 路口指南针辅助转向
 *
 * v9.2 → v9.3:
 * - 路口检测：识别路网中 2+ 路段共享的交叉点
 * - 分支朝向计算：每个路口出口方向的方位角
 * - 指南针匹配：在路口附近用平滑指南针判定用户转入了哪个分支
 * - 路口吸附增强：路口区域 50m 阈值，优先按方向匹配吸附
 * - 路口后惯导锁定：转弯完成后沿匹配道路方向继续惯导推进
 */
class MainActivity : AppCompatActivity(), LocationListener, SensorEventListener {

    private lateinit var hudView: HudView
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private val handler = Handler(Looper.getMainLooper())

    // === GPS 抗跳变滤波器 ===
    private val gpsFilter = GpsFilter()

    // === UI（仅权限重试） ===
    private lateinit var flipContainer: FrameLayout
    private lateinit var permDeniedLayout: LinearLayout
    private lateinit var btnRetryPerm: TextView

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var roadFetchJob: Job? = null

    // === GPS 目标（GPS 滤波器输出） ===
    private var targetLat = 0.0; private var targetLng = 0.0
    private var targetBearing = 0f; private var targetSpeed = 0f

    // === IMU 惯导状态（替代旧的追逐式插值） ===
    // vehicleLat/vehicleLng 就是当前显示位置，不再区分"目标"和"显示"
    private var vehicleLat = 0.0; private var vehicleLng = 0.0
    private var vehicleBearing = 0f
    private var lastFrameTime = 0L
    private val FRAME_MS = 16L

    // 惯导速度：GPS 到达时锁定，GPS 丢失后指数衰减
    private var imuSpeedKmh = 0f
    private val VELOCITY_DECAY = 0.995f          // GPS 丢失后每帧速度衰减

    // === 路口检测 + 分支匹配 ===
    private var intersections: List<IntersectionNode> = emptyList()
    private var nearIntersection = false
    private var matchedBranchHeading = Float.NaN  // 匹配到的分支朝向（NaN = 无匹配）
    private var lastIntersectionTime = 0L         // 上次在路口的时间
    private var branchLockFrames = 0              // 分支锁定帧数（防抖）

    // 传感器原始数据
    private val accData = FloatArray(3)
    private val magData = FloatArray(3)
    private var hasCompass = false
    private var compassBearing = 0f              // 指南针原始值（不直接使用）
    private var smoothedCompassBearing = 0f      // EMA 平滑后的指南针
    private var compassInitialized = false       // 首次初始化标记
    private val worldAcc = FloatArray(3)         // 世界坐标系加速度（北/东/上）
    private val smoothedWorldAcc = FloatArray(3) // 平滑后的世界加速度
    private val ACC_SMOOTH = 0.15f               // 加速度 EMA 平滑系数

    // === 状态 ===
    private var gpsFixCount = 0
    private var lastGpsTime = 0L
    private var lastRoadFetchLat = 0.0
    private var lastRoadFetchLng = 0.0
    private var lastRoadFetchTime = 0L

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
        private const val ROAD_FETCH_DIST = 150.0
        private const val ROAD_FETCH_INTERVAL = 5000L
        // === 指南针抗抖动参数 ===
        private const val COMPASS_EMA_ALPHA = 0.08f   // EMA 平滑系数（越小越平滑，0.05~0.15）
        private const val HEADING_DEAD_ZONE = 2.5f    // 航向死区（度）：小于此变化直接忽略
        private const val GPS_BEARING_SPEED = 5f      // GPS 航向优先的速度阈值（km/h）
        private const val FREEZE_ACC_THRESHOLD = 0.5f // 静止冻结的加速度阈值（m/s²）
        private const val FREEZE_SPEED_THRESHOLD = 2f // 静止冻结的速度阈值（km/h）
        // === 路口检测参数 ===
        private const val INTERSECTION_NODE_DIST = 15.0  // 端点距离 < 15m 视为同一节点
        private const val INTERSECTION_DETECT_RADIUS = 50.0  // 路口检测半径（m）
        private const val BRANCH_SAMPLE_DIST = 30.0  // 分支朝向采样距离（m）
        private const val BRANCH_HEADING_TOLERANCE = 35f  // 分支匹配角度容差（°）
        private const val BRANCH_LOCK_MIN_FRAMES = 30  // 分支锁定最少帧数（防抖 0.5s）
    }

    // === 速度分级吸附参数 ===
    // 吸附状态（滞后机制，防止边缘抖动）
    private var snapLocked = false              // 当前是否处于吸附锁定状态
    private var snapLockFrames = 0              // 已锁定帧数
    private val SNAP_LOCK_MIN_FRAMES = 60       // 最少锁定 60 帧（1s），防止闪断
    private val SNAP_HYSTERESIS_RATIO = 1.5     // 脱锁阈值 = 吸锁阈值 × 1.5

    private fun getSnapParams(speedKmh: Float): Pair<Double, Double> {
        return when {
            speedKmh < 5f  -> Pair(0.0, 0.0)     // 0–5: 不吸附
            speedKmh < 15f -> Pair(30.0, 0.3)     // 5–15: 弱吸附
            speedKmh < 30f -> Pair(25.0, 0.6)     // 15–30: 中等吸附
            else           -> Pair(20.0, 1.0)     // 30+: 正常吸附
        }
    }

    /**
     * 速度分级道路吸附（带滞后防抖）
     *
     * 滞后机制：
     * - 吸锁阈值 = getSnapParams 返回值（如 20m）
     * - 脱锁阈值 = 吸锁阈值 × 1.5（如 30m）
     * - 一旦锁定，至少保持 SNAP_LOCK_MIN_FRAMES 帧（1s），防止反复开关
     * - 锁定期间即使飘到脱锁阈值外，仍然保持吸附
     * - 锁定帧数够且飘出脱锁阈值 → 释放锁定
     */
    private fun snapToRoadSpeedAware(lat: Double, lng: Double, speedKmh: Float): Pair<Double, Double>? {
        if (!hudView.hasRoads) {
            snapLocked = false
            snapLockFrames = 0
            return null
        }
        val (baseThreshold, blendRatio) = getSnapParams(speedKmh)
        if (baseThreshold <= 0.0) {
            // 低速不吸附，但如果之前锁定了，平滑释放
            snapLocked = false
            snapLockFrames = 0
            return null
        }

        // 脱锁阈值比吸锁阈值宽 50%（滞后带）
        val lockThreshold = baseThreshold       // 进入吸附的距离
        val unlockThreshold = baseThreshold * SNAP_HYSTERESIS_RATIO  // 脱离吸附的距离

        // 计算到最近道路的投影距离
        var minDist = Double.MAX_VALUE
        var bestLat = lat
        var bestLng = lng

        for (segment in hudView.roadSegments) {
            val points = segment.points
            for (i in 0 until points.size - 1) {
                val (lat1, lng1) = points[i]
                val (lat2, lng2) = points[i + 1]

                val dx = lng2 - lng1
                val dy = lat2 - lat1
                val lenSq = dx * dx + dy * dy
                if (lenSq < 1e-12) continue

                val t = ((lng - lng1) * dx + (lat - lat1) * dy) / lenSq
                val clampedT = t.coerceIn(0.0, 1.0)

                val projLat = lat1 + clampedT * dy
                val projLng = lng1 + clampedT * dx

                val dist = RoadFetcher.haversine(lat, lng, projLat, projLng)
                if (dist < minDist) {
                    minDist = dist
                    bestLat = projLat
                    bestLng = projLng
                }
            }
        }

        // ── 滞后状态机 ──
        val inLockRange = minDist <= lockThreshold
        val inUnlockRange = minDist > unlockThreshold

        when {
            // 已锁定 + 仍在锁定范围内 → 保持锁定，刷新帧计数
            snapLocked && !inUnlockRange -> {
                snapLockFrames++
            }
            // 已锁定 + 飘出脱锁范围 + 锁定帧数够 → 释放
            snapLocked && inUnlockRange && snapLockFrames >= SNAP_LOCK_MIN_FRAMES -> {
                snapLocked = false
                snapLockFrames = 0
            }
            // 已锁定 + 飘出脱锁范围 + 锁定帧数不够 → 强制保持锁定
            snapLocked && inUnlockRange && snapLockFrames < SNAP_LOCK_MIN_FRAMES -> {
                snapLockFrames++
                // 不释放，继续吸附
            }
            // 未锁定 + 进入吸锁范围 → 锁定
            !snapLocked && inLockRange -> {
                snapLocked = true
                snapLockFrames = 0
            }
            // 未锁定 + 在滞后带内（lockThreshold < dist < unlockThreshold） → 不操作
            // 未锁定 + 超出脱锁范围 → 不吸附
        }

        if (!snapLocked) return null

        // 吸附：按混合比修正位置
        // 锁定期间如果距离变远，混合比动态降低（避免把箭头拉到太远的路上）
        val effectiveBlend = if (minDist > lockThreshold) {
            // 在滞后带内：随距离增大逐渐减弱混合比
            val fade = ((unlockThreshold - minDist) / (unlockThreshold - lockThreshold)).coerceIn(0.0, 1.0)
            blendRatio * fade
        } else {
            blendRatio
        }

        val snappedLat = lat + (bestLat - lat) * effectiveBlend
        val snappedLng = lng + (bestLng - lng) * effectiveBlend
        return Pair(snappedLat, snappedLng)
    }

    // ═══════════════════════════════════════════════════════
    // 路口检测 + 指南针分支匹配
    // ═══════════════════════════════════════════════════════

    /**
     * 路口节点：多个路段共享的交叉点
     * @param lat 路口纬度
     * @param lng 路口经度
     * @param branches 从路口出发的各分支（段起点方向 + 采样点）
     */
    data class IntersectionNode(
        val lat: Double,
        val lng: Double,
        val branches: List<BranchInfo>
    )

    /**
     * 分支信息：从路口出发的一个方向
     * @param heading 该分支的起始朝向（度，0=北）
     * @param segmentIdx 所属路段索引
     * @param pointIdx 该分支在路段中的起始点索引
     * @param nextLat 采样点纬度（路口后 30m 处）
     * @param nextLng 采样点经度
     */
    data class BranchInfo(
        val heading: Float,
        val segmentIdx: Int,
        val pointIdx: Int,
        val nextLat: Double,
        val nextLng: Double
    )

    /**
     * 构建路口节点列表（在路网数据更新时调用）
     *
     * 算法：
     * 1. 收集所有路段的端点
     * 2. 距离 < 15m 的端点合并为同一节点
     * 3. 有 2+ 路段共享的节点 = 路口
     * 4. 从路口出发计算每个分支的朝向（沿路段走 30m 采样）
     */
    private fun buildIntersections(segments: List<RoadFetcher.RoadSegment>): List<IntersectionNode> {
        if (segments.isEmpty()) return emptyList()

        // 1. 收集所有端点 → (lat, lng, segmentIdx, pointIdx)
        val endpoints = mutableListOf<Triple<Double, Double, Pair<Int, Int>>>()
        for ((sIdx, seg) in segments.withIndex()) {
            if (seg.points.size < 2) continue
            // 只取路段的首尾端点
            endpoints.add(Triple(seg.points.first().first, seg.points.first().second, Pair(sIdx, 0)))
            endpoints.add(Triple(seg.points.last().first, seg.points.last().second, Pair(sIdx, seg.points.size - 1)))
        }

        // 2. 聚类：距离 < 15m 的端点合并
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

        // 3. 筛选 2+ 不同路段共享的节点 = 路口
        val result = mutableListOf<IntersectionNode>()
        for (cluster in clusters) {
            val distinctSegments = cluster.map { it.third.first }.toSet()
            if (distinctSegments.size < 2) continue

            // 路口坐标 = 聚类中心
            val centerLat = cluster.map { it.first }.average()
            val centerLng = cluster.map { it.second }.average()

            // 4. 计算每个分支的朝向
            val branches = mutableListOf<BranchInfo>()
            for (ep in cluster) {
                val sIdx = ep.third.first
                val pIdx = ep.third.second
                val seg = segments[sIdx]

                // 确定前进方向：从端点向路段内部走
                val forwardIdx = when {
                    pIdx == 0 && seg.points.size > 1 -> 1  // 端点是起点，向前看第二个点
                    pIdx == seg.points.size - 1 && seg.points.size > 1 -> pIdx - 1  // 端点是终点，向后看
                    else -> continue
                }

                val (fromLat, fromLng) = seg.points[pIdx]
                val (toLat, toLng) = seg.points[forwardIdx]

                // 计算朝向
                val heading = bearingBetween(fromLat, fromLng, toLat, toLng)

                // 如果是端点是终点（backward），需要翻转方向
                val finalHeading = if (pIdx > 0 && pIdx == seg.points.size - 1) {
                    (heading + 180f) % 360f
                } else heading

                // 采样点：从路口沿分支方向走 30m
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

    /**
     * 在路口附近时，用指南针匹配用户转入的分支
     *
     * @return 匹配到的分支信息，或 null（无匹配）
     */
    private fun matchBranchAtIntersection(
        lat: Double, lng: Double, compass: Float
    ): BranchInfo? {
        if (!nearIntersection || !hasCompass) return null

        // 找最近的路口
        var nearestInt: IntersectionNode? = null
        var nearestDist = Double.MAX_VALUE
        for (inter in intersections) {
            val d = RoadFetcher.haversine(lat, lng, inter.lat, inter.lng)
            if (d < nearestDist) {
                nearestDist = d
                nearestInt = inter
            }
        }
        if (nearestInt == null || nearestDist > INTERSECTION_DETECT_RADIUS) return null

        // 在路口各分支中找最匹配指南针的
        var bestBranch: BranchInfo? = null
        var bestDiff = Float.MAX_VALUE
        for (branch in nearestInt.branches) {
            val diff = abs(((branch.heading - compass + 540f) % 360f) - 180f)
            if (diff < bestDiff) {
                bestDiff = diff
                bestBranch = branch
            }
        }

        // 容差检查：最佳匹配的角度差不能太大
        if (bestBranch == null || bestDiff > BRANCH_HEADING_TOLERANCE) return null

        return bestBranch
    }

    /**
     * 路口增强的道路吸附
     * 在路口附近优先按匹配分支方向吸附，而不是简单最近距离
     */
    private fun snapToRoadAtIntersection(
        lat: Double, lng: Double, speedKmh: Float, branch: BranchInfo
    ): Pair<Double, Double>? {
        if (!hudView.hasRoads) return null

        val segment = hudView.roadSegments.getOrNull(branch.segmentIdx) ?: return null
        val points = segment.points
        if (points.size < 2) return null

        // 在匹配分支所属路段上做投影吸附
        var minDist = Double.MAX_VALUE
        var bestLat = lat
        var bestLng = lng

        for (i in 0 until points.size - 1) {
            val (lat1, lng1) = points[i]
            val (lat2, lng2) = points[i + 1]

            val dx = lng2 - lng1
            val dy = lat2 - lat1
            val lenSq = dx * dx + dy * dy
            if (lenSq < 1e-12) continue

            val t = ((lng - lng1) * dx + (lat - lat1) * dy) / lenSq
            val clampedT = t.coerceIn(0.0, 1.0)

            val projLat = lat1 + clampedT * dy
            val projLng = lng1 + clampedT * dx

            val dist = RoadFetcher.haversine(lat, lng, projLat, projLng)
            if (dist < minDist) {
                minDist = dist
                bestLat = projLat
                bestLng = projLng
            }
        }

        // 路口附近阈值放宽到 50m，混合比 80%（强吸附到匹配分支）
        if (minDist > INTERSECTION_DETECT_RADIUS) return null
        val blendRatio = 0.8
        val snappedLat = lat + (bestLat - lat) * blendRatio
        val snappedLng = lng + (bestLng - lng) * blendRatio
        return Pair(snappedLat, snappedLng)
    }

    /**
     * 两点之间的方位角（度，0=北，顺时针）
     */
    private fun bearingBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val dLng = lng2 - lng1
        val y = sin(Math.toRadians(dLng)) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(Math.toRadians(dLng))
        val bearing = Math.toDegrees(atan2(y, x))
        return ((bearing.toFloat() + 360f) % 360f)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        flipContainer = findViewById(R.id.flipContainer)
        permDeniedLayout = findViewById(R.id.permDeniedLayout)
        btnRetryPerm = findViewById(R.id.btnRetryPerm)
        hudView = findViewById(R.id.hudView)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        btnRetryPerm.setOnClickListener {
            permDeniedLayout.visibility = View.GONE
            requestPermissions()
        }

        startHudForegroundService()
        requestPermissions()
    }

    private fun startHudForegroundService() {
        val intent = Intent(this, HudForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
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
        if (requestCode == PERM_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                permDeniedLayout.visibility = View.GONE
                startLocationUpdates()
            } else {
                permDeniedLayout.visibility = View.VISIBLE
                hudView.statusText = "需要定位权限"
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
        val mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val acc = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val linAcc = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (mag != null && acc != null) {
            sensorManager.registerListener(this, mag, SensorManager.SENSOR_DELAY_GAME)
            sensorManager.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
            hasCompass = true
        }
        linAcc?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onLocationChanged(it) }
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { onLocationChanged(it) }
    }

    override fun onLocationChanged(location: Location) {
        val now = System.currentTimeMillis()
        gpsFixCount++
        lastGpsTime = now

        // ── GPS 抗跳变滤波 ──
        val filtered = gpsFilter.process(
            rawLat = location.latitude,
            rawLng = location.longitude,
            rawSpeedMs = location.speed,
            accuracy = location.accuracy,
            timestampMs = now
        ) ?: return

        val (filteredLat, filteredLng, filteredSpeedKmh) = filtered

        // ── 更新 GPS 目标 ──
        targetLat = filteredLat
        targetLng = filteredLng
        targetSpeed = filteredSpeedKmh

        val rawBearing = when {
            location.hasBearing() && location.speed > 1f -> location.bearing
            hasCompass -> smoothedCompassBearing  // 使用平滑后的指南针
            else -> 0f
        }
        targetBearing = if (vehicleLat == 0.0) rawBearing
                        else circularShortest(vehicleBearing, rawBearing)

        // ── GPS 到达：瞬移到校正位置（无追逐、无指数平滑） ──
        if (vehicleLat == 0.0) {
            // 首次定位
            vehicleLat = targetLat
            vehicleLng = targetLng
            vehicleBearing = targetBearing
            lastFrameTime = now
        } else {
            // 后续 GPS：瞬移修正
            vehicleLat = targetLat
            vehicleLng = targetLng
        }

        // ── 惯导速度锁定到 GPS 速度 ──
        imuSpeedKmh = targetSpeed

        // ── 重置加速度平滑（GPS 来了，惯导重新校准） ──
        smoothedWorldAcc[0] = 0f
        smoothedWorldAcc[1] = 0f
        smoothedWorldAcc[2] = 0f

        hudView.vehicleLat = vehicleLat
        hudView.vehicleLng = vehicleLng
        hudView.vehicleBearing = vehicleBearing
        hudView.vehicleSpeed = targetSpeed

        tryFetchRoads()
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
                // 路网更新后重建路口节点
                intersections = buildIntersections(segments)
                Log.i(TAG, "Roads: ${segments.size} segments, ${intersections.size} intersections")
            }
        }
    }

    // === 60fps 渲染 + IMU 惯导推进 ===
    private fun updateFrame() {
        if (targetLat == 0.0) return

        val now = System.currentTimeMillis()
        val dt = if (lastFrameTime == 0L) 16.0 else (now - lastFrameTime).toDouble().coerceIn(1.0, 100.0)
        lastFrameTime = now

        // ── 路口检测 ──
        detectIntersection()

        // ── IMU 惯导推进（GPS 间隔内用指南针航向 + 惯导速度推算位置） ──
        propagateInertial(dt)

        // ── 方向处理（抗抖动核心 + 路口分支锁定） ──
        val newBearing = when {
            // 在路口且匹配到分支 → 优先跟随分支朝向
            nearIntersection && !matchedBranchHeading.isNaN() -> {
                val diff = ((matchedBranchHeading - vehicleBearing + 540f) % 360f) - 180f
                if (abs(diff) < HEADING_DEAD_ZONE) vehicleBearing
                else vehicleBearing + diff * 0.6f  // 路口处稍微更积极地跟上分支方向
            }
            // GPS 速度足够 → 强制用 GPS 航向（比指南针稳定得多）
            targetSpeed > GPS_BEARING_SPEED -> targetBearing
            // 有指南针 → 用 EMA 平滑后的指南针 + 死区过滤
            hasCompass -> {
                val diff = ((smoothedCompassBearing - vehicleBearing + 540f) % 360f) - 180f
                if (abs(diff) < HEADING_DEAD_ZONE) vehicleBearing  // 死区内：不动
                else vehicleBearing + diff * 0.5f  // 死区外：半速过渡（进一步平滑）
            }
            else -> vehicleBearing
        }
        vehicleBearing = ((newBearing % 360f) + 360f) % 360f

        // ── 道路吸附（路口增强） ──
        // 优先尝试路口分支吸附，失败则回退到普通速度分级吸附
        var snapped: Pair<Double, Double>? = null
        if (nearIntersection && !matchedBranchHeading.isNaN()) {
            val branch = matchBranchAtIntersection(vehicleLat, vehicleLng, smoothedCompassBearing)
            if (branch != null) {
                snapped = snapToRoadAtIntersection(vehicleLat, vehicleLng, targetSpeed, branch)
            }
        }
        if (snapped == null) {
            snapped = snapToRoadSpeedAware(vehicleLat, vehicleLng, targetSpeed)
        }

        if (snapped != null) {
            hudView.snappedLat = snapped.first
            hudView.snappedLng = snapped.second
            hudView.isSnapped = true
            hudView.vehicleLat = snapped.first
            hudView.vehicleLng = snapped.second
        } else {
            hudView.isSnapped = false
            hudView.vehicleLat = vehicleLat
            hudView.vehicleLng = vehicleLng
        }

        hudView.vehicleBearing = vehicleBearing
        hudView.vehicleSpeed = targetSpeed
        hudView.invalidate()
    }

    /**
     * 路口检测（每帧调用）
     *
     * 检测当前位置是否在任何路口的 50m 范围内，
     * 如果是，尝试用指南针匹配转入的分支。
     */
    private fun detectIntersection() {
        if (intersections.isEmpty()) {
            nearIntersection = false
            matchedBranchHeading = Float.NaN
            return
        }

        // 检查是否在任何路口附近
        var inIntersection = false
        for (inter in intersections) {
            val d = RoadFetcher.haversine(vehicleLat, vehicleLng, inter.lat, inter.lng)
            if (d < INTERSECTION_DETECT_RADIUS) {
                inIntersection = true
                break
            }
        }

        if (inIntersection) {
            nearIntersection = true
            lastIntersectionTime = System.currentTimeMillis()

            // 尝试匹配分支（使用平滑指南针）
            val branch = matchBranchAtIntersection(vehicleLat, vehicleLng, smoothedCompassBearing)
            if (branch != null) {
                matchedBranchHeading = branch.heading
                branchLockFrames++
                Log.d(TAG, "Intersection branch matched: ${branch.heading.toInt()}° (lock=$branchLockFrames)")
            } else {
                // 未匹配到分支，但仍在路口
                if (branchLockFrames < BRANCH_LOCK_MIN_FRAMES) {
                    matchedBranchHeading = Float.NaN
                }
            }
        } else {
            // 离开路口后，保持一段锁定（防抖）
            if (nearIntersection && branchLockFrames > 0) {
                branchLockFrames--
                if (branchLockFrames == 0) {
                    nearIntersection = false
                    matchedBranchHeading = Float.NaN
                    Log.d(TAG, "Left intersection, branch lock released")
                }
            } else {
                nearIntersection = false
                matchedBranchHeading = Float.NaN
                branchLockFrames = 0
            }
        }
    }

    /**
     * IMU 惯导推进
     *
     * GPS 每 500ms~1s 来一次，中间用惯导填充：
     * - 航向：EMA 平滑后的指南针 + 死区过滤
     * - 路口增强：在路口附近且匹配到分支时，惯导航向偏向分支朝向
     * - 速度：GPS 到达时锁定，GPS 丢失后指数衰减
     * - 加速度计：检测运动状态，静止时冻结航向和位置
     */
    private fun propagateInertial(dt: Double) {
        val speedKmh = imuSpeedKmh
        val speedMs = speedKmh / 3.6

        // ── 加速度计运动检测 ──
        val accMag = sqrt(
            worldAcc[0] * worldAcc[0] +
            worldAcc[1] * worldAcc[1] +
            worldAcc[2] * worldAcc[2]
        )
        val isStationary = accMag < FREEZE_ACC_THRESHOLD && speedKmh < FREEZE_SPEED_THRESHOLD

        // 静止时不推进（避免指南针抖动导致位置漂移）
        if (isStationary) return

        // ── 航向选择 ──
        var heading = if (hasCompass) smoothedCompassBearing else vehicleBearing

        // 路口增强：在路口附近且匹配到分支时，将惯导航向向分支朝向混合
        // 这样转弯后箭头会沿着转入的道路方向推进，而不是被指南针带偏
        if (nearIntersection && !matchedBranchHeading.isNaN() && branchLockFrames > 5) {
            val diff = ((matchedBranchHeading - heading + 540f) % 360f) - 180f
            // 70% 指南针 + 30% 分支朝向（路口时更信任道路方向）
            heading = ((heading + diff * 0.3f) + 360f) % 360f
        }

        val headingRad = Math.toRadians(heading.toDouble())

        // 位移 = 速度 × 时间
        val distMeters = speedMs * (dt / 1000.0)

        // 经纬度增量（小角度近似）
        val dLat = distMeters * cos(headingRad) / 111111.0
        val dLng = distMeters * sin(headingRad) / (111111.0 * cos(Math.toRadians(vehicleLat)))

        vehicleLat += dLat
        vehicleLng += dLng

        // ── GPS 丢失后速度自然衰减（模拟减速） ──
        val timeSinceGps = System.currentTimeMillis() - lastGpsTime
        if (timeSinceGps > 2000) {
            imuSpeedKmh *= VELOCITY_DECAY
        }
    }

    private fun circularShortest(from: Float, to: Float): Float {
        val diff = ((to - from + 540f) % 360f) - 180f
        return (from + diff + 360f) % 360f
    }

    // === 传感器 ===
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, accData, 0, 3)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magData, 0, 3)
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                // 线性加速度（已去除重力），旋转到世界坐标系
                val R = FloatArray(9)
                val I = FloatArray(9)
                if (SensorManager.getRotationMatrix(R, I, accData, magData)) {
                    for (i in 0..2) {
                        var sum = 0f
                        for (j in 0..2) {
                            sum += R[i * 3 + j] * event.values[j]
                        }
                        worldAcc[i] = sum
                    }
                    // EMA 平滑
                    for (i in 0..2) {
                        smoothedWorldAcc[i] += ACC_SMOOTH * (worldAcc[i] - smoothedWorldAcc[i])
                    }
                }
            }
        }

        // 指南针（从加速度计 + 磁力计计算）— EMA 平滑 + 圆形处理
        val R = FloatArray(9); val Im = FloatArray(9)
        if (SensorManager.getRotationMatrix(R, Im, accData, magData)) {
            val o = FloatArray(3); SensorManager.getOrientation(R, o)
            val rawBearing = ((Math.toDegrees(o[0].toDouble()).toFloat() + 360f) % 360f)

            if (!compassInitialized) {
                // 首次：直接赋值，不滤波
                smoothedCompassBearing = rawBearing
                compassInitialized = true
            } else {
                // 圆形 EMA：处理 359°→1° 跨越
                val diff = ((rawBearing - smoothedCompassBearing + 540f) % 360f) - 180f
                smoothedCompassBearing = ((smoothedCompassBearing + COMPASS_EMA_ALPHA * diff) + 360f) % 360f
            }
            compassBearing = rawBearing  // 保留原始值供调试，实际使用 smoothedCompassBearing
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
        super.onResume(); startRenderLoop(); startHudForegroundService()
    }
    override fun onPause() {
        stopRenderLoop(); super.onPause()
    }
    override fun onDestroy() {
        stopRenderLoop()
        locationManager.removeUpdates(this)
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
        roadFetchJob?.cancel(); scope.cancel()
        stopService(Intent(this, HudForegroundService::class.java))
        super.onDestroy()
    }
}
