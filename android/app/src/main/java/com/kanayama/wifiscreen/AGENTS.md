# 投屏接收核心模块开发指南

> 更新：2026-10-10。位置：`android/app/src/main/java/com/kanayama/wifiscreen/`。

## 1. 模块概述

把手机系统投屏的音画稳定显示在极米投影仪等 Android 大屏上，同时允许遥控器调整画面、选择编码和诊断问题。当前生产路径为 `MainActivity` → `LegacyReceiver`；它承载旧协议与 HyperOS v2 两种会话，共用视频、声音和同步链路。系统 WFD 实验代码没有连接到主界面。

## 2. 核心代码结构

| 文件 / 组件 | 职责 | 关键入口 / 约束 |
| --- | --- | --- |
| `MainActivity.kt`、`CinemaUi.kt` | 主界面、菜单、设置、焦点、前后台状态 | `showCodecs`、`startCodecTest`、`showPicture`、`repairPicture`；UI 回调要核对当前会话 |
| `VideoViewport.kt`、`ReceiverName.kt` | 显示边界、缩放、名称校验与 XML 转义 | `bounds`、`PictureSettings.normalized`、`error`、`xml` |
| `DeviceProfile.kt`、`DeviceCodecs.kt` | 网络地址、系统权限、可用解码器和诊断 | `addresses`、`report`、`find`；能力存在不等于实测性能 |
| `LegacyReceiver.kt` | 固定监听端口、HTTP / RTSP、会话、预约、诊断 | `start/stop`、`handle`、`receiveAudio`、`selectEncoding`、`beginCodecTest` |
| `LegacyDiscovery.kt` | IPv4 mDNS 发现与编码能力公告 | `isReady` 检查公告状态；`updateEncoding` 跳过相同值，在 setup 线程重注册单条记录，保留端口、身份 |
| `Rtsp.kt`、`LegacyResponse.kt` | 有界头 / body 解析、二进制 body、旧手机兼容响应 | `read`、`encode`；长度按字节，不能宽泛接受重复头 |
| `LelinkPairing.kt` | v2 开放模式配对、TLV、认证记录 | `handshake`、`LelinkTlv`、`LelinkRecords`；乱序 / 认证失败不可继续 |
| `LelinkPlist.kt` | v2 镜像控制 plist | `decode/encode`；受限 Apple DTD 仅剥离，不访问网络或外部实体 |
| `VideoEncoding.kt`、`LegacyAvc.kt`、`LegacyHevc.kt` | 首选值、帧头、参数集、NAL 及配置识别 | `VideoConfiguration.parse`、`configuration`、`accessUnit`；实际码流类型独立判断 |
| `LelinkVideoCipher.kt` | v2 图片局部 CBC 状态 | `clearPicture`；跨加密关键帧延续 IV，不跟随解码器重建重置 |
| `LegacyVideo.kt` | MediaCodec、输入重试、追帧、输出调度、修复 | `offer/decode`；缓存明文输入，重试不得再次推进密码状态 |
| `VideoInputQueue.kt`、`VideoFrameTracker.kt`、`VideoPresentationQueue.kt` | 背压、按 PTS 记录、待显示输出所有权 | 输入队列 4、时间记录 64、输出队列 4；缓冲只释放一次 |
| `PlaybackTiming.kt`、`VideoSyncPolicy.kt` | 独立视频节奏与有界迟到决策 | 20ms 额外预算，追帧与同步丢显示不丢参考输入 |
| `LegacyAudio.kt` | AAC-ELD 解码、音量、AudioTrack、声音统计 | `offer`、`volume`、`close`；播放启动与接收计数分开 |
| `AudioRtpQueue.kt`、`AudioSampleClock.kt` | 乱序、重复 / 缺包统计、固定样本时钟 | `offer/poll`、`position`；每包 480 样本、44.1kHz |
| `AudioContinuity.kt`、`PcmConcealer.kt` | 确认缺口后的有限波形接续 | `accept`、`conceal/join`；连续 PCM 原样保留 |
| `AvPlaybackClock.kt`、`AudioOutputClock.kt` | 实际声音进度、媒体映射和硬件时钟切换 | `appendAudio/updateAudio/videoTargetNs`、`update`；无可靠音频时回退 |
| `AvcParameters.kt`、`VideoRepair.kt` | AVC VUI / slice 解析与完整 IDR 修复状态机 | `sps/pps/slice/joinIdr`、`IdrPictureCollector`、`VideoRepair` |
| `VideoDiagnostics.kt`、`VideoDiagnosticsJson.kt` | 有界元数据历史与 JSON | `configuration`、`event`、`snapshot`、`toJson`；未知字段保留未知 |
| `CodecBenchmark.kt`、`CodecNetworkProbe.kt`、`CodecRecommendation.kt` | 十秒评估与解释 | `run`、`sample/result`、`evaluate`；不能冒充端到端测试 |
| `ReceiverRecovery.kt`、`StreamStallWatchdog.kt`、`StreamRateMeter.kt` | 网络恢复、静止 / 卡顿区分、速率 | `check`、`sample`；静止画面不算失效，FPS 使用 presented 增量 |
| `NativeReceiver.kt`、`WfdSession.kt`、`RtpTransport.kt`、`RtpPacket.kt`、`StreamBuffer.kt` | WFD 实验接收与 RTP 缓冲，不用于当前 AAC-ELD 接收 | `NativeReceiver.start` 先查系统授权；`WfdSession` 只在成功 PLAY 后开始；当前声音由 `LegacyAudio.payload` 解析 |
| `DiagnosticServer.kt` | 旧实验的临时只读报告服务器 | 随机端口 / 验证码、5 分钟窗口；不是当前 47110 固定诊断实现 |

