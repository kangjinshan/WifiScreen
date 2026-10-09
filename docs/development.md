# WifiScreen 开发说明

普通用户的下载、安装和使用方法见 [README](../README.md)。本文介绍构建、发布与接收链路。

## 本地构建

需要 **JDK 17、Android SDK 35**，使用仓库内的 Gradle Wrapper：

```bash
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

调试 APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`。

发布构建先将 `android/signing.properties.example` 复制为 `android/signing.properties`，配置密钥文件、密码文件和 alias：

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

Release APK 位于 `android/app/build/outputs/apk/release/app-release.apk`。签名配置、密码文件、密钥、APK 和设备日志不入库。不同证书签名的 APK 无法直接覆盖安装。

版本号与版本码在 [app/build.gradle.kts](../android/app/build.gradle.kts) 中维护。

## GitHub 构建与发布

工作流见 [Build Android APK](../.github/workflows/android.yml)。

- 推送 `v版本号` 标签，会运行单元测试、Lint 与 Release 构建，并发布 APK 到 GitHub Releases。
- 在 **Actions → Build Android APK → Run workflow** 手动运行且不填写 `release_tag` 时，只构建 Artifacts。
- 重试已有标签的发布时，在最新主干工作流中填写 `release_tag`。工作流会检出标签源码，并校验源码提交与标签一致。
- 标签必须与 APK 的 `versionName` 一致，例如 `v0.2.0` 对应 `0.2.0`。
- 已发布版本重试时，会校验原 APK 的文件、源码、版本和签名。匹配后保留原文件；不完整或不一致则报错，不自动覆盖。

Fork 后使用自己的签名时，在仓库 **Settings → Secrets and variables → Actions** 配置：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 发布 keystore 的 Base64 内容 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 签名 alias |

发布产物包含 APK、`SHA256SUMS` 和 `build-info.json`，可核对 SHA-256、文件大小、签名指纹与源码提交。

## 代码结构

| 文件 | 职责 |
| --- | --- |
| `MainActivity.kt` / `CinemaUi.kt` | 电视界面、焦点、遥控器菜单与设置 |
| `ReceiverRecovery.kt` / `StreamStallWatchdog.kt` | 前台网络恢复与异常会话判断 |
| `LegacyReceiver.kt` / `LegacyDiscovery.kt` | 当前启用的接收会话、固定端口与 IPv4 mDNS 发现 |
| `LegacyVideo.kt` / `VideoInputQueue.kt` | 硬件视频解码、输入背压与显示 |
| `LegacyAudio.kt` / `AudioRtpQueue.kt` | 音频解码、播放与 RTP 排队 |
| `PlaybackTiming.kt` / `AudioSampleClock.kt` | 媒体时间、缓冲与播放节奏 |
| `AudioContinuity.kt` / `PcmConcealer.kt` | 有限波形补偿、迟到 PCM 去重和缺口接续 |
| `AvPlaybackClock.kt` | AudioTrack 播放进度与源媒体时间映射、音画同步跳时 |
| `VideoFrameTracker.kt` | 有界的视频输入时间记录，兼容一个画面由多个输入单元组成 |
| `VideoPresentationQueue.kt` | 有界的待显示输出，允许解码与显示等待交错推进 |
| `VideoViewport.kt` / `ReceiverName.kt` | 画面几何、名称校验与协议转义 |

上述文件位于 `android/app/src/main/java/com/kanayama/wifiscreen/`。`NativeReceiver` / `WfdSession` 为早期 Miracast 实验代码，当前主界面使用 `LegacyReceiver`。

## 接收链路与兼容范围

- 接收端最低 Android API 21。
- 当前主要针对小米 / Redmi 系统投屏验证，不代表所有 Google Cast、Miracast、AirPlay 或其他品牌发送端均兼容。
- IPv4 mDNS 设备发现，HTTP / RTSP 会话，视频使用 TCP，音频使用 RTP UDP。
- 固定端口：TCP **47110**（控制与诊断）、TCP **47111**（视频）、UDP **47112**（音频）。
- 接收端身份由本应用生成；改名保持设备身份及固定端口。
- 应用退到后台或退出时停止接收服务。

基础音画接收曾在极米 Z6X Android 9 上实机验证。0.2.0 新增功能已完成模拟器回归，不能据此宣称已完成目标投影仪的音画听感、延迟和显示边缘验收。测试方法与覆盖范围见 [测试说明](testing.md)。

