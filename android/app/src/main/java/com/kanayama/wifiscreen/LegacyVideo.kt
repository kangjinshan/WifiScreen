package com.kanayama.wifiscreen

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/** All codec operations, including release, are owned by the decoder thread. */
class LegacyVideo(
    private val surface: () -> Surface?,
    private val firstFrame: (Int, Int) -> Unit,
    private val error: (String) -> Unit,
    private val playbackClock: AvPlaybackClock? = null
) {
    private data class Input(val frame: LegacyFrame, val streamTime: String, val receivedNs: Long,
        var accessUnit: ByteArray? = null)
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
    private val worker = Thread(::decode, "WifiScreen-AVC").apply { start() }

    fun offer(frame: LegacyFrame, streamTime: String) {
        if (!running || frame.kind !in 0..1) return
        if (frame.kind == 0) {
            received.incrementAndGet()
            if (LegacyAvc.isKeyframe(frame.data)) keyframes.incrementAndGet()
        }
        // Blocking the TCP reader applies bounded backpressure without losing reference frames.
        queue.put(Input(frame, streamTime, System.nanoTime()))
    }

    private fun decode() {
        var codec: MediaCodec? = null
        var configuredSurface: Surface? = null
        var config: AvcConfiguration? = null
        var awaitingKeyframe = true
        var announced = false
        var pending: Input? = null
        val inputArrivals = VideoFrameTracker()
        val outputs = VideoPresentationQueue()
        val syncPolicy = VideoSyncPolicy()
        var lastCodecActivityNs = 0L
        var inputStallSinceNs = 0L
        val presentationClock = VideoPresentationClock()
        val info = MediaCodec.BufferInfo()
        fun release() {
            decoderState = "正在重置解码器"
            for (frame in outputs.clear()) {
                runCatching { codec?.releaseOutputBuffer(frame.index, false) }
                dropped.incrementAndGet()
            }
            presentationQueueDepth = 0; inputStallSinceNs = 0
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
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
                var progressed = false
                if (codec != null) {
                    try { progressed = drainPresentations() }
                    catch (failure: Exception) {
                        release()
                        error("视频显示：" + (failure.message ?: failure.javaClass.simpleName))
                    }
                }
                // Poll promptly after actual codec activity; idle input metadata
                // must not keep this thread spinning forever.
                val waitMs = if (outputs.size > 0) outputs.waitMs(System.nanoTime()) else
                    if (codec == null || System.nanoTime() - lastCodecActivityNs >= 10_000_000L) 5 else 0
                val input = pending ?: queue.poll(waitMs)
                pending = null
                if (input != null) {
                    val frame = input.frame
                    try {
                        if (frame.kind == 1) {
                            progressed = true
                            val parameters = LegacyAvc.configuration(frame.data)
                            val changed = config?.let { !it.sps.contentEquals(parameters.sps) || !it.pps.contentEquals(parameters.pps) } ?: true
                            config = parameters
                            if (changed) {
                                width = frame.width
                                height = frame.height
                                release()
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
                                    catch (_: Exception) { release() }
                                } else release()
                            }
                            if (awaitingKeyframe && !LegacyAvc.isKeyframe(frame.data)) continue
                            val parameters = config ?: continue
                            presentationClock.observeInput(frame.timeUs, input.receivedNs)
                            if (codec == null) {
                                decoderState = "正在启动解码器"
                                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                                    setByteBuffer("csd-0", ByteBuffer.wrap(parameters.sps))
                                    setByteBuffer("csd-1", ByteBuffer.wrap(parameters.pps))
                                    if (Build.VERSION.SDK_INT >= 23) setInteger(MediaFormat.KEY_PRIORITY, 0)
                                    if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                                }
                                // Avoid adaptive max-width/max-height hints on the target MStar codec.
                                codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
                                    codecName = it.name
                                    it.configure(format, target, null, 0)
                                    it.start()
                                }
                                configuredSurface = target
                            }
                            val unit = input.accessUnit ?: LegacyAvc.accessUnit(frame.data, input.streamTime).also { input.accessUnit = it }
                            val index = codec!!.dequeueInputBuffer(0)
                            if (index >= 0) {
                                decoderState = "正在解码"
                                val buffer = codec!!.getInputBuffer(index)!!
                                buffer.clear()
                                if (buffer.remaining() < unit.size) throw IllegalArgumentException("Video input buffer too small")
                                buffer.put(unit)
                                codec!!.queueInputBuffer(index, 0, unit.size, frame.timeUs, 0)
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
                        release()
                        error("视频解码：" + (failure.message ?: failure.javaClass.simpleName))
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
                            playbackClock?.observeVideoOutput(info.presentationTimeUs)
                            val nowNs = System.nanoTime()
                            // Keep catch-up state until the real input queue is
                            // drained; precomputing normal pacing here would
                            // reset its preview throttle on every queued frame.
                            val fallback = if (hasBacklog()) null else
                                presentationClock.presentationTime(info.presentationTimeUs, nowNs)
                            val audioTarget = if (receivedNs != null)
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
                    release()
                    error("视频输出：" + (failure.message ?: failure.javaClass.simpleName))
                }
                if (!progressed) Thread.sleep(1)
            }
        } catch (_: InterruptedException) {
            // Closing a session interrupts the idle decoder wait.
        } finally { release(); decoderState = "已停止" }
    }

    fun close() { running = false; queue.close(); worker.interrupt() }
}