## 3. 核心业务流程

- **发现与重连**：`onResume` → `ReceiverRecovery.start/check` → `LegacyReceiver.start` → socket + 多播锁 + `LegacyDiscovery`；`ready` 必须等 `_leboremote` 完成公告，不能仅检查对象存在。失网 / 换地址时停止旧服务并通知 UI。手机自行发起连接，接收端不替用户操作手机。
- **编码公告更新**：同值刷新直接返回；实际切换由 setup executor 注销 / 重注册 `_leboremote`，保持名称、MAC / UID、端口和现有媒体连接。启动后补读最新首选，排队任务核对 generation；失败交回前台恢复。JmDNS 3.5.9 的 `setText` 会清除当前探测 / 公告任务，不可在启动或快速切换时使用；就绪查询依赖该版本 `ServiceInfoImpl.isAnnounced`，升级库须回归。
- **旧协议接收**：`handle` 解析握手 → `POST /stream` 直接进入帧通道（不额外回 HTTP 响应）→ `LegacyAvc.frame` → `LegacyVideo.offer`；声音经 `receiveAudio` → `LegacyAudio`。
- **HyperOS v2**：配对三步 → 已认证能力查询 → 视频 SETUP 预约 → 同来源视频 socket 接入 → `Session`。`avformat_support` 来自首选与设备能力；视频 / 音频 teardown 分别回复。帧解密使用会话的 v2 标识和连续 CBC IV。
- **视频与声音**：排队 / 解密缓存 → 解码 → 有界显示；声音排序 → 样本位置 → AAC 解码 → 仅确认缺口时补偿 → AudioTrack。`AvPlaybackClock` 将实际声音进度映射回媒体时间，异常偏差回退独立视频节奏。
- **画面修复**：用户触发 → 原子保存修复前报告 → 等待完整 AVC IDR → 仅重建视频 → 验证输出。等待 2 秒，重建后验证 3 秒，连续请求间隔至少 30 秒；HEVC 不进入 AVC 修复状态机。
- **编码测试**：界面预约互斥 → 准备合成片段 → AVC / HEVC 各采样 5 秒，同时采样局域网 → 推荐与保存报告。暂停、返回或取消立即取消任务；异步结果还须核对取消标记与页面。结果不会自动更改首选。

