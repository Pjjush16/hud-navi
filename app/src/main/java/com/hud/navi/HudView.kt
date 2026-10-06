/*
 * HudView.kt - v14.0 MapLibre HUD 覆盖层
 *
 * 从 Canvas 全量渲染改为透明覆盖层：
 * - 去掉：路网绘制、路名标签、导航路线（改由 MapLibre 底图渲染）
 * - 保留：速度仪表、车辆标记、HUD 镜像、OsmAnd 归属标注
 *
 * MapLibre MapView 负责：矢量瓦片路网、45° 倾斜、3D 建筑、路线渲染
 * HudView 覆盖层负责：速度数字、车辆标记图标、HUD 信息叠加
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

    // === HUD 镜像（垂直翻转，用于挡风玻璃投影） ===
    var mirrorEnabled: Boolean = true

    // === 地图中心（用于 HUD 覆盖层定位参考） ===
    var mapCenterLat: Double = 0.0
    var mapCenterLng: Double = 0.0

    // === 手动缩放偏移（语音控制） ===
    var zoomOffset: Float = 0f

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

    init {
        // 透明背景，让 MapLibre 底图透过来
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 不画黑色背景（透明覆盖层）

        if (vehicleLat == 0.0) {
            val waitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF8844"); textSize = 36f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("等待 GPS...", w / 2f, h / 2f, waitPaint)
            return
        }

        // HUD 镜像：垂直翻转整个画面
        if (mirrorEnabled) {
            canvas.save()
            canvas.scale(1f, -1f, w / 2f, h / 2f)
        }

        // 只画 HUD 覆盖元素
        drawVehicleMarker(canvas, w, h)
        drawSpeedometer(canvas, w, h)

        if (mirrorEnabled) {
            canvas.restore()
        }

        // OSM 归属标注（ODbL 协议要求）
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
        canvas.drawText("© OpenFreeMap © OpenStreetMap", w / 2f, attrY, paint)
    }

    private fun drawVehicleMarker(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h * 0.55f

        val size = 48f
        val circleRadius = size * 1.2f

        // 白色描边圆（外层）
        val whiteBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, circleRadius + 3f, whiteBorderPaint)

        // 蓝色实心圆（内层）
        val blueFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2196F3")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, circleRadius, blueFillPaint)

        // 白色 chevron 箭头
        val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        val tipY = cy - size * 0.65f
        val shoulderY = cy + size * 0.5f
        val indentY = cy + size * 0.05f
        val halfW = size * 0.5f

        val chevronPath = Path().apply {
            moveTo(cx, tipY)
            lineTo(cx - halfW, shoulderY)
            lineTo(cx, indentY)
            lineTo(cx + halfW, shoulderY)
            close()
        }

        canvas.drawPath(chevronPath, arrowFillPaint)
    }

    private fun drawSpeedometer(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val speedY = 120f
        val speedStr = vehicleSpeed.toInt().toString()

        speedNumPaint.color = getSpeedColor(vehicleSpeed)
        canvas.drawText(speedStr, cx, speedY, speedNumPaint)
        canvas.drawText("km/h", cx, speedY + 40f, speedUnitPaint)
    }

    // === 速度制动态缩放（供 MainActivity 调用，控制 MapLibre zoom） ===
    // 基准 zoom 19.5 对应 3m 眼高（pitch=-15°, FOV≈50°）
    fun getDynamicZoom(speedKmh: Float): Float {
        val clamped = speedKmh.coerceIn(0f, 150f)
        val base = 19.5f - (clamped / 150f) * 3f
        return (base + zoomOffset).coerceIn(14f, 22f)
    }

    // === 兼容旧接口（路网数据现在由 MapLibre 处理，这里空实现） ===
    var roadSegments: List<RoadFetcher.RoadSegment> = emptyList()
        private set
    var hasRoads: Boolean = false
        private set

    fun setRoads(segments: List<RoadFetcher.RoadSegment>, centerLat: Double, centerLng: Double) {
        roadSegments = segments
        hasRoads = segments.isNotEmpty()
        // 路网数据现在由 MapLibre 底图自动加载，这里只保留状态供 HMM 地图匹配使用
    }

    // === 导航路线（由 MapLibre GeoJSON Source 渲染，HudView 不再画路线） ===
    var navigationRoute: List<Pair<Double, Double>> = emptyList()
        set(value) {
            field = value
            // 通知 MainActivity 更新 MapLibre 路线图层
            onRouteChanged?.invoke(value)
        }
    var navigationDestination: String? = null

    // 路线变更回调（MainActivity 设置）
    var onRouteChanged: ((List<Pair<Double, Double>>) -> Unit)? = null

    // === 道路吸附（保留给 HMM 使用） ===
    var snappedLat: Double = 0.0
    var snappedLng: Double = 0.0
    var isSnapped: Boolean = false
}
