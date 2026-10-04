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
    val rawText: String
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
    private val context: Context
) {
    companion object {
        private const val TAG = "IntentClassifier"
        private const val TIMEOUT = 10000

        // 系统提示词 — 让模型稳定输出 JSON
        private val SYSTEM_PROMPT = """
你是车载语音助手"哈德"。识别用户意图，仅输出JSON，不要其他文字。
格式：{"intent":"类型","action":"动作","params":{参数}}
类型：navigation(导航/找地点), search(搜索/查资料), hud_control(HUD设置/镜像), music(音乐/播放), chat(闲聊/问答)
示例：
"导航去加油站"→{"intent":"navigation","action":"search_poi","params":{"keyword":"加油站","sort":"nearest"}}
"搜一下明天天气"→{"intent":"search","action":"web_search","params":{"query":"明天天气"}}
"关镜像"→{"intent":"hud_control","action":"mirror_off","params":{}}
"播放音乐"→{"intent":"music","action":"play","params":{}}
"你好"→{"intent":"chat","action":"reply","params":{"text":"你好，我是哈德，有什么可以帮你的？"}}
        """.trimIndent()

        // ==================== 关键词规则（降级用） ====================
        private val NAV_KEYWORDS = listOf("导航", "去哪", "路线", "带路", "怎么走")
        private val POI_KEYWORDS = listOf("加油站", "停车场", "停车", "厕所", "洗手间", "医院",
            "餐厅", "饭店", "酒店", "超市", "商场", "银行", "充电桩", "洗车")
        private val SEARCH_KEYWORDS = listOf("搜索", "搜一下", "查一下", "帮我搜", "帮我查", "看看", "告诉我", "什么是")
        private val MIRROR_ON = listOf("开镜像", "打开镜像", "镜像开", "翻转", "打开翻转")
        private val MIRROR_OFF = listOf("关镜像", "关闭镜像", "镜像关", "镜像关闭", "关翻转")
        private val MIRROR_TOGGLE = listOf("切换镜像", "镜像切换", "翻转切换")
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
                    return result
                }
            } catch (e: Exception) {
                Log.w(TAG, "API classification failed: ${e.message}, falling back to keywords")
            }
        }

        // 降级：关键词匹配
        val result = classifyWithKeywords(userText)
        Log.i(TAG, "Keyword classified: ${result.intent}/${result.action}")
        return result
    }

    /**
     * 聊天模式（云端 API）
     */
    suspend fun chat(userText: String): String {
        if (!hasApiKey) return "抱歉，请先在设置中配置 AI 服务。"

        return withContext(Dispatchers.IO) {
            callApi(userText, jsonMode = false) ?: "抱歉，出了点问题。"
        }
    }

    fun release() {
        // 无需释放（HTTP 调用无状态）
    }

    // ==================== 智谱 API 调用 ====================

    private fun classifyWithApi(userText: String): IntentResult? {
        val prompt = "$SYSTEM_PROMPT\n用户：$userText\n输出："
        val response = callApi(prompt, jsonMode = true) ?: return null
        return parseResponse(response)
    }

    /**
     * 调用智谱 API（OpenAI 兼容格式）
     *
     * POST https://open.bigmodel.cn/api/paas/v4/chat/completions
     * Authorization: Bearer {apiKey}
     * {
     *   "model": "glm-4.7-flash",
     *   "messages": [...],
     *   "temperature": 0.1,
     *   "max_tokens": 256
     * }
     */
    private fun callApi(prompt: String, jsonMode: Boolean): String? {
        val url = URL("${SetupActivity.API_BASE_URL}/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT
        conn.readTimeout = TIMEOUT
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")

        val body = JSONObject().apply {
            put("model", modelId.ifBlank { "glm-4.7-flash" })
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", if (jsonMode) SYSTEM_PROMPT else "你是车载语音助手\"哈德\"，用简短自然的方式回答用户。")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("temperature", if (jsonMode) 0.1 else 0.7)
            put("max_tokens", 256)
            put("stream", false)
        }

        OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
            writer.write(body.toString())
            writer.flush()
        }

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            val errorBody = try {
                BufferedReader(InputStreamReader(conn.errorStream, "UTF-8")).use { it.readText() }
            } catch (e: Exception) { "" }
            Log.w(TAG, "API returned $responseCode: $errorBody")
            conn.disconnect()
            return null
        }

        val responseBody = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
        conn.disconnect()

        // 解析 OpenAI 兼容响应
        // {"choices":[{"message":{"content":"..."}}]}
        val json = JSONObject(responseBody)
        val choices = json.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        val message = choices.getJSONObject(0).optJSONObject("message") ?: return null
        val content = message.optString("content", "")

        Log.d(TAG, "API response: ${content.take(100)}...")
        return content
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

        // 导航
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
