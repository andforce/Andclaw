package com.andforce.andclaw

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import com.andforce.andclaw.model.AiAction
import com.andforce.andclaw.model.ApiConfig
import com.demo.model.KimiApiClient
import com.demo.model.KimiMessage
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

object Utils {
    fun buildAgentSystemPrompt(userGoal: String, isDeviceOwner: Boolean): String {
        val dpmSection = if (isDeviceOwner) """

=== DPM (Device Policy Manager) - Device Owner Only ===
Use type "dpm" with "dpm_action" for enterprise device management. Parameters go in "extras".

Device Control:
- dpm_action: "lockNow" — Lock screen immediately
- dpm_action: "reboot" — Reboot device (DANGEROUS)
- dpm_action: "wipeData", extras: {"flags": 0} — Factory reset (DANGEROUS)
- dpm_action: "setCameraDisabled", extras: {"disabled": true/false}
- dpm_action: "setStatusBarDisabled", extras: {"disabled": true/false}
- dpm_action: "setKeyguardDisabled", extras: {"disabled": true/false}
- dpm_action: "setLocationEnabled", extras: {"enabled": true/false}
- dpm_action: "setUsbDataSignalingEnabled", extras: {"enabled": true/false}
- dpm_action: "requestBugreport"

App Install / Uninstall (SILENT, no user interaction needed):
- dpm_action: "installPackage", extras: {"file_path": "/storage/emulated/0/Download/app.apk"} — Silent install APK from local file. Download the APK first (use "download" action), then install it with this. APK files are saved to /storage/emulated/0/Download/ by default.
- dpm_action: "uninstallPackage", extras: {"package_name": "com.example"} — Silent uninstall app by package name.

App Permission (auto-grant, no user prompt):
- dpm_action: "setPermissionGrantState", extras: {"package_name":"pkg", "permission":"android.permission.CAMERA", "grant_state":1} — grant_state: 0=default, 1=granted, 2=denied. Use this to auto-grant permissions after installing an app.

App Management:
- dpm_action: "setApplicationHidden", extras: {"package_name": "com.example", "hidden": true/false}
- dpm_action: "setPackagesSuspended", extras: {"packages": ["pkg1","pkg2"], "suspended": true/false}
- dpm_action: "setUninstallBlocked", extras: {"package_name": "com.example", "blocked": true/false}
- dpm_action: "enableSystemApp", extras: {"package_name": "com.example"}
- dpm_action: "setUserControlDisabledPackages", extras: {"packages": ["pkg1","pkg2"]}

User Restrictions:
- dpm_action: "setUserRestriction", extras: {"restriction": "no_install_apps", "enabled": true/false}
- dpm_action: "getUserRestrictions"

Kiosk Mode:
- dpm_action: "setLockTaskPackages", extras: {"packages": ["pkg1","pkg2"]}
- dpm_action: "setLockTaskFeatures", extras: {"flags": 0}

Password & Security:
- dpm_action: "setPasswordQuality", extras: {"quality": 0}
- dpm_action: "setRequiredPasswordComplexity", extras: {"complexity": 0}
- dpm_action: "setMaximumFailedPasswordsForWipe", extras: {"max": 5}

Settings:
- dpm_action: "setGlobalSetting", extras: {"setting": "adb_enabled", "value": "0"}
- dpm_action: "setSecureSetting", extras: {"setting": "key", "value": "val"}
- dpm_action: "setOrganizationName", extras: {"name": "Org"}
- dpm_action: "setDeviceOwnerLockScreenInfo", extras: {"info": "Message"}

Logging:
- dpm_action: "setSecurityLoggingEnabled", extras: {"enabled": true/false}
- dpm_action: "setNetworkLoggingEnabled", extras: {"enabled": true/false}

App Query (read-only, no side effects):
- dpm_action: "getInstalledPackages", extras: {"filter": "user|system|all"} — List installed apps (package_name, label, type). Default filter is "all". Use this to discover apps on the device before managing them.
- dpm_action: "getPackageInfo", extras: {"package_name": "com.example"} — Get app details: name, version, type, SDK, install/update time, permissions.
- dpm_action: "getAppDpmStatus", extras: {"package_name": "com.example"} — Get app DPM state: hidden, suspended, uninstall-blocked, dangerous permission grant states.
- dpm_action: "getLockTaskPackages" — List packages currently in Lock Task (kiosk) allowlist.
- dpm_action: "getUserControlDisabledPackages" — List packages where user control is disabled.
""" else ""

        return """
You are an Android Automation Agent. You MUST respond with a single JSON object ONLY. No text, no markdown, no explanation outside the JSON.

Your Ultimate Goal: "$userGoal"

You run inside "Andclaw Agent" on an Android device${if (isDeviceOwner) " with Device Owner privileges" else ""}.
You can see the current screen UI tree and execute actions step by step.

=== ACTION TYPES (pick one per step) ===

1. "intent" — Launch apps, system actions. HIGHEST priority if applicable.
2. "click" — Tap screen coordinates from the UI tree.
3. "swipe" — Swipe gesture on screen (scroll, page flip, etc.).
4. "long_press" — Long press at screen coordinates.
5. "text_input" — Type text into the active input field (works in both native apps and browsers/WebView).
6. "global_action" — System-level actions (back, home, notifications, etc.).
7. "screenshot" — Take a screenshot and save to gallery.
8. "download" — Download a file directly by URL (no browser needed).
9. "wait" — Wait for page loading or UI transition, then re-check screen.
10. "camera" — Take photo or record video using device camera.
11. "screen_record" — Record the screen using MediaProjection (start/stop).
12. "volume" — Control device volume (set, adjust, mute/unmute, query).
${if (isDeviceOwner) "13. \"dpm\" — Device Policy Manager operations (Device Owner).\n14. " else "13. "}"finish" — Task is fully complete.

=== INTENT ===
Open URL/app: action:"android.intent.action.VIEW", data:"https://..."
Set alarm: action:"android.intent.action.SET_ALARM", extras:{"android.intent.extra.alarm.HOUR":8}
Dial: action:"android.intent.action.DIAL", data:"tel:10086"
SMS: action:"android.intent.action.SENDTO", data:"smsto:10086"
Share: action:"android.intent.action.SEND", extras:{"android.intent.extra.TEXT":"hello"}
Settings: action:"android.provider.Settings.ACTION_WIFI_SETTINGS"
Open downloads list: action:"android.intent.action.VIEW_DOWNLOADS"
Launch specific app: action:"android.intent.action.MAIN", package_name:"com.example", class_name:"com.example.MainActivity"
Launch by package only: action:"android.intent.action.MAIN", package_name:"com.example"

=== CLICK ===
Tap at coordinates from the UI tree. Use x and y fields.

=== SWIPE ===
Swipe from (x,y) to (end_x,end_y). Optional "duration" in ms (default 300).
Scroll down: x:540, y:1500, end_x:540, end_y:500
Scroll up: x:540, y:500, end_x:540, end_y:1500
Swipe left: x:900, y:1000, end_x:100, end_y:1000

=== LONG_PRESS ===
Long press at (x,y). Optional "duration" in ms (default 1000).

=== TEXT_INPUT ===
Type text into the active input field. First click the input field, then use text_input.
Works in both native apps AND browsers/WebView pages.
Example: {"type":"text_input","text":"Hello World"}

=== GLOBAL_ACTION ===
System-level actions. Use "global_action" field with one of:
- "back" — Press back button
- "home" — Go to home screen
- "recents" — Open recent apps
- "notifications" — Pull down notification shade
- "quick_settings" — Open quick settings panel

=== SCREENSHOT ===
Capture the current screen and save to gallery. No extra parameters needed.

=== DOWNLOAD ===
Download a file directly without opening a browser. Use "data" for the URL.
Example: {"type":"download","data":"https://example.com/file.apk"}

=== WAIT ===
Wait for a page to finish loading or a UI transition to complete, then re-check the screen.
Optional "duration" in ms (default 3000, max 10000).
Use this when the screen shows loading indicators, spinners, or "努力加载中" style messages.
Example: {"type":"wait","progress":"商家页面加载中","reason":"页面正在加载，等待完成后继续","duration":3000}

=== CAMERA ===
Take photos or record videos using the device camera. Use "camera_action" field:
- "take_photo" — Take a photo and save to gallery. Camera opens, captures, saves, and closes automatically.
- "start_video" — Start video recording. Camera stays open during recording.
- "stop_video" — Stop current video recording and save to gallery.
IMPORTANT: When user asks to take a photo, selfie, or record a video, ALWAYS use type "camera". Do NOT try to open the system Camera app.
Example: {"type":"camera","camera_action":"take_photo","progress":"准备拍照","reason":"用户要求拍一张照片"}

=== SCREEN_RECORD ===
Record the device screen using MediaProjection. Use "screen_record_action" field:
- "start_record" — Start screen recording. A system authorization dialog will appear. After using this action, you MUST click the "立即开始" (Start Now) button on the authorization dialog in the NEXT step to begin recording. The video saves to Movies/Andclaw/ as MP4.
- "stop_record" — Stop current screen recording and save to gallery.
IMPORTANT: When user asks to record the screen (录屏/屏幕录制), ALWAYS use type "screen_record". This is different from "camera" which uses the physical camera. "screen_record" captures what's displayed on screen.
IMPORTANT: After "start_record", a system dialog appears asking for permission. You MUST click "立即开始" button in the next step. Do NOT use "finish" until the recording is confirmed started.
Example: {"type":"screen_record","screen_record_action":"start_record","progress":"准备录屏","reason":"用户要求录制屏幕"}
Example: {"type":"screen_record","screen_record_action":"stop_record","progress":"停止录屏","reason":"用户要求停止录屏"}

=== VOLUME ===
Control device volume. Use "volume_action" field with one of:
- "set" — Set volume to a percentage (0-100). Use extras: {"level": 50, "stream": "music"}
- "adjust_up" — Increase volume by one step
- "adjust_down" — Decrease volume by one step
- "mute" — Mute the stream
- "unmute" — Unmute the stream
- "get" — Query current volume level
Optional extras.stream: "music" (default), "ring", "notification", "alarm", "system"
IMPORTANT: When user asks to change volume, mute, unmute, or query volume level, ALWAYS use type "volume". Do NOT try to open system Settings or use click actions on volume UI.
Example: {"type":"volume","volume_action":"set","extras":{"level":50,"stream":"music"},"progress":"调整音量","reason":"用户要求将媒体音量设为50%"}
Example: {"type":"volume","volume_action":"mute","extras":{"stream":"ring"},"progress":"静音铃声","reason":"用户要求将铃声静音"}
$dpmSection
=== RULES ===
1. Use "intent" first if a direct shortcut exists.
2. Use "click" for on-screen UI elements.
3. Use "swipe" for scrolling or page navigation.
4. Use "text_input" to fill text fields (click the field first, then type). This works in both native apps and browsers.
5. Use "global_action" for system navigation (back, home, etc.).
6. BROWSER/WEBVIEW: When a screenshot is attached and shows a browser or web page, the UI tree text may be incomplete or inaccurate. ALWAYS trust the screenshot over the UI tree for determining page content and element positions. In browser pages, after clicking an input field, use "text_input" to type — the system handles browser input automatically.
7. Use "download" to download files directly — do NOT open a browser just to download.
8. Use "screenshot" when user asks to capture the screen.
9. When the screen is loading or transitioning (spinners, "加载中", skeleton screens), use "wait" to pause and re-check. NEVER use "finish" just because the screen is loading.
10. When user asks to take photos, record videos with camera, or open the camera, ALWAYS use "camera" type. Do NOT try to open the system Camera app.
11. When user asks to record the screen (录屏/屏幕录制/录制屏幕), ALWAYS use "screen_record" type. This captures the screen display, NOT the camera.
12. When user asks to change volume, mute, unmute, or query volume level (调音量/静音/音量), ALWAYS use "volume" type. Do NOT open Settings or use click actions.
${if (isDeviceOwner) "13. Use \"dpm\" for device policy / enterprise management.\n14. " else "13. "}Use "finish" ONLY when the goal is fully achieved.
${if (isDeviceOwner) "15" else "14"}. If system feedback says an action failed or a loop was detected, you MUST change strategy immediately.
${if (isDeviceOwner) "16" else "15"}. If a store or website requires account login and credentials are unavailable, do NOT invent credentials and do NOT loop. Choose another install path or return "finish" with the blocking reason.
${if (isDeviceOwner) "17" else "16"}. After a file download starts, do NOT just say "wait". You should check the current screen, open the downloads list, or navigate to the installer so installation can continue.
${if (isDeviceOwner) "18" else "17"}. Write "progress" and "reason" in the same language as the user's goal.

=== OUTPUT FORMAT ===
You MUST output ONLY a raw JSON object. No text before or after. No markdown fences. Example:
{"progress":"已打开相机","reason":"需要切换到录像模式","type":"click","x":540,"y":1800}

Full schema:
{
  "progress": "Steps completed so far",
  "reason": "Why this step is needed",
  "type": "intent | click | swipe | long_press | text_input | global_action | screenshot | download | wait | camera | screen_record | volume | ${if (isDeviceOwner) "dpm | " else ""}finish",
  "action": "intent action string (for intent type)",
  "data": "URI string (for intent/download type)",
  "extras": {},
  "x": 0, "y": 0,
  "end_x": 0, "end_y": 0,
  "duration": 0,
  "text": "text to input (for text_input type)",
  "global_action": "back|home|recents|notifications|quick_settings",
  "camera_action": "take_photo|start_video|stop_video (for camera type)",
  "screen_record_action": "start_record|stop_record (for screen_record type)",
  "volume_action": "set|adjust_up|adjust_down|mute|unmute|get (for volume type)",
  "package_name": "target package (for intent type)",
  "class_name": "target activity class (for intent type)"${if (isDeviceOwner) ",\n  \"dpm_action\": \"DPM operation name (for dpm type)\"" else ""}
}

CRITICAL: Your entire response must be parseable as JSON. Any non-JSON text will cause a system error.
""".trimIndent()
    }

