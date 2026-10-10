# Android 工程层开发指南

> 更新：2026-10-10。位置：`android/`。

## 1. 概述

本层定义投屏助手的构建环境和依赖解析；实际接收逻辑在 [app](app/AGENTS.md)，入口为 `MainActivity` / `LegacyReceiver`。只有一个 `:app` 模块，没有独立服务端或手机发送 App。

## 2. 核心组件

- `build.gradle.kts`：Android Gradle Plugin 8.2.0、Kotlin 插件 1.9.22。
- `settings.gradle.kts`：Google / Maven Central / Gradle Plugin Portal 仓库与 `:app` 注册，禁止项目私自添加依赖仓库。
- `gradle.properties`：AndroidX、Kotlin 风格和构建 JVM 参数。
- `gradlew` / `gradlew.bat`：生成的官方 Wrapper 入口；版本在 [gradle/wrapper](gradle/wrapper/AGENTS.md)。
- `signing.properties.example`：签名文件路径、密码文件路径、alias 的占位示例。真实 `signing.properties` 与证书必须保持本地忽略。

## 3. 设计约定

使用 JDK 17 与 SDK 35；具体包名、最低 API、目标 API、版本和依赖在 `app/build.gradle.kts`。升级 AGP / Kotlin / Wrapper 要检查三者兼容性，不能只改一个版本号。不要打印签名配置或密码；GitHub Actions 在临时目录生成签名文件并在结束后清理。

APK 由 `app/build/outputs/apk/` 产生。`build/`、`.gradle/` 和本地环境路径不是源码。文档不进入 assets / res；仅文档改动复用已验证产物，避免造成不必要的重新构建和签名差异。

## 4. 使用示例或典型调用路径

```bash
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
# 未配置发布签名时，仅做本地调试：
./gradlew :app:assembleDebug
```

构建异常先区分 JDK、SDK、依赖下载、签名和源码编译阶段；商店失败不应从构建阶段重跑。更改目录结构时同步本指南与根目录导航。
