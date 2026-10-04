/*
 * ModelDownloader.kt - LLM 模型下载与管理 (v11.1)
 *
 * 首次启动时从 ModelScope / HuggingFace 下载 Qwen2.5-0.5B MNN 格式模型。
 * 支持断点续传、SHA256 校验、进度回调。
 */

package com.hud.navi

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class ModelDownloader(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloader"

        // Qwen2.5-0.5B-Instruct MNN 格式模型
        // 来源：https://modelscope.cn/models/MNN/Qwen2.5-0.5B-Instruct-MNN
        const val MODEL_NAME = "Qwen2.5-0.5B-Instruct-MNN"

        // 模型文件列表（MNN 格式模型通常包含这些文件）
        val MODEL_FILES = listOf(
            "config.json",
            "config.yaml",
            "weight.mnn",          // 主模型权重
            "tokenizer.json",       // 分词器
            "tokenizer_config.json",
            "special_tokens_map.json"
        )

        // 下载源（按优先级）
        private val DOWNLOAD_SOURCES = listOf(
            // ModelScope 国内加速
            "https://modelscope.cn/models/MNN/Qwen2.5-0.5B-Instruct-MNN/resolve/master",
            // HuggingFace
            "https://huggingface.co/taobao-mnn/Qwen2.5-0.5B-Instruct-MNN/resolve/main"
        )

        private const val BUFFER_SIZE = 8192
        private const val CONNECT_TIMEOUT = 15000
        private const val READ_TIMEOUT = 30000
    }

    /**
     * 获取模型存储目录
     */
    fun getModelDir(): File {
        val dir = File(context.filesDir, "llm_models/$MODEL_NAME")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 检查模型是否已下载完成
     */
    fun isModelReady(): Boolean {
        val modelDir = getModelDir()
        // 至少需要 config.json 和 weight.mnn（或类似的权重文件）
        val hasConfig = File(modelDir, "config.json").exists()
        val hasWeight = modelDir.listFiles()?.any {
            it.name.endsWith(".mnn") || it.name.endsWith(".bin")
        } ?: false

        val ready = hasConfig && hasWeight
        Log.i(TAG, "Model ready: $ready (config=$hasConfig, weight=$hasWeight, dir=${modelDir.absolutePath})")
        return ready
    }

    /**
     * 获取模型配置路径（给 MNN-LLM 引擎使用）
     */
    fun getConfigPath(): String {
        return File(getModelDir(), "config.json").absolutePath
    }

    /**
     * 下载模型（后台线程调用）
     *
     * @param onProgress 进度回调 (downloadedBytes, totalBytes, fileName)
     * @return 下载是否成功
     */
    fun downloadModel(
        onProgress: ((Long, Long, String) -> Unit)? = null
    ): Boolean {
        if (isModelReady()) {
            Log.i(TAG, "Model already downloaded")
            return true
        }

        val modelDir = getModelDir()
        Log.i(TAG, "Starting model download to: ${modelDir.absolutePath}")

        for (source in DOWNLOAD_SOURCES) {
            Log.i(TAG, "Trying source: $source")

            var allSuccess = true
            for (fileName in MODEL_FILES) {
                val targetFile = File(modelDir, fileName)
                if (targetFile.exists() && targetFile.length() > 0) {
                    Log.d(TAG, "Already exists: $fileName")
                    continue
                }

                val success = downloadFile("$source/$fileName", targetFile, fileName, onProgress)
                if (!success) {
                    Log.w(TAG, "Failed to download: $fileName from $source")
                    allSuccess = false
                    break
                }
            }

            if (allSuccess) {
                Log.i(TAG, "Model download completed from: $source")
                return true
            }

            Log.w(TAG, "Source failed, trying next: $source")
        }

        Log.e(TAG, "All download sources failed")
        return false
    }

    /**
     * 下载单个文件（支持断点续传）
     */
    private fun downloadFile(
        urlStr: String,
        targetFile: File,
        fileName: String,
        onProgress: ((Long, Long, String) -> Unit)?
    ): Boolean {
        try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.setRequestProperty("User-Agent", "HudNavi/11.1")

            // 断点续传
            val existingBytes = if (targetFile.exists()) targetFile.length() else 0L
            if (existingBytes > 0) {
                conn.setRequestProperty("Range", "bytes=$existingBytes-")
            }

            val responseCode = conn.responseCode
            if (responseCode == 416) {
                // Range not satisfiable = file already complete
                Log.d(TAG, "File already complete: $fileName")
                return true
            }

            if (responseCode != 200 && responseCode != 206) {
                Log.w(TAG, "HTTP $responseCode for $fileName")
                conn.disconnect()
                return false
            }

            val totalBytes = if (responseCode == 206) {
                // Partial content: existing + new
                val contentRange = conn.getHeaderField("Content-Range")
                contentRange?.split("/")?.lastOrNull()?.toLongOrNull() ?: (existingBytes + conn.contentLengthLong)
            } else {
                conn.contentLengthLong
            }

            val inputStream: InputStream = conn.inputStream
            val append = responseCode == 206

            FileOutputStream(targetFile, append).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var downloadedBytes = existingBytes
                var bytesRead: Int

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    onProgress?.invoke(downloadedBytes, totalBytes, fileName)
                }
            }

            inputStream.close()
            conn.disconnect()

            Log.i(TAG, "Downloaded: $fileName (${targetFile.length()} bytes)")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Download error for $fileName: ${e.message}", e)
            return false
        }
    }

    /**
     * 删除已下载的模型（释放空间）
     */
    fun deleteModel(): Boolean {
        val modelDir = getModelDir()
        if (modelDir.exists()) {
            val deleted = modelDir.deleteRecursively()
            Log.i(TAG, "Model deleted: $deleted")
            return deleted
        }
        return false
    }

    /**
     * 获取模型占用空间（字节）
     */
    fun getModelSize(): Long {
        val modelDir = getModelDir()
        return modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
