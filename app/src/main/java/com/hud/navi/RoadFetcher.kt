package com.hud.navi

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.atan2

/**
 * 路网数据段：两个端点的经纬度 + 道路类型
 */
data class RoadSegment(
    val lat1: Double, val lng1: Double,
    val lat2: Double, val lng2: Double,
    val highwayType: String
)

/**
 * 从 Overpass API 获取矢量路网数据
 */
object RoadFetcher {

    private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
    private const val RADIUS_M = 800  // 查询半径 800 米

    /**
     * 同步获取路网（需在后台线程调用）
     * @return 路网段列表，失败返回空列表
     */
    fun fetch(lat: Double, lng: Double): List<RoadSegment> {
        return try {
            val query = buildQuery(lat, lng)
            val conn = (URL(OVERPASS_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connectTimeout = 8000
                readTimeout = 12000
                setRequestProperty("User-Agent", "HudNavi/1.0")
            }
            conn.outputStream.use { os ->
                os.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray())
            }
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            parseOverpassJson(response)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * 构建 Overpass QL 查询（获取所有道路几何数据）
     */
    private fun buildQuery(lat: Double, lng: Double): String {
        return """
            [out:json][timeout:15];
            way["highway"](around:$RADIUS_M,$lat,$lng);
            out geom;
        """.trimIndent()
    }

    /**
     * 解析 Overpass JSON 响应，提取道路线段
     */
    private fun parseOverpassJson(json: String): List<RoadSegment> {
        val segments = mutableListOf<RoadSegment>()
        try {
            val root = JSONObject(json)
            val elements = root.getJSONArray("elements")
            for (i in 0 until elements.length()) {
                val way = elements.getJSONObject(i)
                if (way.optString("type") != "way") continue

                val tags = way.optJSONObject("tags")
                val highwayType = tags?.optString("highway", "road") ?: "road"

                val geometry = way.optJSONArray("geometry") ?: continue
                for (j in 0 until geometry.length() - 1) {
                    val p1 = geometry.getJSONObject(j)
                    val p2 = geometry.getJSONObject(j + 1)
                    segments.add(
                        RoadSegment(
                            p1.getDouble("lat"), p1.getDouble("lon"),
                            p2.getDouble("lat"), p2.getDouble("lon"),
                            highwayType
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return segments
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
