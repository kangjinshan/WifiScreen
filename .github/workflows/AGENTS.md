# GitHub APK 发布工作流指南

> 更新：2026-10-10。位置：`.github/workflows/`。

## 1. 概述

`android.yml` 构建、验证并交付签名 APK。触发器是 `v*` 标签或手动 `workflow_dispatch`；普通 main 推送不会自动创建 Release，也不会上传私有 TV App Store。

## 2. 核心组件

- `actions/checkout@v5`：检出指定 `release_tag` 或当前 ref，完整历史用于校验标签提交。
- `actions/setup-java@v5` / `gradle/actions/setup-gradle@v4`：JDK 17 与 Gradle 缓存；SDK 阶段只安装缺少的 API 35 / build-tools。
- `Prepare release signing`：读取 GitHub secrets，写临时 keystore / 密码文件与 `android/signing.properties`；不输出密钥。
- `Test, lint and build signed APK`：运行 JVM 测试、Release Lint、Release 构建。
- `Verify and package APK`：从实际 APK 读取包名 / 版本，拒绝 debuggable，核对标签与源码，生成 SHA256SUMS / build-info.json。
- `Upload downloadable APK`：Artifacts 保存 30 天；仅当有合法 release_tag 时执行 `Publish GitHub Release`。
- `Publish GitHub Release`：使用 `../release-notes.md`；已有 Release 必须完整且身份一致，不覆盖已有资产。相关回归在 `../../tests/test_release_workflow.py`。
- `Remove signing files`：always 执行；失败阶段的构建 / 测试报告另存 artifact。

## 3. 设计约定

版本以 APK 内 `versionName` / `versionCode` 为准，标签必须为 `v<versionName>` 且提交一致。相同来源重构建可有不同字节，已有资产按发布元数据校验，不能静默替换或用一次失败查询判定“不存在”。修改 heredoc 或步骤名称须同步 Python 回归抽取逻辑。

正式发布沿用已有证书。不要为了普通 main 提交创建标签、Release 或重复构建；按用户当次授权选择操作。TV App Store 是独立发布目标，复用同一已验证 APK，上传后下载比对，不用此工作流伪装商店发布成功。

## 4. 使用示例或典型调用路径

手动不填 `release_tag` → 构建 Artifacts；手动填写已有标签 → 检出标签、核对源码 / 版本、创建或验证对应 Release。推送版本标签 → 同一路径自动执行。只改本目录文档不会触发 APK 工作流。
