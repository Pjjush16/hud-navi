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
import java.security.MessageDigest
import kotlin.math.*

/**
 * Overpass API 路网数据获取器
 * v2 — 增加磁盘缓存，启动时秒加载上次数据
 */
object RoadFetcher {

    private const val TAG = "RoadFetcher"
    private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
    // v14.7.12: 增大查询半径到 6km，覆盖 3×3 区块网格（≈9km×9km 范围）
    private const val RADIUS = 6000
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
    // 磁盘缓存永不过期（启动时优先显示本地缓存，再异步请求服务器）

    // 当前缓存的逐段哈希集合（用于增量 diff）
    private var currentSegmentHashes: Set<String> = emptySet()
    // 按哈希索引的段（用于增量合并时快速查找）
    private var segmentsByHash: Map<String, RoadSegment> = emptyMap()
    // 上次渲染时的哈希集合（判断是否需要重绘）
    private var renderedSegmentHashes: Set<String> = emptySet()

    data class RoadSegment(
        val type: RoadType,
        val points: List<Pair<Double, Double>>,
        val name: String = ""
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
     * 本地缓存永久有效，优先显示
     * 加载后立即构建逐段哈希索引，用于后续增量 diff
     */
    fun loadDiskCache(): List<RoadSegment> {
        val dir = cacheDir ?: return emptyList()
        val file = File(dir, CACHE_FILE)
        if (!file.exists()) return emptyList()

        return try {
            val json = file.readText()
            val segments = deserializeSegments(json)
            // 构建逐段哈希索引
            val hashMap = mutableMapOf<String, RoadSegment>()
            for (seg in segments) {
                hashMap[computeSegmentHash(seg)] = seg
            }
            segmentsByHash = hashMap
            currentSegmentHashes = hashMap.keys
            renderedSegmentHashes = currentSegmentHashes  // 标记为已渲染
            Log.i(TAG, "Loaded ${segments.size} segments from disk cache (${currentSegmentHashes.size} unique hashes)")
            segments
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load disk cache: ${e.message}")
            emptyList()
        }
    }

    /**
     * 保存合并后的路网到磁盘（全量写入，渲染需要完整数据）
     * 增量逻辑在内存中完成，磁盘只做持久化
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

    /**
     * 计算单条路网的 SHA-256 哈希（逐段哈希，用于增量 diff）
     * 量化到小数点后5位（~1m精度），同一条路在不同请求中哈希一致
     */
    private fun computeSegmentHash(seg: RoadSegment): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(seg.type.name.toByteArray())
        if (seg.name.isNotEmpty()) md.update(seg.name.toByteArray())
        for ((lat, lng) in seg.points) {
            val latInt = (lat * 100000).toInt()
            val lngInt = (lng * 100000).toInt()
            md.update(byteArrayOf(
                (latInt shr 24).toByte(), (latInt shr 16).toByte(),
                (latInt shr 8).toByte(), latInt.toByte()
            ))
            md.update(byteArrayOf(
                (lngInt shr 24).toByte(), (lngInt shr 16).toByte(),
                (lngInt shr 8).toByte(), lngInt.toByte()
            ))
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 增量 diff：比对新旧哈希集合，返回合并后的段列表和变化统计
     */
    data class DiffResult(
        val merged: List<RoadSegment>,
        val added: Int,
        val removed: Int,
        val unchanged: Int
    ) {
        val hasChanges: Boolean get() = added > 0 || removed > 0
    }

    private fun diffAndMerge(newSegments: List<RoadSegment>): DiffResult {
        // 1. 构建新数据的哈希索引
        val newHashMap = mutableMapOf<String, RoadSegment>()
        for (seg in newSegments) {
            newHashMap[computeSegmentHash(seg)] = seg
        }
        val newHashes = newHashMap.keys

        // 2. 集合运算
        val addedHashes = newHashes - currentSegmentHashes
        val removedHashes = currentSegmentHashes - newHashes
        val unchangedHashes = currentSegmentHashes intersect newHashes

        // 3. 增量合并：保留不变段（从缓存取）+ 加入新段
        val mergedMap = mutableMapOf<String, RoadSegment>()
        for (h in unchangedHashes) {
            segmentsByHash[h]?.let { mergedMap[h] = it }
        }
        for (h in addedHashes) {
            newHashMap[h]?.let { mergedMap[h] = it }
        }

        // 4. 更新索引
        segmentsByHash = mergedMap
        currentSegmentHashes = mergedMap.keys

        val merged = mergedMap.values.sortedBy { it.type.priority }
        return DiffResult(merged, addedHashes.size, removedHashes.size, unchangedHashes.size)
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
            if (seg.name.isNotEmpty()) obj.put("name", seg.name)
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
                val roadName = obj.optString("name", "")
                segments.add(RoadSegment(type, points, roadName))
            }
        }

        // 恢复缓存中心坐标
        cacheCenterLat = root.optDouble("centerLat", 0.0)
        cacheCenterLng = root.optDouble("centerLng", 0.0)

        return segments.sortedBy { it.type.priority }
    }

