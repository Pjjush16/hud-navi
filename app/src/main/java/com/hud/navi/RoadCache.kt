package com.hud.navi

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.*

/**
 * 路网缓存：内存 + 磁盘双层
 *
 * 设计：
 * - 空间网格索引：将经纬度量化为 ~300m 格子
 * - 每个格子缓存一次 Overpass 查询结果（半径 1000m 的道路段）
 * - 渲染时合并车辆周围所有命中格子的道路段
 * - 过期时间 30 分钟（道路不会经常变）
 * - 磁盘持久化：app 重启后缓存仍可用
 */
object RoadCache {

    private const val TAG = "RoadCache"
    private const val GRID_SIZE_DEG = 0.003  // ~333m at mid-latitudes
    private const val CACHE_TTL_MS = 30 * 60 * 1000L  // 30 分钟
    private const val MAX_CACHE_ENTRIES = 200
    private const val CACHE_FILE = "road_cache.json"

    // 内存缓存：gridKey → CacheEntry
    private val cache = LinkedHashMap<String, CacheEntry>(64, 0.75f, true)

    private var cacheDir: File? = null
    private var loaded = false

    data class CacheEntry(
        val segments: List<RoadSegment>,
        val timestamp: Long,
        val centerLat: Double,
        val centerLng: Double
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > CACHE_TTL_MS
    }

    /**
     * 初始化（从磁盘加载）
     */
    fun init(context: Context) {
        cacheDir = File(context.cacheDir, CACHE_FILE)
        if (!loaded) {
            loadFromDisk()
            loaded = true
        }
    }

    /**
     * 网格 key：将经纬度量化到格子
     */
    fun gridKey(lat: Double, lng: Double): String {
        val gLat = (lat / GRID_SIZE_DEG).toInt()
        val gLng = (lng / GRID_SIZE_DEG).toInt()
        return "${gLat}_${gLng}"
    }

    /**
     * 查询缓存：获取车辆周围所有命中格子的道路段（合并去重）
     * 返回 null 表示缓存未命中（需要去网络获取）
     */
    fun get(lat: Double, lng: Double): List<RoadSegment>? {
        val nearbyKeys = getNearbyKeys(lat, lng)
        val allSegments = mutableListOf<RoadSegment>()
        var allHit = true

        for (key in nearbyKeys) {
            val entry = cache[key]
            if (entry == null || entry.isExpired()) {
                allHit = false
                continue
            }
            allSegments.addAll(entry.segments)
        }

        // 至少要有中心格子命中才算缓存有效
        val centerKey = gridKey(lat, lng)
        val centerEntry = cache[centerKey]
        if (centerEntry == null || centerEntry.isExpired()) {
            return null  // 中心格子未命中，需要网络获取
        }

        Log.d(TAG, "缓存命中: ${allSegments.size} 段 (${nearbyKeys.size} 格子)")
        return deduplicate(allSegments)
    }

    /**
     * 写入缓存
     */
    fun put(lat: Double, lng: Double, segments: List<RoadSegment>) {
        val key = gridKey(lat, lng)
        val entry = CacheEntry(segments, System.currentTimeMillis(), lat, lng)
        cache[key] = entry

        // 限制缓存大小
        while (cache.size > MAX_CACHE_ENTRIES) {
            val oldest = cache.entries.minByOrNull { it.value.timestamp }
            if (oldest != null) {
                cache.remove(oldest.key)
            }
        }

        Log.d(TAG, "缓存写入: $key = ${segments.size} 段 (总格子: ${cache.size})")
        saveToDisk()
    }

    /**
     * 获取车辆周围的网格 key（3x3 范围）
     */
    private fun getNearbyKeys(lat: Double, lng: Double): List<String> {
        val gLat = (lat / GRID_SIZE_DEG).toInt()
        val gLng = (lng / GRID_SIZE_DEG).toInt()
        val keys = mutableListOf<String>()
        for (dLat in -1..1) {
            for (dLng in -1..1) {
                keys.add("${gLat + dLat}_${gLng + dLng}")
            }
        }
        return keys
    }

    /**
     * 去重：同一段路可能被多个格子覆盖
     */
    private fun deduplicate(segments: List<RoadSegment>): List<RoadSegment> {
        val seen = HashSet<Long>()
        val result = mutableListOf<RoadSegment>()
        for (seg in segments) {
            // 用四舍五入到小数点后 5 位的 hash 做去重
            val key = hashSegment(seg)
            if (seen.add(key)) {
                result.add(seg)
            }
        }
        return result
    }

    private fun hashSegment(seg: RoadSegment): Long {
        val a = (seg.lat1 * 100000).toLong()
        val b = (seg.lng1 * 100000).toLong()
        val c = (seg.lat2 * 100000).toLong()
        val d = (seg.lng2 * 100000).toLong()
        return a * 1000000000000000L + b * 1000000000L + c * 1000L + d
    }

    /**
     * 清理过期缓存
     */
    fun cleanup() {
        val before = cache.size
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.isExpired()) {
                iterator.remove()
            }
        }
        if (cache.size != before) {
            Log.d(TAG, "清理过期缓存: $before → ${cache.size}")
            saveToDisk()
        }
    }

    /**
     * 统计信息
     */
    fun stats(): String {
        val valid = cache.values.count { !it.isExpired() }
        val total = cache.values.sumOf { it.segments.size }
        return "缓存: $valid/${cache.size} 格子, $total 段道路"
    }

    // === 磁盘持久化 ===

    private fun saveToDisk() {
        val file = cacheDir ?: return
        try {
            val json = JSONObject()
            val arr = JSONArray()
            for ((key, entry) in cache) {
                val obj = JSONObject().apply {
                    put("key", key)
                    put("ts", entry.timestamp)
                    put("lat", entry.centerLat)
                    put("lng", entry.centerLng)
                    val segs = JSONArray()
                    for (seg in entry.segments) {
                        segs.put(JSONObject().apply {
                            put("lat1", seg.lat1); put("lng1", seg.lng1)
                            put("lat2", seg.lat2); put("lng2", seg.lng2)
                            put("type", seg.highwayType)
                            put("w", seg.widthMeters)
                        })
                    }
                    put("segs", segs)
                }
                arr.put(obj)
            }
            json.put("entries", arr)
            file.writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "缓存写盘失败: ${e.message}")
        }
    }

    private fun loadFromDisk() {
        val file = cacheDir ?: return
        if (!file.exists()) return
        try {
            val json = JSONObject(file.readText())
            val arr = json.getJSONArray("entries")
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val key = obj.getString("key")
                val ts = obj.getLong("ts")
                val lat = obj.getDouble("lat")
                val lng = obj.getDouble("lng")
                val segs = mutableListOf<RoadSegment>()
                val segArr = obj.getJSONArray("segs")
                for (j in 0 until segArr.length()) {
                    val s = segArr.getJSONObject(j)
                    segs.add(RoadSegment(
                        s.getDouble("lat1"), s.getDouble("lng1"),
                        s.getDouble("lat2"), s.getDouble("lng2"),
                        s.getString("type"),
                        s.optDouble("w", -1.0).toFloat()
                    ))
                }
                cache[key] = CacheEntry(segs, ts, lat, lng)
            }
            Log.d(TAG, "缓存加载: ${cache.size} 格子")
            // 清理过期的
            cleanup()
        } catch (e: Exception) {
            Log.w(TAG, "缓存读盘失败: ${e.message}")
        }
    }
}
