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
        val inputArrivals = linkedMapOf<Long, java.util.ArrayDeque<Long>>()
        var inFlight = 0
        val presentationClock = VideoPresentationClock()
        val info = MediaCodec.BufferInfo()
        fun release() {
            decoderState = "正在重置解码器"
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            codec = null
            configuredSurface = null
            awaitingKeyframe = true
            presentationClock.reset()
            playbackClock?.resetVideo()
            usingAudioClock = false; audioSkewMs = 0
            inputArrivals.clear(); inFlight = 0
            catchingUp = false; receiverLatencyMs = -1
            decoderState = "等待关键帧"
        }
        // Allow the hardware codec's normal pipeline. An old source timestamp
        // alone does not mean removable queueing, especially on OEM decoders.
        fun hasBacklog(): Boolean = queue.size >= 2 || inFlight > 4
        try {
            while (running) {
                var progressed = false
                // Sleep on incoming data when the codec is empty, but never
                // delay draining pending output. This also leaves CPU for audio.
                val input = pending ?: queue.poll(if (codec == null || inFlight == 0) 5 else 0)
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
                                inputArrivals.getOrPut(frame.timeUs) { java.util.ArrayDeque() }.addLast(input.receivedNs)
                                inFlight++
                                progressed = true
                                awaitingKeyframe = false
                            } else {
                                decoderState = "等待解码缓冲区"
                                // A briefly busy codec has not lost this frame. Drain its
                                // output below, then retry the same input instead of resetting
                                // the decoder and discarding pictures until the next IDR.
                                pending = input
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
                            inFlight = (inFlight - 1).coerceAtLeast(0)
                            val arrivals = inputArrivals[info.presentationTimeUs]
                            val receivedNs = arrivals?.pollFirst()
                            if (arrivals?.isEmpty() == true) inputArrivals.remove(info.presentationTimeUs)
                            if (receivedNs == null && inputArrivals.isNotEmpty()) {
                                // Some OEM codecs rewrite timestamps. Do not report
                                // a guessed latency, and keep tracking bounded.
                                val first = inputArrivals.entries.first()
                                first.value.pollFirst()
                                if (first.value.isEmpty()) inputArrivals.remove(first.key)
                            }
                            val nowNs = System.nanoTime()
                            var audioTarget = if (receivedNs != null && (usingAudioClock || !hasBacklog()))
                                playbackClock?.videoTargetNs(info.presentationTimeUs, nowNs) else null
                            usingAudioClock = audioTarget != null
                            audioSkewMs = audioTarget?.let { (it - nowNs) / 1_000_000L } ?: 0
                            catchingUp = if (usingAudioClock) audioSkewMs < -40 else hasBacklog()
                            var targetNs = if (audioTarget != null) {
                                if (audioSkewMs < -40 || playbackClock?.videoWasSkipped(info.presentationTimeUs, nowNs) == true) {
                                    audioSyncDrops.incrementAndGet(); null
                                } else maxOf(audioTarget, nowNs)
                            } else presentationClock.presentationTime(info.presentationTimeUs, nowNs, catchingUp)
                            if (targetNs != null) {
                                while (running && targetNs!! > System.nanoTime()) {
                                    if (System.nanoTime() - nowNs >= 200_000_000L) {
                                        usingAudioClock = false; targetNs = System.nanoTime(); break
                                    }
                                    if (usingAudioClock) {
                                        val clockNow = System.nanoTime()
                                        audioTarget = playbackClock?.videoTargetNs(info.presentationTimeUs, clockNow)
                                        if (audioTarget == null) {
                                            usingAudioClock = false
                                            targetNs = presentationClock.presentationTime(info.presentationTimeUs, clockNow, hasBacklog())
                                            if (targetNs == null) break
                                        } else {
                                            audioSkewMs = (audioTarget - clockNow) / 1_000_000L
                                            if (audioSkewMs < -40 || playbackClock?.videoWasSkipped(info.presentationTimeUs, clockNow) == true) {
                                                audioSyncDrops.incrementAndGet(); targetNs = null; break
                                            }
                                            targetNs = maxOf(audioTarget, clockNow)
                                        }
                                    } else if (hasBacklog()) {
                                        catchingUp = true
                                        targetNs = presentationClock.presentationTime(info.presentationTimeUs, System.nanoTime(), true)
                                        break
                                    }
                                    Thread.sleep(1)
                                }
                            }
                            if (!running) break
                            if (targetNs == null) {
                                current.releaseOutputBuffer(index, false)
                                dropped.incrementAndGet()
                            } else {
                                // Older TV codecs do not consistently honor the timestamped release overload.
                                // Use immediate rendering after the bounded pacing above.
                                current.releaseOutputBuffer(index, true)
                                receiverLatencyMs = receivedNs?.let { (System.nanoTime() - it) / 1_000_000L } ?: -1
                                presented.incrementAndGet()
                            }
                            decoded.incrementAndGet()
                            lastOutputAt = SystemClock.elapsedRealtime()
                            if (!announced && targetNs != null) { announced = true; firstFrame(width, height) }
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
