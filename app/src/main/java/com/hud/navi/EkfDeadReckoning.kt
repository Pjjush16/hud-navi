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

import kotlin.math.*

/**
 * EKF 卡尔曼融合惯导引擎 v2 — 路网约束惯导
 *
 * v10.11 核心改进（参考高德/百度惯导+路网吸附学术论文）：
 * 1. 惯导仅在路网吸附时激活 — 未吸附时不做 IMU 推算，回退 GPS-only
 * 2. 惯导约束在路网内 — 预测方向沿道路走向，不能惯出道路
 * 3. GPS 精度动态加权 — GPS 精度越高，GPS 在融合中的权重越大
 *
 * 学术参考：
 * - 高德: "A Map-Aided Dead Reckoning System Using HMM-Based Map Matching"
 * - 百度: "Real-time INS/GPS Integration with Road Network Constraints"
 * - 通用: Kalman filter GPS accuracy → measurement noise R mapping
 *
 * 状态向量 [4]:
 *   [0] lat (deg)
 *   [1] lng (deg)
 *   [2] vN (m/s) — 北向速度
 *   [3] vE (m/s) — 东向速度
 *
 * 观测量: GPS lat, lng + 路网吸附位置（伪观测）
 *
 * 控制输入: heading (deg), speed (m/s) — 来自 GPS bearing + speed 或 road heading
 */
class EkfDeadReckoning {

    // === 状态 ===
    var lat = 0.0          // 当前纬度（EKF 输出，这就是"主位置"）
    var lng = 0.0          // 当前经度
    var vN = 0.0           // 北向速度 (m/s)
    var vE = 0.0           // 东向速度 (m/s)
    var heading = 0.0      // 当前航向 (deg, 0=北)
    var speed = 0.0        // 当前速度 (m/s)
    var initialized = false

    // === 路网吸附状态（v10.11 新增） ===
    var snappedToRoad = false       // 是否被路网吸附
    var roadHeadingDeg = 0.0        // 当前吸附路段的走向（度，0=北）
    var snapConfidence = 0.0        // 吸附置信度（0~1）
    private var insActive = false   // 惯导是否激活（仅吸附时激活）

    // === GPS 精度分级权重（v10.11） ===
    // GPS accuracy → R（观测噪声）映射表
    // accuracy < 5m: 高精度（城市开阔地），R = accuracy² → GPS 权重大
    // accuracy 5-15m: 中精度（城市峡谷），R = accuracy² * 1.5
    // accuracy > 15m: 低精度（隧道/高架），R = accuracy² * 3 → GPS 权重小，惯导主导
    private val GPS_R_FLOOR = 3.0   // GPS 最小观测噪声（米），不过度信任
    private val GPS_R_PRECISE = 5.0  // 高精度阈值
    private val GPS_R_MEDIUM = 15.0  // 中精度阈值

    // === 协方差矩阵 P (4x4, 对角线近似) ===
    // P[0][0] = lat 方差, P[1][1] = lng 方差, P[2][2] = vN 方差, P[3][3] = vE 方差
    private val P = Array(4) { DoubleArray(4) }

    // === 过程噪声 Q ===
    // 加速度噪声标准差（m/s²），手机 IMU 典型值 0.3~1.0
    private val ACCEL_NOISE = 0.5

    // === GPS 观测噪声 R ===
    // GPS 水平精度（米），由 GPS accuracy 动态调整
    private var gpsR = 10.0  // 默认 10m

    // === GPS 丢失计时 ===
    var gpsLostTimeMs = 0L   // GPS 丢失后经过的毫秒数
    private var lastGpsTimeMs = 0L

    // === 卡尔曼增益 (用于调试输出) ===
    var lastKalmanGain = 0.0
    var lastInnovation = 0.0  // 观测残差（米）

