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
    private val error: (String) -> Unit
) {
    private data class Input(val frame: LegacyFrame, val streamTime: String, val receivedNs: Long)
    private val queue = VideoInputQueue<Input>(32)
    @Volatile private var running = true
    val received = AtomicLong()
    val decoded = AtomicLong()
    val presented = AtomicLong()
    val dropped = AtomicLong()
    val keyframes = AtomicLong()
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
            decoderState = "等待关键帧"
        }
        try {
            while (running) {
                val input = pending ?: queue.poll()
                pending = null
                if (input != null) {
                    val frame = input.frame
                    try {
                        if (frame.kind == 1) {
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
                            val unit = LegacyAvc.accessUnit(frame.data, input.streamTime)
                            val index = codec!!.dequeueInputBuffer(10_000)
                            if (index >= 0) {
                                decoderState = "正在解码"
                                val buffer = codec!!.getInputBuffer(index)!!
                                buffer.clear()
                                if (buffer.remaining() < unit.size) throw IllegalArgumentException("Video input buffer too small")
                                buffer.put(unit)
                                codec!!.queueInputBuffer(index, 0, unit.size, frame.timeUs, 0)
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
                            val targetNs = presentationClock.presentationTime(info.presentationTimeUs, System.nanoTime())
                            if (targetNs == null) {
                                current.releaseOutputBuffer(index, false)
                                dropped.incrementAndGet()
                            } else {
                                // Older TV codecs do not consistently honor the timestamped release overload.
                                // Pace in our thread, then use the widely supported immediate render call.
                                while (running && targetNs > System.nanoTime()) Thread.sleep(1)
                                if (!running) break
                                current.releaseOutputBuffer(index, true)
                                presented.incrementAndGet()
                            }
                            decoded.incrementAndGet()
                            lastOutputAt = SystemClock.elapsedRealtime()
                            if (!announced && targetNs != null) { announced = true; firstFrame(width, height) }
                        } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
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
            }
        } finally { release(); decoderState = "已停止" }
    }

    fun close() { running = false; queue.close(); worker.interrupt() }
}
