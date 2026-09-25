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
 * HUD 导航 v10.0 — EKF 卡尔曼融合惯导（高德式"后端融合"）
 *
 * v9.8 → v10.0:
 * - 用 EKF 卡尔曼滤波替代速度制追赶（chase）
 * - "后端融合"模式：IMU 推算的经纬度是主位置，GPS 只在偏差大时校正
 * - GPS 丢失后 IMU 继续推算，速度自然衰减
 * - 卡尔曼增益 K 动态调节：GPS 好→多信 GPS，GPS 差→多信 IMU
 * - 位置不确定度 σ 驱动 HMM sigma 自适应
 *
 * 学自高德车机版 v9.5.0 逆向分析：
 * com.amap.location.fusion.LocationProvider（后端融合模式）
 * com.amap.location.support.security.gnssrtk.SatSol（KalmanFilter）
 */
class MainActivity : AppCompatActivity(), LocationListener, SensorEventListener {

    private lateinit var hudView: HudView
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private val handler = Handler(Looper.getMainLooper())

    // === GPS 抗跳变滤波器 ===
    private val gpsFilter = GpsFilter()

    // === EKF 卡尔曼融合惯导引擎（高德式"后端融合"） ===
    private val ekf = EkfDeadReckoning()

    // === UI（仅权限重试） ===
    private lateinit var flipContainer: FrameLayout
    private lateinit var permDeniedLayout: LinearLayout
    private lateinit var btnRetryPerm: TextView

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var roadFetchJob: Job? = null

    // === GPS 目标（GPS 滤波器输出，供 EKF 观测更新） ===
    private var targetLat = 0.0; private var targetLng = 0.0
    private var targetBearing = 0f; private var targetSpeed = 0f
    private var gpsAccuracy = 10f  // GPS 精度（米），用于 HMM sigma 自适应

    // === 车辆状态（由 EKF 输出驱动） ===
    private var vehicleLat = 0.0; private var vehicleLng = 0.0
    private var vehicleBearing = 0f
    private var lastFrameTime = 0L
    private val FRAME_MS = 16L

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

    // === 陀螺仪（精确角速度，用于 EKF 航向积分） ===
    private var hasGyro = false
    private val gyroData = FloatArray(3)         // 原始角速度 (rad/s)
    private var gyroHeadingRate = 0f             // 偏航角速度 (deg/s)
    private var gyroHeadingRateSmooth = 0f       // EMA 平滑后的偏航角速度
    private val GYRO_SMOOTH = 0.3f               // 陀螺仪 EMA 平滑系数
    private var lastGyroTime = 0L

    // === 旋转矢量（Android 9 轴融合：陀螺 + 加速 + 磁力） ===
    private var hasRotationVector = false
    private var rvHeading = 0f                   // 旋转矢量给出的航向 (deg)
    private var rvHeadingSmooth = 0f             // EMA 平滑
    private var rvInitialized = false

    // === 气压计（海拔高度） ===
    private var hasBarometer = false
    private var pressureAltitude = Float.NaN     // 气压海拔高度 (m)
    private var baseAltitude = Float.NaN         // 首次气压读数对应的海拔（用于相对高度校正）

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

    // ═══════════════════════════════════════════════════════
    // HMM 地图匹配（替代阈值吸附，从根源消除抖动）
    // ═══════════════════════════════════════════════════════
    //
    // 核心原理（Newson & Krumm 2009，高德/百度/Google 通用方案）：
    //
    // 每个 GPS 点 = 一个观测值
    // 附近的路段 = 隐状态（候选）
    // 发射概率 = GPS 到路段的距离（高斯分布，sigma = GPS 精度）
    // 转移概率 = 停留在同一路段 vs 切换到其他路段
    // 选综合概率最高的路段 → 天然无抖动
    //
    // 为什么不抖：
    // - GPS 漂移时：当前路段的发射概率仍然最高（因为距离没变多少）
    // - 转移到其他路段的概率很低（转移惩罚）→ 不会跳来跳去
    // - 真正转弯时：方向明显改变 + 路口处转移概率高 → 自然切换
    // - 没有阈值/滞后/锁定帧这些 hack，概率模型自带平滑

