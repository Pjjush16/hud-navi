/*
 * WakeWordManager.kt - 语音唤醒词识别管理器
 * hud-navi 语音交互第一步：关键词识别
 *
 * 基于 sherpa-onnx KeywordSpotter，完全离线，支持中文唤醒词：
 *   - 哈德 (hā dé)
 *   - 你好小哈
 *   - 小哈
 *   - 哈德哈德
 *
 * 使用方式：
 *   val wm = WakeWordManager(context, onWake = { keyword -> Log.i("WAKE", keyword) })
 *   wm.start()   // 在 onResume 中调用
 *   wm.stop()    // 在 onPause 中调用
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
        private const val CHANNELS = 1

        // 模型文件在 assets 中的路径
        private const val ASSET_ENCODER = "kws/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val ASSET_DECODER = "kws/decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
        private const val ASSET_JOINER = "kws/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val ASSET_TOKENS = "kws/tokens.txt"
        private const val ASSET_KEYWORDS = "kws/keywords.txt"

        // 模型释放到内部存储后的路径
        private const val MODEL_DIR = "kws_models"
    }

    private var audioRecord: AudioRecord? = null
    private var listenThread: Thread? = null
    private val running = AtomicBoolean(false)
    private var kwsReady = false

    // sherpa-onnx 对象（通过反射加载，避免编译期依赖）
    private var keywordSpotter: Any? = null
    private var stream: Any? = null

    /**
     * 初始化：从 assets 复制模型文件到内部存储，创建 KeywordSpotter
     */
    fun init() {
        try {
            // 1. 复制模型文件到内部存储
            val modelDir = File(context.filesDir, MODEL_DIR)
            if (!modelDir.exists()) modelDir.mkdirs()

            val files = listOf(ASSET_ENCODER, ASSET_DECODER, ASSET_JOINER, ASSET_TOKENS, ASSET_KEYWORDS)
            for (assetPath in files) {
                val fileName = assetPath.substringAfterLast("/")
                val targetFile = File(modelDir, fileName)
                if (!targetFile.exists() || targetFile.length() < 100) {
                    Log.i(TAG, "Extracting asset: $assetPath")
                    try {
                        context.assets.open(assetPath).use { input ->
                            FileOutputStream(targetFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        Log.i(TAG, "  -> ${targetFile.length()} bytes")
                    } catch (e: Exception) {
                        Log.w(TAG, "Asset not found: $assetPath (${e.message})")
                        Log.w(TAG, "Please run download_model.sh and copy files to assets/kws/")
                        return
                    }
                }
            }

            // 2. 创建 KeywordSpotter（通过 sherpa-onnx Java API）
            val encoderPath = File(modelDir, "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx").absolutePath
            val decoderPath = File(modelDir, "decoder-epoch-12-avg-2-chunk-16-left-64.onnx").absolutePath
            val joinerPath = File(modelDir, "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx").absolutePath
            val tokensPath = File(modelDir, "tokens.txt").absolutePath
            val keywordsPath = File(modelDir, "keywords.txt").absolutePath

            // 验证文件存在
            for (path in listOf(encoderPath, decoderPath, joinerPath, tokensPath, keywordsPath)) {
                if (!File(path).exists()) {
                    Log.e(TAG, "Model file missing: $path")
                    return
                }
            }

            // 使用 sherpa-onnx Java API
            val kwsClass = Class.forName("com.k2fsa.sherpa.onnx.KeywordSpotter")
            val configClass = Class.forName("com.k2fsa.sherpa.onnx.KeywordSpotterConfig")

            val config = configClass.getConstructor(
                String::class.java, // tokens
                String::class.java, // encoder
                String::class.java, // decoder
                String::class.java, // joiner
                String::class.java, // keywordsFile
                Int::class.javaPrimitiveType, // numThreads
                Float::class.javaPrimitiveType, // sampleRate
                Int::class.javaPrimitiveType, // featureDim
                Int::class.javaPrimitiveType, // maxActivePaths
                Float::class.javaPrimitiveType, // keywordsScore
                Float::class.javaPrimitiveType, // keywordsThreshold
                Int::class.javaPrimitiveType, // numTrailingBlanks
                String::class.java, // provider
                Int::class.javaPrimitiveType, // device
            ).newInstance(
                tokensPath, encoderPath, decoderPath, joinerPath, keywordsPath,
                2, 16000.0f, 80, 4, 1.0f, 0.25f, 1, "cpu", 0
            )

            keywordSpotter = kwsClass.getConstructor(configClass).newInstance(config)
            kwsReady = true
            Log.i(TAG, "KeywordSpotter initialized successfully")

        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "sherpa-onnx not found. Add dependency: com.k2fsa.sherpa:onnx:1.10.32")
            Log.e(TAG, "See README for setup instructions")
        } catch (e: Exception) {
            Log.e(TAG, "Init failed: ${e.message}", e)
        }
    }

    /**
     * 开始监听麦克风
     */
    fun start() {
        if (!kwsReady) {
            Log.w(TAG, "KWS not ready, cannot start")
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO permission not granted")
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
        Log.i(TAG, "Listening for wake words: 哈德 / 你好小哈 / 小哈 / 哈德哈德")

        listenThread = Thread {
            listenLoop()
        }.apply {
            name = "WakeWordThread"
            isDaemon = true
            start()
        }
    }

    /**
     * 停止监听
     */
    fun stop() {
        running.set(false)
        listenThread?.interrupt()
        listenThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        Log.i(TAG, "Stopped listening")
    }

    /**
     * 释放所有资源
     */
    fun release() {
        stop()
        keywordSpotter = null
        kwsReady = false
    }

    private fun listenLoop() {
        val chunkSamples = 1600 // 100ms at 16kHz
        val buffer = ShortArray(chunkSamples)

        try {
            // 创建 stream
            val kwsClass = Class.forName("com.k2fsa.sherpa.onnx.KeywordSpotter")
            val createStreamMethod = kwsClass.getMethod("createStream")
            val isReadyMethod = kwsClass.getMethod("isReady", createStreamMethod.returnType)
            val decodeMethod = kwsClass.getMethod("decodeStream", createStreamMethod.returnType)
            val getResultMethod = kwsClass.getMethod("getResult", createStreamMethod.returnType)
            val resetMethod = kwsClass.getMethod("resetStream", createStreamMethod.returnType)
            val acceptWaveformMethod = createStreamMethod.returnType.getMethod(
                "acceptWaveform",
                FloatArray::class.java,
                Float::class.javaPrimitiveType
            )

            stream = createStreamMethod.invoke(keywordSpotter)

            while (running.get()) {
                val readCount = audioRecord?.read(buffer, 0, chunkSamples) ?: 0
                if (readCount <= 0) continue

                // Short[] -> Float[] (归一化到 -1.0 ~ 1.0)
                val floatBuffer = FloatArray(readCount)
                for (i in 0 until readCount) {
                    floatBuffer[i] = buffer[i].toFloat() / 32768.0f
                }

                acceptWaveformMethod.invoke(stream, floatBuffer, SAMPLE_RATE.toFloat())

                while (isReadyMethod.invoke(keywordSpotter, stream) as Boolean) {
                    decodeMethod.invoke(keywordSpotter, stream)
                    val result = getResultMethod.invoke(keywordSpotter, stream) as String
                    if (result.isNotBlank()) {
                        resetMethod.invoke(keywordSpotter, stream)
                        val keyword = result.trim()
                        Log.i(TAG, "[WAKE] $keyword")
                        onWake(keyword)
                    }
                }
            }
        } catch (e: InterruptedException) {
            Log.i(TAG, "Listen thread interrupted")
        } catch (e: Exception) {
            Log.e(TAG, "Listen loop error: ${e.message}", e)
        }
    }

    /**
     * 检查模型是否就绪
     */
    fun isReady(): Boolean = kwsReady
}
