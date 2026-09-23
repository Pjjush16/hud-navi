package com.hud.navi

import android.content.Context
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.osmdroid.util.GeoPoint

/**
 * 插值定位提供者：在两次 GPS 更新之间进行线性插值，
 * 让位置标记在地图上平滑移动而不是跳跃。
 *
 * 供 osmdroid MyLocationNewOverlay 使用。
 */
class InterpolatedLocationProvider(private val context: Context) :
    org.osmdroid.views.overlay.mylocation.IMyLocationConsumer,
    org.osmdroid.views.overlay.mylocation.IMyLocationProvider {

    private val handler = Handler(Looper.getMainLooper())
    private var consumers = mutableListOf<org.osmdroid.views.overlay.mylocation.IMyLocationConsumer>()

    // 原始 GPS 位置
    private var gpsLat = 0.0
    private var gpsLng = 0.0
    private var gpsAccuracy = 0f
    private var gpsBearing = 0f
    private var gpsSpeed = 0f
    private var hasFix = false

    // 插值位置
    private var interpLat = 0.0
    private var interpLng = 0.0
    private var lastUpdateTime = 0L

    // 速度平滑（EMA）
    private var smoothedSpeed = 0f
    private val speedAlpha = 0.2f

    // 方向平滑（EMA）
    private var smoothedBearing = 0f
    private val bearingAlpha = 0.15f

    var lastKnownLocation: Location? = null
        private set

    /**
     * GPS 更新时调用
     */
    fun updateLocation(lat: Double, lng: Double, accuracy: Float, bearing: Float, speed: Float) {
        if (!hasFix) {
            gpsLat = lat; gpsLng = lng
            interpLat = lat; interpLng = lng
            smoothedSpeed = speed * 3.6f
            smoothedBearing = bearing
            hasFix = true
        } else {
            gpsLat = lat; gpsLng = lng
        }

        gpsAccuracy = accuracy
        gpsBearing = bearing
        gpsSpeed = speed

        // EMA 平滑
        smoothedSpeed = smoothedSpeed * (1 - speedAlpha) + (speed * 3.6f) * speedAlpha
        if (bearing != 0f || speed > 0.5f) {
            // 处理 359° → 1° 跨越
            val diff = bearing - smoothedBearing
            val wrappedDiff = ((diff + 540f) % 360f) - 180f
            smoothedBearing = (smoothedBearing + wrappedDiff * bearingAlpha + 360f) % 360f
        }

        lastUpdateTime = System.currentTimeMillis()

        // 构建 Location 对象
        val loc = Location("gps").apply {
            latitude = lat
            longitude = lng
            this.accuracy = accuracy
            this.bearing = bearing
            this.speed = speed
            time = System.currentTimeMillis()
        }
        lastKnownLocation = loc

        // 通知消费者
        for (c in consumers) {
            c.onLocationChanged(loc, this)
        }
    }

    fun hasGpsFix() = hasFix
    fun getSmoothedSpeedKmh(): Float = smoothedSpeed
    fun getSmoothedBearing(): Float = smoothedBearing

    // === IMyLocationProvider 接口 ===

    override fun startLocationConsumer(consumer: org.osmdroid.views.overlay.mylocation.IMyLocationConsumer): Boolean {
        consumers.add(consumer)
        // 如果有已定位的点，立即通知
        if (hasFix && lastKnownLocation != null) {
            consumer.onLocationChanged(lastKnownLocation!!, this)
        }
        return true
    }

    override fun stopLocationConsumer(consumer: org.osmdroid.views.overlay.mylocation.IMyLocationConsumer): Boolean {
        consumers.remove(consumer)
        return true
    }

    override fun getLastKnownLocation(): Location? = lastKnownLocation

    override fun onDestroy() {
        consumers.clear()
        handler.removeCallbacksAndMessages(null)
    }
}
