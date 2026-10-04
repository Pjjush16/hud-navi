/*
 * ChatEngine.kt - 聊天引擎 + TTS 语音回复 (v11.0)
 *
 * 当 IntentClassifier 判定 intent=chat 时，使用 LLM 生成回复并通过 TTS 播报。
 * 当 intent=search 时，调用 WebSearchClient 搜索并摘要回复。
 */

package com.hud.navi

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class ChatEngine(
    private val context: Context,
    private val intentClassifier: IntentClassifier,
    private val webSearchClient: WebSearchClient
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "ChatEngine"
        private const val MAX_TTS_LENGTH = 200  // TTS 最大朗读字符数（避免太长）
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var onTtsDone: (() -> Unit)? = null

    /**
     * 初始化 TTS 引擎
     */
    fun init() {
        try {
            tts = TextToSpeech(context, this)
            Log.i(TAG, "TTS engine initializing...")
        } catch (e: Exception) {
            Log.e(TAG, "TTS init failed: ${e.message}", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.CHINESE)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Chinese TTS not available, trying default")
                tts?.setLanguage(Locale.getDefault())
            }
            tts?.setSpeechRate(1.1f)  // 稍微加快语速
            tts?.setPitch(1.0f)

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "TTS done: $utteranceId")
                    onTtsDone?.invoke()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {}
            })

            ttsReady = true
            Log.i(TAG, "TTS engine ready")
        } else {
            Log.e(TAG, "TTS init failed with status: $status")
        }
    }

    /**
     * 处理意图结果 — 根据 intent 类型执行对应动作
     *
     * @param result IntentClassifier 输出的意图
     * @param onUiCallback UI 更新回调（显示结果到 HUD）
     */
    fun handleIntent(result: IntentResult, onUiCallback: ((String) -> Unit)? = null) {
        handleIntentInternal(result, onUiCallback)
    }

    private fun handleIntentInternal(result: IntentResult, onUiCallback: ((String) -> Unit)? = null) {
        Log.i(TAG, "Handling intent: ${result.intent}/${result.action}")

        when (result.intent) {
            IntentResult.INTENT_NAVIGATION -> handleNavigation(result, onUiCallback)
            IntentResult.INTENT_SEARCH -> handleSearch(result, onUiCallback)
            IntentResult.INTENT_HUD_CONTROL -> handleHudControl(result, onUiCallback)
            IntentResult.INTENT_MUSIC -> handleMusic(result, onUiCallback)
            IntentResult.INTENT_CHAT -> handleChat(result, onUiCallback)
            else -> {
                speak("抱歉，我不太理解你的意思。")
                onUiCallback?.invoke("未识别的指令")
            }
        }
    }

    // ==================== 意图处理器 ====================

    /**
     * 导航意图 — 搜索 POI / 设置目的地
     */
    private fun handleNavigation(result: IntentResult, callback: ((String) -> Unit)?) {
        val keyword = result.params["keyword"] as? String ?: ""
        val action = result.action

        when (action) {
            "search_poi" -> {
                if (keyword.isNotBlank()) {
                    val sort = result.params["sort"] as? String ?: ""
                    val response = if (sort == "nearest") {
                        "好的，正在搜索附近的$keyword"
                    } else {
                        "好的，正在搜索$keyword"
                    }
                    speak(response)
                    callback?.invoke("🔍 搜索: $keyword")

                    // TODO: 对接 OpenStreetMap Overpass API 搜索 POI
                    // 目前先通过 WebSearch 提供信息
                    Thread {
                        val searchResult = webSearchClient.search("附近 $keyword")
                        if (searchResult.success) {
                            val summary = searchResult.toSummary(2)
                            speak(summary)
                            callback?.invoke(summary.take(80))
                        }
                    }.start()
                } else {
                    speak("请告诉我要搜索什么地点？")
                    callback?.invoke("请说出目的地")
                }
            }
            else -> {
                speak("好的，$keyword")
                callback?.invoke("导航: $keyword")
            }
        }
    }

    /**
     * 搜索意图 — 联网搜索并播报结果
     */
    private fun handleSearch(result: IntentResult, callback: ((String) -> Unit)?) {
        val query = result.params["query"] as? String
            ?: result.params["keyword"] as? String
            ?: ""

        if (query.isBlank()) {
            speak("请告诉我要搜索什么？")
            callback?.invoke("请说出搜索内容")
            return
        }

        speak("正在搜索$query")
        callback?.invoke("🔍 搜索: $query")

        // 在后台线程执行网络搜索
        Thread {
            val searchResult = webSearchClient.search(query)

            if (searchResult.success) {
                // 将搜索结果摘要播报
                val summary = searchResult.toSummary(3)
                val ttsText = truncateForTts(summary)
                speak(ttsText)
                callback?.invoke(ttsText.take(80) + "...")
            } else {
                speak("抱歉，搜索失败，请稍后重试。")
                callback?.invoke("搜索失败")
            }
        }.start()
    }

    /**
     * HUD 控制意图 — 镜像、亮度等
     */
    private fun handleHudControl(result: IntentResult, callback: ((String) -> Unit)?) {
        when (result.action) {
            "mirror_toggle", "mirror_on" -> {
                speak("好的，已切换镜像模式")
                callback?.invoke("MIRROR_TOGGLE")
            }
            "mirror_off" -> {
                speak("好的，已关闭镜像")
                callback?.invoke("MIRROR_OFF")
            }
            "brightness_up" -> {
                speak("好的，已提高亮度")
                callback?.invoke("BRIGHTNESS_UP")
            }
            "brightness_down" -> {
                speak("好的，已降低亮度")
                callback?.invoke("BRIGHTNESS_DOWN")
            }
            else -> {
                speak("好的，已调整 HUD 设置")
                callback?.invoke("HUD 设置已更新")
            }
        }
    }

    /**
     * 音乐意图 — 播放/暂停/下一首
     */
    private fun handleMusic(result: IntentResult, callback: ((String) -> Unit)?) {
        when (result.action) {
            "play" -> {
                val query = result.params["query"] as? String ?: ""
                if (query.isNotBlank()) {
                    speak("正在播放$query")
                    callback?.invoke("▶ $query")
                } else {
                    speak("正在播放音乐")
                    callback?.invoke("▶ 播放")
                }
                // TODO: 对接系统媒体控制
            }
            "pause" -> {
                speak("已暂停")
                callback?.invoke("⏸ 暂停")
            }
            "next" -> {
                speak("下一首")
                callback?.invoke("⏭ 下一首")
            }
            "previous" -> {
                speak("上一首")
                callback?.invoke("⏮ 上一首")
            }
            else -> {
                speak("好的")
                callback?.invoke("音乐控制")
            }
        }
    }

    /**
     * 聊天意图 — 自由对话，LLM 生成回复 + TTS 播报
     */
    private fun handleChat(result: IntentResult, callback: ((String) -> Unit)?) {
        // 如果模型已经生成了回复文本，直接播报
        val text = result.params["text"] as? String
        if (!text.isNullOrBlank()) {
            val ttsText = truncateForTts(text)
            speak(ttsText)
            callback?.invoke(ttsText)
            return
        }

        // 否则用聊天模式重新生成回复
        speak("让我想想...")
        Thread {
            // TODO: 传入用户原始文本
            val reply = intentClassifier.chat("用户刚才说的话")
            val ttsText = truncateForTts(reply)
            speak(ttsText)
            callback?.invoke(ttsText)
        }.start()
    }

    // ==================== TTS 控制 ====================

    /**
     * 播报文本
     */
    fun speak(text: String) {
        if (!ttsReady || text.isBlank()) {
            Log.w(TAG, "TTS not ready or empty text")
            return
        }

        val truncated = truncateForTts(text)
        tts?.speak(truncated, TextToSpeech.QUEUE_ADD, null, "hud_${System.currentTimeMillis()}")
        Log.i(TAG, "Speaking: ${truncated.take(50)}...")
    }

    /**
     * 立即停止播报
     */
    fun stopSpeaking() {
        tts?.stop()
    }

    /**
     * 设置 TTS 完成回调
     */
    fun setOnTtsDoneListener(listener: (() -> Unit)?) {
        onTtsDone = listener
    }

    fun isTtsReady(): Boolean = ttsReady

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    // ==================== 工具方法 ====================

    /**
     * 截断文本以适应 TTS 长度限制
     * 避免播报过长的搜索结果
     */
    private fun truncateForTts(text: String): String {
        if (text.length <= MAX_TTS_LENGTH) return text
        // 在句号处截断
        val truncated = text.take(MAX_TTS_LENGTH)
        val lastPeriod = truncated.lastIndexOf('。')
        return if (lastPeriod > MAX_TTS_LENGTH / 2) {
            truncated.take(lastPeriod + 1)
        } else {
            truncated + "。"
        }
    }
}
