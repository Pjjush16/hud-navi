/*
 * EkfDeadReckoning.kt - v15.2 纯 GPS+EKF 定位
 *
 * 基于 v10.00 的 4 维 EKF（lat, lng, vN, vE）。
 * 去掉了所有路口减速/停车逻辑（v15.1 的距离驱动减速导致"引力弹弓"问题）。
 * 车标位置 = EKF 输出的实时位置，不做任何延迟或减速。
 *
 * 状态向量 [4]: [lat, lng, vN, vE]
 */

package com.hud.navi

import kotlin.math.*

class EkfDeadReckoning {

    // === 状态 ===
    var lat = 0.0
    var lng = 0.0
    var vN = 0.0
    var vE = 0.0
    var heading = 0.0      // deg, 0=北
    var speed = 0.0        // m/s
    var initialized = false

    // === 协方差 P (4x4 对角近似) ===
    private val P = Array(4) { DoubleArray(4) }

    // === 噪声参数 ===
    private val ACCEL_NOISE = 0.5   // m/s²
    private var gpsR = 10.0         // GPS 观测噪声（米）
    private val GPS_R_FLOOR = 3.0

    // === GPS 丢失计时 ===
    var gpsLostTimeMs = 0L
    private var lastGpsTimeMs = 0L

    // === 调试输出 ===
    var lastKalmanGain = 0.0
    var lastInnovation = 0.0

    // === 航向源（多源融合） ===
    var compassBearing = 0.0
    var compassAvailable = false
    var rvBearing = 0.0
    var rvAvailable = false
    var gyroRateDegPerSec = 0.0
    private var gyroAvailable = false
    private var lastGyroTimeMs = 0L

    // 罗盘平滑
    private var compassSmoothed = 0.0
    private var compassInit = false
    private var rvSmoothed = 0.0
    private var rvInit = false

    // === 路网吸附（v10.20 简化版） ===
    var snappedToRoad = false
    var roadHeadingDeg = 0.0
    var snapConfidence = 0.0

    // === 初始化 ===
    fun initialize(lat: Double, lng: Double, bearing: Float, speedMs: Float, timeMs: Long) {
        this.lat = lat; this.lng = lng
        this.heading = bearing.toDouble()
        this.speed = speedMs.toDouble()

        val hRad = Math.toRadians(heading)
        vN = speed * cos(hRad)
        vE = speed * sin(hRad)

        val initVar = (gpsR * gpsR) / (111111.0 * 111111.0)
        for (i in 0..3) for (j in 0..3) P[i][j] = 0.0
        P[0][0] = initVar; P[1][1] = initVar
        P[2][2] = 4.0; P[3][3] = 4.0

        lastGpsTimeMs = timeMs
        gpsLostTimeMs = 0L
        initialized = true
    }

    // === 外部航向源输入 ===

    fun updateCompass(bearingDeg: Float) {
        compassAvailable = true
        if (!compassInit) {
            compassSmoothed = bearingDeg.toDouble()
            compassInit = true
        } else {
            var diff = bearingDeg.toDouble() - compassSmoothed
            if (diff > 180) diff -= 360
            if (diff < -180) diff += 360
            compassSmoothed += 0.15 * diff
            if (compassSmoothed < 0) compassSmoothed += 360
            if (compassSmoothed >= 360) compassSmoothed -= 360
        }
        compassBearing = compassSmoothed
    }

    fun updateGyroRate(rateDegPerSec: Float) {
        gyroAvailable = true
        lastGyroTimeMs = System.currentTimeMillis()
        gyroRateDegPerSec += 0.3 * (rateDegPerSec.toDouble() - gyroRateDegPerSec)
    }

    fun updateRotationVector(bearingDeg: Float) {
        rvAvailable = true
        if (!rvInit) {
            rvSmoothed = bearingDeg.toDouble()
            rvInit = true
        } else {
            var diff = bearingDeg.toDouble() - rvSmoothed
            if (diff > 180) diff -= 360
            if (diff < -180) diff += 360
            rvSmoothed += 0.2 * diff
            if (rvSmoothed < 0) rvSmoothed += 360
            if (rvSmoothed >= 360) rvSmoothed -= 360
        }
        rvBearing = rvSmoothed
    }