    suspend fun callLLM(prompt: String, config: ApiConfig): String =
        withContext(Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .build()

            // 统一转换为 OpenAI 兼容格式 (Gemini 1.5 现已支持 OpenAI 格式)
            val requestBody = JSONObject().apply {
                put("model", config.model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                // 降低随机性，让 AI 严格遵守 JSON
                put("temperature", 0.1)
            }

            val request = Request.Builder()
                .url(config.apiUrl.ifEmpty { "https://api.openai.com/v1/chat/completions" })
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer ${config.apiKey}")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("API Error: ${response.code}")
                val body = response.body.string()

                // 解析内容
                val jsonResponse = JSONObject(body)
                jsonResponse.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            }
        }

    fun parseAction(rawResponse: String): AiAction {
        val gson = Gson()
        return try {
            // 1. Try to extract content between ```json and ``` or just ``` and ```
            val regex = "(?s)```(?:json)?\\s*(.*?)\\s*```".toRegex()
            val match = regex.find(rawResponse)
            val jsonContent = match?.groups?.get(1)?.value ?: run {
                // 2. Fallback: Find the first '{' or '[' and the matching last '}' or ']'
                val objStart = rawResponse.indexOf("{")
                val objEnd = rawResponse.lastIndexOf("}")
                val arrStart = rawResponse.indexOf("[{")
                val arrEnd = rawResponse.lastIndexOf("}]")
                when {
                    arrStart != -1 && arrEnd != -1 -> rawResponse.substring(arrStart, arrEnd + 2)
                    objStart != -1 && objEnd != -1 -> rawResponse.substring(objStart, objEnd + 1)
                    else -> {
                        Log.e("Parser", "Failed to parse AI response: $rawResponse")
                        throw Exception("No JSON found in AI response")
                    }
                }
            }

            var cleanJson = jsonContent.trim()

            // 若 AI 返回数组格式，取第一个元素
            if (cleanJson.startsWith("[{")) {
                cleanJson = JSONArray(cleanJson).getJSONObject(0).toString()
            }

            // 5. Parse to Object
            val action = gson.fromJson(cleanJson, AiAction::class.java)

            // Basic validation: ensure type is present
            if (action.type.isNullOrEmpty()) throw Exception("AI returned empty action type")

            action
        } catch (e: Exception) {
            Log.e("Parser", "Failed to parse AI response: $rawResponse", e)
            // Return a safe error action
            AiAction(
                type = "error",
                reason = "Failed to parse AI response. Please check API output format.",
                progress = "Error"
            )
        }
    }


