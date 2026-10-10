# WifiScreen 协作指南

> 更新：2026-10-10。本文约束公开工程；本机研究、签名和发布回执不属于源码交付范围。

## 系统概述

投屏助手安装在极米投影仪等 Android 大屏设备上，接收小米 / Redmi 系统投屏。当前主要入口为 `MainActivity` → `LegacyReceiver`，支持旧会话与 HyperOS Lelink v2 镜像会话、AVC / HEVC 视频和 AAC-ELD 声音。`NativeReceiver` / `WfdSession` 是未接入主界面的 Miracast 实验路径，不能将其测试当成当前接收链路的验收。

技术栈：Kotlin、Android Views / SurfaceView、MediaCodec / AudioTrack、JmDNS、Bouncy Castle、Gradle Kotlin DSL、JUnit、Python 合成流探针。最低 API 21，编译 SDK 35、目标 SDK 34，构建用 JDK 17。状态使用 SharedPreferences 与应用私有诊断文件，无 SQL、Redis、消息中间件或服务端数据库。

极米 Z6X / Android 9 已有基础 H.264 音画投屏实测；0.3.1 的 HyperOS / H.265 验证使用原版发送组件和电视模拟器。不得写成所有极米型号、真实手机到投影的 H.265 或长期口型同步均已验证。

## 目录导航

| 目录 | 职责及边界 | 指南 |
| --- | --- | --- |
| `android/` | Gradle 工程、环境与签名入口 | [android](android/AGENTS.md) |
| `android/app/` | APK 配置、依赖、资源与测试组织 | [app](android/app/AGENTS.md) |
| `android/app/src/main/` | 清单、公开入口、资源边界 | [main](android/app/src/main/AGENTS.md) |
| `android/app/src/main/java/com/kanayama/wifiscreen/` | 界面、协议、解码、同步、诊断 | [核心模块](android/app/src/main/java/com/kanayama/wifiscreen/AGENTS.md) |
| `android/app/src/test/java/com/kanayama/wifiscreen/` | JVM 单元测试 | [测试模块](android/app/src/test/java/com/kanayama/wifiscreen/AGENTS.md) |
| `android/gradle/wrapper/` | 固定 Gradle 版本 | [Wrapper](android/gradle/wrapper/AGENTS.md) |
| `tools/` | 合成视频与协议探针 | [工具](tools/AGENTS.md) |
| `tests/` | Python 探针与发布工作流测试 | [Python 测试](tests/AGENTS.md) |
| `.github/workflows/` | 标签 / 手动 APK 构建与 GitHub Release | [发布工作流](.github/workflows/AGENTS.md) |
| `docs/` | 面向人的开发、测试、设计说明；静态文档不单独建 AGENTS | [开发](docs/development.md)、[测试](docs/testing.md) |
| `android/app/src/main/assets`、`android/app/src/main/res`、`android/app/src/test/resources` | 静态素材、资源和测试向量，由模块指南维护；不在打包资源目录放 Markdown | [资源说明](android/app/AGENTS.md) |

`src/java/com/kanayama` 等仅构成目录层级的路径，由上述模块直接导航。`.research/`、`output/`、`artifacts/`、构建目录和本地配置为忽略项；不要将反编译源码、厂商 APK / 原生库、原始设备日志、截图、地址或凭证提交到公开仓库。

## 核心业务场景索引

