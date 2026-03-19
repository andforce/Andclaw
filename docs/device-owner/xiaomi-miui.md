# Xiaomi / MIUI 现场记录

以下问题是在小米设备上做单包 `Device Owner` 落地时真实遇到并解决过的。

## 1. `adb install` 被 `USB安装提示` 卡住

### 现象

- `adb install -r -d ...` 卡住或失败
- 手机弹出系统对话框：
  - `USB安装提示`
  - `正在通过USB安装此应用，是否继续?`

### 处理办法

- 直接在手机上点 `继续安装`
- 如果是无人值守工装场景，可以临时用 ADB 点击该按钮，但坐标依赖机型分辨率

本次验证设备上，`继续安装` 的按钮中心大约在 `(320, 2080)`。这个坐标只应视为现场样例，不能直接当成通用值。

## 2. `dpm set-device-owner` 提示已有账号

### 现象

即使恢复出厂后，仍可能报错：

```text
Not allowed to set the device owner because there are already some accounts on the device.
```

### 根因

小米系统账号仍然占据了 user 0 的 account state，导致 Android 拒绝设置 `Device Owner`。

### 已验证修复

先临时卸载 user 0 上的小米账号包：

```bash
adb shell pm uninstall --user 0 com.xiaomi.account
```

确认 `Accounts: 0` 后，再执行：

```bash
adb shell dpm set-device-owner com.andforce.andclaw/.DeviceAdminReceiver
```

设置成功后，再把系统包恢复回来：

```bash
adb shell cmd package install-existing com.xiaomi.account
```

### 注意

- 这是工装阶段的补救动作，只建议在刚恢复出厂、尚未登录账号的设备上使用
- 恢复系统包后，本次验证里账号数量仍保持 `0`，并未影响已经建立的 `Device Owner`
- 若后续手机厂商 ROM 行为变化，应以 `tools/device-owner/00-diagnose-reset-state.sh` 的实时输出为准
