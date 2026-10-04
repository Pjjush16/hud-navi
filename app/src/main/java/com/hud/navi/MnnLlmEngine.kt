/*
 * MnnLlmEngine.kt - MNN-LLM 本地推理引擎 (v11.1)
 *
 * 通过 JNI 调用 MNN-LLM 原生库，在设备本地运行 Qwen2.5-0.5B 模型。
 *
 * 架构说明：
 *   MNN-LLM 是阿里巴巴开源的端侧 LLM 推理引擎，
 *   需要编译原生 .so 库（通过 NDK + CMake 构建）。
 *   本类封装了 JNI 接口，提供 Kotlin 友好的调用 API。
 *
 * 构建依赖：
 *   .so 文件由 GitHub Actions CI 从 MNN 源码编译，
 *   输出到 app/src/main/jniLibs/arm64-v8a/ 目录。
 *   编译参数见 .github/workflows/build-mnn.yml
 */

package com.hud.navi

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MnnLlmEngine(private val context: Context) {

    companion object {
        private const val TAG = "MnnLlmEngine"
        private const val LIB_NAME = "mnn_llm_jni"

        // MNN-LLM 后端类型
        const val BACKEND_CPU = 0
        const val BACKEND_GPU = 1  // OpenCL

        // 默认推理参数
        const val DEFAULT_MAX_TOKENS = 256
        const val DEFAULT_TEMPERATURE = 0.1f  // 低温度 = 更确定性的输出（适合 JSON）
        const val DEFAULT_TOP_P = 0.9f
        const val DEFAULT_THREADS = 4
    }

    private var nativePtr: Long = 0  // C++ 侧 Session 指针
    private var isInitialized = false
    private var isGenerating = false
    private var modelDir: String = ""

    /**
     * 引擎是否可用（.so 已加载）
     */
    var isAvailable = false
        private set

    init {
        try {
            System.loadLibrary(LIB_NAME)
            isAvailable = true
            Log.i(TAG, "MNN-LLM native library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "MNN-LLM native library not found: ${e.message}")
            isAvailable = false
        }
    }

    /**
     * 初始化引擎并加载模型
     *
     * @param modelPath 模型目录路径（包含 config.json 和 weight.mnn）
     * @param backend 后端类型（CPU / GPU）
     * @param threads CPU 线程数
     * @return 初始化是否成功
     */
    suspend fun init(
        modelPath: String,
        backend: Int = BACKEND_CPU,
        threads: Int = DEFAULT_THREADS
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable) {
            Log.w(TAG, "Native library not available")
            return@withContext false
        }

        if (isInitialized) {
            Log.i(TAG, "Already initialized")
            return@withContext true
        }

        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            Log.e(TAG, "Model path not found: $modelPath")
            return@withContext false
        }

        try {
            modelDir = modelPath
            nativePtr = nativeInit(modelPath, backend, threads)
            isInitialized = nativePtr != 0L

            if (isInitialized) {
                Log.i(TAG, "Engine initialized: model=$modelPath, backend=$backend, threads=$threads")
            } else {
                Log.e(TAG, "Engine init failed (native returned 0)")
            }

            return@withContext isInitialized
        } catch (e: Exception) {
            Log.e(TAG, "Init error: ${e.message}", e)
            return@withContext false
        }
    }

    /**
     * 生成回复（同步调用，建议在 IO 线程使用）
     *
     * @param prompt 完整提示词（含系统提示词）
     * @param maxTokens 最大生成 token 数
     * @param temperature 温度参数
     * @return 生成的文本，失败返回 null
     */
    suspend fun generate(
        prompt: String,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        temperature: Float = DEFAULT_TEMPERATURE
    ): String? = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            Log.w(TAG, "Engine not initialized")
            return@withContext null
        }

        if (isGenerating) {
            Log.w(TAG, "Already generating, skipping")
            return@withContext null
        }

        isGenerating = true
        try {
            val startTime = System.currentTimeMillis()
            val result = nativeGenerate(nativePtr, prompt, maxTokens, temperature)
            val elapsed = System.currentTimeMillis() - startTime

            Log.i(TAG, "Generated ${result?.length ?: 0} chars in ${elapsed}ms")
            return@withContext result
        } catch (e: Exception) {
            Log.e(TAG, "Generate error: ${e.message}", e)
            return@withContext null
        } finally {
            isGenerating = false
        }
    }

    /**
     * 流式生成回复（逐 token 回调）
     *
     * @param prompt 完整提示词
     * @param maxTokens 最大 token 数
     * @param temperature 温度
     * @param onToken 每个 token 的回调
     * @return 完整生成文本
     */
    suspend fun generateStream(
        prompt: String,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        temperature: Float = DEFAULT_TEMPERATURE,
        onToken: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        if (!isInitialized) return@withContext null
        if (isGenerating) return@withContext null

        isGenerating = true
        val result = StringBuilder()

        try {
            nativeGenerateStream(nativePtr, prompt, maxTokens, temperature) { token ->
                result.append(token)
                onToken?.invoke(token)
            }
            return@withContext result.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Stream generate error: ${e.message}", e)
            return@withContext if (result.isNotEmpty()) result.toString() else null
        } finally {
            isGenerating = false
        }
    }

    /**
     * 停止当前生成
     */
    fun stopGeneration() {
        if (isGenerating && isInitialized) {
            nativeStopGeneration(nativePtr)
            isGenerating = false
        }
    }

    /**
     * 释放引擎资源
     */
    fun release() {
        if (isInitialized) {
            nativeRelease(nativePtr)
            nativePtr = 0L
            isInitialized = false
            Log.i(TAG, "Engine released")
        }
    }

    // ==================== JNI 原生方法 ====================
    // 这些方法由 mnn_llm_jni.cpp 实现

    private external fun nativeInit(modelPath: String, backend: Int, threads: Int): Long
    private external fun nativeGenerate(ptr: Long, prompt: String, maxTokens: Int, temperature: Float): String?
    private external fun nativeGenerateStream(ptr: Long, prompt: String, maxTokens: Int, temperature: Float, callback: StreamCallback): String?
    private external fun nativeStopGeneration(ptr: Long)
    private external fun nativeRelease(ptr: Long)

    /**
     * JNI 回调接口 — 流式生成时逐 token 回调
     */
    interface StreamCallback {
        fun onToken(token: String)
    }
}
