package com.afwsamples.testdpc.policy.locktask

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.afwsamples.testdpc.DevicePolicyManagerGateway
import com.afwsamples.testdpc.DevicePolicyManagerGatewayImpl
import com.afwsamples.testdpc.databinding.ActivitySetupKioskLayoutBinding
import com.afwsamples.testdpc.policy.locktask.viewmodule.KioskViewModule
import com.andforce.andclaw.DeviceAdminReceiver
import com.andforce.mdm.center.AppUtils
import com.andforce.mdm.center.DeviceStatusViewModel
import com.base.services.ITgBridgeService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

open class SetupKioskModeActivity : AppCompatActivity() {
    private var mAdminComponentName: ComponentName? = null
    private var mDevicePolicyManager: DevicePolicyManager? = null
    private var mPackageManager: PackageManager? = null
    private var binding: ActivitySetupKioskLayoutBinding? = null
    private var mDevicePolicyManagerGateway: DevicePolicyManagerGateway? = null
    private var mUserManager: UserManager? = null
    private var connectivityManager: ConnectivityManager? = null
    private var usbEnableDebugAlertDialog: AlertDialog? = null
    private var latestReadiness: AndclawAgentReadiness.Snapshot? = null

    private val kioskViewModule: KioskViewModule by viewModel()
    private val deviceStatusViewModel: DeviceStatusViewModel by inject()
    private val tgBridgeService: ITgBridgeService by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mDevicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        mPackageManager = packageManager
        mUserManager = getSystemService(UserManager::class.java)

        binding = ActivitySetupKioskLayoutBinding.inflate(layoutInflater)
        binding?.let { binding ->
            setContentView(binding.root)

            binding.setupNetwork.setOnClickListener {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }

            binding.setupDeviceOwner.setOnClickListener {
                if (kioskViewModule.deviceOwnerStateFlow.value) {
                    showRemoveDeviceOwnerDialog()
                } else {
                    showDeviceOwnerInstructions()
                }
            }

            binding.permissionPrimaryAction.setOnClickListener {
                openNextPermissionAction()
            }

            binding.permissionAutoFix.setOnClickListener {
                runDeviceOwnerAutoFix(userInitiated = true)
            }

            binding.refreshAgentPermissions.setOnClickListener {
                refreshAgentReadiness(showToast = true)
            }

            binding.openChatActivity.setOnClickListener {
                openChatActivity()
            }

            binding.openTestActivity.setOnClickListener {
                openTestActivity()
            }

            binding.openAiSettings.setOnClickListener {
                openAiSettings()
            }
        }

        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        lifecycleScope.launch {
            connectivityManager?.let { manager ->
                deviceStatusViewModel.observeNetworkState(manager).collect { isConnected ->
                    binding?.apply {
                        networkStatus.text = if (isConnected) "已连接" else "未连接"
                        setupNetwork.visibility = if (!isConnected) View.VISIBLE else View.GONE
                    }
                }
            }
        }

        if (deviceStatusViewModel.isNetworkConnected(connectivityManager)) {
            binding?.apply {
                networkStatus.text = "已连接"
                setupNetwork.visibility = View.GONE
            }
        }

