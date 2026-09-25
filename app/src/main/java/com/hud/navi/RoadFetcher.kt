/*
 * hud-navi - Lightweight HUD navigation with Canvas 2D rendering
 * Copyright (C) 2026 Pjjush16
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */


package com.hud.navi

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.*

/**
 * Overpass API 路网数据获取器
 * 查询当前位置周围的道路网络，按道路类型分层返回
 */
object RoadFetcher {

    private const val TAG = "RoadFetcher"
    private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
    private const val RADIUS = 1200 // 查询半径（米）
    private const val MIN_INTERVAL_MS = 3000 // 最小请求间隔

    private var lastFetchTime = 0L
    private var lastLat = 0.0
    private var lastLng = 0.0
    private var cachedSegments: List<RoadSegment> = emptyList()
    private var cacheCenterLat = 0.0
    private var cacheCenterLng = 0.0

    /**
     * 道路分段数据
     * @param type 道路类型 (motorway, primary, secondary, tertiary, residential, service, path)
     * @param points 经纬度坐标列表 [(lat,lng), ...]
     */
    data class RoadSegment(
        val type: RoadType,
        val points: List<Pair<Double, Double>>
    )

    enum class RoadType(val priority: Int, val color: Int, val widthBase: Float) {
        PATH(0, 0xFF3A4A55.toInt(), 1.5f),
        SERVICE(1, 0xFF4A5A65.toInt(), 2.0f),
        RESIDENTIAL(2, 0xFF6A8090.toInt(), 3.0f),
        TERTIARY(3, 0xFF88AABB.toInt(), 4.0f),
        SECONDARY(4, 0xFFAACCDD.toInt(), 5.5f),
        PRIMARY(5, 0xFFCCDDEE.toInt(), 7.0f),
        TRUNK(6, 0xFF66DDFF.toInt(), 8.5f),
        MOTORWAY(7, 0xFF44DDFF.toInt(), 10.0f);

        companion object {
            fun fromHighway(tag: String): RoadType = when (tag) {
                "motorway", "motorway_link" -> MOTORWAY
                "trunk", "trunk_link" -> TRUNK
                "primary", "primary_link" -> PRIMARY
                "secondary", "secondary_link" -> SECONDARY
                "tertiary", "tertiary_link" -> TERTIARY
                "residential", "living_street", "unclassified" -> RESIDENTIAL
                "service" -> SERVICE
                else -> PATH
            }
        }
    }

    /**
     * 获取路网数据（带缓存）
     * 当距离上次获取位置超过 200m 或超过 10s 才真正请求
     */
    suspend fun fetchRoads(lat: Double, lng: Double): List<RoadSegment> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val dist = haversine(lat, lng, cacheCenterLat, cacheCenterLng)

        // 缓存命中：位置没变太远且时间没过久
        if (cachedSegments.isNotEmpty() && dist < 200.0 && (now - lastFetchTime) < 15000) {
            return@withContext cachedSegments
        }

        // 限流
        if (now - lastFetchTime < MIN_INTERVAL_MS) {
            return@withContext cachedSegments
        }

        try {
            val query = buildQuery(lat, lng)
            val result = httpPost(OVERPASS_URL, "data=${URLEncoder.encode(query, "UTF-8")}")
            val segments = parseOverpassResponse(result)

            cachedSegments = segments
            cacheCenterLat = lat
            cacheCenterLng = lng
            lastFetchTime = now

            Log.i(TAG, "Fetched ${segments.size} road segments (${segments.sumOf { it.points.size }} points)")
            segments
        } catch (e: Exception) {
            Log.e(TAG, "Overpass fetch failed: ${e.message}")
            cachedSegments // 返回旧缓存
        }
    }

    private fun buildQuery(lat: Double, lng: Double): String {
        return """
            [out:json][timeout:10];
            way["highway"~"motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|residential|living_street|unclassified|service"]
            (around:$RADIUS,$lat,$lng);
            out body;
            >;
            out skel qt;
        """.trimIndent()
    }

    private fun parseOverpassResponse(json: String): List<RoadSegment> {
        val root = JSONObject(json)
        val elements = root.getJSONArray("elements")

        // 先收集所有 node
        val nodes = mutableMapOf<Long, Pair<Double, Double>>()
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            if (el.getString("type") == "node") {
                nodes[el.getLong("id")] = Pair(el.getDouble("lat"), el.getDouble("lon"))
            }
        }

        // 再解析 way → 路段
        val segments = mutableListOf<RoadSegment>()
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            if (el.getString("type") != "way") continue

            val tags = el.optJSONObject("tags") ?: continue
            val highway = tags.optString("highway", "")
            val roadType = RoadType.fromHighway(highway)

            val nodeIds = el.getJSONArray("nodes")
            val points = mutableListOf<Pair<Double, Double>>()
            for (j in 0 until nodeIds.length()) {
                val nodeId = nodeIds.getLong(j)
                nodes[nodeId]?.let { points.add(it) }
            }

            if (points.size >= 2) {
                segments.add(RoadSegment(roadType, points))
            }
        }

        // 按优先级排序：小路先画（底层），大路后画（上层）
        return segments.sortedBy { it.type.priority }
    }

    private fun httpPost(urlStr: String, body: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 8000
        conn.readTimeout = 12000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("User-Agent", "HudNavi/7.0")

        conn.outputStream.use { it.write(body.toByteArray()) }

        return BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
            reader.readText()
        }
    }

    /**
     * Haversine 距离（米）
     */
    fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * 缓存是否覆盖指定坐标（距缓存中心 < 800m）
     */
    fun isCacheValid(lat: Double, lng: Double): Boolean {
        return cachedSegments.isNotEmpty() && haversine(lat, lng, cacheCenterLat, cacheCenterLng) < 800.0
    }
}
