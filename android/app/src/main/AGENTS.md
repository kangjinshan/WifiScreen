# Android 运行入口与资源指南

> 更新：2026-10-10。位置：`android/app/src/main/`。

## 1. 概述

本目录决定系统怎样启动接收端、允许哪些设备安装、声明哪些能力。实现入口见 [核心代码](java/com/kanayama/wifiscreen/AGENTS.md)，版本 / 签名见 [APK 模块](../../AGENTS.md)。

## 2. 核心组件

- `AndroidManifest.xml`：唯一公开 Activity 为 `.MainActivity`，横屏、singleTop，支持普通 Launcher 与 Leanback Launcher。
- `application` 使用 `AppTheme`、`app_icon`、`app_banner`，关闭备份；改应用名或包名不是普通资源替换。
- 网络权限支持局域网、Wi-Fi 与多播发现；`LegacyReceiver` 无需 Root 或设备 ADB 即可接收。
- `CONFIGURE_WIFI_DISPLAY` 是系统授予权限，仅供 `DeviceProfile.hasWfdPermission` / `NativeReceiver.start` 查询与实验使用，不能绕过权限判断。
- Wi-Fi Direct、Leanback、触屏均为可选 feature；改为 required 会改变 APK 可安装设备范围。
- `res/values/styles.xml` 定义大屏主题，`res/drawable` 和 `res/drawable-nodpi` 放图标与背景；`assets/codec-test` 由 `CodecBenchmark.load` 读取。

## 3. 设计约定

新增 exported 组件或权限前确认业务调用链，避免把内部接收状态暴露成可写控制接口。应用需要保持前台：`onPause` 取消测试，`onStop` 停止接收服务；不要依靠新增后台服务悄悄改变这一行为。

资源内容没有独立业务逻辑，直接由本层及 APK 指南覆盖；不要将 Markdown 放进资源打包目录。极米品牌出现在文档中的适用范围，不代表官方签名、合作认证或所有机型均经实测。

## 4. 使用示例或典型调用路径

电视启动器 → `MainActivity` → `buildScreen` 创建 SurfaceView → `onResume` 开启网络恢复与发现。修改启动方式或资源后，确认安装范围、桌面图标 / 横幅、遥控器焦点和返回行为；已有有效证据可复用，涉及清单与资源的改变才重新构建相应 APK。
