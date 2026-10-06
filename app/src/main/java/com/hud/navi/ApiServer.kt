/*
 * ApiServer.kt - HTTP API 服务器
 *
 * 基于 NanoHTTPD 的轻量级嵌入式 HTTP 服务器，提供 RESTful API 接口。
 * 支持 API Key 认证，允许远程 AI 模型通过 Function Calling 控制应用。
 *
 * 默认端口: 8080
 * 认证方式: Header "X-API-Key" 或 Query param "api_key"
 */

package com.hud.navi

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

/**
 * API 操作回调接口
 */
interface ApiActionHandler {
    fun onMapZoomIn()
    fun onMapZoomOut()
    fun onMapZoomReset()
    fun onMirrorToggle()
    fun onMirrorOn()
    fun onMirrorOff()
    fun onNavigateTo(lat: Double, lng: Double, name: String)
    fun onNavigateCancel()
    fun onMusicPlay()
    fun onMusicPause()
    fun onMusicNext()
    fun onMusicPrev()
    fun onGetStatus(): JSONObject
}

class ApiServer(
    private val context: Context,
    private val handler: ApiActionHandler,
    port: Int = 8080
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "ApiServer"
        private const val PREFS_NAME = "api_server_prefs"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_PORT = "api_port"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var serverPort = port
    private var apiKey: String = ""

    var isRunning: Boolean = false
        private set

    init {
        // 从配置读取 API Key 和端口
        apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        serverPort = prefs.getInt(KEY_PORT, port)

        // 如果没有 API Key，生成一个随机 Key
        if (apiKey.isBlank()) {
            apiKey = generateApiKey()
            prefs.edit().putString(KEY_API_KEY, apiKey).apply()
            Log.i(TAG, "Generated new API key: $apiKey")
        }
    }

    /**
     * 启动服务器
     */
    fun startServer() {
        try {
            start(SOCKET_READ_TIMEOUT, false)
            isRunning = true
            Log.i(TAG, "API server started on port $serverPort")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start API server: ${e.message}", e)
            isRunning = false
        }
    }

    /**
     * 停止服务器
     */
    fun stopServer() {
        stop()
        isRunning = false
        Log.i(TAG, "API server stopped")
    }

    /**
     * 获取 API Key
     */
    fun getApiKey(): String = apiKey

    /**
     * 设置 API Key
     */
    fun setApiKey(newKey: String) {
        apiKey = newKey
        prefs.edit().putString(KEY_API_KEY, newKey).apply()
        Log.i(TAG, "API key updated")
    }

    /**
     * 获取服务器端口
     */
    fun getPort(): Int = serverPort

    /**
     * 设置服务器端口
     */
    fun setPort(newPort: Int) {
        serverPort = newPort
        prefs.edit().putInt(KEY_PORT, newPort).apply()
        Log.i(TAG, "API port updated to $newPort")
    }

    /**
     * 生成随机 API Key
     */
    private fun generateApiKey(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..32).map { chars.random() }.joinToString("")
    }

    /**
     * 处理 HTTP 请求
     */
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        val params = session.parms

        Log.d(TAG, "API request: $method $uri")

        // API Key 认证（/api/status 不需要认证）
        if (uri != "/api/status") {
            val keyFromHeader = session.headers["x-api-key"] ?: session.headers["X-API-Key"]
            val keyFromParam = params["api_key"]
            val providedKey = keyFromHeader ?: keyFromParam

            if (providedKey != apiKey) {
                return jsonResponse(401, mapOf(
                    "success" to false,
                    "error" to "Unauthorized: Invalid API key"
                ))
            }
        }

        // 路由分发
        return try {
            when {
                // 状态信息
                uri == "/api/status" -> handleStatus()

                // 地图控制
                uri == "/api/map/zoom/in" && method == Method.POST -> {
                    handler.onMapZoomIn()
                    jsonResponse(200, mapOf("success" to true, "action" to "zoom_in"))
                }
                uri == "/api/map/zoom/out" && method == Method.POST -> {
                    handler.onMapZoomOut()
                    jsonResponse(200, mapOf("success" to true, "action" to "zoom_out"))
                }
                uri == "/api/map/zoom/reset" && method == Method.POST -> {
                    handler.onMapZoomReset()
                    jsonResponse(200, mapOf("success" to true, "action" to "zoom_reset"))
                }

                // HUD 控制
                uri == "/api/hud/mirror/toggle" && method == Method.POST -> {
                    handler.onMirrorToggle()
                    jsonResponse(200, mapOf("success" to true, "action" to "mirror_toggle"))
                }
                uri == "/api/hud/mirror/on" && method == Method.POST -> {
                    handler.onMirrorOn()
                    jsonResponse(200, mapOf("success" to true, "action" to "mirror_on"))
                }
                uri == "/api/hud/mirror/off" && method == Method.POST -> {
                    handler.onMirrorOff()
                    jsonResponse(200, mapOf("success" to true, "action" to "mirror_off"))
                }

                // 导航
                uri == "/api/navigation/route" && method == Method.POST -> {
                    handleNavigate(params)
                }
                uri == "/api/navigation/cancel" && method == Method.POST -> {
                    handler.onNavigateCancel()
                    jsonResponse(200, mapOf("success" to true, "action" to "navigation_cancel"))
                }

                // 音乐控制（占位）
                uri == "/api/music/play" && method == Method.POST -> {
                    handler.onMusicPlay()
                    jsonResponse(200, mapOf("success" to true, "action" to "music_play"))
                }
                uri == "/api/music/pause" && method == Method.POST -> {
                    handler.onMusicPause()
                    jsonResponse(200, mapOf("success" to true, "action" to "music_pause"))
                }
                uri == "/api/music/next" && method == Method.POST -> {
                    handler.onMusicNext()
                    jsonResponse(200, mapOf("success" to true, "action" to "music_next"))
                }
                uri == "/api/music/prev" && method == Method.POST -> {
                    handler.onMusicPrev()
                    jsonResponse(200, mapOf("success" to true, "action" to "music_prev"))
                }

                else -> jsonResponse(404, mapOf("success" to false, "error" to "Not found"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "API error: ${e.message}", e)
            jsonResponse(500, mapOf("success" to false, "error" to "Internal error: ${e.message}"))
        }
    }

    /**
     * 处理状态查询
     */
    private fun handleStatus(): Response {
        val status = handler.onGetStatus()
        status.put("api_version", "1.0")
        status.put("endpoints", JSONArray().apply {
            put("/api/status")
            put("/api/map/zoom/in")
            put("/api/map/zoom/out")
            put("/api/map/zoom/reset")
            put("/api/hud/mirror/toggle")
            put("/api/hud/mirror/on")
            put("/api/hud/mirror/off")
            put("/api/navigation/route")
            put("/api/navigation/cancel")
            put("/api/music/play")
            put("/api/music/pause")
            put("/api/music/next")
            put("/api/music/prev")
        })
        return jsonResponse(200, status)
    }

    /**
     * 处理导航请求
     */
    private fun handleNavigate(params: Map<String, String>): Response {
        val lat = params["lat"]?.toDoubleOrNull()
        val lng = params["lng"]?.toDoubleOrNull()
        val name = params["name"] ?: "目的地"

        if (lat == null || lng == null) {
            return jsonResponse(400, mapOf(
                "success" to false,
                "error" to "Missing or invalid lat/lng parameters"
            ))
        }

        handler.onNavigateTo(lat, lng, name)
        return jsonResponse(200, mapOf(
            "success" to true,
            "action" to "navigate",
            "destination" to mapOf(
                "lat" to lat,
                "lng" to lng,
                "name" to name
            )
        ))
    }

    /**
     * 生成 JSON 响应
     */
    private fun jsonResponse(statusCode: Int, data: Map<String, Any?>): Response {
        val json = JSONObject()
        data.forEach { (key, value) ->
            when (value) {
                is Map<*, *> -> json.put(key, JSONObject(value as Map<String, Any>))
                is List<*> -> json.put(key, JSONArray(value))
                else -> json.put(key, value)
            }
        }
        return newFixedLengthResponse(
            Response.Status.lookup(statusCode) ?: Response.Status.INTERNAL_ERROR,
            "application/json",
            json.toString(2)
        )
    }
}
