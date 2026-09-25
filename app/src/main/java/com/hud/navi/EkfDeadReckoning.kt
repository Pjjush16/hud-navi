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
 * EKF 卡尔曼融合惯导引擎
 *
 * 学自高德车机版 v9.5.0 逆向分析：
 * - "后端融合"模式：IMU 推算的经纬度是主位置
 * - GPS 只在偏差超阈值时校正（卡尔曼增益动态调节）
 * - GPS 丢失后 IMU 继续推算，地图匹配约束在道路上
 *
 * 状态向量 [4]:
 *   [0] lat (deg)
 *   [1] lng (deg)
 *   [2] vN (m/s) — 北向速度
 *   [3] vE (m/s) — 东向速度
 *
 * 观测量: GPS lat, lng
 *
 * 控制输入: heading (deg), speed (m/s) — 来自 GPS bearing + speed
 *
 * 参考: 高德 SatSol.smali / OpenSpaceDetectorForCar.smali 中的 KalmanFilter 使用模式
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
     * 用当前速度 + 航向前推位置（惯导推算）
     * 这是高德"后端融合"的核心：预测位置就是显示位置
     *
     * @param dtMs 帧间隔（毫秒）
     * @param headingDeg 当前航向（度，来自 GPS bearing 或 IMU）
     * @param speedMs 当前速度（m/s，来自 GPS speed）
     */
    fun predict(dtMs: Long, headingDeg: Float, speedMs: Float) {
        if (!initialized) return

        val dt = dtMs.toDouble() / 1000.0
        if (dt <= 0.0 || dt > 1.0) return  // 保护：dt 异常时跳过

        // 更新速度和航向
        this.heading = headingDeg.toDouble()
        this.speed = speedMs.toDouble()

        // 分解速度到北/东
        val headingRad = Math.toRadians(heading)
        val newVN = speed * cos(headingRad)
        val newVE = speed * sin(headingRad)

        // 速度 EMA 平滑（防止 GPS 速度突变导致位置跳变）
        val speedAlpha = 0.3
        vN += speedAlpha * (newVN - vN)
        vE += speedAlpha * (newVE - vE)

        // ── 状态预测 ──
        // lat += vN * dt / 111111  (1度 ≈ 111111m)
        // lng += vE * dt / (111111 * cos(lat))
        val latRad = Math.toRadians(lat)
        lat += vN * dt / 111111.0
        lng += vE * dt / (111111.0 * cos(latRad))

        // GPS 丢失衰减：超过 2 秒没有 GPS，速度自然衰减
        val timeSinceGps = System.currentTimeMillis() - lastGpsTimeMs
        if (timeSinceGps > 2000) {
            val decay = 0.995  // 每帧衰减 0.5%
            vN *= decay
            vE *= decay
            speed *= decay
            gpsLostTimeMs = timeSinceGps
        } else {
            gpsLostTimeMs = 0L
        }

        // ── 协方差预测 (P = FPF' + Q) ──
        // 简化：只更新对角线
        val qLat = (ACCEL_NOISE * dt) * (ACCEL_NOISE * dt) / (111111.0 * 111111.0)
        val qLng = qLat  // 近似
        val qV = (ACCEL_NOISE * dt) * (ACCEL_NOISE * dt)

        P[0][0] += qLat + 2.0 * dt * P[0][2]  // lat 方差增长（含速度-位置耦合）
        P[1][1] += qLng + 2.0 * dt * P[1][3]  // lng 方差增长
        P[2][2] += qV                            // vN 方差增长
        P[3][3] += qV                            // vE 方差增长

        // 限制协方差上界（防止 GPS 长时间丢失后方差爆炸）
        val maxPosVar = (50.0 * 50.0) / (111111.0 * 111111.0)  // 50m 对应的位置方差
        P[0][0] = minOf(P[0][0], maxPosVar)
        P[1][1] = minOf(P[1][1], maxPosVar)
        P[2][2] = minOf(P[2][2], 100.0)  // 速度方差上限 100 (m/s)²
        P[3][3] = minOf(P[3][3], 100.0)
    }

    /**
     * 更新步（Update）— GPS 到达时调用
     *
     * 用 GPS 观测值校正 EKF 状态
     * 卡尔曼增益 K 决定"信 GPS 多少"：
     * - GPS 精度好（R 小）→ K 大 → 多信 GPS
     * - GPS 精度差（R 大）→ K 小 → 多信 IMU
     * - 预测方差大（P 大）→ K 大 → 更需要 GPS 校正
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

        // 更新 GPS 观测噪声
        gpsR = maxOf(accuracy.toDouble(), 3.0)  // 最小 3m（避免过度信任 GPS）

        // ── 观测残差（Innovation）──
        // y = z - Hx，其中 H = [1 0 0 0; 0 1 0 0]（直接观测 lat, lng）
        val yLat = gpsLat - lat
        val yLng = gpsLng - lng

        // 转换为米（用于显示/调试）
        val innovMeters = sqrt(
            (yLat * 111111.0) * (yLat * 111111.0) +
            (yLng * 111111.0 * cos(Math.toRadians(lat))) * (yLng * 111111.0 * cos(Math.toRadians(lat)))
        )
        lastInnovation = innovMeters

        // ── 残差协方差 S = HPH' + R ──
        val rLat = (gpsR / 111111.0) * (gpsR / 111111.0)
        val rLng = (gpsR / (111111.0 * cos(Math.toRadians(lat)))) *
                   (gpsR / (111111.0 * cos(Math.toRadians(lat))))

        val sLat = P[0][0] + rLat
        val sLng = P[1][1] + rLng

        // ── 卡尔曼增益 K = PH⁻¹S⁻¹（对角近似）──
        val kLat = P[0][0] / sLat
        val kLng = P[1][1] / sLng
        lastKalmanGain = maxOf(kLat, kLng)

        // ── 状态更新 x = x + Ky ──
        lat += kLat * yLat
        lng += kLng * yLng

        // 速度也做小幅校正（位置校正隐含速度信息）
        val kV = 0.1 * maxOf(kLat, kLng)  // 速度校正比位置校正弱
        vN += kV * (yLat * 111111.0 / maxOf(1.0, (timeMs - lastGpsTimeMs).toDouble() / 1000.0))
        vE += kV * (yLng * 111111.0 * cos(Math.toRadians(lat)) / maxOf(1.0, (timeMs - lastGpsTimeMs).toDouble() / 1000.0))

        // ── 协方差更新 P = (I - KH)P ──
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
        val mode = if (gpsAge < 2000) "GPS+IMU" else "IMU-DR"
        return "${mode} K=${String.format("%.2f", lastKalmanGain)} Δ=${String.format("%.1f", lastInnovation)}m σ=${String.format("%.1f", unc)}m"
    }
}
