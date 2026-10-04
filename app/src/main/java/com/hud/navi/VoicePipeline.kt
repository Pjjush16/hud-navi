/*
 * VoicePipeline.kt - 语音交互管线编排器 (v11.3 双版本)
 *
 * 完整语音管线：
 *   唤醒词(sherpa-onnx KWS) → "我在"反馈 → 录音(ASR) → 意图分类 → 执行 + TTS
 *
 * full 版：意图分类使用本地 Qwen2.5-0.5B (MNN-LLM) 语义理解
 * lite 版：意图分类使用关键词匹配（固定指令集）
 *
 * 由 BuildConfig.IS_LLM_ENABLED 控制，IntentClassifier 内部自动切换
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
    PROCESSING,     // ASR + 意图分类处理中
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
     * 1. WakeWordManager（唤醒词检测）
     * 2. IntentClassifier（LLM 或关键词，由 BuildConfig 决定）
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

        // 2. 意图分类器（LLM full版 / 关键词 lite版）
        try {
            intentClassifier = IntentClassifier(context)
            scope.launch(Dispatchers.IO) {
                intentClassifier?.checkAvailability()
                Log.i(TAG, "IntentClassifier ready, mode: ${intentClassifier?.mode ?: "unknown"}")
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

    fun start() {
        if (wakeWordManager?.isReady() == true) {
            wakeWordManager?.start()
            state = PipelineState.IDLE
            Log.i(TAG, "Pipeline started, listening for wake word")
        } else {
            Log.w(TAG, "Cannot start: wake word not ready")
        }
    }

    fun pause() {
        wakeWordManager?.stop()
        voiceCommandManager?.cancelListening()
        chatEngine?.stopSpeaking()
        state = PipelineState.IDLE
    }

    fun release() {
        wakeWordManager?.release()
        voiceCommandManager?.release()
        chatEngine?.release()
        intentClassifier?.release()
        scope.cancel()
        Log.i(TAG, "Pipeline released")
    }

    // ==================== 管线流程 ====================

    private fun onWakeDetected(keyword: String) {
        Log.i(TAG, "Wake detected: $keyword")
        state = PipelineState.WAKE_DETECTED
        callback.onWakeDetected(keyword)

        handler.postDelayed({
            startListening()
        }, 300)
    }

    private fun startListening() {
        state = PipelineState.LISTENING
        voiceCommandManager?.startListening()
        Log.i(TAG, "Listening for command...")
    }

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

            scope.launch(Dispatchers.IO) {
                processIntent(text)
            }
        }
    }

    /**
     * 意图分类 + 执行
     * IntentClassifier 内部自动选择 LLM 或关键词模式
     */
    private suspend fun processIntent(userText: String) {
        try {
            val classifier = intentClassifier ?: run {
                Log.w(TAG, "Classifier null, fallback")
                handler.post {
                    callback.onIntentResult(IntentResult.fallback(userText))
                    state = PipelineState.SPEAKING
                    chatEngine?.speak("抱歉，语音助手暂时不可用。")
                }
                return
            }

            // 分类意图（LLM 或关键词，由 classifier 内部决定）
            val result = classifier.classify(userText)

            Log.i(TAG, "Intent: ${result.intent}/${result.action} (mode: ${classifier.mode})")
            handler.post { callback.onIntentResult(result) }

            // 执行动作
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

    private fun executeIntent(result: IntentResult) {
        val chatEng = chatEngine ?: return

        chatEng.handleIntent(result) { uiCommand ->
            handler.post { callback.onUiCommand(uiCommand) }
        }

        chatEng.setOnTtsDoneListener {
            handler.post {
                state = PipelineState.IDLE
                Log.i(TAG, "TTS done, back to idle")
            }
        }

        // 兜底：5秒后回到 IDLE
        handler.postDelayed({
            if (state == PipelineState.SPEAKING) {
                state = PipelineState.IDLE
            }
        }, 5000)
    }
}
