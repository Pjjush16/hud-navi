/*
 * VoiceCommandManager.kt - 唤醒后语音指令管理器 (v11.0)
 *
 * 唤醒词检测成功后，接管录音流程：
 *   1. 播放"我在"提示音
 *   2. 开始录音（用户说话）
 *   3. 检测语音结束（静音超时 / 最大时长）
 *   4. 调用 ASR 转文字
 *   5. 将文本交给 IntentClassifier 分类
 *
 * 当前 ASR 使用 Android 内置 SpeechRecognizer（免模型文件）。
 * 后续可切换为 sherpa-onnx Whisper 离线识别（完全离线，无需网络）。
 */

package com.hud.navi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
        private const val SAMPLE_RATE = 16000
        private const val MAX_LISTEN_MS = 8000L     // 最大录音 8 秒
        private const val SILENCE_TIMEOUT_MS = 2000L // 静音 2 秒视为结束
        private const val MIN_SPEECH_MS = 300L       // 最短有效语音 300ms
        private const val NO_SPEECH_DISMISS_MS = 2000L  // v13.11: 唤醒后 2 秒内无语音自动收起
    }

    private var recognizer: SpeechRecognizer? = null
    private val recording = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var silenceTimer: Runnable? = null
    private var maxTimer: Runnable? = null
    private var noSpeechDismissTimer: Runnable? = null  // v13.11: 无语音自动收起
    private var speechStartTime = 0L
    private var hasSpeech = false

    /**
     * 初始化语音识别器
     */
    fun init(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition not available on this device")
            return false
        }

        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(createRecognitionListener())
            Log.i(TAG, "VoiceCommandManager initialized")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Init failed: ${e.message}", e)
            return false
        }
    }

    /**
     * 开始录音（唤醒词触发后调用）
     * 每次调用都重建 SpeechRecognizer，确保音频管线干净
     */
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
        hasSpeech = false
        speechStartTime = System.currentTimeMillis()

        // 重建 recognizer — 避免复用导致内部音频状态残留
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
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MIN_SPEECH_MS.toInt())
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_TIMEOUT_MS.toInt())
        }

        try {
            recognizer?.startListening(intent)
            Log.i(TAG, "Started listening (fresh recognizer)...")

            // v13.11: 2 秒内无语音自动收起（不等满 8 秒）
            noSpeechDismissTimer = Runnable {
                if (recording.get() && !hasSpeech) {
                    Log.i(TAG, "No speech detected in ${NO_SPEECH_DISMISS_MS}ms, auto-dismiss")
                    recording.set(false)
                    try { recognizer?.cancel() } catch (_: Exception) {}
                    onResult("", true)
                }
            }
            handler.postDelayed(noSpeechDismissTimer!!, NO_SPEECH_DISMISS_MS)

            // 设置最大录音时间
            maxTimer = Runnable {
                if (recording.get()) {
                    Log.i(TAG, "Max recording time reached, stopping")
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

    /**
     * 停止录音
     */
    fun stopListening() {
        if (!recording.get()) return
        recording.set(false)

        // 清理计时器
        silenceTimer?.let { handler.removeCallbacks(it) }
        maxTimer?.let { handler.removeCallbacks(it) }
        noSpeechDismissTimer?.let { handler.removeCallbacks(it) }
        silenceTimer = null
        maxTimer = null
        noSpeechDismissTimer = null

        try {
            recognizer?.stopListening()
        } catch (e: Exception) {
            Log.w(TAG, "stopListening error: ${e.message}")
        }
    }

    /**
     * 取消录音
     */
    fun cancelListening() {
        if (!recording.get()) return
        recording.set(false)

        silenceTimer?.let { handler.removeCallbacks(it) }
        maxTimer?.let { handler.removeCallbacks(it) }
        noSpeechDismissTimer?.let { handler.removeCallbacks(it) }

        try {
            recognizer?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "cancel error: ${e.message}")
        }
    }

    fun isRecording(): Boolean = recording.get()

    fun release() {
        cancelListening()
        recognizer?.destroy()
        recognizer = null
    }

    // ==================== 识别监听器 ====================

    private fun createRecognitionListener() = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech started")
            hasSpeech = true
            speechStartTime = System.currentTimeMillis()
            // v13.11: 检测到语音后取消无语音自动收起计时器
            noSpeechDismissTimer?.let { handler.removeCallbacks(it) }
            noSpeechDismissTimer = null
        }

        override fun onRmsChanged(rmsdB: Float) {
            // 可选：用于 UI 显示音量条
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "Speech ended")
            // 如果语音持续时间太短，忽略
            val duration = System.currentTimeMillis() - speechStartTime
            if (duration < MIN_SPEECH_MS) {
                Log.w(TAG, "Speech too short: ${duration}ms")
                recording.set(false)
                onResult("", true)
            }
        }

        override fun onResults(results: Bundle?) {
            recording.set(false)
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""

            if (text.isNotBlank()) {
                Log.i(TAG, "Final result: $text")
                onResult(text, true)
            } else {
                Log.w(TAG, "Empty result")
                onResult("", true)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""

            if (text.isNotBlank()) {
                Log.d(TAG, "Partial: $text")
                onResult(text, false)

                // 重置静音计时器
                silenceTimer?.let { handler.removeCallbacks(it) }
                silenceTimer = Runnable {
                    if (recording.get()) {
                        Log.i(TAG, "Silence timeout, stopping")
                        stopListening()
                    }
                }
                handler.postDelayed(silenceTimer!!, SILENCE_TIMEOUT_MS)
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
            Log.w(TAG, "Recognition error: $errorMsg (code=$error)")
            // v13.11: 将错误信息作为文本返回，让用户看到发生了什么
            // 而不是静默返回空字符串（之前用户说了半天话什么都不知道）
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                onResult("", true)  // 无匹配/超时 → 空文本触发"没听清"
            } else {
                onResult("语音识别: $errorMsg", true)  // 其他错误 → 显示错误信息
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
