/*
 * HudView.kt - v15.0 自研 Canvas 2D 地图渲染引擎
 *
 * 完全移除 MapLibre，回归自研渲染：
 * - Overpass API 矢量路网 + 透视投影（45° 倾斜）
 * - 道路按类型分层着色（高速红/主干橙/支路绿/服务灰）
 * - 车辆标记（蓝色 chevron 箭头）
 * - 导航路线（深蓝轮廓 + 亮蓝路线）
 * - 速度仪表（速度制颜色渐变）
 * - HUD 镜像（垂直翻转，挡风玻璃投影）
 * - OSM 归属标注
 */

package com.hud.navi

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

class HudView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // === 车辆状态 ===
    var vehicleLat: Double = 0.0
    var vehicleLng: Double = 0.0
    var vehicleBearing: Float = 0f
    var vehicleSpeed: Float = 0f     // km/h

    // === HUD 镜像 ===
    var mirrorEnabled: Boolean = true

    // === 地图中心 ===
    var mapCenterLat: Double = 0.0
    var mapCenterLng: Double = 0.0

    // === 手动缩放偏移（语音控制） ===
    var zoomOffset: Float = 0f

    // === 路网数据 ===
    var roadSegments: List<RoadFetcher.RoadSegment> = emptyList()
        private set
    var hasRoads: Boolean = false
        private set

    // === 导航路线 ===
    var navigationRoute: List<Pair<Double, Double>> = emptyList()
        set(value) {
            field = value
        }
    var navigationDestination: String? = null

    // === 道路吸附 ===
    var snappedLat: Double = 0.0
    var snappedLng: Double = 0.0
    var isSnapped: Boolean = false

    // === 渲染参数 ===
    // 45° 透视投影参数
    private val PITCH_DEG = 45.0
    private val pitchRad = Math.toRadians(PITCH_DEG)
    private val cosPitch = cos(pitchRad)
    private val sinPitch = sin(pitchRad)

    // 基准米/像素比（zoom=19 时约 0.3m/px）
    private fun metersPerPixel(): Double {
        val baseZoom = 19.0 + zoomOffset
        return 156543.03392 * cos(Math.toRadians(vehicleLat)) / (1 shl baseZoom.toInt())
    }

    // === 速度 → 颜色插值 ===
    private fun getSpeedColor(speedKmh: Float): Int {
        val t = (speedKmh / 120f).coerceIn(0f, 1f)
        return when {
            t <= 0.5f -> {
                val r = (t / 0.5f)
                Color.rgb((r * 255).toInt(), 255, 0)
            }
            else -> {
                val r = ((t - 0.5f) / 0.5f)
                Color.rgb(255, (255 * (1f - r)).toInt(), 0)
            }
        }
    }

    // === 速度制动态缩放 ===
    fun getDynamicZoom(speedKmh: Float): Float {
        val clamped = speedKmh.coerceIn(0f, 150f)
        val base = 19.5f - (clamped / 150f) * 3f
        return (base + zoomOffset).coerceIn(14f, 22f)
    }

    fun setRoads(segments: List<RoadFetcher.RoadSegment>, centerLat: Double, centerLng: Double) {
        roadSegments = segments
        hasRoads = segments.isNotEmpty()
        mapCenterLat = centerLat
        mapCenterLng = centerLng
    }

    // === 画笔 ===
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
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val roadOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#1A1A1A")
    }
    private val routeOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#003366")
        strokeWidth = 12f
    }
    private val routeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#00BFFF")
        strokeWidth = 6f
    }
    private val vehiclePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val attribPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44FFFFFF")
        textSize = 22f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val waitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8844"); textSize = 36f
        textAlign = Paint.Align.CENTER
    }

    init {
        setBackgroundColor(Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        if (vehicleLat == 0.0) {
            canvas.drawText("等待 GPS...", w / 2f, h / 2f, waitPaint)
            return
        }

        // 1. 画路网
        drawRoads(canvas, w, h)

        // 2. 画导航路线
        drawNavigationRoute(canvas, w, h)

        // 3. 画车辆标记
        drawVehicleMarker(canvas, w, h)

        // 4. 画速度仪表
        drawSpeedometer(canvas, w, h)

        // 5. OSM 归属标注
        canvas.drawText("© OpenStreetMap contributors", w / 2f, h - 8f, attribPaint)
    }

    // === 经纬度 → 屏幕坐标（透视投影） ===
    // 以车辆位置为中心，heading 方向朝上，45° 俯视角
    private fun projectToScreen(lat: Double, lng: Double, w: Float, h: Float): FloatArray {
        val mpp = metersPerPixel()
        val cosBearing = cos(Math.toRadians(vehicleBearing.toDouble()))
        val sinBearing = sin(Math.toRadians(vehicleBearing.toDouble()))

        // 经纬度差 → 米（以车辆为中心）
        val dLatM = (lat - vehicleLat) * 111_320.0
        val dLngM = (lng - vehicleLng) * 111_320.0 * cos(Math.toRadians(vehicleLat))

        // 旋转（heading 朝上）：把世界坐标旋转到车辆坐标系
        // 车辆前方 = 屏幕上方
        val rotatedX = dLatM * sinBearing + dLngM * cosBearing   // 左右
        val rotatedY = dLatM * cosBearing - dLngM * sinBearing   // 前后（正=前方）

        // 透视投影：45° 倾斜
        // 远处的物体（rotatedY 大 = 前方）在屏幕上更高、更小
        val depth = rotatedY * cosPitch + 3.0 * sinPitch  // 3m 眼高
        val scale = if (depth > 0.1) (3.0 / depth) else 0.0

        val screenX = w / 2f + (rotatedX * scale / mpp).toFloat()
        // 车辆位置在屏幕 70% 高度处（留出前方视野）
        val screenY = h * 0.70f - (rotatedY * sinPitch * scale / mpp).toFloat()

        return floatArrayOf(screenX, screenY, scale.toFloat())
    }

    // === 画路网 ===
    private fun drawRoads(canvas: Canvas, w: Float, h: Float) {
        if (!hasRoads) return

        for (seg in roadSegments) {
            val pts = seg.points
            if (pts.size < 2) continue

            // 道路颜色
            val (fillColor, outlineW, fillW) = getRoadStyle(seg.highwayType)

            // 先画轮廓（深色描边）
            roadOutlinePaint.strokeWidth = outlineW
            roadOutlinePaint.color = Color.parseColor("#1A1A1A")
            drawPolyline(canvas, pts, w, h, roadOutlinePaint)

            // 再画填充（道路颜色）
            roadPaint.strokeWidth = fillW
            roadPaint.color = fillColor
            drawPolyline(canvas, pts, w, h, roadPaint)
        }
    }

    private fun getRoadStyle(type: String): Triple<Int, Float, Float> {
        return when (type) {
            "motorway", "motorway_link" -> Triple(Color.parseColor("#E53935"), 10f, 7f)
            "trunk", "trunk_link", "primary", "primary_link" -> Triple(Color.parseColor("#FF8F00"), 9f, 6f)
            "secondary", "secondary_link" -> Triple(Color.parseColor("#FFB300"), 8f, 5f)
            "tertiary", "tertiary_link" -> Triple(Color.parseColor("#FFCA28"), 7f, 4.5f)
            "residential", "living_street" -> Triple(Color.parseColor("#78909C"), 6f, 4f)
            "service" -> Triple(Color.parseColor("#546E7A"), 5f, 3f)
            "unclassified" -> Triple(Color.parseColor("#90A4AE"), 5f, 3.5f)
            else -> Triple(Color.parseColor("#607D8B"), 4f, 2.5f)
        }
    }

    private fun drawPolyline(canvas: Canvas, points: List<Pair<Double, Double>>, w: Float, h: Float, paint: Paint) {
        if (points.size < 2) return
        val path = Path()
        val first = projectToScreen(points[0].first, points[0].second, w, h)
        path.moveTo(first[0], first[1])

        for (i in 1 until points.size) {
            val p = projectToScreen(points[i].first, points[i].second, w, h)
            // 裁剪：跳过完全在屏幕外的线段（宽松裁剪，留 200px 余量）
            if (p[0] < -200f || p[0] > w + 200f || p[1] < -200f || p[1] > h + 200f) {
                // 如果前一个点在屏内，先 lineTo 到当前点再 moveTo
                val prev = projectToScreen(points[i-1].first, points[i-1].second, w, h)
                if (prev[0] in -200f..(w+200f) && prev[1] in -200f..(h+200f)) {
                    path.lineTo(p[0], p[1])
                }
                path.moveTo(p[0], p[1])
            } else {
                path.lineTo(p[0], p[1])
            }
        }

        canvas.drawPath(path, paint)
    }

    // === 画导航路线 ===
    private fun drawNavigationRoute(canvas: Canvas, w: Float, h: Float) {
        if (navigationRoute.size < 2) return
        val pts = navigationRoute
        val path = Path()
        val first = projectToScreen(pts[0].first, pts[0].second, w, h)
        path.moveTo(first[0], first[1])
        for (i in 1 until pts.size) {
            val p = projectToScreen(pts[i].first, pts[i].second, w, h)
            path.lineTo(p[0], p[1])
        }
        canvas.drawPath(path, routeOutlinePaint)
        canvas.drawPath(path, routeLinePaint)
    }

    // === 画车辆标记 ===
    private fun drawVehicleMarker(canvas: Canvas, w: Float, h: Float) {
        // 车辆始终在屏幕中心偏下（70% 高度）
        val cx = w / 2f
        val cy = h * 0.70f
        val size = 32f

        // 白色外圈
        vehiclePaint.color = Color.WHITE
        vehiclePaint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, size * 1.3f, vehiclePaint)

        // 蓝色内圆
        vehiclePaint.color = Color.parseColor("#2196F3")
        canvas.drawCircle(cx, cy, size * 1.1f, vehiclePaint)

        // 白色 chevron 箭头（朝上 = 车辆前方方向）
        vehiclePaint.color = Color.WHITE
        vehiclePaint.style = Paint.Style.FILL

        val tipY = cy - size * 0.7f
        val shoulderY = cy + size * 0.55f
        val indentY = cy + size * 0.05f
        val halfW = size * 0.5f

        val chevron = Path().apply {
            moveTo(cx, tipY)
            lineTo(cx - halfW, shoulderY)
            lineTo(cx, indentY)
            lineTo(cx + halfW, shoulderY)
            close()
        }
        canvas.drawPath(chevron, vehiclePaint)
    }

    // === 画速度仪表 ===
    private fun drawSpeedometer(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val speedY = 120f
        val speedStr = vehicleSpeed.toInt().toString()

        speedNumPaint.color = getSpeedColor(vehicleSpeed)
        canvas.drawText(speedStr, cx, speedY, speedNumPaint)
        canvas.drawText("km/h", cx, speedY + 40f, speedUnitPaint)
    }
}
