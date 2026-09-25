package com.hud.navi

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

/**
 * hud-navi v7.1 — 自研 Canvas 2D 引擎 + 标准上帝视角
 *
 * 与 v5.4 相同的 UI 布局：
 * - 速度显示：屏幕顶部居中（大字体）
 * - GPS 方向箭头：屏幕顶部居中（速度下方）
 * - 状态文本：屏幕底部居中
 * - 车辆标记：屏幕中心偏下（65%）
 * - 路网：上帝视角（正上方俯视），地图随航向旋转
 */
class HudView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // === 车辆状态（由 MainActivity 更新） ===
    var vehicleLat: Double = 0.0
    var vehicleLng: Double = 0.0
    var vehicleBearing: Float = 0f  // 航向角（0=北，顺时针）
    var vehicleSpeed: Float = 0f    // km/h
    var statusText: String = "等待 GPS..."

    // === 路网数据 ===
    private var roadSegments: List<RoadFetcher.RoadSegment> = emptyList()

    // === 缩放 ===
    var zoomLevel: Int = 16
        set(value) {
            field = value.coerceIn(13, 19)
            invalidate()
        }

    // === 画笔 ===
    private val roadPaints = mutableMapOf<RoadFetcher.RoadType, Paint>()
    private val bgPaint = Paint().apply { color = Color.BLACK }

    // 速度（顶部居中大字体，同 v5.4）
    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 80f; isFakeBoldText = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888888"); textSize = 28f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    // GPS 方向箭头（顶部居中）
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF88"); style = Paint.Style.FILL
    }
    private val arrowGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF88"); style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(15f, BlurMaskFilter.Blur.OUTER)
    }

    // 状态文本（底部居中）
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8844"); textSize = 30f
        textAlign = Paint.Align.CENTER
    }

    // 车辆标记（中心）
    private val vehicleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }
    private val vehicleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = 2f; strokeJoin = Paint.Join.ROUND
    }

    // 准星
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
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

    fun setRoads(segments: List<RoadFetcher.RoadSegment>, centerLat: Double, centerLng: Double) {
        roadSegments = segments
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 1. 纯黑背景
        canvas.drawColor(Color.BLACK)

        if (vehicleLat == 0.0) {
            // 未定位：显示等待提示
            canvas.drawText("等待 GPS...", w / 2f, h / 2f, statusPaint)
            return
        }

        // 2. 上帝视角路网渲染
        drawRoadNetwork(canvas, w, h)

        // 3. 车辆标记（屏幕中心偏下 65%）
        drawVehicleMarker(canvas, w, h)

        // 4. HUD 信息层（不随地图旋转）
        drawHudInfo(canvas, w, h)
    }

    /**
     * 上帝视角路网渲染
     * 正上方俯视，地图随航向旋转，车辆始终居中
     */
    private fun drawRoadNetwork(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h * 0.65f  // 车辆在 65% 处（中心偏下，给顶部 HUD 留空间）
        val metersPerPixel = getMetersPerPixel(vehicleLat, zoomLevel)

        // 保存画布，旋转到航向
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(-vehicleBearing)

        // 绘制路网（小路先画 → 大路后画）
        for (segment in roadSegments) {
            val paint = roadPaints[segment.type] ?: continue
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

        // 准星（随地图旋转，表示前方方向）
        val len = 50f
        canvas.drawLine(-len, 0f, -15f, 0f, crosshairPaint)
        canvas.drawLine(15f, 0f, len, 0f, crosshairPaint)
        canvas.drawLine(0f, -len, 0f, -15f, crosshairPaint)
        canvas.drawLine(0f, 15f, 0f, len, crosshairPaint)

        canvas.restore()
    }

    /**
     * 经纬度 → 屏幕像素（相对于车辆位置，上帝视角）
     */
    private fun latLngToPixel(lat: Double, lng: Double, metersPerPixel: Double): Pair<Float, Float> {
        val cosLat = cos(Math.toRadians(vehicleLat))
        val dx = (lng - vehicleLng) * 111320.0 * cosLat  // 东西（米）
        val dy = (lat - vehicleLat) * 110540.0            // 南北（米）
        val px = (dx / metersPerPixel).toFloat()
        val py = (-dy / metersPerPixel).toFloat()  // 北=上=-Y
        return Pair(px, py)
    }

    private fun getMetersPerPixel(lat: Double, zoom: Int): Double {
        val cosLat = cos(Math.toRadians(lat))
        return 156543.03392 * cosLat / (1 shl zoom)
    }

    /**
     * 车辆标记 — 飞镖/纸飞机形（同 v5.4）
     */
    private fun drawVehicleMarker(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h * 0.65f

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
     * HUD 信息层（同 v5.4 布局）
     * - 速度：屏幕顶部居中
     * - GPS 方向箭头：顶部居中（速度下方）
     * - 状态文本：底部居中
     */
    private fun drawHudInfo(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f

        // ── 速度（屏幕顶部居中，大字体）──
        val speedStr = vehicleSpeed.toInt().toString()
        val speedY = 100f
        canvas.drawText(speedStr, cx, speedY, speedPaint)
        canvas.drawText("km/h", cx, speedY + 36f, unitPaint)

        // ── GPS 方向箭头（顶部居中，速度下方）──
        drawDirectionArrow(canvas, cx, speedY + 80f)

        // ── 状态文本（底部居中）──
        canvas.drawText(statusText, cx, h - 40f, statusPaint)

        // ── 指南针（右上角小字）──
        val compassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x88FFFFFF.toInt(); textSize = 22f; textAlign = Paint.Align.RIGHT
            typeface = Typeface.DEFAULT_BOLD
        }
        val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val idx = ((vehicleBearing + 22.5f) % 360f / 45f).toInt() % 8
        canvas.drawText("${directions[idx]} ${vehicleBearing.toInt()}°", w - 20f, 36f, compassPaint)

        // ── 比例尺（左下角）──
        drawScaleBar(canvas, h, getMetersPerPixel(vehicleLat, zoomLevel))
    }

    /**
     * GPS 方向箭头 — 顶部居中
     * 始终指北（不随地图旋转），让用户知道当前朝向
     */
    private fun drawDirectionArrow(canvas: Canvas, cx: Float, cy: Float) {
        val size = 16f

        // 外圈光晕
        canvas.drawCircle(cx, cy, size + 8f, arrowGlowPaint)

        // 箭头指北方向
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(-vehicleBearing) // 箭头始终指北，车辆旋转时箭头反向

        val arrowPath = Path()
        arrowPath.moveTo(0f, -size)       // 尖端（北）
        arrowPath.lineTo(-size * 0.6f, size * 0.5f)  // 左下
        arrowPath.lineTo(0f, size * 0.2f)             // 内凹
        arrowPath.lineTo(size * 0.6f, size * 0.5f)   // 右下
        arrowPath.close()

        canvas.drawPath(arrowPath, arrowPaint)
        canvas.restore()

        // "N" 标签
        val nPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00FF88"); textSize = 18f
            textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("N", cx, cy - size - 8f, nPaint)
    }

    private fun drawScaleBar(canvas: Canvas, h: Float, metersPerPixel: Double) {
        val barMeters = 100.0
        val barPx = (barMeters / metersPerPixel).toFloat()
        val y = h - 80f
        val x0 = 20f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xAAFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f
        }
        canvas.drawLine(x0, y, x0 + barPx, y, paint)
        canvas.drawLine(x0, y - 5f, x0, y + 5f, paint)
        canvas.drawLine(x0 + barPx, y - 5f, x0 + barPx, y + 5f, paint)
        val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xAAFFFFFF.toInt(); textSize = 20f; textAlign = Paint.Align.CENTER
        }
        canvas.drawText("100m", x0 + barPx / 2f, y - 10f, textP)
    }
}
