package com.afwsamples.testdpc.policy.locktask

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat

internal object AndclawAgentReadiness {
    enum class Requirement(
        val title: String,
        val requiredForLaunch: Boolean,
        val actionLabel: String?
    ) {
        ACCESSIBILITY("辅助功能", true, "开启辅助功能"),
        FILE_ACCESS("文件访问", true, "允许所有文件访问"),
        OVERLAY("悬浮窗", true, "允许悬浮窗"),
        RUNTIME_PERMISSIONS("运行时权限", false, "打开应用权限"),
        USAGE_STATS("使用情况访问", false, "允许使用情况访问"),
        BATTERY_OPTIMIZATION("电池白名单", false, "加入电池白名单")
    }

    data class Item(
        val requirement: Requirement,
        val granted: Boolean,
        val detail: String
    ) {
        val actionLabel: String?
            get() = if (granted) null else requirement.actionLabel
    }

    data class Snapshot(
        val items: List<Item>
    ) {
        val readyForLaunch: Boolean
            get() = items.none { it.requirement.requiredForLaunch && !it.granted }

        val summaryText: String
            get() {
                val requiredItems = items.filter { it.requirement.requiredForLaunch }
                val recommendedItems = items.filterNot { it.requirement.requiredForLaunch }
                val requiredReady = requiredItems.count { it.granted }
                val recommendedReady = recommendedItems.count { it.granted }
                val requiredTotal = requiredItems.size
                val recommendedTotal = recommendedItems.size

                return when {
                    readyForLaunch && recommendedItems.all { it.granted } ->
                        "Agent 已进入完整就绪状态，核心权限和建议优化均已完成。"

                    readyForLaunch ->
                        "Agent 核心权限已就绪，建议优化已完成 $recommendedReady/$recommendedTotal。"

                    else ->
                        "Agent 核心权限已就绪 $requiredReady/$requiredTotal，仍需补齐后才能稳定进入对话页。"
                }
            }

        val detailText: String
            get() = items.joinToString("\n") { item ->
                val marker = if (item.granted) "[OK]" else "[ ]"
                "$marker ${item.requirement.title}: ${item.detail}"
            }

        fun nextActionableItem(): Item? {
            return items.firstOrNull { it.requirement.requiredForLaunch && !it.granted && it.actionLabel != null }
                ?: items.firstOrNull { !it.requirement.requiredForLaunch && !it.granted && it.actionLabel != null }
        }
    }

    fun inspect(context: Context): Snapshot {
        val runtimeMissing = runtimePermissionsMissing(context)

        return Snapshot(
            listOf(
                Item(
                    requirement = Requirement.ACCESSIBILITY,
                    granted = isAccessibilityServiceEnabled(context) && isAccessibilityServiceConnected(context),
                    detail = accessibilityDetail(context)
                ),
                Item(
                    requirement = Requirement.FILE_ACCESS,
                    granted = Environment.isExternalStorageManager(),
                    detail = if (Environment.isExternalStorageManager()) {
                        "已允许访问下载目录和外部文件。"
                    } else {
                        "未开启，静默安装和文件读写会受限。"
                    }
                ),
                Item(
                    requirement = Requirement.OVERLAY,
                    granted = Settings.canDrawOverlays(context),
                    detail = if (Settings.canDrawOverlays(context)) {
                        "已允许显示悬浮急停按钮。"
                    } else {
                        "未开启，运行中无法稳定显示紧急停止按钮。"
                    }
                ),
                Item(
                    requirement = Requirement.RUNTIME_PERMISSIONS,
                    granted = runtimeMissing.isEmpty(),
                    detail = if (runtimeMissing.isEmpty()) {
                        "相机和麦克风权限已到位。"
                    } else {
                        "仍缺少: ${runtimeMissing.joinToString("、") { permissionLabel(it) }}"
                    }
                ),
                Item(
                    requirement = Requirement.USAGE_STATS,
                    granted = isUsageStatsGranted(context),
                    detail = if (isUsageStatsGranted(context)) {
                        "已允许读取使用情况。"
                    } else {
                        "未开启，后续应用切换与前台状态分析能力会受限。"
                    }
                ),
                Item(
                    requirement = Requirement.BATTERY_OPTIMIZATION,
                    granted = isIgnoringBatteryOptimizations(context),
                    detail = if (isIgnoringBatteryOptimizations(context)) {
                        "已加入电池优化白名单。"
                    } else {
                        "未加入，系统可能在后台杀死长任务。"
                    }
                )
            )
        )
    }

    fun buildActionIntent(context: Context, requirement: Requirement): Intent? {
        val packageUri = Uri.parse("package:${context.packageName}")
        return when (requirement) {
            Requirement.ACCESSIBILITY ->
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

            Requirement.FILE_ACCESS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
                } else {
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                }

            Requirement.OVERLAY ->
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)

            Requirement.RUNTIME_PERMISSIONS ->
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)

            Requirement.USAGE_STATS ->
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

            Requirement.BATTERY_OPTIMIZATION ->
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
        }
    }

    private fun accessibilityDetail(context: Context): String {
        return when {
            isAccessibilityServiceEnabled(context) && isAccessibilityServiceConnected(context) ->
                "已开启并连接到 Andclaw 服务。"

            isAccessibilityServiceEnabled(context) ->
                "已授权，但系统尚未把服务连接起来。"

            else ->
                "未开启，Agent 无法读取屏幕和执行点击。"
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val targetComponent = ComponentName(
            context.packageName,
            "com.andforce.andclaw.AgentAccessibilityService"
        ).flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (splitter.next().equals(targetComponent, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun isAccessibilityServiceConnected(context: Context): Boolean {
        val targetComponent = ComponentName(
            context.packageName,
            "com.andforce.andclaw.AgentAccessibilityService"
        ).flattenToString()
        val accessibilityManager =
            context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        return accessibilityManager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { serviceInfo ->
                serviceInfo.resolveInfo.serviceInfo?.let { info ->
                    ComponentName(info.packageName, info.name).flattenToString()
                } == targetComponent
            }
    }

    private fun runtimePermissionsMissing(context: Context): List<String> {
        val permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        return permissions.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isUsageStatsGranted(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun permissionLabel(permission: String): String {
        return when (permission) {
            Manifest.permission.CAMERA -> "相机"
            Manifest.permission.RECORD_AUDIO -> "麦克风"
            else -> permission.substringAfterLast('.')
        }
    }
}