    /**
     * 获取路网数据（内存缓存 + 磁盘缓存 + 网络）
     *
     * 增量更新流程：
     * 1. 从服务器拉取新数据
     * 2. 对每段路计算 SHA-256 哈希
     * 3. 与缓存哈希集合做差集 → 得到 added/removed
     * 4. 增量合并：保留不变段 + 加入新段 - 移除旧段
     * 5. 只有存在实际变化时才标记 needRerender
     */
    data class FetchResult(
        val segments: List<RoadSegment>,
        val needRerender: Boolean
    )

    suspend fun fetchRoads(lat: Double, lng: Double): FetchResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val dist = haversine(lat, lng, cacheCenterLat, cacheCenterLng)

        // 内存缓存命中（短时间内距离很近，不需要重新请求）
        if (cachedSegments.isNotEmpty() && dist < 200.0 && (now - lastFetchTime) < 15000) {
            return@withContext FetchResult(cachedSegments, false)
        }

        // 限流
        if (now - lastFetchTime < MIN_INTERVAL_MS) {
            return@withContext FetchResult(cachedSegments, false)
        }

        try {
            val query = buildQuery(lat, lng)
            val result = httpPost(OVERPASS_URL, "data=${URLEncoder.encode(query, "UTF-8")}")
            val newSegments = parseOverpassResponse(result)

            // 增量 diff + 合并
            val diff = diffAndMerge(newSegments)

            if (!diff.hasChanges && cachedSegments.isNotEmpty()) {
                // 哈希集合完全一致，没有任何段增删，不需要重绘
                Log.i(TAG, "No changes (${diff.unchanged} segments identical), skip rerender")
                lastFetchTime = now
                return@withContext FetchResult(cachedSegments, false)
            }

            // 有变化，更新缓存
            cachedSegments = diff.merged
            cacheCenterLat = lat
            cacheCenterLng = lng
            lastFetchTime = now
            saveDiskCache(diff.merged, lat, lng)

            Log.i(TAG, "Incremental update: +${diff.added} -${diff.removed} =${diff.unchanged} → ${diff.merged.size} total")
            FetchResult(diff.merged, true)
        } catch (e: Exception) {
            Log.e(TAG, "Overpass fetch failed: ${e.message}")
            // 请求失败，返回缓存数据，不需要重绘
            FetchResult(cachedSegments, false)
        }
    }

    /**
     * 标记当前缓存数据已渲染到屏幕
     */
    fun markRendered() {
        renderedSegmentHashes = currentSegmentHashes
    }

    /**
     * 检查当前缓存是否与上次渲染的数据不同
     */
    fun needsRerender(): Boolean {
        return currentSegmentHashes != renderedSegmentHashes && cachedSegments.isNotEmpty()
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
                val roadName = tags.optString("name", "")
                segments.add(RoadSegment(roadType, points, roadName))
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
        return cachedSegments.isNotEmpty() && haversine(lat, lng, cacheCenterLat, cacheCenterLng) < 5000.0
    }
}
