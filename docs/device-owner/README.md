# Device Owner 落地指南

本目录把实机上 `Andclaw` 单包 `Device Owner` 落地所需的文档和脚本整理回正式仓库，替代之前散落在临时目录里的 reset kit。

## 目录结构

- `docs/device-owner/README.md`
  - Device Owner 落地主入口。
- `docs/device-owner/xiaomi-miui.md`
  - 小米 / MIUI 现场踩坑与修复办法。
- `tools/device-owner/*.sh`
  - 通过 ADB 完成安装、激活、修权限、验收、重启复验和基线采集。

## 已验证链路

这套流程已经在实机上验证过以下结果：

- 单包包名为 `com.andforce.andclaw`
- `dpm list-owners` 返回 `com.andforce.andclaw/.DeviceAdminReceiver,DeviceOwner`
- 重启后 owner 状态保持
- 通过 `AgentDebugReceiver` 下发 `截一张当前屏幕并结束任务` 可以完整结束任务，并在 `Pictures/Andclaw/` 产出截图

## 仓库内约定

- 将签名后的单包 APK 放到 `out/device-owner/andclaw-single-owner-signed.apk`
- 或者在运行脚本时显式传入 APK 路径
- 或者通过环境变量 `ANDCLAW_DEVICE_OWNER_APK=/abs/path/to/app.apk` 覆盖默认路径
- 所有脚本输出默认都落到 `out/device-owner/`

以下产物不入仓库：

- keystore / `*.keystore` / `*.jks`
- 签名后的 APK 二进制
- 大体积录屏 / 演示视频
- 现场一次性的日志快照目录

## 手机侧最小动作

恢复出厂后，手机侧只需要做到这里：

1. 不登录任何账号，不恢复备份
2. 进入桌面
3. 打开开发者模式
4. 开启 USB 调试
5. 插回数据线，并允许当前电脑的 ADB 授权

完成后就可以交给脚本继续。

## 电脑侧执行顺序

先诊断当前状态：

```bash
tools/device-owner/00-diagnose-reset-state.sh
```

一键跑完整引导：

```bash
tools/device-owner/40-bootstrap-after-reset.sh
```

如需显式指定 APK：

```bash
tools/device-owner/40-bootstrap-after-reset.sh /abs/path/to/andclaw-single-owner-signed.apk
```

单步执行时推荐顺序：

```bash
tools/device-owner/10-provision-single-owner.sh
tools/device-owner/20-repair-single-owner-runtime.sh
tools/device-owner/30-verify-single-owner.sh
tools/device-owner/35-reboot-and-reverify.sh
```

如果需要保留一次完整的 agent 基线输出：

```bash
tools/device-owner/50-capture-agent-baseline.sh
```

## 核验标准

至少应满足：

- `tools/device-owner/00-diagnose-reset-state.sh` 中 owner 正确
- `tools/device-owner/30-verify-single-owner.sh` 中 smoke test 输出 `[system]: Finished.`
- 最新截图出现在 `/sdcard/Pictures/Andclaw/`

## 小米 / MIUI 注意事项

如果设备是小米 / MIUI，请先看 [xiaomi-miui.md](./xiaomi-miui.md)：

- `adb install` 可能被 `USB安装提示` 阻断
- `dpm set-device-owner` 可能被系统账号拦截，即使刚恢复出厂

这些问题都已经有可复现的处理办法。
