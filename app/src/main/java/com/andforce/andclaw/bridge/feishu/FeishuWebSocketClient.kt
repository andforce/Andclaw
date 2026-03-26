package com.andforce.andclaw.bridge.feishu

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.base.services.FeishuInboundMessage
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

internal class FeishuWebSocketClient(
    private val appId: String,
    private val appSecret: String,
    private val apiClient: FeishuApiClient,
    private val onMessage: suspend (FeishuInboundMessage) -> Unit,
    private val onConnectionStatus: (Boolean) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    
    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    
    private var appAccessToken: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()
    private var tokenExpireAt: Long = 0L

    fun start() {
        scope.launch {
            connect()
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "Client stopped")
        webSocket = null
        onConnectionStatus(false)
    }

    private suspend fun connect() {
        val wsUrl = getWebSocketUrl()
        if (wsUrl == null) {
            Log.e(TAG, "Failed to get WebSocket URL")
            onConnectionStatus(false)
            scheduleReconnect()
            return
        }

        Log.d(TAG, "Connecting to WebSocket: $wsUrl")
        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                onConnectionStatus(true)
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Text message: $text")
                scope.launch {
                    handleMessage(text)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "Binary message received: ${bytes.hex()}...")
                scope.launch {
                    handleBinaryMessage(bytes)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code - $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code - $reason")
                onConnectionStatus(false)
                heartbeatJob?.cancel()
                if (code != 1000) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                onConnectionStatus(false)
                heartbeatJob?.cancel()
                scheduleReconnect()
            }
        })
    }

    private fun handleMessage(raw: String) {
        try {
            Log.d(TAG, "Raw message: $raw")
            val json = gson.fromJson(raw, JsonObject::class.java)
            
            val schema = json.get("schema")?.asString
            val header = json.getAsJsonObject("header")
            val event = json.getAsJsonObject("event")
            
            if (schema == "2.0" && header != null) {
                val eventType = header.get("event_type")?.asString
                Log.d(TAG, "Event type: $eventType")
                
                if (eventType == "im.message.receive_v1" && event != null) {
                    val message = event.getAsJsonObject("message")
                    if (message != null) {
                        val sender = event.getAsJsonObject("sender")
                        val senderType = sender?.get("sender_type")?.asString ?: ""
                        val senderId = sender?.get("id")?.asString ?: ""
                        
                        if (senderType == "user") {
                            val messageId = message.get("message_id")?.asString ?: ""
                            val chatId = message.get("chat_id")?.asString ?: ""
                            val chatType = message.get("chat_type")?.asString ?: ""
                            val createTime = message.get("create_time")?.asString?.toLongOrNull() ?: 0L
                            
                            Log.d(TAG, "Message from user: $senderId, chat: $chatType, msgId: $messageId")
                            
                            scope.launch {
                                val msgText = fetchMessageText(messageId)
                                val inbound = FeishuInboundMessage(
                                    eventId = header.get("event_id")?.asString ?: "",
                                    messageId = messageId,
                                    chatId = chatId,
                                    chatType = chatType,
                                    senderId = senderId,
                                    senderType = senderType,
                                    text = msgText,
                                    createTime = createTime,
                                    parentMessageId = null
                                )
                                onMessage(inbound)
                            }
                        }
                    }
                }
            } else if (header != null) {
                val eventType = header.get("event_type")?.asString
                if (eventType == "url_verification") {
                    val challenge = header.get("challenge")?.asString
                    val response = JsonObject().apply {
                        addProperty("challenge", challenge)
                    }
                    webSocket?.send(response.toString())
                    Log.d(TAG, "URL verification: $challenge")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle message: ${e.message}", e)
        }
    }

    private suspend fun handleBinaryMessage(data: ByteString) {
        try {
            val hex = data.hex()
            val jsonStartHex = "7b22736368656d61223a22322e3022" // {"schema":"2.0"
            val jsonStart = hex.indexOf(jsonStartHex)
            if (jsonStart < 0) {
                Log.d(TAG, "Binary: no JSON found")
                return
            }
            
            val bytePos = jsonStart / 2
            val jsonBytes = data.toByteArray().copyOfRange(bytePos, data.size)
            var jsonStr = String(jsonBytes, Charsets.UTF_8)
            
            // 找到完整 JSON
            var braceCount = 0
            var inString = false
            var escape = false
            val endBuilder = StringBuilder()
            
            for (c in jsonStr) {
                endBuilder.append(c)
                if (escape) {
                    escape = false
                    continue
                }
                when (c) {
                    '\\' -> escape = true
                    '"' -> inString = !inString
                    '{' -> if (!inString) braceCount++
                    '}' -> if (!inString) {
                        braceCount--
                        if (braceCount == 0) {
                            jsonStr = endBuilder.toString()
                            break
                        }
                    }
                }
            }
            
            // 解析外层 JSON
            val outerJson = gson.fromJson(jsonStr, JsonObject::class.java)
            val event = outerJson.getAsJsonObject("event") ?: return
            val message = event.getAsJsonObject("message") ?: return
            
            val content = message.get("content")?.asString ?: return
            val chatId = message.get("chat_id")?.asString ?: return
            val chatType = message.get("chat_type")?.asString ?: return
            val messageId = message.get("message_id")?.asString ?: return
            val sender = event.getAsJsonObject("sender")
            val senderId = sender?.getAsJsonObject("sender_id")?.get("open_id")?.asString ?: ""
            val senderType = sender?.get("sender_type")?.asString ?: ""
            
            // 解析内层 content JSON
            val innerContent = gson.fromJson(content, JsonObject::class.java)
            val text = innerContent.get("text")?.asString ?: ""
            
            Log.d(TAG, "Feishu msg: text=$text, chatId=$chatId")
            
            val inbound = FeishuInboundMessage(
                eventId = outerJson.getAsJsonObject("header")?.get("event_id")?.asString ?: "",
                messageId = messageId,
                chatId = chatId,
                chatType = chatType,
                senderId = senderId,
                senderType = senderType,
                text = text,
                createTime = 0L,
                parentMessageId = null
            )
            
            onMessage(inbound)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle binary: ${e.message}", e)
        }
    }

    private fun parseMessageEvent(payload: JsonObject): FeishuInboundMessage? {
        return try {
            FeishuInboundMessage(
                eventId = payload.get("event_id")?.asString ?: "",
                messageId = payload.get("message_id")?.asString ?: "",
                chatId = payload.get("chat_id")?.asString ?: "",
                chatType = payload.get("chat_type")?.asString ?: "",
                senderId = payload.getAsJsonObject("sender")?.get("id")?.asString ?: "",
                senderType = payload.getAsJsonObject("sender")?.get("sender_type")?.asString ?: "",
                text = "",
                createTime = payload.get("create_time")?.asLong ?: 0L,
                parentMessageId = payload.get("parent_id")?.asString
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message event: ${e.message}")
            null
        }
    }

    private suspend fun fetchMessageText(messageId: String): String {
        val token = getTenantAccessToken() ?: return ""
        val result = apiClient.getTextContent(token, messageId)
        return result.getOrNull() ?: ""
    }

    private suspend fun getTenantAccessToken(): String? {
        val now = System.currentTimeMillis()
        if (appAccessToken != null && now < tokenExpireAt - 60000) {
            return appAccessToken
        }
        val result = apiClient.getTenantAccessToken(appId, appSecret)
        return result.getOrNull()?.also { token ->
            appAccessToken = token
            tokenExpireAt = now + 7200000
        }
    }

    private suspend fun getWebSocketUrl(): String? {
        return apiClient.getWebSocketEndpoint(appId, appSecret).getOrNull()
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(30000)
                val ping = JsonObject().apply {
                    addProperty("type", "ping")
                }
                webSocket?.send(ping.toString())
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(5000)
            Log.d(TAG, "Attempting to reconnect...")
            connect()
        }
    }

    suspend fun sendMessage(chatId: String, text: String) {
        val token = getTenantAccessToken() ?: return
        val content = JsonObject().apply {
            addProperty("text", text)
        }
        apiClient.sendMessage(token, chatId, "chat_id", "text", content.toString())
    }

    suspend fun replyMessage(messageId: String, text: String) {
        val token = getTenantAccessToken() ?: return
        val content = JsonObject().apply {
            addProperty("text", text)
        }
        apiClient.replyMessage(token, messageId, "text", content.toString())
    }

    companion object {
        private const val TAG = "FeishuWebSocket"
    }
}