    /**
     * 初始化：第一次 GPS 定位时调用
     */
    fun initialize(lat: Double, lng: Double, bearing: Float, speedMs: Float, timeMs: Long) {
        this.lat = lat
        this.lng = lng
        this.heading = bearing.toDouble()
        this.speed = speedMs.toDouble()

        // 分解速度到北/东分量
        val headingRad = Math.toRadians(heading)
        vN = speed * cos(headingRad)
        vE = speed * sin(headingRad)

        // 初始协方差：GPS 精度
        val initVar = (gpsR * gpsR / (111111.0 * 111111.0))
        P[0][0] = initVar; P[0][1] = 0.0; P[0][2] = 0.0; P[0][3] = 0.0
        P[1][0] = 0.0; P[1][1] = initVar; P[1][2] = 0.0; P[1][3] = 0.0
        P[2][0] = 0.0; P[2][1] = 0.0; P[2][2] = 4.0; P[2][3] = 0.0  // 速度方差 4 (m/s)²
        P[3][0] = 0.0; P[3][1] = 0.0; P[3][2] = 0.0; P[3][3] = 4.0

        lastGpsTimeMs = timeMs
        gpsLostTimeMs = 0L
        initialized = true
    }

    /**
     * 预测步（Predict）— 每帧调用（16ms）
     *
     * v10.11 路网约束惯导：
     * - 吸附时（snappedToRoad=true）：惯导激活，预测方向沿道路走向（roadHeadingDeg），
     *   而非自由航向。这确保惯导不会惯出道路。
     * - 未吸附时（snappedToRoad=false）：惯导不激活，不做 IMU 推算，
     *   位置保持 GPS 最后有效值 + 速度衰减。
     *
     * @param dtMs 帧间隔（毫秒）
     * @param headingDeg 当前航向（度，来自 GPS bearing 或 IMU）
     * @param speedMs 当前速度（m/s，来自 GPS speed）
     */
    fun predict(dtMs: Long, headingDeg: Float, speedMs: Float) {
        if (!initialized) return

        val dt = dtMs.toDouble() / 1000.0
        if (dt <= 0.0 || dt > 1.0) return  // 保护：dt 异常时跳过

        // 更新惯导激活状态
        insActive = snappedToRoad

        if (!insActive) {
            // ── 未吸附：惯导不激活 ──
            // 速度快速衰减，位置不推算（防止惯出道路）
            val decay = 0.95  // 每帧衰减 5%（~20帧/0.3秒归零）
            vN *= decay
            vE *= decay
            speed *= decay

            // GPS 丢失计时
            val timeSinceGps = System.currentTimeMillis() - lastGpsTimeMs
            gpsLostTimeMs = if (timeSinceGps > 2000) timeSinceGps else 0L
            return
        }

        // ── 吸附中：惯导激活，沿道路方向推算 ──
        // 使用道路走向（roadHeadingDeg）替代自由航向
        // 这是路网约束惯导的核心：预测方向被道路几何锁定
        val effectiveHeading = if (snapConfidence > 0.3) roadHeadingDeg else headingDeg.toDouble()

        this.heading = effectiveHeading
        this.speed = speedMs.toDouble()

        // 分解速度到北/东（沿道路方向）
        val headingRad = Math.toRadians(effectiveHeading)
        val newVN = speed * cos(headingRad)
        val newVE = speed * sin(headingRad)

        // 速度 EMA 平滑（沿道路方向的速度平滑）
        val speedAlpha = 0.3
        vN += speedAlpha * (newVN - vN)
        vE += speedAlpha * (newVE - vE)

        // ── 状态预测（沿道路方向前推）──
        val latRad = Math.toRadians(lat)
        lat += vN * dt / 111111.0
        lng += vE * dt / (111111.0 * cos(latRad))

        // GPS 丢失衰减
        val timeSinceGps = System.currentTimeMillis() - lastGpsTimeMs
        if (timeSinceGps > 2000) {
            val decay = 0.995  // 每帧衰减 0.5%（吸附中衰减更慢，惯导持续推算）
            vN *= decay
            vE *= decay
            speed *= decay
            gpsLostTimeMs = timeSinceGps
        } else {
            gpsLostTimeMs = 0L
        }

        // ── 协方差预测 (P = FPF' + Q) ──
        val qLat = (ACCEL_NOISE * dt) * (ACCEL_NOISE * dt) / (111111.0 * 111111.0)
        val qLng = qLat
        val qV = (ACCEL_NOISE * dt) * (ACCEL_NOISE * dt)

        P[0][0] += qLat + 2.0 * dt * P[0][2]
        P[1][1] += qLng + 2.0 * dt * P[1][3]
        P[2][2] += qV
        P[3][3] += qV

        // 限制协方差上界（吸附中可适当放宽，因为有路网约束）
        val maxPosVar = (80.0 * 80.0) / (111111.0 * 111111.0)  // 80m（吸附中比自由推算更宽松）
        P[0][0] = minOf(P[0][0], maxPosVar)
        P[1][1] = minOf(P[1][1], maxPosVar)
        P[2][2] = minOf(P[2][2], 100.0)
        P[3][3] = minOf(P[3][3], 100.0)
    }