    // IMU 接口（保留 API 兼容）
    fun updateIMU(accel: FloatArray, gyro: FloatArray, timestampNs: Long) {
        if (gyro.size >= 3) {
            updateGyroRate(Math.toDegrees(gyro[2].toDouble()).toFloat())
        }
    }

    // === 预测步（Predict）— 每帧调用 ===

    fun predict(dtMs: Long, headingDeg: Float = -1f, speedMs: Float = 0f,
                linearAccelMag: Double = 999.0,
                worldAccN: Double = 0.0, worldAccE: Double = 0.0) {
        if (!initialized) return
        val dt = dtMs.toDouble() / 1000.0
        if (dt <= 0.0 || dt > 1.0) return

        // ── 航向确定（多源优先级）──
        val speedKmh = speedMs * 3.6
        val timeSinceGps = System.currentTimeMillis() - lastGpsTimeMs
        val gpsLost = timeSinceGps > 2000
        val gyroFresh = gyroAvailable && (System.currentTimeMillis() - lastGyroTimeMs < 500)

        val effectiveHeading = when {
            !gpsLost && speedKmh > 3.0 && headingDeg >= 0 -> headingDeg.toDouble()
            gpsLost && gyroFresh -> heading + gyroRateDegPerSec * dt
            rvAvailable -> rvSmoothed
            compassAvailable -> compassSmoothed
            else -> heading
        }

        // 航向 EMA 平滑
        var hDiff = effectiveHeading - heading
        if (hDiff > 180) hDiff -= 360
        if (hDiff < -180) hDiff += 360
        val hAlpha = when {
            gpsLost && gyroFresh -> 1.0
            speedKmh > 3.0 -> 0.3
            rvAvailable -> 0.25
            else -> 0.15
        }
        heading += hAlpha * hDiff
        if (heading < 0) heading += 360
        if (heading >= 360) heading -= 360

        // ── 速度处理 ──
        this.speed = speedMs.toDouble()

        // 分解速度到北/东
        val headingRad = Math.toRadians(heading)
        val newVN = speed * cos(headingRad)
        val newVE = speed * sin(headingRad)

        // 速度 EMA 平滑
        val speedAlpha = 0.3
        vN += speedAlpha * (newVN - vN)
        vE += speedAlpha * (newVE - vE)

        // ── 位置积分 ──
        val latRad = Math.toRadians(lat)
        lat += vN * dt / 111111.0
        lng += vE * dt / (111111.0 * cos(latRad).coerceAtLeast(0.01))

        // GPS 丢失衰减
        if (gpsLost) {
            val decay = 0.995
            vN *= decay; vE *= decay; speed *= decay
            gpsLostTimeMs = timeSinceGps
        } else {
            gpsLostTimeMs = 0L
        }

        // ── 协方差预测 ──
        val qLat = (ACCEL_NOISE * dt).pow(2) / (111111.0 * 111111.0)
        val qV = (ACCEL_NOISE * dt).pow(2)

        P[0][0] += qLat + 2.0 * dt * P[0][2]
        P[1][1] += qLat + 2.0 * dt * P[1][3]
        P[2][2] += qV
        P[3][3] += qV

        // GPS 丢失越久，过程噪声越大
        val driftScale = 1.0 + (gpsLostTimeMs / 60000.0) * 2.0
        P[0][0] *= (1.0 + (driftScale - 1.0) * 0.01)
        P[1][1] *= (1.0 + (driftScale - 1.0) * 0.01)

        // 上限
        val maxPosVar = (50.0 * 50.0) / (111111.0 * 111111.0)
        P[0][0] = minOf(P[0][0], maxPosVar)
        P[1][1] = minOf(P[1][1], maxPosVar)
        P[2][2] = minOf(P[2][2], 100.0)
        P[3][3] = minOf(P[3][3], 100.0)
    }

    // === 更新步（Update）— GPS 到达时 ===

