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
 * hud-navi v10.24 — 逐段哈希 + 增量 diff 更新
 *
 * v10.23 变更：
 * - 每条路网段独立 SHA-256 哈希，不再整图哈希
 * - 新旧哈希集合做差集 → 精确检测 added/removed 段
 * - 增量合并：保留不变段 + 加入新段 - 移除旧段
 * - 只有实际存在段增删时才重绘，位置微偏不触发全量刷新
 *
 * v10.21 变更：
 * - 修复 setRoads 从 IO 线程调用 invalidate() 导致路网加载后不渲染（改用 postInvalidate()）
 * - 路网查询半径 2000m → 3000m，缓存有效距离 1500m → 2500m
 * - 刷新触发距离 300m → 500m，保证前方始终有已渲染路网
 */
class HudView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // === 车辆状态 ===
    var vehicleLat: Double = 0.0
    var vehicleLng: Double = 0.0
    var vehicleBearing: Float = 0f
    var vehicleSpeed: Float = 0f     // km/h

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

    // === 地图中心（固定不动，车在地图上移动） ===
    var mapCenterLat: Double = 0.0
    var mapCenterLng: Double = 0.0

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
        // postInvalidate() 线程安全，可从 IO 线程调用；invalidate() 只能在 UI 线程调用
        postInvalidate()
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

        // 速度显示（在镜像块内部，随镜像翻转）
        drawSpeedometer(canvas, w, h)

        if (mirrorEnabled) {
            canvas.restore()
        }

        // OSM 归属标注（ODbL 协议要求，右下角半透明小字）
        drawAttribution(canvas, w, h)
    }

    private fun drawAttribution(canvas: Canvas, w: Float, h: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#44FFFFFF")
            textSize = 22f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val attrY = if (mirrorEnabled) 24f else h - 8f
        canvas.drawText("© OpenStreetMap contributors", w / 2f, attrY, paint)
    }

    private fun drawRoadNetwork(canvas: Canvas, w: Float, h: Float, metersPerPixel: Double) {
        // 车永远在屏幕中心，路网以车辆位置为中心绘制
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

        // === 路名标签（仅 PRIMARY 及以上等级道路） ===
        if (mirrorEnabled) {
            // 抵消垂直镜像：在路网坐标系中再做一次 Y 翻转，让文字正向
            canvas.save()
            canvas.scale(1f, -1f)
            drawRoadNames(canvas, cx, cy, drawLat, drawLng, metersPerPixel, zoomFactor)
            canvas.restore()
        } else {
            drawRoadNames(canvas, cx, cy, drawLat, drawLng, metersPerPixel, zoomFactor)
        }

        canvas.restore()
    }

    private val roadNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFFFFF.toInt()
        textSize = 20f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }

    private fun drawRoadNames(
        canvas: Canvas, cx: Float, cy: Float,
        centerLat: Double, centerLng: Double,
        metersPerPixel: Double, zoomFactor: Float
    ) {
        // 只对 PRIMARY / TRUNK / MOTORWAY 显示路名
        val nameThreshold = RoadFetcher.RoadType.PRIMARY
        for (segment in roadSegments) {
            if (segment.name.isEmpty()) continue
            if (segment.type.priority < nameThreshold.priority) continue
            if (segment.points.size < 2) continue

            // 取路段中点
            val midIdx = segment.points.size / 2
            val (midLat, midLng) = segment.points[midIdx]
            val (mx, my) = latLngToPixel(midLat, midLng, centerLat, centerLng, metersPerPixel)

            // 计算路段方向角（用于沿路旋转文字）
            val p1 = segment.points[midIdx - 1]
            val p2 = segment.points[minOf(midIdx + 1, segment.points.size - 1)]
            val (x1, y1) = latLngToPixel(p1.first, p1.second, centerLat, centerLng, metersPerPixel)
            val (x2, y2) = latLngToPixel(p2.first, p2.second, centerLat, centerLng, metersPerPixel)
            var angle = Math.toDegrees(atan2(y2 - y1, x2 - x1).toDouble()).toFloat()

            // 保证文字总是正向可读（不 upside down）
            if (angle > 90f) angle -= 180f
            if (angle < -90f) angle += 180f

            val fontSize = (segment.type.widthBase * zoomFactor * 0.7f * 2.5f).coerceIn(16f, 32f)
            roadNamePaint.textSize = fontSize

            canvas.save()
            canvas.translate(mx, my)
            canvas.rotate(angle)
            canvas.drawText(segment.name, 0f, -fontSize * 0.4f, roadNamePaint)
            canvas.restore()
        }
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

        // v10.16: 蓝色实心圆 + 白色描边 + 白色4点 chevron 箭头
        val size = 48f
        val circleRadius = size * 1.2f

        // === 白色描边圆（外层） ===
        val whiteBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, circleRadius + 3f, whiteBorderPaint)

        // === 蓝色实心圆（内层） ===
        val blueFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2196F3")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, circleRadius, blueFillPaint)

        // === 白色4点 chevron 箭头（大三角挖掉底部小三角） ===
        val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        // 4个顶点：顶、左肩、底部凹口、右肩
        val tipY = cy - size * 0.65f          // 1. 顶部尖端
        val shoulderY = cy + size * 0.5f      // 2 & 4. 左右肩（底部最宽处）
        val indentY = cy + size * 0.05f       // 3. 底部凹口点（小三角形被挖掉）
        val halfW = size * 0.5f               // 左右肩的半宽

        val chevronPath = Path().apply {
            moveTo(cx, tipY)                   // → 顶
            lineTo(cx - halfW, shoulderY)      // → 左肩
            lineTo(cx, indentY)                // → 底部凹口（挖掉小三角）
            lineTo(cx + halfW, shoulderY)      // → 右肩
            close()                            // → 回到顶
        }

        canvas.drawPath(chevronPath, arrowFillPaint)
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