    /**
     * GPS 更新步（Update）— GPS 到达时调用
     *
     * v10.11 GPS 精度动态加权：
     * - accuracy < 5m（高精度）: R = accuracy², 卡尔曼增益大 → GPS 主导
     * - accuracy 5-15m（中精度）: R = accuracy² × 1.5, GPS/INS 均衡融合
     * - accuracy > 15m（低精度）: R = accuracy² × 3, GPS 权重小 → INS 主导
     *
     * 未吸附时 GPS 直接覆盖位置（不做融合，因为没有惯导预测）
     *
     * @param gpsLat GPS 纬度
     * @param gpsLng GPS 经度
     * @param accuracy GPS 精度（米）
     * @param timeMs 当前时间戳
     */
    fun update(gpsLat: Double, gpsLng: Double, accuracy: Float, timeMs: Long) {
        if (!initialized) {
            initialize(gpsLat, gpsLng, 0f, 0f, timeMs)
            return
        }

        // ── GPS 精度分级 → 观测噪声 R ──
        // 精度越高（accuracy 数值越小），R 越小，卡尔曼增益越大，GPS 权重越高
        val accD = accuracy.toDouble().coerceAtLeast(1.0)
        gpsR = when {
            accD < GPS_R_PRECISE -> accD  // 高精度：R = accuracy（完全信任）
            accD < GPS_R_MEDIUM -> accD * 1.5  // 中精度：R = accuracy × 1.5
            else -> accD * 3.0  // 低精度：R = accuracy × 3（大幅降低信任度）
        }.coerceAtLeast(GPS_R_FLOOR)

        // ── 未吸附时：GPS 直接覆盖（不做卡尔曼融合）──
        if (!insActive) {
            lat = gpsLat
            lng = gpsLng
            lastGpsTimeMs = timeMs
            gpsLostTimeMs = 0L
            // 重置协方差（GPS 精度决定初始不确定度）
            val initVar = (gpsR * gpsR) / (111111.0 * 111111.0)
            P[0][0] = initVar; P[1][1] = initVar
            return
        }

        // ── 吸附中：标准卡尔曼更新 ──
        // 观测残差（Innovation）
        val yLat = gpsLat - lat
        val yLng = gpsLng - lng

        val innovMeters = sqrt(
            (yLat * 111111.0) * (yLat * 111111.0) +
            (yLng * 111111.0 * cos(Math.toRadians(lat))) * (yLng * 111111.0 * cos(Math.toRadians(lat)))
        )
        lastInnovation = innovMeters

        // 残差协方差 S = HPH' + R
        val rLat = (gpsR / 111111.0) * (gpsR / 111111.0)
        val rLng = (gpsR / (111111.0 * cos(Math.toRadians(lat)))) *
                   (gpsR / (111111.0 * cos(Math.toRadians(lat))))

        val sLat = P[0][0] + rLat
        val sLng = P[1][1] + rLng

        // 卡尔曼增益 K = P / S
        val kLat = P[0][0] / sLat
        val kLng = P[1][1] / sLng
        lastKalmanGain = maxOf(kLat, kLng)

        // 状态更新 x = x + Ky
        lat += kLat * yLat
        lng += kLng * yLng

        // 速度校正（小幅）
        val kV = 0.1 * maxOf(kLat, kLng)
        vN += kV * (yLat * 111111.0 / maxOf(1.0, (timeMs - lastGpsTimeMs).toDouble() / 1000.0))
        vE += kV * (yLng * 111111.0 * cos(Math.toRadians(lat)) / maxOf(1.0, (timeMs - lastGpsTimeMs).toDouble() / 1000.0))

        // 协方差更新 P = (I - KH)P
        P[0][0] *= (1.0 - kLat)
        P[1][1] *= (1.0 - kLng)
        P[2][2] *= (1.0 - kV)
        P[3][3] *= (1.0 - kV)

        // 交叉项衰减
        P[0][2] *= 0.5; P[2][0] *= 0.5
        P[1][3] *= 0.5; P[3][1] *= 0.5

        lastGpsTimeMs = timeMs
        gpsLostTimeMs = 0L
    }

