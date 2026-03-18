package com.andforce.andclaw

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class TgMessage(
    val updateId: Long,
    val chatId: Long,
    val messageId: Long,
    val text: String
)

class TgBotClient(private val token: String) {

    private val base = "https://api.telegram.org/bot$token"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private var offset: Long = 0

    suspend fun poll(timeoutSec: Int = 30): List<TgMessage> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$base/getUpdates?timeout=$timeoutSec&offset=$offset&allowed_updates=message")
            .get()
            .build()

        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val root = JSONObject(resp.body.string())
                if (!root.optBoolean("ok")) return@withContext emptyList()

                val result = root.optJSONArray("result") ?: return@withContext emptyList()
                val out = mutableListOf<TgMessage>()

                for (i in 0 until result.length()) {
                    val update = result.getJSONObject(i)
                    val uid = update.getLong("update_id")
                    if (uid >= offset) offset = uid + 1

                    val msg = update.optJSONObject("message") ?: continue
                    val text = msg.optString("text", "").trim()
                    if (text.isBlank()) continue

                    val chat = msg.optJSONObject("chat") ?: continue
                    val chatId = chat.getLong("id")
                    out += TgMessage(uid, chatId, msg.getLong("message_id"), text)
                }
                out
            }
        }.getOrElse { emptyList() }
    }

    suspend fun setMyCommands(commands: List<Pair<String, String>>) = withContext(Dispatchers.IO) {
        val cmds = JSONArray()
        for ((cmd, desc) in commands) {
            cmds.put(JSONObject().apply {
                put("command", cmd)
                put("description", desc)
            })
        }
        val payload = JSONObject().apply { put("commands", cmds) }
        val req = Request.Builder()
            .url("$base/setMyCommands")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        runCatching { client.newCall(req).execute().use { } }
    }

    suspend fun sendTyping(chatId: Long) = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("chat_id", chatId)
            put("action", "typing")
        }
        val req = Request.Builder()
            .url("$base/sendChatAction")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        runCatching { client.newCall(req).execute().use { } }
    }

    suspend fun send(chatId: Long, text: String, replyTo: Long? = null) = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("chat_id", chatId)
            put("text", text.take(4096))
            if (replyTo != null) put("reply_to_message_id", replyTo)
        }
        val req = Request.Builder()
            .url("$base/sendMessage")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        runCatching { client.newCall(req).execute().use { } }
    }

    suspend fun sendPhoto(
        chatId: Long,
        photoBytes: ByteArray,
        caption: String? = null,
        fileName: String = "photo.png"
    ) = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId.toString())
            .addFormDataPart(
                "photo", fileName,
                photoBytes.toRequestBody("image/png".toMediaType())
            )
            .apply { if (!caption.isNullOrEmpty()) addFormDataPart("caption", caption) }
            .build()

        val req = Request.Builder()
            .url("$base/sendPhoto")
            .post(body)
            .build()

        runCatching { client.newCall(req).execute().use { } }
    }

    suspend fun sendVideo(
        chatId: Long,
        videoBytes: ByteArray,
        caption: String? = null,
        fileName: String = "video.mp4"
    ) = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId.toString())
            .addFormDataPart(
                "video", fileName,
                videoBytes.toRequestBody("video/mp4".toMediaType())
            )
            .apply { if (!caption.isNullOrEmpty()) addFormDataPart("caption", caption) }
            .build()

        val req = Request.Builder()
            .url("$base/sendVideo")
            .post(body)
            .build()

        runCatching { client.newCall(req).execute().use { } }
    }

    suspend fun sendAudio(
        chatId: Long,
        audioBytes: ByteArray,
        caption: String? = null,
        fileName: String = "audio.m4a"
    ) = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId.toString())
            .addFormDataPart(
                "audio", fileName,
                audioBytes.toRequestBody("audio/mp4".toMediaType())
            )
            .apply { if (!caption.isNullOrEmpty()) addFormDataPart("caption", caption) }
            .build()

        val req = Request.Builder()
            .url("$base/sendAudio")
            .post(body)
            .build()

        runCatching { client.newCall(req).execute().use { } }
    }
}