## 4. 关键资源与副作用

- 网络：TCP 47110 / 47111、UDP 47112；`Session` 绑定来源地址、控制 socket 与 v2 会话 ID。v2 预约 15 秒失效，关闭控制释放预约 / 会话；停止服务必须关闭所有 socket、多播锁和 worker。
- 持久状态：`receiver` 下的 `stable_ports_id`、`display_name`、`video_encoding`；`display` 下的 `show_realtime_rates`、`picture_mode/zoom/x/y`、`codec_test_report`、`codec_test_recommendation`。改键名要考虑已安装版本。
- 文件：应用私有 `last-video-repair.txt` 由 `AtomicFile` 写入，下次修复覆盖；不要保存原始用户音视频。
- 内存：视频包体最大 2MiB，控制 body 最大 64KiB，v2 认证记录最大 5120 字节；诊断事件默认 32 条。不要将全码流、密钥或无限历史加入报告。
- 没有数据库、Redis 或 MQ。状态一致性靠会话归属、generation、同步锁和线程所有权，不能移除这些检查后依赖 UI 顺序。

## 5. 常见修改场景与切入点

- 新编码或手机协议：从 `LegacyReceiver.handle`、`VideoConfiguration.parse`、解码 CSD 和能力公告一起检查；只加按钮 / MIME 字符串不足以支持手机实际切换。
- v2 后续关键帧损坏：检查 `LelinkVideoCipher` 是否被重置或重复推进；对照独立 CBC 向量和连续多组 IDR。不要盲目更改 v1 兼容行为。
- 音频无声 / 杂音：先看 `LegacyAudio` 的接收、PCM、播放样本和欠载指标，再查 `AudioSampleClock` / `AudioContinuity`，不能按包到达间隔替换正常音频。
- 延迟 / 帧率：看真实队列、`VideoFrameTracker`、显示等待与音频时钟；不能使用累计 received-decoded 差值作为可靠积压。
- UI / 画面比例：`CinemaUi` / `VideoViewport.bounds` / `MainActivity.applyPicture`；检查 DPAD 焦点、退出恢复和横竖屏，保留用户设置。
- 推荐策略：修改 `CodecRecommendation.evaluate` 并覆盖弱网、未知网络、性能不足 / 无解码器；只要网络切换或两者不达标就不要伪造推荐。

## 6. 维护与风险说明

| 风险 | 概率 / 条件 | 影响 | 缓解与回归 |
| --- | --- | --- | --- |
| CBC 被逐关键帧重置 | 改解码生命周期时高 | 首组正常，后续关键帧失败 | 6 组 IDR、独立向量、重建不重置、输入只解密一次 |
| 背压改成丢输入 | 网络 / 解码繁忙时高 | 参考链破坏，画面停顿或损坏 | 保留 4 项输入背压，突发和重复参数测试 |
| 误补音 | 按到达间隔判断时高 | 正常音频被替换产生杂音 | 连续 PCM 字节一致；确认缺口后才允许 ≤20ms 补偿 |
| 同步等待失控 | 设备音频时钟切换时中 | 画面延迟、掉帧 | 20ms 显示预算，±200ms 偏差回退，测试时钟切换 |
| 旧回调污染新会话 | 快速断开 / 重连时中 | 错误首页、Surface 或结果被覆盖 | generation / Session 归属与取消状态检查 |

改动会影响 `MainActivity`、`LegacyReceiver`、音视频同步和测试模块；按修改路径更新对应 JVM 测试及 `docs/testing.md`。验收必须说明是原版组件 / 模拟器还是物理极米投影，不把输出计数或“重新启动”当成实际画面、听感已确认。