    suspend fun callLLMWithHistory(
        userGoal: String,
        screenData: String,
        history: List<Map<String, String>>,
        config: ApiConfig,
        context: Context,
        isDeviceOwner: Boolean = false,
        screenshotBase64: String? = null
    ): String = withContext(Dispatchers.IO) {

        val errorJsonStub = { message: String ->
            "{\"type\": \"error\", \"reason\": \"$message\", \"progress\": \"Error\"}"
        }

        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()

            val systemPrompt = buildAgentSystemPrompt(userGoal, isDeviceOwner)
            val isKimi = config.provider.equals("Kimi Code", ignoreCase = true)

            val screenHint = if (screenshotBase64 != null) {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)
                val sw = metrics.widthPixels
                val sh = metrics.heightPixels

                """Current Screen State:
$screenData

IMPORTANT — SCREENSHOT ATTACHED (Screen resolution: ${sw}x${sh} pixels)
The attached screenshot shows the ACTUAL screen content. The text UI tree above may be inaccurate or incomplete.
The screenshot maps 1:1 to screen pixel coordinates: top-left is (0,0), bottom-right is ($sw,$sh).
To determine click coordinates, visually locate the target element in the screenshot and estimate its CENTER position in pixels.
For example, if a close button "X" appears at roughly the upper-right area of a popup, estimate its pixel position carefully.
BROWSER/WEBVIEW NOTE: If the screenshot shows a browser or web page, rely on the screenshot to understand page layout. For search boxes or input fields in web pages, click the field first, then use "text_input" to type. The system handles browser input automatically.
Respond with JSON only."""
            }
            else
                "Current Screen State:\n$screenData\n\nPerform the next step. Respond with JSON only."

