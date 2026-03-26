package com.andforce.andclaw.bridge.feishu

import android.util.Log
import com.base.services.BridgeStatus
import com.base.services.FeishuInboundMessage
import com.base.services.RemoteSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class FeishuBridge(
    private val scope: CoroutineScope,
    private val getAppId: () -> String,
    private val getAppSecret: () -> String,
    private val onInbound: suspend (FeishuInboundMessage) -> Unit,
    private val onConnectionStatus: (BridgeStatus) -> Unit
) {
    private var wsClient: FeishuWebSocketClient? = null
    private var wsJob: Job? = null
    private var currentAppId: String? = null

    private val apiClient = FeishuApiClient()

    fun start() {
        stopInternal(setStatus = false)
        
        val appId = getAppId().trim()
        val appSecret = getAppSecret().trim()
        
        if (appId.isBlank() || appSecret.isBlank()) {
            onConnectionStatus(BridgeStatus.NOT_CONFIGURED)
            return
        }
        
        if (wsClient != null && currentAppId == appId) {
            return
        }
        
        currentAppId = appId
        
        wsClient = FeishuWebSocketClient(
            appId = appId,
            appSecret = appSecret,
            apiClient = apiClient,
            onMessage = onInbound,
            onConnectionStatus = { connected ->
                onConnectionStatus(if (connected) BridgeStatus.CONNECTED else BridgeStatus.DISCONNECTED)
            }
        )
        wsClient?.start()
    }

    fun stop() {
        stopInternal(setStatus = true)
    }

    private fun stopInternal(setStatus: Boolean) {
        wsClient?.stop()
        wsClient = null
        wsJob?.cancel()
        wsJob = null
        currentAppId = null
        if (setStatus) {
            onConnectionStatus(BridgeStatus.STOPPED)
        }
    }

    fun isRunning(): Boolean = wsClient != null

    suspend fun sendText(session: RemoteSession, text: String) {
        withContext(Dispatchers.IO) {
            val parentMsgId = session.replyToken
            if (!parentMsgId.isNullOrBlank()) {
                wsClient?.replyMessage(parentMsgId, text)
            } else if (!session.sessionKey.isBlank()) {
                wsClient?.sendMessage(session.sessionKey, text)
            }
        }
    }

    suspend fun sendPhoto(session: RemoteSession, photoBytes: ByteArray, caption: String?, fileName: String) {
        withContext(Dispatchers.IO) {
            try {
                val token = apiClient.getTenantAccessToken(getAppId(), getAppSecret()).getOrNull() ?: return@withContext
                val chatId = session.sessionKey
                if (chatId.isBlank()) return@withContext
                
                val imageKey = apiClient.uploadImage(token, photoBytes, fileName).getOrNull() ?: run {
                    Log.e(TAG, "Failed to upload image")
                    sendText(session, "[截图上传失败]")
                    return@withContext
                }
                
                apiClient.sendImageMessage(token, chatId, "chat_id", imageKey)
                Log.d(TAG, "Image sent successfully")
            } catch (e: Exception) {
                Log.e(TAG, "sendPhoto failed: ${e.message}")
                sendText(session, "[截图发送失败: ${e.message}]")
            }
        }
    }

    suspend fun sendVideo(session: RemoteSession, videoBytes: ByteArray, caption: String?, fileName: String) {
        withContext(Dispatchers.IO) {
            sendText(session, "[视频已保存到本地] $fileName${caption?.let { " - $it" } ?: ""}")
        }
    }

    suspend fun sendAudio(session: RemoteSession, audioBytes: ByteArray, caption: String?, fileName: String) {
        withContext(Dispatchers.IO) {
            sendText(session, "[音频已保存到本地] $fileName${caption?.let { " - $it" } ?: ""}")
        }
    }

    suspend fun sendTyping(session: RemoteSession) {
    }

    companion object {
        private const val TAG = "FeishuBridge"
    }
}