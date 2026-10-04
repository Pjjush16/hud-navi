/*
 * SetupActivity.kt - OOBE 首次启动配置页面 (v12.0)
 *
 * 用户首次启动应用时显示，配置：
 * 1. 智谱 AI API Key（免费获取：bigmodel.cn）
 * 2. 选择模型（免费/付费）
 *
 * 配置完成后保存到 SharedPreferences，进入主界面。
 */

package com.hud.navi

import android.content.Intent
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SetupActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "hud_navi_prefs"
        const val KEY_API_KEY = "zhipu_api_key"
        const val KEY_MODEL = "zhipu_model"
        const val KEY_SETUP_DONE = "setup_done"

        // 智谱 API 端点
        const val API_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"

        // 可选模型列表
        data class ModelOption(
            val id: String,
            val name: String,
            val desc: String,
            val free: Boolean
        )

        val MODELS = listOf(
            // === 文本模型（免费） ===
            ModelOption("glm-4.7-flash", "GLM-4.7-Flash", "免费文本 · 30B参数 · 200K上下文 · 推荐", true),
            ModelOption("glm-4.5-flash", "GLM-4.5-Flash", "免费文本 · 128K上下文", true),
            ModelOption("glm-4-flash-250414", "GLM-4-Flash-250414", "免费文本 · 128K上下文 · 增强版", true),
            // === 视觉理解模型（免费） ===
            ModelOption("glm-4.6v-flash", "GLM-4.6V-Flash", "免费视觉 · 支持视觉推理", true),
            ModelOption("glm-4.1v-thinking-flash", "GLM-4.1V-Thinking-Flash", "免费视觉 · 支持视觉推理", true),
            ModelOption("glm-4v-flash", "GLM-4V-Flash", "免费视觉 · 图像理解", true),
            // === 图像生成模型（免费） ===
            ModelOption("cogview-3-flash", "CogView-3-Flash", "免费 · 图像生成", true),
            // === 视频生成模型（免费） ===
            ModelOption("cogvideox-flash", "CogVideoX-Flash", "免费 · 视频生成", true),
            // === 付费模型（可选） ===
            ModelOption("glm-4.7", "GLM-4.7", "付费 · 更强能力 · 200K上下文", false),
            ModelOption("glm-5.3", "GLM-5.3", "付费 · 旗舰模型 · 1M上下文", false),
            ModelOption("glm-5.3-flash", "GLM-5.3-Flash", "付费 · 多模态 · 1M上下文", false),
            ModelOption("glm-5.3-flashx", "GLM-5.3-FlashX", "付费 · 200tokens/s高速", false),
        )
    }

    private lateinit var etApiKey: EditText
    private lateinit var spModel: Spinner
    private lateinit var btnConfirm: Button
    private lateinit var btnSkip: Button
    private lateinit var tvLink: TextView
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 检查是否已完成配置
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SETUP_DONE, false)) {
            startMainActivity()
            return
        }

        setContentView(createLayout())
        setupUI()
    }

    private fun createLayout(): View {
        val scroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF1A1A2E.toInt())
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 80)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 标题
        val title = TextView(this).apply {
            text = "🚗 HUD 导航"
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        container.addView(title)

        val subtitle = TextView(this).apply {
            text = "语音助手配置"
            textSize = 18f
            setTextColor(0xAAFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 40)
        }
        container.addView(subtitle)

        // 说明文字
        val desc = TextView(this).apply {
            text = "语音助手使用智谱 AI 大模型进行语义理解。\n请输入您的 API Key（免费注册即可获取）。"
            textSize = 14f
            setTextColor(0xCCFFFFFF.toInt())
            setPadding(0, 0, 0, 24)
        }
        container.addView(desc)

        // 获取 API Key 链接
        tvLink = TextView(this).apply {
            text = "👉 点击这里注册并获取免费 API Key"
            textSize = 14f
            setTextColor(0xFF64B5F6.toInt())
            setPadding(0, 0, 0, 24)
            movementMethod = LinkMovementMethod.getInstance()
        }
        container.addView(tvLink)

        // API Key 输入
        val apiLabel = TextView(this).apply {
            text = "API Key"
            textSize = 14f
            setTextColor(0xCCFFFFFF.toInt())
            setPadding(0, 0, 0, 8)
        }
        container.addView(apiLabel)

        etApiKey = EditText(this).apply {
            hint = "请输入您的智谱 API Key"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0x66FFFFFF.toInt())
            setBackgroundColor(0xFF2D2D44.toInt())
            setPadding(24, 20, 24, 20)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            textSize = 14f
        }
        container.addView(etApiKey)

        // 模型选择
        val modelLabel = TextView(this).apply {
            text = "选择模型"
            textSize = 14f
            setTextColor(0xCCFFFFFF.toInt())
            setPadding(0, 24, 0, 8)
        }
        container.addView(modelLabel)

        spModel = Spinner(this).apply {
            setBackgroundColor(0xFF2D2D44.toInt())
            setPadding(24, 16, 24, 16)
        }
        container.addView(spModel)

        // 模型说明
        val modelDesc = TextView(this).apply {
            text = "推荐选择免费的 GLM-4.7-Flash，日常使用完全够用"
            textSize = 12f
            setTextColor(0x88FFFFFF.toInt())
            setPadding(0, 8, 0, 32)
        }
        container.addView(modelDesc)

        // 状态文字
        tvStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFEF5350.toInt())
            setPadding(0, 0, 0, 16)
            visibility = View.GONE
        }
        container.addView(tvStatus)

        // 确认按钮
        btnConfirm = Button(this).apply {
            text = "确认并开始"
            setBackgroundColor(0xFF4CAF50.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            setPadding(0, 20, 0, 20)
        }
        container.addView(btnConfirm)

        // 跳过按钮
        btnSkip = Button(this).apply {
            text = "跳过（仅使用关键词指令模式）"
            setBackgroundColor(0x00000000)
            setTextColor(0x88FFFFFF.toInt())
            textSize = 14f
            setPadding(0, 16, 0, 16)
        }
        container.addView(btnSkip)

        scroll.addView(container)
        return scroll
    }

    private fun setupUI() {
        // 模型选择下拉框
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, MODELS.map {
            "${it.name} (${if (it.free) "免费" else "付费"})"
        })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spModel.adapter = adapter

        // 链接点击
        tvLink.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://bigmodel.cn/"))
            startActivity(intent)
        }

        // 确认按钮
        btnConfirm.setOnClickListener {
            val apiKey = etApiKey.text.toString().trim()
            if (apiKey.isBlank()) {
                showStatus("请输入 API Key")
                return@setOnClickListener
            }

            val selectedModel = MODELS[spModel.selectedItemPosition]
            saveConfig(apiKey, selectedModel.id)
        }

        // 跳过按钮
        btnSkip.setOnClickListener {
            // 保存空配置（关键词模式）
            saveConfig("", "")
        }
    }

    private fun saveConfig(apiKey: String, modelId: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_API_KEY, apiKey)
            putString(KEY_MODEL, modelId)
            putBoolean(KEY_SETUP_DONE, true)
            apply()
        }

        startMainActivity()
    }

    private fun startMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun showStatus(msg: String) {
        tvStatus.text = msg
        tvStatus.visibility = View.VISIBLE
    }
}
