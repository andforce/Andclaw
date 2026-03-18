package com.andforce.andclaw.model

import android.content.Intent
import com.google.gson.annotations.SerializedName

// AI 返回的操作模型
data class AiAction(
    /**
     * 操作类型: "intent", "click", "finish", "error"
     */
    @SerializedName("type")
    val type: String = "error",

    /**
     * AI 对当前任务进度的总结（有助于保持会话状态）
     */
    @SerializedName("progress")
    val progress: String? = null,

    /**
     * 执行此操作的原因（以用户语言显示在聊天气泡中）
     */
    @SerializedName("reason")
    val reason: String? = null,

    val data: String? = null,
    /**
     * Intent 的 Action 字符串 (如 "android.intent.action.VIEW")
     */
    @SerializedName("action")
    val action: String? = null,

    /**
     * Intent 的参数键值对
     * AI 传回的数字通常会被解析为 Double，布尔值为 Boolean
     */
    @SerializedName("extras")
    val extras: Map<String, Any>? = null,

    /**
     * 点击操作的 X 坐标
     */
    @SerializedName("x")
    val x: Int = 0,

    /**
     * 点击操作的 Y 坐标
     */
    @SerializedName("y")
    val y: Int = 0,

    /**
     * 可选：目标应用的包名（用于显式启动）
     */
    @SerializedName("package_name")
    val packageName: String? = null,

    /**
     * 可选：目标 Activity 的类名
     */
    @SerializedName("class_name")
    val className: String? = null,

    /**
     * DPM 操作名（如 "lockNow"、"setApplicationHidden"）
     */
    @SerializedName("dpm_action")
    val dpmAction: String? = null,

    @SerializedName("end_x")
    val endX: Int = 0,

    @SerializedName("end_y")
    val endY: Int = 0,

    @SerializedName("duration")
    val duration: Long = 0,

    @SerializedName("text")
    val text: String? = null,

    @SerializedName("global_action")
    val globalAction: String? = null,

    @SerializedName("camera_action")
    val cameraAction: String? = null,

    @SerializedName("screen_record_action")
    val screenRecordAction: String? = null,

    @SerializedName("volume_action")
    val volumeAction: String? = null,

    @SerializedName("audio_record_action")
    val audioRecordAction: String? = null
) {
    companion object {
        const val TYPE_INTENT = "intent"
        const val TYPE_CLICK = "click"
        const val TYPE_DPM = "dpm"
        const val TYPE_SWIPE = "swipe"
        const val TYPE_LONG_PRESS = "long_press"
        const val TYPE_TEXT_INPUT = "text_input"
        const val TYPE_GLOBAL_ACTION = "global_action"
        const val TYPE_SCREENSHOT = "screenshot"
        const val TYPE_DOWNLOAD = "download"
        const val TYPE_WAIT = "wait"
        const val TYPE_CAMERA = "camera"
        const val TYPE_SCREEN_RECORD = "screen_record"
        const val TYPE_VOLUME = "volume"
        const val TYPE_AUDIO_RECORD = "audio_record"
        const val TYPE_WAKE_SCREEN = "wake_screen"
        const val TYPE_FINISH = "finish"
        const val TYPE_ERROR = "error"
    }

    /**
     * 辅助方法：将 Map 中的 extras 填充到 Intent 中
     * 处理了 AI 常见的 Double 转 Int 的问题
     */
    fun fillIntentExtras(intent: Intent) {
        extras?.forEach { (key, value) ->
            when (value) {
                is Boolean -> intent.putExtra(key, value)
                is String -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is Double -> {
                    if (value == value.toInt().toDouble()) {
                        intent.putExtra(key, value.toInt())
                    } else {
                        intent.putExtra(key, value)
                    }
                }
                is Long -> intent.putExtra(key, value)
                else -> intent.putExtra(key, value.toString())
            }
        }
    }
}

data class AgentUiState(
    val isRunning: Boolean = false,
    val status: String = "waiting command...",
    val userInput: String = "",
    val aiProvider: String = "Kimi Code"
)

data class ApiConfig(
    val provider: String = "Kimi Code",
    val apiKey: String = "",
    val apiUrl: String = "https://api.kimi.com/coding",
    val model: String = "kimi-k2.5"
)

data class ChatMessage(
    val role: String,
    val content: String,
    val action: AiAction? = null,
    val screenshotBase64: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val id: Long = 0
)