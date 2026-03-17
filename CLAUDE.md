# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Andclaw is an Android automation assistant powered by Large Language Models (LLMs). It uses Android Accessibility Services to "read" UI hierarchy and performs actions based on natural language commands. Supports remote control via Telegram Bot.

## Build Commands

This is a Gradle-based Android project with multiple modules.

```bash
# Build the project
./gradlew build

# Install debug APK to connected device
./gradlew :app:installDebug

# Clean build
./gradlew clean

# Run tests
./gradlew test

# Run Android instrumented tests
./gradlew connectedAndroidTest
```

### Required Setup

1. Create `local.properties` with your API keys:
   ```properties
   kimi_key=your_kimi_api_key
   tg_token=your_telegram_bot_token  # Optional, for Telegram integration
   ```

2. The app requires these permissions at runtime:
   - **Accessibility Service**: Must be manually enabled in Settings > Accessibility
   - **Display over other apps**: For the emergency stop floating button

## Project Architecture

### Module Structure

| Module | Purpose |
|--------|---------|
| `:app` | Main application module containing AI agent logic, UI, and accessibility service |
| `:model` | Kimi API client (`KimiApiClient`) using Anthropic messages format |
| `:mdm` | Mobile Device Management - Device Owner policies, Kiosk mode, silent app install |
| `:services` | Kotlin interface definitions for cross-module communication (NOT AIDL) |
| `:commonUtils` | Common Java/Kotlin utilities shared across modules |
| `:network` | Network utilities, Retrofit factory, and file download manager |
| `:setupdesign`, `:setupcompat`, `:setupdesign_strings` | Setup Wizard UI components (from Google testdpc) |

### Module Dependencies

```
app → model, mdm, services, commonUtils
mdm → setupdesign, setupcompat, setupdesign_strings, services, commonUtils
setupdesign → setupcompat, setupdesign_strings
```

### Key Components (app module)

**AgentController** (`app/.../AgentController.kt`)
- Singleton `object` that orchestrates the AI agent loop
- Implements `ITgBridgeService` and `IAiConfigService`
- Manages LLM API calls via `Utils.callLLMWithHistory()`
- Handles Telegram bot integration for remote control (`TgBotClient`)
- Maintains conversation state in `MutableStateFlow<List<ChatMessage>>`
- Loop detection via action fingerprint tracking (`consecutiveSameCount`)

**AgentAccessibilityService** (`app/.../AgentAccessibilityService.kt`)
- Android AccessibilityService providing screen perception and interaction
- `captureScreenHierarchy()`: Captures UI hierarchy as text with element bounds
- `click(x, y)`: Simulates tap gestures using `dispatchGesture()`
- `swipe()`, `longPress()`: Gesture simulation
- `inputText()`: Text injection (SET_TEXT → clipboard paste fallback)
- `isWebViewContext()`: Detects browser/WebView for screenshot-based analysis
- `captureScreenshot()`: Takes screenshot via `takeScreenshot()` API (API 30+)
- `globalAction()`: System-level actions (back, home, recents, etc.)

**Utils** (`app/.../Utils.kt`)
- `buildAgentSystemPrompt()`: Constructs the system prompt with all action type documentation
- `callLLMWithHistory()`: Multi-provider LLM call supporting Kimi (Anthropic format) and OpenAI-compatible APIs
- `parseAction()`: Extracts JSON from LLM response, handles markdown fences and array wrapping
- Supports multimodal input (screenshot base64) for both Kimi and OpenAI providers

**DpmBridge** (`app/.../DpmBridge.kt`)
- Bridges AI actions to Device Policy Manager operations
- `execute()`: Dispatches 40+ DPM actions (lock, reboot, install/uninstall, permissions, kiosk, etc.)
- `getSafetyLevel()`: Classifies actions as SAFE / CONFIRM / DANGEROUS

**TgBotClient** (`app/.../TgBotClient.kt`)
- Telegram Bot API client using OkHttp
- `poll()`: Long-polling `getUpdates`
- `send()`, `sendPhoto()`, `sendVideo()`: Message/media delivery

### Action Types (`app/.../model/Models.kt`)

The AI returns JSON actions with these types:
- `intent` — Launch apps/activities with extras
- `click` — Tap at screen coordinates (x, y)
- `swipe` — Swipe from (x, y) to (endX, endY)
- `long_press` — Long press at coordinates
- `text_input` — Type text into focused fields
- `global_action` — System actions (back, home, recents, notifications, quick_settings)
- `screenshot` — Capture screen and save to gallery
- `download` — Download file by URL via DownloadManager
- `wait` — Wait for UI transition, then re-check screen
- `camera` — Take photo or record video (take_photo, start_video, stop_video)
- `screen_record` — Screen recording via MediaProjection (start_record, stop_record)
- `volume` — Volume control (set, adjust_up, adjust_down, mute, unmute, get)
- `dpm` — Device Policy Manager actions (Device Owner only)
- `finish` — Task completed

### AI Agent Loop

1. User provides command (text via UI, or remotely via Telegram Bot)
2. `AgentAccessibilityService.captureScreenHierarchy()` captures current UI tree
3. If in WebView/browser context, also captures screenshot for visual analysis
4. Prompt sent to LLM with: system prompt + last 12 messages history + screen data
5. AI returns JSON action, parsed by `Utils.parseAction()` into `AiAction`
6. If parse fails, retries with correction prompt
7. `handleAction()` dispatches by type:
   - `intent`: `executeIntent()` → delay 3s → next step
   - `click/swipe/long_press/text_input/global_action/screenshot/download/camera/screen_record/volume`: `performConfirmedAction()` → delay 2.5s → next step
   - `dpm`: `DpmBridge.execute()` → delay 2.5s → next step
   - `wait`: delay specified duration → next step
   - `finish`: stops agent
