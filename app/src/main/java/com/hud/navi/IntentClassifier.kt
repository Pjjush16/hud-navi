/*
 * IntentClassifier.kt - 本地 LLM 意图分类器 (v11.0)
 *
 * 通过 Ollama 兼容的 HTTP API 调用本地 LLM（Qwen2.5-0.5B-Instruct）。
 * 支持后端：
 *   1. MNN-LLM Android 本地推理（通过内置 HTTP Server）
 *   2. 远程 Ollama 服务器（vm590z / cloudpc）
 *   3. 任何 OpenAI / Ollama 兼容 API
 *
 * 模型输出结构化 JSON，代码直接解析路由。
 */

package com.hud.navi

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

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
    private var endpoint: String = DEFAULT_ENDPOINT,
    private var modelName: String = DEFAULT_MODEL,
    private val timeout: Int = 15000
) {
    companion object {
        private const val TAG = "IntentClassifier"

        // 默认端点：本地 MNN-LLM HTTP Server
        // 如果模型跑在远程服务器上，修改为 "http://100.80.62.97:11434" 等
        const val DEFAULT_ENDPOINT = "http://127.0.0.1:11434"
        const val DEFAULT_MODEL = "qwen2.5:0.5b"

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

        // 备用端点列表（按优先级）
        val FALLBACK_ENDPOINTS = listOf(
            "http://127.0.0.1:11434",     // 本地 MNN-LLM
            "http://100.80.62.97:11434",  // vm590z Ollama (Tailscale)
        )
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var currentEndpoint: String = endpoint
    private var isAvailable = false

    /**
     * 检查 LLM 服务是否可用
     */
    fun checkAvailability(): Boolean {
        for (ep in FALLBACK_ENDPOINTS) {
            try {
                val url = URL("$ep/api/tags")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "GET"

                if (conn.responseCode == 200) {
                    currentEndpoint = ep
                    isAvailable = true
                    Log.i(TAG, "LLM service available at: $ep")
                    conn.disconnect()
                    return true
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "LLM not available at $ep: ${e.message}")
            }
        }
        isAvailable = false
        Log.w(TAG, "No LLM service available")
        return false
    }

    fun isReady(): Boolean = isAvailable

    /**
     * 分类用户输入的意图
     *
     * @param userText ASR 识别出的用户文本
     * @return IntentResult 意图分类结果
     */
    fun classify(userText: String): IntentResult {
        if (!isAvailable) {
            Log.w(TAG, "LLM not available, returning fallback")
            return IntentResult.fallback(userText)
        }

        try {
            val prompt = buildPrompt(userText)
            val response = callOllamaAPI(prompt)

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
    fun chat(userText: String, context: String = ""): String {
        if (!isAvailable) return "抱歉，语音助手暂时不可用。"

        try {
            val chatPrompt = if (context.isNotEmpty()) {
                "你是车载语音助手\"哈德\"，用简短自然的方式回答用户。$context\n用户: $userText"
            } else {
                "你是车载语音助手\"哈德\"，用简短自然的方式回答用户。\n用户: $userText"
            }

            val response = callOllamaAPI(chatPrompt, jsonMode = false)
            return response?.trim() ?: "抱歉，我没听懂，请再说一次。"
        } catch (e: Exception) {
            Log.e(TAG, "Chat failed: ${e.message}", e)
            return "抱歉，出了点问题。"
        }
    }

    /**
     * 更新 LLM 服务地址
     */
    fun updateEndpoint(newEndpoint: String) {
        endpoint = newEndpoint
        currentEndpoint = newEndpoint
        isAvailable = checkAvailability()
    }

    // ==================== 内部方法 ====================

    private fun buildPrompt(userText: String): String {
        return "$SYSTEM_PROMPT\n用户：$userText\n输出："
    }

    /**
     * 调用 Ollama 兼容 API
     *
     * 请求格式：
     * POST /api/generate
     * {
     *   "model": "qwen2.5:0.5b",
     *   "prompt": "...",
     *   "system": "...",
     *   "stream": false,
     *   "options": { "temperature": 0.1, "num_predict": 256 }
     * }
     */
    private fun callOllamaAPI(prompt: String, jsonMode: Boolean = true): String? {
        val url = URL("$currentEndpoint/api/generate")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = timeout
        conn.readTimeout = timeout
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")

        val requestBody = JSONObject().apply {
            put("model", modelName)
            put("prompt", prompt)
            put("stream", false)
            put("options", JSONObject().apply {
                put("temperature", if (jsonMode) 0.1 else 0.7)
                put("num_predict", 256)
                put("stop", listOf("\n用户", "\nUser"))
            })
        }

        Log.d(TAG, "Request: ${requestBody.length()} bytes to $currentEndpoint")

        OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
            writer.write(requestBody.toString())
            writer.flush()
        }

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            Log.w(TAG, "API returned $responseCode")
            conn.disconnect()
            return null
        }

        val responseBody = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { reader ->
            reader.readText()
        }
        conn.disconnect()

        val responseJson = JSONObject(responseBody)
        val response = responseJson.optString("response", "")
        Log.d(TAG, "Response: ${response.take(100)}...")

        return response
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
