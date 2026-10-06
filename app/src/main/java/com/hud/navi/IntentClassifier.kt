/*
 * IntentClassifier.kt - 智谱云端意图分类器 (v12.0)
 *
 * 调用智谱 AI API（OpenAI 兼容格式）进行语义理解。
 * 免费模型：glm-4.7-flash（完全免费，200K 上下文）
 *
 * 当 API 不可用或网络异常时，自动降级到关键词匹配。
 */

package com.hud.navi

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 意图分类结果
 */
data class IntentResult(
    val intent: String,
    val action: String,
    val params: Map<String, Any>,
    val rawText: String,
    val userInput: String = ""  // 原始用户输入（用于 Function Calling）
) {
    companion object {
        const val INTENT_NAVIGATION = "navigation"
        const val INTENT_SEARCH = "search"
        const val INTENT_HUD_CONTROL = "hud_control"
        const val INTENT_MUSIC = "music"
        const val INTENT_CHAT = "chat"
        const val INTENT_UNKNOWN = "unknown"

        fun fallback(text: String) = IntentResult(
            intent = INTENT_CHAT,
            action = "reply",
            params = mapOf("text" to text),
            rawText = "fallback"
        )
    }
}

class IntentClassifier(
    private val context: Context,
    private val webSearchClient: WebSearchClient? = null
) {
    companion object {
        private const val TAG = "IntentClassifier"
        private const val TIMEOUT = 15000  // 15s (function calling 需要更长时间)

        // 系统提示词 — 让模型稳定输出 JSON
        private val SYSTEM_PROMPT = """
你是车载语音助手"哈德"。识别用户意图，仅输出JSON，不要其他文字。
格式：{"intent":"类型","action":"动作","params":{参数}}
类型：navigation(导航/找地点), search(搜索/查资料), hud_control(HUD设置/镜像/地图缩放), music(音乐/播放), chat(闲聊/问答)
示例：
"导航去加油站"→{"intent":"navigation","action":"search_poi","params":{"keyword":"加油站","sort":"nearest"}}
"搜一下明天天气"→{"intent":"search","action":"web_search","params":{"query":"明天天气"}}
"关镜像"→{"intent":"hud_control","action":"mirror_off","params":{}}
"放大地图"→{"intent":"hud_control","action":"map_zoom_in","params":{}}
"缩小地图"→{"intent":"hud_control","action":"map_zoom_out","params":{}}
"重置缩放"→{"intent":"hud_control","action":"map_zoom_reset","params":{}}
"播放音乐"→{"intent":"music","action":"play","params":{}}
"你好"→{"intent":"chat","action":"reply","params":{"text":"你好，我是哈德，有什么可以帮你的？"}}
        """.trimIndent()

        // ==================== 关键词规则（降级用） ====================
        private val NAV_KEYWORDS = listOf("导航", "去哪", "路线", "带路", "怎么走")
        private val NAV_START = listOf("开始导航", "导航去", "导航到", "带我去", "去")
        private val NAV_CANCEL = listOf("取消导航", "停止导航", "关闭导航", "结束导航")
        private val POI_KEYWORDS = listOf("加油站", "停车场", "停车", "厕所", "洗手间", "医院",
            "餐厅", "饭店", "酒店", "超市", "商场", "银行", "充电桩", "洗车")
        private val SEARCH_KEYWORDS = listOf("搜索", "搜一下", "查一下", "帮我搜", "帮我查", "看看", "告诉我", "什么是")
        private val MIRROR_ON = listOf("开镜像", "打开镜像", "镜像开", "翻转", "打开翻转")
        private val MIRROR_OFF = listOf("关镜像", "关闭镜像", "镜像关", "镜像关闭", "关翻转")
        private val MIRROR_TOGGLE = listOf("切换镜像", "镜像切换", "翻转切换")

        // 地图缩放
        private val MAP_ZOOM_IN = listOf("放大地图", "放大", "拉近", "地图放大", "放大一点", "再放大")
        private val MAP_ZOOM_OUT = listOf("缩小地图", "缩小", "拉远", "地图缩小", "缩小一点", "再缩小")
        private val MAP_ZOOM_RESET = listOf("重置缩放", "恢复缩放", "缩放恢复", "默认缩放")

        private val MUSIC_PLAY = listOf("播放", "放歌", "放音乐", "来首歌", "听歌")
        private val MUSIC_PAUSE = listOf("暂停", "停一下", "停止播放")
        private val MUSIC_NEXT = listOf("下一首", "换一首", "跳过")
        private val MUSIC_PREV = listOf("上一首", "前一首")
    }

    // 从 SharedPreferences 读取配置
    private val prefs = context.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
    private val apiKey: String get() = prefs.getString(SetupActivity.KEY_API_KEY, "") ?: ""
    private val modelId: String get() = prefs.getString(SetupActivity.KEY_MODEL, "") ?: ""

    val hasApiKey: Boolean get() = apiKey.isNotBlank()
    val mode: String get() = if (hasApiKey) "Cloud(${modelId})" else "Keyword"

    // 当前位置（由 MainActivity 更新）
    var currentLocation: Pair<Double, Double>? = null

    /**
     * 检查引擎是否可用
     */
    fun isReady(): Boolean = hasApiKey

    /**
     * 分类用户输入意图
     * 优先使用智谱 API，失败则降级关键词匹配
     */
    suspend fun classify(userText: String): IntentResult {
        // 优先尝试云端 API
        if (hasApiKey) {
            try {
                val result = withContext(Dispatchers.IO) {
                    classifyWithApi(userText)
                }
                if (result != null) {
                    Log.i(TAG, "API classified: ${result.intent}/${result.action}")
                    return result.copy(userInput = userText)
                }
            } catch (e: Exception) {
                Log.w(TAG, "API classification failed: ${e.message}, falling back to keywords")
            }
        }

        // 降级：关键词匹配
        val result = classifyWithKeywords(userText)
        Log.i(TAG, "Keyword classified: ${result.intent}/${result.action}")
        return result.copy(userInput = userText)
    }

    /**
     * 聊天模式（云端 API + Function Calling 联网搜索）
     *
     * 模型自动判断是否需要联网搜索：
     * 1. 第一轮：发送用户消息 + tools 定义
     * 2. 模型可能返回 tool_calls（web_search）
     * 3. 执行搜索，结果回传
     * 4. 第二轮：模型结合搜索结果生成最终回复
     */
    suspend fun chat(userText: String): String {
        if (!hasApiKey) return "抱歉，请先在设置中配置 AI 服务。"

        return withContext(Dispatchers.IO) {
            chatWithFunctionCalling(userText) ?: "抱歉，出了点问题。"
        }
    }

    fun release() {
        // 无需释放（HTTP 调用无状态）
    }

    // Function Calling 工具执行回调（ChatEngine 注册此回调以触发 UI 操作）
    var onToolCall: ((toolName: String, args: Map<String, Any>) -> String)? = null

    // ==================== Function Calling 工具定义 ====================

    private fun simpleTool(name: String, description: String): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                })
            })
        }
    }

    private fun stringParamTool(name: String, description: String, paramName: String, paramDesc: String, required: Boolean = true): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put(paramName, JSONObject().apply {
                            put("type", "string")
                            put("description", paramDesc)
                        })
                    })
                    if (required) put("required", JSONArray().apply { put(paramName) })
                })
            })
        }
    }

    private fun buildTools(): JSONArray {
        return JSONArray().apply {
            // 联网搜索
            put(stringParamTool("web_search",
                "在互联网上搜索实时信息。当用户询问天气、新闻、股票、赛事、最新事件等需要实时数据的问题时调用。",
                "query", "搜索关键词，简洁准确，中文优先"))

            // 导航
            put(stringParamTool("navigate_to",
                "开始导航到指定地点。先用此工具查询地点坐标，然后开始导航。",
                "destination", "目的地名称，例如\"天安门\"、\"最近的加油站\""))
            put(simpleTool("cancel_navigation",
                "取消当前导航。当用户说\"取消导航\"、\"停止导航\"、\"不导航了\"时调用。"))
            put(simpleTool("get_navigation_status",
                "获取当前导航状态：是否正在导航、目的地、剩余距离和时间。"))

            // 地图缩放
            put(simpleTool("map_zoom_in", "放大地图/拉近视角。当用户说\"放大\"、\"拉近\"、\"放大地图\"时调用。"))
            put(simpleTool("map_zoom_out", "缩小地图/拉远视角。当用户说\"缩小\"、\"拉远\"、\"缩小地图\"时调用。"))
            put(simpleTool("map_zoom_reset", "重置地图缩放到默认级别。当用户说\"恢复缩放\"、\"重置缩放\"时调用。"))

            // HUD 镜像
            put(simpleTool("mirror_on", "开启HUD镜像翻转（挡风玻璃投影模式）。"))
            put(simpleTool("mirror_off", "关闭HUD镜像翻转。"))
            put(simpleTool("mirror_toggle", "切换HUD镜像状态（开→关 或 关→开）。"))

            // 车辆状态
            put(simpleTool("get_current_speed", "获取当前车速（km/h）和GPS定位信息。"))

            // 音乐
            put(stringParamTool("play_music", "播放音乐。", "query", "歌曲名或歌手名，留空则播放/恢复播放", required = false))
            put(simpleTool("pause_music", "暂停当前播放的音乐。"))
            put(simpleTool("next_track", "跳到下一首歌曲。"))
            put(simpleTool("prev_track", "跳到上一首歌曲。"))
        }
    }

    // ==================== Function Calling 主循环 ====================

    /**
     * 带 Function Calling 的聊天
     *
     * 流程：
     *   用户消息 → API(带tools) → 模型决定是否搜索
     *     ├─ 不需要搜索 → 直接返回回复
     *     └─ 需要搜索 → 执行 web_search → 结果回传 API → 最终回复
     */
    private fun chatWithFunctionCalling(userText: String): String? {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", """你是车载语音助手"哈德"。用简短、自然、口语化的方式回答用户。
你可以调用以下工具来帮助用户：
- web_search: 搜索互联网实时信息（天气、新闻、股票等）
- navigate_to: 开始导航到指定地点
- cancel_navigation: 取消当前导航
- get_navigation_status: 获取当前导航状态
- map_zoom_in/out/reset: 放大/缩小/重置地图缩放
- mirror_on/off/toggle: 开启/关闭/切换HUD镜像
- get_current_speed: 获取当前车速
- play_music/pause_music/next_track/prev_track: 音乐播放控制
回答要简洁，适合语音播报，控制在3-5句话以内。如果用户的请求需要操作车辆功能，调用对应工具后告知用户操作结果。""".trimIndent())
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userText)
            })
        }

        val tools = buildTools()

        // 第一轮：带工具发送
        // callApiRaw 返回的就是 message 对象（choices[0].message）
        val firstMessage = callApiRaw(messages, tools) ?: return null
        val toolCalls = firstMessage.optJSONArray("tool_calls")

        // 模型没有调用工具 → 直接返回文本
        if (toolCalls == null || toolCalls.length() == 0) {
            return firstMessage.optString("content", "")
        }

        // 模型调用了工具 → 执行工具 → 第二轮
        // 先把 assistant 的 tool_calls 消息加入历史
        // 注意：content 可能为 null（当 finish_reason 为 tool_calls 时）
        messages.put(JSONObject().apply {
            put("role", "assistant")
            put("content", firstMessage.optString("content", ""))
            put("tool_calls", toolCalls)
        })

        // 执行每个工具调用
        for (i in 0 until toolCalls.length()) {
            val tc = toolCalls.getJSONObject(i)
            val funcName = tc.getJSONObject("function").getString("name")
            val funcArgs = tc.getJSONObject("function").getString("arguments")
            val toolCallId = tc.getString("id")

            val resultJson = when (funcName) {
                "web_search" -> executeWebSearch(funcArgs)
                "navigate_to" -> executeToolWithCallback(funcName, funcArgs)
                "cancel_navigation" -> executeToolWithCallback(funcName, funcArgs)
                "get_navigation_status" -> executeToolWithCallback(funcName, funcArgs)
                "map_zoom_in" -> executeToolWithCallback(funcName, funcArgs)
                "map_zoom_out" -> executeToolWithCallback(funcName, funcArgs)
                "map_zoom_reset" -> executeToolWithCallback(funcName, funcArgs)
                "mirror_on" -> executeToolWithCallback(funcName, funcArgs)
                "mirror_off" -> executeToolWithCallback(funcName, funcArgs)
                "mirror_toggle" -> executeToolWithCallback(funcName, funcArgs)
                "get_current_speed" -> executeToolWithCallback(funcName, funcArgs)
                "play_music" -> executeToolWithCallback(funcName, funcArgs)
                "pause_music" -> executeToolWithCallback(funcName, funcArgs)
                "next_track" -> executeToolWithCallback(funcName, funcArgs)
                "prev_track" -> executeToolWithCallback(funcName, funcArgs)
                else -> """{"error":"未知工具: $funcName"}"""
            }

            messages.put(JSONObject().apply {
                put("role", "tool")
                put("tool_call_id", toolCallId)
                put("content", resultJson)
            })
        }

        // 第二轮：带工具结果，获取最终回复
        val finalMessage = callApiRaw(messages, tools) ?: return null
        return finalMessage.optString("content", "")
    }

    /**
     * 执行 web_search 工具调用
     */
    private fun executeWebSearch(argsJson: String): String {
        try {
            val args = JSONObject(argsJson)
            val query = args.optString("query", "").trim()
            if (query.isBlank()) return """{"error":"搜索关键词为空"}"""

            Log.i(TAG, "Tool call: web_search($query)")
            val result = webSearchClient?.search(query, 5)
                ?: return """{"error":"搜索引擎不可用"}"""

            return JSONObject().apply {
                put("query", result.query)
                put("engine", result.engine)
                put("success", result.success)
                put("results", JSONArray().apply {
                    result.results.take(5).forEach { r ->
                        put(JSONObject().apply {
                            put("title", r.title)
                            put("url", r.url)
                            put("snippet", r.snippet)
                        })
                    }
                })
            }.toString()
        } catch (e: Exception) {
            Log.e(TAG, "web_search failed: ${e.message}")
            return """{"error":"搜索执行失败: ${e.message}"}"""
        }
    }

    /**
     * 执行本地工具调用（通过 onToolCall 回调触发 UI 操作）
     *
     * 所有 HUD 控制工具（导航、缩放、镜像、音乐、速度）都通过此函数执行。
     * ChatEngine 注册 onToolCall 回调来处理实际的 UI 操作。
     *
     * @param toolName 工具名称
     * @param argsJson 参数 JSON 字符串
     * @return 执行结果 JSON（返回给 API 第二轮）
     */
    private fun executeToolWithCallback(toolName: String, argsJson: String): String {
        try {
            val args = try {
                val json = JSONObject(argsJson)
                val map = mutableMapOf<String, Any>()
                json.keys().forEach { key -> map[key] = json.get(key) }
                map
            } catch (e: Exception) {
                emptyMap<String, Any>()
            }

            Log.i(TAG, "Tool call: $toolName(${args})")

            // 通过回调执行实际操作（ChatEngine 处理 UI 变更）
            val result = onToolCall?.invoke(toolName, args)
                ?: """{"status":"ok","tool":"$toolName","message":"已执行"}"""

            return result
        } catch (e: Exception) {
            Log.e(TAG, "Tool $toolName failed: ${e.message}")
            return """{"error":"工具执行失败: ${e.message}"}"""
        }
    }

    // ==================== 智谱 API 调用 ====================

    private fun classifyWithApi(userText: String): IntentResult? {
        val prompt = "$SYSTEM_PROMPT\n用户：$userText\n输出："
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", SYSTEM_PROMPT)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }
        // callApiRaw 返回的就是 message 对象（choices[0].message）
        val message = callApiRaw(messages, null, jsonMode = true) ?: return null
        val content = message.optString("content", "")
        if (content.isBlank()) return null
        return parseResponse(content)
    }

    /**
     * 调用智谱 API（OpenAI 兼容格式）— 支持 Function Calling
     *
     * POST https://open.bigmodel.cn/api/paas/v4/chat/completions
     *
     * @param messages 完整消息列表（system + user + tool messages）
     * @param tools    工具定义列表（null 表示不使用工具）
     * @param jsonMode 是否强制 JSON 输出（用于意图分类）
     * @return 响应中的 message 对象（含 content 和/或 tool_calls）
     */
    private fun callApiRaw(messages: JSONArray, tools: JSONArray?, jsonMode: Boolean = false): JSONObject? {
        val url = URL("${SetupActivity.API_BASE_URL}/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT
        conn.readTimeout = TIMEOUT
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")

        val model = modelId.ifBlank { "glm-4.7-flash" }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("max_tokens", if (jsonMode) 256 else 512)
            put("stream", false)

            // 智谱 GLM 系列模型需要 thinking 参数
            // GLM-5.3 强制要求 thinking.type="enabled"
            // GLM-4.7 默认强制思考，但显式传更稳妥
            // jsonMode（意图分类）用 low 减少延迟，聊天用 max
            val isGlm5 = model.contains("5") || model.contains("glm-5")
            if (isGlm5 || model.contains("glm-4.7") || model.contains("4.7")) {
                put("thinking", JSONObject().apply {
                    put("type", "enabled")
                })
                put("reasoning_effort", if (jsonMode) "low" else "max")
            }

            // temperature: 智谱 GLM-5.x 默认 1.0，jsonMode 用低温度稳定输出
            // 注意：GLM-5.x 开了 thinking 后 temperature 对思考过程无效，只影响最终输出
            put("temperature", if (jsonMode) 0.1 else 0.7)

            if (tools != null && tools.length() > 0) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
        }

        Log.d(TAG, "API request: model=$model, tools=${tools?.length() ?: 0}, jsonMode=$jsonMode")

        OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
            writer.write(body.toString())
            writer.flush()
        }

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            val errorBody = try {
                BufferedReader(InputStreamReader(conn.errorStream, "UTF-8")).use { it.readText() }
            } catch (e: Exception) { "" }
            Log.w(TAG, "API returned $responseCode: ${errorBody.take(300)}")
            conn.disconnect()
            return null
        }

        val responseBody = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
        conn.disconnect()

        // 解析智谱 API 响应
        // 响应格式（OpenAI 兼容）：
        // {
        //   "id": "...",
        //   "choices": [{
        //     "index": 0,
        //     "message": {
        //       "role": "assistant",
        //       "content": "回复内容（可能为 null）",
        //       "reasoning_content": "思考过程（GLM-4.7/5.x 开启思考时存在）",
        //       "tool_calls": [{"id": "...", "type": "function", "function": {"name": "...", "arguments": "..."}}]
        //     },
        //     "finish_reason": "stop" | "length" | "tool_calls" | "sensitive"
        //   }],
        //   "usage": {"prompt_tokens": N, "completion_tokens": N, "total_tokens": N}
        // }
        val json = JSONObject(responseBody)
        val choices = json.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        val choice = choices.getJSONObject(0)
        val finishReason = choice.optString("finish_reason", "")
        val message = choice.optJSONObject("message") ?: return null

        // 日志记录（区分 reasoning_content 和 content）
        val reasoningContent = message.optString("reasoning_content", "")
        val content = message.optString("content", "")
        val toolCalls = message.optJSONArray("tool_calls")
        val usage = json.optJSONObject("usage")

        Log.d(TAG, "API response: finish=$finishReason, " +
                "tool_calls=${toolCalls?.length() ?: 0}, " +
                "content=${content.take(80)}" +
                if (reasoningContent.isNotBlank()) ", reasoning=${reasoningContent.take(50)}..." else "" +
                if (usage != null) ", tokens=${usage.optInt("total_tokens", 0)}" else "")

        return message
    }

    /**
     * 解析模型输出的 JSON
     */
    private fun parseResponse(response: String): IntentResult? {
        val trimmed = response.trim()

        // 策略1：直接解析
        try {
            return extractIntent(JSONObject(trimmed), trimmed)
        } catch (_: Exception) {}

        // 策略2：提取 JSON 块
        val jsonBlock = extractJsonBlock(trimmed)
        if (jsonBlock != null) {
            try {
                return extractIntent(JSONObject(jsonBlock), trimmed)
            } catch (_: Exception) {}
        }

        // 策略3：正则提取
        val intentMatch = Regex("\"intent\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)
        val actionMatch = Regex("\"action\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)
        if (intentMatch != null) {
            val intent = intentMatch.groupValues[1]
            val action = actionMatch?.groupValues?.get(1) ?: "unknown"
            val params = mutableMapOf<String, Any>()
            Regex("\"keyword\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)?.let { params["keyword"] = it.groupValues[1] }
            Regex("\"query\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)?.let { params["query"] = it.groupValues[1] }
            Regex("\"text\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)?.let { params["text"] = it.groupValues[1] }
            return IntentResult(intent, action, params, trimmed)
        }

        Log.w(TAG, "Failed to parse response: ${trimmed.take(100)}")
        return null
    }

    private fun extractJsonBlock(text: String): String? {
        Regex("```(?:json)?\\s*\\n?(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL).find(text)?.let {
            return it.groupValues[1].trim()
        }
        val first = text.indexOf('{')
        val last = text.lastIndexOf('}')
        if (first >= 0 && last > first) return text.substring(first, last + 1)
        return null
    }

    private fun extractIntent(json: JSONObject, rawText: String): IntentResult {
        val intent = json.optString("intent", IntentResult.INTENT_UNKNOWN)
        val action = json.optString("action", "unknown")
        val params = mutableMapOf<String, Any>()
        json.optJSONObject("params")?.let { obj ->
            val keys = obj.keys()
            while (keys.hasNext()) { val k = keys.next(); params[k] = obj.get(k) }
        }
        return IntentResult(intent, action, params, rawText)
    }

    // ==================== 关键词匹配（降级方案） ====================

    private fun classifyWithKeywords(userText: String): IntentResult {
        val text = userText.lowercase().trim()

        // HUD 控制
        if (MIRROR_OFF.any { text.contains(it) })
            return IntentResult(IntentResult.INTENT_HUD_CONTROL, "mirror_off", emptyMap(), userText)
        if (MIRROR_ON.any { text.contains(it) })
            return IntentResult(IntentResult.INTENT_HUD_CONTROL, "mirror_on", emptyMap(), userText)
        if (MIRROR_TOGGLE.any { text.contains(it) })
            return IntentResult(IntentResult.INTENT_HUD_CONTROL, "mirror_toggle", emptyMap(), userText)

        // 地图缩放
        if (MAP_ZOOM_RESET.any { text.contains(it) })
            return IntentResult(IntentResult.INTENT_HUD_CONTROL, "map_zoom_reset", emptyMap(), userText)
        if (MAP_ZOOM_IN.any { text.contains(it) })
            return IntentResult(IntentResult.INTENT_HUD_CONTROL, "map_zoom_in", emptyMap(), userText)
        if (MAP_ZOOM_OUT.any { text.contains(it) })
            return IntentResult(IntentResult.INTENT_HUD_CONTROL, "map_zoom_out", emptyMap(), userText)

        // 导航取消（优先匹配）
        if (NAV_CANCEL.any { text.contains(it) }) {
            return IntentResult(IntentResult.INTENT_NAVIGATION, "cancel", emptyMap(), userText)
        }

        // 导航开始（带目的地）
        if (NAV_START.any { text.contains(it) }) {
            val keyword = extractDestination(text)
            return IntentResult(IntentResult.INTENT_NAVIGATION, "navigate_to",
                buildMap { if (keyword.isNotBlank()) put("destination", keyword) }, userText)
        }

        // 导航（POI 搜索）
        if (NAV_KEYWORDS.any { text.contains(it) }) {
            val keyword = extractDestination(text)
            val sort = if (text.contains("最近") || text.contains("附近") || text.contains("近")) "nearest" else ""
            return IntentResult(IntentResult.INTENT_NAVIGATION, "search_poi",
                buildMap {
                    if (keyword.isNotBlank()) put("keyword", keyword)
                    if (sort.isNotBlank()) put("sort", sort)
                }, userText)
        }
        for (poi in POI_KEYWORDS) {
            if (text.contains(poi)) {
                val sort = if (text.contains("最近") || text.contains("附近") || text.contains("近")) "nearest" else ""
                return IntentResult(IntentResult.INTENT_NAVIGATION, "search_poi",
                    buildMap { put("keyword", poi); if (sort.isNotBlank()) put("sort", sort) }, userText)
            }
        }

        // 搜索
        for (kw in SEARCH_KEYWORDS) {
            if (text.contains(kw)) {
                val query = text.substringAfter(kw).trim().trimEnd('？', '?', '。', '.', '！', '!')
                return IntentResult(IntentResult.INTENT_SEARCH, "web_search",
                    mapOf("query" to query.ifBlank { userText }), userText)
            }
        }

        // 音乐
        if (MUSIC_PAUSE.any { text.contains(it) })
            return IntentResult(IntentResult.INTENT_MUSIC, "pause", emptyMap(), userText)
        if (MUSIC_NEXT.any { text.contains(it) })
            return IntentResult(IntentResult.INTENT_MUSIC, "next", emptyMap(), userText)
        if (MUSIC_PREV.any { text.contains(it) })
            return IntentResult(IntentResult.INTENT_MUSIC, "previous", emptyMap(), userText)
        if (MUSIC_PLAY.any { text.contains(it) })
            return IntentResult(IntentResult.INTENT_MUSIC, "play", emptyMap(), userText)

        return IntentResult.fallback(userText)
    }

    private fun extractDestination(text: String): String {
        var dest = text
        for (kw in NAV_KEYWORDS) dest = dest.replace(kw, "")
        for (m in listOf("最近的", "附近的", "去", "到", "个", "一个")) dest = dest.replace(m, "")
        return dest.trim().trimEnd('？', '?', '。', '.', '！', '!', '吧', '啊')
    }
}
