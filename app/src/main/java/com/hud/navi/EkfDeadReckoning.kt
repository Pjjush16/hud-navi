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
 * MERCHANTABILITY or FITNESS FOR the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.hud.navi

import kotlin.math.*

/**
 * EKF 三源融合惯导引擎 v3 — GPS/北斗 + IMU + 电子罗盘
 *
 * v10.20 核心改进：
 * 1. IMU 始终激活 — 不再依赖路网吸附状态，惯导始终参与融合
 * 2. 电子罗盘融合 — 低速时用罗盘提供航向（GPS bearing 在低速时不可靠）
 * 3. GPS 权重动态调整 — GPS 精度差时更多信任 IMU 推算
 * 4. 路网吸附仅作为微调层 — 不影响惯导激活状态
 *
 * 三源分工：
 * - GPS/北斗：大方向参考（绝对位置，精度低，会漂移）
 * - IMU（加速度计+陀螺仪）：精确轨迹推算（转弯、加减速）
 * - 电子罗盘：车头朝向（不依赖运动状态）
 *
 * 状态向量 [4]:
 *   [0] lat (deg)
 *   [1] lng (deg)
 *   [2] vN (m/s) — 北向速度
 *   [3] vE (m/s) — 东向速度
 */
class EkfDeadReckoning {

    // === 状态 ===
    var lat = 0.0
    var lng = 0.0
    var vN = 0.0
    var vE = 0.0
    var heading = 0.0      // 当前航向 (deg, 0=北)
    var speed = 0.0        // 当前速度 (m/s)
    var initialized = false

    // === 电子罗盘状态 ===
    var compassBearing = 0.0    // 罗盘航向（度，0=北）
    var compassAvailable = false
    private var compassSmoothed = 0.0
    private var compassInitialized = false

    // === 路网吸附状态（仅作为微调，不控制惯导激活） ===
    var snappedToRoad = false
    var roadHeadingDeg = 0.0
    var snapConfidence = 0.0

    // === GPS 精度 ===
    private val GPS_R_FLOOR = 3.0
    private val GPS_R_PRECISE = 5.0
    private val GPS_R_MEDIUM = 15.0
    private var gpsR = 10.0

    // === GPS 丢失计时 ===
    var gpsLostTimeMs = 0L
    private var lastGpsTimeMs = 0L

    // === 协方差 ===
    private val P = Array(4) { DoubleArray(4) }
    private val ACCEL_NOISE = 0.5
    var lastKalmanGain = 0.0
    var lastInnovation = 0.0

    /**
     * 初始化
     */
    fun initialize(lat: Double, lng: Double, bearing: Float, speedMs: Float, timeMs: Long) {
        this.lat = lat
        this.lng = lng
        this.heading = bearing.toDouble()
        this.speed = speedMs.toDouble()

        val headingRad = Math.toRadians(heading)
        vN = speed * cos(headingRad)
        vE = speed * sin(headingRad)

        val initVar = (gpsR * gpsR / (111111.0 * 111111.0))
        P[0][0] = initVar; P[0][1] = 0.0; P[0][2] = 0.0; P[0][3] = 0.0
        P[1][0] = 0.0; P[1][1] = initVar; P[1][2] = 0.0; P[1][3] = 0.0
        P[2][0] = 0.0; P[2][1] = 0.0; P[2][2] = 4.0; P[2][3] = 0.0
        P[3][0] = 0.0; P[3][1] = 0.0; P[3][2] = 0.0; P[3][3] = 4.0

        lastGpsTimeMs = timeMs
        gpsLostTimeMs = 0L
        initialized = true
    }

    /**
     * 更新电子罗盘数据
     * 低速时（<3 km/h）罗盘比 GPS bearing 更可靠
     */
    fun updateCompass(bearingDeg: Float) {
        compassAvailable = true
        if (!compassInitialized) {
            compassSmoothed = bearingDeg.toDouble()
            compassInitialized = true
        } else {
            // 罗盘角度平滑（处理 359→1 跨越）
            var diff = bearingDeg.toDouble() - compassSmoothed
            if (diff > 180) diff -= 360
            if (diff < -180) diff += 360
            compassSmoothed += 0.15 * diff  // EMA alpha=0.15
            if (compassSmoothed < 0) compassSmoothed += 360
            if (compassSmoothed >= 360) compassSmoothed -= 360
        }
        compassBearing = compassSmoothed
    }

