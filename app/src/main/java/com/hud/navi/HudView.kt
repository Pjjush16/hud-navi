package com.hud.navi

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

/**
 * HUD 矢量路网渲染视图
 * 纯黑背景 + 矢量路网 + 45° 透视（Matrix 梯形变换）
 * 无任何瓦片底图，专为挡风玻璃 HUD 设计
 */
class HudView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // === 车辆状态 ===
    var vehicleLat: Double = 0.0
    var vehicleLng: Double = 0.0
    var vehicleBearing: Float = 0f
    var vehicleSpeed: Float = 0f

    // === 路网数据 ===
    var roads: List<RoadSegment> = emptyList()
    var statusText: String = "等待 GPS..."

    // === 画笔 ===
    private val roadPaint = Paint().apply {
        isAntiAlias = true; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val arrowPaint = Paint().apply {
        isAntiAlias = true; style = Paint.Style.FILL
        color = Color.parseColor("#00FF88")
    }
    private val arrowGlowPaint = Paint().apply {
        isAntiAlias = true; style = Paint.Style.FILL
        color = Color.parseColor("#00FF88")
        maskFilter = BlurMaskFilter(15f, BlurMaskFilter.Blur.OUTER)
    }
    private val arrowOutline = Paint().apply {
        isAntiAlias = true; style = Paint.Style.STROKE
        strokeWidth = 2f; color = Color.parseColor("#003322")
    }
    private val infoPaint = Paint().apply {
        color = Color.parseColor("#AACCEE"); textSize = 34f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        isAntiAlias = true
    }
    private val speedPaint = Paint().apply {
        color = Color.WHITE; textSize = 72f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        isAntiAlias = true; isFakeBoldText = true
    }
    private val unitPaint = Paint().apply {
        color = Color.parseColor("#667788"); textSize = 24f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        isAntiAlias = true
    }
    private val statusPaint = Paint().apply {
        color = Color.parseColor("#FF8844"); textSize = 30f; isAntiAlias = true
    }
    private val dotPaint = Paint().apply {
        color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true
    }
    private val ringPaint = Paint().apply {
        color = Color.parseColor("#00FF88"); style = Paint.Style.STROKE
        strokeWidth = 2.5f; isAntiAlias = true
    }
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#112233"); strokeWidth = 1f
        style = Paint.Style.STROKE; isAntiAlias = true
    }
    // 飞镖箭头 — 白色填充
    private val dartFillPaint = Paint().apply {
        color = Color.WHITE; style = Paint.Style.FILL
        isAntiAlias = true
    }
    // 飞镖箭头 — 白色边线（轮廓增强）
    private val dartStrokePaint = Paint().apply {
        color = Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = 2f; isAntiAlias = true
        strokeJoin = Paint.Join.ROUND
    }

    // 道路宽度（极简HUD风格，道路非常粗，模拟真实道路宽度）
    private val roadWidths = mapOf(
        "motorway" to 200f, "motorway_link" to 160f,
        "trunk" to 180f, "trunk_link" to 140f,
        "primary" to 150f, "primary_link" to 120f,
        "secondary" to 130f, "secondary_link" to 100f,
        "tertiary" to 110f, "tertiary_link" to 80f,
        "residential" to 90f, "service" to 70f,
        "unclassified" to 90f, "living_street" to 90f,
        "road" to 90f
    )
    // 透视参数（15° 从地面 / 75° 从正上方）
    // 摄像头几乎平视前方，像真车挡风玻璃 HUD
    private val maxRenderDist = 500f  // 最大渲染距离 500m
    private val perspectiveNear = 1.0f   // 近处缩放
    private val perspectiveFar = 0.12f   // 远处缩放（15° 视角，远处强烈压缩）
    private val cameraAngleRad = Math.toRadians(15.0).toFloat()  // 摄像头离地 15°

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawColor(Color.BLACK)

        if (vehicleLat != 0.0 && roads.isNotEmpty()) {
            drawRoadNetwork(canvas, w, h)
        }

        drawVehicleMarker(canvas, w, h)
        drawHudInfo(canvas, w, h)
    }

    /**
     * 渲染矢量路网（15° 离地视角 / 75° 离正上方）
     *
     * 摄像头近乎平视前方，像真车挡风玻璃 HUD。
     * 使用 1/d 透视除法：近处道路极大，远处强烈压缩汇聚到灭点。
     * 灭点（地平线）在屏幕 35% 处。
     */
    private fun drawRoadNetwork(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2
        val cy = h * 0.92f  // 车辆在屏幕 92% 处（更靠底部，拉近摄像头）
        val metersToPixels = w / 50f  // 250m = 屏幕宽度（拉近，原来 400m）

        val bearingRad = Math.toRadians(vehicleBearing.toDouble()).toFloat()

        // 保存画布状态
        canvas.save()

        // 按距离排序（远的先画，近的覆盖在上面）
        val sortedRoads = roads.sortedByDescending { seg ->
            val midFwd = (seg.lat1 + seg.lat2) / 2 - vehicleLat
            val midRight = (seg.lng1 + seg.lng2) / 2 - vehicleLng
            sqrt(midFwd * midFwd + midRight * midRight)
        }

        for (seg in sortedRoads) {
            // GPS → 局部米坐标（北=east 右, fwd 上）
            val cosLat = cos(Math.toRadians(vehicleLat)).toFloat()
            val radDeg = Math.toRadians(1.0).toFloat() * 6371000f

            val dx1 = ((seg.lng1 - vehicleLng) * radDeg * cosLat).toFloat()
            val dy1 = ((seg.lat1 - vehicleLat) * radDeg).toFloat()
            val dx2 = ((seg.lng2 - vehicleLng) * radDeg * cosLat).toFloat()
            val dy2 = ((seg.lat2 - vehicleLat) * radDeg).toFloat()

            // 旋转（车辆航向朝上）
            val cosB = cos(bearingRad); val sinB = sin(bearingRad)
            val rx1 = dx1 * sinB + dy1 * cosB
            val ry1 = dx1 * cosB - dy1 * sinB  // 前方为正
            val rx2 = dx2 * sinB + dy2 * cosB
            val ry2 = dx2 * cosB - dy2 * sinB

            // 距离裁剪
            val d1 = sqrt(rx1 * rx1 + ry1 * ry1)
            val d2 = sqrt(rx2 * rx2 + ry2 * ry2)
            if (d1 > maxRenderDist && d2 > maxRenderDist) continue

            // 透视变换：ry > 0 是前方，ry < 0 是后方
            // 将 ry 映射到屏幕 Y（前方=上方=小 Y）
            val (sx1, sy1) = projectPoint(rx1, ry1, cx, cy, metersToPixels, w, h) ?: continue
            val (sx2, sy2) = projectPoint(rx2, ry2, cx, cy, metersToPixels, w, h) ?: continue

            // 线宽（按道路类型）
            val baseW = roadWidths[seg.highwayType] ?: 8f
            val avgD = (d1 + d2) / 2f

            // 远处变细变暗（与 15° 透视匹配）
            val fade = (1f - avgD / (maxRenderDist * 1.2f)).coerceIn(0.15f, 1f)
            // 15° 低视角：近处道路很粗，远处急剧变细（模拟真实透视）
            val widthDepthScale = 8f  // 近距离衰减常数（越小近处越粗）
            val perspWidthScale = widthDepthScale / (avgD + widthDepthScale)
            val widthScale = perspWidthScale.coerceIn(0.05f, 1f)

            // 所有道路统一双线渲染：先画白色粗线（边线），再叠加黑色细线（填充）
            val outerW = baseW * 1.8f * widthScale  // 白色边线宽度
            val innerW = baseW * 0.55f * widthScale  // 黑色填充宽度

            // 第一层：白色边线
            roadPaint.color = Color.WHITE
            roadPaint.alpha = (fade * 220).toInt().coerceIn(50, 220)
            roadPaint.strokeWidth = outerW
            canvas.drawLine(sx1, sy1, sx2, sy2, roadPaint)

            // 第二层：黑色填充（叠加在白色上面，形成两侧白边+中间黑色）
            roadPaint.color = Color.BLACK
            roadPaint.alpha = 255
            roadPaint.strokeWidth = innerW
            canvas.drawLine(sx1, sy1, sx2, sy2, roadPaint)
        }

        canvas.restore()
    }

    /**
     * 透视投影：将局部坐标 (rx, ry) 映射到屏幕坐标
     *
     * 摄像头离地 15°（距正上方 75°），近乎平视前方。
     * ry > 0 = 前方（屏幕上方），ry < 0 = 后方（屏幕下方）
     * 使用 1/d 透视除法模拟真实透视：
     * - 近处极大、远处极小（强烈的近大远小）
     * - 灭点（地平线）在屏幕 35% 处
     */
    private fun projectPoint(rx: Float, ry: Float, cx: Float, cy: Float,
                              m2px: Float, w: Float, h: Float): Pair<Float, Float>? {
        val dist = sqrt(rx * rx + ry * ry)
        if (dist > maxRenderDist) return null

        // 灭点（地平线）位置
        val vanishingY = h * 0.35f
        // 从灭点到车辆的可用屏幕高度
        val usableH = cy - vanishingY

        // 前方距离（ry > 0 表示前方）
        val fwd = ry.coerceAtLeast(0.1f)

        // 透视除法：1/d 映射
        // depthScale=20 让 500m 落在灭点附近
        val depthScale = 20f
        val t = fwd / (fwd + depthScale)  // 0→0, ∞→1

        // 屏幕 Y：cy（近）→ vanishingY（远）
        val screenY = cy - t * usableH

        // 透视缩放：近处 1.0，远处急剧缩小
        val perspScale = depthScale / (fwd + depthScale)

        // 水平偏移（乘以透视缩放，远处压缩汇聚）
        val screenX = cx + rx * m2px * perspScale

        // 裁剪
        if (screenX < -w || screenX > 2 * w || screenY < -h * 0.5f || screenY > h * 1.5f) return null

        return Pair(screenX, screenY)
    }

    /**
     * 车辆位置标记 — 飞镖/纸飞机形箭头（屏幕底部中心）
     * 三角形头部 + 内凹尾部，白色填充，方向朝上（前方）
     */
    private fun drawVehicleMarker(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2
        val cy = h * 0.92f

        // 飞镖形状参数
        val size = 28f          // 整体大小
        val tipY = cy - size * 1.4f    // 箭头尖端（前方/上方）
        val shoulderY = cy + size * 0.3f  // 肩部（最宽处）
        val tailY = cy + size * 0.8f   // 尾部
        val indentY = cy               // 内凹点（尾部中间的凹陷）
        val halfW = size * 0.55f       // 半宽

        // 飞镖路径：尖端 → 左肩 → 左尾 → 内凹 → 右尾 → 右肩 → 尖端
        val dartPath = Path().apply {
            moveTo(cx, tipY)                          // 尖端
            lineTo(cx - halfW, shoulderY)             // 左肩
            lineTo(cx - halfW * 0.4f, tailY)          // 左尾
            lineTo(cx, indentY)                       // 内凹中点
            lineTo(cx + halfW * 0.4f, tailY)          // 右尾
            lineTo(cx + halfW, shoulderY)             // 右肩
            close()
        }

        // 白色填充
        canvas.drawPath(dartPath, dartFillPaint)

        // 细白边线（增加轮廓感）
        canvas.drawPath(dartPath, dartStrokePaint)
    }

    /**
     * HUD 信息覆盖层 — 极简模式：只显示速度
     */
    private fun drawHudInfo(canvas: Canvas, w: Float, h: Float) {
        if (vehicleLat == 0.0) {
            // 未定位时显示提示
            val paint = Paint(statusPaint).apply { textAlign = Paint.Align.CENTER }
            canvas.drawText("等待 GPS...", w / 2, h / 2, paint)
            return
        }

        // 速度数字（屏幕顶部居中，大字体）
        val speedStr = vehicleSpeed.toInt().toString()
        val speedX = w / 2
        val speedY = 100f  // 屏幕顶部
        val speedPaint = Paint().apply {
            color = Color.WHITE
            textSize = 80f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isAntiAlias = true
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val unitPaint = Paint().apply {
            color = Color.parseColor("#888888")
            textSize = 28f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(speedStr, speedX, speedY, speedPaint)
        canvas.drawText("km/h", speedX, speedY + 36f, unitPaint)
    }
}
