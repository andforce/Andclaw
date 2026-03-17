package com.afwsamples.testdpc.policy.locktask

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.afwsamples.testdpc.databinding.ActivityAiSettingsBinding
import com.base.services.IAiConfigService
import kotlinx.coroutines.Dispatchers
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupProviderSpinner()
        loadCurrentConfig()

        binding.btnTestApi.setOnClickListener { testApiConnection() }
        binding.btnTestTg.setOnClickListener { testTelegram() }
        binding.btnSave.setOnClickListener { saveAndFinish() }
    }

    private fun setupProviderSpinner() {
        val providers = listOf("Kimi Code", "Moonshot", "OpenAI")
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, providers)
        binding.spinnerProvider.apply {
            setAdapter(adapter)
            setOnItemClickListener { _, _, position, _ ->
                when (providers[position]) {
                    "Kimi Code" -> {
                        binding.etBaseUrl.setText("https://api.kimi.com/coding")
                        binding.etModel.setText("kimi-k2.5")
                        binding.etApiKey.setText(aiConfigService.defaultApiKey)
                    }
                    "Moonshot" -> {
                        binding.etBaseUrl.setText("https://api.moonshot.cn/v1")
                        binding.etModel.setText("kimi-k2-turbo-preview")
                    }
                    "OpenAI" -> {
                        binding.etBaseUrl.setText("https://api.openai.com/v1/chat/completions")
                        binding.etModel.setText("gpt-4o")
                    }
                }
            }
        }
    }

    private fun loadCurrentConfig() {
        binding.spinnerProvider.setText(aiConfigService.provider, false)
        binding.etBaseUrl.setText(aiConfigService.apiUrl)
        binding.etApiKey.setText(aiConfigService.apiKey)
        binding.etModel.setText(aiConfigService.model)
        binding.etTgToken.setText(aiConfigService.tgToken)
        val savedChatId = aiConfigService.getTgChatId()
        binding.etTgChatId.setText(if (savedChatId == 0L) "" else savedChatId.toString())
    }

    private fun saveAndFinish() {
        aiConfigService.updateConfig(
            provider = binding.spinnerProvider.text.toString(),
            apiUrl = binding.etBaseUrl.text.toString(),
            apiKey = binding.etApiKey.text.toString(),
            model = binding.etModel.text.toString()
        )
        aiConfigService.setTgToken(binding.etTgToken.text.toString().trim())
        val chatId = binding.etTgChatId.text.toString().trim().toLongOrNull() ?: 0L
        aiConfigService.setTgChatId(chatId)
        finish()
    }

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
                put("temperature", 0.0)
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
                put("temperature", 0.0)
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
}
