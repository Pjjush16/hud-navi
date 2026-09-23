package com.hud.navi

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

/**
 * HUD 导航视图：45 度倾斜透视投影渲染路网 + 方向箭头
 *
 * 相机模型：
 * - 相机位于车辆上方 h 米，向下倾斜 45°
 * - 将前方路面投影到屏幕上，远处汇聚到灭点
 * - 近处道路在屏幕下方（大、清晰），远处在上方（小、模糊）
 */
class HudView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // === 车辆状态 ===
    var vehicleLat: Double = 0.0
    var vehicleLng: Double = 0.0
    var vehicleBearing: Float = 0f   // 度，0=北，90=东
    var vehicleSpeed: Float = 0f     // km/h

    // === 路网数据 ===
    var roads: List<RoadSegment> = emptyList()
    var statusText: String = "等待 GPS 定位..."

    // === 相机参数 ===
    private val pitchDeg = 45.0
    private val pitchRad = Math.toRadians(pitchDeg)
    private val cameraHeight = 2.5   // 相机高于路面（米）

    // === 画笔 ===
    private val roadPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val arrowPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.parseColor("#00FF88")
    }
    private val arrowOutlinePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#004422")
    }
    private val horizonPaint = Paint().apply {
        color = Color.parseColor("#1A3355")
        strokeWidth = 1f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val infoPaint = Paint().apply {
        color = Color.parseColor("#AACCEE")
        textSize = 36f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        isAntiAlias = true
    }
    private val speedPaint = Paint().apply {
        color = Color.WHITE
        textSize = 72f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        isAntiAlias = true
        isFakeBoldText = true
    }
    private val unitPaint = Paint().apply {
        color = Color.parseColor("#88AACC")
        textSize = 28f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        isAntiAlias = true
    }
    private val statusPaint = Paint().apply {
        color = Color.parseColor("#FF8844")
        textSize = 32f
        isAntiAlias = true
    }

    // === 道路颜色映射 ===
    private val roadColors = mapOf(
        "motorway" to Color.parseColor("#FF4444"),
        "motorway_link" to Color.parseColor("#FF4444"),
        "trunk" to Color.parseColor("#FF8833"),
        "trunk_link" to Color.parseColor("#FF8833"),
        "primary" to Color.parseColor("#44BBFF"),
        "primary_link" to Color.parseColor("#44BBFF"),
        "secondary" to Color.parseColor("#44DDAA"),
        "secondary_link" to Color.parseColor("#44DDAA"),
        "tertiary" to Color.parseColor("#66CC88"),
        "tertiary_link" to Color.parseColor("#66CC88"),
        "residential" to Color.parseColor("#5588AA"),
        "service" to Color.parseColor("#445566"),
        "unclassified" to Color.parseColor("#557799"),
        "living_street" to Color.parseColor("#557799"),
        "road" to Color.parseColor("#5588AA")
    )

    private val roadWidths = mapOf(
        "motorway" to 8f, "motorway_link" to 5f,
        "trunk" to 7f, "trunk_link" to 4f,
        "primary" to 6f, "primary_link" to 3.5f,
        "secondary" to 5f, "secondary_link" to 3f,
        "tertiary" to 4f, "tertiary_link" to 2.5f,
        "residential" to 3f, "service" to 2f,
        "unclassified" to 2.5f, "living_street" to 2.5f,
        "road" to 3f
    )

    // === 坐标缓存 ===
    private val pointCache = HashMap<Long, FloatArray>()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 纯黑背景
        canvas.drawColor(Color.BLACK)

        // 灭点位置（屏幕中上方）
        val vpx = w / 2
        val vpy = h * 0.22f

        // 画灭点参考线（极淡的水平线）
        canvas.drawLine(0f, vpy, w, vpy, horizonPaint)

        // === 渲染路网 ===
        if (roads.isNotEmpty() && vehicleLat != 0.0) {
            drawRoads(canvas, w, h, vpx, vpy)
        }

        // === 画方向箭头（固定在屏幕中央偏下） ===
        drawArrow(canvas, w, h)

        // === HUD 信息 ===
        drawHudInfo(canvas, w, h)
    }

    /**
     * 透视投影：将路面上的 3D 点投影到 2D 屏幕
     *
     * @param fwd   前方距离（米，正=前方）
     * @param right 右侧距离（米，正=右方）
     * @return [screenX, screenY]，null 表示在相机后面不可见
     */
    private fun project(fwd: Float, right: Float, w: Float, h: Float, vpx: Float, vpy: Float): FloatArray? {
        // 焦距（像素）：0.2 倍宽度，确保 1km 内道路在屏幕可见范围
        val focal = w * 0.2f

        // 相机空间坐标（45° 俯角）
        val zCam = fwd * cos(pitchRad).toFloat()
        val yCam = fwd * sin(pitchRad).toFloat() - cameraHeight.toFloat()

        // 防止除以零：zCam 接近 0 时裁剪（约 ±2.8m 内）
        if (kotlin.math.abs(zCam) < 2f) return null

        // 距离过远不渲染
        val dist = kotlin.math.sqrt(fwd * fwd + right * right)
        if (dist > 1200f) return null

        // 透视投影
        val scale = focal / zCam
        val sx = vpx + right * scale
        val sy = vpy + yCam * scale

        // 宽松裁剪
        if (sx < -3 * w || sx > 4 * w || sy < -3 * h || sy > 4 * h) return null

        return floatArrayOf(sx, sy, fwd)
    }

    /**
     * GPS → 车辆相对坐标（前方/右侧，单位米）
     */
    private fun gpsToVehicle(lat: Double, lng: Double): FloatArray {
        val dLat = lat - vehicleLat
        val dLng = lng - vehicleLng
        val cosLat = cos(Math.toRadians(vehicleLat)).toFloat()

        // 东向/北向（米）
        val east = (dLng * Math.toRadians(1.0) * 6371000.0 * cosLat).toFloat()
        val north = (dLat * Math.toRadians(1.0) * 6371000.0).toFloat()

        // 按航向旋转到车辆坐标系
        val brg = Math.toRadians(vehicleBearing.toDouble()).toFloat()
        val fwd = north * cos(brg) + east * sin(brg)
        val right = east * cos(brg) - north * sin(brg)

        return floatArrayOf(fwd, right)
    }

    /**
     * 渲染路网（带深度衰减）
     */
    private fun drawRoads(canvas: Canvas, w: Float, h: Float, vpx: Float, vpy: Float) {
        // 按到车辆的绝对距离排序：远的先画（被近的覆盖）
        val sortedRoads = roads.sortedByDescending { seg ->
            val mid = gpsToVehicle((seg.lat1 + seg.lat2) / 2, (seg.lng1 + seg.lng2) / 2)
            kotlin.math.sqrt(mid[0] * mid[0] + mid[1] * mid[1])  // 绝对距离
        }

        for (seg in sortedRoads) {
            val p1 = gpsToVehicle(seg.lat1, seg.lng1)
            val p2 = gpsToVehicle(seg.lat2, seg.lng2)

            val sp1 = project(p1[0], p1[1], w, h, vpx, vpy) ?: continue
            val sp2 = project(p2[0], p2[1], w, h, vpx, vpy) ?: continue

            // 道路颜色
            val baseColor = roadColors[seg.highwayType] ?: roadColors["road"]!!
            val baseWidth = roadWidths[seg.highwayType] ?: 3f

            // 深度衰减：用绝对距离（不管前后），远处更细更暗
            val absDist1 = kotlin.math.sqrt(p1[0] * p1[0] + p1[1] * p1[1])
            val absDist2 = kotlin.math.sqrt(p2[0] * p2[0] + p2[1] * p2[1])
            val avgDist = (absDist1 + absDist2) / 2f
            val distFade = (1f - (avgDist / 1000f)).coerceIn(0.15f, 1f)
            val widthFade = (1f - (avgDist / 1200f)).coerceIn(0.2f, 1f)

            // 设置画笔
            val alpha = (distFade * 255).toInt().coerceIn(40, 255)
            roadPaint.color = baseColor
            roadPaint.alpha = alpha
            roadPaint.strokeWidth = baseWidth * widthFade

            // 画线段
            canvas.drawLine(sp1[0], sp1[1], sp2[0], sp2[1], roadPaint)
        }
    }

    /**
     * 画方向箭头（屏幕中央偏下，始终朝上表示"前方"）
     */
    private fun drawArrow(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2
        val cy = h * 0.72f  // 箭头中心在屏幕 72% 高度处
        val arrowH = 80f     // 箭头高度
        val arrowW = 40f     // 箭头宽度
        val tailW = 16f      // 尾部宽度
        val tailH = 30f      // 尾部高度

        val path = Path()
        // 箭头尖端
        path.moveTo(cx, cy - arrowH / 2)
        // 右翼
        path.lineTo(cx + arrowW / 2, cy + arrowH / 4)
        // 右尾
        path.lineTo(cx + tailW / 2, cy + arrowH / 4)
        path.lineTo(cx + tailW / 2, cy + arrowH / 2 + tailH)
        // 左尾
        path.lineTo(cx - tailW / 2, cy + arrowH / 2 + tailH)
        path.lineTo(cx - tailW / 2, cy + arrowH / 4)
        // 左翼
        path.lineTo(cx - arrowW / 2, cy + arrowH / 4)
        path.close()

        // 发光效果
        val glowPaint = Paint(arrowPaint).apply {
            maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.OUTER)
        }
        canvas.drawPath(path, glowPaint)

        // 实心箭头
        canvas.drawPath(path, arrowPaint)
        canvas.drawPath(path, arrowOutlinePaint)
    }

    /**
     * HUD 信息显示：速度、道路数量、状态
     */
    private fun drawHudInfo(canvas: Canvas, w: Float, h: Float) {
        // 速度（左下角）
        val speedKmh = vehicleSpeed.toInt()
        canvas.drawText(speedKmh.toString(), 40f, h - 40f, speedPaint)
        canvas.drawText("km/h", 40f + speedPaint.measureText(speedKmh.toString()) + 10f, h - 50f, unitPaint)

        // 状态信息（左上角）
        val roadCount = roads.size
        if (vehicleLat == 0.0) {
            canvas.drawText("等待 GPS 定位...", 40f, 60f, statusPaint)
        } else {
            // 状态文本（可能含加载/错误信息）
            val displayStatus = if (roadCount > 0) "路网: $roadCount 段" else statusText
            val paintToUse = if (statusText.contains("失败") || statusText.contains("错误")) statusPaint else infoPaint
            canvas.drawText(displayStatus, 40f, 60f, paintToUse)
            canvas.drawText(String.format("位置: %.4f, %.4f", vehicleLat, vehicleLng), 40f, 100f, infoPaint)
            canvas.drawText(String.format("航向: %.0f°", vehicleBearing), 40f, 140f, infoPaint)
        }

        // 标题（右上角）
        val titlePaint = Paint(infoPaint).apply {
            textAlign = Paint.Align.RIGHT
            textSize = 28f
            color = Color.parseColor("#335577")
        }
        canvas.drawText("HUD NAVI v1.0", w - 30f, 50f, titlePaint)
    }
}
