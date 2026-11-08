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
 * hud-navi v10.0 — 应用图标 + 签名发布 + GitHub Release
 *
 * v10.0 变更：
 * - 新增自定义应用图标（HUD 导航风格）
 * - 配置 release 签名（keystore）
 * - CI 自动构建 release APK 并发布到 GitHub Releases
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

        // 渐变遮罩：视觉底部 28% 区域做 BLACK→TRANSPARENT 渐变，其余透明
        val fadeHeight = h * 0.28f
        val fadeShader = if (mirrorEnabled) {
            // 镜像翻转后 canvas y=h = 视觉底部
            // y=h BLACK → y=h-fadeHeight TRANSPARENT
            // CLAMP y<h-fadeHeight → TRANSPARENT（路网区域全透明）
            LinearGradient(0f, h, 0f, h - fadeHeight,
                Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        } else {
            // 非镜像：canvas y=0 = 视觉顶部
            // y=0 BLACK → y=fadeHeight TRANSPARENT
            // CLAMP y>fadeHeight → TRANSPARENT（路网区域全透明）
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

        // OSM 归属标注（ODbL 协议要求，始终在视觉底部）
        drawAttribution(canvas, w, h)
    }

    private fun drawAttribution(canvas: Canvas, w: Float, h: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#44FFFFFF")
            textSize = 22f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        // 镜像时视觉底部 = canvas y=0 附近；非镜像 = canvas y=h 附近
        val attrY = if (mirrorEnabled) 24f else h - 8f
        canvas.drawText("© OpenStreetMap contributors", w / 2f, attrY, paint)
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
