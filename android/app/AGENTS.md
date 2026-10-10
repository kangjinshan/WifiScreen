# APK 模块开发指南

> 更新：2026-10-10。位置：`android/app/`。

## 1. 概述

生成安装在极米投影仪等 Android 大屏上的接收端 APK。`build.gradle.kts` 管理版本、签名、平台与依赖；[运行入口](src/main/AGENTS.md)、[核心代码](src/main/java/com/kanayama/wifiscreen/AGENTS.md)、[JVM 测试](src/test/java/com/kanayama/wifiscreen/AGENTS.md) 分别维护。

## 2. 核心组件

- `build.gradle.kts`：包名 `com.kanayama.wifiscreen`，最低 API 21 / compileSdk 35 / targetSdk 34；当前版本 0.3.1、versionCode 17。以后以此文件和实际 APK 为准。
- AndroidX Core / AppCompat 构建界面；`MainActivity` / `CinemaUi` 使用原生 Views 与 DPAD 焦点。
- `LegacyReceiver` / `LegacyVideo` / `LegacyAudio` 使用平台网络、MediaCodec 与 AudioTrack；JmDNS 提供发现。
- `LelinkPairing` / `LelinkVideoCipher` 使用标准密码原语，Bouncy Castle 1.80；不要用研究目录的厂商二进制代替。
- Media3 为实验 WFD 路径依赖，不能因依赖存在就宣称 Google Cast / Miracast 通用兼容。
- `src/main/assets/codec-test/{h264,h265}.mp4` 是合成的 1080p30 片段，由 `tools/generate_codec_test_clips.py` 生成；两种素材大小不代表等画质压缩比。
- `src/main/res/` 是主题、图标、横幅、背景；`src/test/resources/lelink-v2.properties` 是合成测试密钥生成的独立协议向量，不是凭证。

## 3. 设计约定

资源与测试向量是静态内容，由此文维护来源，不在 assets / res 中新增 AGENTS.md。版本码必须来自构建配置及 APK，不能从日期或语义版本推算。签名配置仅从父工程的本地文件读取；不允许以 Debug APK 冒充发布包。

升级依赖、修改打包资源或构建配置会改变 APK 输入，需相应验证。Kotlin / Java 目录中的文档不是编译源；纯文档更新可复用当前已验收 APK。图片修改还要检查电视距离辨识度、背景与焦点对比，但不能把新图片带来的差异误当音视频功能回归。

## 4. 使用示例或典型调用路径

`MainActivity.onCreate` → `LegacyReceiver`；单元测试进入 `:app:testDebugUnitTest`，Release 检查进入 `:app:lintRelease` / `:app:assembleRelease`。发布前读取 APK 的实际包名、版本码和证书，确认与已验证源码一致；上传后下载比较 SHA-256 与大小。公开描述保留极米 Z6X 的既有基础实测与新版 HyperOS 模拟器验证的区别。
