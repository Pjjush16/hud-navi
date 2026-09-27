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
 * 模型: sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile
 *
 * 使用方式：
 *   val wm = WakeWordManager(context) { keyword -> ... }
 *   wm.init()    // 在 onCreate 中调用
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

        // 模型文件在 assets 中的相对路径
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
    private var kwsReady = false

    // sherpa-onnx 对象（延迟绑定，避免编译期硬依赖）
    private var spotter: Any? = null
    private var kwsStream: Any? = null

    // 缓存反射方法
    private var createStreamMethod: java.lang.reflect.Method? = null
    private var isReadyMethod: java.lang.reflect.Method? = null
    private var decodeStreamMethod: java.lang.reflect.Method? = null
    private var getResultMethod: java.lang.reflect.Method? = null
    private var resetStreamMethod: java.lang.reflect.Method? = null
    private var acceptWaveformMethod: java.lang.reflect.Method? = null
    private var streamClass: Class<*>? = null

    fun init() {
        try {
            val modelDir = File(context.filesDir, MODEL_DIR)
            if (!modelDir.exists()) modelDir.mkdirs()

            // 1. 从 assets 释放模型文件到内部存储
            val modelFiles = listOf(ENCODER_FILE, DECODER_FILE, JOINER_FILE, TOKENS_FILE, KEYWORDS_FILE)
            var allReady = true

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
                        Log.w(TAG, "Asset missing: $ASSET_DIR/$fileName — ${e.message}")
                        allReady = false
                    }
                }
            }

            if (!allReady) {
                Log.w(TAG, "Model files incomplete. Run download_kws_model.sh first.")
                return
            }

            // 2. 通过反射创建 KeywordSpotter（避免编译期对 sherpa-onnx 的强依赖）
            val encoderPath = File(modelDir, ENCODER_FILE).absolutePath
            val decoderPath = File(modelDir, DECODER_FILE).absolutePath
            val joinerPath = File(modelDir, JOINER_FILE).absolutePath
            val tokensPath = File(modelDir, TOKENS_FILE).absolutePath
            val keywordsPath = File(modelDir, KEYWORDS_FILE).absolutePath

            spotter = createKeywordSpotter(
                tokensPath, encoderPath, decoderPath, joinerPath, keywordsPath
            )

            if (spotter != null) {
                // 缓存反射方法
                val spotterClass = spotter!!.javaClass
                Log.i(TAG, "Spotter class: ${spotterClass.name}")
                Log.i(TAG, "Spotter methods: ${spotterClass.methods.map { "${it.name}(${it.parameterTypes.joinToString { it.simpleName }})" }.take(20)}")

                createStreamMethod = spotterClass.getMethod("createStream")
                streamClass = createStreamMethod!!.returnType
                Log.i(TAG, "Stream class: ${streamClass!!.name}")

                isReadyMethod = spotterClass.getMethod("isReady", streamClass)
                decodeStreamMethod = spotterClass.getMethod("decodeStream", streamClass)
                getResultMethod = spotterClass.getMethod("getResult", streamClass)
                resetStreamMethod = spotterClass.getMethod("resetStream", streamClass)
                acceptWaveformMethod = streamClass!!.getMethod(
                    "acceptWaveform", FloatArray::class.java, Float::class.javaPrimitiveType
                )

                kwsReady = true
                Log.i(TAG, "KeywordSpotter initialized — all 7 methods cached, ready to listen")
            } else {
                Log.e(TAG, "Spotter is null — KWS will not work")
            }
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "sherpa-onnx not found on classpath. Check dependency: com.k2fsa.sherpa:onnx")
        } catch (e: Exception) {
            Log.e(TAG, "WakeWordManager init failed: ${e.message}", e)
        }
    }

    /**
     * 创建 KeywordSpotter 实例（通过反射，兼容多个版本的 sherpa-onnx API）
     */
    private fun createKeywordSpotter(
        tokens: String, encoder: String, decoder: String,
        joiner: String, keywords: String
    ): Any? {
        return try {
            // 方式1: 尝试 sherpa-onnx v1.10+ Java API
            val configClass = Class.forName("com.k2fsa.sherpa.onnx.KeywordSpotterConfig")
            val config = configClass.newInstance()

            // 尝试通过 setter 或字段设置
            trySetField(config, "tokens", tokens)
            trySetField(config, "encoder", encoder)
            trySetField(config, "decoder", decoder)
            trySetField(config, "joiner", joiner)
            trySetField(config, "keywordsFile", keywords)
            trySetField(config, "numThreads", 2)
            trySetField(config, "sampleRate", 16000.0f)
            trySetField(config, "featureDim", 80)
            trySetField(config, "maxActivePaths", 4)
            trySetField(config, "keywordsScore", 1.0f)
            trySetField(config, "keywordsThreshold", 0.25f)
            trySetField(config, "numTrailingBlanks", 1)
            trySetField(config, "provider", "cpu")
            trySetField(config, "device", 0)

            val spotterClass = Class.forName("com.k2fsa.sherpa.onnx.KeywordSpotter")
            spotterClass.getConstructor(configClass).newInstance(config)
        } catch (e: Exception) {
            Log.w(TAG, "KeywordSpotter creation method 1 failed: ${e.message}")
            try {
                // 方式2: 直接构造函数传参
                val spotterClass = Class.forName("com.k2fsa.sherpa.onnx.KeywordSpotter")
                val ctor = spotterClass.constructors.firstOrNull { it.parameterCount >= 5 }
                if (ctor != null) {
                    val params = Array(ctor.parameterCount) { i ->
                        when (ctor.parameterTypes[i]) {
                            String::class.java -> when (i) {
                                0 -> tokens; 1 -> encoder; 2 -> decoder
                                3 -> joiner; 4 -> keywords; else -> ""
                            }
                            Int::class.javaPrimitiveType -> 2
                            Float::class.javaPrimitiveType -> 16000.0f
                            else -> 0
                        }
                    }
                    ctor.newInstance(*params)
                } else {
                    Log.e(TAG, "No suitable KeywordSpotter constructor found")
                    null
                }
            } catch (e2: Exception) {
                Log.e(TAG, "All creation methods failed: ${e2.message}")
                null
            }
        }
    }

    private fun trySetField(obj: Any, fieldName: String, value: Any) {
        try {
            val field = obj.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(obj, value)
        } catch (e: NoSuchFieldException) {
            // 尝试 setter 方法
            try {
                val setter = obj.javaClass.getMethod(
                    "set${fieldName.replaceFirstChar { it.uppercase() }}",
                    value.javaClass
                )
                setter.invoke(obj, value)
            } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    fun start() {
        if (!kwsReady) {
            Log.w(TAG, "KWS not ready")
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
        listenThread?.interrupt()
        listenThread = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        Log.i(TAG, "Stopped")
    }

    fun release() {
        stop()
        spotter = null
        kwsReady = false
    }

    fun isReady(): Boolean = kwsReady

    private fun listenLoop() {
        val chunkSamples = 1600 // 100ms
        val buffer = ShortArray(chunkSamples)
        var chunkCount = 0L
        var lastLogTime = System.currentTimeMillis()

        try {
            kwsStream = createStreamMethod?.invoke(spotter)
            Log.i(TAG, "Listen loop started, stream created")

            while (running.get()) {
                val readCount = audioRecord?.read(buffer, 0, chunkSamples) ?: 0
                if (readCount <= 0) continue

                chunkCount++
                val floatBuf = FloatArray(readCount)
                for (i in 0 until readCount) {
                    floatBuf[i] = buffer[i].toFloat() / 32768.0f
                }

                acceptWaveformMethod?.invoke(kwsStream, floatBuf, SAMPLE_RATE.toFloat())

                while (isReadyMethod?.invoke(spotter, kwsStream) == true) {
                    decodeStreamMethod?.invoke(spotter, kwsStream)
                    val result = getResultMethod?.invoke(spotter, kwsStream) as? String ?: ""
                    if (result.isNotBlank()) {
                        resetStreamMethod?.invoke(spotter, kwsStream)
                        val keyword = result.trim()
                        Log.i(TAG, "[WAKE] $keyword")
                        onWake(keyword)
                    }
                }

                // 每5秒打印一次心跳日志
                val now = System.currentTimeMillis()
                if (now - lastLogTime > 5000) {
                    Log.i(TAG, "Heartbeat: $chunkCount chunks processed, running=${running.get()}")
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
