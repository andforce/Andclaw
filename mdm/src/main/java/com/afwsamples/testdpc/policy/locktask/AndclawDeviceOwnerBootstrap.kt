package com.afwsamples.testdpc.policy.locktask

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.util.Log
import com.andforce.andclaw.DeviceAdminReceiver
import com.andforce.mdm.center.AppUtils

object AndclawDeviceOwnerBootstrap {
    private const val TAG = "AndclawBootstrap"

    data class Result(
        val appliedSteps: List<String>,
        val failures: List<String>
    ) {
        fun userSummary(): String {
            if (appliedSteps.isEmpty() && failures.isEmpty()) {
                return "没有可执行的自动修复项。"
            }

            return buildString {
                if (appliedSteps.isNotEmpty()) {
                    append("已检查并补齐: ${appliedSteps.joinToString("、")}")
                }
                if (failures.isNotEmpty()) {
                    if (isNotEmpty()) append("；")
                    append("失败: ${failures.joinToString("、")}")
                }
            }
        }
    }

    fun apply(context: Context): Result {
        val devicePolicyManager =
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return Result(emptyList(), listOf("DevicePolicyManager 不可用"))
        val adminComponent = DeviceAdminReceiver.getComponentName(context)
            ?: return Result(emptyList(), listOf("Device Owner 组件不可用"))

        val applied = mutableListOf<String>()
        val failures = mutableListOf<String>()

        runStep("自动授权策略", failures) {
            devicePolicyManager.setPermissionPolicy(
                adminComponent,
                DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT
            )
            applied += "自动授权策略"
        }

        runStep("运行时权限", failures) {
            AppUtils.enablePermission(context, context.packageName)
            applied += "运行时权限"
        }

        runStep("辅助功能白名单", failures) {
            val permitted = devicePolicyManager.getPermittedAccessibilityServices(adminComponent)
            if (permitted != null && !permitted.contains(context.packageName)) {
                devicePolicyManager.setPermittedAccessibilityServices(
                    adminComponent,
                    permitted + context.packageName
                )
            }
            applied += "辅助功能白名单"
        }

        return Result(applied, failures)
    }

    private fun runStep(
        name: String,
        failures: MutableList<String>,
        block: () -> Unit
    ) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "Bootstrap step failed: $name", t)
            failures += "$name(${t.message ?: "unknown"})"
        }
    }
}