        lifecycleScope.launch {
            kioskViewModule.deviceOwnerStateFlow.collect { isDeviceOwner ->
                if (isDeviceOwner) {
                    mAdminComponentName = DeviceAdminReceiver.getComponentName(this@SetupKioskModeActivity)
                    mDevicePolicyManagerGateway =
                        DevicePolicyManagerGatewayImpl(
                            mDevicePolicyManager!!,
                            mUserManager!!,
                            mPackageManager!!,
                            getSystemService(LocationManager::class.java),
                            mAdminComponentName
                        )
                    usbEnableDebugAlertDialog?.dismiss()
                    tgBridgeService.startBridge()
                    runDeviceOwnerAutoFix(userInitiated = false)
                } else {
                    tgBridgeService.stopBridge()
                }

                binding?.apply {
                    deviceOwnerStatus.text = if (isDeviceOwner) "已开启" else "未开启"
                    setupDeviceOwner.text = if (isDeviceOwner) "移除设备管理员" else "设置设备管理员"
                    setupDeviceOwner.visibility = View.VISIBLE
                    permissionAutoFix.visibility = if (isDeviceOwner) View.VISIBLE else View.GONE
                }

                refreshAgentReadiness()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (kioskViewModule.deviceOwnerStateFlow.value) {
            runDeviceOwnerAutoFix(userInitiated = false)
        }
        if (maybeOpenChatOnLauncherEntry()) {
            return
        }
        refreshAgentReadiness()
    }

    private fun maybeOpenChatOnLauncherEntry(): Boolean {
        val launchIntent = intent ?: return false
        val isLauncherEntry = launchIntent.action == Intent.ACTION_MAIN &&
            launchIntent.hasCategory(Intent.CATEGORY_LAUNCHER)
        if (!isLauncherEntry || !hasCoreAgentPrerequisites()) {
            return false
        }
        openChatActivity(finishAfterLaunch = true)
        return true
    }

    private fun hasCoreAgentPrerequisites(): Boolean {
        return AndclawAgentReadiness.inspect(this).readyForLaunch
    }

    private fun refreshAgentReadiness(showToast: Boolean = false) {
        val snapshot = AndclawAgentReadiness.inspect(this)
        latestReadiness = snapshot
        val nextAction = snapshot.nextActionableItem()
        binding?.apply {
            agentPermissionSummary.text = snapshot.summaryText
            agentPermissionDetails.text = snapshot.detailText
            permissionPrimaryAction.text = nextAction?.actionLabel ?: "全部就绪"
            permissionPrimaryAction.isEnabled = nextAction != null
        }
        if (showToast) {
            Toast.makeText(this, snapshot.summaryText, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openNextPermissionAction() {
        val target = latestReadiness?.nextActionableItem()
        if (target == null) {
            Toast.makeText(this, "当前没有待处理的权限项。", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = AndclawAgentReadiness.buildActionIntent(this, target.requirement)
            ?: run {
                Toast.makeText(this, "没有可用的设置入口。", Toast.LENGTH_SHORT).show()
                return
            }

        val fallbackIntent = when (target.requirement) {
            AndclawAgentReadiness.Requirement.FILE_ACCESS ->
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

            AndclawAgentReadiness.Requirement.BATTERY_OPTIMIZATION ->
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

            else -> null
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            try {
                if (fallbackIntent != null) {
                    startActivity(fallbackIntent)
                } else {
                    throw e
                }
            } catch (inner: Exception) {
                Toast.makeText(
                    this,
                    "打开设置失败: ${inner.message ?: e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun runDeviceOwnerAutoFix(userInitiated: Boolean) {
        if (!kioskViewModule.deviceOwnerStateFlow.value) {
            if (userInitiated) {
                Toast.makeText(this, "需要先开启设备管理员。", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val result = AndclawDeviceOwnerBootstrap.apply(this)
        refreshAgentReadiness()
        if (userInitiated) {
            Toast.makeText(this, result.userSummary(), Toast.LENGTH_LONG).show()
        }
    }

    private fun showRemoveDeviceOwnerDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("移除设备管理员")
            .setMessage("确定要移除设备管理员吗？移除后需要重新设置。")
            .setPositiveButton("确定") { _, _ ->
                AppUtils.showAllHideApps(this)

                mDevicePolicyManagerGateway?.clearDeviceOwnerApp(
                    {
                        Toast.makeText(this, "设备管理员已移除", Toast.LENGTH_SHORT).show()
                        kioskViewModule.updateDeviceOwnerState(false)
                    },
                    { e: Exception? ->
                        Toast.makeText(this, "移除设备管理员失败: $e", Toast.LENGTH_SHORT).show()
                    }
                )
            }
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .show()
    }

    private fun showDeviceOwnerInstructions() {
        val componentName = DeviceAdminReceiver.getReceiverComponentName(this).flattenToShortString()
        usbEnableDebugAlertDialog = MaterialAlertDialogBuilder(this)
            .setTitle("设置设备管理员")
            .setMessage(
                "请按照以下步骤操作：\n\n" +
                    "1. 打开「设置  >  关于手机」\n" +
                    "2. 连续点击「版本号」7 次开启开发者选项\n" +
                    "3. 在「开发者选项」中开启 USB 调试\n" +
                    "4. 连接电脑，在终端执行以下命令：\n\n" +
                    "adb shell dpm set-device-owner $componentName"
            )
            .setPositiveButton("打开开发者选项") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            }
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .show()
    }

    private fun openAiSettings() {
        startActivity(Intent(this, AiSettingsActivity::class.java))
    }

    private fun openChatActivity(finishAfterLaunch: Boolean = false) {
        val intent = Intent().setClassName(packageName, "com.andforce.andclaw.ChatHistoryActivity")
        try {
            startActivity(intent)
            if (finishAfterLaunch) {
                finish()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "启动对话页失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openTestActivity() {
        val intent = Intent().setClassName(packageName, "com.andforce.andclaw.ActionTestActivity")
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "启动测试页失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