工程不附带其他投屏应用的 SDK 或二进制，不修改其他应用的授权。依赖见 [第三方组件说明](../THIRD_PARTY_NOTICES.md)。

## 音视频解码与缓冲

视频使用 Android `MediaCodec`。Z6X 曾验证的解码器为 `OMX.MS.AVC.Decoder`（MStar H.264 硬件解码器），实际选择取决于设备提供的解码器。音频使用 AAC-ELD 解码与 `AudioTrack` 播放，当前接收格式为 44.1kHz 双声道。

0.1.4 起使用 **20 ms 额外缓冲目标**：音频线程优先处理，解码器繁忙时保留 AAC 帧，在缓冲预算内恢复 RTP 乱序包并过滤重复包。视频按媒体时间安排显示，补偿相同的新增延迟；迟到画面可以跳过显示，参考帧仍完整解码。

20 ms 叠加在设备原有的 AudioTrack 缓冲之上，并非整个投屏链路的延迟。链路还包含手机采集与编码、网络、电视解码和显示。UDP 音频由手机发送端协商，接收端不能单方面改用 TCP。短缺口保留媒体时长并平滑边界，严重丢包仍需改善网络。

0.2.2 按当前 AAC-ELD 协议每包固定 480 样本的约定，用排序后的 RTP 序号生成音频时间，缺包仍保留对应时长。部分旧发送端的 RTP 时间戳不以 44.1kHz 样本计时；直接采用它会触发逐帧清空 AudioTrack，导致始终无法完成启动缓冲。时间戳与固定时长不一致时计入兼容回退，不再打断播放。到达抖动也使用相同的固定样本时钟估算。此逻辑仅适用于当前固定帧长格式；新增可变帧长音频格式时需要同时扩展时钟处理。

0.2.3 将应用内视频队列从 32 帧缩短为 4 帧。存在积压时，解码线程不再等待旧画面的显示时刻，也不把播放时钟重新对齐到旧队列。所有参考帧仍送入解码器，过时输出可以不显示；追帧期间每 100ms 最多立即显示一帧过渡画面，避免一直没有首帧而阻止音频启动。追上队列后恢复按源时间戳显示。解码输入采用非阻塞查询，繁忙时先排空输出；空闲才短暂让出线程。设备正常的少量解码流水线缓冲不会单独触发追帧。

4 帧限制只约束应用队列，不能单独约束手机、TCP 或硬件解码器的缓冲；主动追帧用于消化已接收的积压。画质与发送端帧率保持原值，持续解码能力不足时仍需实机定位瓶颈。

0.2.4 将短缺口的静音填充替换为波形补偿：用最近 40ms PCM 历史匹配相近周期，保持声道相位并在恢复时用约 3ms 交叉淡化接回真实音频。这段历史不增加播放等待。每次连续缺数据最多补偿 20ms，用尽预算时逐渐淡出；包迟到或缓冲接近空时按约 5ms 补充，正常的解码器批量输出不单独触发补音。迟到 PCM 中已补偿的样本不再重复播放，避免累计延迟。

超过补偿范围的缺口在下一段 PCM 到达后直接接续，保留 AudioTrack 和已排队数据，不再清空重建播放器。播放时间映射记录每段 PCM 对应的源样本位置，视频在播放头到达接续点时跳过同一缺失区间的显示，参考帧仍完整解码。视频优先跟随 AudioTrack 的硬件播放时间戳，设备未提供有效时间戳时使用播放头估计；没有可用音频、时间戳不可靠或音频长时间停滞时，回退到独立的视频节奏，单次等待有 200ms 上限。

当前旧投屏协议的音频 RTP 时间戳并非始终可用，初始音画偏移通过接收端到达时间估计，播放头间隔内仅做有界插值。这不是外部测量的绝对口型同步。持续断流仍可能出现空缺；波形补偿不能还原已经丢失的内容。

0.2.5 禁用 0.2.4 的预测式补音。真实设备和成对发包回归表明，即使没有丢包，该策略仍可能大量替换正常音频，产生杂音。现在只有排序后媒体样本位置的缺口才允许修改 PCM；连续输入逐字节保留，短缺口有限补偿，长缺口平滑接续。`audioStarvationSamples` 为兼容旧诊断保留，固定为 0。