    /**
     * 路网吸附伪观测更新（v10.11 新增）
     *
     * 当 HMM 地图匹配置信度高时，将吸附位置视为"伪 GPS 观测"注入 EKF。
     * 这是路网约束惯导的核心机制：吸附位置来自路网几何投影，
     * 精度高于原始 GPS（等效 accuracy ~5m），用于修正惯导漂移。
     *
     * @param snapLat 吸附位置纬度
     * @param snapLng 吸附位置经度
     * @param confidence HMM 匹配置信度（0~1）
     * @param roadHeading 吸附路段走向（度）
     * @param timeMs 当前时间戳
     */
    fun roadConstrainedUpdate(snapLat: Double, snapLng: Double, confidence: Double, roadHeading: Float, timeMs: Long) {
        if (!initialized || !insActive) return

        // 更新道路状态
        roadHeadingDeg = roadHeading.toDouble()
        snapConfidence = confidence

        // 伪观测噪声：置信度越高，噪声越小（等效高精度 GPS）
        // confidence 0.8 → R ≈ 5m，confidence 0.3 → R ≈ 20m
        val pseudoR = (5.0 / confidence.coerceAtLeast(0.1)).coerceAtMost(30.0)
        val rLat = (pseudoR / 111111.0) * (pseudoR / 111111.0)
        val rLng = (pseudoR / (111111.0 * cos(Math.toRadians(lat)))) *
                   (pseudoR / (111111.0 * cos(Math.toRadians(lat))))

        val yLat = snapLat - lat
        val yLng = snapLng - lng

        val sLat = P[0][0] + rLat
        val sLng = P[1][1] + rLng

        val kLat = P[0][0] / sLat
        val kLng = P[1][1] / sLng

        // 位置修正（力度由置信度缩放）
        val correctionScale = confidence.coerceIn(0.0, 0.8)  // 最大修正力度 80%
        lat += kLat * yLat * correctionScale
        lng += kLng * yLng * correctionScale

        P[0][0] *= (1.0 - kLat * correctionScale)
        P[1][1] *= (1.0 - kLng * correctionScale)
    }

    /**
     * 获取当前位置不确定度（米）
     * 用于 UI 显示和 HMM sigma 自适应
     */
    fun getPositionUncertainty(): Double {
        val latStd = sqrt(maxOf(0.0, P[0][0])) * 111111.0
        val lngStd = sqrt(maxOf(0.0, P[1][1])) * 111111.0 * cos(Math.toRadians(lat))
        return sqrt(latStd * latStd + lngStd * lngStd)
    }

    /**
     * 获取 EKF 状态摘要（用于 HUD 显示）
     */
    fun getStatusString(): String {
        val unc = getPositionUncertainty()
        val gpsAge = System.currentTimeMillis() - lastGpsTimeMs
        val mode = when {
            !insActive -> "GPS"  // 未吸附：纯 GPS
            gpsAge < 2000 -> "GPS+INS"  // 吸附中 + GPS 在线
            else -> "INS-DR"  // 吸附中 + GPS 丢失（纯惯导推算）
        }
        val insTag = if (insActive) "⊕路" else ""
        return "${mode}${insTag} K=${String.format("%.2f", lastKalmanGain)} Δ=${String.format("%.1f", lastInnovation)}m σ=${String.format("%.1f", unc)}m"
    }
}
