/*
 * OsrmRouter.kt - OSRM 路线规划客户端
 *
 * 使用 OSRM (Open Source Routing Machine) 进行路线规划。
 * 支持公共服务器和自部署服务器。
 *
 * API: http://router.project-osrm.org/route/v1/driving/{lng},{lat};{lng},{lat}?overview=full&geometries=geojson&steps=true
 */

package com.hud.navi

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 路线规划结果
 */
data class RouteResult(
    val success: Boolean,
    val distance: Double = 0.0,      // 米
    val duration: Double = 0.0,      // 秒
    val geometry: List<Pair<Double, Double>> = emptyList(),  // [(lat, lng), ...]
    val steps: List<RouteStep> = emptyList(),
    val error: String = ""
) {
    companion object {
        fun failure(msg: String) = RouteResult(success = false, error = msg)
    }
}

/**
 * 单步导航指令
 */
data class RouteStep(
    val instruction: String,          // 例如 "Turn left onto Main Street"
    val distance: Double,             // 米
    val duration: Double,             // 秒
    val maneuver: Maneuver
)

/**
 * 转向动作
 */
data class Maneuver(
    val type: String,                 // turn, depart, arrive, ...
    val modifier: String = "",        // left, right, straight, ...
    val bearingAfter: Int = 0,
    val bearingBefore: Int = 0,
    val location: Pair<Double, Double> = Pair(0.0, 0.0)  // (lat, lng)
)

class OsrmRouter {
    companion object {
        private const val TAG = "OsrmRouter"
        private const val TIMEOUT = 10000  // 10s

        // OSRM 公共服务器（免费，有使用限制）
        const val DEFAULT_SERVER = "http://router.project-osrm.org"

        // 导航指令中文映射
        private val MANEUVER_CN = mapOf(
            "depart" to "出发",
            "arrive" to "到达目的地",
            "turn" to "转弯",
            "turn-left" to "左转",
            "turn-right" to "右转",
            "turn-straight" to "直行",
            "turn-slight left" to "向左前方",
            "turn-slight right" to "向右前方",
            "turn-sharp left" to "向左急转",
            "turn-sharp right" to "向右急转",
            "roundabout" to "进入环岛",
            "roundabout-exit" to "驶出环岛",
            "fork" to "分叉路口",
            "fork-left" to "靠左行驶",
            "fork-right" to "靠右行驶",
            "merge" to "并入",
            "merge-left" to "向左并道",
            "merge-right" to "向右并道",
            "continue" to "继续直行",
            "continue-left" to "向左继续",
            "continue-right" to "向右继续",
            "end of road" to "道路尽头",
            "end of road-left" to "在道路尽头左转",
            "end of road-right" to "在道路尽头右转"
        )
    }

    private var serverUrl: String = DEFAULT_SERVER

    /**
     * 设置 OSRM 服务器地址
     */
    fun setServer(url: String) {
        serverUrl = url.trimEnd('/')
        Log.i(TAG, "OSRM server set to: $serverUrl")
    }

    /**
     * 规划路线
     *
     * @param from 起点 (lat, lng)
     * @param to   终点 (lat, lng)
     * @return 路线规划结果
     */
    fun route(from: Pair<Double, Double>, to: Pair<Double, Double>): RouteResult {
        try {
            // OSRM 坐标格式: lng,lat (注意顺序)
            val url = "$serverUrl/route/v1/driving/${from.second},${from.first};${to.second},${to.first}" +
                "?overview=full&geometries=geojson&steps=true&annotations=false"

            Log.i(TAG, "Routing: $url")

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "hud-navi/1.0")

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                conn.disconnect()
                return RouteResult.failure("OSRM server returned HTTP $responseCode")
            }

            val responseBody = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            conn.disconnect()

