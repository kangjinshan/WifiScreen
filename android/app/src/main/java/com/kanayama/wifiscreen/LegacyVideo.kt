package com.kanayama.wifiscreen

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/** All codec operations, including release, are owned by the decoder thread. */
class LegacyVideo(
    private val surface: () -> Surface?,
    private val firstFrame: (Int, Int) -> Unit,
    private val error: (String) -> Unit,
    private val playbackClock: AvPlaybackClock? = null,
    private val cipherName: String = LegacyAvc.LEGACY_CIPHER
) {
    private data class Input(val frame: LegacyFrame, val streamTime: String, val receivedNs: Long,
        val receivedMs: Long, var accessUnit: ByteArray? = null, var repairChecked: Boolean = false)
    private val queue = VideoInputQueue<Input>(4)
    @Volatile private var running = true
    val received = AtomicLong()
    val decoded = AtomicLong()
    val presented = AtomicLong()
    val dropped = AtomicLong()
    val audioSyncDrops = AtomicLong()
    val forcedPresentations = AtomicLong()
    @Volatile var presentationQueueDepth = 0
        private set
    @Volatile var presentationHoldMs = 0L
        private set
    @Volatile var renderCallMs = 0L
        private set
    @Volatile private var lastPresentedAt = 0L
    val presentedAgeMs: Long get() = if (lastPresentedAt == 0L) -1 else SystemClock.elapsedRealtime() - lastPresentedAt
    val audioClockRejections: Long get() = playbackClock?.rejectedOffsets ?: 0
    val audioClockOffsetMs: Long get() = playbackClock?.lastOffsetMs ?: 0
    @Volatile var usingAudioClock = false
        private set
    @Volatile var audioSkewMs = 0L
        private set
    val keyframes = AtomicLong()
    @Volatile var catchingUp = false
        private set
    @Volatile var receiverLatencyMs = -1L
        private set
    val queueDepth: Int get() = queue.size
    val backpressureWaits: Long get() = queue.waits.get()
    @Volatile var decoderState = "等待视频参数"
        private set
    @Volatile private var lastOutputAt = 0L
    val outputAgeMs: Long get() = if (lastOutputAt == 0L) -1 else SystemClock.elapsedRealtime() - lastOutputAt
    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set
    @Volatile var codecName = ""
        private set
    @Volatile var encoding: VideoEncoding? = null
        private set
    private val health = VideoDiagnostics(SystemClock.elapsedRealtime())
    private val repair = VideoRepair<Input>()
    fun diagnosticSnapshot(): VideoDiagnosticSnapshot = health.snapshot(SystemClock.elapsedRealtime())
    fun repairStatus(): VideoRepairStatus = repair.snapshot()
    val repairOwnsRecovery: Boolean get() = repairStatus().let { it.active || it.state == VideoRepairState.FAILED }

    /** Called off the UI thread. Persist the pre-repair report before arming the decoder request. */
    @Synchronized fun requestRepair(saveDiagnostics: () -> Unit): String {
        if (!running) return "投屏已结束"
        if (encoding == VideoEncoding.H265) return "H.265 画面修复暂需在手机断开后重新投屏。"
        repair.rejection(SystemClock.elapsedRealtime())?.let { return it }
        try { saveDiagnostics() } catch (_: Exception) { return "诊断保存失败，尚未开始修复，请稍后重试。" }
        if (!running) return "投屏已结束"
        val now = SystemClock.elapsedRealtime()
        if (repair.request(now)) health.event(now, "repair_requested", "pre-repair diagnostics saved; natural IDR only")
        return repairStatus().message
    }
    private val worker = Thread(::decode, "WifiScreen-Video").apply { start() }

    fun offer(frame: LegacyFrame, streamTime: String) {
        if (!running || frame.kind !in 0..1) return
        if (frame.kind == 0) {
            received.incrementAndGet()
        }
        // Blocking the TCP reader applies bounded backpressure without losing reference frames.
        queue.put(Input(frame, streamTime, System.nanoTime(), SystemClock.elapsedRealtime()))
    }

    private fun decode() {
        var codec: MediaCodec? = null
        var configuredSurface: Surface? = null
        var config: VideoConfiguration? = null
        var sourceSps: AvcSps? = null
        var sourcePps: AvcPps? = null
        val v2Cipher = if (cipherName == LegacyAvc.V2_CIPHER) LelinkVideoCipher() else null
        var codedWidth = 0
        var codedHeight = 0
        var awaitingKeyframe = true
        var announced = false
        var pending: Input? = null
        val replay = ArrayDeque<Input>()
        var repairBlocked = false
        var lastRepairState = VideoRepairState.IDLE
        val inputArrivals = VideoFrameTracker()
        val outputs = VideoPresentationQueue()
        val syncPolicy = VideoSyncPolicy()
        var lastCodecActivityNs = 0L
        var inputStallSinceNs = 0L
        val presentationClock = VideoPresentationClock()
        val info = MediaCodec.BufferInfo()
        fun release(reason: String) {
            decoderState = "正在重置解码器"
            for (frame in outputs.clear()) {
                runCatching { codec?.releaseOutputBuffer(frame.index, false) }
                dropped.incrementAndGet()
            }
            presentationQueueDepth = 0; inputStallSinceNs = 0
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (codec != null) health.released(SystemClock.elapsedRealtime(), reason)
            codec = null
            configuredSurface = null
            awaitingKeyframe = true
            presentationClock.reset()
            syncPolicy.reset()
            playbackClock?.resetVideo()
            usingAudioClock = false; audioSkewMs = 0
            inputArrivals.clear(); lastCodecActivityNs = 0
            catchingUp = false; receiverLatencyMs = -1
            decoderState = "等待关键帧"
        }
        fun failed(stage: String, failure: Exception) {
            val now = SystemClock.elapsedRealtime()
            val detail = if (failure is MediaCodec.CodecException)
                "${failure.diagnosticInfo}; recoverable=${failure.isRecoverable}; transient=${failure.isTransient}"
                else "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}"
            health.event(now, "error", "$stage: $detail")
            if (repair.snapshot().active || repairBlocked) {
                repair.fail(now)
                repairBlocked = true
                replay.clear(); pending = null
            }
            release("$stage 失败")
            error(stage + "：" + (failure.message ?: failure.javaClass.simpleName))
        }
        // OEM codecs may combine input units or omit output callbacks. The
        // input-minus-output total is not a live queue and must not gate sync.
        fun hasBacklog(): Boolean = queue.size >= 2

        fun refreshOutput(frame: VideoOutputFrame, nowNs: Long, catchUp: Boolean) {
            if (frame.usesAudioClock && !frame.syncDrop) {
                val audioTarget = playbackClock?.videoTargetNs(frame.ptsUs, nowNs)
                if (audioTarget == null) {
                    syncPolicy.reset()
                    frame.usesAudioClock = false
                    frame.targetNs = frame.fallbackNs
                } else {
                    audioSkewMs = (audioTarget - nowNs) / 1_000_000L
                    frame.syncDrop = syncPolicy.shouldDrop(audioTarget - nowNs, nowNs, outputs.size > 1,
                        playbackClock?.videoWasSkipped(frame.ptsUs, nowNs) == true)
                    frame.targetNs = if (frame.syncDrop) null else maxOf(audioTarget, nowNs)
                }
            }
            if (!frame.usesAudioClock) syncPolicy.reset()
            if (catchUp && !frame.syncDrop) {
                val waitingForAudio = frame.usesAudioClock && frame.targetNs?.let { it > nowNs } == true
                frame.usesAudioClock = false
                if (waitingForAudio) {
                    frame.earlyForThroughput = true
                    frame.targetNs = nowNs
                } else frame.targetNs = presentationClock.presentationTime(frame.ptsUs, nowNs, catchingUp = true)
            }
            catchingUp = catchUp || frame.syncDrop
        }

        fun releaseOutput(frame: VideoOutputFrame, nowNs: Long) {
            val show = frame.targetNs != null
            val early = frame.earlyForThroughput || frame.targetNs?.let { it > nowNs } == true
            val callStartedNs = System.nanoTime()
            codec!!.releaseOutputBuffer(frame.index, show)
            val releasedAtNs = System.nanoTime()
            renderCallMs = (releasedAtNs - callStartedNs) / 1_000_000L
            usingAudioClock = frame.usesAudioClock && !early
            if (!usingAudioClock) audioSkewMs = 0
            presentationHoldMs = (releasedAtNs - frame.decodedNs).coerceAtLeast(0) / 1_000_000L
            if (show) {
                if (early) forcedPresentations.incrementAndGet()
                presented.incrementAndGet()
                lastPresentedAt = SystemClock.elapsedRealtime()
                repair.presented(lastPresentedAt)
                receiverLatencyMs = frame.receivedNs?.let { (releasedAtNs - it) / 1_000_000L } ?: -1
                if (!announced) { announced = true; firstFrame(frame.width, frame.height) }
            } else {
                dropped.incrementAndGet()
                if (frame.syncDrop) audioSyncDrops.incrementAndGet()
            }
        }

        fun drainPresentations(forceProgress: Boolean = false): Boolean {
            var progress = false
            while (running) {
                val first = outputs.first() ?: break
                val nowNs = System.nanoTime()
                refreshOutput(first, nowNs, hasBacklog())
                val ready = outputs.poll(nowNs, forceProgress) ?: break
                presentationQueueDepth = outputs.size
                releaseOutput(ready, nowNs)
                progress = true
                if (forceProgress) break
            }
            return progress
        }
        try {
            while (running) {
                val nowMs = SystemClock.elapsedRealtime()
                repair.tick(nowMs)
                val repairState = repair.snapshot().state
                if (repairState != lastRepairState) {
                    health.event(nowMs, "repair_state", repairState.name + ": " + repair.snapshot().message)
                    if (repairState == VideoRepairState.FAILED) {
                        repairBlocked = true
                        replay.clear(); pending = null
                        release("画面修复失败")
                    }
                    if (repairState == VideoRepairState.WAITING) repairBlocked = false
                    lastRepairState = repairState
                }
                var progressed = false
                if (codec != null) {
                    try { progressed = drainPresentations() }
                    catch (failure: Exception) {
                        failed("视频显示", failure)
                    }
                }
                // Poll promptly after actual codec activity; idle input metadata
                // must not keep this thread spinning forever.
                val waitMs = if (outputs.size > 0) outputs.waitMs(System.nanoTime()) else
                    if (codec == null || System.nanoTime() - lastCodecActivityNs >= 10_000_000L) 5 else 0
                val input = pending ?: replay.pollFirst() ?: queue.poll(waitMs)
                pending = null
                if (input != null) {
                    val frame = input.frame
                    try {
                        if (frame.kind == 1) {
                            progressed = true
                            val parameters = VideoConfiguration.parse(frame.data)
                            val changed = !parameters.sameAs(config)
                            config = parameters
                            encoding = parameters.encoding
                            if (changed) {
                                sourceSps = if (encoding == VideoEncoding.H264) AvcParameters.sps(parameters.csd[0]) else null
                                sourcePps = if (encoding == VideoEncoding.H264) AvcParameters.pps(parameters.csd[1]) else null
                                health.configuration(SystemClock.elapsedRealtime(), parameters.csd, sourceSps, true)
                                repair.configurationChanged()
                                codedWidth = frame.width
                                codedHeight = frame.height
                                width = frame.width
                                height = frame.height
                                release("视频参数变化")
                                announced = false
                            }
                        } else {
                            val target = surface()
                            if (target == null || !target.isValid) {
                                decoderState = "等待显示表面"
                                pending = input
                                Thread.sleep(5)
                                continue
                            }
                            if (codec != null && configuredSurface !== target) {
                                if (Build.VERSION.SDK_INT >= 23) {
                                    try { codec!!.setOutputSurface(target); configuredSurface = target }
                                    catch (failure: Exception) {
                                        health.event(SystemClock.elapsedRealtime(), "surface_change_failed", failure.javaClass.simpleName)
                                        release("显示表面切换失败")
                                    }
                                } else release("显示表面变化")
                            }
                            val parameters = config ?: continue
                            val keyframe = if (parameters.encoding == VideoEncoding.H265) LegacyHevc.isKeyframe(frame.data)
                                else LegacyAvc.isKeyframe(frame.data)
                            val encrypted = cipherName == LegacyAvc.LEGACY_CIPHER || frame.flags != 0
                            val unit = input.accessUnit ?: run {
                                val encryptedKey = encrypted && if (parameters.encoding == VideoEncoding.H265)
                                    parameters.legacyHevc && frame.data.size >= 6 && LegacyHevc.type(frame.data[4]) == 19
                                    else LegacyAvc.isKeyframe(frame.data)
                                val picture = if (v2Cipher != null && encryptedKey)
                                    v2Cipher.clearPicture(frame.data, input.streamTime) else frame.data
                                if (parameters.encoding == VideoEncoding.H265)
                                    LegacyHevc.accessUnit(picture, input.streamTime, parameters.legacyHevc, cipherName,
                                        parameters.legacyHevc && encrypted && v2Cipher == null)
                                else LegacyAvc.accessUnit(picture, input.streamTime, cipherName, encrypted && v2Cipher == null)
                            }.also {
                                    input.accessUnit = it
                                    if (keyframe) { keyframes.incrementAndGet(); health.keyframe(input.receivedMs) }
                                }
                            if (!input.repairChecked && parameters.encoding == VideoEncoding.H264) {
                                input.repairChecked = true
                                val isIdr = unit[4].toInt() and 31 == 5
                                val waiting = repair.snapshot().state == VideoRepairState.WAITING
                                val slice = if (waiting || isIdr) AvcParameters.slice(unit, sourceSps, sourcePps) else null
                                if (slice?.idr == true && slice.firstMb == 0) health.idr(input.receivedMs)
                                if (waiting) {
                                    val afterRequest = repair.snapshot().requestedAtMs?.let { input.receivedMs > it } == true
                                    val complete = repair.offer(input, slice, frame.data.size + unit.size,
                                        SystemClock.elapsedRealtime(), afterRequest)
                                    if (complete != null) {
                                        val picture = complete.first().copy(
                                            accessUnit = AvcParameters.joinIdr(complete.map { it.accessUnit!! }),
                                            receivedNs = complete.maxOf { it.receivedNs },
                                            receivedMs = complete.maxOf { it.receivedMs }, repairChecked = true)
                                        release("用户修复画面")
                                        replay.addLast(picture)
                                        replay.addLast(input)
                                        // Submit the complete IDR in one codec input, then the untouched
                                        // lookahead unit. Some OEM codecs emit output for each input buffer.
                                        continue
                                    }
                                }
                            }
                            if (repairBlocked) continue
                            if (awaitingKeyframe && !keyframe) continue
                            presentationClock.observeInput(frame.timeUs, input.receivedNs)
                            if (codec == null) {
                                decoderState = "正在启动解码器"
                                val format = MediaFormat.createVideoFormat(parameters.encoding.mime, codedWidth, codedHeight).apply {
                                    parameters.csd.forEachIndexed { index, bytes -> setByteBuffer("csd-$index", ByteBuffer.wrap(bytes)) }
                                    if (Build.VERSION.SDK_INT >= 23) setInteger(MediaFormat.KEY_PRIORITY, 0)
                                    if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                                }
                                // Avoid adaptive max-width/max-height hints on the target MStar codec.
                                // Take ownership before configure/start so a failure cannot leak the instance.
                                val device = DeviceCodecs.find(parameters.encoding, codedWidth, codedHeight)
                                    ?: throw IllegalStateException("设备不支持 ${parameters.encoding.label} ${codedWidth}×${codedHeight}，请选 H.264 后重新投屏")
                                codec = MediaCodec.createByCodecName(device.name)
                                codecName = codec!!.name
                                codec!!.configure(format, target, null, 0)
                                codec!!.start()
                                health.started(SystemClock.elapsedRealtime(), codecName)
                                repair.started()
                                configuredSurface = target
                            }
                            val index = codec!!.dequeueInputBuffer(0)
                            if (index >= 0) {
                                decoderState = "正在解码"
                                val buffer = codec!!.getInputBuffer(index)!!
                                buffer.clear()
                                if (buffer.remaining() < unit.size) throw IllegalArgumentException("Video input buffer too small")
                                buffer.put(unit)
                                codec!!.queueInputBuffer(index, 0, unit.size, frame.timeUs, 0)
                                health.lastInputPtsUs = frame.timeUs
                                playbackClock?.observeVideo(frame.timeUs, input.receivedNs)
                                inputArrivals.record(frame.timeUs, input.receivedNs)
                                lastCodecActivityNs = System.nanoTime()
                                inputStallSinceNs = 0
                                progressed = true
                                awaitingKeyframe = false
                            } else {
                                decoderState = "等待解码缓冲区"
                                // A briefly busy codec has not lost this frame. Drain its
                                // output below, then retry the same input instead of resetting
                                // the decoder and discarding pictures until the next IDR.
                                pending = input
                                if (inputStallSinceNs == 0L) inputStallSinceNs = System.nanoTime()
                            }
                        }
                    } catch (failure: Exception) {
                        failed("视频解码", failure)
                    }
                }
                val current = codec ?: continue
                try {
                    while (running) {
                        val index = current.dequeueOutputBuffer(info, 0)
                        if (index >= 0) {
                            progressed = true
                            lastCodecActivityNs = System.nanoTime()
                            val receivedNs = inputArrivals.take(info.presentationTimeUs)
                            health.lastOutputPtsUs = info.presentationTimeUs
                            playbackClock?.observeVideoOutput(info.presentationTimeUs)
                            val nowNs = System.nanoTime()
                            // Keep catch-up state until the real input queue is
                            // drained; precomputing normal pacing here would
                            // reset its preview throttle on every queued frame.
                            val verifyingRepair = repair.snapshot().state == VideoRepairState.VERIFYING
                            val fallback = if (verifyingRepair) nowNs else if (hasBacklog()) null else
                                presentationClock.presentationTime(info.presentationTimeUs, nowNs)
                            val audioTarget = if (receivedNs != null && !verifyingRepair)
                                playbackClock?.videoTargetNs(info.presentationTimeUs, nowNs) else null
                            val frame = VideoOutputFrame(index, info.presentationTimeUs, receivedNs, nowNs,
                                width, height, fallback, audioTarget ?: fallback, audioTarget != null)
                            decoded.incrementAndGet()
                            lastOutputAt = SystemClock.elapsedRealtime()
                            // Never sleep with a codec output in hand. Keep feeding
                            // inputs while its deadline is pending; free older
                            // outputs early when the hardware runs out of buffers.
                            outputs.offer(frame)?.let { displaced ->
                                refreshOutput(displaced, nowNs, hasBacklog())
                                releaseOutput(displaced, nowNs)
                            }
                            presentationQueueDepth = outputs.size
                            drainPresentations()
                        } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            progressed = true
                            val output = current.outputFormat
                            fun integer(key: String): Int? = runCatching {
                                if (output.containsKey(key)) output.getInteger(key) else null
                            }.getOrNull()
                            health.format(SystemClock.elapsedRealtime(), VideoFormat(integer("width"), integer("height"),
                                integer("color-format"), integer("color-standard"), integer("color-transfer"),
                                integer("color-range"), integer("stride"), integer("slice-height")))
                            width = if (output.containsKey("crop-right"))
                                output.getInteger("crop-right") - (if (output.containsKey("crop-left")) output.getInteger("crop-left") else 0) + 1 else output.getInteger(MediaFormat.KEY_WIDTH)
                            height = if (output.containsKey("crop-bottom"))
                                output.getInteger("crop-bottom") - (if (output.containsKey("crop-top")) output.getInteger("crop-top") else 0) + 1 else output.getInteger(MediaFormat.KEY_HEIGHT)
                            if (announced) firstFrame(width, height)
                        } else break
                    }
                    if (drainPresentations()) progressed = true
                    if (pending != null && inputStallSinceNs != 0L &&
                        System.nanoTime() - inputStallSinceNs >= PlaybackTiming.BUFFER_NS) {
                        if (drainPresentations(forceProgress = true)) progressed = true
                    }
                } catch (failure: Exception) {
                    failed("视频输出", failure)
                }
                if (!progressed) Thread.sleep(1)
            }
        } catch (_: InterruptedException) {
            // Closing a session interrupts the idle decoder wait.
        } finally { replay.clear(); release("投屏结束"); repair.stop(SystemClock.elapsedRealtime()); decoderState = "已停止" }
    }

    fun close() { running = false; queue.close(); worker.interrupt() }
}
