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

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

/**
 * hud-navi v9.6 — 镜像渐变起点修正（从速度表位置开始绘制）
 *
 * v9.6 变更：
 * - 镜像模式下渐变起点从 canvas y=h 改为 y=speedometerBot(180f)
 * - CLAMP y<speedBot 延伸 BLACK(速度表区纯黑)，y>speedBot+fadeHeight 延伸 TRANSPARENT(路网透明)
 * - 不再用 canvas y=h 作起点导致 CLAMP 把黑色铺满整个路网
 */
class HudView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // === 车辆状态 ===
    var vehicleLat: Double = 0.0
    var vehicleLng: Double = 0.0
    var vehicleBearing: Float = 0f
    var vehicleSpeed: Float = 0f     // km/h
    var statusText: String = "等待 GPS..."

    // === HUD 镜像（垂直翻转，用于挡风玻璃投影） ===
    var mirrorEnabled: Boolean = true  // 默认开启镜像

    // === 路网数据 ===
    var roadSegments: List<RoadFetcher.RoadSegment> = emptyList()
        private set
    var hasRoads: Boolean = false
        private set

    // === 道路吸附 ===
    var snappedLat: Double = 0.0
    var snappedLng: Double = 0.0
    var isSnapped: Boolean = false

    // === 速度制动态缩放 ===
    private fun getDynamicZoom(speedKmh: Float): Float {
        val clamped = speedKmh.coerceIn(0f, 150f)
        return 18f - (clamped / 150f) * 3f
    }

    // === 画笔 ===
    private val roadPaints = mutableMapOf<RoadFetcher.RoadType, Paint>()

    private val speedNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 96f; isFakeBoldText = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val speedUnitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#666666"); textSize = 28f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    // === 顶部渐变遮罩画笔（路网淡出效果） ===
    private val topFadePaint = Paint().apply { style = Paint.Style.FILL }

    // === 速度 → 颜色插值 ===
    private fun getSpeedColor(speedKmh: Float): Int {
        val t = (speedKmh / 120f).coerceIn(0f, 1f)
        return when {
            t <= 0.5f -> {
                // 绿 → 黄
                val r = (t / 0.5f)
                Color.rgb((r * 255).toInt(), 255, 0)
            }
            else -> {
                // 黄 → 红
                val r = ((t - 0.5f) / 0.5f)
                Color.rgb(255, (255 * (1f - r)).toInt(), 0)
            }
        }
    }

    private val vehicleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }
    private val vehicleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = 2f; strokeJoin = Paint.Join.ROUND
    }

    private val snapGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x3300FF88.toInt(); style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.OUTER)
    }

    private val roadPath = Path()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        RoadFetcher.RoadType.values().forEach { type ->
            roadPaints[type] = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = type.color
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
        }
    }

    fun setRoads(segments: List<RoadFetcher.RoadSegment>, centerLat: Double, centerLng: Double) {
        roadSegments = segments
        hasRoads = segments.isNotEmpty()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawColor(Color.BLACK)

        if (vehicleLat == 0.0) {
            val waitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF8844"); textSize = 36f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("等待 GPS...", w / 2f, h / 2f, waitPaint)
            return
        }

        // HUD 镜像：垂直翻转整个画面（挡风玻璃投影）
        if (mirrorEnabled) {
            canvas.save()
            canvas.scale(1f, -1f, w / 2f, h / 2f)
        }

        val dynamicZoom = getDynamicZoom(vehicleSpeed)
        val metersPerPixel = getMetersPerPixel(vehicleLat, dynamicZoom)

        drawRoadNetwork(canvas, w, h, metersPerPixel)
        drawVehicleMarker(canvas, w, h)

        // 渐变遮罩：速度表区域纯黑，路网区域透明，中间渐变过渡
        // 速度表底部边界（canvas 坐标）：speedY(120) + 文字高度(40) + 下间距(20) = 180f
        val speedBot = 180f
        val fadeHeight = h * 0.28f
        val fadeShader = if (mirrorEnabled) {
            // 镜像翻转后，速度表在 canvas 顶部(y≈0~180)，视觉在屏幕底部
            // 渐变从速度表底部开始，向上(canvas y 增大方向)过渡到透明
            // CLAMP y<speedBot → BLACK(速度表区纯黑)
            // CLAMP y>speedBot+fadeHeight → TRANSPARENT(路网区透明)
            LinearGradient(0f, speedBot, 0f, speedBot + fadeHeight,
                Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        } else {
            // 非镜像：速度表在 canvas 顶部 = 视觉顶部
            // CLAMP y<0 → BLACK，CLAMP y>fadeHeight → TRANSPARENT(路网区透明)
            LinearGradient(0f, 0f, 0f, fadeHeight,
                Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        topFadePaint.shader = fadeShader
        canvas.drawRect(0f, 0f, w, h, topFadePaint)

        // 速度显示（在镜像块内部，随镜像翻转）
        drawSpeedometer(canvas, w, h)

        if (mirrorEnabled) {
            canvas.restore()
        }
    }

    private fun drawRoadNetwork(canvas: Canvas, w: Float, h: Float, metersPerPixel: Double) {
        val drawLat = if (isSnapped) snappedLat else vehicleLat
        val drawLng = if (isSnapped) snappedLng else vehicleLng
        val cx = w / 2f
        val cy = h * 0.55f

        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(-vehicleBearing)

        val dynamicZoom = getDynamicZoom(vehicleSpeed)
        val zoomFactor = 2f.pow(dynamicZoom - 15)

        for (segment in roadSegments) {
            val paint = roadPaints[segment.type] ?: continue
            paint.strokeWidth = segment.type.widthBase * zoomFactor * 0.7f

            roadPath.reset()
            var first = true
            for ((lat, lng) in segment.points) {
                val (px, py) = latLngToPixel(lat, lng, drawLat, drawLng, metersPerPixel)
                if (first) { roadPath.moveTo(px, py); first = false }
                else roadPath.lineTo(px, py)
            }
            canvas.drawPath(roadPath, paint)
        }

        canvas.restore()
    }

    private fun latLngToPixel(
        lat: Double, lng: Double,
        centerLat: Double, centerLng: Double,
        metersPerPixel: Double
    ): Pair<Float, Float> {
        val cosLat = cos(Math.toRadians(centerLat))
        val dx = (lng - centerLng) * 111320.0 * cosLat
        val dy = (lat - centerLat) * 110540.0
        val px = (dx / metersPerPixel).toFloat()
        val py = (-dy / metersPerPixel).toFloat()
        return Pair(px, py)
    }

    private fun getMetersPerPixel(lat: Double, zoom: Float): Double {
        val cosLat = cos(Math.toRadians(lat))
        return 156543.03392 * cosLat / (2.0.pow(zoom.toDouble()))
    }

    private fun drawVehicleMarker(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h * 0.55f

        // 车标颜色随速度变化：绿→黄→红
        val speedColor = getSpeedColor(vehicleSpeed)
        vehicleFillPaint.color = speedColor
        vehicleStrokePaint.color = speedColor

        if (isSnapped) {
            // 吸附光晕也用速度色
            snapGlowPaint.color = Color.argb(0x33,
                Color.red(speedColor), Color.green(speedColor), Color.blue(speedColor))
            canvas.drawCircle(cx, cy, 30f, snapGlowPaint)
        }

        val size = 28f
        val tipY = cy - size * 1.4f
        val shoulderY = cy + size * 0.3f
        val tailY = cy + size * 0.8f
        val indentY = cy
        val halfW = size * 0.55f

        val dartPath = Path().apply {
            moveTo(cx, tipY)
            lineTo(cx - halfW, shoulderY)
            lineTo(cx - halfW * 0.4f, tailY)
            lineTo(cx, indentY)
            lineTo(cx + halfW * 0.4f, tailY)
            lineTo(cx + halfW, shoulderY)
            close()
        }

        canvas.drawPath(dartPath, vehicleFillPaint)
        canvas.drawPath(dartPath, vehicleStrokePaint)
    }

    private fun drawSpeedometer(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val speedY = 120f
        val speedStr = vehicleSpeed.toInt().toString()

        // 速度数字随车标同色
        speedNumPaint.color = getSpeedColor(vehicleSpeed)
        canvas.drawText(speedStr, cx, speedY, speedNumPaint)
        canvas.drawText("km/h", cx, speedY + 40f, speedUnitPaint)
    }
}
