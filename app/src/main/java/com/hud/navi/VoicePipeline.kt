/*
 * VoicePipeline.kt - 语音交互管线编排器 (v11.0)
 *
 * 完整语音管线：
 *   唤醒词(sherpa-onnx KWS) → "我在"反馈 → 录音(VAD) → ASR转文字
 *   → Qwen2.5-0.5B(MNN-LLM) → JSON意图路由 → 执行 + TTS语音回复
 *
 * 各模块职责：
 *   - WakeWordManager: 唤醒词检测（已有）
 *   - VoiceCommandManager: 录音 + ASR
 *   - IntentClassifier: LLM 意图分类
 *   - WebSearchClient: SearXNG + Bing 搜索
 *   - ChatEngine: 聊天 + TTS
 */

package com.hud.navi

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*

/**
 * 语音管线状态
 */
enum class PipelineState {
    IDLE,           // 等待唤醒词
    WAKE_DETECTED,  // 唤醒词已检测，准备录音
    LISTENING,      // 正在录音
    PROCESSING,     // ASR + LLM 处理中
    SPEAKING,       // TTS 播报中
    ERROR           // 错误状态
}

/**
 * 语音管线事件回调
 */
interface VoicePipelineCallback {
    fun onStateChanged(state: PipelineState)
    fun onWakeDetected(keyword: String)
    fun onAsrResult(text: String, isFinal: Boolean)
    fun onIntentResult(result: IntentResult)
    fun onUiCommand(command: String)  // 传给 MainActivity 的 UI 指令
    fun onChatResponse(text: String)
    fun onError(message: String)
}

