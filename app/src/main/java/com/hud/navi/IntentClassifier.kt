/*
 * IntentClassifier.kt - 意图分类器 (v11.3 双版本)
 *
 * full 版本：通过 MNN-LLM 原生引擎调用本地 Qwen2.5-0.5B 语义理解
 * lite 版本：通过关键词匹配识别固定指令
 *
 * 由 BuildConfig.IS_LLM_ENABLED 控制使用哪种模式
 */

package com.hud.navi

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 意图分类结果
 */
data class IntentResult(
    val intent: String,       // navigation | search | hud_control | music | chat
    val action: String,       // 具体动作
    val params: Map<String, Any>,  // 参数
    val rawText: String       // 原始文本（调试用）
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

        // ==================== LLM 系统提示词（仅 full 版使用） ====================
        val SYSTEM_PROMPT = """
你是车载语音助手"哈德"。识别用户意图，仅输出JSON，不要其他文字。
格式：{"intent":"类型","action":"动作","params":{参数}}
类型：navigation(导航/找地点/路线), search(搜索/查资料/查天气/查新闻), hud_control(HUD设置/镜像/亮度), music(音乐/播放/暂停), chat(闲聊/问答/其他)
示例：
"导航去最近的加油站"→{"intent":"navigation","action":"search_poi","params":{"keyword":"加油站","sort":"nearest"}}
"帮我搜一下明天天气"→{"intent":"search","action":"web_search","params":{"query":"明天天气"}}
"把镜像关了"→{"intent":"hud_control","action":"mirror_off","params":{}}
"播放音乐"→{"intent":"music","action":"play","params":{}}
"你好啊"→{"intent":"chat","action":"reply","params":{"text":"你好，我是哈德，有什么可以帮你的？"}}
"现在几点了"→{"intent":"chat","action":"reply","params":{"text":"请查看手机时间"}}
        """.trimIndent()

        // ==================== 关键词规则（lite 版 + full 版降级用） ====================

        // 导航相关关键词
        private val NAV_KEYWORDS = listOf("导航", "去哪", "路线", "带路", "怎么走", "开往")
        private val POI_KEYWORDS = listOf("加油站", "停车场", "停车", "厕所", "洗手间", "医院",
            "餐厅", "饭店", "酒店", "超市", "商场", "银行", "充电桩", "洗车", "修车")
        private val NAV_DIRECTION = listOf("家", "公司", "单位", "学校", "机场", "火车站")

        // 搜索相关关键词
        private val SEARCH_KEYWORDS = listOf("搜索", "搜一下", "查一下", "查查", "帮我搜",
            "帮我查", "看看", "找一下", "问问", "告诉我", "什么是", "是什么")

        // HUD 控制关键词
        private val MIRROR_ON_KEYWORDS = listOf("开镜像", "打开镜像", "镜像开", "镜像打开", "翻转", "打开翻转")
        private val MIRROR_OFF_KEYWORDS = listOf("关镜像", "关闭镜像", "镜像关", "镜像关闭", "关翻转", "关闭翻转")
        private val MIRROR_TOGGLE_KEYWORDS = listOf("切换镜像", "镜像切换", "翻转切换", "切换翻转")

        // 音乐关键词
        private val MUSIC_PLAY_KEYWORDS = listOf("播放", "放歌", "放音乐", "来首歌", "听歌", "听音乐")
        private val MUSIC_PAUSE_KEYWORDS = listOf("暂停", "停一下", "别放了", "停止播放")
        private val MUSIC_NEXT_KEYWORDS = listOf("下一首", "换一首", "跳过")
        private val MUSIC_PREV_KEYWORDS = listOf("上一首", "前一首")
    }

    // ========== LLM 引擎（仅 full 版使用） ==========
    private val useLlm = BuildConfig.IS_LLM_ENABLED
    private var engine: MnnLlmEngine? = null
    private var modelManager: ModelManager? = null
    private var isLlmAvailable = false

    // ========== 模式标识 ==========
    val mode: String get() = if (useLlm && isLlmAvailable) "LLM" else "Keyword"

    /**
     * 检查引擎是否可用
     * full 版：检查 .so + 模型是否就绪
     * lite 版：直接返回 true（关键词匹配不需要额外资源）
     */
    suspend fun checkAvailability(): Boolean {
        if (!useLlm) {
            Log.i(TAG, "Lite mode: keyword matching, always available")
            return true
        }

        // full 版：初始化 LLM 引擎
        if (engine == null) {
            engine = MnnLlmEngine(context)
            modelManager = ModelManager(context)
        }

        val eng = engine!!
        val mgr = modelManager!!

        // 1. 检查原生库
        if (!eng.isAvailable) {
            Log.w(TAG, "MNN-LLM native library not available, falling back to keyword mode")
            isLlmAvailable = false
            return true  // 关键词模式仍然可用
        }

        // 2. 检查/解压模型
        if (!mgr.isModelReady()) {
            if (!mgr.hasAssetsModel()) {
                Log.w(TAG, "No model in assets, falling back to keyword mode")
                isLlmAvailable = false
                return true
            }
            Log.i(TAG, "Extracting model from assets...")
            val extracted = withContext(Dispatchers.IO) { mgr.extractFromAssets() }
            if (!extracted) {
                Log.e(TAG, "Model extraction failed, falling back to keyword mode")
                isLlmAvailable = false
                return true
            }
        }

        // 3. 初始化引擎
        if (!isLlmAvailable) {
            val modelDir = mgr.getModelDir().absolutePath
            isLlmAvailable = eng.init(modelDir)
            if (!isLlmAvailable) {
                Log.w(TAG, "LLM init failed, falling back to keyword mode")
            }
        }

        Log.i(TAG, "LLM engine available: $isLlmAvailable, mode: $mode")
        return true
    }

    fun isReady(): Boolean = true  // 关键词模式始终可用

    /**
     * 分类用户输入意图
     * full + LLM可用：使用本地模型语义理解
     * lite 或 LLM不可用：使用关键词匹配
     */
    suspend fun classify(userText: String): IntentResult {
        // 优先尝试 LLM（仅 full 版且引擎可用）
        if (useLlm && isLlmAvailable) {
            try {
                val result = classifyWithLlm(userText)
                if (result != null) {
                    Log.i(TAG, "LLM classified: ${result.intent}/${result.action}")
                    return result
                }
            } catch (e: Exception) {
                Log.e(TAG, "LLM classification failed: ${e.message}, falling back to keyword")
            }
        }

        // 降级/默认：关键词匹配
        val result = classifyWithKeywords(userText)
        Log.i(TAG, "Keyword classified: ${result.intent}/${result.action}")
        return result
    }

    /**
     * 聊天模式（仅 full 版 LLM 可用时使用）
     */
    suspend fun chat(userText: String, context: String = ""): String {
        if (!useLlm || !isLlmAvailable) {
            return "抱歉，当前为轻量版，不支持自由聊天。"
        }

        try {
            val chatPrompt = if (context.isNotEmpty()) {
                "你是车载语音助手\"哈德\"，用简短自然的方式回答用户。$context\n用户: $userText"
            } else {
                "你是车载语音助手\"哈德\"，用简短自然的方式回答用户。\n用户: $userText"
            }

            val response = engine!!.generate(
                prompt = chatPrompt,
                maxTokens = 256,
                temperature = 0.7f
            )
            return response?.trim() ?: "抱歉，我没听懂，请再说一次。"
        } catch (e: Exception) {
            Log.e(TAG, "Chat failed: ${e.message}", e)
            return "抱歉，出了点问题。"
        }
    }

    fun release() {
        engine?.release()
        isLlmAvailable = false
    }

    // ==================== LLM 分类（full 版） ====================

    private suspend fun classifyWithLlm(userText: String): IntentResult? {
        val prompt = "$SYSTEM_PROMPT\n用户：$userText\n输出："
        val response = engine!!.generate(prompt = prompt, maxTokens = 256, temperature = 0.1f)
            ?: return null
        return parseLlmResponse(response)
    }

    private fun parseLlmResponse(response: String): IntentResult? {
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

        Log.w(TAG, "Failed to parse LLM response: ${trimmed.take(100)}")
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

    // ==================== 关键词分类（lite 版 + full 版降级） ====================

    private fun classifyWithKeywords(userText: String): IntentResult {
        val text = userText.lowercase().trim()

        // --- 1. HUD 控制（镜像） ---
        if (MIRROR_OFF_KEYWORDS.any { text.contains(it) }) {
            return IntentResult(IntentResult.INTENT_HUD_CONTROL, "mirror_off", emptyMap(), userText)
        }
        if (MIRROR_ON_KEYWORDS.any { text.contains(it) }) {
            return IntentResult(IntentResult.INTENT_HUD_CONTROL, "mirror_on", emptyMap(), userText)
        }
        if (MIRROR_TOGGLE_KEYWORDS.any { text.contains(it) }) {
            return IntentResult(IntentResult.INTENT_HUD_CONTROL, "mirror_toggle", emptyMap(), userText)
        }

        // --- 2. 导航 ---
        if (NAV_KEYWORDS.any { text.contains(it) }) {
            // 提取目的地关键词
            val keyword = extractDestination(text)
            val sort = if (text.contains("最近") || text.contains("附近") || text.contains("离我近")) "nearest" else ""
            return IntentResult(IntentResult.INTENT_NAVIGATION, "search_poi",
                buildMap {
                    if (keyword.isNotBlank()) put("keyword", keyword)
                    if (sort.isNotBlank()) put("sort", sort)
                }, userText)
        }
        // POI 直接说（"附近的加油站"）
        for (poi in POI_KEYWORDS) {
            if (text.contains(poi)) {
                val sort = if (text.contains("最近") || text.contains("附近") || text.contains("近")) "nearest" else ""
                return IntentResult(IntentResult.INTENT_NAVIGATION, "search_poi",
                    buildMap {
                        put("keyword", poi)
                        if (sort.isNotBlank()) put("sort", sort)
                    }, userText)
            }
        }

        // --- 3. 搜索 ---
        for (kw in SEARCH_KEYWORDS) {
            if (text.contains(kw)) {
                val query = text.substringAfter(kw).trim().trimEnd('？', '?', '。', '.', '！', '!')
                return IntentResult(IntentResult.INTENT_SEARCH, "web_search",
                    mapOf("query" to query.ifBlank { userText }), userText)
            }
        }

        // --- 4. 音乐 ---
        if (MUSIC_PAUSE_KEYWORDS.any { text.contains(it) }) {
            return IntentResult(IntentResult.INTENT_MUSIC, "pause", emptyMap(), userText)
        }
        if (MUSIC_NEXT_KEYWORDS.any { text.contains(it) }) {
            return IntentResult(IntentResult.INTENT_MUSIC, "next", emptyMap(), userText)
        }
        if (MUSIC_PREV_KEYWORDS.any { text.contains(it) }) {
            return IntentResult(IntentResult.INTENT_MUSIC, "previous", emptyMap(), userText)
        }
        if (MUSIC_PLAY_KEYWORDS.any { text.contains(it) }) {
            return IntentResult(IntentResult.INTENT_MUSIC, "play", emptyMap(), userText)
        }

        // --- 5. 兜底：聊天 ---
        return IntentResult.fallback(userText)
    }

    /**
     * 从用户文本中提取目的地关键词
     * "导航去加油站" → "加油站"
     * "带我去机场" → "机场"
     * "导航去最近的加油站" → "加油站"
     */
    private fun extractDestination(text: String): String {
        var dest = text
        // 去掉导航动词
        for (kw in NAV_KEYWORDS) {
            dest = dest.replace(kw, "")
        }
        // 去掉修饰词
        for (modifier in listOf("最近的", "附近的", "最近的", "去", "到", "个", "一个")) {
            dest = dest.replace(modifier, "")
        }
        return dest.trim().trimEnd('？', '?', '。', '.', '！', '!', '吧', '啊')
    }
}
