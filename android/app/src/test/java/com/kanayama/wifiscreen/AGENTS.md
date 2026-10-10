# JVM 测试层开发指南

> 更新：2026-10-10。位置：`android/app/src/test/java/com/kanayama/wifiscreen/`。

## 1. 概述

用 JUnit 4 验证接收链路中可脱离 Android 设备的解析、时钟、状态机和边界。不将 JVM 通过等同于 MediaCodec、Surface、AudioTrack 或极米实机验收；设备方法见根目录 `docs/testing.md`。

## 2. 核心组件

- `ProtocolTest`、`RtpPacketTest`、`LegacyProtocolTest`、`LegacyResponseTest`：握手状态、二进制边界、大小写敏感响应、旧 AVC 加密与 RTP。
- `LegacyHevcTest`：hvcC / 旧包装、NAL 类型、截断、多 NAL 和第二个头字节参与解密。
- `LelinkProtocolTest`、`LelinkVideoCipherTest`：独立完整配对向量、连续认证记录、篡改 / 重放、低阶公钥、重复 DID 例外、CBC 跨关键帧与码流变化。
- `CodecRecommendationTest`：设备性能、硬件 / 软件、未知网络、无响应网关、断网 / 切换和两编码均失败。
- `AudioRtpQueueTest`、`AudioSampleClockTest`、`AudioContinuityTest`、`AudioOutputClockTest`、`AvPlaybackClockTest`：乱序、时间戳兼容、连续 PCM 字节保持、补偿预算、时钟切换。
- `PlaybackTimingTest`、`VideoInputQueueTest`、`VideoFrameTrackerTest`、`VideoPresentationQueueTest`、`VideoSyncPolicyTest`：输入背压、分片 PTS、输出所有权、追帧和显示等待。
- `AvcParametersTest`、`VideoRepairTest`、`VideoDiagnosticsTest`：SPS / PPS / VUI、完整 IDR、超时 / 冷却 / 生命周期和有界不可变历史。
- `VideoViewportTest`、`ReceiverNameTest`、`StreamRateMeterTest`、`StreamStallWatchdogTest`、`DiagnosticServerTest`：画面几何、名字、计数、静止画面、临时报告窗口。
- [lelink-v2.properties](../../../../resources/lelink-v2.properties)：独立 Python 密码库生成的合成 transcript，经 classloader 加载；不是用户密钥。

## 3. 设计约定

密码 / 字节流预期值优先来自独立实现或经过核对的向量，不能用被测函数生成自己的 expected。检查原样播放时保留独立输入副本，避免原地修改掩盖错误。计时与状态机传入逻辑时间，避免靠长 sleep 制造脆弱测试；并发队列测试必须有停止和超时边界。

Android 平台类可出现在业务文件中，但这些测试应聚焦纯逻辑，不依赖虚假的设备解码成功。改协议参数集、时钟、补偿或解密上下文时，选择相应组回归；普通文档发布复用当前有效结果。新增测试后保持向量来源、场景与根开发文档同步。

## 4. 使用示例或典型调用路径

在 `android/` 执行 `./gradlew :app:testDebugUnitTest`；定位单组可使用 `--tests 'com.kanayama.wifiscreen.LelinkProtocolTest'`。报告位于 `app/build/reports/tests/testDebugUnitTest/`，JUnit XML 位于 `app/build/test-results/testDebugUnitTest/`，不提交生成报告。

修复 v2 关键帧问题时：`LelinkVideoCipherTest` → `LegacyHevcTest` / `LegacyProtocolTest` → 对应实际接收流与原版发送组件验证。图像颜色、音画口型、真实吞吐和投影硬件性能必须另行观察。
