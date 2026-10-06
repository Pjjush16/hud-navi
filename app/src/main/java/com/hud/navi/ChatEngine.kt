/*
 * ChatEngine.kt - 聊天引擎 + TTS 语音回复 (v11.0)
 *
 * 当 IntentClassifier 判定 intent=chat 时，使用 LLM 生成回复并通过 TTS 播报。
 * 当 intent=search 时，调用 WebSearchClient 搜索并摘要回复。
 */

package com.hud.navi

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class ChatEngine(
    private val context: Context,
    private val intentClassifier: IntentClassifier,
    private val webSearchClient: WebSearchClient,
    private val osrmRouter: OsrmRouter? = null,
    private val apiServer: ApiServer? = null
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "ChatEngine"
        private const val MAX_TTS_LENGTH = 200  // TTS 最大朗读字符数（避免太长）
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var onTtsDone: (() -> Unit)? = null

    // 导航状态
    var currentDestination: String? = null
        private set
    var currentRoute: RouteResult? = null
        private set

    // Function Calling 工具执行的 UI 回调（由 MainActivity 设置）
    var toolUiCallback: ((String) -> Unit)? = null

    /**
     * 初始化 TTS 引擎 + 注册 Function Calling 工具回调
     */
    fun init() {
        // 注册工具回调：当 AI 模型调用工具时，ChatEngine 执行实际操作
        intentClassifier.onToolCall = { toolName, args ->
            handleToolCall(toolName, args)
        }

        try {
            tts = TextToSpeech(context, this)
            Log.i(TAG, "TTS engine initializing...")
        } catch (e: Exception) {
            Log.e(TAG, "TTS init failed: ${e.message}", e)
        }
    }

    /**
     * 处理 Function Calling 工具调用
     * 由 IntentClassifier 的 onToolCall 回调触发
     *
     * @return JSON 字符串，返回给 API 作为工具执行结果
     */
    private fun handleToolCall(toolName: String, args: Map<String, Any>): String {
        Log.i(TAG, "Handling tool call: $toolName($args)")

        return when (toolName) {
            // === 导航 ===
            "navigate_to" -> {
                val destination = args["destination"] as? String ?: ""
                if (destination.isBlank()) {
                    """{"error":"缺少目的地参数"}"""
                } else {
                    // 异步启动导航，立即返回状态
                    val intent = IntentResult(
                        IntentResult.INTENT_NAVIGATION, "navigate_to",
                        mapOf("destination" to destination), toolName, destination
                    )
                    handleNavigation(intent, toolUiCallback)
                    """{"status":"navigating","destination":"$destination"}"""
                }
            }
            "cancel_navigation" -> {
                currentDestination = null
                currentRoute = null
                speak("已取消导航")
                toolUiCallback?.invoke("NAVIGATION_CANCEL")
                """{"status":"cancelled","message":"导航已取消"}"""
            }
            "get_navigation_status" -> {
                val dest = currentDestination ?: "无"
                val route = currentRoute
                val dist = if (route != null) "${(route.distance / 1000).toInt()}公里" else "无"
                val dur = if (route != null) "${(route.duration / 60).toInt()}分钟" else "无"
                """{"navigating":${currentDestination != null},"destination":"$dest","distance":"$dist","eta":"$dur"}"""
            }

            // === 地图缩放 ===
            "map_zoom_in" -> {
                toolUiCallback?.invoke("MAP_ZOOM_IN")
                speak("已放大地图")
                """{"status":"ok","action":"zoom_in","message":"地图已放大"}"""
            }
            "map_zoom_out" -> {
                toolUiCallback?.invoke("MAP_ZOOM_OUT")
                speak("已缩小地图")
                """{"status":"ok","action":"zoom_out","message":"地图已缩小"}"""
            }
            "map_zoom_reset" -> {
                toolUiCallback?.invoke("MAP_ZOOM_RESET")
                speak("已恢复默认缩放")
                """{"status":"ok","action":"zoom_reset","message":"缩放已重置"}"""
            }

            // === HUD 镜像 ===
            "mirror_on" -> {
                toolUiCallback?.invoke("MIRROR_ON")
                speak("已开启镜像")
                """{"status":"ok","action":"mirror_on","message":"HUD镜像已开启"}"""
            }
            "mirror_off" -> {
                toolUiCallback?.invoke("MIRROR_OFF")
                speak("已关闭镜像")
                """{"status":"ok","action":"mirror_off","message":"HUD镜像已关闭"}"""
            }
            "mirror_toggle" -> {
                toolUiCallback?.invoke("MIRROR_TOGGLE")
                speak("已切换镜像")
                """{"status":"ok","action":"mirror_toggle","message":"HUD镜像已切换"}"""
            }

            // === 车辆状态 ===
            "get_current_speed" -> {
                val location = intentClassifier.currentLocation
                val lat = location?.first ?: 0.0
                val lng = location?.second ?: 0.0
                // 速度由 MainActivity 通过 toolUiCallback 传入
                toolUiCallback?.invoke("GET_SPEED")
                """{"lat":$lat,"lng":$lng,"message":"当前定位已获取"}"""
            }

            // === 音乐 ===
            "play_music" -> {
                val query = args["query"] as? String ?: ""
                toolUiCallback?.invoke("MUSIC_PLAY|$query")
                speak(if (query.isNotBlank()) "正在播放$query" else "正在播放音乐")
                """{"status":"ok","action":"play","query":"$query"}"""
            }
            "pause_music" -> {
                toolUiCallback?.invoke("MUSIC_PAUSE")
                speak("已暂停")
                """{"status":"ok","action":"pause"}"""
            }
            "next_track" -> {
                toolUiCallback?.invoke("MUSIC_NEXT")
                speak("下一首")
                """{"status":"ok","action":"next"}"""
            }
            "prev_track" -> {
                toolUiCallback?.invoke("MUSIC_PREV")
                speak("上一首")
                """{"status":"ok","action":"previous"}"""
            }

            else -> """{"error":"未知工具: $toolName"}"""
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.CHINESE)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Chinese TTS not available, trying default")
                tts?.setLanguage(Locale.getDefault())
            }
            tts?.setSpeechRate(1.1f)  // 稍微加快语速
            tts?.setPitch(1.0f)

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "TTS done: $utteranceId")
                    onTtsDone?.invoke()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {}
            })

            ttsReady = true
            Log.i(TAG, "TTS engine ready")
        } else {
            Log.e(TAG, "TTS init failed with status: $status")
        }
    }

    /**
     * 处理意图结果 — 根据 intent 类型执行对应动作
     *
     * @param result IntentClassifier 输出的意图
     * @param onUiCallback UI 更新回调（显示结果到 HUD）
     */
    fun handleIntent(result: IntentResult, onUiCallback: ((String) -> Unit)? = null) {
        handleIntentInternal(result, onUiCallback)
    }

    private fun handleIntentInternal(result: IntentResult, onUiCallback: ((String) -> Unit)? = null) {
        Log.i(TAG, "Handling intent: ${result.intent}/${result.action}")

        when (result.intent) {
            IntentResult.INTENT_NAVIGATION -> handleNavigation(result, onUiCallback)
            IntentResult.INTENT_SEARCH -> handleSearch(result, onUiCallback)
            IntentResult.INTENT_HUD_CONTROL -> handleHudControl(result, onUiCallback)
            IntentResult.INTENT_MUSIC -> handleMusic(result, onUiCallback)
            IntentResult.INTENT_CHAT -> handleChat(result, onUiCallback)
            else -> {
                speak("抱歉，我不太理解你的意思。")
                onUiCallback?.invoke("未识别的指令")
            }
        }
    }

    // ==================== 意图处理器 ====================

    /**
     * 导航意图 — 搜索 POI / 开始导航 / 取消导航
     */
    private fun handleNavigation(result: IntentResult, callback: ((String) -> Unit)?) {
        val action = result.action

        when (action) {
            "cancel" -> {
                currentDestination = null
                currentRoute = null
                speak("已取消导航")
                callback?.invoke("NAVIGATION_CANCEL")
            }
            "navigate_to" -> {
                val destination = result.params["destination"] as? String ?: ""
                if (destination.isBlank()) {
                    speak("请告诉我你要去哪里？")
                    callback?.invoke("请说出目的地")
                    return
                }
                startNavigation(destination, callback)
            }
            "search_poi" -> {
                val keyword = result.params["keyword"] as? String ?: ""
                if (keyword.isNotBlank()) {
                    val sort = result.params["sort"] as? String ?: ""
                    val response = if (sort == "nearest") {
                        "好的，正在搜索附近的$keyword"
                    } else {
                        "好的，正在搜索$keyword"
                    }
                    speak(response)
                    callback?.invoke("🔍 搜索: $keyword")

                    // 通过 WebSearch 搜索 POI
                    Thread {
                        val searchResult = webSearchClient.search("附近 $keyword")
                        if (searchResult.success) {
                            val summary = searchResult.toSummary(2)
                            speak(summary)
                            callback?.invoke(summary.take(80))
                        }
                    }.start()
                } else {
                    speak("请告诉我要搜索什么地点？")
                    callback?.invoke("请说出目的地")
                }
            }
            else -> {
                speak("好的，导航功能已就绪")
                callback?.invoke("导航: 就绪")
            }
        }
    }

    /**
     * 开始导航到目的地
     */
    private fun startNavigation(destination: String, callback: ((String) -> Unit)?) {
        speak("正在规划到${destination}的路线...")
        callback?.invoke("🗺️ 规划路线: $destination")

        Thread {
            try {
                // 首先通过 WebSearch 获取目的地的经纬度
                val locationInfo = geocodeDestination(destination)
                if (locationInfo == null) {
                    speak("抱歉，无法找到${destination}的位置")
                    callback?.invoke("未找到: $destination")
                    return@Thread
                }

                // 获取当前位置
                val currentLocation = intentClassifier.currentLocation
                if (currentLocation == null) {
                    speak("无法获取当前位置，请确保 GPS 已开启")
                    callback?.invoke("GPS 未就绪")
                    return@Thread
                }

                // 使用 OSRM 规划路线
                val route = osrmRouter?.route(currentLocation, locationInfo.first)
                if (route == null || !route.success) {
                    speak("路线规划失败，请稍后再试")
                    callback?.invoke("路线规划失败")
                    return@Thread
                }

                // 保存导航状态
                currentDestination = destination
                currentRoute = route

                // 播报导航信息
                val distanceStr = osrmRouter?.formatDistance(route.distance) ?: "${(route.distance/1000).toInt()}公里"
                val durationStr = osrmRouter?.formatDuration(route.duration) ?: "${(route.duration/60).toInt()}分钟"
                val summary = "路线规划完成，全程${distanceStr}，预计${durationStr}"
                speak(summary)
                callback?.invoke("NAVIGATION_START|$destination|$distanceStr|$durationStr")

                // 播报前几个导航步骤
                if (route.steps.isNotEmpty()) {
                    val firstSteps = route.steps.take(3).map { it.instruction }.joinToString("，然后")
                    speak("首先${firstSteps}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Navigation failed: ${e.message}", e)
                speak("导航启动失败")
                callback?.invoke("导航失败")
            }
        }.start()
    }

    /**
     * 地理编码：将地名转换为经纬度
     * 使用 Nominatim (OpenStreetMap) API
     */
    private fun geocodeDestination(query: String): Pair<Pair<Double, Double>, String>? {
        try {
            val url = java.net.URL("https://nominatim.openstreetmap.org/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&format=json&limit=1")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "hud-navi/1.0")

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                conn.disconnect()
                return null
            }

            val responseBody = java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            conn.disconnect()

            val json = org.json.JSONArray(responseBody)
            if (json.length() == 0) return null

            val place = json.getJSONObject(0)
            val lat = place.getDouble("lat")
            val lon = place.getDouble("lon")
            val displayName = place.optString("display_name", query)

            return Pair(Pair(lat, lon), displayName)

        } catch (e: Exception) {
            Log.e(TAG, "Geocoding failed: ${e.message}", e)
            return null
        }
    }

    /**
     * 搜索意图 — 联网搜索并播报结果
     */
    private fun handleSearch(result: IntentResult, callback: ((String) -> Unit)?) {
        val query = result.params["query"] as? String
            ?: result.params["keyword"] as? String
            ?: ""

        if (query.isBlank()) {
            speak("请告诉我要搜索什么？")
            callback?.invoke("请说出搜索内容")
            return
        }

        speak("正在搜索$query")
        callback?.invoke("🔍 搜索: $query")

        // 在后台线程执行网络搜索
        Thread {
            val searchResult = webSearchClient.search(query)

            if (searchResult.success) {
                // 将搜索结果摘要播报
                val summary = searchResult.toSummary(3)
                val ttsText = truncateForTts(summary)
                speak(ttsText)
                callback?.invoke(ttsText.take(80) + "...")
            } else {
                speak("抱歉，搜索失败，请稍后重试。")
                callback?.invoke("搜索失败")
            }
        }.start()
    }

    /**
     * HUD 控制意图 — 镜像、亮度等
     */
    private fun handleHudControl(result: IntentResult, callback: ((String) -> Unit)?) {
        when (result.action) {
            "mirror_toggle", "mirror_on" -> {
                speak("好的，已切换镜像模式")
                callback?.invoke("MIRROR_TOGGLE")
            }
            "mirror_off" -> {
                speak("好的，已关闭镜像")
                callback?.invoke("MIRROR_OFF")
            }
            "map_zoom_in" -> {
                speak("已放大地图")
                callback?.invoke("MAP_ZOOM_IN")
            }
            "map_zoom_out" -> {
                speak("已缩小地图")
                callback?.invoke("MAP_ZOOM_OUT")
            }
            "map_zoom_reset" -> {
                speak("已恢复默认缩放")
                callback?.invoke("MAP_ZOOM_RESET")
            }
            "brightness_up" -> {
                speak("好的，已提高亮度")
                callback?.invoke("BRIGHTNESS_UP")
            }
            "brightness_down" -> {
                speak("好的，已降低亮度")
                callback?.invoke("BRIGHTNESS_DOWN")
            }
            else -> {
                speak("好的，已调整 HUD 设置")
                callback?.invoke("HUD 设置已更新")
            }
        }
    }

    /**
     * 音乐意图 — 播放/暂停/下一首
     */
    private fun handleMusic(result: IntentResult, callback: ((String) -> Unit)?) {
        when (result.action) {
            "play" -> {
                val query = result.params["query"] as? String ?: ""
                if (query.isNotBlank()) {
                    speak("正在播放$query")
                    callback?.invoke("▶ $query")
                } else {
                    speak("正在播放音乐")
                    callback?.invoke("▶ 播放")
                }
                // TODO: 对接系统媒体控制
            }
            "pause" -> {
                speak("已暂停")
                callback?.invoke("⏸ 暂停")
            }
            "next" -> {
                speak("下一首")
                callback?.invoke("⏭ 下一首")
            }
            "previous" -> {
                speak("上一首")
                callback?.invoke("⏮ 上一首")
            }
            else -> {
                speak("好的")
                callback?.invoke("音乐控制")
            }
        }
    }

    /**
     * 聊天意图 — 自由对话，LLM 生成回复 + TTS 播报
     * 支持 Function Calling：模型可自动调用 web_search 联网搜索
     */
    private fun handleChat(result: IntentResult, callback: ((String) -> Unit)?) {
        // 如果模型已经生成了回复文本（分类阶段直接给出），直接播报
        // 防回读：如果 text 等于用户原话（userInput/rawText），说明是降级回退的残留，跳过直接走 AI
        val text = result.params["text"] as? String
        val isEcho = text != null && (text == result.userInput || text == result.rawText)
        if (!text.isNullOrBlank() && !isEcho) {
            speak(truncateForTts(text))
            callback?.invoke(text)  // UI 显示完整文本
            return
        }

        // 用 Function Calling 模式生成回复（云端 API + 联网搜索工具）
        callback?.invoke("让我想想...")
        speak("让我想想...")
        Thread {
            val reply = kotlinx.coroutines.runBlocking {
                // 使用原始用户输入（而非 API 响应）
                intentClassifier.chat(result.userInput.ifBlank { result.rawText })
            }
            speak(truncateForTts(reply))
            callback?.invoke(reply)  // UI 显示完整回复
        }.start()
    }

    // ==================== TTS 控制 ====================

    /**
     * 播报文本
     */
    fun speak(text: String) {
        if (!ttsReady || text.isBlank()) {
            Log.w(TAG, "TTS not ready or empty text")
            return
        }

        val truncated = truncateForTts(text)
        tts?.speak(truncated, TextToSpeech.QUEUE_ADD, null, "hud_${System.currentTimeMillis()}")
        Log.i(TAG, "Speaking: ${truncated.take(50)}...")
    }

    /**
     * 立即停止播报
     */
    fun stopSpeaking() {
        tts?.stop()
    }

    /**
     * 设置 TTS 完成回调
     */
    fun setOnTtsDoneListener(listener: (() -> Unit)?) {
        onTtsDone = listener
    }

    fun isTtsReady(): Boolean = ttsReady

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    // ==================== 工具方法 ====================

    /**
     * 截断文本以适应 TTS 长度限制
     * 避免播报过长的搜索结果
     */
    private fun truncateForTts(text: String): String {
        if (text.length <= MAX_TTS_LENGTH) return text
        // 在句号处截断
        val truncated = text.take(MAX_TTS_LENGTH)
        val lastPeriod = truncated.lastIndexOf('。')
        return if (lastPeriod > MAX_TTS_LENGTH / 2) {
            truncated.take(lastPeriod + 1)
        } else {
            truncated + "。"
        }
    }
}
