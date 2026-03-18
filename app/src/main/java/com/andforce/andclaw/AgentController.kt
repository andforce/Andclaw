package com.andforce.andclaw

import android.accessibilityservice.AccessibilityService
import android.app.DownloadManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import com.andforce.andclaw.model.AgentUiState
import com.andforce.andclaw.model.AiAction
import com.andforce.andclaw.model.ApiConfig
import com.andforce.andclaw.model.ChatMessage
import com.afwsamples.testdpc.common.Util
import com.andforce.andclaw.db.ChatMessageDao
import com.andforce.andclaw.db.ChatMessageEntity
import com.google.gson.Gson
import com.base.services.IAiConfigService
import com.base.services.ITgBridgeService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object AgentController : ITgBridgeService, IAiConfigService {

    private const val TAG = "AgentController"
    private const val PREFS_NAME = "agent_config"

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val gson = Gson()

    private var tgJob: Job? = null
    private var tgBotClient: TgBotClient? = null
    var tgActiveChatId: Long = 0L
        private set

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    var config = ApiConfig(apiKey = BuildConfig.KIMI_KEY)
        private set
    var isAgentRunning = false
        private set
    private var agentJob: Job? = null
    private var consecutiveSameCount = 0
    private var lastFingerprint = ""
    private var loopRetryCount = 0
    private var uiState = AgentUiState()

    private val dpmBridge by lazy { DpmBridge(appContext) }
    private lateinit var chatDao: ChatMessageDao

    fun init(context: Context, dao: ChatMessageDao) {
        appContext = context.applicationContext
        chatDao = dao
        migrateOldProviderKeys()
        restoreConfig()
        loadHistory()
    }

    private fun loadHistory() {
        scope.launch(Dispatchers.IO) {
            val entities = chatDao.getAll()
            val msgs = entities.map { e ->
                val action = e.actionJson?.let {
                    try { gson.fromJson(it, AiAction::class.java) } catch (_: Exception) { null }
                }
                ChatMessage(role = e.role, content = e.content, action = action, timestamp = e.timestamp, id = e.id)
            }
            _messages.value = msgs
        }
    }

    private fun migrateOldProviderKeys() {
        val oldPrefs = appContext.getSharedPreferences("ai_provider_keys", Context.MODE_PRIVATE)
        val allEntries = oldPrefs.all
        if (allEntries.isEmpty()) return

        val prefs = getPrefs()
        val editor = prefs.edit()
        for ((key, value) in allEntries) {
            if (!prefs.contains(key)) {
                editor.putString(key, value as? String ?: "")
            }
        }
        editor.apply()
        oldPrefs.edit().clear().apply()
        Log.d(TAG, "migrateOldProviderKeys: migrated ${allEntries.size} entries")
    }

    private fun restoreConfig() {
        val prefs = getPrefs()
        val savedProvider = prefs.getString("ai_provider", null)
        val apiKey = if (savedProvider != null) {
            prefs.getString("ai_api_key", null)
                ?: loadProviderKey(savedProvider)
        } else {
            loadProviderKey("Kimi Code").ifEmpty { config.apiKey }
        }
        config = ApiConfig(
            provider = savedProvider ?: config.provider,
            apiKey = apiKey,
            apiUrl = prefs.getString("ai_api_url", config.apiUrl) ?: config.apiUrl,
            model = prefs.getString("ai_model", config.model) ?: config.model
        )
        Log.d(TAG, "restoreConfig: provider=${config.provider}, apiKey=${Utils.maskKey(config.apiKey)}")
    }

    private fun persistConfig() {
        getPrefs().edit()
            .putString("ai_provider", config.provider)
            .putString("ai_api_key", config.apiKey)
            .putString("ai_api_url", config.apiUrl)
            .putString("ai_model", config.model)
            .apply()
    }

    override val provider: String get() = config.provider
    override val apiUrl: String get() = config.apiUrl
    override val apiKey: String get() = config.apiKey
    override val model: String get() = config.model
    override val defaultApiKey: String get() = BuildConfig.KIMI_KEY

    override fun updateConfig(provider: String, apiUrl: String, apiKey: String, model: String) {
        config = config.copy(provider = provider, apiUrl = apiUrl, apiKey = apiKey, model = model)
        persistConfig()
    }

    override fun saveProviderKey(provider: String, key: String) {
        if (provider.isNotBlank() && key.isNotBlank()) {
            getPrefs().edit().putString("api_key_$provider", key).apply()
        }
    }

    override fun loadProviderKey(provider: String): String {
        return getPrefs().getString("api_key_$provider", "") ?: ""
    }

    override fun getTgChatId(): Long = getPrefs().getLong("tg_allowed_chat_id", 0L)

    override fun setTgChatId(chatId: Long) {
        getPrefs().edit().putLong("tg_allowed_chat_id", chatId).apply()
    }

    override val tgToken: String
        get() = getPrefs().getString("tg_token", null) ?: BuildConfig.TG_TOKEN

    override fun setTgToken(token: String) {
        val oldToken = tgToken
        getPrefs().edit().putString("tg_token", token).apply()
        if (token != oldToken) {
            stopBridge()
            startBridge()
        }
    }

    fun getPrefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- ITgBridgeService ---

    override fun startBridge() {
        val token = tgToken
        if (token.isBlank()) return

        tgBotClient = TgBotClient(token)

        scope.launch(Dispatchers.IO) {
            tgBotClient?.setMyCommands(listOf(
                "status" to "查看 Andclaw 运行状态",
                "stop" to "停止当前任务"
            ))
        }

        tgJob?.cancel()
        tgJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val allowedChatId = getPrefs().getLong("tg_allowed_chat_id", 0L)
                    val updates = tgBotClient?.poll() ?: emptyList()
                    for (msg in updates) {
                        if (allowedChatId != 0L && msg.chatId != allowedChatId) continue
                        handleTelegramCommand(msg.chatId, msg.messageId, msg.text)
                    }
                } catch (_: Exception) {
                    delay(2000)
                }
            }
        }
    }

    override fun stopBridge() {
        tgJob?.cancel()
        tgJob = null
    }

    private suspend fun handleTelegramCommand(chatId: Long, msgId: Long, text: String) {
        tgActiveChatId = chatId
        when (text) {
            "/status" -> {
                val allowedId = getPrefs().getLong("tg_allowed_chat_id", 0L)
                val accessInfo = if (allowedId == 0L) "⚠️ 未设置 Chat ID 白名单" else "✅ Chat ID 已锁定"
                val agentInfo = if (isAgentRunning) "▶️ Agent 运行中: ${uiState.userInput}" else "⏸ Agent 空闲"
                tgBotClient?.send(chatId, "Andclaw 状态\n$agentInfo\n$accessInfo\n你的 Chat ID: $chatId", msgId)
            }
            "/stop" -> {
                withContext(Dispatchers.Main) { stopAgent() }
                tgBotClient?.send(chatId, "✅ 已停止当前任务", msgId)
            }
            else -> {
                tgBotClient?.sendTyping(chatId)
                withContext(Dispatchers.Main) { startAgent(text) }
            }
        }
    }

    // --- Agent Logic ---

    fun startAgent(input: String) {
        addMessage("user", input)
        isAgentRunning = true
        uiState = uiState.copy(isRunning = true, userInput = input)
        consecutiveSameCount = 0
        lastFingerprint = ""
        loopRetryCount = 0

        Log.d(TAG, "startAgent: provider=${config.provider}, model=${config.model}, apiUrl=${config.apiUrl}, apiKey=${Utils.maskKey(config.apiKey)}")

        agentJob = scope.launch {
            delay(1500)
            executeAgentStep(input)
        }
    }

    fun stopAgent() {
        isAgentRunning = false
        uiState = uiState.copy(isRunning = false, status = "Agent Stopped.")
        agentJob?.cancel()
    }

    private suspend fun executeAgentStep(userInput: String, screenshotBase64: String? = null) {
        if (!isAgentRunning) return

        if (tgActiveChatId != 0L) tgBotClient?.sendTyping(tgActiveChatId)

        val svc = AgentAccessibilityService.instance
        val screenData = svc?.captureScreenHierarchy() ?: "Screen data inaccessible"

        var finalScreenshot = screenshotBase64
        if (finalScreenshot == null && svc?.isWebViewContext() == true) {
            finalScreenshot = captureScreenBase64()
        }

        val currentMessages = _messages.value
        val historyContext = currentMessages.takeLast(12).mapNotNull {
            when (it.role) {
                "user" -> mapOf("role" to "user", "content" to it.content)
                "ai" -> it.action?.let { action ->
                    mapOf("role" to "assistant", "content" to gson.toJson(action))
                }
                "system" -> {
                    val content = it.content
                    val shouldKeep = content.startsWith("Intent failed:") ||
                        content.startsWith("Loop detected") ||
                        content.startsWith("Execution Exception:") ||
                        content.startsWith("Error occurred:") ||
                        content.startsWith("AI Request Failed:") ||
                        (content.startsWith("Action success.") && content.contains("\n"))
                    if (shouldKeep) {
                        mapOf("role" to "user", "content" to "System feedback: $content")
                    } else {
                        null
                    }
                }
                else -> null
            }
        }

        try {
            val isDeviceOwner = Util.isDeviceOwner(appContext)
            Log.d(TAG, "executeAgentStep: calling LLM, provider=${config.provider}, apiKey=${Utils.maskKey(config.apiKey)}, historySize=${historyContext.size}, hasScreenshot=${finalScreenshot != null}")
            var response = Utils.callLLMWithHistory(
                userInput, screenData, historyContext, config, appContext,
                isDeviceOwner = isDeviceOwner,
                screenshotBase64 = finalScreenshot
            )
            var action = Utils.parseAction(response)

            if (action.type == "error" && action.reason?.contains("Failed to parse") == true) {
                Log.w(TAG, "LLM returned non-JSON, retrying with correction prompt")
                val retryHistory = historyContext.toMutableList().apply {
                    add(mapOf("role" to "assistant", "content" to response))
                    add(mapOf("role" to "user", "content" to "Invalid response. Output a single JSON object only, no other text."))
                }
                response = Utils.callLLMWithHistory(
                    userInput, screenData, retryHistory, config, appContext,
                    isDeviceOwner = isDeviceOwner
                )
                action = Utils.parseAction(response)
            }

            if (action.type == "error") {
                addMessage("system", "Error occurred: ${action.reason}")
                stopAgent()
            } else {
                withContext(Dispatchers.Main) {
                    val aiDisplayMessage = "[Progress: ${action.progress ?: "Executing"}]\n${action.reason ?: "Thinking..."}"
                    addMessage("ai", aiDisplayMessage, action)
                    handleAction(action)
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                addMessage("system", "AI Request Failed: ${e.message}")
                stopAgent()
            }
        }
    }

    private fun handleAction(action: AiAction) {
        if (!isAgentRunning) return

        val fingerprint = "${action.type}_${action.x}_${action.y}"
        if (fingerprint == lastFingerprint) {
            consecutiveSameCount++
        } else {
            consecutiveSameCount = 1
            lastFingerprint = fingerprint
            loopRetryCount = 0
        }

        if (consecutiveSameCount >= 5) {
            consecutiveSameCount = 0
            loopRetryCount++

            if (loopRetryCount >= 3) {
                addMessage("system", "Loop detected. Same action [$fingerprint] repeated ${loopRetryCount * 5} times with screenshots. Agent stopped.")
                stopAgent()
                return
            }

            scope.launch {
                val screenshot = captureScreenBase64()
                addMessage("system", "Loop detected. Action [$fingerprint] repeated 5 times. Taking screenshot for visual analysis... (retry $loopRetryCount/3)", screenshotBase64 = screenshot)
                executeAgentStep(uiState.userInput, screenshotBase64 = screenshot)
            }
            return
        }

        when (action.type) {
            AiAction.TYPE_INTENT -> {
                addMessage("ai", action.reason ?: "I will use a system shortcut.", action)
                executeIntent(action)

                val isTerminal = action.action?.let {
                    it.contains("ALARM") || it.contains("SEND")
                } ?: false
                if (isTerminal) {
                    addMessage("system", "Task dispatched via system.")
                    stopAgent()
                } else {
                    addMessage("system", "App opened, checking next step...")
                    isAgentRunning = true
                    scope.launch {
                        delay(3000)
                        executeAgentStep(uiState.userInput)
                    }
                }
            }

            AiAction.TYPE_CLICK,
            AiAction.TYPE_SWIPE,
            AiAction.TYPE_LONG_PRESS,
            AiAction.TYPE_TEXT_INPUT,
            AiAction.TYPE_GLOBAL_ACTION,
            AiAction.TYPE_SCREENSHOT,
            AiAction.TYPE_DOWNLOAD,
            AiAction.TYPE_CAMERA,
            AiAction.TYPE_SCREEN_RECORD,
            AiAction.TYPE_VOLUME,
            AiAction.TYPE_AUDIO_RECORD,
            AiAction.TYPE_WAKE_SCREEN -> {
                performConfirmedAction(action)
            }

            AiAction.TYPE_DPM -> {
                val dpmAction = action.dpmAction
                if (dpmAction.isNullOrEmpty()) {
                    addMessage("system", "DPM action name missing")
                    stopAgent()
                    return
                }
                performConfirmedAction(action)
            }

            AiAction.TYPE_WAIT -> {
                val waitMs = if (action.duration > 0) action.duration.coerceAtMost(10000) else 3000L
                addMessage("system", "Waiting ${waitMs}ms for UI update...")
                scope.launch {
                    delay(waitMs)
                    executeAgentStep(uiState.userInput)
                }
            }

            AiAction.TYPE_FINISH -> {
                addMessage("system", "Finished.")
                stopAgent()
            }

            AiAction.TYPE_ERROR -> {
                addMessage("system", "AI Error: ${action.reason}")
                stopAgent()
            }

            else -> {
                addMessage("system", "Unknown action: ${action.type}")
                stopAgent()
            }
        }
    }

    fun performConfirmedAction(action: AiAction) {
        if (!isAgentRunning) return

        scope.launch(Dispatchers.IO) {
            var success = false
            var outputMsg: String? = null
            try {
                when (action.type) {
                    AiAction.TYPE_CLICK -> {
                        withContext(Dispatchers.Main) {
                            AgentAccessibilityService.instance?.click(action.x, action.y)
                        }
                        success = true
                    }

                    AiAction.TYPE_SWIPE -> {
                        val svc = AgentAccessibilityService.instance
                        if (svc == null) {
                            outputMsg = "Accessibility service not running"
                        } else {
                            val dur = if (action.duration > 0) action.duration else 300L
                            withContext(Dispatchers.Main) {
                                svc.swipe(action.x, action.y, action.endX, action.endY, dur)
                            }
                            success = true
                        }
                    }

                    AiAction.TYPE_LONG_PRESS -> {
                        val svc = AgentAccessibilityService.instance
                        if (svc == null) {
                            outputMsg = "Accessibility service not running"
                        } else {
                            val dur = if (action.duration > 0) action.duration else 1000L
                            withContext(Dispatchers.Main) {
                                svc.longPress(action.x, action.y, dur)
                            }
                            success = true
                        }
                    }

                    AiAction.TYPE_TEXT_INPUT -> {
                        val svc = AgentAccessibilityService.instance
                        if (svc == null) {
                            outputMsg = "Accessibility service not running"
                        } else if (action.text.isNullOrEmpty()) {
                            outputMsg = "text field is empty"
                        } else {
                            val result = withContext(Dispatchers.Main) {
                                svc.inputText(action.text)
                            }
                            success = result
                            if (!result) outputMsg = "No focused input field found"
                        }
                    }

                    AiAction.TYPE_GLOBAL_ACTION -> {
                        val svc = AgentAccessibilityService.instance
                        if (svc == null) {
                            outputMsg = "Accessibility service not running"
                        } else {
                            val actionId = when (action.globalAction) {
                                "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                                "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                                "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                                "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                                "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                                else -> {
                                    outputMsg = "Unknown global_action: ${action.globalAction}"
                                    -1
                                }
                            }
                            if (actionId >= 0) {
                                withContext(Dispatchers.Main) { svc.globalAction(actionId) }
                                success = true
                            }
                        }
                    }

                    AiAction.TYPE_SCREENSHOT -> {
                        val svc = AgentAccessibilityService.instance
                        if (svc == null) {
                            outputMsg = "Accessibility service not running"
                        } else {
                            val latch = CountDownLatch(1)
                            var bitmap: Bitmap? = null
                            withContext(Dispatchers.Main) {
                                svc.captureScreenshot { bmp ->
                                    bitmap = bmp
                                    latch.countDown()
                                }
                            }
                            latch.await(5, TimeUnit.SECONDS)
                            if (bitmap != null) {
                                val fileName = "screenshot_${System.currentTimeMillis()}.png"
                                val values = ContentValues().apply {
                                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Andclaw")
                                }
                                appContext.contentResolver.insert(
                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                                )?.let { uri ->
                                    appContext.contentResolver.openOutputStream(uri)?.use { out ->
                                        bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, out)
                                    }
                                }

                                if (tgActiveChatId != 0L && tgBotClient != null) {
                                    val baos = ByteArrayOutputStream()
                                    bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, baos)
                                    val chatId = tgActiveChatId
                                    tgBotClient?.sendPhoto(chatId, baos.toByteArray(), fileName, fileName)
                                }

                                success = true
                                outputMsg = "Screenshot saved & sent: Pictures/Andclaw/$fileName"
                            } else {
                                outputMsg = "Screenshot failed (API 30+ required)"
                            }
                        }
                    }

                    AiAction.TYPE_DOWNLOAD -> {
                        if (action.data.isNullOrEmpty()) {
                            outputMsg = "Download URL (data) is empty"
                        } else {
                            try {
                                val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                val fileName = action.data.substringAfterLast("/")
                                    .substringBefore("?")
                                    .ifEmpty { "download_${System.currentTimeMillis()}" }
                                val request = DownloadManager.Request(
                                    Uri.parse(action.data)
                                ).apply {
                                    setTitle("Andclaw Download")
                                    setDescription(fileName)
                                    setNotificationVisibility(
                                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                                    )
                                    setDestinationInExternalPublicDir("Download", fileName)
                                }
                                val downloadId = dm.enqueue(request)
                                success = true
                                outputMsg = "Download started: $fileName (ID=$downloadId)"
                            } catch (e: Exception) {
                                outputMsg = "Download failed: ${e.message}"
                            }
                        }
                    }

                    AiAction.TYPE_DPM -> {
                        val dpmResult = dpmBridge.execute(action.dpmAction ?: "", action.extras)
                        success = dpmResult.success
                        outputMsg = "DPM ${action.dpmAction}: ${dpmResult.message}"
                    }

                    AiAction.TYPE_CAMERA -> {
                        val cameraAction = action.cameraAction
                        if (cameraAction.isNullOrEmpty()) {
                            outputMsg = "camera_action field is empty"
                        } else {
                            CameraActivity.lastResult = null
                            val cameraIntent = Intent(appContext, CameraActivity::class.java).apply {
                                putExtra(CameraActivity.EXTRA_CAMERA_ACTION, cameraAction)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            appContext.startActivity(cameraIntent)

                            if (cameraAction == CameraActivity.ACTION_START_VIDEO) {
                                delay(3000)
                                success = true
                                outputMsg = CameraActivity.lastResult ?: "Video recording started"
                            } else {
                                var waited = 0L
                                while (CameraActivity.lastResult == null && waited < 15000) {
                                    delay(500)
                                    waited += 500
                                }
                                val result = CameraActivity.lastResult
                                if (result != null) {
                                    success = true
                                    outputMsg = result

                                    if (tgActiveChatId != 0L && tgBotClient != null) {
                                        when (cameraAction) {
                                            CameraActivity.ACTION_TAKE_PHOTO -> {
                                                val uri = CameraActivity.lastPhotoUri
                                                if (uri != null) {
                                                    try {
                                                        appContext.contentResolver.openInputStream(uri)?.use { input ->
                                                            tgBotClient?.sendPhoto(
                                                                tgActiveChatId, input.readBytes(),
                                                                "photo.jpg", "photo.jpg"
                                                            )
                                                            outputMsg += " (已发送到 Telegram)"
                                                        }
                                                    } catch (_: Exception) { }
                                                }
                                            }
                                            CameraActivity.ACTION_STOP_VIDEO -> {
                                                val uri = CameraActivity.lastVideoUri
                                                if (uri != null) {
                                                    try {
                                                        appContext.contentResolver.openInputStream(uri)?.use { input ->
                                                            tgBotClient?.sendVideo(
                                                                tgActiveChatId, input.readBytes(),
                                                                "video.mp4", "video.mp4"
                                                            )
                                                            outputMsg += " (已发送到 Telegram)"
                                                        }
                                                    } catch (_: Exception) { }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    outputMsg = "Camera operation timed out"
                                }
                            }
                        }
                    }

                    AiAction.TYPE_AUDIO_RECORD -> {
                        val recordAction = action.audioRecordAction
                        if (recordAction.isNullOrEmpty()) {
                            outputMsg = "audio_record_action field is empty"
                        } else {
                            AudioRecordActivity.lastResult = null
                            val recordIntent = Intent(appContext, AudioRecordActivity::class.java).apply {
                                putExtra(AudioRecordActivity.EXTRA_AUDIO_RECORD_ACTION, recordAction)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            appContext.startActivity(recordIntent)

                            if (recordAction == AudioRecordActivity.ACTION_START_RECORD) {
                                delay(3000)
                                success = true
                                outputMsg = AudioRecordActivity.lastResult ?: "Audio recording started"
                            } else {
                                var waited = 0L
                                while (AudioRecordActivity.lastResult == null && waited < 15000) {
                                    delay(500)
                                    waited += 500
                                }
                                val result = AudioRecordActivity.lastResult
                                if (result != null) {
                                    success = true
                                    outputMsg = result

                                    if (tgActiveChatId != 0L && tgBotClient != null) {
                                        val uri = AudioRecordActivity.lastAudioUri
                                        if (uri != null) {
                                            try {
                                                appContext.contentResolver.openInputStream(uri)?.use { input ->
                                                    tgBotClient?.sendAudio(
                                                        tgActiveChatId, input.readBytes(),
                                                        "audio.m4a", "audio.m4a"
                                                    )
                                                    outputMsg += " (已发送到 Telegram)"
                                                }
                                            } catch (_: Exception) { }
                                        }
                                    }
                                } else {
                                    outputMsg = "Audio record operation timed out"
                                }
                            }
                        }
                    }

                    AiAction.TYPE_SCREEN_RECORD -> {
                        val recordAction = action.screenRecordAction
                        if (recordAction.isNullOrEmpty()) {
                            outputMsg = "screen_record_action field is empty"
                        } else if (recordAction == ScreenRecordActivity.ACTION_STOP) {
                            if (!ScreenRecordService.isRecording) {
                                outputMsg = "当前没有在录屏"
                            } else {
                                val stopIntent = Intent(appContext, ScreenRecordService::class.java)
                                stopIntent.action = "STOP"
                                appContext.startService(stopIntent)
                                delay(2000)
                                success = true
                                val filePath = ScreenRecordService.lastRecordedFile
                                outputMsg = "录屏已停止, 文件: ${filePath ?: "unknown"}"

                                if (filePath != null && tgActiveChatId != 0L && tgBotClient != null) {
                                    try {
                                        val file = File(filePath)
                                        if (file.exists()) {
                                            tgBotClient?.sendVideo(
                                                tgActiveChatId, file.readBytes(),
                                                file.name, file.name
                                            )
                                            outputMsg += " (已发送到 Telegram)"
                                        }
                                    } catch (_: Exception) { }
                                }
                            }
                        } else {
                            if (ScreenRecordService.isRecording) {
                                success = true
                                outputMsg = "录屏已在进行中"
                            } else {
                                ScreenRecordActivity.lastResult = null
                                val recordIntent = Intent(appContext, ScreenRecordActivity::class.java).apply {
                                    putExtra(ScreenRecordActivity.EXTRA_RECORD_ACTION, recordAction)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                appContext.startActivity(recordIntent)
                                delay(1500)
                                success = true
                                outputMsg = "录屏授权对话框已弹出，请在下一步点击「立即开始」按钮完成授权"
                            }
                        }
                    }

                    AiAction.TYPE_VOLUME -> {
                        val volumeAction = action.volumeAction
                        if (volumeAction.isNullOrEmpty()) {
                            outputMsg = "volume_action field is empty"
                        } else {
                            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            val streamType = when (action.extras?.get("stream")?.toString()) {
                                "ring" -> AudioManager.STREAM_RING
                                "notification" -> AudioManager.STREAM_NOTIFICATION
                                "alarm" -> AudioManager.STREAM_ALARM
                                "system" -> AudioManager.STREAM_SYSTEM
                                else -> AudioManager.STREAM_MUSIC
                            }
                            val streamName = action.extras?.get("stream")?.toString() ?: "music"
                            when (volumeAction) {
                                "set" -> {
                                    val maxVol = audioManager.getStreamMaxVolume(streamType)
                                    val level = when (val v = action.extras?.get("level")) {
                                        is Number -> v.toInt()
                                        is String -> v.toIntOrNull() ?: 50
                                        else -> 50
                                    }
                                    val vol = (level * maxVol / 100).coerceIn(0, maxVol)
                                    audioManager.setStreamVolume(streamType, vol, 0)
                                    success = true
                                    outputMsg = "音量已设置: $streamName $vol/$maxVol ($level%)"
                                }
                                "adjust_up" -> {
                                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_RAISE, 0)
                                    val cur = audioManager.getStreamVolume(streamType)
                                    val max = audioManager.getStreamMaxVolume(streamType)
                                    success = true
                                    outputMsg = "音量已调高: $streamName $cur/$max"
                                }
                                "adjust_down" -> {
                                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_LOWER, 0)
                                    val cur = audioManager.getStreamVolume(streamType)
                                    val max = audioManager.getStreamMaxVolume(streamType)
                                    success = true
                                    outputMsg = "音量已调低: $streamName $cur/$max"
                                }
                                "mute" -> {
                                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_MUTE, 0)
                                    success = true
                                    outputMsg = "已静音: $streamName"
                                }
                                "unmute" -> {
                                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_UNMUTE, 0)
                                    val cur = audioManager.getStreamVolume(streamType)
                                    val max = audioManager.getStreamMaxVolume(streamType)
                                    success = true
                                    outputMsg = "已取消静音: $streamName $cur/$max"
                                }
                                "get" -> {
                                    val cur = audioManager.getStreamVolume(streamType)
                                    val max = audioManager.getStreamMaxVolume(streamType)
                                    val pct = if (max > 0) cur * 100 / max else 0
                                    val muted = audioManager.isStreamMute(streamType)
                                    success = true
                                    outputMsg = "当前音量: $streamName $cur/$max ($pct%)${if (muted) " [已静音]" else ""}"
                                }
                                else -> outputMsg = "Unknown volume_action: $volumeAction"
                            }
                        }
                    }

                    AiAction.TYPE_WAKE_SCREEN -> {
                        val pm = appContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                        @Suppress("DEPRECATION")
                        val wakeLock = pm.newWakeLock(
                            android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                            "andclaw:wakeup"
                        )
                        wakeLock.acquire(3000L)
                        wakeLock.release()
                        success = true
                        outputMsg = "屏幕已唤醒"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { addMessage("system", "Execution Exception: ${e.message}") }
            }

            val finalMsg = outputMsg
            if (success && isAgentRunning) {
                withContext(Dispatchers.Main) {
                    val msg = if (finalMsg != null) "Action success.\n$finalMsg" else "Action success. Waiting for UI refresh..."
                    addMessage("system", msg)
                }
                delay(2500)
                executeAgentStep(uiState.userInput)
            } else {
                withContext(Dispatchers.Main) {
                    if (finalMsg != null) addMessage("system", finalMsg)
                    stopAgent()
                }
            }
        }
    }

    private fun executeIntent(action: AiAction) {
        try {
            Intent(action.action).let { intent ->
                if (!action.data.isNullOrEmpty()) {
                    intent.data = action.data.toUri()
                }
                if (!action.packageName.isNullOrEmpty() && !action.className.isNullOrEmpty()) {
                    intent.component = ComponentName(action.packageName, action.className)
                } else if (!action.packageName.isNullOrEmpty()) {
                    intent.setPackage(action.packageName)
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                action.fillIntentExtras(intent)
                appContext.startActivity(intent)
            }
        } catch (e: Exception) {
            addMessage("system", "Intent failed: ${e.message}")
        }
    }

    // --- Helpers ---

    private suspend fun captureScreenBase64(): String? {
        val svc = AgentAccessibilityService.instance ?: return null
        return suspendCancellableCoroutine { cont ->
            svc.captureScreenshot { bitmap ->
                if (bitmap != null) {
                    val baos = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                    cont.resume(Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
                } else {
                    cont.resume(null)
                }
            }
        }
    }

    fun addMessage(role: String, content: String, action: AiAction? = null, screenshotBase64: String? = null) {
        val msg = ChatMessage(role, content, action, screenshotBase64 = screenshotBase64)
        _messages.update { current -> current + msg }
        Log.d(TAG, "[$role]: $content")

        scope.launch(Dispatchers.IO) {
            val entity = ChatMessageEntity(
                role = msg.role,
                content = msg.content,
                actionJson = action?.let { gson.toJson(it) },
                timestamp = msg.timestamp
            )
            val id = chatDao.insert(entity)
            _messages.update { list ->
                list.map { if (it.timestamp == msg.timestamp && it.role == msg.role && it.id == 0L) it.copy(id = id) else it }
            }
        }

        if (role != "user" && tgActiveChatId != 0L) {
            scope.launch(Dispatchers.IO) {
                tgBotClient?.send(tgActiveChatId, "[$role] $content")
            }
        }
    }

    fun deleteMessages(ids: List<Long>) {
        scope.launch(Dispatchers.IO) {
            chatDao.deleteByIds(ids)
            _messages.update { list -> list.filter { it.id !in ids } }
        }
    }

    fun clearAllMessages() {
        scope.launch(Dispatchers.IO) {
            chatDao.deleteAll()
            _messages.value = emptyList()
        }
    }
}
