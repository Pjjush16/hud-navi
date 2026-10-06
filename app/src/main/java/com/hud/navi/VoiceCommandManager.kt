/*
 * VoiceCommandManager.kt - 唤醒后语音指令管理器 (v13.12 离线流式ASR)
 *
 * 唤醒词检测成功后，接管录音流程：
 *   1. 使用 sherpa-onnx 离线流式 ASR（优先）或 Android SpeechRecognizer（降级）
 *   2. 流式输出：边说边出字（Siri 风格）
 *   3. 内置端点检测：自动判断语音结束
 *   4. 将文本交给 IntentClassifier 分类
 */

package com.hud.navi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class VoiceCommandManager(
    private val context: Context,
    private val onResult: (String, Boolean) -> Unit  // (text, isFinal)
) {
    companion object {
        private const val TAG = "VoiceCommand"
        private const val MAX_LISTEN_MS = 15000L     // v13.12: 最大录音 15 秒（流式 ASR 有端点检测，但设安全上限）
    }

    // 离线流式 ASR（优先）
    private var streamingAsr: StreamingAsrManager? = null
    // Android SpeechRecognizer（降级备用）
    private var recognizer: SpeechRecognizer? = null

    private val recording = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var maxTimer: Runnable? = null
    private var useOfflineAsr = false

    var asrMode: String = "unknown"
        private set

    fun init(): Boolean {
        // 优先初始化离线流式 ASR
        try {
            streamingAsr = StreamingAsrManager(context) { text, isFinal ->
                // 确保在主线程回调
                handler.post { onResult(text, isFinal) }
            }
            if (streamingAsr!!.init()) {
                useOfflineAsr = true
                asrMode = "offline-streaming"
                Log.i(TAG, "Offline streaming ASR initialized")
                return true
            } else {
                Log.w(TAG, "Offline ASR init failed: ${streamingAsr!!.initError}, falling back to SpeechRecognizer")
                streamingAsr = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Offline ASR exception: ${e.message}, falling back")
            streamingAsr = null
        }

        // 降级：Android SpeechRecognizer
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition not available on this device")
            return false
        }

        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(createRecognitionListener())
            useOfflineAsr = false
            asrMode = "android-cloud"
            Log.i(TAG, "VoiceCommandManager initialized (SpeechRecognizer fallback)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Init failed: ${e.message}", e)
            return false
        }
    }

    fun startListening() {
        if (recording.get()) {
            Log.w(TAG, "Already recording")
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted")
            onResult("需要录音权限", true)
            return
        }

        recording.set(true)

        if (useOfflineAsr) {
            startOfflineAsr()
        } else {
            startSpeechRecognizer()
        }
    }

    fun stopListening() {
        if (!recording.get()) return
        recording.set(false)

        maxTimer?.let { handler.removeCallbacks(it) }
        maxTimer = null

        if (useOfflineAsr) {
            streamingAsr?.stopListening()
        } else {
            try { recognizer?.stopListening() } catch (_: Exception) {}
        }
    }

    fun cancelListening() {
        if (!recording.get()) return
        recording.set(false)

        maxTimer?.let { handler.removeCallbacks(it) }
        maxTimer = null

        if (useOfflineAsr) {
            streamingAsr?.stopListening()
        } else {
            try { recognizer?.cancel() } catch (_: Exception) {}
        }
    }

    fun isRecording(): Boolean = recording.get()

    fun release() {
        cancelListening()
        streamingAsr?.release()
        streamingAsr = null
        recognizer?.destroy()
        recognizer = null
    }

    // ==================== 离线流式 ASR ====================

    private fun startOfflineAsr() {
        Log.i(TAG, "Starting offline streaming ASR...")

        // 设置安全超时
        maxTimer = Runnable {
            if (recording.get()) {
                Log.i(TAG, "Max recording time reached")
                stopListening()
            }
        }
        handler.postDelayed(maxTimer!!, MAX_LISTEN_MS)

        streamingAsr?.startListening()
    }

    // ==================== Android SpeechRecognizer（降级） ====================

    private fun startSpeechRecognizer() {
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {}
        recognizer = null

        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(createRecognitionListener())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create recognizer: ${e.message}", e)
            recording.set(false)
            onResult("语音识别初始化失败", true)
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toString())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        try {
            recognizer?.startListening(intent)
            Log.i(TAG, "Started SpeechRecognizer (fallback)...")

            maxTimer = Runnable {
                if (recording.get()) {
                    Log.i(TAG, "Max recording time reached (SpeechRecognizer)")
                    stopListening()
                }
            }
            handler.postDelayed(maxTimer!!, MAX_LISTEN_MS)
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed: ${e.message}", e)
            recording.set(false)
            onResult("语音识别启动失败: ${e.message}", true)
        }
    }

    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onResults(results: Bundle?) {
            recording.set(false)
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            if (text.isNotBlank()) {
                onResult(text, true)
            } else {
                onResult("", true)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            if (text.isNotBlank()) {
                onResult(text, false)
            }
        }

        override fun onError(error: Int) {
            recording.set(false)
            val errorMsg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "没有识别到语音"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音输入超时"
                SpeechRecognizer.ERROR_AUDIO -> "音频录制错误"
                SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙"
                SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                else -> "未知错误 ($error)"
            }
            Log.w(TAG, "SpeechRecognizer error: $errorMsg (code=$error)")
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                onResult("", true)
            } else {
                onResult("语音识别: $errorMsg", true)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
