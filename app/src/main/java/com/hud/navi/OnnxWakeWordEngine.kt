/*
 * OnnxWakeWordEngine.kt - OpenWakeWord ONNX 推理引擎 (v13.1)
 *
 * 使用 ONNX Runtime 运行自训练的"哈德哈德"唤醒词模型。
 * 特征管线：Audio → Mel Spectrogram → Embedding → Classifier
 *
 * 三个 ONNX 模型：
 *   1. melspectrogram.onnx: 音频 → mel 频谱 [1, n_frames, 32]
 *   2. embedding_model.onnx: 76帧 mel → 96维 embedding
 *   3. hadehade.onnx: 16个 embedding → 唤醒概率
 */

package com.hud.navi

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import ai.onnxruntime.*
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

class OnnxWakeWordEngine(
    private val context: Context,
    private val onWake: () -> Unit
) {
    companion object {
        private const val TAG = "OnnxWakeWord"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = 1280  // 80ms @ 16kHz (openwakeword 标准)
        private const val MEL_FRAMES_FOR_EMBEDDING = 76
        private const val N_EMBEDDINGS_FOR_CLASSIFIER = 16
        private const val EMBEDDING_DIM = 96
        private const val WAKE_THRESHOLD = 0.2f  // v4: lowered from 0.5 to handle diverse voice patterns
        private const val COOLDOWN_MS = 1500L

        private const val ASSET_DIR = "wakeword"
        private const val MELSPECTROGRAM_MODEL = "melspectrogram.onnx"
        private const val EMBEDDING_MODEL = "embedding_model.onnx"
        private const val CLASSIFIER_MODEL = "hadehade.onnx"
    }

    private var env: OrtEnvironment? = null
    private var melSession: OrtSession? = null
    private var embedSession: OrtSession? = null
    private var classifierSession: OrtSession? = null

    private var audioRecord: AudioRecord? = null
    private var listenThread: Thread? = null
    private val running = AtomicBoolean(false)

    var isReady = false
        private set
    var initError: String? = null
        private set

    private var lastWakeTime = 0L

    /**
     * 初始化 ONNX 模型
     */
    fun init() {
        initError = null
        try {
            // Safety check: verify native library is loadable before touching OrtEnvironment
            try {
                System.loadLibrary("onnxruntime4j_jni")
                Log.i(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                initError = "Native lib missing: ${e.message}"
                Log.e(TAG, initError, e)
                isReady = false
                return
            }

            env = OrtEnvironment.getEnvironment()

            // Use CPU-only options first (most stable), never try NNAPI on first attempt
            // NNAPI can cause native crashes on some devices that Java try-catch cannot catch
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(2)  // Limit threads to reduce memory pressure
            opts.setInterOpNumThreads(1)

            // Load models from assets (one at a time, GC between loads)
            melSession = loadModelFromAssets(MELSPECTROGRAM_MODEL, opts)
            System.gc()
            embedSession = loadModelFromAssets(EMBEDDING_MODEL, opts)
            System.gc()
            classifierSession = loadModelFromAssets(CLASSIFIER_MODEL, opts)
            opts.close()  // Close session options after all models loaded

            isReady = true
            Log.i(TAG, "All 3 ONNX models loaded successfully (CPU mode)")
        } catch (e: Exception) {
            initError = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "Init failed: $initError", e)
            isReady = false
            // Clean up any partially loaded sessions
            try { melSession?.close() } catch (_: Exception) {}
            try { embedSession?.close() } catch (_: Exception) {}
            try { classifierSession?.close() } catch (_: Exception) {}
            try { env?.close() } catch (_: Exception) {}
            melSession = null
            embedSession = null
            classifierSession = null
            env = null
        }
    }

    private fun loadModelFromAssets(fileName: String, opts: OrtSession.SessionOptions): OrtSession {
        val bytes = context.assets.open("$ASSET_DIR/$fileName").use { it.readBytes() }
        Log.i(TAG, "Loaded $fileName (${bytes.size / 1024} KB)")
        return try {
            env!!.createSession(bytes, opts)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create session for $fileName: ${e.message}")
            // Retry with default options (no NNAPI)
            val defaultOpts = OrtSession.SessionOptions()
            env!!.createSession(bytes, defaultOpts)
        }
    }

    /**
     * 开始监听
     */
    fun start() {
        if (!isReady) return
        if (running.get()) return
        running.set(true)

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(CHUNK_SAMPLES * 4)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "VOICE_RECOGNITION failed, trying MIC")
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            running.set(false)
            return
        }

        audioRecord?.startRecording()
        Log.i(TAG, "Listening started")

        listenThread = Thread { listenLoop() }.apply {
            name = "OnnxWakeWordThread"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        val thread = listenThread
        listenThread = null
        // 先等线程自然退出（running=false 后循环最多再跑一个 80ms read）
        try { thread?.join(300) } catch (_: Exception) {}
        // 线程退出后再释放 AudioRecord，避免和读取循环竞争
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        Log.d(TAG, "Stopped (thread joined, AudioRecord released)")
    }

    fun release() {
        stop()
        melSession?.close()
        embedSession?.close()
        classifierSession?.close()
        env?.close()
        isReady = false
    }

    // ==================== 核心监听循环 ====================

    private fun listenLoop() {
        val buffer = ShortArray(CHUNK_SAMPLES)

        // Rolling buffers for the feature pipeline
        val melBuffer = mutableListOf<FloatArray>()  // List of [?, 32] mel frames
        val embeddingBuffer = mutableListOf<FloatArray>()  // List of [96] embeddings

        try {
            while (running.get()) {
                val readCount = audioRecord?.read(buffer, 0, CHUNK_SAMPLES) ?: 0
                if (readCount <= 0) continue

                // Convert to float
                val floatBuf = FloatArray(readCount)
                for (i in 0 until readCount) {
                    floatBuf[i] = buffer[i].toFloat() / 32768.0f
                }

                // Step 1: Compute mel spectrogram for this chunk
                val melFrames = computeMelSpectrogram(floatBuf) ?: continue
                melBuffer.add(melFrames)

                // Keep mel buffer bounded (~5 seconds worth)
                while (melBuffer.size > 250) {
                    melBuffer.removeAt(0)
                }

                // Step 2: When we have enough mel frames, compute embedding
                val totalMelFrames = melBuffer.sumOf { it.size / 32 }
                if (totalMelFrames >= MEL_FRAMES_FOR_EMBEDDING) {
                    // Flatten recent mel frames into [76, 32] window
                    val flatMel = flattenMelFrames(melBuffer, MEL_FRAMES_FOR_EMBEDDING)
                    if (flatMel != null) {
                        val embedding = computeEmbedding(flatMel)
                        if (embedding != null) {
                            embeddingBuffer.add(embedding)

                            // Keep embedding buffer bounded
                            while (embeddingBuffer.size > N_EMBEDDINGS_FOR_CLASSIFIER + 5) {
                                embeddingBuffer.removeAt(0)
                            }
                        }

                        // Remove consumed mel frames (keep some overlap)
                        val consumeCount = minOf(melBuffer.size - MEL_FRAMES_FOR_EMBEDDING / 4, melBuffer.size / 2)
                        repeat(consumeCount.coerceAtLeast(0)) {
                            if (melBuffer.isNotEmpty()) melBuffer.removeAt(0)
                        }
                    }
                }

                // Step 3: When we have 16 embeddings, run classifier
                if (embeddingBuffer.size >= N_EMBEDDINGS_FOR_CLASSIFIER) {
                    val features = embeddingBuffer.takeLast(N_EMBEDDINGS_FOR_CLASSIFIER)
                    val score = classify(features)

                    if (score >= WAKE_THRESHOLD) {
                        val now = System.currentTimeMillis()
                        if (now - lastWakeTime >= COOLDOWN_MS) {
                            lastWakeTime = now
                            Log.i(TAG, "[WAKE] score=$score")
                            onWake()
                        }
                    }
                }
            }
        } catch (e: InterruptedException) {
            Log.i(TAG, "Listen loop interrupted")
        } catch (e: Exception) {
            Log.e(TAG, "Listen error: ${e.message}", e)
        }
    }

    // ==================== ONNX 推理方法 ====================

    private fun computeMelSpectrogram(audio: FloatArray): FloatArray? {
        try {
            // Input: [1, n_samples] — use FloatBuffer + shape
            val buf = FloatBuffer.wrap(audio)
            val inputTensor = OnnxTensor.createTensor(env!!, buf, longArrayOf(1, audio.size.toLong()))
            val result = melSession!!.run(mapOf("input" to inputTensor))
            val output = result.get("output").get().value as Array<Array<Array<FloatArray>>>
            // Output shape: [1, 1, n_mel_frames, 32]
            val melFrames = output[0][0]  // [n_mel_frames, 32]
            // Flatten to 1D for storage
            val flat = FloatArray(melFrames.size * 32)
            for (i in melFrames.indices) {
                System.arraycopy(melFrames[i], 0, flat, i * 32, 32)
            }
            inputTensor.close()
            result.close()
            return flat
        } catch (e: Exception) {
            Log.w(TAG, "Mel spectrogram failed: ${e.message}")
            return null
        }
    }

    private fun flattenMelFrames(melBuffer: List<FloatArray>, targetFrames: Int): FloatArray? {
        // Concatenate all mel frames and take the last targetFrames*32 values
        val totalElements = melBuffer.sumOf { it.size }
        if (totalElements < targetFrames * 32) return null

        val allFlat = FloatArray(totalElements)
        var offset = 0
        for (chunk in melBuffer) {
            System.arraycopy(chunk, 0, allFlat, offset, chunk.size)
            offset += chunk.size
        }

        // Take last targetFrames * 32 elements
        val start = totalElements - targetFrames * 32
        val result = FloatArray(targetFrames * 32)
        System.arraycopy(allFlat, start, result, 0, result.size)
        return result
    }

    private fun computeEmbedding(melFlat: FloatArray): FloatArray? {
        try {
            // Reshape to [1, 76, 32, 1] using FloatBuffer
            val buf = FloatBuffer.wrap(melFlat)
            val inputTensor = OnnxTensor.createTensor(env!!, buf, longArrayOf(1, 76, 32, 1))
            val result = embedSession!!.run(mapOf("input_1" to inputTensor))
            // Output: [1, 1, 1, 96]
            val output = result.get(0).value as Array<Array<Array<FloatArray>>>
            val embedding = output[0][0][0]  // [96]
            inputTensor.close()
            result.close()
            return embedding.copyOf()
        } catch (e: Exception) {
            Log.w(TAG, "Embedding failed: ${e.message}")
            return null
        }
    }

    private fun classify(embeddings: List<FloatArray>): Float {
        try {
            // v5 模型: input="float_input" shape=[None, 1536]
            // 生产模型: input="input" shape=[1, 16, 96]
            val flat = FloatArray(16 * 96)
            for (i in 0 until 16) {
                System.arraycopy(embeddings[i], 0, flat, i * 96, 96)
            }

            // 检测模型输入名（兼容两种格式）
            val inputName = classifierSession!!.inputNames.first()
            val inputTensor: OnnxTensor
            val shape: LongArray

            if (inputName == "float_input") {
                // v5: [1, 1536]
                shape = longArrayOf(1, 1536)
            } else {
                // production: [1, 16, 96]
                shape = longArrayOf(1, 16, 96)
            }

            val buf = FloatBuffer.wrap(flat)
            inputTensor = OnnxTensor.createTensor(env!!, buf, shape)
            val result = classifierSession!!.run(mapOf(inputName to inputTensor))

            val score: Float = try {
                // 尝试 v5 格式: output_label (int64) → 0 or 1
                val label = result.get("output_label")
                if (label.isPresent) {
                    val labelValue = label.get().value
                    when (labelValue) {
                        is LongArray -> labelValue[0].toFloat()
                        is Array<*> -> (labelValue[0] as Long).toFloat()
                        else -> 0f
                    }
                } else {
                    // production 格式: output [1, 1] float
                    val output = result.get(0).value as Array<FloatArray>
                    output[0][0]
                }
            } catch (e: Exception) {
                // fallback: 尝试直接读取第一个输出
                try {
                    val output = result.get(0).value
                    when (output) {
                        is Array<*> -> {
                            val first = output[0]
                            when (first) {
                                is FloatArray -> first[0]
                                is LongArray -> first[0].toFloat()
                                is Long -> first.toFloat()
                                else -> 0f
                            }
                        }
                        else -> 0f
                    }
                } catch (e2: Exception) {
                    Log.w(TAG, "Score parse failed: ${e2.message}")
                    0f
                }
            }

            inputTensor.close()
            result.close()
            return score
        } catch (e: Exception) {
            Log.w(TAG, "Classify failed: ${e.message}")
            return 0f
        }
    }
}
