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

    private var spotter: Any? = null
    private var kwsStream: Any? = null

    private var createStreamMethod: java.lang.reflect.Method? = null
    private var isReadyMethod: java.lang.reflect.Method? = null
    private var decodeStreamMethod: java.lang.reflect.Method? = null
    private var getResultMethod: java.lang.reflect.Method? = null
    private var resetStreamMethod: java.lang.reflect.Method? = null
    private var acceptWaveformMethod: java.lang.reflect.Method? = null
    private var streamClass: Class<*>? = null

    fun init() {
        initError = null
        try {
            val modelDir = File(context.filesDir, MODEL_DIR)
            if (!modelDir.exists()) modelDir.mkdirs()

            // 1. 从 assets 释放模型文件
            val modelFiles = listOf(ENCODER_FILE, DECODER_FILE, JOINER_FILE, TOKENS_FILE, KEYWORDS_FILE)
            var allReady = true
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
                        allReady = false
                        missingFiles.add(fileName)
                    }
                }
            }

            if (!allReady) {
                initError = "模型文件缺失: ${missingFiles.joinToString(", ")}"
                Log.e(TAG, initError!!)
                // 显示详细 Toast 便于调试
                Toast.makeText(context, "语音唤醒失败: $initError", Toast.LENGTH_LONG).show()
                return
            }

            Log.i(TAG, "All 5 model files present in $modelDir")

            // 2. 创建 KeywordSpotter
            val encoderPath = File(modelDir, ENCODER_FILE).absolutePath
            val decoderPath = File(modelDir, DECODER_FILE).absolutePath
            val joinerPath = File(modelDir, JOINER_FILE).absolutePath
            val tokensPath = File(modelDir, TOKENS_FILE).absolutePath
            val keywordsPath = File(modelDir, KEYWORDS_FILE).absolutePath

            Log.i(TAG, "Creating KeywordSpotter...")
            Log.i(TAG, "  encoder: $encoderPath")
            Log.i(TAG, "  decoder: $decoderPath")
            Log.i(TAG, "  joiner:  $joinerPath")
            Log.i(TAG, "  tokens:  $tokensPath")
            Log.i(TAG, "  keywords: $keywordsPath")

            spotter = createKeywordSpotter(tokensPath, encoderPath, decoderPath, joinerPath, keywordsPath)

            if (spotter != null) {
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
                Log.i(TAG, "KeywordSpotter initialized successfully — all methods cached")
            } else {
                initError = "KeywordSpotter 创建失败（sherpa-onnx 可能未正确加载）"
                Log.e(TAG, initError!!)
                Toast.makeText(context, "语音唤醒失败: $initError", Toast.LENGTH_LONG).show()
            }
        } catch (e: ClassNotFoundException) {
            initError = "sherpa-onnx 库未找到: ${e.message}"
            Log.e(TAG, initError!!, e)
            Toast.makeText(context, "语音唤醒失败: $initError", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            initError = "初始化异常: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, initError!!, e)
            Toast.makeText(context, "语音唤醒失败: $initError", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 创建 KeywordSpotter（多重策略，兼容 sherpa-onnx 多个版本）
     */
    private fun createKeywordSpotter(
        tokens: String, encoder: String, decoder: String,
        joiner: String, keywords: String
    ): Any? {
        // === 策略 1：Config 对象 + 构造函数 ===
        try {
            val configClass = Class.forName("com.k2fsa.sherpa.onnx.KeywordSpotterConfig")
            val config = configClass.getDeclaredConstructor().newInstance()
            Log.i(TAG, "Config class: ${configClass.name}")

            // 列出所有字段用于调试
            configClass.declaredFields.forEach { f ->
                Log.d(TAG, "  Config field: ${f.name} (${f.type.simpleName})")
            }

            // 逐个设置字段，正确处理原始类型
            setFieldSmart(config, "tokens", tokens)
            setFieldSmart(config, "encoder", encoder)
            setFieldSmart(config, "decoder", decoder)
            setFieldSmart(config, "joiner", joiner)
            setFieldSmart(config, "keywordsFile", keywords)
            setFieldSmart(config, "numThreads", 2)
            setFieldSmart(config, "sampleRate", 16000)
            setFieldSmart(config, "featureDim", 80)
            setFieldSmart(config, "maxActivePaths", 4)
            setFieldSmart(config, "keywordsScore", 1.0f)
            setFieldSmart(config, "keywordsThreshold", 0.25f)
            setFieldSmart(config, "numTrailingBlanks", 1)
            setFieldSmart(config, "provider", "cpu")
            setFieldSmart(config, "device", 0)

            val spotterClass = Class.forName("com.k2fsa.sherpa.onnx.KeywordSpotter")
            val result = spotterClass.getConstructor(configClass).newInstance(config)
            Log.i(TAG, "Strategy 1 (Config) SUCCESS")
            return result
        } catch (e: Exception) {
            Log.w(TAG, "Strategy 1 (Config) failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        // === 策略 2：直接构造函数（按参数类型智能映射）===
        try {
            val spotterClass = Class.forName("com.k2fsa.sherpa.onnx.KeywordSpotter")
            Log.i(TAG, "Available constructors:")
            spotterClass.constructors.forEach { ctor ->
                Log.i(TAG, "  ${ctor.parameterTypes.map { it.simpleName }}")
            }

            for (ctor in spotterClass.constructors) {
                val paramTypes = ctor.parameterTypes
                if (paramTypes.size < 5) continue

                val params = Array<Any?>(paramTypes.size) { i ->
                    val pt = paramTypes[i]
                    when {
                        pt == String::class.java -> when (i) {
                            0 -> tokens; 1 -> encoder; 2 -> decoder
                            3 -> joiner; 4 -> keywords; else -> ""
                        }
                        pt == Int::class.javaPrimitiveType || pt == Integer::class.java -> 2
                        pt == Float::class.javaPrimitiveType || pt == java.lang.Float::class.java -> 16000.0f
                        pt == Boolean::class.javaPrimitiveType -> false
                        else -> null
                    }
                }

                // 检查是否有 null 参数（无法映射的类型）
                if (params.any { it == null && paramTypes[params.indexOf(it)].isPrimitive }) continue

                try {
                    val result = ctor.newInstance(*params)
                    Log.i(TAG, "Strategy 2 (direct ctor) SUCCESS with ${paramTypes.size} params")
                    return result
                } catch (e: Exception) {
                    Log.w(TAG, "Strategy 2 ctor(${paramTypes.size}) failed: ${e.message}")
                }
            }

            Log.e(TAG, "All constructors tried, none succeeded")
        } catch (e: Exception) {
            Log.e(TAG, "Strategy 2 failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        return null
    }

    /**
     * 智能设置字段：自动处理原始类型和包装类型之间的转换
     */
    private fun setFieldSmart(obj: Any, fieldName: String, value: Any) {
        try {
            val field = obj.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            val fieldType = field.type

            // 原始类型转换
            val convertedValue: Any = when {
                fieldType == Int::class.javaPrimitiveType && value is Number -> value.toInt()
                fieldType == Float::class.javaPrimitiveType && value is Number -> value.toFloat()
                fieldType == Double::class.javaPrimitiveType && value is Number -> value.toDouble()
                fieldType == Long::class.javaPrimitiveType && value is Number -> value.toLong()
                fieldType == Boolean::class.javaPrimitiveType && value is Boolean -> value
                else -> value
            }

            field.set(obj, convertedValue)
            Log.d(TAG, "  Set $fieldName = $value (${fieldType.simpleName}) OK")
        } catch (e: NoSuchFieldException) {
            // 尝试 setter
            try {
                val setterName = "set${fieldName.replaceFirstChar { it.uppercase() }}"
                val methods = obj.javaClass.methods.filter { it.name == setterName }
                if (methods.isNotEmpty()) {
                    val setter = methods.first()
                    val paramType = setter.parameterTypes.first()
                    val convertedValue: Any = when {
                        paramType == Int::class.javaPrimitiveType && value is Number -> value.toInt()
                        paramType == Float::class.javaPrimitiveType && value is Number -> value.toFloat()
                        else -> value
                    }
                    setter.invoke(obj, convertedValue)
                    Log.d(TAG, "  Set $fieldName via setter OK")
                } else {
                    Log.w(TAG, "  $fieldName: no field or setter found")
                }
            } catch (e2: Exception) {
                Log.w(TAG, "  $fieldName: setter failed: ${e2.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "  $fieldName: set failed: ${e.javaClass.simpleName}: ${e.message}")
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

                val now = System.currentTimeMillis()
                if (now - lastLogTime > 5000) {
                    Log.i(TAG, "Heartbeat: $chunkCount chunks, running=${running.get()}")
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
