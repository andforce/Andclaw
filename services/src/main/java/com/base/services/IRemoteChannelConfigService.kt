package com.base.services

interface IRemoteChannelConfigService {
    val tgToken: String
    fun setTgToken(token: String)
    fun getTgChatId(): Long
    fun setTgChatId(chatId: Long)

    fun getClawBotBaseUrl(): String
    fun setClawBotBaseUrl(url: String)

    fun getClawBotBotType(): String
    fun setClawBotBotType(botType: String)

    fun loadClawBotAuthState(): ClawBotAuthState?
    fun saveClawBotAuthState(state: ClawBotAuthState)
    fun clearClawBotAuthState()
    fun loadClawBotSyncBuf(): String
    fun saveClawBotSyncBuf(syncBuf: String)

    fun getFeishuAppId(): String
    fun setFeishuAppId(appId: String)
    fun getFeishuAppSecret(): String
    fun setFeishuAppSecret(secret: String)
}
