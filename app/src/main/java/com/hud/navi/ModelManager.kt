/*
 * ModelManager.kt - LLM 模型管理 (v11.2)
 *
 * 模型文件打包在 APK 的 assets/llm/ 目录中。
 * 首次启动时从 assets 解压到内部存储（MNN-LLM 需要文件路径加载）。
 * 后续启动直接加载，无需重复解压。
 *
 * 模型：Qwen2.5-0.5B-Instruct MNN 格式（Q4 量化，~350MB）
 * CI 构建时自动下载模型到 assets/llm/，打包进 APK。
 */

package com.hud.navi

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"

        // assets 中的模型目录
        private const val ASSET_DIR = "llm"

        // 模型名称
        const val MODEL_NAME = "Qwen2.5-0.5B-Instruct-MNN"

        private const val BUFFER_SIZE = 64 * 1024  // 64KB buffer for extraction
    }

    /**
     * 获取模型在内部存储中的目录
     */
    fun getModelDir(): File {
        val dir = File(context.filesDir, "llm_models/$MODEL_NAME")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 获取模型配置路径（给 MNN-LLM 引擎使用）
     */
    fun getConfigPath(): String {
        return File(getModelDir(), "config.json").absolutePath
    }

    /**
     * 检查模型是否已解压到内部存储
     */
    fun isModelReady(): Boolean {
        val modelDir = getModelDir()
        val configExists = File(modelDir, "config.json").exists()
        val hasWeight = modelDir.listFiles()?.any {
            it.name.endsWith(".mnn") || it.name.endsWith(".bin") || it.name.endsWith(".weight")
        } ?: false

        // 检查 marker 文件（解压完成后写入）
        val markerFile = File(modelDir, ".extracted")
        val ready = configExists && hasWeight && markerFile.exists()

        Log.i(TAG, "Model ready: $ready (config=$configExists, weight=$hasWeight, marker=${markerFile.exists()})")
        return ready
    }

    /**
     * 从 assets 解压模型到内部存储（首次启动时调用）
     *
     * 必须在后台线程调用（IO 密集型操作）
     *
     * @param onProgress 进度回调 (extractedFiles, totalFiles)
     * @return 解压是否成功
     */
    fun extractFromAssets(
        onProgress: ((Int, Int) -> Unit)? = null
    ): Boolean {
        if (isModelReady()) {
            Log.i(TAG, "Model already extracted, skipping")
            return true
        }

        val modelDir = getModelDir()
        Log.i(TAG, "Extracting model from assets/$ASSET_DIR to ${modelDir.absolutePath}")

        try {
            // 列出 assets/llm/ 目录中的所有文件
            val assetFiles = context.assets.list(ASSET_DIR) ?: run {
                Log.e(TAG, "assets/$ASSET_DIR directory not found or empty")
                return false
            }

            if (assetFiles.isEmpty()) {
                Log.e(TAG, "No model files in assets/$ASSET_DIR")
                return false
            }

            Log.i(TAG, "Found ${assetFiles.size} files in assets/$ASSET_DIR")

            var extractedCount = 0
            for (fileName in assetFiles) {
                val targetFile = File(modelDir, fileName)

                // 跳过已存在且大小相同的文件
                if (targetFile.exists() && targetFile.length() > 0) {
                    Log.d(TAG, "Already exists: $fileName (${targetFile.length()} bytes)")
                    extractedCount++
                    onProgress?.invoke(extractedCount, assetFiles.size)
                    continue
                }

                // 解压文件
                val success = extractAssetFile("$ASSET_DIR/$fileName", targetFile)
                if (success) {
                    extractedCount++
                    onProgress?.invoke(extractedCount, assetFiles.size)
                    Log.i(TAG, "Extracted: $fileName (${targetFile.length()} bytes)")
                } else {
                    Log.e(TAG, "Failed to extract: $fileName")
                    return false
                }
            }

            // 写入 marker 文件表示解压完成
            File(modelDir, ".extracted").writeText(System.currentTimeMillis().toString())
            Log.i(TAG, "Model extraction complete: $extractedCount/${assetFiles.size} files")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Extraction error: ${e.message}", e)
            return false
        }
    }

    /**
     * 解压单个 asset 文件
     */
    private fun extractAssetFile(assetPath: String, targetFile: File): Boolean {
        return try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Extract $assetPath failed: ${e.message}")
            false
        }
    }

    /**
     * 检查 assets 中是否有模型文件
     */
    fun hasAssetsModel(): Boolean {
        return try {
            val files = context.assets.list(ASSET_DIR)
            files != null && files.isNotEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot check assets: ${e.message}")
            false
        }
    }

    /**
     * 删除已解压的模型（释放空间，保留 assets 中的原始文件）
     */
    fun deleteExtractedModel(): Boolean {
        val modelDir = getModelDir()
        if (modelDir.exists()) {
            val deleted = modelDir.deleteRecursively()
            Log.i(TAG, "Extracted model deleted: $deleted")
            return deleted
        }
        return false
    }

    /**
     * 获取已解压模型占用空间（字节）
     */
    fun getExtractedSize(): Long {
        val modelDir = getModelDir()
        return modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
