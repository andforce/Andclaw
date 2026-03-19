package com.andforce.andclaw

import android.accessibilityservice.AccessibilityService
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.util.Base64
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.afwsamples.testdpc.common.Util
import com.andforce.andclaw.databinding.ActivityActionTestBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ActionTestActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ActionTest"
        private const val TEST_DOWNLOAD_URL =
            "https://www.baidu.com/robots.txt"
    }

    private lateinit var binding: ActivityActionTestBinding

    private val screenCaptureRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            ScreenRecordService.prepareResult(result.resultCode, result.data!!)
            val intent = Intent(this, ScreenRecordService::class.java).apply {
                action = "START"
            }
            startForegroundService(intent)
            binding.btnStartRecord.isEnabled = false
            binding.btnStopRecord.isEnabled = true
            log("录屏已启动")
        } else {
            log("用户取消了录屏授权")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityActionTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupScreenshot()
        setupScreenRecord()
        setupDownload()
        setupTextInput()
        setupGestures()
        setupGlobalActions()
        setupAppManagement()
        setupClipboard()
        setupShare()

        binding.btnClearLog.setOnClickListener {
            binding.tvLog.text = ""
        }
    }

    // --- 截屏 ---

    private fun setupScreenshot() {
        binding.btnScreenshot.setOnClickListener {
            val service = AgentAccessibilityService.instance
            if (service == null) {
                log("错误: 无障碍服务未启用")
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                log("错误: 截屏需要 API 30+, 当前 API ${Build.VERSION.SDK_INT}")
                return@setOnClickListener
            }
            log("正在截屏...")
            service.captureScreenshot { bitmap ->
                runOnUiThread {
                    if (bitmap != null) {
                        val path = saveScreenshot(bitmap)
                        log("截屏成功: ${bitmap.width}x${bitmap.height}, 已保存: $path")
                    } else {
                        log("截屏失败")
                    }
                }
            }
        }

        binding.btnScreenshotToAi.setOnClickListener {
            val service = AgentAccessibilityService.instance
            if (service == null) {
                log("错误: 无障碍服务未启用")
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                log("错误: 截屏需要 API 30+")
                return@setOnClickListener
            }
            log("正在截屏并发送给 AI...")
            service.captureScreenshot { bitmap ->
                if (bitmap == null) {
                    runOnUiThread { log("截屏失败") }
                    return@captureScreenshot
                }
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                val screenData = AgentAccessibilityService.instance?.captureScreenHierarchy() ?: "Empty"
                runOnUiThread { log("截屏完成 (${baos.size() / 1024}KB), 正在发送给 AI...") }

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val config = AgentController.config
                        val response = Utils.callLLMWithHistory(
                            "描述当前屏幕内容", screenData, emptyList(), config,
                            this@ActionTestActivity, screenshotBase64 = base64
                        )
                        withContext(Dispatchers.Main) {
                            log("AI 响应:\n$response")
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            log("AI 请求失败: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    private fun saveScreenshot(bitmap: Bitmap): String {
        val fileName = "screenshot_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Andclaw")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
        return "Pictures/Andclaw/$fileName"
    }

    // --- 录屏 ---

    private fun setupScreenRecord() {
        binding.btnStartRecord.setOnClickListener {
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureRequest.launch(projectionManager.createScreenCaptureIntent())
        }

        binding.btnStopRecord.setOnClickListener {
            val intent = Intent(this, ScreenRecordService::class.java).apply {
                action = "STOP"
            }
            startService(intent)
            binding.btnStartRecord.isEnabled = true
            binding.btnStopRecord.isEnabled = false
            log("录屏已停止, 文件: ${ScreenRecordService.lastRecordedFile ?: "unknown"}")
        }
    }

    // --- 文件下载 ---

    private fun setupDownload() {
        binding.btnDownload.setOnClickListener {
            try {
                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                val request = DownloadManager.Request(Uri.parse(TEST_DOWNLOAD_URL)).apply {
                    setTitle("Andclaw 测试下载")
                    setDescription("正在下载测试文件...")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir("Download", "andclaw_test.txt")
                }
                val downloadId = dm.enqueue(request)
                log("下载已启动, ID=$downloadId, URL=$TEST_DOWNLOAD_URL")
            } catch (e: Exception) {
                log("下载失败: ${e.message}")
            }
        }
    }

    // --- 文本输入 ---

    private fun setupTextInput() {
        binding.btnTextInput.setOnClickListener {
            val service = AgentAccessibilityService.instance
            if (service == null) {
                log("错误: 无障碍服务未启用")
                return@setOnClickListener
            }

            val editText = EditText(this).apply {
                hint = "输入要注入的文本"
                setText("Hello from Andclaw!")
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("文本输入测试")
                .setView(editText)
                .setPositiveButton("注入") { _, _ ->
                    val text = editText.text.toString()
                    val result = service.inputText(text)
                    log("文本注入 ${if (result) "成功" else "失败"}: \"$text\"")
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    // --- 手势操作 ---

    private fun setupGestures() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val cx = metrics.widthPixels / 2
        val cy = metrics.heightPixels / 2
        val offset = metrics.heightPixels / 4

        binding.btnSwipeUp.setOnClickListener {
            withService { it.swipe(cx, cy + offset, cx, cy - offset) }
            log("上滑: ($cx,${cy + offset}) -> ($cx,${cy - offset})")
        }
        binding.btnSwipeDown.setOnClickListener {
            withService { it.swipe(cx, cy - offset, cx, cy + offset) }
            log("下滑: ($cx,${cy - offset}) -> ($cx,${cy + offset})")
        }
        binding.btnSwipeLeft.setOnClickListener {
            val xOffset = metrics.widthPixels / 4
            withService { it.swipe(cx + xOffset, cy, cx - xOffset, cy) }
            log("左滑: (${cx + xOffset},$cy) -> (${cx - xOffset},$cy)")
        }
        binding.btnSwipeRight.setOnClickListener {
            val xOffset = metrics.widthPixels / 4
            withService { it.swipe(cx - xOffset, cy, cx + xOffset, cy) }
            log("右滑: (${cx - xOffset},$cy) -> (${cx + xOffset},$cy)")
        }
        binding.btnLongPress.setOnClickListener {
            withService { it.longPress(cx, cy) }
            log("长按屏幕中心: ($cx, $cy)")
        }
    }

    // --- 全局操作 ---

    private fun setupGlobalActions() {
        binding.btnBack.setOnClickListener {
            withService { it.globalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
            log("全局操作: 返回")
        }
        binding.btnHome.setOnClickListener {
            withService { it.globalAction(AccessibilityService.GLOBAL_ACTION_HOME) }
            log("全局操作: Home")
        }
        binding.btnRecents.setOnClickListener {
            withService { it.globalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) }
            log("全局操作: 最近任务")
        }
        binding.btnNotifications.setOnClickListener {
            withService { it.globalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) }
            log("全局操作: 通知栏")
        }
        binding.btnQuickSettings.setOnClickListener {
            withService { it.globalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) }
            log("全局操作: 快捷设置")
        }
    }

    // --- 应用管理 (DPM) ---

    private fun setupAppManagement() {
        val dpmBridge by lazy { DpmBridge(this) }

        binding.btnInstallApk.setOnClickListener {
            if (!Util.isDeviceOwner(this)) {
                log("错误: 需要 Device Owner 权限")
                return@setOnClickListener
            }
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            log("扫描目录: ${downloadDir.absolutePath}, exists=${downloadDir.exists()}")
            val apkFiles = downloadDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".apk", ignoreCase = true) }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()

            if (apkFiles.isEmpty()) {
                log("Download 目录下没有 APK 文件")
                return@setOnClickListener
            }

            val names = apkFiles.map { "${it.name} (${it.length() / 1024}KB)" }.toTypedArray()
            MaterialAlertDialogBuilder(this)
                .setTitle("选择要安装的 APK")
                .setItems(names) { _, which ->
                    val file = apkFiles[which]
                    log("正在安装: ${file.name} ...")
                    val result = dpmBridge.execute("installPackage", mapOf("file_path" to file.absolutePath))
                    log("安装结果: ${result.message}")
                }
                .setNegativeButton("取消", null)
                .show()
        }

        binding.btnUninstallApp.setOnClickListener {
            if (!Util.isDeviceOwner(this)) {
                log("错误: 需要 Device Owner 权限")
                return@setOnClickListener
            }
            val editText = EditText(this).apply { hint = "输入包名, 如 com.example.app" }
            MaterialAlertDialogBuilder(this)
                .setTitle("静默卸载")
                .setView(editText)
                .setPositiveButton("卸载") { _, _ ->
                    val pkg = editText.text.toString().trim()
                    if (pkg.isNotEmpty()) {
                        log("正在卸载: $pkg ...")
                        val result = dpmBridge.execute("uninstallPackage", mapOf("package_name" to pkg))
                        log("卸载结果: ${result.message}")
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        binding.btnGrantPermission.setOnClickListener {
            if (!Util.isDeviceOwner(this)) {
                log("错误: 需要 Device Owner 权限")
                return@setOnClickListener
            }
            val editText = EditText(this).apply {
                hint = "包名"
                setText("com.example.app")
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("自动授权全部权限")
                .setMessage("将授予目标应用所有已声明的运行时权限")
                .setView(editText)
                .setPositiveButton("授权") { _, _ ->
                    val pkg = editText.text.toString().trim()
                    if (pkg.isNotEmpty()) {
                        grantAllPermissions(pkg, dpmBridge)
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun grantAllPermissions(packageName: String, dpmBridge: DpmBridge) {
        try {
            val pkgInfo = packageManager.getPackageInfo(packageName,
                PackageManager.GET_PERMISSIONS)
            val permissions = pkgInfo.requestedPermissions ?: emptyArray()
            var granted = 0
            for (perm in permissions) {
                val result = dpmBridge.execute("setPermissionGrantState", mapOf(
                    "package_name" to packageName,
                    "permission" to perm,
                    "grant_state" to 1
                ))
                if (result.success) granted++
            }
            log("已授权 $granted/${permissions.size} 个权限给 $packageName")
        } catch (e: Exception) {
            log("授权失败: ${e.message}")
        }
    }

    // --- 剪贴板 ---

    private fun setupClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager

        binding.btnCopy.setOnClickListener {
            val testText = "Andclaw Test ${System.currentTimeMillis()}"
            cm.setPrimaryClip(ClipData.newPlainText("test", testText))
            log("已复制到剪贴板: \"$testText\"")
        }

        binding.btnPaste.setOnClickListener {
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString() ?: "(非文本内容)"
                log("剪贴板内容: \"$text\"")
            } else {
                log("剪贴板为空")
            }
        }
    }

    // --- 分享 ---

    private fun setupShare() {
        binding.btnShare.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Hello from Andclaw! 测试分享功能")
            }
            startActivity(Intent.createChooser(shareIntent, "分享到"))
            log("已发起分享")
        }
    }

    private inline fun withService(action: (AgentAccessibilityService) -> Unit): Boolean {
        val service = AgentAccessibilityService.instance
        if (service == null) {
            log("错误: 无障碍服务未启用")
            return false
        }
        action(service)
        return true
    }

    // --- 日志 ---

    private fun log(msg: String) {
        Log.d(TAG, msg)
        runOnUiThread {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            binding.tvLog.append("[$time] $msg\n")
            binding.logScrollView.post {
                binding.logScrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }
}
