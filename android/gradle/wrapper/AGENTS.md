# Gradle Wrapper 层开发指南

> 更新：2026-10-10。位置：`android/gradle/wrapper/`。

## 1. 概述

固定本地与 CI 使用的 Gradle 发行版本，避免开发机全局 Gradle 版本改变 APK 构建行为。本目录不处理业务代码。

## 2. 核心组件

- `gradle-wrapper.properties` 的 `distributionUrl` 指向 Gradle 8.2 二进制发行包。
- `gradle-wrapper.jar` 是 Wrapper 启动组件，不是投屏 SDK；不要手工修改二进制。
- `networkTimeout` 控制下载等待，`validateDistributionUrl` 保留 URL 校验。
- `distributionBase` / `distributionPath` 与 `zipStoreBase` / `zipStorePath` 将缓存放在 `GRADLE_USER_HOME` 下。
- 上游脚本为 `../../gradlew`、`../../gradlew.bat`；AGP / Kotlin 版本定义在 `../../build.gradle.kts`。

## 3. 设计约定

升级必须来自官方 Wrapper，并一起检查 AGP、Kotlin、JDK 兼容性。缓存、下载日志或下载失败的 HTML 文件不能替换 JAR。不要把个人缓存路径或凭证写进分发 URL。

## 4. 使用示例或典型调用路径

`android/gradlew` → Wrapper JAR → 指定 Gradle → `:app` 构建任务。升级后检查 `./gradlew --version`，再执行受影响的测试、Lint 与构建；普通业务提交无需改动 Wrapper。
