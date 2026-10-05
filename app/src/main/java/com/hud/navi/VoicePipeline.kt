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
    private var onnxWakeEngine: OnnxWakeWordEngine? = null  // v13.1: ONNX 自训练唤醒词
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

        // 1. 唤醒词 — 优先使用 ONNX 自训练模型，失败降级到 sherpa-onnx
        try {
            onnxWakeEngine = OnnxWakeWordEngine(context) {
                handler.post { onWakeDetected("哈德哈德") }
            }
            onnxWakeEngine?.init()
            if (onnxWakeEngine?.isReady == true) {
                Log.i(TAG, "ONNX wake word engine ready")
            } else {
                Log.w(TAG, "ONNX wake word failed: ${onnxWakeEngine?.initError}, falling back to sherpa-onnx")
                onnxWakeEngine = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "ONNX wake word exception: ${e.message}")
            onnxWakeEngine = null
        }

        // Fallback: sherpa-onnx KWS
        if (onnxWakeEngine == null) {
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
        }

        // 2. 搜索引擎（先创建，IntentClassifier 需要它做 Function Calling）
        try {
            webSearchClient = WebSearchClient()
        } catch (e: Exception) {
            errors.add("搜索引擎: ${e.message}")
        }

        // 3. 意图分类器（智谱 API + Function Calling 联网搜索 + 关键词降级）
        try {
            intentClassifier = IntentClassifier(context, webSearchClient)
            Log.i(TAG, "IntentClassifier mode: ${intentClassifier?.mode ?: "unknown"}, search=${webSearchClient != null}")
        } catch (e: Exception) {
            errors.add("意图分类: ${e.message}")
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
        Log.i(TAG, "Pipeline initialized. ONNX=${onnxWakeEngine?.isReady}, Sherpa=${wakeWordManager?.isReady()}, mode=${intentClassifier?.mode}")
    }

    fun start() {
        if (onnxWakeEngine?.isReady == true) {
            onnxWakeEngine?.start()
        } else if (wakeWordManager?.isReady() == true) {
            wakeWordManager?.start()
        }
        state = PipelineState.IDLE
    }

    fun pause() {
        onnxWakeEngine?.stop()
        wakeWordManager?.stop()
        voiceCommandManager?.cancelListening()
        chatEngine?.stopSpeaking()
        state = PipelineState.IDLE
    }

    fun release() {
        onnxWakeEngine?.release()
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
        // 先暂停唤醒词检测，释放 AudioRecord，避免和 ASR 抢麦克风
        onnxWakeEngine?.stop()
        wakeWordManager?.stop()
        handler.postDelayed({ startListening() }, 800)  // 800ms 等待 AudioRecord 完全释放+新 recognizer 创建
    }

    private fun startListening() {
        state = PipelineState.LISTENING
        voiceCommandManager?.startListening()
    }

    private fun resumeWakeDetection() {
        onnxWakeEngine?.start()
        wakeWordManager?.start()
    }

    private fun onAsrResult(text: String, isFinal: Boolean) {
        callback.onAsrResult(text, isFinal)
        if (isFinal) {
            if (text.isBlank()) {
                chatEngine?.speak("我没有听清，请再说一次")
                state = PipelineState.IDLE
                resumeWakeDetection()
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
                resumeWakeDetection()
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
                resumeWakeDetection()
            }
        }
        handler.postDelayed({
            if (state == PipelineState.SPEAKING) {
                state = PipelineState.IDLE
                resumeWakeDetection()
            }
        }, 30000)
    }
}
