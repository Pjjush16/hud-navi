package com.hud.navi

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.atan2

/**
 * 路网数据段：两个端点的经纬度 + 道路类型 + 实际宽度(米)
 */
data class RoadSegment(
    val lat1: Double, val lng1: Double,
    val lat2: Double, val lng2: Double,
    val highwayType: String,
    val widthMeters: Float = -1f  // -1 = 未知，用类型默认值
)

/**
 * 获取状态枚举
 */
enum class FetchStatus {
    SUCCESS,        // 成功获取路网
    EMPTY,          // 获取成功但该区域无道路
    TIMEOUT,        // 超时
    NETWORK_ERROR,  // 网络错误
    PARSE_ERROR,    // 解析错误
    ALL_FAILED      // 所有镜像源均失败
}

data class FetchResult(
    val segments: List<RoadSegment>,
    val status: FetchStatus,
    val message: String
)

/**
 * 从 Overpass API 获取矢量路网数据
 * 支持多镜像源自动切换 + 重试
 */
object RoadFetcher {

    private const val TAG = "RoadFetcher"

    // 多个 Overpass API 镜像源（按可用性排序）
    private val MIRRORS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.openstreetmap.ru/api/interpreter"
    )

    private const val RADIUS_M = 1000      // 查询半径 1000 米（扩大范围）
    private const val CONNECT_TIMEOUT = 15000  // 15 秒连接超时
    private const val READ_TIMEOUT = 45000     // 45 秒读取超时
    private const val MAX_RETRIES = 2          // 每个镜像源最多重试 2 次

    @Volatile
    var lastError: String = ""

    /**
     * 同步获取路网（需在后台线程调用）
     * 自动尝试多个镜像源，每个重试最多 MAX_RETRIES 次
     */
    fun fetch(lat: Double, lng: Double): FetchResult {
        val query = buildQuery(lat, lng)
        val errors = mutableListOf<String>()

        for (mirror in MIRRORS) {
            Log.d(TAG, "尝试镜像源: $mirror")
            for (attempt in 1..MAX_RETRIES) {
                try {
                    val result = fetchFromMirror(mirror, query)
                    if (result.segments.isNotEmpty()) {
                        Log.d(TAG, "成功: ${result.segments.size} 段 from $mirror")
                        lastError = ""
                        return result
                    }
                    // 空结果可能是真的没有道路，也可能是解析问题
                    // 继续尝试下一个镜像
                    errors.add("$mirror: 空结果")
                    break  // 空结果不重试，换下一个镜像
                } catch (e: java.net.SocketTimeoutException) {
                    val msg = "$mirror: 超时 (attempt $attempt)"
                    errors.add(msg)
                    Log.w(TAG, msg)
                } catch (e: java.net.ConnectException) {
                    val msg = "$mirror: 连接失败"
                    errors.add(msg)
                    Log.w(TAG, msg)
                    break  // 连接失败直接换镜像
                } catch (e: javax.net.ssl.SSLException) {
                    val msg = "$mirror: SSL错误"
                    errors.add(msg)
                    Log.w(TAG, msg)
                    break
                } catch (e: Exception) {
                    val msg = "$mirror: ${e.javaClass.simpleName}: ${e.message}"
                    errors.add(msg)
                    Log.w(TAG, msg)
                }
            }
        }

        lastError = errors.joinToString("; ")
        Log.e(TAG, "所有镜像源均失败: $lastError")
        return FetchResult(emptyList(), FetchStatus.ALL_FAILED, lastError)
    }

    /**
     * 从单个镜像源获取路网
     */
    private fun fetchFromMirror(mirrorUrl: String, query: String): FetchResult {
        val conn = (URL(mirrorUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("User-Agent", "HudNavi/1.0 (Android)")
        }

        try {
            conn.outputStream.use { os ->
                os.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray())
                os.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errorBody = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Exception) { "" }
                throw RuntimeException("HTTP $responseCode: $errorBody")
            }

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            return parseOverpassJson(response)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 构建 Overpass QL 查询
     */
    private fun buildQuery(lat: Double, lng: Double): String {
        return """
            [out:json][timeout:25];
            way["highway"](around:$RADIUS_M,$lat,$lng);
            out geom;
        """.trimIndent()
    }

    /**
     * 解析 Overpass JSON 响应
     */
    private fun parseOverpassJson(json: String): FetchResult {
        val segments = mutableListOf<RoadSegment>()
        try {
            val root = JSONObject(json)
            val elements = root.getJSONArray("elements")
            for (i in 0 until elements.length()) {
                val way = elements.getJSONObject(i)
                if (way.optString("type") != "way") continue

                val tags = way.optJSONObject("tags")
                val highwayType = tags?.optString("highway", "road") ?: "road"

                // 解析实际宽度（米）：优先用 width 标签，其次用 lanes 估算
                val widthMeters = parseWidth(tags)

                val geometry = way.optJSONArray("geometry") ?: continue
                for (j in 0 until geometry.length() - 1) {
                    val p1 = geometry.getJSONObject(j)
                    val p2 = geometry.getJSONObject(j + 1)
                    segments.add(
                        RoadSegment(
                            p1.getDouble("lat"), p1.getDouble("lon"),
                            p2.getDouble("lat"), p2.getDouble("lon"),
                            highwayType,
                            widthMeters
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "JSON 解析失败: ${e.message}")
            return FetchResult(emptyList(), FetchStatus.PARSE_ERROR, e.message ?: "解析失败")
        }

        val status = if (segments.isEmpty()) FetchStatus.EMPTY else FetchStatus.SUCCESS
        return FetchResult(segments, status, "${segments.size} 段道路")
    }

    /**
     * 解析道路宽度（米）：优先 width 标签，其次 lanes × 3.5m
     */
    private fun parseWidth(tags: JSONObject?): Float {
        if (tags == null) return -1f

        // 1. 直接用 width 标签
        val widthStr = tags.optString("width", "")
        if (widthStr.isNotEmpty()) {
            try {
                val w = widthStr.replace(",", ".").toFloat()
                if (w in 1f..100f) return w
            } catch (_: NumberFormatException) {}
        }

        // 2. 用 lanes 估算（每车道 3.5m + 路肩 2m）
        val lanesStr = tags.optString("lanes", "")
        if (lanesStr.isNotEmpty()) {
            try {
                val lanes = lanesStr.toInt()
                if (lanes in 1..12) return lanes * 3.5f + 2f
            } catch (_: NumberFormatException) {}
        }

        return -1f  // 未知
    }

    /**
     * 两点间距离（米）
     */
    fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
