package com.base.services

import kotlinx.coroutines.flow.StateFlow

interface IRemoteBridgeService {
    val telegramStatus: StateFlow<BridgeStatus>
    val clawBotStatus: StateFlow<BridgeStatus>
    val clawBotLoginStatus: StateFlow<ClawBotLoginStatus>
    val feishuStatus: StateFlow<BridgeStatus>

    fun startEligibleBridges()
    fun stopAllBridges()
    fun startTelegramBridgeIfConfigured()
    fun stopTelegramBridge()
    fun startClawBotBridgeIfConfigured(forceRelogin: Boolean = false)
    fun startFeishuBridgeIfConfigured()
    fun stopFeishuBridge()

    fun setTelegramInboundHandler(handler: suspend (TgInboundMessage) -> Unit) {}

    fun setClawBotInboundHandler(handler: suspend (RemoteIncomingMessage) -> Unit) {}

    fun setFeishuInboundHandler(handler: suspend (FeishuInboundMessage) -> Unit) {}

    suspend fun requestClawBotQrCode(): ClawBotQrCodeResult
    suspend fun pollClawBotQrCodeStatus(qrcode: String): ClawBotQrPollResult

    suspend fun sendTyping(session: RemoteSession)
    suspend fun sendText(session: RemoteSession, text: String, replyHint: String? = null)
    suspend fun sendPhoto(
        session: RemoteSession,
        photoBytes: ByteArray,
        caption: String? = null,
        fileName: String = "photo.png"
    )
    suspend fun sendVideo(
        session: RemoteSession,
        videoBytes: ByteArray,
        caption: String? = null,
        fileName: String = "video.mp4"
    )
    suspend fun sendAudio(
        session: RemoteSession,
        audioBytes: ByteArray,
        caption: String? = null,
        fileName: String = "audio.m4a"
    )
}
