/*
 * WakeWordManager.kt - 语音唤醒词识别管理器 (v13.0 Siri-style)
 *
 * 对标 Apple Siri 唤醒检测的设计哲学：
 *
 * 1. 持续滚动缓冲：永不定时重置 stream，模型自己管理滑动窗口上下文
 * 2. 小读大批：50ms 读取（低延迟），攒够 150ms 再 decode（够上下文）
 * 3. 自适应噪声门限：动态计算背景噪声基线，阈值 = 噪声 × 3
 * 4. 仅在长时间静音后才重置 stream（>3秒无人说话）
 * 5. 冷却机制防重复触发，不靠重建 stream 来去重
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

        // ===== Siri-style 音频参数 =====
        // 小读：50ms 一次 read，保证低延迟，不丢音频
        private const val READ_MS = 50
        private const val READ_SAMPLES = SAMPLE_RATE * READ_MS / 1000 // 800 samples

        // 大批：攒够 150ms（3 个 read）再喂给模型 + decode
        // "哈德"约 400-600ms，150ms 够看到 1/3~1/4 个词，模型内部滑动窗口补全上下文
        private const val BATCH_MS = 150
        private const val BATCH_READS = BATCH_MS / READ_MS  // 3 reads per batch

        // 静音判定：连续静音超过此时间才重置 stream（3秒）
        private const val SILENCE_RESET_MS = 3000L
        private const val SILENCE_RESET_READS = (SILENCE_RESET_MS / READ_MS).toInt() // 60 reads

        // 自适应噪声门限
        private const val NOISE_HISTORY_SIZE = 200  // 跟踪最近 200 个 read 的 RMS（约 10 秒）
        private const val NOISE_GATE_MULTIPLIER = 3.0f  // 阈值 = 噪声基线 × 3
        private const val MIN_ENERGY_FLOOR = 0.002f  // 绝对最低门限（防止死寂环境误触发）

        private const val ASSET_DIR = "kws"
        private const val ENCODER_FILE = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val DECODER_FILE = "decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
        private const val JOINER_FILE = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val TOKENS_FILE = "tokens.txt"
        private const val KEYWORDS_FILE = "keywords.txt"

        private const val MODEL_DIR = "kws_models"
        private const val ASSETS_VERSION = 6  // 每次更新 keywords.txt 时递增
    }

    private var audioRecord: AudioRecord? = null
    private var listenThread: Thread? = null
    private val running = AtomicBoolean(false)
    var kwsReady = false
        private set
    var initError: String? = null
        private set

    private var spotter: KeywordSpotter? = null

    // 冷却机制
    private var lastWakeTime = 0L
    private val WAKE_COOLDOWN_MS = 1500L

    fun init() {
        initError = null
        try {
            val modelDir = File(context.filesDir, MODEL_DIR)
            if (!modelDir.exists()) modelDir.mkdirs()

            val modelFiles = listOf(ENCODER_FILE, DECODER_FILE, JOINER_FILE, TOKENS_FILE, KEYWORDS_FILE)

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

            val config = KeywordSpotterConfig(
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
                keywordsFile = File(modelDir, KEYWORDS_FILE).absolutePath,
                keywordsScore = 3.0f,
                keywordsThreshold = 0.05f,
                numTrailingBlanks = 1
            )

            Log.i(TAG, "Creating KeywordSpotter...")
            spotter = KeywordSpotter(assetManager = null, config = config)
            Log.i(TAG, "KeywordSpotter created!")

            kwsReady = true
        } catch (e: Exception) {
            initError = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "WakeWordManager init failed: $initError", e)
            Toast.makeText(context, "语音唤醒失败: $initError", Toast.LENGTH_LONG).show()
        }
    }

    fun start() {
        if (!kwsReady) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return
        if (running.get()) return
        running.set(true)

        createAndStartAudioRecord()
        if (audioRecord == null) {
            running.set(false)
            return
        }

        Log.i(TAG, "Siri-style listening: read=${READ_MS}ms, batch=${BATCH_MS}ms, silence_reset=${SILENCE_RESET_MS}ms")

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

    // ==================== 核心：Siri 风格的持续监听循环 ====================

    private fun listenLoop() {
        val readBuf = ShortArray(READ_SAMPLES)
        // 批量缓冲区：攒够 BATCH_READS 个 read 再一起喂给模型
        val batchBuf = FloatArray(READ_SAMPLES * BATCH_READS)
        var batchIndex = 0

        var totalReads = 0L
        var lastLogTime = System.currentTimeMillis()
        var consecutiveEmptyReads = 0
        var consecutiveSilentReads = 0

        // 自适应噪声基线
        val noiseHistory = FloatArray(NOISE_HISTORY_SIZE)
        var noiseHistoryIndex = 0
        var noiseHistoryCount = 0
        var noiseFloor = MIN_ENERGY_FLOOR  // 初始值

        // 创建 stream — 整个会话期间只在长时间静音后才重建
        var stream = spotter!!.createStream()

        Log.i(TAG, "Listen loop started")

        try {
            while (running.get()) {
                // ---- 读取 50ms 音频 ----
                val readCount = audioRecord?.read(readBuf, 0, READ_SAMPLES) ?: 0
                if (readCount <= 0) {
                    consecutiveEmptyReads++
                    if (consecutiveEmptyReads > 1000) { // 50 秒无数据
                        Log.w(TAG, "Microphone dead for 50s, recreating...")
                        recreateAudioRecord()
                        stream = spotter!!.createStream()
                        batchIndex = 0
                        consecutiveEmptyReads = 0
                        consecutiveSilentReads = 0
                    }
                    continue
                }
                consecutiveEmptyReads = 0
                totalReads++

                // ---- 转 float + 计算 RMS ----
                var sumSquares = 0.0
                for (i in 0 until readCount) {
                    val sample = readBuf[i].toFloat() / 32768.0f
                    batchBuf[batchIndex * READ_SAMPLES + i] = sample
                    sumSquares += (sample * sample).toDouble()
                }
                val rms = sqrt(sumSquares / readCount).toFloat()

                // ---- 更新噪声基线（自适应） ----
                noiseHistory[noiseHistoryIndex] = rms
                noiseHistoryIndex = (noiseHistoryIndex + 1) % NOISE_HISTORY_SIZE
                if (noiseHistoryCount < NOISE_HISTORY_SIZE) noiseHistoryCount++

                if (noiseHistoryCount >= 20) {
                    // 取最近 N 个 RMS 的中位数作为噪声基线
                    val sorted = noiseHistory.copyOf(noiseHistoryCount).apply { sort() }
                    val median = sorted[noiseHistoryCount / 2]
                    // 平滑更新（防止跳变）
                    noiseFloor = noiseFloor * 0.9f + (median * NOISE_GATE_MULTIPLIER) * 0.1f
                    if (noiseFloor < MIN_ENERGY_FLOOR) noiseFloor = MIN_ENERGY_FLOOR
                }

                // ---- 静音判定 ----
                if (rms < noiseFloor) {
                    consecutiveSilentReads++
                    // 长时间静音 → 重置 stream（防止内存膨胀）
                    if (consecutiveSilentReads >= SILENCE_RESET_READS) {
                        Log.d(TAG, "Silence ${consecutiveSilentReads * READ_MS}ms, resetting stream")
                        stream = spotter!!.createStream()
                        batchIndex = 0
                        consecutiveSilentReads = 0
                    }
                    batchIndex = 0  // 静音时不积累，但也不重建 stream
                    continue
                }

                // ---- 有声 → 重置静音计数 ----
                consecutiveSilentReads = 0
                batchIndex++

                // ---- 攒够一个 batch 再喂给模型 ----
                if (batchIndex < BATCH_READS) continue

                // 把 batch 中的所有音频一次性喂给 stream
                val totalSamples = batchIndex * READ_SAMPLES
                val audioSlice = FloatArray(totalSamples)
                System.arraycopy(batchBuf, 0, audioSlice, 0, totalSamples)
                stream.acceptWaveform(audioSlice, SAMPLE_RATE)
                batchIndex = 0

                // ---- decode 并检查关键词 ----
                while (spotter!!.isReady(stream)) {
                    spotter!!.decode(stream)
                    val result: KeywordSpotterResult = spotter!!.getResult(stream)
                    val keyword = result.keyword
                    if (keyword.isNotBlank()) {
                        val now = System.currentTimeMillis()
                        if (now - lastWakeTime >= WAKE_COOLDOWN_MS) {
                            lastWakeTime = now
                            Log.i(TAG, "[WAKE] '$keyword' rms=$rms floor=$noiseFloor reads=$totalReads")
                            onWake(keyword.trim())
                        }
                        // ★ 不重建 stream！让模型保持上下文
                        break
                    }
                }

                // ---- 心跳日志 ----
                val now = System.currentTimeMillis()
                if (now - lastLogTime > 15000) {
                    Log.i(TAG, "Heartbeat: reads=$totalReads, floor=$noiseFloor, rms=$rms, running=${running.get()}")
                    lastLogTime = now
                }
            }
            Log.i(TAG, "Listen loop exited, total reads: $totalReads")
        } catch (e: InterruptedException) {
            Log.i(TAG, "Listen loop interrupted (normal)")
        } catch (e: Exception) {
            Log.e(TAG, "Listen error: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    private fun createAndStartAudioRecord() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(READ_SAMPLES * 8)  // 8 倍 read 大小的缓冲区

        // 优先用 VOICE_RECOGNITION（跳过系统降噪，保留原始音频）
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "VOICE_RECOGNITION failed, trying MIC fallback...")
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        }

        if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
            audioRecord?.startRecording()
        } else {
            Log.e(TAG, "AudioRecord init failed completely!")
            audioRecord = null
        }
    }

    private fun recreateAudioRecord() {
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        createAndStartAudioRecord()
        Log.i(TAG, "AudioRecord recreated: ${audioRecord?.state}")
    }
}