            return parseRouteResponse(responseBody)

        } catch (e: Exception) {
            Log.e(TAG, "Routing failed: ${e.message}", e)
            return RouteResult.failure("路线规划失败: ${e.message}")
        }
    }

    /**
     * 解析 OSRM 响应
     */
    private fun parseRouteResponse(json: String): RouteResult {
        try {
            val root = JSONObject(json)
            val code = root.optString("code", "")

            if (code != "Ok") {
                return RouteResult.failure("OSRM error: $code")
            }

            val routes = root.optJSONArray("routes") ?: return RouteResult.failure("No routes in response")
            if (routes.length() == 0) return RouteResult.failure("Empty routes")

            val route = routes.getJSONObject(0)
            val distance = route.optDouble("distance", 0.0)
            val duration = route.optDouble("duration", 0.0)

            // 解析几何形状 (GeoJSON LineString)
            val geometry = parseGeometry(route.optJSONObject("geometry"))

            // 解析导航步骤
            val steps = mutableListOf<RouteStep>()
            val legs = route.optJSONArray("legs")
            if (legs != null && legs.length() > 0) {
                val leg = legs.getJSONObject(0)
                val stepsArray = leg.optJSONArray("steps")
                if (stepsArray != null) {
                    for (i in 0 until stepsArray.length()) {
                        val step = stepsArray.getJSONObject(i)
                        steps.add(parseStep(step))
                    }
                }
            }

            Log.i(TAG, "Route: ${(distance/1000).toInt()}km, ${(duration/60).toInt()}min, ${geometry.size} points, ${steps.size} steps")

            return RouteResult(
                success = true,
                distance = distance,
                duration = duration,
                geometry = geometry,
                steps = steps
            )

        } catch (e: Exception) {
            Log.e(TAG, "Parse failed: ${e.message}", e)
            return RouteResult.failure("解析路线失败: ${e.message}")
        }
    }

    /**
     * 解析 GeoJSON LineString 几何形状
     */
    private fun parseGeometry(geojson: JSONObject?): List<Pair<Double, Double>> {
        if (geojson == null) return emptyList()

        val type = geojson.optString("type", "")
        if (type != "LineString") return emptyList()

        val coordinates = geojson.optJSONArray("coordinates") ?: return emptyList()
        val points = mutableListOf<Pair<Double, Double>>()

        for (i in 0 until coordinates.length()) {
            val coord = coordinates.getJSONArray(i)
            val lng = coord.getDouble(0)
            val lat = coord.getDouble(1)
            points.add(Pair(lat, lng))
        }

        return points
    }

    /**
     * 解析单步导航指令
     */
    private fun parseStep(step: JSONObject): RouteStep {
        val distance = step.optDouble("distance", 0.0)
        val duration = step.optDouble("duration", 0.0)

        // 解析 maneuver
        val maneuverJson = step.optJSONObject("maneuver")
        val maneuver = if (maneuverJson != null) {
            val type = maneuverJson.optString("type", "")
            val modifier = maneuverJson.optString("modifier", "")
            val bearingAfter = maneuverJson.optInt("bearing_after", 0)
            val bearingBefore = maneuverJson.optInt("bearing_before", 0)

            val locationJson = maneuverJson.optJSONArray("location")
            val location = if (locationJson != null && locationJson.length() >= 2) {
                Pair(locationJson.getDouble(1), locationJson.getDouble(0))  // lat, lng
            } else {
                Pair(0.0, 0.0)
            }

            Maneuver(type, modifier, bearingAfter, bearingBefore, location)
        } else {
            Maneuver("")
        }

        // 生成中文指令
        val instruction = generateInstruction(maneuver, step.optString("name", ""))

        return RouteStep(instruction, distance, duration, maneuver)
    }

    /**
     * 生成中文导航指令
     */
    private fun generateInstruction(maneuver: Maneuver, roadName: String): String {
        val key = if (maneuver.modifier.isNotBlank()) {
            "${maneuver.type}-${maneuver.modifier}"
        } else {
            maneuver.type
        }

        val action = MANEUVER_CN[key] ?: MANEUVER_CN[maneuver.type] ?: "继续"

        return if (roadName.isNotBlank()) {
            "$action，进入${roadName}"
        } else {
            action
        }
    }

    /**
     * 格式化距离显示
     */
    fun formatDistance(meters: Double): String {
        return if (meters < 1000) {
            "${meters.toInt()}米"
        } else {
            String.format("%.1f公里", meters / 1000)
        }
    }

    /**
     * 格式化时间显示
     */
    fun formatDuration(seconds: Double): String {
        val minutes = (seconds / 60).toInt()
        return if (minutes < 60) {
            "${minutes}分钟"
        } else {
            val hours = minutes / 60
            val mins = minutes % 60
            if (mins == 0) "${hours}小时" else "${hours}小时${mins}分钟"
        }
    }
}