    /**
     * 预测步（Predict）— 每帧调用（16ms）
     *
     * v10.20: IMU 始终激活，不依赖路网吸附
     * 航向来源：
     *   - 速度 > 3 km/h：用 GPS bearing（运动方向可靠）
     *   - 速度 ≤ 3 km/h：用罗盘（GPS bearing 在低速时跳变严重）
     *   - 无罗盘时：用上一帧航向 + IMU 角速度推算
     *
     * @param dtMs 帧间隔（毫秒）
     * @param gpsBearingDeg GPS bearing（度）
     * @param speedMs GPS speed（m/s）
     */
    fun predict(dtMs: Long, gpsBearingDeg: Float, speedMs: Float) {
        if (!initialized) return

        val dt = dtMs.toDouble() / 1000.0
        if (dt <= 0.0 || dt > 1.0) return

        // ── 确定有效航向 ──
        // 高速时 GPS bearing 可靠，低速时用罗盘
        val speedKmh = speedMs * 3.6
        val effectiveHeading = when {
            speedKmh > 3.0 && gpsBearingDeg >= 0 -> gpsBearingDeg.toDouble()
            compassAvailable -> compassSmoothed
            else -> heading  // 保持上一帧航向
        }

        // 航向 EMA 平滑（避免跳变）
        var headingDiff = effectiveHeading - heading
        if (headingDiff > 180) headingDiff -= 360
        if (headingDiff < -180) headingDiff += 360
        val headingAlpha = if (speedKmh > 3.0) 0.3 else 0.15
        heading += headingAlpha * headingDiff
        if (heading < 0) heading += 360
        if (heading >= 360) heading -= 360

        // 速度 EMA 平滑
        this.speed += 0.3 * (speedMs.toDouble() - this.speed)

        // 分解速度到北/东
        val headingRad = Math.toRadians(heading)
        val targetVN = speed * cos(headingRad)
        val targetVE = speed * sin(headingRad)

        // 速度平滑
        val speedAlpha = 0.3
        vN += speedAlpha * (targetVN - vN)
        vE += speedAlpha * (targetVE - vE)

        // ── 状态预测（IMU 惯导始终激活）──
        val latRad = Math.toRadians(lat)
        lat += vN * dt / 111111.0
        lng += vE * dt / (111111.0 * cos(latRad))

        // GPS 丢失衰减（惯导独立推算，误差逐渐增大）
        val timeSinceGps = System.currentTimeMillis() - lastGpsTimeMs
        if (timeSinceGps > 3000) {
            val decay = 0.998  // 每帧衰减 0.2%（惯导推算 5 秒内基本可靠）
            vN *= decay
            vE *= decay
            speed *= decay
            gpsLostTimeMs = timeSinceGps
        } else {
            gpsLostTimeMs = 0L
        }

        // ── 协方差预测 ──
        val qLat = (ACCEL_NOISE * dt) * (ACCEL_NOISE * dt) / (111111.0 * 111111.0)
        val qLng = qLat
        val qV = (ACCEL_NOISE * dt) * (ACCEL_NOISE * dt)

        P[0][0] += qLat + 2.0 * dt * P[0][2]
        P[1][1] += qLng + 2.0 * dt * P[1][3]
        P[2][2] += qV
        P[3][3] += qV

        // GPS 丢失时间越长，协方差增长越快（惯导推算精度逐渐下降）
        val driftFactor = 1.0 + (gpsLostTimeMs / 60000.0) * 2.0  // 每分钟漂移 2 倍
        P[0][0] *= driftFactor
        P[1][1] *= driftFactor

        val maxPosVar = (100.0 * 100.0) / (111111.0 * 111111.0)
        P[0][0] = minOf(P[0][0], maxPosVar)
        P[1][1] = minOf(P[1][1], maxPosVar)
        P[2][2] = minOf(P[2][2], 100.0)
        P[3][3] = minOf(P[3][3], 100.0)
    }

