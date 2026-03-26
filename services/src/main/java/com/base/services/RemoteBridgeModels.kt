package com.base.services

enum class RemoteChannel {
    TELEGRAM,
    CLAWBOT,
    FEISHU
}

data class RemoteSession(
    val channel: RemoteChannel,
    val sessionKey: String,
    val replyToken: String? = null,
    val userId: String? = null,
    val displayName: String? = null,
    val messageId: String? = null,
    val accountId: String? = null,
    val lastIncomingAt: Long = 0L
)

/**
 * 入站消息：扁平字段，不嵌套 [RemoteSession]；与会话相关的字段与 [RemoteSession] 语义对齐。
 */
data class RemoteIncomingMessage(
    val channel: RemoteChannel,
    val sessionKey: String,
    val replyToken: String? = null,
    val userId: String? = null,
    val displayName: String? = null,
    val messageId: String,
    val accountId: String? = null,
    val lastIncomingAt: Long = 0L,
    val text: String,
    val senderId: String? = null,
    val senderDisplayName: String? = null,
    val receivedAtMs: Long = 0L
)

object RemoteChannelPreferences {
    const val NAME = "remote_channel_config"
}

/** Telegram 入站文本消息（与 app 模块 [com.andforce.andclaw.TgMessage] 字段对齐，供桥接层跨模块回调）。 */
data class TgInboundMessage(
    val chatId: Long,
    val messageId: Long,
    val text: String
)

/** 飞书入站消息 */
data class FeishuInboundMessage(
    val eventId: String,
    val messageId: String,
    val chatId: String,
    val chatType: String,
    val senderId: String,
    val senderType: String,
    val text: String,
    val createTime: Long = 0L,
    val parentMessageId: String? = null
)