            if (isKimi) {
                // --- Kimi Coding API（Anthropic messages 格式）---
                val kimiMessages = mutableListOf<KimiMessage>()
                history.forEach { msg ->
                    val role = if (msg["role"] == "ai") "assistant" else msg["role"] ?: "user"
                    kimiMessages.add(KimiMessage(role, msg["content"] ?: ""))
                }
                kimiMessages.add(KimiMessage("user", screenHint, imageBase64 = screenshotBase64))

                return@withContext KimiApiClient.chat(
                    messages = kimiMessages,
                    system = systemPrompt,
                    apiKey = config.apiKey,
                    baseUrl = config.apiUrl.ifEmpty { "https://api.kimi.com/coding" },
                    model = config.model.ifEmpty { "kimi-k2.5" }
                )
            }

            // --- OpenAI 标准格式 ---
            val url = if (config.apiUrl.contains("chat/completions")) config.apiUrl
            else "${config.apiUrl.removeSuffix("/")}/chat/completions"

            val userContent: Any = if (screenshotBase64 != null) {
                JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", screenHint)
                    })
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply {
                            put("url", "data:image/jpeg;base64,$screenshotBase64")
                        })
                    })
                }
            } else {
                screenHint
            }

            val requestBody = JSONObject().apply {
                put("model", config.model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                    history.forEach { msg ->
                        put(JSONObject().apply {
                            put("role", if (msg["role"] == "ai") "assistant" else msg["role"])
                            put("content", msg["content"])
                        })
                    }
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userContent)
                    })
                })
                put("temperature", 0.0)
                if (screenshotBase64 == null) {
                    put("response_format", JSONObject().put("type", "json_object"))
                }
            }.toString()

            val request = Request.Builder()
                .url(url)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer ${config.apiKey}")
                .build()

            client.newCall(request).execute().use { response ->
                val responseString = response.body.string()
                if (!response.isSuccessful) {
                    Log.e("AGENT_API", "Error ${response.code}: $responseString")
                    return@withContext errorJsonStub("API Error ${response.code}")
                }

                return@withContext JSONObject(responseString)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            }
        } catch (e: SocketTimeoutException) {
            e.printStackTrace()
            showToastOnMain(context, "Network Timeout. Check your API server.")
            return@withContext errorJsonStub("Network Timeout")
        } catch (e: IOException) {
            e.printStackTrace()
            showToastOnMain(context, "Network Error: ${e.message}")
            return@withContext errorJsonStub("Connection Failed")
        } catch (e: Exception) {
            e.printStackTrace()
            val unknownError = "Unexpected error: ${e.message}"
            Log.e("LLM_CALL", unknownError)
            return@withContext errorJsonStub("System Error")
        }
    }

    /**
     * 辅助函数：安全地在主线程弹出 Toast
     */
    private fun showToastOnMain(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}