    fun update(gpsLat: Double, gpsLng: Double, accuracy: Float, timeMs: Long) {
        if (!initialized) {
            initialize(gpsLat, gpsLng, 0f, 0f, timeMs)
            return
        }

        val accD = accuracy.toDouble().coerceAtLeast(1.0)
        gpsR = when {
            accD < 5.0 -> accD
            accD < 15.0 -> accD * 2.0
            else -> accD * 5.0
        }.coerceAtLeast(GPS_R_FLOOR)

        val yLat = gpsLat - lat
        val yLng = gpsLng - lng
        val innovMeters = sqrt(
            (yLat * 111111.0).pow(2) +
            (yLng * 111111.0 * cos(Math.toRadians(lat))).pow(2)
        )
        lastInnovation = innovMeters

        val effectiveR = if (innovMeters > 50.0) gpsR * 3.0 else gpsR

        val rLat = (effectiveR / 111111.0).pow(2)
        val rLng = (effectiveR / (111111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01))).pow(2)

        val sLat = P[0][0] + rLat
        val sLng = P[1][1] + rLng
        val kLat = P[0][0] / sLat
        val kLng = P[1][1] / sLng
        lastKalmanGain = maxOf(kLat, kLng)

        lat += kLat * yLat
        lng += kLng * yLng

        val gpsInterval = maxOf(0.5, (timeMs - lastGpsTimeMs).toDouble() / 1000.0)
        val kV = 0.1 * maxOf(kLat, kLng)
        vN += kV * (yLat * 111111.0 / gpsInterval)
        vE += kV * (yLng * 111111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01) / gpsInterval)

        P[0][0] *= (1.0 - kLat)
        P[1][1] *= (1.0 - kLng)
        P[2][2] *= (1.0 - kV)
        P[3][3] *= (1.0 - kV)

        P[0][2] *= 0.5; P[2][0] *= 0.5
        P[1][3] *= 0.5; P[3][1] *= 0.5

        lastGpsTimeMs = timeMs
        gpsLostTimeMs = 0L
    }

    // === 路网约束更新 ===

    fun roadConstrainedUpdate(snapLat: Double, snapLng: Double, confidence: Double,
                               roadHeading: Float, timeMs: Long) {
        if (!initialized) return
        roadHeadingDeg = roadHeading.toDouble()
        snapConfidence = confidence
        if (confidence < 0.3) return

        val pseudoR = (5.0 / confidence.coerceAtLeast(0.1)).coerceAtMost(30.0)
        val rLat = (pseudoR / 111111.0).pow(2)
        val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        val rLng = (pseudoR / (111111.0 * cosLat)).pow(2)

        val yLat = snapLat - lat
        val yLng = snapLng - lng
        val sLat = P[0][0] + rLat
        val sLng = P[1][1] + rLng
        val kLat = P[0][0] / sLat
        val kLng = P[1][1] / sLng

        val scale = confidence.coerceIn(0.0, 0.6)
        lat += kLat * yLat * scale
        lng += kLng * yLng * scale
        P[0][0] *= (1.0 - kLat * scale)
        P[1][1] *= (1.0 - kLng * scale)
    }

    // === 查询接口 ===

    fun getPositionUncertainty(): Double {
        val latStd = sqrt(maxOf(0.0, P[0][0])) * 111111.0
        val lngStd = sqrt(maxOf(0.0, P[1][1])) * 111111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        return sqrt(latStd * latStd + lngStd * lngStd)
    }

    fun getStatusString(): String {
        val unc = getPositionUncertainty()
        val gpsAge = System.currentTimeMillis() - lastGpsTimeMs
        val headingSource = when {
            speed * 3.6 > 3.0 && gpsAge < 2000 -> "GPS"
            gpsAge > 2000 && gyroAvailable -> "陀螺"
            rvAvailable -> "RV"
            compassAvailable -> "罗盘"
            else -> "惯导"
        }
        val mode = if (gpsAge < 2000) "GPS+IMU" else "IMU-DR"
        val snapTag = if (snappedToRoad && snapConfidence > 0.3) "⊕路" else ""
        return "$mode[$headingSource]$snapTag " +
                "K=${String.format("%.2f", lastKalmanGain)} " +
                "Δ=${String.format("%.1f", lastInnovation)}m " +
                "σ=${String.format("%.1f", unc)}m"
    }
}
