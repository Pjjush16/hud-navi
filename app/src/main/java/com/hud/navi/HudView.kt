package com.hud.navi

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

/**
 * hud-navi v7.0 — 自研 Canvas 2D 绘制引擎
 *
 * 标准上帝视角（正上方俯视），地图随航向旋转，车辆始终居中。
 * 零第三方地图 SDK 依赖，纯 Canvas API 绘制路网。
 */
class HudView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // === 车辆状态（由 MainActivity 更新） ===
    var vehicleLat: Double = 0.0
    var vehicleLng: Double = 0.0
    var vehicleBearing: Float = 0f  // 航向角（0=北，顺时针）
    var vehicleSpeed: Float = 0f    // km/h

    // === 路网数据 ===
    private var roadSegments: List<RoadFetcher.RoadSegment> = emptyList()
    private var roadDataCenterLat: Double = 0.0
    private var roadDataCenterLng: Double = 0.0

    // === 缩放 ===
    // metersPerPixel 在基准纬度下的值
    // zoom 15 ≈ 4.8 m/px, zoom 16 ≈ 2.4, zoom 17 ≈ 1.2, zoom 18 ≈ 0.6
    var zoomLevel: Int = 16
        set(value) {
            field = value.coerceIn(13, 19)
            invalidate()
        }

    // === 画笔 ===
    private val roadPaints = mutableMapOf<RoadFetcher.RoadType, Paint>()
    private val bgPaint = Paint().apply { color = 0xFF0D0D14.toInt() }
    private val vehiclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }
    private val vehicleGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x4400FF88.toInt(); style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(30f, BlurMaskFilter.Blur.OUTER)
    }
    private val vehicleOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00FF88.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x44FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x11FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.5f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF88CCFF.toInt(); textSize = 28f; typeface = Typeface.MONOSPACE
    }
    private val compassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt(); textSize = 22f; textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val roadPath = Path()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // 支持 BlurMaskFilter
        // 为每种道路类型创建画笔
        RoadFetcher.RoadType.values().forEach { type ->
            roadPaints[type] = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = type.color
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
        }
    }

    /**
     * 更新路网数据
     */
    fun setRoads(segments: List<RoadFetcher.RoadSegment>, centerLat: Double, centerLng: Double) {
        roadSegments = segments
        roadDataCenterLat = centerLat
        roadDataCenterLng = centerLng
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f

        // 1. 深色背景
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        if (vehicleLat == 0.0 && vehicleLng == 0.0) {
            // 未定位时显示等待状态
            drawWaitingState(canvas, cx, cy)
            return
        }

        // 2. 计算投影参数
        val metersPerPixel = getMetersPerPixel(vehicleLat, zoomLevel)

        // 3. 旋转画布（地图旋转，车始终朝上）
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(-vehicleBearing) // 负号：地图旋转而非车旋转

        // 4. 绘制网格
        drawGrid(canvas, metersPerPixel)

        // 5. 绘制路网（小路先画 → 大路后画）
        drawRoads(canvas, metersPerPixel)

        canvas.restore()

        // 6. 绘制车辆标记（不随地图旋转）
        drawVehicle(canvas, cx, cy)

        // 7. 绘制准星
        drawCrosshair(canvas, cx, cy)

        // 8. 绘制指南针
        drawCompass(canvas, w)

        // 9. 绘制比例尺
        drawScaleBar(canvas, h, metersPerPixel)
    }

    /**
     * 经纬度 → 屏幕像素（相对于车辆位置）
     */
    private fun latLngToPixel(
        lat: Double, lng: Double,
        metersPerPixel: Double
    ): Pair<Float, Float> {
        // 使用简化的局部平面投影（适合小范围）
        val cosLat = cos(Math.toRadians(vehicleLat))
        val dx = (lng - vehicleLng) * 111320.0 * cosLat  // 东西方向（米）
        val dy = (lat - vehicleLat) * 110540.0            // 南北方向（米）

        // 北为正 Y → 屏幕 Y 轴向下，取反
        val px = (dx / metersPerPixel).toFloat()
        val py = (-dy / metersPerPixel).toFloat()
        return Pair(px, py)
    }

    private fun getMetersPerPixel(lat: Double, zoom: Int): Double {
        val cosLat = cos(Math.toRadians(lat))
        return 156543.03392 * cosLat / (1 shl zoom)
    }

    private fun drawRoads(canvas: Canvas, metersPerPixel: Double) {
        for (segment in roadSegments) {
            val paint = roadPaints[segment.type] ?: continue
            // 线宽随缩放级别调整
            val zoomFactor = 2f.pow(zoomLevel - 15)
            paint.strokeWidth = segment.type.widthBase * zoomFactor

            roadPath.reset()
            var first = true
            for ((lat, lng) in segment.points) {
                val (px, py) = latLngToPixel(lat, lng, metersPerPixel)
                if (first) {
                    roadPath.moveTo(px, py)
                    first = false
                } else {
                    roadPath.lineTo(px, py)
                }
            }
            canvas.drawPath(roadPath, paint)
        }
    }

    private fun drawVehicle(canvas: Canvas, cx: Float, cy: Float) {
        // 外圈光晕
        canvas.drawCircle(cx, cy, 28f, vehicleGlowPaint)
        // 白色实心圆
        canvas.drawCircle(cx, cy, 12f, vehiclePaint)
        // 绿色描边
        canvas.drawCircle(cx, cy, 12f, vehicleOutlinePaint)

        // 方向三角（指向正上方，因为地图已经旋转了）
        val arrowPath = Path()
        arrowPath.moveTo(cx, cy - 22f)
        arrowPath.lineTo(cx - 8f, cy - 8f)
        arrowPath.lineTo(cx + 8f, cy - 8f)
        arrowPath.close()
        canvas.drawPath(arrowPath, vehicleOutlinePaint)
    }

    private fun drawCrosshair(canvas: Canvas, cx: Float, cy: Float) {
        val len = 60f
        canvas.drawLine(cx - len, cy, cx - 18f, cy, crosshairPaint)
        canvas.drawLine(cx + 18f, cy, cx + len, cy, crosshairPaint)
        canvas.drawLine(cx, cy - len, cx, cy - 18f, crosshairPaint)
        canvas.drawLine(cx, cy + 18f, cx, cy + len, crosshairPaint)
    }

    private fun drawGrid(canvas: Canvas, metersPerPixel: Double) {
        // 绘制 100m 间距的网格线
        val gridSpacingMeters = 100.0
        val gridSpacingPx = (gridSpacingMeters / metersPerPixel).toFloat()
        if (gridSpacingPx < 20f) return // 太密就不画

        val extent = maxOf(width, height).toFloat() * 1.5f
        var x = -extent
        while (x < extent) {
            val snapped = (x / gridSpacingPx).toInt() * gridSpacingPx
            canvas.drawLine(snapped, -extent, snapped, extent, gridPaint)
            canvas.drawLine(-extent, snapped, extent, snapped, gridPaint)
            x += gridSpacingPx
        }
    }

    private fun drawCompass(canvas: Canvas, w: Float) {
        val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val idx = ((vehicleBearing + 22.5f) % 360f / 45f).toInt() % 8
        val heading = directions[idx]
        canvas.drawText("$heading ${vehicleBearing.toInt()}°", w - 80f, 60f, compassPaint)
    }

    private fun drawScaleBar(canvas: Canvas, h: Float, metersPerPixel: Double) {
        // 比例尺：显示 100m 对应的像素宽度
        val barMeters = 100.0
        val barPx = (barMeters / metersPerPixel).toFloat()
        val y = h - 30f
        val x0 = 20f
        val paint = Paint(textPaint).apply { strokeWidth = 2f; style = Paint.Style.STROKE; color = 0xAAFFFFFF.toInt() }
        canvas.drawLine(x0, y, x0 + barPx, y, paint)
        canvas.drawLine(x0, y - 5f, x0, y + 5f, paint)
        canvas.drawLine(x0 + barPx, y - 5f, x0 + barPx, y + 5f, paint)
        canvas.drawText("100m", x0 + barPx / 2 - 20f, y - 10f, textPaint)
    }

    private fun drawWaitingState(canvas: Canvas, cx: Float, cy: Float) {
        val paint = Paint(textPaint).apply {
            textAlign = Paint.Align.CENTER; textSize = 36f; color = 0xFF44DDFF.toInt()
        }
        canvas.drawText("等待 GPS 定位...", cx, cy, paint)

        // 旋转的等待动画（用简单脉冲圆）
        val pulse = (System.currentTimeMillis() % 2000) / 2000f
        val r = 30f + pulse * 40f
        val alpha = (255 * (1f - pulse)).toInt().coerceIn(0, 255)
        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF44DDFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f
            this.alpha = alpha
        }
        canvas.drawCircle(cx, cy + 60f, r, circlePaint)
    }
}