8. Loop detection: if same action fingerprint repeats 5 times, takes screenshot and retries with visual analysis (max 3 retries before stopping)

### Services Module (Kotlin Interfaces)

The `:services` module defines pure Kotlin interfaces (not AIDL) for cross-module decoupling:

| Interface | Purpose |
|-----------|---------|
| `IAiConfigService` | AI provider config (provider, apiUrl, apiKey, model), Telegram chatId |
| `ITgBridgeService` | Telegram bridge start/stop |
| `IDeviceOwner` | Silent app install/uninstall, install permission check |
| `IAppInfoService` | App info (name, version, package, serial), bring-to-foreground |
| `IToaster` | Toast display |
| `ISocketService` | Socket service start/stop |

### Model Module

`KimiApiClient` (`model/.../KimiApi.kt`):
- Calls Kimi Coding API using Anthropic messages format (`/v1/messages`)
- Supports multimodal input (text + base64 images)
- Auth via `x-api-key` header with `anthropic-version: 2023-06-01`
- Default model: `kimi-k2.5`, default base URL: `https://api.kimi.com/coding`

### MDM Capabilities

The `:mdm` module provides Device Owner features:
- Silent app install/uninstall via `DeviceOwnerImpl` + `PackageInstallationUtils`
- Kiosk mode with `KioskModeHelper` (user restrictions, ADB control, lock task)
- Setup wizard UI via `SetupKioskModeActivity` (device admin, network, AI config)
- Device status monitoring via `DeviceStatusViewModel` (network, screen on/off)
- App lifecycle monitoring via `AppViewModule` (PACKAGE_ADDED/REMOVED broadcasts)
- DPM gateway abstraction via `DevicePolicyManagerGateway` / `DevicePolicyManagerGatewayImpl`

See `ACTIONS.md` for complete capability matrix.

### Dependencies

- **Kotlin**: 2.3.10
- **AGP**: 9.1.0
- **JVM Target**: 17
- **minSdk**: 31, **targetSdk**: 36, **compileSdk**: 36
- **Key Libraries**:
  - ViewBinding + DataBinding (UI)
  - OkHttp 5.3.2 (API calls)
  - Gson 2.13.2 (JSON parsing)
  - Koin 3.3.3 (DI)
  - CameraX 1.4.1 (camera operations)
  - Guava 30.1-jre (ListenableFuture for CameraX)
  - Kotlin Coroutines + StateFlow (async, state management)

## Important Implementation Details

### App Source Files (14 Kotlin files)

| File | Type | Purpose |
|------|------|---------|
| `App.kt` | Application | Koin DI setup, Device Owner listener, permission auto-grant |
| `AgentController.kt` | Agent Core | LLM loop, Telegram bridge, action dispatch |
| `AgentAccessibilityService.kt` | AccessibilityService | Screen perception, gestures, text input, screenshot |
| `Utils.kt` | Utility | System prompt, LLM API calls, JSON parsing |
| `DpmBridge.kt` | Bridge | DPM action execution, safety classification |
| `TgBotClient.kt` | Network | Telegram Bot API client |
| `ChatHistoryActivity.kt` | Activity | Main chat UI with RecyclerView |
| `ActionTestActivity.kt` | Activity | Manual action testing (screenshot, gestures, DPM, etc.) |
| `CameraActivity.kt` | Activity | CameraX photo/video capture |
| `ScreenRecordActivity.kt` | Activity | MediaProjection permission request |
| `ScreenRecordService.kt` | Service | Foreground recording service (MediaRecorder + VirtualDisplay) |
| `Models.kt` | Data | AiAction, AgentUiState, ApiConfig, ChatMessage |
| `ChatAdapter.kt` | Adapter | Chat bubble RecyclerView adapter |
| `AppInfoService.kt` | Service Impl | IAppInfoService implementation for MDM module |

### Safety Features

1. **Human-in-the-Loop**: Actions dispatched via `performConfirmedAction()` for confirmed execution
2. **Loop Detection**: Tracks action fingerprints; after 5 repeats, takes screenshot for visual retry; after 15 total repeats, stops agent
3. **Privacy Warning**: Screen UI data and screenshots are sent to LLM providers

### BuildConfig Fields

- `KIMI_KEY`: Injected from `local.properties` kimi_key
- `TG_TOKEN`: Injected from `local.properties` tg_token

### Screen Recording

Screen capture is implemented via:
- `ScreenRecordActivity`: Transparent activity requesting MediaProjection permission
- `ScreenRecordService`: Foreground service using `MediaRecorder` + `VirtualDisplay`
- Output saved to `Movies/Andclaw/` via MediaStore
- Supports sending recorded video to Telegram

### Default API Configuration

```kotlin
ApiConfig(
    provider = "Kimi Code",
    apiKey = BuildConfig.KIMI_KEY,
    apiUrl = "https://api.kimi.com/coding",
    model = "kimi-k2.5"
)
```

Supports three providers via `IAiConfigService.updateConfig()`:
- **Kimi Code**: Anthropic Messages format (`api.kimi.com/coding`), API Key from Kimi Code console
- **Moonshot**: OpenAI-compatible format (`api.moonshot.cn/v1`), API Key from Moonshot platform
- **OpenAI**: Any OpenAI-compatible API
