# WifiScreen · 投屏助手

[![Build Android APK](https://github.com/kangjinshan/WifiScreen/actions/workflows/android.yml/badge.svg)](https://github.com/kangjinshan/WifiScreen/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

让 Android 投影仪、电视接收手机系统自带的整屏投屏。打开投影端应用，在手机投屏列表中选择 **WifiScreen 投影**，即可传输手机画面与声音。

无需在手机安装配套发送端，无需投影仪 ADB 或 Root。应用不设置投屏时长限制。目前主要针对 **小米 / Redmi 系统投屏 + 极米 Z6X（Android 9）** 验证，其他机型的系统投屏协议可能不同。

## 下载与安装

- **[下载最新 APK](https://github.com/kangjinshan/WifiScreen/releases/latest)**：在 Assets 中下载 `wifiscreen-版本号.apk`。
- **[GitHub Actions 构建记录](https://github.com/kangjinshan/WifiScreen/actions/workflows/android.yml)**：每次版本发布会在 GitHub 直接编译，提供 APK、SHA-256 和源码提交信息。
- 也可在 [TV App Store](https://tvstore.jinshanweb.com/app-detail.html?id=9) 安装「投屏助手（开发版）」。

将 APK 通过 U 盘、局域网文件传输或电视浏览器安装到投影仪 / 电视。应用包名为 `com.kanayama.wifiscreen`。官方 GitHub 构建沿用发布签名，可与 TV App Store 版本相互覆盖更新。

## 开始投屏

1. 手机和投影仪连接同一局域网，使用可互相访问的 Wi-Fi。
2. 在投影仪打开 **投屏助手**，看到「等待手机投屏」后保持应用在前台。
3. 在小米 / Redmi 手机上，下拉控制中心打开系统 **投屏**，选择 **WifiScreen 投影**。
4. 连接后显示完整手机画面，并播放可被手机系统捕获的声音。横竖屏按比例显示。
5. 在手机结束投屏，或按投影遥控器 **返回**，即可回到等待连接页；等待页再次按返回退出应用。

模拟器测试设备使用 **WifiScreen Emulator Test** 名称，避免与真实投影混淆。

## 固定诊断地址

0.1.3 起不再生成随机端口和验证码。应用在前台时，局域网内可读取：

```text
完整诊断：http://投影仪IP:47110/diagnostics
JSON 状态：http://投影仪IP:47110/status
```

例如投影仪 IP 为 `192.168.1.100`，完整地址就是 `http://192.168.1.100:47110/diagnostics`。投影首页会显示实际 IP；「设备信息 / 诊断 → 固定诊断地址」也可查看链接。

地址在 IP 不变时保持固定。报告是只读的，包含设备信息、接收帧数、视频输出、解码器、队列状态与最近错误；退出应用后停止提供。画面与音频不会保存到文件。

## 常见问题

**手机找不到设备**

保持应用在投影前台，确认两端处于同一局域网，再关闭并重新打开手机投屏列表。访客网络、设备隔离或禁止组播会影响发现与连接。

**能发现，但连接失败**

在同一局域网访问固定诊断地址。发现设备不代表两端可以建立连接；若手机无法访问投影 IP，可分别重新连接两端 Wi-Fi 后重试。系统投屏协议也因手机品牌而异。

**有声音，画面黑屏或停住**

先更新到 0.1.3 或更新版本。本版修复了解码缓冲拥塞时丢失参考帧、等待关键帧而停止输出的问题。若仍出现，请提交固定诊断报告，并说明手机型号、应用场景及是否切换横竖屏。

**视频是否使用硬解**

视频通过 Android `MediaCodec` 解码。在 Z6X 实机上使用 `OMX.MS.AVC.Decoder`（MStar H.264 硬件解码器）；具体选择取决于设备提供的解码器。音频使用 AAC-ELD 解码和 `AudioTrack` 播放，当前接收格式为 44.1kHz 双声道。

## 兼容范围与实现

- Android 接收端最低 API 21；已在极米 Z6X 的 Android 9 和 Android TV 模拟器上验证。
- 通过 IPv4 mDNS 发现设备，使用独立实现的兼容 LAN 接收链路、HTTP / RTSP 会话、视频 TCP 与音频 RTP UDP。
- 固定端口：TCP **47110**（控制 / 诊断）、TCP **47111**（视频）、UDP **47112**（音频）。
- 当前验证重点是小米 / Redmi 系统投屏；不能由「Android 投屏」名称推断所有 Google Cast、Miracast 或 iOS 设备均兼容。
- `NativeReceiver` / `WfdSession` 是早期 Miracast 实验代码，当前主界面使用 `LegacyReceiver`。接收端身份由本应用生成。

工程不附带其他投屏应用的 SDK 或二进制，不修改其他应用的授权。开源依赖见 [第三方组件说明](THIRD_PARTY_NOTICES.md)，验证方法见 [测试说明](docs/testing.md)。

## 本地构建

需要 **JDK 17、Android SDK 35**，使用仓库内 Gradle Wrapper：

```bash
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

调试 APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`。

发布构建先将 `android/signing.properties.example` 复制为 `android/signing.properties`，配置本机密钥文件、密码文件和 alias：

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

签名配置、密码文件、密钥、APK、设备日志均不入库。自行签名的构建不能直接覆盖不同证书签名的已安装版本。

## 在 GitHub 直接编译

仓库已配置 [Build Android APK](.github/workflows/android.yml)：

- 推送 `v版本号` 标签，例如 `v0.1.3`，会自动运行测试、Lint、Release 构建，并把 APK 发布到 GitHub Releases。
- 在 **Actions → Build Android APK → Run workflow** 可手动编译当前分支；完成后从该次运行的 Artifacts 下载。
- 标签版本必须与 APK 的 `versionName` 一致。版本号和版本码在 `android/app/build.gradle.kts` 中维护。

Fork 后使用自己的签名时，在仓库 **Settings → Secrets and variables → Actions** 配置：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 发布 keystore 的 Base64 内容 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 签名 alias |

构建产物包含 `SHA256SUMS` 和 `build-info.json`，可核对 APK 的版本、签名指纹、大小和对应源码提交。

## 许可证

本项目独立代码采用 [MIT License](LICENSE)。第三方组件遵循各自许可证。
