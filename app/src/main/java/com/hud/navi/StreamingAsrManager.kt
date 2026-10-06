/*
 * StreamingAsrManager.kt - sherpa-onnx 离线流式语音识别 (v13.12)
 *
 * 替代 Android SpeechRecognizer，使用 sherpa-onnx OnlineRecognizer 实现：
 * - 完全离线，不依赖 Google 语音服务
 * - 流式识别：边说边出字（Siri 风格）
 * - 内置端点检测（VAD）：自动判断语音结束
 *
 * 模型: sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23-mobile
 * 架构: Transducer (encoder + decoder + joiner)
 * 大小: ~24MB (int8 量化)
 */

package com.hud.navi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class StreamingAsrManager(
    private val context: Context,
    private val onResult: (String, Boolean) -> Unit  // (text, isFinal)
) {
    companion object {
        private const val TAG = "StreamingAsr"
        private const val SAMPLE_RATE = 16000

        // 音频参数
        private const val CHUNK_SAMPLES = 1600  // 100ms @ 16kHz

        // 端点检测参数
        private const val ENDPOINT_RULE1_MIN_TRAILING_SILENCE = 2.4f  // 长句后 2.4s 静音触发
        private const val ENDPOINT_RULE2_MIN_TRAILING_SILENCE = 1.2f  // 有识别结果后 1.2s 静音触发
        private const val ENDPOINT_RULE3_MIN_UTTERANCE_LENGTH = 20.0f // 超长句 20s 强制断句

        private const val ASSET_DIR = "asr"
        private const val MODEL_DIR = "asr_models"
        private const val ENCODER_FILE = "encoder.int8.onnx"
        private const val DECODER_FILE = "decoder.onnx"
        private const val JOINER_FILE = "joiner.int8.onnx"
        private const val TOKENS_FILE = "tokens.txt"
    }

    private var recognizer: OnlineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var listenThread: Thread? = null
    private val listening = AtomicBoolean(false)

    var isReady = false
        private set
    var initError: String? = null
        private set

    /**
     * 初始化：从 assets 提取模型文件，创建 OnlineRecognizer
     */
    fun init(): Boolean {
        initError = null
        try {
            val modelDir = File(context.filesDir, MODEL_DIR)
            if (!modelDir.exists()) modelDir.mkdirs()

            val files = listOf(ENCODER_FILE, DECODER_FILE, JOINER_FILE, TOKENS_FILE)

            // 提取 assets 到 internal storage
            for (fileName in files) {
                val target = File(modelDir, fileName)
                if (!target.exists() || target.length() < 100) {
                    context.assets.open("$ASSET_DIR/$fileName").use { input ->
                        FileOutputStream(target).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.i(TAG, "Extracted: $fileName (${target.length()} bytes)")
                }
            }

            // 创建 OnlineRecognizer
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(
                    sampleRate = SAMPLE_RATE,
                    featureDim = 80
                ),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(modelDir, ENCODER_FILE).absolutePath,
                        decoder = File(modelDir, DECODER_FILE).absolutePath,
                        joiner = File(modelDir, JOINER_FILE).absolutePath
                    ),
                    tokens = File(modelDir, TOKENS_FILE).absolutePath,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                ),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, ENDPOINT_RULE1_MIN_TRAILING_SILENCE, 0.0f),
                    rule2 = EndpointRule(true, ENDPOINT_RULE2_MIN_TRAILING_SILENCE, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, ENDPOINT_RULE3_MIN_UTTERANCE_LENGTH)
                ),
                decodingMethod = "greedy_search",
                enableEndpoint = true
            )

            recognizer = OnlineRecognizer(config)
            isReady = true
            Log.i(TAG, "StreamingAsrManager initialized (Transducer, 24MB)")
            return true
        } catch (e: Exception) {
            initError = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "Init failed: $initError", e)
            isReady = false
            return false
        }
    }

    /**
     * 开始流式识别
     */
    fun startListening() {
        if (!isReady || listening.get()) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted")
            onResult("需要录音权限", true)
            return
        }

        listening.set(true)

        // 创建 AudioRecord
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(CHUNK_SAMPLES * 4)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "VOICE_RECOGNITION failed, trying MIC")
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            listening.set(false)
            onResult("麦克风初始化失败", true)
            return
        }

        audioRecord?.startRecording()
        Log.i(TAG, "Streaming ASR listening started")

        listenThread = Thread { asrLoop() }.apply {
            name = "StreamingAsrThread"
            isDaemon = true
            start()
        }
    }

    /**
     * 停止识别
     */
    fun stopListening() {
        listening.set(false)
        val thread = listenThread
        listenThread = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { thread?.join(500) } catch (_: Exception) {}
        Log.d(TAG, "Stopped")
    }

    fun release() {
        stopListening()
        recognizer?.release()
        recognizer = null
        isReady = false
    }

    // ==================== 核心：流式识别循环 ====================

    private fun asrLoop() {
        val buffer = ShortArray(CHUNK_SAMPLES)

        try {
            val rec = recognizer ?: return
            val stream = rec.createStream()
            var lastText = ""
            var hasResult = false

            while (listening.get()) {
                val readCount = audioRecord?.read(buffer, 0, CHUNK_SAMPLES) ?: 0
                if (readCount <= 0) continue

                // 转 float [-1.0, 1.0]
                val floatBuf = FloatArray(readCount)
                for (i in 0 until readCount) {
                    floatBuf[i] = buffer[i].toFloat() / 32768.0f
                }

                // 喂给 stream
                stream.acceptWaveform(floatBuf, SAMPLE_RATE)

                // 解码
                while (rec.isReady(stream)) {
                    rec.decodeStream(stream)
                }

                // 获取当前识别结果
                val result = rec.getResult(stream)
                val text = result.text

                // 流式输出：文本变化时发送 partial result
                if (text.isNotBlank() && text != lastText) {
                    lastText = text
                    hasResult = true
                    onResult(text, false)  // partial
                }

                // 端点检测
                val isEndpoint = rec.isEndpoint(stream)

                if (isEndpoint && hasResult) {
                    // 端点触发 → 发送 final result
                    val finalText = lastText
                    Log.i(TAG, "Endpoint detected: '$finalText'")
                    onResult(finalText, true)  // final
                    listening.set(false)
                    return
                }
            }

            // 正常退出（手动停止）
            if (hasResult && lastText.isNotBlank()) {
                onResult(lastText, true)
            } else {
                onResult("", true)
            }

        } catch (e: Exception) {
            Log.e(TAG, "ASR loop error: ${e.message}", e)
            onResult("", true)
        } finally {
            listening.set(false)
        }
    }
}
