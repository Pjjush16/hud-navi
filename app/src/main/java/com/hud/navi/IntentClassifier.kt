/*
 * IntentClassifier.kt - 本地 LLM 意图分类器 (v11.1)
 *
 * 通过 MNN-LLM 原生引擎调用本地 LLM（Qwen2.5-0.5B-Instruct）。
 * 后端：MNN-LLM JNI 直接调用（无需 HTTP Server）
 *
 * 模型输出结构化 JSON，代码直接解析路由。
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
    val rawText: String       // 模型原始输出（调试用）
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

        /**
         * 系统提示词 — 让 Qwen2.5-0.5B 稳定输出结构化 JSON
         *
         * 设计原则：
         * - 足够短（<200 token），减少推理延迟
         * - 明确的 JSON 格式约束
         * - 覆盖所有 HUD 场景的 intent 类型
         * - 提供 few-shot 示例，帮助小模型理解格式
         */
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
    }

    // MNN-LLM 原生引擎
    private val engine = MnnLlmEngine(context)
    private val modelManager = ModelManager(context)
    private var isAvailable = false

    /**
     * 检查 LLM 引擎是否可用（.so 已加载 + 模型已就绪）
     * 如果模型尚未从 assets 解压，会自动触发解压。
     */
    suspend fun checkAvailability(): Boolean {
        // 1. 检查原生库是否加载成功
        if (!engine.isAvailable) {
            Log.w(TAG, "MNN-LLM native library not available")
            isAvailable = false
            return false
        }

        // 2. 检查模型是否已解压到内部存储，没有则从 assets 解压
        if (!modelManager.isModelReady()) {
            if (!modelManager.hasAssetsModel()) {
                Log.w(TAG, "No model files in assets/llm/")
                isAvailable = false
                return false
            }
            Log.i(TAG, "Extracting model from assets...")
            val extracted = withContext(Dispatchers.IO) {
                modelManager.extractFromAssets()
            }
            if (!extracted) {
                Log.e(TAG, "Model extraction failed")
                isAvailable = false
                return false
            }
        }

        // 3. 初始化引擎（如果尚未初始化）
        if (!isAvailable) {
            val modelDir = modelManager.getModelDir().absolutePath
            isAvailable = engine.init(modelDir)
        }

        Log.i(TAG, "LLM engine available: $isAvailable")
        return isAvailable
    }

    fun isReady(): Boolean = isAvailable

    /**
     * 分类用户输入的意图（suspend 版本，在 IO 线程执行推理）
     *
     * @param userText ASR 识别出的用户文本
     * @return IntentResult 意图分类结果
     */
    suspend fun classify(userText: String): IntentResult {
        if (!isAvailable) {
            Log.w(TAG, "LLM not available, returning fallback")
            return IntentResult.fallback(userText)
        }

        try {
            val prompt = buildPrompt(userText)
            val response = engine.generate(
                prompt = prompt,
                maxTokens = 256,
                temperature = 0.1f  // 低温度确保 JSON 输出稳定
            )

            if (response != null) {
                val result = parseResponse(response)
                if (result != null) {
                    Log.i(TAG, "Classified: ${result.intent}/${result.action}")
                    return result
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Classification failed: ${e.message}", e)
        }

        return IntentResult.fallback(userText)
    }

    /**
     * 聊天模式 — 让模型自由回复（不要求 JSON 格式）
     */
    suspend fun chat(userText: String, context: String = ""): String {
        if (!isAvailable) return "抱歉，语音助手暂时不可用。"

        try {
            val chatPrompt = if (context.isNotEmpty()) {
                "你是车载语音助手\"哈德\"，用简短自然的方式回答用户。$context\n用户: $userText"
            } else {
                "你是车载语音助手\"哈德\"，用简短自然的方式回答用户。\n用户: $userText"
            }

            val response = engine.generate(
                prompt = chatPrompt,
                maxTokens = 256,
                temperature = 0.7f  // 聊天用较高温度，更自然
            )
            return response?.trim() ?: "抱歉，我没听懂，请再说一次。"
        } catch (e: Exception) {
            Log.e(TAG, "Chat failed: ${e.message}", e)
            return "抱歉，出了点问题。"
        }
    }

    /**
     * 释放引擎资源
     */
    fun release() {
        engine.release()
        isAvailable = false
    }

    // ==================== 内部方法 ====================

    private fun buildPrompt(userText: String): String {
        return "$SYSTEM_PROMPT\n用户：$userText\n输出："
    }

    /**
     * 解析模型输出的 JSON
     *
     * 模型可能输出：
     * - 纯 JSON：{"intent":"navigation","action":"search_poi","params":{"keyword":"加油站"}}
     * - 带 markdown：```json\n{...}\n```
     * - 带前缀文字：好的，我理解了...{"intent":"..."}
     *
     * 解析策略：
     * 1. 先尝试直接解析
     * 2. 失败则提取 {...} 部分再解析
     * 3. 再失败则用正则提取关键字段
     */
    private fun parseResponse(response: String): IntentResult? {
        val trimmed = response.trim()

        // 策略1：直接解析
        try {
            val json = JSONObject(trimmed)
            return extractIntent(json, trimmed)
        } catch (_: Exception) {}

        // 策略2：提取 JSON 块
        val jsonBlock = extractJsonBlock(trimmed)
        if (jsonBlock != null) {
            try {
                val json = JSONObject(jsonBlock)
                return extractIntent(json, trimmed)
            } catch (_: Exception) {}
        }

        // 策略3：正则提取
        val intentMatch = Regex("\"intent\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)
        val actionMatch = Regex("\"action\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)
        if (intentMatch != null) {
            val intent = intentMatch.groupValues[1]
            val action = actionMatch?.groupValues?.get(1) ?: "unknown"
            val params = mutableMapOf<String, Any>()

            // 提取 params 中的常见字段
            val keywordMatch = Regex("\"keyword\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)
            val queryMatch = Regex("\"query\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)
            val textMatch = Regex("\"text\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)

            keywordMatch?.let { params["keyword"] = it.groupValues[1] }
            queryMatch?.let { params["query"] = it.groupValues[1] }
            textMatch?.let { params["text"] = it.groupValues[1] }

            Log.i(TAG, "Parsed via regex: intent=$intent, action=$action")
            return IntentResult(intent, action, params, trimmed)
        }

        Log.w(TAG, "Failed to parse response: ${trimmed.take(100)}")
        return null
    }

    private fun extractJsonBlock(text: String): String? {
        // 匹配 ```json ... ``` 或 ``` ... ``` 中的内容
        val codeBlockMatch = Regex("```(?:json)?\\s*\\n?(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL).find(text)
        if (codeBlockMatch != null) return codeBlockMatch.groupValues[1].trim()

        // 匹配第一个 { 到最后一个 } 之间的内容
        val firstBrace = text.indexOf('{')
        val lastBrace = text.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return text.substring(firstBrace, lastBrace + 1)
        }

        return null
    }

    private fun extractIntent(json: JSONObject, rawText: String): IntentResult {
        val intent = json.optString("intent", IntentResult.INTENT_UNKNOWN)
        val action = json.optString("action", "unknown")
        val params = mutableMapOf<String, Any>()

        val paramsObj = json.optJSONObject("params")
        if (paramsObj != null) {
            val keys = paramsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                params[key] = paramsObj.get(key)
            }
        }

        return IntentResult(intent, action, params, rawText)
    }
}