| 场景 | 入口与核心逻辑 | 副作用 / 联动 |
| --- | --- | --- |
| 等待手机发现 | `MainActivity.onResume` → `ReceiverRecovery.start` → `LegacyReceiver.start` → `LegacyDiscovery` | 绑定 IPv4、固定端口、持有多播锁并广播设备 |
| 修改名称 | `MainActivity.renameDialog` → `ReceiverName.error` → `LegacyReceiver.rename` | 保存 `receiver.display_name`，重发发现信息；不替换设备身份 |
| 选择 H.264 / H.265 | `showCodecs` → `selectEncoding` → `LegacyDiscovery.updateEncoding` | 保存首选，更新 vv / avformat_support；实际编码由参数集识别 |
| HyperOS 建立会话 | `LegacyReceiver.handle` → `LelinkPairing` / `LelinkPlist` → 15 秒视频预约 | 验证控制消息、绑定视频 socket；声音仍由 UDP 接收 |
| 播放视频 / 追帧 | `LegacyVideo.offer/decode` → `VideoInputQueue` → MediaCodec → `VideoPresentationQueue` | 保留参考帧；可丢弃过期输出的显示 |
| 声音与同步 | `LegacyAudio` → `AudioRtpQueue` / `AudioSampleClock` → `AudioContinuity` → AudioTrack | `AvPlaybackClock` 与 `AudioOutputClock` 驱动有界同步，连续 PCM 不被替换 |
| 十秒评估 | `startCodecTest` → `beginCodecTest` → `CodecBenchmark` / `CodecNetworkProbe` → `CodecRecommendation` | 与投屏互斥；取消不覆盖结果，采用推荐需要用户操作 |
| 画面修复 | `repairPicture` → `LegacyReceiver.repairPicture` → `VideoRepair` / `AvcParameters` | 先保存诊断，完整 AVC IDR 到达后只重建视频；HEVC 提示重连 |
| 网络与生命周期 | `ReceiverRecovery.check`、`StreamStallWatchdog.check`、`onPause/onStop` | 取消测试、关闭旧会话、重新发布；不代替手机发起重连 |
| 发布新版 | `app/build.gradle.kts`、工作流、已验证 APK 与签名 | 源码、APK、商店版本和验证记录必须能对应 |

## 全局设计约束

- 固定 TCP 47110（控制及只读 `/status`、`/diagnostics`）、TCP 47111（视频）、UDP 47112（声音）。改端口、设备 MAC / UID 或会话归属会影响手机缓存，不能随意随机化。
- 发现就绪以 `_leboremote` 完成公告为准。JmDNS 3.5.9 的 `setText` 会打断尚未完成的探测 / 公告；相同编码不更新，实际切换在 setup 线程重新注册该记录，保持身份与媒体会话。
- MediaCodec 的输入、输出与释放归解码线程所有。会话 / generation 校验必须阻止旧回调覆盖新会话。一次只有一个活动投屏，测试和接收必须互斥。
- 应用输入队列 4 项、待显示队列 4 项、显示额外等待预算 20ms。不能靠丢参考帧、降低发送帧率或扩大队列来掩盖积压；20ms 不是端到端延迟。
- 连续音频保持原样。仅确认媒体样本缺口后允许最多 20ms 波形补偿；禁止恢复预测式替换正常 PCM。按当前 AAC-ELD 每包 480 样本生成时间，不能直接信任所有发送端 RTP 时间戳。
- v2 控制认证、二进制 body、重复头限制、TLV / XML / 帧长度边界必须保留。v2 媒体 CBC IV 跨加密关键帧延续，解码器重建不重置；缓存的明文输入不能再次推进密码状态。v1 兼容行为独立维护。
- H265 首选不等于实际收到 H265，模拟器解码计数不等于物理投影显示 / 听感。十秒测试是本机 1080p30 解码与局域网参考指标，不是手机编码或真实链路吞吐测量。
- 不自动重启长时间投屏来掩盖颜色问题；不保存用户投屏音视频到文件。诊断只记录有界元数据，修复前文件在私有目录原子覆盖。
- 不引入厂商 SDK / 原生库到生产 APK，不获取 Root、ADB 或系统特权来让普通接收路径工作。实验 WFD 路径只在系统已授予权限时运行。

## 构建、验证与发布

在 `android/` 运行 `./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease`，需要已有正确的 Release 签名配置。日常调试可用 `:app:assembleDebug`。发布不得以 Debug 签名替代已有证书。Python 与设备验证入口见对应指南和 `docs/testing.md`。

本轮已经验证且输入未变的 APK、测试和签名证据可以复用。仅更新文档 / 发布元数据不要求重建或再装设备。源码、依赖、版本或打包输入变化时补做对应验证。版本从 APK 读取；同一交付只提交审核过的相关文件，不将本地回执、APK 或私钥加入 Git。发布到 GitHub 主干、创建标签 / Release 和发布商店是不同动作，按用户授权范围执行；商店上传后核对下载哈希与大小。

## AGENTS 维护规则

修改代码、配置、脚本或工作流的 Agent，必须在任务结束前校验并同步对应目录的 AGENTS.md；若内容无需改动，也要确认入口、约束与依赖仍然成立。影响父级导航、跨模块流程或全局约束时同步父级 / 根文档。新增相关目录补建指南，重命名或删除后清理失效引用。跳过文档维护需要用户明确同意；不要默认留到后续任务。纯静态资源的来源和规则维护在模块文档中，避免文档进入 APK 资源打包。