    /**
     * GPS 更新步（Update）— GPS 到达时调用
     *
     * v10.20: GPS 始终做卡尔曼融合（不再直接覆盖）
     * GPS 精度动态加权：
     *   - accuracy < 5m: R = accuracy → GPS 主导
     *   - accuracy 5-15m: R = accuracy × 2 → GPS/IMU 均衡
     *   - accuracy > 15m: R = accuracy × 5 → IMU 主导（GPS 只做大方向参考）
     */
    fun update(gpsLat: Double, gpsLng: Double, accuracy: Float, timeMs: Long) {
        if (!initialized) {
            initialize(gpsLat, gpsLng, 0f, 0f, timeMs)
            return
        }

        // GPS 精度 → 观测噪声 R
        val accD = accuracy.toDouble().coerceAtLeast(1.0)
        gpsR = when {
            accD < GPS_R_PRECISE -> accD                    // 高精度：完全信任 GPS
            accD < GPS_R_MEDIUM -> accD * 2.0               // 中精度：GPS/IMU 均衡
            else -> accD * 5.0                               // 低精度：GPS 仅大方向参考
        }.coerceAtLeast(GPS_R_FLOOR)

        // 观测残差
        val yLat = gpsLat - lat
        val yLng = gpsLng - lng

        val innovMeters = sqrt(
            (yLat * 111111.0) * (yLat * 111111.0) +
            (yLng * 111111.0 * cos(Math.toRadians(lat))) * (yLng * 111111.0 * cos(Math.toRadians(lat)))
        )
        lastInnovation = innovMeters

        // 异常跳变检测：如果 GPS 跳变 >50m，降低 GPS 权重（可能是多径效应）
        val effectiveR = if (innovMeters > 50.0) gpsR * 3.0 else gpsR

        val rLat = (effectiveR / 111111.0) * (effectiveR / 111111.0)
        val rLng = (effectiveR / (111111.0 * cos(Math.toRadians(lat)))) *
                   (effectiveR / (111111.0 * cos(Math.toRadians(lat))))

        val sLat = P[0][0] + rLat
        val sLng = P[1][1] + rLng

        // 卡尔曼增益
        val kLat = P[0][0] / sLat
        val kLng = P[1][1] / sLng
        lastKalmanGain = maxOf(kLat, kLng)

        // 状态更新
        lat += kLat * yLat
        lng += kLng * yLng

        // 速度校正
        val kV = 0.1 * maxOf(kLat, kLng)
        vN += kV * (yLat * 111111.0 / maxOf(1.0, (timeMs - lastGpsTimeMs).toDouble() / 1000.0))
        vE += kV * (yLng * 111111.0 * cos(Math.toRadians(lat)) / maxOf(1.0, (timeMs - lastGpsTimeMs).toDouble() / 1000.0))

        // 协方差更新
        P[0][0] *= (1.0 - kLat)
        P[1][1] *= (1.0 - kLng)
        P[2][2] *= (1.0 - kV)
        P[3][3] *= (1.0 - kV)

        P[0][2] *= 0.5; P[2][0] *= 0.5
        P[1][3] *= 0.5; P[3][1] *= 0.5

        lastGpsTimeMs = timeMs
        gpsLostTimeMs = 0L
    }

    /**
     * 路网吸附微调（v10.20: 仅作为微调层，不影响惯导激活）
     *
     * 高置信度时将吸附位置作为伪观测注入 EKF，修正 IMU 累积漂移。
     * 低置信度时不注入（路网数据可能不准确）。
     */
    fun roadConstrainedUpdate(snapLat: Double, snapLng: Double, confidence: Double, roadHeading: Float, timeMs: Long) {
        if (!initialized) return

        roadHeadingDeg = roadHeading.toDouble()
        snapConfidence = confidence

        // 低置信度不注入
        if (confidence < 0.3) return

        // 伪观测噪声
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

        // 修正力度由置信度缩放（最大 60%，比 v10.11 的 80% 更保守）
        val correctionScale = confidence.coerceIn(0.0, 0.6)
        lat += kLat * yLat * correctionScale
        lng += kLng * yLng * correctionScale

        P[0][0] *= (1.0 - kLat * correctionScale)
        P[1][1] *= (1.0 - kLng * correctionScale)
    }

    fun getPositionUncertainty(): Double {
        val latStd = sqrt(maxOf(0.0, P[0][0])) * 111111.0
        val lngStd = sqrt(maxOf(0.0, P[1][1])) * 111111.0 * cos(Math.toRadians(lat))
        return sqrt(latStd * latStd + lngStd * lngStd)
    }

    fun getStatusString(): String {
        val unc = getPositionUncertainty()
        val gpsAge = System.currentTimeMillis() - lastGpsTimeMs
        val headingSource = when {
            speed * 3.6 > 3.0 -> "GPS"
            compassAvailable -> "罗盘"
            else -> "惯导"
        }
        val mode = when {
            gpsAge < 2000 -> "GPS+INS"
            else -> "INS-DR"
        }
        val compassTag = if (compassAvailable) "⊕罗盘" else ""
        val snapTag = if (snappedToRoad && snapConfidence > 0.3) "⊕路" else ""
        return "${mode}[${headingSource}]${compassTag}${snapTag} K=${String.format("%.2f", lastKalmanGain)} Δ=${String.format("%.1f", lastInnovation)}m σ=${String.format("%.1f", unc)}m"
    }
}
