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
        private const val WAKE_THRESHOLD = 0.5f
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
            env = OrtEnvironment.getEnvironment()

            val opts = OrtSession.SessionOptions().apply {
                addNnapi()  // Try NNAPI first, fallback to CPU
            }

            // Load models from assets
            melSession = loadModelFromAssets(MELSPECTROGRAM_MODEL, opts)
            embedSession = loadModelFromAssets(EMBEDDING_MODEL, opts)
            classifierSession = loadModelFromAssets(CLASSIFIER_MODEL, opts)

            isReady = true
            Log.i(TAG, "All 3 ONNX models loaded successfully")
        } catch (e: Exception) {
            initError = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "Init failed: $initError", e)
            isReady = false
        }
    }

    private fun loadModelFromAssets(fileName: String, opts: OrtSession.SessionOptions): OrtSession {
        val bytes = context.assets.open("$ASSET_DIR/$fileName").use { it.readBytes() }
        Log.i(TAG, "Loaded $fileName (${bytes.size} bytes)")
        return env!!.createSession(bytes, opts)
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
        listenThread?.interrupt()
        listenThread = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
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
            // Input: [1, n_samples]
            val inputTensor = OnnxTensor.createTensor(env!!, audio, longArrayOf(1, audio.size.toLong()))
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
            // Reshape to [1, 76, 32, 1]
            val shaped = Array(1) { Array(76) { Array(32) { FloatArray(1) } } }
            for (i in 0 until 76) {
                for (j in 0 until 32) {
                    shaped[0][i][j][0] = melFlat[i * 32 + j]
                }
            }

            val inputTensor = OnnxTensor.createTensor(env!!, shaped)
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
            // Shape: [1, 16, 96]
            val input = Array(1) { Array(16) { FloatArray(96) } }
            for (i in 0 until 16) {
                System.arraycopy(embeddings[i], 0, input[0][i], 0, 96)
            }

            val inputTensor = OnnxTensor.createTensor(env!!, input)
            val result = classifierSession!!.run(mapOf("input" to inputTensor))
            val output = result.get(0).value as Array<FloatArray>
            val score = output[0][0]
            inputTensor.close()
            result.close()
            return score
        } catch (e: Exception) {
            Log.w(TAG, "Classify failed: ${e.message}")
            return 0f
        }
    }
}
