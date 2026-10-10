# 协议与合成素材工具指南

> 更新：2026-10-10。位置：`tools/`。

## 1. 概述

通过原创合成音画定位接收端问题，或生成应用内编码测试素材；不采集、导出用户投屏内容。本目录工具可能监听局域网或向接收端建立投屏，使用前确认目标是当前任务的空闲测试设备。

## 2. 核心组件

- `check_video_codecs.py`：`run_case` 通过真实控制 / 视频 TCP 端口发送 AVC、hvcC HEVC、旧包装 HEVC；`config`、`packet`、`response` 负责协议边界。验证输出数、尺寸、错误及重复配置不重建。
- `generate_codec_test_clips.py`：`main` 调用 FFmpeg / libx264 / libx265，将相同 testsrc2 动态画面编码为 1080p30、2 秒、无 B 帧素材，写入 `android/app/src/main/assets/codec-test/`。
- `legacy_receiver_probe.py`：`Handler` / `Server` 提供研究接收端；`VideoValidator.feed` 用 FFmpeg 校验旧 AVC；`event` 只记录元数据；`main` 启动 HTTP / RTSP、UDP、SSDP 和 dns-sd。
- Python 探针回归由 `../tests/test_legacy_probe.py` 执行。`adb-setup/` 是被 Git 忽略的本地设备研究，不属于公开交付。

## 3. 设计约定

FFmpeg 与 PyCryptodome 是相应探针的外部依赖。默认输出写 `output/qa`，不入库；生成的合成 MP4 是应用正式输入，需要随源码维护并重新验证 APK。不要在发布请求时重新生成素材。

`legacy_receiver_probe.py` 会绑定 47110 / 47111 / 47112 / 47113 与 SSDP 1900，并发布测试发现信息，不能与实际接收端占用同一地址端口。优先设置 `WIFISCREEN_LISTEN_IP`；默认 IP 检测与 `/usr/bin/dns-sd` 依赖 macOS。结束时关闭进程与服务，不留长期广播。

`check_video_codecs.py` 的默认 host 为本机、端口为正式固定端口。连接模拟器时，若接收端绑定其 LAN 地址，应使用 emulator redir，不能假定 adb forward 的 loopback 一定可达。只发合成流，不能把源码里的“probe”当成无副作用读取。

## 4. 使用示例或典型调用路径

```bash
python3 tools/check_video_codecs.py --host 127.0.0.1 --control-port 49226 --video-port 49230
python3 tools/check_video_codecs.py --case hevc-hvcc --host TEST_RECEIVER_IP
```

需改变基准素材时才运行 `python3 tools/generate_codec_test_clips.py`，随后核对编码、尺寸、帧数与实际解码。诊断新协议时更新纯逻辑单测和对应真实接收回归，不能把 v1 探针成功当成 v2 配对已验证。
