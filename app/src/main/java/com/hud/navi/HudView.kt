package com.hud.navi

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

/**
 * HUD 导航视图：45 度倾斜透视投影渲染路网 + 方向箭头
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
    var statusText: String = "等待 GPS 定位..."

    // === 相机参数 ===
    private val pitchDeg = 45.0
    private val pitchRad = Math.toRadians(pitchDeg)
    private val cosP = cos(pitchRad).toFloat()
    private val sinP = sin(pitchRad).toFloat()
    private val cameraHeight = 3.0   // 相机高于路面 3m

    // === 画笔 ===
    private val roadPaint = Paint().apply {
        isAntiAlias = true; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val arrowPaint = Paint().apply {
        isAntiAlias = true; style = Paint.Style.FILL
        color = Color.parseColor("#00FF88")
    }
    private val arrowOutlinePaint = Paint().apply {
        isAntiAlias = true; style = Paint.Style.STROKE
        strokeWidth = 3f; color = Color.parseColor("#004422")
    }
    private val infoPaint = Paint().apply {
        color = Color.parseColor("#AACCEE"); textSize = 36f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); isAntiAlias = true
    }
    private val speedPaint = Paint().apply {
        color = Color.WHITE; textSize = 72f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        isAntiAlias = true; isFakeBoldText = true
    }
    private val unitPaint = Paint().apply {
        color = Color.parseColor("#88AACC"); textSize = 28f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); isAntiAlias = true
    }
    private val statusPaint = Paint().apply {
        color = Color.parseColor("#FF8844"); textSize = 32f; isAntiAlias = true
    }
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#0A1520"); strokeWidth = 1f
        style = Paint.Style.STROKE; isAntiAlias = true
    }
    private val vehicleDotPaint = Paint().apply {
        color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true
    }
    private val vehicleRingPaint = Paint().apply {
        color = Color.parseColor("#00FF88"); style = Paint.Style.STROKE
        strokeWidth = 2f; isAntiAlias = true
    }
    private val compassPaint = Paint().apply {
        color = Color.parseColor("#446688"); textSize = 24f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); isAntiAlias = true
        textAlign = Paint.Align.CENTER
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
        "motorway" to 12f, "motorway_link" to 7f,
        "trunk" to 10f, "trunk_link" to 5f,
        "primary" to 8f, "primary_link" to 5f,
        "secondary" to 7f, "secondary_link" to 4f,
        "tertiary" to 5f, "tertiary_link" to 3f,
        "residential" to 4f, "service" to 3f,
        "unclassified" to 3f, "living_street" to 3f,
        "road" to 4f
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val vpx = w / 2
        val vpy = h * 0.30f  // 灭点在屏幕 30% 处

        canvas.drawColor(Color.BLACK)

        if (vehicleLat != 0.0) {
            drawGrid(canvas, w, h, vpx, vpy)
        }

        if (roads.isNotEmpty() && vehicleLat != 0.0) {
            drawRoads(canvas, w, h, vpx, vpy)
        }

        drawVehicleMarker(canvas, w, h, vpx, vpy)
        drawArrow(canvas, w, h)
        drawHudInfo(canvas, w, h)
    }

    /**
     * 透视投影（45° 俯角，focal=0.5w）
     * 返回 [screenX, screenY, distance] 或 null
     */
    private fun project(fwd: Float, right: Float, w: Float, h: Float, vpx: Float, vpy: Float): FloatArray? {
        val focal = w * 0.5f
        val zCam = fwd * cosP
        val yCam = fwd * sinP - cameraHeight.toFloat()

        if (abs(zCam) < 1.5f) return null

        val dist = sqrt(fwd * fwd + right * right)
        if (dist > 600f) return null

        val scale = focal / zCam
        val sx = vpx + right * scale
        val sy = vpy + yCam * scale

        if (sx < -w || sx > 2 * w || sy < -h || sy > 2 * h) return null
        return floatArrayOf(sx, sy, dist)
    }

    /**
     * GPS → 车辆前方/右侧坐标（米）
     */
    private fun gpsToVehicle(lat: Double, lng: Double): FloatArray {
        val dLat = lat - vehicleLat
        val dLng = lng - vehicleLng
        val cosLat = cos(Math.toRadians(vehicleLat)).toFloat()
        val east = (dLng * Math.toRadians(1.0) * 6371000.0 * cosLat).toFloat()
        val north = (dLat * Math.toRadians(1.0) * 6371000.0).toFloat()
        val brg = Math.toRadians(vehicleBearing.toDouble()).toFloat()
        val fwd = north * cos(brg) + east * sin(brg)
        val right = east * cos(brg) - north * sin(brg)
        return floatArrayOf(fwd, right)
    }

    /**
     * 地面参考网格（同心距离圆）
     */
    private fun drawGrid(canvas: Canvas, w: Float, h: Float, vpx: Float, vpy: Float) {
        val distances = floatArrayOf(100f, 200f, 400f, 600f)
        for (d in distances) {
            val focal = w * 0.5f
            val zCam = d * cosP
            if (abs(zCam) < 2f) continue
            val scale = focal / zCam
            val yCam = d * sinP - cameraHeight.toFloat()
            val sy = vpy + yCam * scale
            // 画一条极淡的水平线表示该距离
            gridPaint.alpha = (30 * (1f - d / 800f)).toInt().coerceIn(5, 30)
            canvas.drawLine(0f, sy, w, sy, gridPaint)
        }
    }

    /**
     * 渲染路网
     */
    private fun drawRoads(canvas: Canvas, w: Float, h: Float, vpx: Float, vpy: Float) {
        val sortedRoads = roads.sortedByDescending { seg ->
            val mid = gpsToVehicle((seg.lat1 + seg.lat2) / 2, (seg.lng1 + seg.lng2) / 2)
            sqrt(mid[0] * mid[0] + mid[1] * mid[1])
        }

        for (seg in sortedRoads) {
            val p1 = gpsToVehicle(seg.lat1, seg.lng1)
            val p2 = gpsToVehicle(seg.lat2, seg.lng2)

            val sp1 = project(p1[0], p1[1], w, h, vpx, vpy) ?: continue
            val sp2 = project(p2[0], p2[1], w, h, vpx, vpy) ?: continue

            val baseColor = roadColors[seg.highwayType] ?: roadColors["road"]!!
            val baseWidth = roadWidths[seg.highwayType] ?: 4f

            val avgDist = (sp1[2] + sp2[2]) / 2f
            // 近粗远细：100m 内全粗，600m 衰减到 30%
            val widthScale = (1f - (avgDist / 800f)).coerceIn(0.3f, 1f)
            val alphaScale = (1f - (avgDist / 700f)).coerceIn(0.25f, 1f)

            roadPaint.color = baseColor
            roadPaint.alpha = (alphaScale * 255).toInt().coerceIn(60, 255)
            roadPaint.strokeWidth = baseWidth * widthScale

            canvas.drawLine(sp1[0], sp1[1], sp2[0], sp2[1], roadPaint)
        }
    }

    /**
     * 车辆位置标记（在屏幕下方偏中）
     */
    private fun drawVehicleMarker(canvas: Canvas, w: Float, h: Float, vpx: Float, vpy: Float) {
        if (vehicleLat == 0.0) return
        // 车辆自身坐标投影到屏幕
        val sp = project(5f, 0f, w, h, vpx, vpy) ?: return  // 前方5米处作为标记点
        val vx = sp[0]
        val vy = sp[1]

        // 白色圆点
        canvas.drawCircle(vx, vy, 8f, vehicleDotPaint)
        // 绿色外圈
        canvas.drawCircle(vx, vy, 16f, vehicleRingPaint)
        // 方向指示线（朝前方）
        canvas.drawLine(vx, vy, vx, vy - 40f, vehicleRingPaint)
    }

    /**
     * 方向箭头
     */
    private fun drawArrow(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2
        val cy = h * 0.78f
        val arrowH = 70f
        val arrowW = 36f
        val tailW = 14f
        val tailH = 25f

        val path = Path()
        path.moveTo(cx, cy - arrowH / 2)
        path.lineTo(cx + arrowW / 2, cy + arrowH / 4)
        path.lineTo(cx + tailW / 2, cy + arrowH / 4)
        path.lineTo(cx + tailW / 2, cy + arrowH / 2 + tailH)
        path.lineTo(cx - tailW / 2, cy + arrowH / 2 + tailH)
        path.lineTo(cx - tailW / 2, cy + arrowH / 4)
        path.lineTo(cx - arrowW / 2, cy + arrowH / 4)
        path.close()

        val glowPaint = Paint(arrowPaint).apply {
            maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.OUTER)
        }
        canvas.drawPath(path, glowPaint)
        canvas.drawPath(path, arrowPaint)
        canvas.drawPath(path, arrowOutlinePaint)
    }

    /**
     * HUD 信息
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
            val displayStatus = if (roadCount > 0) "路网: $roadCount 段" else statusText
            val paintToUse = if (statusText.contains("失败") || statusText.contains("错误")) statusPaint else infoPaint
            canvas.drawText(displayStatus, 40f, 60f, paintToUse)
            canvas.drawText(String.format("位置: %.4f, %.4f", vehicleLat, vehicleLng), 40f, 100f, infoPaint)
            canvas.drawText(String.format("航向: %.0f°", vehicleBearing), 40f, 140f, infoPaint)
        }

        // 罗盘方位（右上角）
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val bearingIdx = (((vehicleBearing + 22.5f) % 360f) / 45f).toInt() % 8
        canvas.drawText(dirs[bearingIdx], w - 60f, 50f, compassPaint)

        // 版本号
        val titlePaint = Paint(infoPaint).apply {
            textAlign = Paint.Align.RIGHT; textSize = 24f; color = Color.parseColor("#223344")
        }
        canvas.drawText("HUD NAVI v1.4", w - 30f, 90f, titlePaint)
    }
}
