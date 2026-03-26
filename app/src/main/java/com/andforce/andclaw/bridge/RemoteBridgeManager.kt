package com.andforce.andclaw.bridge

import android.content.Context
import android.util.Log
import com.andforce.andclaw.BuildConfig
import com.andforce.andclaw.bridge.clawbot.ClawBotApiClient
import com.andforce.andclaw.bridge.clawbot.ClawBotBridge
import com.andforce.andclaw.bridge.clawbot.buildClawBotAuthStateFromConfirmed
import com.andforce.andclaw.bridge.clawbot.isCompleteForBridge
import com.andforce.andclaw.bridge.feishu.FeishuBridge
import com.base.services.BridgeStatus
import com.base.services.ClawBotAuthState
import com.base.services.ClawBotDefaults
import com.base.services.ClawBotLoginStatus
import com.base.services.ClawBotQrCodeResult
import com.base.services.ClawBotQrPollResult
import com.base.services.FeishuInboundMessage
import com.base.services.IRemoteBridgeService
import com.base.services.IRemoteChannelConfigService
import com.base.services.RemoteChannel
import com.base.services.RemoteChannelPreferences
import com.base.services.RemoteIncomingMessage
import com.base.services.RemoteSession
import com.base.services.TgInboundMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

class RemoteBridgeManager(
    private val context: Context
) : IRemoteBridgeService, IRemoteChannelConfigService {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 与 [com.andforce.andclaw.AgentController] 使用同一文件与键名，避免双源。 */
    private val agentPrefs by lazy {
        context.applicationContext.getSharedPreferences(AGENT_PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** ClawBot 等仍使用独立文件。 */
    private val channelPrefs by lazy {
        context.applicationContext.getSharedPreferences(RemoteChannelPreferences.NAME, Context.MODE_PRIVATE)
    }

    private val _telegramStatus = MutableStateFlow(BridgeStatus.NOT_CONFIGURED)
    override val telegramStatus: StateFlow<BridgeStatus> = _telegramStatus.asStateFlow()

    private val _clawBotStatus = MutableStateFlow(BridgeStatus.NOT_CONFIGURED)
    override val clawBotStatus: StateFlow<BridgeStatus> = _clawBotStatus.asStateFlow()

    private val _clawBotLoginStatus = MutableStateFlow(ClawBotLoginStatus.NOT_CONFIGURED)
    override val clawBotLoginStatus: StateFlow<ClawBotLoginStatus> = _clawBotLoginStatus.asStateFlow()

    private val _feishuStatus = MutableStateFlow(BridgeStatus.NOT_CONFIGURED)
    override val feishuStatus: StateFlow<BridgeStatus> = _feishuStatus.asStateFlow()

    private var telegramBridge: TelegramBridge? = null

    /** 当前已启动轮询的 token（trim 后）；用于幂等，避免重复创建 [TelegramBridge]。 */
    private var telegramBridgeActiveToken: String? = null

    private var inboundHandler: (suspend (TgInboundMessage) -> Unit)? = null

    private var clawBotBridge: ClawBotBridge? = null

    /** 当前已启动 ClawBot 的 botToken（trim 后）；用于幂等。 */
    private var clawBotBridgeActiveToken: String? = null

    private var clawBotInboundHandler: (suspend (RemoteIncomingMessage) -> Unit)? = null

    private var feishuBridge: FeishuBridge? = null
    private var feishuBridgeActiveAppId: String? = null
    private var feishuInboundHandler: (suspend (FeishuInboundMessage) -> Unit)? = null

    private val clawBotApiClient = ClawBotApiClient()

    init {
        migrateLegacyTgFromRemoteChannelPrefs()
    }

    override fun setTelegramInboundHandler(handler: suspend (TgInboundMessage) -> Unit) {
        inboundHandler = handler
    }

    override fun setClawBotInboundHandler(handler: suspend (RemoteIncomingMessage) -> Unit) {
        clawBotInboundHandler = handler
    }

    override fun setFeishuInboundHandler(handler: suspend (FeishuInboundMessage) -> Unit) {
        feishuInboundHandler = handler
    }

    override fun startEligibleBridges() {
        startTelegramBridgeIfConfigured()
        startClawBotBridgeIfConfigured(forceRelogin = false)
        startFeishuBridgeIfConfigured()
    }

    override fun stopAllBridges() {
        stopTelegramBridge()
        stopClawBotBridgeInternal()
        stopFeishuBridgeInternal()
    }

    override fun startTelegramBridgeIfConfigured() {
        val token = tgToken.trim()
        if (token.isBlank()) {
            if (telegramBridge != null) {
                stopTelegramBridge()
                _telegramStatus.value = BridgeStatus.NOT_CONFIGURED
            } else {
                _telegramStatus.value = BridgeStatus.NOT_CONFIGURED
            }
            return
        }

        if (telegramBridge != null && telegramBridgeActiveToken == token) {
            return
        }

        telegramBridge?.stop()
        telegramBridgeActiveToken = null
        val bridge = TelegramBridge(
            scope = scope,
            getAllowedChatId = { agentPrefs.getLong(KEY_TG_CHAT_ID, 0L) },
            onInbound = { msg ->
                inboundHandler?.invoke(msg) ?: Unit
            },
            onConnectionStatus = { status -> _telegramStatus.value = status }
        )
        telegramBridge = bridge
        telegramBridgeActiveToken = token
        bridge.start(token)
    }

    override fun stopTelegramBridge() {
        telegramBridge?.stop()
        telegramBridge = null
        telegramBridgeActiveToken = null
        _telegramStatus.value = BridgeStatus.STOPPED
    }

    override fun startClawBotBridgeIfConfigured(forceRelogin: Boolean) {
        val auth = loadClawBotAuthState()
        if (auth == null || !auth.isCompleteForBridge()) {
            stopClawBotBridgeInternal()
            _clawBotStatus.value = BridgeStatus.NOT_CONFIGURED
            _clawBotLoginStatus.value = ClawBotLoginStatus.LOGIN_REQUIRED
            return
        }
        val token = auth.botToken.trim()
        _clawBotLoginStatus.value = ClawBotLoginStatus.CONNECTED

        if (forceRelogin) {
            clawBotBridge?.stop(updateStatus = false)
            clawBotBridge = null
            clawBotBridgeActiveToken = null
        }
    
        if (clawBotBridge != null && clawBotBridgeActiveToken == token) {
            return
        }
    
        clawBotBridge?.stop(updateStatus = false)
        clawBotBridge = null
        clawBotBridgeActiveToken = null
    
        val bridge = ClawBotBridge(
            scope = scope,
            channelConfig = this,
            apiClient = clawBotApiClient,
            onInbound = { msg ->
                clawBotInboundHandler?.invoke(msg) ?: Unit
            },
            onConnectionStatus = { status -> _clawBotStatus.value = status }
        )
        clawBotBridge = bridge
        clawBotBridgeActiveToken = token
        bridge.start()
    }

    override suspend fun requestClawBotQrCode(): ClawBotQrCodeResult = withContext(Dispatchers.IO) {
        val baseUrl = getClawBotBaseUrl()
        val botType = getClawBotBotType()
        val resp = clawBotApiClient.getBotQrcode(baseUrl, botType)
        _clawBotLoginStatus.value = ClawBotLoginStatus.QR_READY
        ClawBotQrCodeResult(resp.qrcode, resp.qrcodeImgContent)
    }

    override suspend fun pollClawBotQrCodeStatus(qrcode: String): ClawBotQrPollResult = withContext(Dispatchers.IO) {
        val baseUrl = getClawBotBaseUrl()
        val resp = clawBotApiClient.getQrcodeStatus(baseUrl, qrcode)
        when (resp.phase) {
            com.base.services.ClawBotQrPollPhase.SCANED -> {
                _clawBotLoginStatus.value = ClawBotLoginStatus.WAITING_CONFIRM
            }
            com.base.services.ClawBotQrPollPhase.CONFIRMED -> {
                val auth = buildClawBotAuthStateFromConfirmed(
                    resp, baseUrl, getClawBotBotType(), System.currentTimeMillis()
                )
                if (auth != null) {
                    saveClawBotAuthState(auth)
                    _clawBotLoginStatus.value = ClawBotLoginStatus.CONNECTED
                }
                return@withContext ClawBotQrPollResult(resp.phase, auth)
            }
            com.base.services.ClawBotQrPollPhase.EXPIRED -> {
                _clawBotLoginStatus.value = ClawBotLoginStatus.LOGIN_REQUIRED
            }
            else -> {}
        }
        ClawBotQrPollResult(resp.phase, null)
    }

    private fun stopClawBotBridgeInternal() {
        clawBotBridge?.stop()
        clawBotBridge = null
        clawBotBridgeActiveToken = null
        _clawBotStatus.value = BridgeStatus.STOPPED
    }

    override fun startFeishuBridgeIfConfigured() {
        val appId = getFeishuAppId().trim()
        val appSecret = getFeishuAppSecret().trim()
        
        if (appId.isBlank() || appSecret.isBlank()) {
            if (feishuBridge != null) {
                stopFeishuBridgeInternal()
            }
            _feishuStatus.value = BridgeStatus.NOT_CONFIGURED
            return
        }
        
        if (feishuBridge != null && feishuBridgeActiveAppId == appId) {
            return
        }
        
        feishuBridge?.stop()
        feishuBridgeActiveAppId = null
        
        val bridge = FeishuBridge(
            scope = scope,
            getAppId = { getFeishuAppId() },
            getAppSecret = { getFeishuAppSecret() },
            onInbound = { msg ->
                feishuInboundHandler?.invoke(msg) ?: Unit
            },
            onConnectionStatus = { status -> _feishuStatus.value = status }
        )
        feishuBridge = bridge
        feishuBridgeActiveAppId = appId
        bridge.start()
    }

    override fun stopFeishuBridge() {
        stopFeishuBridgeInternal()
    }

    private fun stopFeishuBridgeInternal() {
        feishuBridge?.stop()
        feishuBridge = null
        feishuBridgeActiveAppId = null
        _feishuStatus.value = BridgeStatus.STOPPED
    }

    override suspend fun sendTyping(session: RemoteSession) {
        when (session.channel) {
            RemoteChannel.TELEGRAM -> {
                val chatId = session.sessionKey.toLongOrNull() ?: return
                telegramBridge?.sendTyping(chatId)
            }
            RemoteChannel.CLAWBOT -> clawBotBridge?.sendTyping(session)
            RemoteChannel.FEISHU -> feishuBridge?.sendTyping(session)
        }
    }

    override suspend fun sendText(session: RemoteSession, text: String, replyHint: String?) {
        when (session.channel) {
            RemoteChannel.TELEGRAM -> {
                val chatId = session.sessionKey.toLongOrNull() ?: return
                val replyTo = replyHint?.toLongOrNull()
                telegramBridge?.sendText(chatId, text, replyTo)
            }
            RemoteChannel.CLAWBOT -> clawBotBridge?.sendText(session, text)
            RemoteChannel.FEISHU -> feishuBridge?.sendText(session, text)
        }
    }

    override suspend fun sendPhoto(
        session: RemoteSession,
        photoBytes: ByteArray,
        caption: String?,
        fileName: String
    ) {
        when (session.channel) {
            RemoteChannel.TELEGRAM -> {
                val chatId = session.sessionKey.toLongOrNull() ?: return
                telegramBridge?.sendPhoto(chatId, photoBytes, caption, fileName)
            }
            RemoteChannel.CLAWBOT -> {
                val text = ClawBotMediaFallbackMessages.image(caption, fileName)
                val b = clawBotBridge
                if (b == null) {
                    Log.w(TAG, "sendPhoto: ClawBotBridge inactive; fallback text not sent (file=$fileName)")
                } else {
                    b.sendText(session, text)
                }
            }
            RemoteChannel.FEISHU -> {
                feishuBridge?.sendPhoto(session, photoBytes, caption, fileName)
            }
        }
    }

    override suspend fun sendVideo(
        session: RemoteSession,
        videoBytes: ByteArray,
        caption: String?,
        fileName: String
    ) {
        when (session.channel) {
            RemoteChannel.TELEGRAM -> {
                val chatId = session.sessionKey.toLongOrNull() ?: return
                telegramBridge?.sendVideo(chatId, videoBytes, caption, fileName)
            }
            RemoteChannel.CLAWBOT -> {
                val text = ClawBotMediaFallbackMessages.video(caption, fileName)
                val b = clawBotBridge
                if (b == null) {
                    Log.w(TAG, "sendVideo: ClawBotBridge inactive; fallback text not sent (file=$fileName)")
                } else {
                    b.sendText(session, text)
                }
            }
            RemoteChannel.FEISHU -> {
                val text = "[视频已保存到本地] $fileName${caption?.let { " - $it" } ?: ""}"
                feishuBridge?.sendText(session, text)
            }
        }
    }

    override suspend fun sendAudio(
        session: RemoteSession,
        audioBytes: ByteArray,
        caption: String?,
        fileName: String
    ) {
        when (session.channel) {
            RemoteChannel.TELEGRAM -> {
                val chatId = session.sessionKey.toLongOrNull() ?: return
                telegramBridge?.sendAudio(chatId, audioBytes, caption, fileName)
            }
            RemoteChannel.CLAWBOT -> {
                val text = ClawBotMediaFallbackMessages.audio(caption, fileName)
                val b = clawBotBridge
                if (b == null) {
                    Log.w(TAG, "sendAudio: ClawBotBridge inactive; fallback text not sent (file=$fileName)")
                } else {
                    b.sendText(session, text)
                }
            }
            RemoteChannel.FEISHU -> {
                val text = "[音频已保存到本地] $fileName${caption?.let { " - $it" } ?: ""}"
                feishuBridge?.sendText(session, text)
            }
        }
    }

    override val tgToken: String
        get() = agentPrefs.getString(KEY_TG_TOKEN, null) ?: BuildConfig.TG_TOKEN

    override fun setTgToken(token: String) {
        val oldToken = tgToken
        agentPrefs.edit().putString(KEY_TG_TOKEN, token).apply()
        if (token != oldToken) {
            stopTelegramBridge()
            if (token.isBlank()) {
                _telegramStatus.value = BridgeStatus.NOT_CONFIGURED
            } else {
                startTelegramBridgeIfConfigured()
            }
        }
    }

    override fun getTgChatId(): Long =
        agentPrefs.getLong(KEY_TG_CHAT_ID, 0L)

    override fun setTgChatId(chatId: Long) {
        agentPrefs.edit().putLong(KEY_TG_CHAT_ID, chatId).apply()
    }

    override fun getClawBotBaseUrl(): String {
        val raw = channelPrefs.getString(KEY_CLAWBOT_BASE_URL, null)?.trim().orEmpty()
        return raw.ifBlank { ClawBotDefaults.DEFAULT_BASE_URL }
    }

    override fun setClawBotBaseUrl(url: String) {
        channelPrefs.edit().putString(KEY_CLAWBOT_BASE_URL, url).apply()
    }

    override fun getClawBotBotType(): String {
        val raw = channelPrefs.getString(KEY_CLAWBOT_BOT_TYPE, null)?.trim().orEmpty()
        return raw.ifBlank { ClawBotDefaults.DEFAULT_BOT_TYPE }
    }

    override fun setClawBotBotType(botType: String) {
        channelPrefs.edit().putString(KEY_CLAWBOT_BOT_TYPE, botType).apply()
    }

    override fun loadClawBotAuthState(): ClawBotAuthState? {
        val raw = channelPrefs.getString(KEY_CLAWBOT_AUTH_STATE, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            ClawBotAuthState(
                botToken = json.optString(JSON_BOT_TOKEN, ""),
                baseUrl = json.optString(JSON_BASE_URL, ""),
                accountId = json.optString(JSON_ACCOUNT_ID, ""),
                userId = json.optString(JSON_USER_ID, ""),
                botType = json.optString(JSON_BOT_TYPE, ""),
                savedAt = json.optLong(JSON_SAVED_AT, 0L)
            )
        }.getOrNull()
    }

    override fun saveClawBotAuthState(state: ClawBotAuthState) {
        val raw = JSONObject().apply {
            put(JSON_BOT_TOKEN, state.botToken)
            put(JSON_BASE_URL, state.baseUrl)
            put(JSON_ACCOUNT_ID, state.accountId)
            put(JSON_USER_ID, state.userId)
            put(JSON_BOT_TYPE, state.botType)
            put(JSON_SAVED_AT, state.savedAt)
        }.toString()
        channelPrefs.edit().putString(KEY_CLAWBOT_AUTH_STATE, raw).apply()
    }

    override fun clearClawBotAuthState() {
        channelPrefs.edit().remove(KEY_CLAWBOT_AUTH_STATE).apply()
    }

    override fun loadClawBotSyncBuf(): String =
        channelPrefs.getString(KEY_CLAWBOT_SYNC_BUF, "") ?: ""

    override fun saveClawBotSyncBuf(syncBuf: String) {
        channelPrefs.edit().putString(KEY_CLAWBOT_SYNC_BUF, syncBuf).apply()
    }

    override fun getFeishuAppId(): String =
        channelPrefs.getString(KEY_FEISHU_APP_ID, "") ?: ""

    override fun setFeishuAppId(appId: String) {
        val oldAppId = getFeishuAppId()
        channelPrefs.edit().putString(KEY_FEISHU_APP_ID, appId).apply()
        if (appId.trim() != oldAppId.trim()) {
            stopFeishuBridgeInternal()
            if (appId.isNotBlank() && getFeishuAppSecret().isNotBlank()) {
                startFeishuBridgeIfConfigured()
            } else {
                _feishuStatus.value = BridgeStatus.NOT_CONFIGURED
            }
        }
    }

    override fun getFeishuAppSecret(): String =
        channelPrefs.getString(KEY_FEISHU_APP_SECRET, "") ?: ""

    override fun setFeishuAppSecret(secret: String) {
        val oldSecret = getFeishuAppSecret()
        channelPrefs.edit().putString(KEY_FEISHU_APP_SECRET, secret).apply()
        if (secret.trim() != oldSecret.trim() && getFeishuAppId().isNotBlank()) {
            stopFeishuBridgeInternal()
            if (secret.isNotBlank()) {
                startFeishuBridgeIfConfigured()
            } else {
                _feishuStatus.value = BridgeStatus.NOT_CONFIGURED
            }
        }
    }

    /**
     * 旧版 [RemoteChannelPreferences] 中曾单独存 Telegram；迁移到 agent_config，避免丢配置。
     */
    private fun migrateLegacyTgFromRemoteChannelPrefs() {
        val legacy = context.applicationContext.getSharedPreferences(
            RemoteChannelPreferences.NAME,
            Context.MODE_PRIVATE
        )
        val editor = agentPrefs.edit()
        var changed = false
        if (!agentPrefs.contains(KEY_TG_TOKEN)) {
            val t = legacy.getString(LEGACY_KEY_TG_TOKEN, null)
            if (!t.isNullOrBlank()) {
                editor.putString(KEY_TG_TOKEN, t)
                changed = true
            }
        }
        if (!agentPrefs.contains(KEY_TG_CHAT_ID)) {
            if (legacy.contains(LEGACY_KEY_TG_CHAT_ID)) {
                editor.putLong(KEY_TG_CHAT_ID, legacy.getLong(LEGACY_KEY_TG_CHAT_ID, 0L))
                changed = true
            }
        }
        if (changed) {
            editor.apply()
        }
    }

    private companion object {
        const val TAG = "RemoteBridgeManager"
        const val AGENT_PREFS_NAME = "agent_config"
        const val KEY_TG_TOKEN = "tg_token"
        const val KEY_TG_CHAT_ID = "tg_allowed_chat_id"

        const val LEGACY_KEY_TG_TOKEN = "tg_token"
        const val LEGACY_KEY_TG_CHAT_ID = "tg_chat_id"

        const val KEY_CLAWBOT_AUTH_STATE = "clawbot_auth_state"
        const val KEY_CLAWBOT_SYNC_BUF = "clawbot_sync_buf"
        const val KEY_CLAWBOT_BASE_URL = "clawbot_base_url"
        const val KEY_CLAWBOT_BOT_TYPE = "clawbot_bot_type"

        const val KEY_FEISHU_APP_ID = "feishu_app_id"
        const val KEY_FEISHU_APP_SECRET = "feishu_app_secret"

        const val JSON_BOT_TOKEN = "botToken"
        const val JSON_BASE_URL = "baseUrl"
        const val JSON_ACCOUNT_ID = "accountId"
        const val JSON_USER_ID = "userId"
        const val JSON_BOT_TYPE = "botType"
        const val JSON_SAVED_AT = "savedAt"
    }
}