class VoicePipeline(
    private val context: Context,
    private val callback: VoicePipelineCallback
) {
    companion object {
        private const val TAG = "VoicePipeline"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 子模块
    private var wakeWordManager: WakeWordManager? = null
    private var voiceCommandManager: VoiceCommandManager? = null
    private var intentClassifier: IntentClassifier? = null
    private var webSearchClient: WebSearchClient? = null
    private var chatEngine: ChatEngine? = null

    var state: PipelineState = PipelineState.IDLE
        private set(value) {
            field = value
            handler.post { callback.onStateChanged(value) }
        }

    var pipelineReady = false
        private set

    var initError: String? = null
        private set

    /**
     * 初始化所有子模块
     *
     * 初始化顺序：
     * 1. WakeWordManager（已有，唤醒词检测）
     * 2. IntentClassifier（MNN-LLM 意图分类）
     * 3. WebSearchClient（搜索引擎）
     * 4. ChatEngine（TTS）
     * 5. VoiceCommandManager（ASR）
     */
    fun init() {
        Log.i(TAG, "Initializing voice pipeline...")
        val errors = mutableListOf<String>()

        // 1. 唤醒词
        try {
            wakeWordManager = WakeWordManager(context) { keyword ->
                handler.post { onWakeDetected(keyword) }
            }
            wakeWordManager?.init()
            if (wakeWordManager?.isReady() != true) {
                errors.add("唤醒词: ${wakeWordManager?.initError ?: "未知"}")
            }
        } catch (e: Exception) {
            errors.add("唤醒词: ${e.message}")
        }

        // 2. 意图分类器
        try {
            intentClassifier = IntentClassifier()
            // 异步检查 LLM 服务可用性
            scope.launch(Dispatchers.IO) {
                val available = intentClassifier?.checkAvailability() ?: false
                if (!available) {
                    Log.w(TAG, "LLM service not available, intent classification will use fallback")
                }
            }
        } catch (e: Exception) {
            errors.add("意图分类: ${e.message}")
        }

        // 3. 搜索引擎
        try {
            webSearchClient = WebSearchClient()
        } catch (e: Exception) {
            errors.add("搜索引擎: ${e.message}")
        }

        // 4. 聊天引擎 + TTS
        try {
            chatEngine = ChatEngine(context, intentClassifier!!, webSearchClient!!)
            chatEngine?.init()
        } catch (e: Exception) {
            errors.add("聊天引擎: ${e.message}")
        }

        // 5. ASR
        try {
            voiceCommandManager = VoiceCommandManager(context) { text, isFinal ->
                handler.post { onAsrResult(text, isFinal) }
            }
            if (!voiceCommandManager!!.init()) {
                errors.add("ASR: 设备不支持语音识别")
            }
        } catch (e: Exception) {
            errors.add("ASR: ${e.message}")
        }

        if (errors.isNotEmpty()) {
            initError = errors.joinToString("; ")
            Log.w(TAG, "Pipeline init with errors: $initError")
        }

        pipelineReady = true
        state = PipelineState.IDLE
        Log.i(TAG, "Pipeline initialized. Wake=${wakeWordManager?.isReady()}, ASR=${voiceCommandManager != null}")
    }

    /**
     * 启动唤醒词监听
     */
    fun start() {
        if (wakeWordManager?.isReady() == true) {
            wakeWordManager?.start()
            state = PipelineState.IDLE
            Log.i(TAG, "Pipeline started, listening for wake word")
        } else {
            Log.w(TAG, "Cannot start: wake word not ready")
        }
    }

    /**
     * 暂停（onPause 时调用）
     */
    fun pause() {
        wakeWordManager?.stop()
        voiceCommandManager?.cancelListening()
        chatEngine?.stopSpeaking()
        state = PipelineState.IDLE
    }

    /**
     * 释放所有资源
     */
    fun release() {
        wakeWordManager?.release()
        voiceCommandManager?.release()
        chatEngine?.release()
        scope.cancel()
        Log.i(TAG, "Pipeline released")
    }

    // ==================== 管线流程 ====================

    /**
     * 唤醒词检测回调
     */
    private fun onWakeDetected(keyword: String) {
        Log.i(TAG, "Wake detected: $keyword")
        state = PipelineState.WAKE_DETECTED
        callback.onWakeDetected(keyword)

        // 短暂延迟后开始录音（给用户反应时间）
        handler.postDelayed({
            startListening()
        }, 300)
    }

    /**
     * 开始录音（用户说话）
     */
    private fun startListening() {
        state = PipelineState.LISTENING
        voiceCommandManager?.startListening()
        Log.i(TAG, "Listening for command...")
    }

    /**
     * ASR 结果回调
     */
    private fun onAsrResult(text: String, isFinal: Boolean) {
        callback.onAsrResult(text, isFinal)

        if (isFinal) {
            if (text.isBlank()) {
                Log.w(TAG, "Empty ASR result")
                chatEngine?.speak("我没有听清，请再说一次")
                state = PipelineState.IDLE
                return
            }

            state = PipelineState.PROCESSING
            Log.i(TAG, "Final ASR: $text, processing...")

            // 在后台线程执行 LLM 意图分类
            scope.launch(Dispatchers.IO) {
                processIntent(text)
            }
        }
    }

    /**
     * LLM 意图分类 + 执行
     */
    private suspend fun processIntent(userText: String) {
        try {
            val classifier = intentClassifier
            if (classifier == null || !classifier.isReady()) {
                // LLM 不可用，降级为基础处理
                Log.w(TAG, "LLM not available, using basic fallback")
                handleBasicFallback(userText)
                return
            }

            // 分类意图
            val result = withContext(Dispatchers.IO) {
                classifier.classify(userText)
            }

            Log.i(TAG, "Intent: ${result.intent}/${result.action}")
            handler.post { callback.onIntentResult(result) }

            // 根据意图执行动作（切换到主线程处理 UI）
            withContext(Dispatchers.Main) {
                state = PipelineState.SPEAKING
                executeIntent(result)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Process intent failed: ${e.message}", e)
            handler.post {
                callback.onError("处理失败: ${e.message}")
                state = PipelineState.IDLE
            }
        }
    }

    /**
     * 执行意图
     */
    private fun executeIntent(result: IntentResult) {
        val chatEng = chatEngine ?: return

        chatEng.handleIntent(result) { uiCommand ->
            handler.post { callback.onUiCommand(uiCommand) }
        }

        // TTS 完成后回到 IDLE 状态
        chatEng.setOnTtsDoneListener {
            handler.post {
                state = PipelineState.IDLE
                Log.i(TAG, "TTS done, back to idle")
            }
        }

        // 兜底：如果 TTS 没有触发（文本太短或 TTS 不可用），5秒后回到 IDLE
        handler.postDelayed({
            if (state == PipelineState.SPEAKING) {
                state = PipelineState.IDLE
            }
        }, 5000)
    }

    /**
     * 基础降级处理 — LLM 不可用时的简单关键词匹配
     */
    private fun handleBasicFallback(userText: String) {
        val result = when {
            userText.contains("导航") || userText.contains("去哪") || userText.contains("路线") -> {
                val keyword = userText.replace(Regex(".*(?:导航|去|路线)"), "").trim()
                IntentResult(IntentResult.INTENT_NAVIGATION, "search_poi",
                    mapOf("keyword" to keyword), userText)
            }
            userText.contains("搜索") || userText.contains("查") || userText.contains("搜") -> {
                val query = userText.replace(Regex(".*(?:搜索|查|搜)"), "").trim()
                IntentResult(IntentResult.INTENT_SEARCH, "web_search",
                    mapOf("query" to query), userText)
            }
            userText.contains("镜像") -> {
                val action = if (userText.contains("关") || userText.contains("停")) "mirror_off" else "mirror_toggle"
                IntentResult(IntentResult.INTENT_HUD_CONTROL, action, emptyMap(), userText)
            }
            userText.contains("播放") || userText.contains("音乐") -> {
                IntentResult(IntentResult.INTENT_MUSIC, "play", emptyMap(), userText)
            }
            else -> {
                IntentResult.fallback(userText)
            }
        }

        handler.post {
            callback.onIntentResult(result)
            state = PipelineState.SPEAKING
            executeIntent(result)
        }
    }
}
