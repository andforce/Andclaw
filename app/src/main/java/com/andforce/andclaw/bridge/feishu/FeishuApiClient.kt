package com.andforce.andclaw.bridge.feishu

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

internal class FeishuApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    private val gson = Gson()

    suspend fun getWebSocketEndpoint(appId: String, appSecret: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JsonObject().apply {
                addProperty("AppID", appId)
                addProperty("AppSecret", appSecret)
            }
            val request = Request.Builder()
                .url("$WS_BASE_URL/callback/ws/endpoint")
                .header("Locale", "zh")
                .post(body.toString().toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw Exception("getWebSocketEndpoint failed: ${resp.code}")
                }
                val json = gson.fromJson(resp.body?.string(), JsonObject::class.java)
                val code = json.get("code")?.asInt ?: -1
                if (code != 0) {
                    throw Exception("getWebSocketEndpoint error: $code - ${json.get("msg")?.asString}")
                }
                json.getAsJsonObject("data")
                    ?.get("URL")
                    ?.asString
                    ?: throw Exception("WebSocket URL not found in response")
            }
        }
    }

    suspend fun getTenantAccessToken(appId: String, appSecret: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JsonObject().apply {
                addProperty("app_id", appId)
                addProperty("app_secret", appSecret)
            }
            val request = Request.Builder()
                .url("$BASE_URL/auth/v3/tenant_access_token/internal")
                .post(body.toString().toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw Exception("getTenantAccessToken failed: ${resp.code}")
                }
                val json = gson.fromJson(resp.body?.string(), JsonObject::class.java)
                val code = json.get("code")?.asInt ?: -1
                if (code != 0) {
                    throw Exception("getTenantAccessToken error: $code - ${json.get("msg")?.asString}")
                }
                json.get("tenant_access_token")?.asString
                    ?: throw Exception("tenant_access_token not found")
            }
        }
    }

    suspend fun sendMessage(
        accessToken: String,
        receiveId: String,
        receiveIdType: String,
        msgType: String,
        content: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JsonObject().apply {
                addProperty("receive_id", receiveId)
                addProperty("receive_id_type", receiveIdType)
                addProperty("msg_type", msgType)
                addProperty("content", content)
            }
            val request = Request.Builder()
                .url("$BASE_URL/im/v1/messages")
                .header("Authorization", "Bearer $accessToken")
                .post(body.toString().toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw Exception("sendMessage failed: ${resp.code}")
                }
                val json = gson.fromJson(resp.body?.string(), JsonObject::class.java)
                val code = json.get("code")?.asInt ?: -1
                if (code != 0) {
                    throw Exception("sendMessage error: $code - ${json.get("msg")?.asString}")
                }
                json.getAsJsonObject("data")?.get("message_id")?.asString ?: ""
            }
        }
    }

    suspend fun replyMessage(
        accessToken: String,
        messageId: String,
        msgType: String,
        content: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JsonObject().apply {
                addProperty("msg_type", msgType)
                addProperty("content", content)
            }
            val request = Request.Builder()
                .url("$BASE_URL/im/v1/messages/$messageId/reply")
                .header("Authorization", "Bearer $accessToken")
                .post(body.toString().toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw Exception("replyMessage failed: ${resp.code}")
                }
                val json = gson.fromJson(resp.body?.string(), JsonObject::class.java)
                val code = json.get("code")?.asInt ?: -1
                if (code != 0) {
                    throw Exception("replyMessage error: $code - ${json.get("msg")?.asString}")
                }
                json.getAsJsonObject("data")?.get("message_id")?.asString ?: ""
            }
        }
    }

    suspend fun getTextContent(accessToken: String, messageId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$BASE_URL/im/v1/messages/$messageId?user_id_type=open_id")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw Exception("getTextContent failed: ${resp.code}")
                }
                val json = gson.fromJson(resp.body?.string(), JsonObject::class.java)
                val code = json.get("code")?.asInt ?: -1
                if (code != 0) {
                    throw Exception("getTextContent error: $code")
                }
                val items = json.getAsJsonObject("data")
                    ?.getAsJsonArray("items")
                val content = items?.get(0)?.asJsonObject
                    ?.get("body")?.asString
                if (content != null) {
                    val contentJson = gson.fromJson(content, JsonObject::class.java)
                    contentJson.get("text")?.asString ?: content
                } else {
                    ""
                }
            }
        }
    }

    suspend fun uploadImage(
        accessToken: String,
        imageBytes: ByteArray,
        imageName: String = "screenshot.png"
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val requestBody = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart(
                    "image_type", "message",
                    okhttp3.RequestBody.create("image/png".toMediaType(), imageBytes)
                )
                .build()
            
            val request = Request.Builder()
                .url("$BASE_URL/im/v1/images")
                .header("Authorization", "Bearer $accessToken")
                .post(requestBody)
                .build()
            
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw Exception("uploadImage failed: ${resp.code}")
                }
                val json = gson.fromJson(resp.body?.string(), JsonObject::class.java)
                val code = json.get("code")?.asInt ?: -1
                if (code != 0) {
                    throw Exception("uploadImage error: $code - ${json.get("msg")?.asString}")
                }
                json.getAsJsonObject("data")
                    ?.get("image_key")
                    ?.asString
                    ?: throw Exception("image_key not found")
            }
        }
    }

    suspend fun sendImageMessage(
        accessToken: String,
        receiveId: String,
        receiveIdType: String,
        imageKey: String
    ): Result<String> {
        val content = JsonObject().apply {
            addProperty("image_key", imageKey)
        }.toString()
        return sendMessage(accessToken, receiveId, receiveIdType, "image", content)
    }

    companion object {
        const val BASE_URL = "https://open.feishu.cn/open-apis"
        const val WS_BASE_URL = "https://open.feishu.cn"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}