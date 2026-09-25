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
 * hud-navi v8.0 — 极简 HUD + 上帝视角 + 道路吸附 + 速度制缩放
 *
 * 视觉对标 Hudway：
 * - 仅顶部时速码表，无任何多余 UI
 * - 上帝视角（正上方俯视），地图随航向旋转
 * - 速度越高 zoom 越小（远处视野大）：15~18 之间动态缩放
 * - 道路吸附：车辆位置自动贴合到最近道路
 */
class HudView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // === 车辆状态 ===
    var vehicleLat: Double = 0.0
    var vehicleLng: Double = 0.0
    var vehicleBearing: Float = 0f
    var vehicleSpeed: Float = 0f     // km/h
    var roadLayer: Int = 0           // 当前道路层级：0=地面, 1=高架/桥上, -1=隧道/地下
    var statusText: String = "等待 GPS..."

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
    // speed=0 → zoom=18（近景，看清细节）
    // speed=30 → zoom=17
    // speed=60 → zoom=16
    // speed=120+ → zoom=15（远景，看清全局）
    private fun getDynamicZoom(speedKmh: Float): Float {
        val clamped = speedKmh.coerceIn(0f, 150f)
        // 线性映射: 0km/h→18, 150km/h→15
        return 18f - (clamped / 150f) * 3f
    }

    // === 画笔 ===
    private val roadPaints = mutableMapOf<RoadFetcher.RoadType, Paint>()
    private val bgPaint = Paint().apply { color = Color.BLACK }

    // 速度码表（顶部居中，大字体）
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

    // 车辆标记（飞镖形，白色）
    private val vehicleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }
    private val vehicleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = 2f; strokeJoin = Paint.Join.ROUND
    }

    // 道路吸附指示
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

        // 动态缩放
        val dynamicZoom = getDynamicZoom(vehicleSpeed)
        val metersPerPixel = getMetersPerPixel(vehicleLat, dynamicZoom)

        // 绘制路网
        drawRoadNetwork(canvas, w, h, metersPerPixel)

        // 绘制车辆
        drawVehicleMarker(canvas, w, h)

        // 顶部时速码表
        drawSpeedometer(canvas, w)
    }

    private fun drawRoadNetwork(canvas: Canvas, w: Float, h: Float, metersPerPixel: Double) {
        // 使用吸附后的坐标（如果有）或原始坐标
        val drawLat = if (isSnapped) snappedLat else vehicleLat
        val drawLng = if (isSnapped) snappedLng else vehicleLng
        val cx = w / 2f
        val cy = h * 0.55f  // 车辆位置（中心偏下一点，给顶部码表留空间）

        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(-vehicleBearing)

        val dynamicZoom = getDynamicZoom(vehicleSpeed)
        val zoomFactor = 2f.pow(dynamicZoom - 15)

        // 虚线效果（用于隧道/地下路段）
        val dashPathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)

        // 纯平面绘制：所有道路在同一平面上，仅靠线条样式区分
        // 地面 = 正常实线
        // 高架 = 加粗实线（×1.3）
        // 隧道 = 虚线 + 半透明
        for (segment in roadSegments) {
            val paint = roadPaints[segment.type] ?: continue
            paint.strokeWidth = segment.type.widthBase * zoomFactor * 0.7f

            if (segment.elevated) {
                // 高架：加粗，不偏移
                paint.strokeWidth *= 1.3f
            }

            if (segment.tunnel) {
                // 隧道：虚线 + 降低透明度
                paint.pathEffect = dashPathEffect
                paint.alpha = 100
            }

            roadPath.reset()
            var first = true
            for ((lat, lng) in segment.points) {
                val (px, py) = latLngToPixel(lat, lng, drawLat, drawLng, metersPerPixel)
                if (first) { roadPath.moveTo(px, py); first = false }
                else roadPath.lineTo(px, py)
            }
            canvas.drawPath(roadPath, paint)

            // 恢复画笔状态
            paint.pathEffect = null
            paint.alpha = 255
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
        // 支持浮点 zoom
        return 156543.03392 * cosLat / (2.0.pow(zoom.toDouble()))
    }

    private fun drawVehicleMarker(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h * 0.55f

        // 道路吸附时显示绿色光晕
        if (isSnapped) {
            canvas.drawCircle(cx, cy, 30f, snapGlowPaint)
        }

        // 飞镖形状
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

    /**
     * 顶部时速码表 — Hudway 风格
     * 大号数字 + 小字 km/h
     * 右上角显示道路层级指示器（↑高架 / ↓隧道 / 无=地面）
     */
    private fun drawSpeedometer(canvas: Canvas, w: Float) {
        val cx = w / 2f
        val speedY = 120f
        val speedStr = vehicleSpeed.toInt().toString()
        canvas.drawText(speedStr, cx, speedY, speedNumPaint)
        canvas.drawText("km/h", cx, speedY + 40f, speedUnitPaint)

        // 道路层级指示器（右上角，仅非地面时显示）
        if (roadLayer != 0) {
            val (label, color) = when {
                roadLayer > 1 -> "↑ 高架 L$roadLayer" to Color.parseColor("#FF8844")
                roadLayer == 1 -> "↑ 高架" to Color.parseColor("#FF8844")
                roadLayer < -1 -> "↓ 隧道 L$roadLayer" to Color.parseColor("#4488FF")
                else -> "↓ 隧道" to Color.parseColor("#4488FF")
            }
            val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; textSize = 28f
                textAlign = Paint.Align.RIGHT
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            // 半透明背景圆角矩形
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = 0x66000000.toInt()
            }
            val textW = layerPaint.measureText(label)
            val bgRect = RectF(w - 35f - textW - 16f, 25f, w - 20f, 70f)
            canvas.drawRoundRect(bgRect, 8f, 8f, bgPaint)
            canvas.drawText(label, w - 30f, 58f, layerPaint)
        }
    }
}
