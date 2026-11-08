/*
 * hud-navi - Lightweight HUD navigation with Canvas 2D rendering
 * Copyright (C) 2026 Pjjush16
 *
 * Road data provided by OpenStreetMap (https://www.openstreetmap.org),
 * licensed under the Open Database License (ODbL).
 * © OpenStreetMap contributors
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
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.*

/**
 * Overpass API 路网数据获取器
 * v2 — 增加磁盘缓存，启动时秒加载上次数据
 */
object RoadFetcher {

    private const val TAG = "RoadFetcher"
    private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
    private const val RADIUS = 1200
    private const val MIN_INTERVAL_MS = 3000

    private var lastFetchTime = 0L
    private var lastLat = 0.0
    private var lastLng = 0.0
    private var cachedSegments: List<RoadSegment> = emptyList()
    private var cacheCenterLat = 0.0
    private var cacheCenterLng = 0.0

    // === 磁盘缓存 ===
    private var cacheDir: File? = null
    private const val CACHE_FILE = "road_cache.json"
    private const val CACHE_MAX_AGE_MS = 30 * 60 * 1000L  // 30 分钟

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
     * 初始化磁盘缓存目录
     */
    fun initCache(dir: File) {
        cacheDir = dir
        if (!dir.exists()) dir.mkdirs()
    }

    /**
     * 从磁盘加载缓存（启动时调用，秒加载）
     */
    fun loadDiskCache(): List<RoadSegment> {
        val dir = cacheDir ?: return emptyList()
        val file = File(dir, CACHE_FILE)
        if (!file.exists()) return emptyList()

        val age = System.currentTimeMillis() - file.lastModified()
        if (age > CACHE_MAX_AGE_MS) {
            Log.i(TAG, "Disk cache expired (${age / 1000}s old)")
            return emptyList()
        }

        return try {
            val json = file.readText()
            val segments = deserializeSegments(json)
            Log.i(TAG, "Loaded ${segments.size} segments from disk cache (${age / 1000}s old)")
            segments
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load disk cache: ${e.message}")
            emptyList()
        }
    }

    /**
     * 保存缓存到磁盘
     */
    private fun saveDiskCache(segments: List<RoadSegment>, lat: Double, lng: Double) {
        val dir = cacheDir ?: return
        val file = File(dir, CACHE_FILE)
        try {
            val json = serializeSegments(segments, lat, lng)
            file.writeText(json)
            Log.i(TAG, "Saved ${segments.size} segments to disk cache")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save disk cache: ${e.message}")
        }
    }

    private fun serializeSegments(segments: List<RoadSegment>, lat: Double, lng: Double): String {
        val root = JSONObject()
        root.put("centerLat", lat)
        root.put("centerLng", lng)
        root.put("timestamp", System.currentTimeMillis())

        val arr = org.json.JSONArray()
        for (seg in segments) {
            val obj = JSONObject()
            obj.put("type", seg.type.name)
            val ptsArr = org.json.JSONArray()
            for ((plat, plng) in seg.points) {
                ptsArr.put(plat)
                ptsArr.put(plng)
            }
            obj.put("points", ptsArr)
            arr.put(obj)
        }
        root.put("segments", arr)
        return root.toString()
    }

    private fun deserializeSegments(json: String): List<RoadSegment> {
        val root = JSONObject(json)
        val arr = root.getJSONArray("segments")
        val segments = mutableListOf<RoadSegment>()

        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val typeName = obj.getString("type")
            val type = try { RoadType.valueOf(typeName) } catch (e: Exception) { continue }

            val ptsArr = obj.getJSONArray("points")
            val points = mutableListOf<Pair<Double, Double>>()
            for (j in 0 until ptsArr.length() step 2) {
                points.add(Pair(ptsArr.getDouble(j), ptsArr.getDouble(j + 1)))
            }

            if (points.size >= 2) {
                segments.add(RoadSegment(type, points))
            }
        }

        // 恢复缓存中心坐标
        cacheCenterLat = root.optDouble("centerLat", 0.0)
        cacheCenterLng = root.optDouble("centerLng", 0.0)

        return segments.sortedBy { it.type.priority }
    }

    /**
     * 获取路网数据（内存缓存 + 磁盘缓存 + 网络）
     */
    suspend fun fetchRoads(lat: Double, lng: Double): List<RoadSegment> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val dist = haversine(lat, lng, cacheCenterLat, cacheCenterLng)

        // 内存缓存命中
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

            // 保存到磁盘
            saveDiskCache(segments, lat, lng)

            Log.i(TAG, "Fetched ${segments.size} road segments")
            segments
        } catch (e: Exception) {
            Log.e(TAG, "Overpass fetch failed: ${e.message}")
            cachedSegments
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

        val nodes = mutableMapOf<Long, Pair<Double, Double>>()
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            if (el.getString("type") == "node") {
                nodes[el.getLong("id")] = Pair(el.getDouble("lat"), el.getDouble("lon"))
            }
        }

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
        conn.setRequestProperty("User-Agent", "HudNavi/9.0")

        conn.outputStream.use { it.write(body.toByteArray()) }

        return BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
            reader.readText()
        }
    }

    fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun isCacheValid(lat: Double, lng: Double): Boolean {
        return cachedSegments.isNotEmpty() && haversine(lat, lng, cacheCenterLat, cacheCenterLng) < 800.0
    }
}
