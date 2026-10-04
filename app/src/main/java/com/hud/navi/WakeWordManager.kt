/*
 * WakeWordManager.kt - 语音唤醒词识别管理器 (v12.9)
 *
 * 修复核心问题：音频被切成碎片导致唤醒词识别率极低
 *
 * v12.9 改动：
 * 1. chunk 从 100ms 增大到 200ms（3200 samples），每次喂够足够音频
 * 2. 不再在每次检测后重建 stream（丢失上下文是"剪成两半"的元凶）
 * 3. 先积累 500ms 音频再开始 decode，确保模型有足够上下文
 * 4. 添加能量检测，静音时跳过 decode 节省算力
 * 5. 增加更多声调变体和常见误读
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
import kotlin.math.sqrt

class WakeWordManager(
    private val context: Context,
    private val onWake: (String) -> Unit
) {
    companion object {
        private const val TAG = "WakeWord"
        private const val SAMPLE_RATE = 16000

        // 200ms chunk（之前 100ms 太小，"哈德"约 400-600ms，100ms 只看到 1/4 就被 decode）
        private const val CHUNK_MS = 200
        private const val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_MS / 1000 // 3200

        // 最少积累 500ms 音频再开始 decode（确保模型看到完整音节）
        private const val MIN_AUDIO_MS = 500
        private const val MIN_CHUNKS_BEFORE_DECODE = MIN_AUDIO_MS / CHUNK_MS // 3 个 chunk = 600ms

        // 能量阈值：低于此值的 chunk 视为静音，跳过 decode
        private const val ENERGY_THRESHOLD = 0.005f

        private const val ASSET_DIR = "kws"
        private const val ENCODER_FILE = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val DECODER_FILE = "decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
        private const val JOINER_FILE = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val TOKENS_FILE = "tokens.txt"
        private const val KEYWORDS_FILE = "keywords.txt"

        private const val MODEL_DIR = "kws_models"
        private const val ASSETS_VERSION = 4  // 每次更新 keywords.txt 时递增
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
    private val WAKE_COOLDOWN_MS = 1500L  // 1.5 秒冷却（之前 2 秒太长）

    fun init() {
        initError = null
        try {
            val modelDir = File(context.filesDir, MODEL_DIR)
            if (!modelDir.exists()) modelDir.mkdirs()

            val modelFiles = listOf(ENCODER_FILE, DECODER_FILE, JOINER_FILE, TOKENS_FILE, KEYWORDS_FILE)

            // 版本检查：ASSETS_VERSION 递增时强制重新释放所有模型文件
            val versionFile = File(modelDir, "assets_version")
            val currentVersion = if (versionFile.exists()) versionFile.readText().trim().toIntOrNull() ?: 0 else 0
            if (currentVersion < ASSETS_VERSION) {
                Log.i(TAG, "Assets version updated ($currentVersion → $ASSETS_VERSION), re-extracting all models")
                modelFiles.forEach { f -> File(modelDir, f).delete() }
            }

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
            versionFile.writeText(ASSETS_VERSION.toString())

            val encoderPath = File(modelDir, ENCODER_FILE).absolutePath
            val decoderPath = File(modelDir, DECODER_FILE).absolutePath
            val joinerPath = File(modelDir, JOINER_FILE).absolutePath
            val tokensPath = File(modelDir, TOKENS_FILE).absolutePath
            val keywordsPath = File(modelDir, KEYWORDS_FILE).absolutePath

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
                keywordsScore = 3.0f,
                keywordsThreshold = 0.05f,  // 进一步降低阈值（之前 0.08 仍然偏严格）
                numTrailingBlanks = 1
            )

            Log.i(TAG, "Creating KeywordSpotter...")
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
        ).coerceAtLeast(CHUNK_SAMPLES * 4) // 至少 4 倍 chunk 大小的缓冲区

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, // 用 VOICE_RECOGNITION 而非 MIC，跳过系统降噪
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed, trying MIC fallback...")
            // 降级到 MIC
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord MIC fallback also failed")
                running.set(false)
                return
            }
        }

        audioRecord?.startRecording()
        Log.i(TAG, "Listening started: chunk=${CHUNK_MS}ms, min_decode=${MIN_CHUNKS_BEFORE_DECODE} chunks, energy_threshold=$ENERGY_THRESHOLD")

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
        val buffer = ShortArray(CHUNK_SAMPLES)
        var chunkCount = 0L
        var lastLogTime = System.currentTimeMillis()
        var consecutiveEmptyReads = 0
        var stream: OnlineStream = spotter!!.createStream()
        var chunksSinceLastReset = 0  // 跟踪 stream 积累了多少 chunk

        Log.i(TAG, "Listen loop started, stream created")

        try {
            while (running.get()) {
                // 读取 200ms 音频
                val readCount = audioRecord?.read(buffer, 0, CHUNK_SAMPLES) ?: 0
                if (readCount <= 0) {
                    consecutiveEmptyReads++
                    if (consecutiveEmptyReads > 250) { // 250 * 200ms = 50 秒无数据
                        Log.w(TAG, "Microphone dead for 50s, recreating AudioRecord...")
                        recreateAudioRecord()
                        stream = spotter!!.createStream()
                        chunksSinceLastReset = 0
                        consecutiveEmptyReads = 0
                    }
                    continue
                }
                consecutiveEmptyReads = 0
                chunkCount++
                chunksSinceLastReset++

                // 转为 float 并计算 RMS 能量
                val floatBuf = FloatArray(readCount)
                var sumSquares = 0.0
                for (i in 0 until readCount) {
                    floatBuf[i] = buffer[i].toFloat() / 32768.0f
                    sumSquares += (floatBuf[i] * floatBuf[i]).toDouble()
                }
                val rms = sqrt(sumSquares / readCount).toFloat()

                // 静音跳过（节省算力，但不重置 stream）
                if (rms < ENERGY_THRESHOLD) {
                    continue
                }

                // 喂给模型
                stream.acceptWaveform(floatBuf, SAMPLE_RATE)

                // 至少积累 MIN_CHUNKS_BEFORE_DECODE 个 chunk 再 decode
                // 这是关键修复：之前 100ms 就 decode，"哈德"被切成两半
                if (chunksSinceLastReset < MIN_CHUNKS_BEFORE_DECODE) {
                    continue
                }

                // decode 并检查关键词
                while (spotter!!.isReady(stream)) {
                    spotter!!.decode(stream)
                    val result: KeywordSpotterResult = spotter!!.getResult(stream)
                    val keyword = result.keyword
                    if (keyword.isNotBlank()) {
                        val now = System.currentTimeMillis()
                        if (now - lastWakeTime >= WAKE_COOLDOWN_MS) {
                            lastWakeTime = now
                            Log.i(TAG, "[WAKE] '$keyword' rms=$rms chunks=$chunksSinceLastReset")
                            onWake(keyword.trim())
                        } else {
                            Log.d(TAG, "[WAKE-SKIP] '$keyword' cooldown ${now - lastWakeTime}ms")
                        }
                        // ★ 关键修复：不重建 stream！
                        // 之前的 stream = spotter.createStream() 会把积累的音频上下文全部丢失，
                        // 导致用户连续说两次"哈德"时第二次开头被截断。
                        // 冷却机制已经防止了重复触发，不需要靠重建 stream 来去重。
                        break
                    }
                }

                // 如果 stream 积累了太多音频（超过 5 秒），定期重建防止内存膨胀
                if (chunksSinceLastReset > 25) { // 25 * 200ms = 5 秒
                    Log.d(TAG, "Stream accumulated ${chunksSinceLastReset} chunks, refreshing")
                    stream = spotter!!.createStream()
                    chunksSinceLastReset = 0
                }

                // 心跳日志（每 15 秒一次）
                val now = System.currentTimeMillis()
                if (now - lastLogTime > 15000) {
                    Log.i(TAG, "Heartbeat: $chunkCount chunks, rms=$rms, stream_age=$chunksSinceLastReset, running=${running.get()}")
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

    private fun recreateAudioRecord() {
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}

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
        if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
            audioRecord?.startRecording()
            Log.i(TAG, "AudioRecord recreated")
        } else {
            Log.e(TAG, "AudioRecord recreation failed!")
        }
    }
}
