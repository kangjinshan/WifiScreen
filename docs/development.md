# WifiScreen 开发说明

普通用户的下载、安装和使用方法见 [README](../README.md)。本文介绍构建、发布与接收链路。

## 本地构建

需要 **JDK 17、Android SDK 35**，使用仓库内的 Gradle Wrapper：

```bash
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

调试 APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`。

发布构建先将 `android/signing.properties.example` 复制为 `android/signing.properties`，配置密钥文件、密码文件和 alias：

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

Release APK 位于 `android/app/build/outputs/apk/release/app-release.apk`。签名配置、密码文件、密钥、APK 和设备日志不入库。不同证书签名的 APK 无法直接覆盖安装。

版本号与版本码在 [app/build.gradle.kts](../android/app/build.gradle.kts) 中维护。

## GitHub 构建与发布

工作流见 [Build Android APK](../.github/workflows/android.yml)。

- 推送 `v版本号` 标签，会运行单元测试、Lint 与 Release 构建，并发布 APK 到 GitHub Releases。
- 在 **Actions → Build Android APK → Run workflow** 手动运行且不填写 `release_tag` 时，只构建 Artifacts。
- 重试已有标签的发布时，在最新主干工作流中填写 `release_tag`。工作流会检出标签源码，并校验源码提交与标签一致。
- 标签必须与 APK 的 `versionName` 一致，例如 `v0.2.0` 对应 `0.2.0`。
- 已发布版本重试时，会校验原 APK 的文件、源码、版本和签名。匹配后保留原文件；不完整或不一致则报错，不自动覆盖。

Fork 后使用自己的签名时，在仓库 **Settings → Secrets and variables → Actions** 配置：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 发布 keystore 的 Base64 内容 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 签名 alias |

发布产物包含 APK、`SHA256SUMS` 和 `build-info.json`，可核对 SHA-256、文件大小、签名指纹与源码提交。

## 代码结构

| 文件 | 职责 |
| --- | --- |
| `MainActivity.kt` / `CinemaUi.kt` | 电视界面、焦点、遥控器菜单与设置 |
| `ReceiverRecovery.kt` / `StreamStallWatchdog.kt` | 前台网络恢复与异常会话判断 |
| `LegacyReceiver.kt` / `LegacyDiscovery.kt` | 当前启用的接收会话、固定端口与 IPv4 mDNS 发现 |
| `LegacyVideo.kt` / `VideoInputQueue.kt` | 硬件视频解码、输入背压与显示 |
| `LegacyAudio.kt` / `AudioRtpQueue.kt` | 音频解码、播放与 RTP 排队 |
| `PlaybackTiming.kt` / `AudioSampleClock.kt` | 媒体时间、缓冲与播放节奏 |
| `VideoViewport.kt` / `ReceiverName.kt` | 画面几何、名称校验与协议转义 |

上述文件位于 `android/app/src/main/java/com/kanayama/wifiscreen/`。`NativeReceiver` / `WfdSession` 为早期 Miracast 实验代码，当前主界面使用 `LegacyReceiver`。

## 接收链路与兼容范围

- 接收端最低 Android API 21。
- 当前主要针对小米 / Redmi 系统投屏验证，不代表所有 Google Cast、Miracast、AirPlay 或其他品牌发送端均兼容。
- IPv4 mDNS 设备发现，HTTP / RTSP 会话，视频使用 TCP，音频使用 RTP UDP。
- 固定端口：TCP **47110**（控制与诊断）、TCP **47111**（视频）、UDP **47112**（音频）。
- 接收端身份由本应用生成；改名保持设备身份及固定端口。
- 应用退到后台或退出时停止接收服务。

基础音画接收曾在极米 Z6X Android 9 上实机验证。0.2.0 新增功能已完成模拟器回归，不能据此宣称已完成目标投影仪的音画听感、延迟和显示边缘验收。测试方法与覆盖范围见 [测试说明](testing.md)。

工程不附带其他投屏应用的 SDK 或二进制，不修改其他应用的授权。依赖见 [第三方组件说明](../THIRD_PARTY_NOTICES.md)。

## 音视频解码与缓冲

视频使用 Android `MediaCodec`。Z6X 曾验证的解码器为 `OMX.MS.AVC.Decoder`（MStar H.264 硬件解码器），实际选择取决于设备提供的解码器。音频使用 AAC-ELD 解码与 `AudioTrack` 播放，当前接收格式为 44.1kHz 双声道。

0.1.4 起使用 **20 ms 额外缓冲目标**：音频线程优先处理，解码器繁忙时保留 AAC 帧，在缓冲预算内恢复 RTP 乱序包并过滤重复包。视频按媒体时间安排显示，补偿相同的新增延迟；迟到画面可以跳过显示，参考帧仍完整解码。

20 ms 叠加在设备原有的 AudioTrack 缓冲之上，并非整个投屏链路的延迟。链路还包含手机采集与编码、网络、电视解码和显示。UDP 音频由手机发送端协商，接收端不能单方面改用 TCP。短缺口保留媒体时长并平滑边界，严重丢包仍需改善网络。

## 诊断与实时速率

应用在前台时提供固定只读地址：

```text
http://电视IP:47110/diagnostics
http://电视IP:47110/status
```

`/diagnostics` 提供文本报告，`/status` 提供 JSON。IP 不变时地址不变；报告包含设备、接收、解码、队列、错误以及音频欠载、乱序与丢包、抖动和显示跳帧等指标。

实时速率每秒刷新：

- FPS 统计实际送去显示的帧；跳过显示的帧不计入。
- 接收速度统计音视频数据，包括媒体协议头，不包括 TCP/IP 开销。
- 使用十进制 KB/s、MB/s。
- 手机静止画面可能降低帧率；低帧率或长时间没有新画面本身不等于卡死。

应用不将投屏音视频保存到文件。公开提交前应检查诊断附件，避免把用户设备信息、局域网地址或原始日志混入仓库。
