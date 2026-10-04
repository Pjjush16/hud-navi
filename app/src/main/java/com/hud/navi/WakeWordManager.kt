/*
 * WakeWordManager.kt - 语音唤醒词识别管理器 (v10.17)
 *
 * 基于 sherpa-onnx 官方 Android Kotlin API（data class 直接构造，非反射，非 Builder）。
 * 支持中文唤醒词：哈德 / 你好小哈 / 小哈 / 哈德哈德
 *
 * 模型: sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile
 */

package com.hud.navi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.KeywordSpotterResult
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class WakeWordManager(
    private val context: Context,
    private val onWake: (String) -> Unit
) {
    companion object {
        private const val TAG = "WakeWord"
        private const val SAMPLE_RATE = 16000

        private const val ASSET_DIR = "kws"
        private const val ENCODER_FILE = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val DECODER_FILE = "decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
        private const val JOINER_FILE = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val TOKENS_FILE = "tokens.txt"
        private const val KEYWORDS_FILE = "keywords.txt"

        private const val MODEL_DIR = "kws_models"
    }

    private var audioRecord: AudioRecord? = null
    private var listenThread: Thread? = null
    private val running = AtomicBoolean(false)
    var kwsReady = false
        private set
    var initError: String? = null
        private set

    private var spotter: KeywordSpotter? = null

    // 冷却机制：防止同一次唤醒词触发多次
    private var lastWakeTime = 0L
    private val WAKE_COOLDOWN_MS = 2000L  // 2秒冷却

    fun init() {
        initError = null
        try {
            val modelDir = File(context.filesDir, MODEL_DIR)
            if (!modelDir.exists()) modelDir.mkdirs()

            // 1. 从 assets 释放模型文件到内部存储
            val modelFiles = listOf(ENCODER_FILE, DECODER_FILE, JOINER_FILE, TOKENS_FILE, KEYWORDS_FILE)
            val missingFiles = mutableListOf<String>()

            for (fileName in modelFiles) {
                val target = File(modelDir, fileName)
                if (!target.exists() || target.length() < 100) {
                    try {
                        context.assets.open("$ASSET_DIR/$fileName").use { input ->
                            FileOutputStream(target).use { output ->
                                input.copyTo(output)
                            }
                        }
                        Log.i(TAG, "Extracted: $fileName (${target.length()} bytes)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Asset missing: $ASSET_DIR/$fileName — ${e.message}")
                        missingFiles.add(fileName)
                    }
                }
            }

            if (missingFiles.isNotEmpty()) {
                initError = "模型文件缺失: ${missingFiles.joinToString(", ")}"
                Log.e(TAG, initError!!)
                Toast.makeText(context, "语音唤醒失败: $initError", Toast.LENGTH_LONG).show()
                return
            }

            Log.i(TAG, "All 5 model files present in $modelDir")

            // 2. 使用官方 Kotlin data class 构建配置（非反射、非 Builder）
            val encoderPath = File(modelDir, ENCODER_FILE).absolutePath
            val decoderPath = File(modelDir, DECODER_FILE).absolutePath
            val joinerPath = File(modelDir, JOINER_FILE).absolutePath
            val tokensPath = File(modelDir, TOKENS_FILE).absolutePath
            val keywordsPath = File(modelDir, KEYWORDS_FILE).absolutePath

            Log.i(TAG, "Building KeywordSpotterConfig with Kotlin data class constructors...")

            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(
                    sampleRate = SAMPLE_RATE,
                    featureDim = 80
                ),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = encoderPath,
                        decoder = decoderPath,
                        joiner = joinerPath
                    ),
                    tokens = tokensPath,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                ),
                keywordsFile = keywordsPath,
                keywordsScore = 3.0f,    // 提高关键词权重，短词（哈德）更容易被识别
                keywordsThreshold = 0.08f, // 降低阈值，提高灵敏度（之前0.15太严格）
                numTrailingBlanks = 1    // 减少尾随空白，加快响应速度
            )

            Log.i(TAG, "Creating KeywordSpotter (newFromFile mode)...")
            spotter = KeywordSpotter(assetManager = null, config = config)
            Log.i(TAG, "KeywordSpotter created successfully!")

            kwsReady = true
        } catch (e: Exception) {
            initError = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "WakeWordManager init failed: $initError", e)
            Toast.makeText(context, "语音唤醒失败: $initError", Toast.LENGTH_LONG).show()
        }
    }

    fun start() {
        if (!kwsReady) {
            Log.w(TAG, "KWS not ready: ${initError ?: "unknown"}")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted")
            return
        }
        if (running.get()) return
        running.set(true)

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            running.set(false)
            return
        }

        audioRecord?.startRecording()
        Log.i(TAG, "Listening: 哈德 / 你好小哈 / 小哈 / 哈德哈德")

        listenThread = Thread { listenLoop() }.apply {
            name = "WakeWordThread"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        val thread = listenThread
        listenThread = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        // 等待线程结束，确保 AudioRecord 完全释放
        try { thread?.join(500) } catch (_: Exception) {}
        Log.i(TAG, "Stopped")
    }

    fun release() {
        stop()
        spotter?.release()
        spotter = null
        kwsReady = false
    }

    fun isReady(): Boolean = kwsReady

    private fun listenLoop() {
        val chunkSamples = 1600 // 100ms
        val buffer = ShortArray(chunkSamples)
        var chunkCount = 0L
        var lastLogTime = System.currentTimeMillis()
        var silenceChunks = 0L

        try {
            var stream: OnlineStream = spotter!!.createStream()
            Log.i(TAG, "Listen loop started, stream created")

            while (running.get()) {
                val readCount = audioRecord?.read(buffer, 0, chunkSamples) ?: 0
                if (readCount <= 0) {
                    silenceChunks++
                    // 连续500个空chunk（约50秒）重建stream，防止stream卡死
                    if (silenceChunks > 500) {
                        Log.w(TAG, "Too many empty reads, recreating stream")
                        try { stream = spotter!!.createStream() } catch (_: Exception) {}
                        silenceChunks = 0
                    }
                    continue
                }
                silenceChunks = 0

                chunkCount++
                val floatBuf = FloatArray(readCount)
                for (i in 0 until readCount) {
                    floatBuf[i] = buffer[i].toFloat() / 32768.0f
                }

                stream.acceptWaveform(floatBuf, SAMPLE_RATE)

                while (spotter!!.isReady(stream)) {
                    spotter!!.decode(stream)
                    val result: KeywordSpotterResult = spotter!!.getResult(stream)
                    val keyword = result.keyword
                    if (keyword.isNotBlank()) {
                        val now = System.currentTimeMillis()
                        if (now - lastWakeTime >= WAKE_COOLDOWN_MS) {
                            lastWakeTime = now
                            Log.i(TAG, "[WAKE] $keyword (score accepted)")
                            onWake(keyword.trim())
                        } else {
                            Log.d(TAG, "[WAKE-SKIP] $keyword (cooldown ${now - lastWakeTime}ms)")
                        }
                        // 无论是否触发，检测到关键词后重建stream
                        // 清除缓冲区中残留的相同音节，防止重复触发
                        try { stream = spotter!!.createStream() } catch (_: Exception) {}
                        break
                    }
                }

                // 心跳日志
                val now = System.currentTimeMillis()
                if (now - lastLogTime > 10000) {
                    Log.i(TAG, "Heartbeat: $chunkCount chunks, running=${running.get()}, cooldown_active=${now - lastWakeTime < WAKE_COOLDOWN_MS}")
                    lastLogTime = now
                }
            }
            Log.i(TAG, "Listen loop exited normally, total chunks: $chunkCount")
        } catch (e: InterruptedException) {
            Log.i(TAG, "Listen loop interrupted (normal stop)")
        } catch (e: Exception) {
            Log.e(TAG, "Listen error: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }
}