0.2.5 也不再用累计输入数减输出数判断视频积压。部分编码器会拆分输入、部分硬件解码器会合并或省略输出，累计差值不代表待显示画面。追帧只参考实际应用队列；时间记录最多保留 64 项，同一时间戳的多个输入单元在画面输出时一起移除。时间戳可靠性按解码输出检查，避免重复的输入分片关闭音画同步。

0.2.6 对音画偏差做双向有效性检查。超过 ±200ms 时，音频时钟不再用于该画面的同步决策，回退到已有的视频节奏和积压追帧；防止视频时钟暂停或偏移后产生持续的大负差，导致所有新输出都被丢弃。范围内的正常迟到帧仍参与同步。`videoAudioClockRejections` 统计异常偏差回退次数，`videoAudioClockOffsetMs` 保留最近一次计算的原始差值，便于区分时钟异常与解码停滞。

0.2.7 取消了解码输出后的阻塞式同步等待。所有 MediaCodec 操作仍由同一线程拥有，但未来才显示的输出放入最多 4 项的队列，主循环继续喂入输入、排空解码输出并检查显示期限。队列满、输入缓冲持续不可用或持有超过 200ms 时释放较老输出；真实输入积压仍触发追帧。若只是等待音频导致缓冲压力，会提前显示以保障吞吐量，可能暂时降低同步精度。音频队列耗尽时立即恢复独立视频节奏，不等待陈旧时钟超时。不会清除参考数据、增加无界画面队列或恢复预测补音。

新增 `videoPresented`、`videoPresentedAgeMs`、`videoPresentationQueueDepth`、`videoPresentationHoldMs`、`videoRenderCallMs`、`videoForcedPresentations`，分别观察显示提交、显示进度、待显示输出、持有时间、原生提交耗时和提前显示次数。`videoDecoded` 现在在取到解码输出时增加，待显示帧单独计数；实时 FPS 继续使用实际显示提交数，不改变统计口径。缓冲提前释放时 `videoUsingAudioClock` 为 false，不代表解码停止。

## 诊断与实时速率

应用在前台时提供固定只读地址：

```text
http://电视IP:47110/diagnostics
http://电视IP:47110/status
```

`/diagnostics` 提供文本报告，`/status` 提供 JSON。IP 不变时地址不变；报告包含设备、接收、解码、队列、错误以及音频欠载、乱序与丢包、抖动和显示跳帧等指标。

0.2.2 新增 `audioPlaying`（AudioTrack 已启动）、`audioPlayedFrames`（播放头累计推进的样本帧）和 `audioTimestampFallbacks`（采用固定帧长兼容的次数）。`audioPcmBytes` 只表示写入缓冲的数据量；即使持续增长，也不证明已开始播放。系统音量和实际扬声器听感仍需另行确认。

0.2.3 新增 `videoCatchingUp` 和 `videoReceiverLatencyMs`。后者测量最近显示帧从应用完整接收至交给 Surface 显示的时间，包含应用排队、解码和等待，不包含此前的手机采集、网络/TCP 积压及之后的显示器处理。无法匹配 OEM 解码器输出时间戳时返回 -1，不推测耗时。

0.2.4 新增 `audioDecodedSamples`、`audioStarvationSamples`、`audioSkippedSamples`、`audioOverlapSamples`，区分解码、临时补音、跳过缺口与去重样本；`audioConcealedSamples` 统计插入的波形补偿样本。`audioHardwareClock` 标识经新鲜度、进度和位置差校验后的硬件时间戳是否有效，`audioHardwareLagMs` 便于识别设备异常时间戳；`videoUsingAudioClock`、`videoAudioSkewMs`、`videoAudioSyncDrops` 显示音频时钟是否生效及同步处理情况。模拟器欠载与实际听感分别验收，不以补偿计数代替原始丢包统计。

实时速率每秒刷新：

- FPS 统计实际送去显示的帧；跳过显示的帧不计入。
- 接收速度统计音视频数据，包括媒体协议头，不包括 TCP/IP 开销。
- 使用十进制 KB/s、MB/s。
- 手机静止画面可能降低帧率；低帧率或长时间没有新画面本身不等于卡死。

应用不将投屏音视频保存到文件。公开提交前应检查诊断附件，避免把用户设备信息、局域网地址或原始日志混入仓库。
