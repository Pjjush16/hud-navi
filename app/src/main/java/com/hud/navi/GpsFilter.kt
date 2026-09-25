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

/**
 * GPS 抗跳变滤波器
 *
 * 三层防护:
 * 1. 卡尔曼滤波 — 对 lat/lng 独立做 1D Kalman，平滑噪声
 * 2. 最小位移阈值 — 位移 < 3m 时视为静止抖动，不更新位置
 * 3. 异常点剔除 — 隐含速度 > 300km/h 的跳点直接丢弃
 *
 * 辅助:
 * - 速度 EMA 平滑（防止速度突变）
 * - 精度加权（GPS accuracy 差时降低卡尔曼增益）
 */
class GpsFilter {

    // === 卡尔曼滤波器（lat/lng 各一个） ===
    private val kalmanLat = KalmanFilter1D(processNoise = 0.000001, measurementNoise = 0.00001)
    private val kalmanLng = KalmanFilter1D(processNoise = 0.000001, measurementNoise = 0.00001)

    // === 上一次接受的位置 ===
    private var lastAcceptedLat = 0.0
    private var lastAcceptedLng = 0.0
    private var lastAcceptedTime = 0L
    private var lastAcceptedSpeed = 0f

    // === 速度 EMA ===
    private var smoothedSpeed = 0f
    private val SPEED_ALPHA = 0.3f  // EMA 系数（越小越平滑）

    // === 统计 ===
    var acceptedCount = 0
        private set
    var rejectedCount = 0
        private set
    var stationaryCount = 0
        private set

    // === 阈值 ===
    companion object {
        private const val MIN_DISPLACEMENT_M = 3.0     // 最小位移（米）
        private const val MAX_REASONABLE_SPEED = 83.0   // 最大合理速度 m/s（约 300 km/h）
        private const val INITIAL_LOCK_DISTANCE_M = 50.0 // 首次定位锁定的宽松距离
    }

    /**
     * 处理原始 GPS 数据，返回滤波后的结果，或 null 表示被丢弃
     *
     * @param rawLat 原始纬度
     * @param rawLng 原始经度
     * @param rawSpeedMs 原始速度（m/s）
     * @param accuracy GPS 精度（米）
     * @param timestampMs 时间戳
     * @return 滤波后的 (lat, lng, speedKmh)，null 表示被拒绝
     */
    fun process(
        rawLat: Double, rawLng: Double,
        rawSpeedMs: Float, accuracy: Float,
        timestampMs: Long
    ): Triple<Double, Double, Float>? {

        // ── 首次定位：直接接受，初始化卡尔曼 ──
        if (lastAcceptedLat == 0.0) {
            kalmanLat.reset(rawLat, accuracyToVariance(accuracy))
            kalmanLng.reset(rawLng, accuracyToVariance(accuracy))
            lastAcceptedLat = rawLat
            lastAcceptedLng = rawLng
            lastAcceptedTime = timestampMs
            smoothedSpeed = rawSpeedMs * 3.6f
            lastAcceptedSpeed = smoothedSpeed
            acceptedCount++
            return Triple(rawLat, rawLng, smoothedSpeed)
        }

        // ── 第一层：异常点剔除（隐含速度检查）──
        val displacement = RoadFetcher.haversine(lastAcceptedLat, lastAcceptedLng, rawLat, rawLng)
        val dt = (timestampMs - lastAcceptedTime) / 1000.0  // 秒
        if (dt > 0.1) {  // 有有效时间差
            val impliedSpeed = displacement / dt  // m/s
            if (impliedSpeed > MAX_REASONABLE_SPEED && displacement > INITIAL_LOCK_DISTANCE_M) {
                rejectedCount++
                return null  // 跳变太大，丢弃
            }
        }

        // ── 第二层：最小位移阈值（静止抖动过滤）──
        if (displacement < MIN_DISPLACEMENT_M && dt < 3.0) {
            stationaryCount++
            // 仍然更新时间戳和速度（速度可以为0），但不更新位置
            lastAcceptedTime = timestampMs
            smoothedSpeed = ema(smoothedSpeed, rawSpeedMs * 3.6f, SPEED_ALPHA)
            lastAcceptedSpeed = smoothedSpeed
            return Triple(lastAcceptedLat, lastAcceptedLng, smoothedSpeed)
        }

        // ── 第三层：卡尔曼滤波 ──
        // 根据精度动态调整测量噪声（accuracy 越差，噪声越大，卡尔曼越不信这个测量值）
        val measurementVar = accuracyToVariance(accuracy)
        kalmanLat.setMeasurementNoise(measurementVar)
        kalmanLng.setMeasurementNoise(measurementVar)

        val filteredLat = kalmanLat.update(rawLat)
        val filteredLng = kalmanLng.update(rawLng)

        // ── 速度 EMA 平滑 ──
        smoothedSpeed = ema(smoothedSpeed, rawSpeedMs * 3.6f, SPEED_ALPHA)

        // ── 更新状态 ──
        lastAcceptedLat = filteredLat
        lastAcceptedLng = filteredLng
        lastAcceptedTime = timestampMs
        lastAcceptedSpeed = smoothedSpeed
        acceptedCount++

        return Triple(filteredLat, filteredLng, smoothedSpeed)
    }

    /**
     * GPS accuracy → 测量方差
     * accuracy 越高（数值越小），方差越小，卡尔曼越信任测量值
     */
    private fun accuracyToVariance(accuracyMeters: Float): Double {
        val a = accuracyMeters.coerceIn(1f, 100f)
        // accuracy 1m → variance 0.0000001 (约 0.01m 的经纬度方差)
        // accuracy 50m → variance 0.0002
        return (a * a * 0.00000001).toDouble()
    }

    private fun ema(prev: Float, curr: Float, alpha: Float): Float {
        return prev + alpha * (curr - prev)
    }

    fun reset() {
        lastAcceptedLat = 0.0; lastAcceptedLng = 0.0; lastAcceptedTime = 0L
        smoothedSpeed = 0f; lastAcceptedSpeed = 0f
        acceptedCount = 0; rejectedCount = 0; stationaryCount = 0
    }
}

/**
 * 一维卡尔曼滤波器
 *
 * 状态方程: x_k = x_{k-1} + w (过程噪声)
 * 观测方程: z_k = x_k + v (测量噪声)
 *
 * 简化版：无控制输入，状态转移矩阵 = 1
 */
class KalmanFilter1D(
    private var processNoise: Double,
    private var measurementNoise: Double
) {
    private var estimate = 0.0       // 状态估计值
    private var errorCovariance = 1.0 // 估计误差协方差
    private var initialized = false

    fun reset(initialValue: Double, initialVariance: Double) {
        estimate = initialValue
        errorCovariance = initialVariance
        initialized = true
    }

    fun setMeasurementNoise(noise: Double) {
        measurementNoise = noise
    }

    fun update(measurement: Double): Double {
        if (!initialized) {
            estimate = measurement
            errorCovariance = measurementNoise
            initialized = true
            return estimate
        }

        // 预测步：状态不变，协方差增加过程噪声
        errorCovariance += processNoise

        // 更新步：计算卡尔曼增益
        val kalmanGain = errorCovariance / (errorCovariance + measurementNoise)

        // 更新估计值
        estimate += kalmanGain * (measurement - estimate)

        // 更新协方差
        errorCovariance *= (1.0 - kalmanGain)

        return estimate
    }
}
