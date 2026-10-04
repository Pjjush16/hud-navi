/*
 * VoicePipeline.kt - 语音交互管线编排器 (v12.0 云端版)
 *
 * 完整语音管线：
 *   唤醒词(sherpa-onnx KWS) → "我在"反馈 → 录音(ASR) → 意图分类 → 执行 + TTS
 *
 * 意图分类使用智谱 AI 云端 API（免费模型 glm-4.7-flash）
 * API 不可用时自动降级到关键词匹配
 */

package com.hud.navi

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*

enum class PipelineState {
    IDLE, WAKE_DETECTED, LISTENING, PROCESSING, SPEAKING, ERROR
}

interface VoicePipelineCallback {
    fun onStateChanged(state: PipelineState)
    fun onWakeDetected(keyword: String)
    fun onAsrResult(text: String, isFinal: Boolean)
    fun onIntentResult(result: IntentResult)
    fun onUiCommand(command: String)
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

        // 2. 意图分类器（智谱 API + 关键词降级）
        try {
            intentClassifier = IntentClassifier(context)
            Log.i(TAG, "IntentClassifier mode: ${intentClassifier?.mode ?: "unknown"}")
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
        Log.i(TAG, "Pipeline initialized. Wake=${wakeWordManager?.isReady()}, mode=${intentClassifier?.mode}")
    }

    fun start() {
        if (wakeWordManager?.isReady() == true) {
            wakeWordManager?.start()
            state = PipelineState.IDLE
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
    }

    private fun onWakeDetected(keyword: String) {
        Log.i(TAG, "Wake: $keyword")
        state = PipelineState.WAKE_DETECTED
        callback.onWakeDetected(keyword)
        handler.postDelayed({ startListening() }, 300)
    }

    private fun startListening() {
        state = PipelineState.LISTENING
        voiceCommandManager?.startListening()
    }

    private fun onAsrResult(text: String, isFinal: Boolean) {
        callback.onAsrResult(text, isFinal)
        if (isFinal) {
            if (text.isBlank()) {
                chatEngine?.speak("我没有听清，请再说一次")
                state = PipelineState.IDLE
                return
            }
            state = PipelineState.PROCESSING
            scope.launch(Dispatchers.IO) { processIntent(text) }
        }
    }

    private suspend fun processIntent(userText: String) {
        try {
            val classifier = intentClassifier ?: return
            val result = classifier.classify(userText)
            Log.i(TAG, "Intent: ${result.intent}/${result.action} (mode: ${classifier.mode})")
            handler.post { callback.onIntentResult(result) }

            withContext(Dispatchers.Main) {
                state = PipelineState.SPEAKING
                executeIntent(result)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Process failed: ${e.message}", e)
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
            handler.post { state = PipelineState.IDLE }
        }
        handler.postDelayed({ if (state == PipelineState.SPEAKING) state = PipelineState.IDLE }, 5000)
    }
}
