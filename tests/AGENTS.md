# Python 回归测试指南

> 更新：2026-10-10。位置：`tests/`。

## 1. 概述

验证公开 Python 探针和 GitHub 工作流中的发布逻辑，避免握手差异、误删首个关键帧或错误覆盖 Release。与 `android/app/src/test` 中的 Kotlin 测试独立，不代替设备音画验收。

## 2. 核心组件

- `test_legacy_probe.py` 的 `ProbeTest`：在临时日志 / loopback 服务验证 `event` 字段、`POST /stream` 单向转换、分片读取和首 IDR 解码。
- `test_first_idr_is_retained_and_decoded`：使用 FFmpeg 生成独立 AVC 流及 AES 加密输入，确认 30 帧输出；无 FFmpeg 时显式 skip，不能算作通过了解码验证。
- `test_release_workflow.py` 的 `ReleaseWorkflowTest`：抽取 `.github/workflows/android.yml` 内 `Publish GitHub Release` 的 Python 代码执行，而不是复制一份实现。
- `run_publish` 用临时 `dist/` 与 mocked `gh` 调用检查首次发布、幂等、缺资产、草稿、源码 / 签名 / 包名不同、大小 / 哈希不一致与 API 失败；不会访问真实 Release。
- `WORKFLOW`、`STEP`、`SOURCE` 依赖实际工作流步骤名称和 heredoc 边界；修改工作流时同步这些抽取位置。

## 3. 设计约定

使用 Python unittest，FFmpeg / PyCryptodome 仅供探针集成用例。测试临时服务必须 shutdown / close / join，不占用正式接收端。不要用真实凭证、用户音频或实际 GitHub 发布操作代替 mock。

发布测试故意允许“相同源码重新构建产生不同 APK 字节”：应按源码、包名、版本、证书和已发布资产记录校验，不能自动覆盖旧 APK。不得将 API 报错当作 Release 不存在。

## 4. 使用示例或典型调用路径

```bash
python3 -m unittest discover -s tests -p test_release_workflow.py -v
python3 -m unittest discover -s tests -p test_legacy_probe.py -v
```

修改发布工作流时跑前者，修改旧接收探针时跑后者。仅更新 README / AGENTS 或上传已验证版本时，不重新运行不相关设备测试；有新的失败或构建输入变化才补对应验证。
