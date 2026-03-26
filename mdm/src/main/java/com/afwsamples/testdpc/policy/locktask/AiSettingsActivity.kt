package com.afwsamples.testdpc.policy.locktask

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.afwsamples.testdpc.databinding.ActivityAiSettingsBinding
import com.base.services.BridgeStatus
import com.base.services.ClawBotLoginStatus
import com.base.services.ClawBotQrPollPhase
import com.base.services.IAiConfigService
import com.base.services.IRemoteBridgeService
import com.base.services.IRemoteChannelConfigService
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.koin.android.ext.android.inject
import java.net.HttpURLConnection
import java.net.URL

class AiSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAiSettingsBinding
    private val aiConfigService: IAiConfigService by inject()
    private val channelConfig: IRemoteChannelConfigService by inject()
    private val remoteBridge: IRemoteBridgeService by inject()
    private var currentProvider = ""

    private var clawBotLoginJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupProviderSpinner()
        loadCurrentConfig()
        observeClawBotStatusLine()

        binding.btnFetchModels.setOnClickListener { fetchModels() }
        binding.btnTestApi.setOnClickListener { testApiConnection() }
        binding.btnTestTg.setOnClickListener { testTelegram() }

        binding.btnClawBotLogin.setOnClickListener { startClawBotQrLogin() }
        binding.btnClawBotClearAuth.setOnClickListener {
            cancelClawBotLogin()
            channelConfig.clearClawBotAuthState()
            remoteBridge.startClawBotBridgeIfConfigured(forceRelogin = false)
            hideQrCode()
            showClawBotResult("已清除 ClawBot 登录状态", isError = false)
        }

        binding.btnTestFeishu.setOnClickListener { testFeishu() }

        binding.btnSave.setOnClickListener { saveAndFinish() }
    }

    override fun onDestroy() {
        cancelClawBotLogin()
        super.onDestroy()
    }

    // region ClawBot QR Login

    private fun startClawBotQrLogin() {
        cancelClawBotLogin()
        binding.btnClawBotLogin.isEnabled = false
        binding.btnClawBotLogin.text = "正在获取二维码…"
        showClawBotResult("正在获取二维码…", isError = false)
        hideQrCode()

        clawBotLoginJob = lifecycleScope.launch {
            try {
                runClawBotLoginFlow()
            } catch (e: Exception) {
                if (isActive) {
                    showClawBotResult("登录失败: ${e.message}", isError = true)
                }
            } finally {
                binding.btnClawBotLogin.isEnabled = true
                binding.btnClawBotLogin.text = "扫码登录"
            }
        }
    }

    private suspend fun runClawBotLoginFlow() {
        var qrRefreshCount = 0
        val maxQrRefresh = 3

        while (qrRefreshCount < maxQrRefresh) {
            val qrResult = remoteBridge.requestClawBotQrCode()

            val qrBitmap = withContext(Dispatchers.Default) {
                generateQrBitmap(qrResult.qrcodeImgContent, 600)
            }

            withContext(Dispatchers.Main) {
                binding.ivClawBotQr.setImageBitmap(qrBitmap)
                binding.ivClawBotQr.visibility = View.VISIBLE
                binding.tvClawBotQrHint.visibility = View.VISIBLE
                binding.tvClawBotQrHint.text = "请打开微信「ClawBot 插件」扫描上方二维码"
                showClawBotResult("等待扫码…", isError = false)
                binding.btnClawBotLogin.text = "取消登录"
                binding.btnClawBotLogin.isEnabled = true
                binding.btnClawBotLogin.setOnClickListener {
                    cancelClawBotLogin()
                    hideQrCode()
                    showClawBotResult("已取消登录", isError = false)
                    binding.btnClawBotLogin.text = "扫码登录"
                    binding.btnClawBotLogin.setOnClickListener { startClawBotQrLogin() }
                }
            }

            val deadline = System.currentTimeMillis() + 5 * 60 * 1000L

            while (System.currentTimeMillis() < deadline) {
                delay(1500)
                val pollResult = remoteBridge.pollClawBotQrCodeStatus(qrResult.qrcode)

                when (pollResult.phase) {
                    ClawBotQrPollPhase.WAIT -> { /* keep polling */ }
                    ClawBotQrPollPhase.SCANED -> {
                        withContext(Dispatchers.Main) {
                            binding.tvClawBotQrHint.text = "已扫码，请在微信上确认…"
                            showClawBotResult("已扫码，等待确认…", isError = false)
                        }
                    }
                    ClawBotQrPollPhase.CONFIRMED -> {
                        withContext(Dispatchers.Main) {
                            hideQrCode()
                            if (pollResult.authState != null) {
                                showClawBotResult("登录成功 ✓", isError = false)
                                remoteBridge.startClawBotBridgeIfConfigured(forceRelogin = false)
                            } else {
                                showClawBotResult("登录确认但凭据不完整，请重试", isError = true)
                            }
                        }
                        return
                    }
                    ClawBotQrPollPhase.EXPIRED -> {
                        qrRefreshCount++
                        withContext(Dispatchers.Main) {
                            if (qrRefreshCount < maxQrRefresh) {
                                showClawBotResult("二维码已过期，正在刷新…（第 ${qrRefreshCount}/$maxQrRefresh 次）", isError = false)
                            }
                        }
                        break
                    }
                    ClawBotQrPollPhase.UNKNOWN -> {
                        withContext(Dispatchers.Main) {
                            hideQrCode()
                            showClawBotResult("收到未知状态，请重试", isError = true)
                        }
                        return
                    }
                }
            }

            if (System.currentTimeMillis() >= deadline) {
                qrRefreshCount++
                withContext(Dispatchers.Main) {
                    if (qrRefreshCount >= maxQrRefresh) {
                        hideQrCode()
                        showClawBotResult("登录超时，请重新扫码", isError = true)
                    } else {
                        showClawBotResult("等待超时，正在刷新二维码…", isError = false)
                    }
                }
            }
        }

        if (qrRefreshCount >= maxQrRefresh) {
            withContext(Dispatchers.Main) {
                hideQrCode()
                showClawBotResult("二维码多次过期，请稍后重试", isError = true)
            }
        }
    }

    private fun cancelClawBotLogin() {
        clawBotLoginJob?.cancel()
        clawBotLoginJob = null
    }

    private fun hideQrCode() {
        binding.ivClawBotQr.visibility = View.GONE
        binding.ivClawBotQr.setImageBitmap(null)
        binding.tvClawBotQrHint.visibility = View.GONE
    }

    private fun generateQrBitmap(content: String, size: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
        )
        val bitMatrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    // endregion

    // region ClawBot Status

    private fun observeClawBotStatusLine() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    remoteBridge.clawBotStatus,
                    remoteBridge.clawBotLoginStatus
                ) { bridge, login -> bridge to login }.collect { (bridge, login) ->
                    binding.tvClawBotStatus.text = formatClawBotStatusLine(bridge, login)
                    updateClawBotLoginButtonText(login)
                }
            }
        }
    }

    private fun formatClawBotStatusLine(bridge: BridgeStatus, login: ClawBotLoginStatus): String {
        val b = when (bridge) {
            BridgeStatus.NOT_CONFIGURED -> "未配置"
            BridgeStatus.STOPPED -> "已停止"
            BridgeStatus.CONNECTED -> "已连接"
            BridgeStatus.DISCONNECTED -> "未连接"
        }
        val l = when (login) {
            ClawBotLoginStatus.NOT_CONFIGURED -> "未配置"
            ClawBotLoginStatus.LOGIN_REQUIRED -> "需登录"
            ClawBotLoginStatus.QR_READY -> "二维码就绪"
            ClawBotLoginStatus.WAITING_CONFIRM -> "待确认"
            ClawBotLoginStatus.CONNECTED -> "已登录"
            ClawBotLoginStatus.DISCONNECTED -> "已断开"
            ClawBotLoginStatus.STOPPED -> "已停止"
        }
        return "桥接: $b · 登录: $l"
    }

    private fun updateClawBotLoginButtonText(login: ClawBotLoginStatus) {
        if (clawBotLoginJob?.isActive == true) return
        binding.btnClawBotLogin.text = when (login) {
            ClawBotLoginStatus.CONNECTED -> "重新登录"
            else -> "扫码登录"
        }
    }

    private fun showClawBotResult(text: String, isError: Boolean) {
        binding.tvClawBotResult.apply {
            visibility = View.VISIBLE
            this.text = text
            setTextColor(getColor(if (isError) android.R.color.holo_red_dark else android.R.color.holo_green_dark))
        }
    }

    // endregion

    // region AI Provider Config

    private fun setupProviderSpinner() {
        val providers = listOf("Kimi Code", "Moonshot", "OpenAI")
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, providers)
        binding.spinnerProvider.apply {
            setAdapter(adapter)
            setOnItemClickListener { _, _, position, _ ->
                aiConfigService.saveProviderKey(currentProvider, binding.etApiKey.text.toString().trim())
                val selected = providers[position]
                currentProvider = selected
                val savedKey = aiConfigService.loadProviderKey(selected)
                when (selected) {
                    "Kimi Code" -> {
                        binding.etBaseUrl.setText("https://api.kimi.com/coding")
                        binding.etModel.setText("kimi-k2.5", false)
                        binding.etApiKey.setText(savedKey.ifEmpty { aiConfigService.defaultApiKey })
                    }
                    "Moonshot" -> {
                        binding.etBaseUrl.setText("https://api.moonshot.cn/v1")
                        binding.etModel.setText("kimi-k2-turbo-preview", false)
                        binding.etApiKey.setText(savedKey)
                    }
                    "OpenAI" -> {
                        binding.etBaseUrl.setText("https://api.openai.com/v1/chat/completions")
                        binding.etModel.setText("gpt-4o", false)
                        binding.etApiKey.setText(savedKey)
                    }
                }
            }
        }
    }

    private fun loadCurrentConfig() {
        currentProvider = aiConfigService.provider
        binding.spinnerProvider.setText(currentProvider, false)
        binding.etBaseUrl.setText(aiConfigService.apiUrl)
        val savedKey = aiConfigService.loadProviderKey(currentProvider)
        binding.etApiKey.setText(savedKey.ifEmpty { aiConfigService.apiKey })
        binding.etModel.setText(aiConfigService.model, false)
        binding.etTgToken.setText(channelConfig.tgToken)
        val savedChatId = channelConfig.getTgChatId()
        binding.etTgChatId.setText(if (savedChatId == 0L) "" else savedChatId.toString())
        binding.etFeishuAppId.setText(channelConfig.getFeishuAppId())
        binding.etFeishuAppSecret.setText(channelConfig.getFeishuAppSecret())
        observeFeishuStatusLine()
    }

    private fun saveAndFinish() {
        val provider = binding.spinnerProvider.text.toString()
        val apiKey = binding.etApiKey.text.toString().trim()
        aiConfigService.saveProviderKey(provider, apiKey)
        aiConfigService.updateConfig(
            provider = provider,
            apiUrl = binding.etBaseUrl.text.toString(),
            apiKey = apiKey,
            model = binding.etModel.text.toString()
        )
        channelConfig.setTgToken(binding.etTgToken.text.toString().trim())
        val chatId = binding.etTgChatId.text.toString().trim().toLongOrNull() ?: 0L
        channelConfig.setTgChatId(chatId)
        channelConfig.setFeishuAppId(binding.etFeishuAppId.text.toString().trim())
        channelConfig.setFeishuAppSecret(binding.etFeishuAppSecret.text.toString().trim())
        finish()
    }

    // endregion

    // region 获取模型列表

    private fun fetchModels() {
        val provider = binding.spinnerProvider.text.toString()
        val baseUrl = binding.etBaseUrl.text.toString().trim()
        val apiKey = binding.etApiKey.text.toString().trim()

        if (baseUrl.isEmpty() || apiKey.isEmpty()) {
            showModelListResult("请先填写 Base URL 和 API Key", isError = true)
            return
        }

        binding.btnFetchModels.isEnabled = false
        showModelListResult("正在获取模型列表...", isError = false)

        lifecycleScope.launch {
            val result = queryModels(provider, baseUrl, apiKey)
            binding.btnFetchModels.isEnabled = true
            if (result.second != null) {
                showModelListResult(result.second!!, isError = true)
            } else {
                val models = result.first
                val adapter = ArrayAdapter(
                    this@AiSettingsActivity,
                    android.R.layout.simple_dropdown_item_1line,
                    models
                )
                binding.etModel.setAdapter(adapter)
                binding.etModel.showDropDown()
                showModelListResult("获取到 ${models.size} 个可用模型", isError = false)
            }
        }
    }

    private suspend fun queryModels(
        provider: String,
        baseUrl: String,
        apiKey: String
    ): Pair<List<String>, String?> = withContext(Dispatchers.IO) {
        try {
            val isKimiCode = provider.equals("Kimi Code", ignoreCase = true)
            val url = if (isKimiCode) {
                "${baseUrl.removeSuffix("/")}/v1/models"
            } else {
                "${baseUrl.removeSuffix("/").removeSuffix("/chat/completions")}/models"
            }
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                if (isKimiCode) {
                    setRequestProperty("x-api-key", apiKey)
                    setRequestProperty("anthropic-version", "2023-06-01")
                } else {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
                connectTimeout = 15000
                readTimeout = 15000
            }
            val code = conn.responseCode
            val respBody = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            conn.disconnect()

            if (code in 200..299) {
                val dataArr = JSONObject(respBody).getJSONArray("data")
                val models = (0 until dataArr.length())
                    .map { dataArr.getJSONObject(it).getString("id") }
                    .sorted()
                models to null
            } else {
                emptyList<String>() to "获取失败 (HTTP $code)\n$respBody"
            }
        } catch (e: Exception) {
            emptyList<String>() to "获取失败: ${e.message}"
        }
    }

    private fun showModelListResult(text: String, isError: Boolean) {
        binding.tvModelListResult.apply {
            visibility = View.VISIBLE
            this.text = text
            setTextColor(getColor(if (isError) android.R.color.holo_red_dark else android.R.color.holo_green_dark))
        }
    }

    // endregion

    // region API 测试

    private fun testApiConnection() {
        val provider = binding.spinnerProvider.text.toString()
        val baseUrl = binding.etBaseUrl.text.toString().trim()
        val apiKey = binding.etApiKey.text.toString().trim()
        val model = binding.etModel.text.toString().trim()

        if (baseUrl.isEmpty() || apiKey.isEmpty() || model.isEmpty()) {
            showApiResult("请填写完整的 API 配置", isError = true)
            return
        }

        binding.btnTestApi.isEnabled = false
        showApiResult("正在测试连接...", isError = false)

        lifecycleScope.launch {
            val isKimiCode = provider.equals("Kimi Code", ignoreCase = true)
            val result = if (isKimiCode) {
                testKimiCodeApi(baseUrl, apiKey, model)
            } else {
                testOpenAiCompatibleApi(baseUrl, apiKey, model)
            }
            binding.btnTestApi.isEnabled = true
            showApiResult(result.first, result.second)
        }
    }

    private suspend fun testKimiCodeApi(
        baseUrl: String,
        apiKey: String,
        model: String
    ): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = "${baseUrl.removeSuffix("/")}/v1/messages"
            val body = JSONObject().apply {
                put("model", model)
                put("max_tokens", 64)
                if (!model.contains("k2.5")) {
                    put("temperature", 0.0)
                }
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Say 'OK' if you can hear me.")
                    })
                })
            }
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("anthropic-version", "2023-06-01")
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val respBody = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            conn.disconnect()

            if (code in 200..299) {
                val text = JSONObject(respBody).getJSONArray("content")
                    .let { arr ->
                        (0 until arr.length())
                            .map { arr.getJSONObject(it) }
                            .firstOrNull { it.getString("type") == "text" }
                            ?.getString("text") ?: ""
                    }
                "连接成功 ✓\n模型回复: ${text.take(100)}" to false
            } else {
                "连接失败 (HTTP $code)\n$respBody" to true
            }
        } catch (e: Exception) {
            "连接失败: ${e.message}" to true
        }
    }

    private suspend fun testOpenAiCompatibleApi(
        baseUrl: String,
        apiKey: String,
        model: String
    ): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = if (baseUrl.contains("chat/completions")) baseUrl
            else "${baseUrl.removeSuffix("/")}/chat/completions"

            val body = JSONObject().apply {
                put("model", model)
                put("max_tokens", 64)
                if (!model.contains("k2.5")) {
                    put("temperature", 0.0)
                }
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Say 'OK' if you can hear me.")
                    })
                })
            }
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val respBody = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            conn.disconnect()

            if (code in 200..299) {
                val text = JSONObject(respBody)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                "连接成功 ✓\n模型回复: ${text.take(100)}" to false
            } else {
                "连接失败 (HTTP $code)\n$respBody" to true
            }
        } catch (e: Exception) {
            "连接失败: ${e.message}" to true
        }
    }

    private fun showApiResult(text: String, isError: Boolean) {
        binding.tvApiTestResult.apply {
            visibility = View.VISIBLE
            this.text = text
            setTextColor(getColor(if (isError) android.R.color.holo_red_dark else android.R.color.holo_green_dark))
        }
    }

    // endregion

    // region Telegram 测试

    private fun testTelegram() {
        val token = binding.etTgToken.text.toString().trim()
        if (token.isEmpty()) {
            showTgResult("请填写 Telegram Bot Token", isError = true)
            return
        }

        binding.btnTestTg.isEnabled = false
        showTgResult("正在测试连接...", isError = false)

        lifecycleScope.launch {
            val result = testTgGetMe(token)
            binding.btnTestTg.isEnabled = true
            showTgResult(result.first, result.second)
        }
    }

    private suspend fun testTgGetMe(token: String): Pair<String, Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://api.telegram.org/bot$token/getMe"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                }
                val code = conn.responseCode
                val respBody = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText() ?: ""
                conn.disconnect()

                if (code in 200..299) {
                    val json = JSONObject(respBody)
                    if (json.optBoolean("ok")) {
                        val bot = json.getJSONObject("result")
                        val name = bot.optString("first_name", "")
                        val username = bot.optString("username", "")
                        "连接成功 ✓\nBot: $name (@$username)" to false
                    } else {
                        "Token 无效: ${json.optString("description")}" to true
                    }
                } else {
                    "连接失败 (HTTP $code)\n$respBody" to true
                }
            } catch (e: Exception) {
                "连接失败: ${e.message}" to true
            }
        }

    private fun showTgResult(text: String, isError: Boolean) {
        binding.tvTgTestResult.apply {
            visibility = View.VISIBLE
            this.text = text
            setTextColor(getColor(if (isError) android.R.color.holo_red_dark else android.R.color.holo_green_dark))
        }
    }

    // endregion

    // region Feishu Status

    private fun observeFeishuStatusLine() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                remoteBridge.feishuStatus.collect { status ->
                    binding.tvFeishuStatus.text = formatFeishuStatusLine(status)
                }
            }
        }
    }

    private fun formatFeishuStatusLine(status: BridgeStatus): String {
        return "状态: " + when (status) {
            BridgeStatus.NOT_CONFIGURED -> "未配置"
            BridgeStatus.STOPPED -> "已停止"
            BridgeStatus.CONNECTED -> "已连接 ✓"
            BridgeStatus.DISCONNECTED -> "未连接"
        }
    }

    // endregion

    // region Feishu Test

    private fun testFeishu() {
        val appId = binding.etFeishuAppId.text.toString().trim()
        val appSecret = binding.etFeishuAppSecret.text.toString().trim()

        if (appId.isEmpty() || appSecret.isEmpty()) {
            showFeishuResult("请填写 App ID 和 App Secret", isError = true)
            return
        }

        binding.btnTestFeishu.isEnabled = false
        showFeishuResult("正在测试连接...", isError = false)

        lifecycleScope.launch {
            val result = testFeishuToken(appId, appSecret)
            binding.btnTestFeishu.isEnabled = true
            showFeishuResult(result.first, result.second)
        }
    }

    private suspend fun testFeishuToken(appId: String, appSecret: String): Pair<String, Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"
                val body = JSONObject().apply {
                    put("app_id", appId)
                    put("app_secret", appSecret)
                }
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 15000
                    readTimeout = 15000
                    doOutput = true
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }

                val code = conn.responseCode
                val respBody = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText() ?: ""
                conn.disconnect()

                if (code in 200..299) {
                    val json = JSONObject(respBody)
                    val code = json.optInt("code", -1)
                    if (code == 0) {
                        "连接成功 ✓" to false
                    } else {
                        "App ID/Secret 无效: ${json.optString("msg")}" to true
                    }
                } else {
                    "连接失败 (HTTP $code)\n$respBody" to true
                }
            } catch (e: Exception) {
                "连接失败: ${e.message}" to true
            }
        }

    private fun showFeishuResult(text: String, isError: Boolean) {
        binding.tvFeishuTestResult.apply {
            visibility = View.VISIBLE
            this.text = text
            setTextColor(getColor(if (isError) android.R.color.holo_red_dark else android.R.color.holo_green_dark))
        }
    }

    // endregion
}