    private var matchedSegIdx = -1              // 当前匹配的路段索引（-1 = 无）
    private var matchedProjLat = 0.0            // 匹配路段上的投影点
    private var matchedProjLng = 0.0
    private var hmmConfidence = 0.0             // 匹配置信度（0~1）
    private val HMM_SIGMA = 10.0                // GPS 精度标准差（米），用于高斯发射概率
    private val HMM_STAY_BONUS = 3.0            // 停留在同一路段的概率加成（ln 空间）
    private val HMM_SEARCH_RADIUS = 50.0        // 候选搜索半径（米）
    private val HMM_MIN_CONFIDENCE = 0.1        // 最低匹配置信度，低于此不吸附
    private val HMM_SPEED_GATE = 3f             // 速度低于此不做 HMM（静止不匹配）

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
        if (!nearIntersection) return null

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
        // 基础传感器：磁力计 + 加速度计
        val mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val acc = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (mag != null && acc != null) {
            sensorManager.registerListener(this, mag, SensorManager.SENSOR_DELAY_GAME)
            sensorManager.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
            hasCompass = true
        }
        // 陀螺仪：精确角速度，用于 EKF 航向积分（高德核心传感器之一）
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (gyro != null) {
            sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
            hasGyro = true
            Log.i(TAG, "Gyroscope registered")
        }
        // 旋转矢量：Android 9轴融合（陀螺+加速+磁力），比纯磁力计稳定得多
        val rv = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rv != null) {
            sensorManager.registerListener(this, rv, SensorManager.SENSOR_DELAY_GAME)
            hasRotationVector = true
            Log.i(TAG, "Rotation vector registered")
        }
        // 气压计：海拔高度
        val baro = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (baro != null) {
            sensorManager.registerListener(this, baro, SensorManager.SENSOR_DELAY_NORMAL)
            hasBarometer = true
            Log.i(TAG, "Barometer registered")
        }
        // 线性加速度（已去除重力）
        val linAcc = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
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

        // ── 更新 GPS 目标（供 EKF 观测更新和方向处理使用） ──
        targetLat = filteredLat
        targetLng = filteredLng
        targetSpeed = filteredSpeedKmh
        gpsAccuracy = location.accuracy

        // ── 航向：纯 GPS，不依赖指南针 ──
        val rawBearing = if (location.hasBearing() && location.speed > 0.5f) {
            location.bearing
        } else {
            vehicleBearing  // 低速/静止保持上次航向
        }
        targetBearing = if (vehicleLat == 0.0) rawBearing
                        else circularShortest(vehicleBearing, rawBearing)

        // ── EKF 更新：GPS 观测校正 ──
        if (!ekf.initialized) {
            ekf.initialize(filteredLat, filteredLng, rawBearing, location.speed, now)
            vehicleLat = ekf.lat
            vehicleLng = ekf.lng
            vehicleBearing = ekf.heading.toFloat()
            lastFrameTime = now
        } else {
            // GPS 到达 → EKF 观测更新（卡尔曼增益动态调节信 GPS 多少）
            ekf.update(filteredLat, filteredLng, location.accuracy, now)
        }

        // 从 EKF 读取校正后的位置（这就是"主位置"）
        vehicleLat = ekf.lat
        vehicleLng = ekf.lng

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

    // === 60fps 渲染 + EKF 惯导推算 ===
    private fun updateFrame() {
        if (targetLat == 0.0) return

        val now = System.currentTimeMillis()
        val dt = if (lastFrameTime == 0L) 16L else (now - lastFrameTime).coerceIn(1L, 100L)
        lastFrameTime = now

        // ── 路口检测 ──
        detectIntersection()

        // ── EKF 预测步：IMU 惯导推算（每帧前推位置） ──
        // 这是高德"后端融合"的核心：predict 的输出就是显示位置
        ekf.predict(dt, vehicleBearing, targetSpeed * 1000f / 3600f)  // km/h → m/s

        // 从 EKF 读取推算位置
        vehicleLat = ekf.lat
        vehicleLng = ekf.lng

        // ── 方向处理（多源融合：GPS + 陀螺仪积分 + 旋转矢量） ──
        val gyroActive = hasGyro && (System.currentTimeMillis() - lastGyroTime) < 500
        val dtSec = dt / 1000.0

        val newBearing = when {
            // 在路口且匹配到分支 → 优先跟随分支朝向
            nearIntersection && !matchedBranchHeading.isNaN() -> {
                val diff = ((matchedBranchHeading - vehicleBearing + 540f) % 360f) - 180f
                if (abs(diff) < HEADING_DEAD_ZONE) vehicleBearing
                else vehicleBearing + diff * 0.6f
            }
            // GPS 有航向且速度足够 → 用 GPS 航向 + 死区过滤
            targetSpeed > 1f -> {
                val diff = ((targetBearing - vehicleBearing + 540f) % 360f) - 180f
                if (abs(diff) < HEADING_DEAD_ZONE) vehicleBearing
                else vehicleBearing + diff * 0.5f
            }
            // GPS 无航向但有陀螺仪 → 用陀螺仪角速度积分推算航向
            gyroActive && abs(gyroHeadingRateSmooth) > 0.5f -> {
                // 陀螺仪积分：heading += rate * dt
                vehicleBearing + gyroHeadingRateSmooth * dtSec.toFloat()
            }
            // 低速/静止但有旋转矢量 → 缓慢跟随旋转矢量航向
            hasRotationVector && rvInitialized -> {
                val diff = ((rvHeadingSmooth - vehicleBearing + 540f) % 360f) - 180f
                if (abs(diff) < HEADING_DEAD_ZONE) vehicleBearing
                else vehicleBearing + diff * 0.1f  // 非常缓慢跟随，避免低速抖动
            }
            // 都没有 → 保持上次航向
            else -> vehicleBearing
        }
        vehicleBearing = ((newBearing % 360f) + 360f) % 360f

        // ── 道路吸附（HMM 地图匹配 + 路口增强） ──
        // 用 EKF 位置不确定度动态调整 HMM sigma
        val ekfUncertainty = ekf.getPositionUncertainty()
        val adaptiveSigma = maxOf(HMM_SIGMA, ekfUncertainty)

        var snapped: Pair<Double, Double>? = null
        if (nearIntersection && !matchedBranchHeading.isNaN()) {
            val branch = matchBranchAtIntersection(vehicleLat, vehicleLng, vehicleBearing)
            if (branch != null) {
                snapped = snapToRoadAtIntersection(vehicleLat, vehicleLng, targetSpeed, branch)
            }
        }
        // HMM 匹配（sigma 由 EKF 不确定度驱动）
        if (snapped == null) {
            snapped = hmmMapMatchWithSigma(vehicleLat, vehicleLng, targetSpeed, vehicleBearing, adaptiveSigma)
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
        hudView.vehicleSpeed = (ekf.speed * 3.6).toFloat()  // m/s → km/h，用 EKF 积分速度
        // 状态栏：EKF 状态 + 活跃传感器
        val sensors = buildString {
            append("GPS")
            if (hasGyro) append("+Gyro")
            if (hasRotationVector) append("+RV")
            if (hasBarometer) append("+Baro")
        }
        hudView.statusText = "${ekf.getStatusString()} [$sensors]"
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

            // 尝试匹配分支（使用 GPS 航向）
            val branch = matchBranchAtIntersection(vehicleLat, vehicleLng, vehicleBearing)
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
     * HMM 地图匹配（EKF sigma 自适应版本）
     */
    private fun hmmMapMatchWithSigma(
        lat: Double, lng: Double, speedKmh: Float,
        bearing: Float, sigma: Double
    ): Pair<Double, Double>? {
        if (!hudView.hasRoads) {
            matchedSegIdx = -1
            hmmConfidence = 0.0
            return null
        }
        if (speedKmh < HMM_SPEED_GATE) return null

        val effectiveSigma = maxOf(sigma, 5.0)  // 最小 5m
        val sigma2 = 2.0 * effectiveSigma * effectiveSigma

        data class Candidate(
            val segIdx: Int,
            val projLat: Double,
            val projLng: Double,
            val dist: Double,
            val segHeading: Float
        )

        val candidates = mutableListOf<Candidate>()
        val segments = hudView.roadSegments

        for ((sIdx, seg) in segments.withIndex()) {
            val points = seg.points
            var segMinDist = Double.MAX_VALUE
            var segBestPLat = 0.0
            var segBestPLng = 0.0
            var segBestHeading = 0f

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
                if (dist < segMinDist) {
                    segMinDist = dist
                    segBestPLat = projLat
                    segBestPLng = projLng
                    segBestHeading = bearingBetween(lat1, lng1, lat2, lng2)
                }
            }

            if (segMinDist <= HMM_SEARCH_RADIUS) {
                candidates.add(Candidate(sIdx, segBestPLat, segBestPLng, segMinDist, segBestHeading))
            }
        }

        if (candidates.isEmpty()) {
            matchedSegIdx = -1; hmmConfidence = 0.0; return null
        }

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

        if (bestCandidate == null) {
            matchedSegIdx = -1; hmmConfidence = 0.0; return null
        }

        val bestEmit = exp(-(bestCandidate.dist * bestCandidate.dist) / sigma2)
        var totalEmit = 0.0
        for (cand in candidates) {
            totalEmit += exp(-(cand.dist * cand.dist) / sigma2)
        }
        hmmConfidence = if (totalEmit > 0) bestEmit / totalEmit else 0.0

        if (hmmConfidence < HMM_MIN_CONFIDENCE) {
            matchedSegIdx = -1; return null
        }

        matchedSegIdx = bestCandidate.segIdx
        matchedProjLat = bestCandidate.projLat
        matchedProjLng = bestCandidate.projLng

        val distFade = maxOf(0.0, 1.0 - bestCandidate.dist / HMM_SEARCH_RADIUS)
        val blendRatio = (hmmConfidence * distFade).coerceIn(0.0, 1.0)

        val snappedLat = lat + (bestCandidate.projLat - lat) * blendRatio
        val snappedLng = lng + (bestCandidate.projLng - lng) * blendRatio

        return Pair(snappedLat, snappedLng)
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
            Sensor.TYPE_GYROSCOPE -> {
                // 陀螺仪：精确角速度（rad/s）
                // Z轴 = 偏航角速度（绕竖直轴旋转）→ 直接积分得航向变化
                System.arraycopy(event.values, 0, gyroData, 0, 3)
                // Z轴角速度 (rad/s) → deg/s，负号因为 Android Z轴朝上、逆时针为正
                val rawRate = -Math.toDegrees(event.values[2].toDouble()).toFloat()
                gyroHeadingRate = rawRate
                // EMA 平滑
                gyroHeadingRateSmooth += GYRO_SMOOTH * (rawRate - gyroHeadingRateSmooth)
                lastGyroTime = System.currentTimeMillis()
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                // 旋转矢量：Android 9轴融合（陀螺+加速+磁力）
                // 输出四元数，转换为航向角
                val rotMat = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotMat, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotMat, orientation)
                val rawHeading = ((Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f)

                rvHeading = rawHeading
                if (!rvInitialized) {
                    rvHeadingSmooth = rawHeading
                    rvInitialized = true
                } else {
                    // 圆形 EMA 平滑
                    val diff = ((rawHeading - rvHeadingSmooth + 540f) % 360f) - 180f
                    rvHeadingSmooth = ((rvHeadingSmooth + 0.15f * diff) + 360f) % 360f
                }
            }
            Sensor.TYPE_PRESSURE -> {
                // 气压计：气压 → 海拔高度（米）
                // SensorManager.getAltitude(PRESSURE_STANDARD_ATMOSPHERE, pressure) 返回海拔
                val pressure = event.values[0]
                pressureAltitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressure)
                // 首次读数作为基准
                if (baseAltitude.isNaN()) {
                    baseAltitude = pressureAltitude
                }
                // 传递给 HudView 显示
                hudView.altitude = pressureAltitude
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
                smoothedCompassBearing = rawBearing
                compassInitialized = true
            } else {
                val diff = ((rawBearing - smoothedCompassBearing + 540f) % 360f) - 180f
                smoothedCompassBearing = ((smoothedCompassBearing + COMPASS_EMA_ALPHA * diff) + 360f) % 360f
            }
            compassBearing = rawBearing
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
