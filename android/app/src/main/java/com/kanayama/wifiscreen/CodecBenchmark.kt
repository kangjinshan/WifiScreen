package com.kanayama.wifiscreen

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

data class CodecTestResult(val avc: DecodeTrial, val hevc: DecodeTrial, val network: NetworkTrial) {
    val advice: CodecAdvice get() = CodecRecommendation.evaluate(avc, hevc, network)
    fun description(): String = buildString {
        appendLine(advice.recommended?.let { "推荐 ${it.label}" } ?: "暂不能给出推荐")
        appendLine(advice.reason)
        for (test in listOf(avc, hevc)) {
            appendLine()
            append(test.encoding.label).append("：")
            if (test.error != null) appendLine(test.error)
            else appendLine(String.format(Locale.ROOT, "%.1f 帧/秒 · 解码 P95 %d ms · %s解码",
                test.fps, test.p95Ms, if (test.hardware) "硬件" else "软件"))
        }
        appendLine()
        append(network.transport)
        network.rssi?.let { append(" · 信号 $it dBm") }
        network.linkMbps?.let { append(" · 链路 $it Mbps") }
        appendLine()
        appendLine(network.p95Ms?.let { "网关延迟 P95 $it ms（${network.replies}/${network.probes} 次响应）" }
            ?: "网关未响应探测或不可测，不能据此认定网络差。")
        append("结果基于 1080p30 本机解码和局域网参考指标；未测手机编码、实际投屏吞吐或端到端延迟。")
    }
}

/** Two sequential five-second decoder trials, with LAN sampling alongside both. */
class CodecBenchmark(private val context: Context, private val address: String) {
    private val cancelled = AtomicBoolean()
    val isCancelled: Boolean get() = cancelled.get()
    fun cancel() { cancelled.set(true) }
    private fun checkCancelled() { if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException() }
    private data class Clip(val format: MediaFormat, val samples: List<ByteArray>)

    private fun load(encoding: VideoEncoding): Clip {
        val extractor = MediaExtractor()
        try {
            context.assets.openFd(encoding.asset).use { extractor.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == encoding.mime }
            extractor.selectTrack(track)
            val samples = mutableListOf<ByteArray>()
            val buffer = ByteBuffer.allocate(LegacyAvc.MAX_PAYLOAD)
            while (samples.size < 120) {
                checkCancelled(); buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                buffer.position(0)
                samples.add(ByteArray(size).also { buffer.get(it) })
                if (!extractor.advance()) break
            }
            check(samples.isNotEmpty()) { "测试片段为空" }
            return Clip(extractor.getTrackFormat(track), samples)
        } finally { extractor.release() }
    }

    fun run(progress: (Int, VideoEncoding) -> Unit): CodecTestResult {
        // File preparation precedes the visible ten-second sampling window.
        val clips = VideoEncoding.values().associateWith { load(it) }
        val probe = CodecNetworkProbe(context, address)
        val started = SystemClock.elapsedRealtime()
        val finished = AtomicBoolean()
        val networkThread = Thread({
            while (!finished.get() && !cancelled.get() && SystemClock.elapsedRealtime() - started < 10_000) {
                runCatching { probe.sample() }
                try { Thread.sleep(300) } catch (_: InterruptedException) { break }
            }
        }, "WifiScreen-CodecNetwork").apply { start() }
        try {
            val avc = trial(VideoEncoding.H264, clips.getValue(VideoEncoding.H264), 0, progress)
            checkCancelled()
            val hevc = trial(VideoEncoding.H265, clips.getValue(VideoEncoding.H265), 5, progress)
            checkCancelled()
            return CodecTestResult(avc, hevc, probe.result())
        } finally {
            finished.set(true); networkThread.interrupt(); networkThread.join(500)
        }
    }

    private fun trial(encoding: VideoEncoding, clip: Clip, elapsedSeconds: Int,
        progress: (Int, VideoEncoding) -> Unit): DecodeTrial {
        val trialStart = SystemClock.elapsedRealtime()
        val deadline = trialStart + 5000
        val device = DeviceCodecs.find(encoding)
        var codec: MediaCodec? = null
        var texture: SurfaceTexture? = null
        var surface: Surface? = null
        var outputs = 0
        var inputs = 0L
        var failure: String? = null
        var elapsed = 0L
        var lastProgress = -1
        val arrivals = mutableMapOf<Long, Long>()
        val latencies = java.util.ArrayDeque<Long>()
        fun updateProgress() {
            checkCancelled()
            val elapsed = elapsedSeconds + ((SystemClock.elapsedRealtime() - trialStart) / 1000).toInt().coerceIn(0, 5)
            if (elapsed != lastProgress) { lastProgress = elapsed; progress(10 - elapsed, encoding) }
        }
        try {
            updateProgress()
            if (device == null) throw IllegalStateException("设备无可用的 1080p 解码器")
            texture = SurfaceTexture(0).apply { setDefaultBufferSize(1920, 1080) }
            surface = Surface(texture)
            codec = MediaCodec.createByCodecName(device.name)
            clip.format.apply {
                if (Build.VERSION.SDK_INT >= 23) setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec.configure(clip.format, surface, null, 0); codec.start()
            val info = MediaCodec.BufferInfo()
            while (SystemClock.elapsedRealtime() < deadline) {
                updateProgress()
                var advanced = false
                // Bounded in-flight work; no artificial frame pacing during the throughput trial.
                if (arrivals.size < 8) {
                    val index = codec.dequeueInputBuffer(0)
                    if (index >= 0) {
                        val bytes = clip.samples[(inputs % clip.samples.size).toInt()]
                        val buffer = codec.getInputBuffer(index)!!
                        buffer.clear(); buffer.put(bytes)
                        val pts = inputs * 1_000_000 / 30
                        arrivals[pts] = SystemClock.elapsedRealtime()
                        codec.queueInputBuffer(index, 0, bytes.size, pts, 0)
                        inputs++; advanced = true
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 0)
                if (index >= 0) {
                    arrivals.remove(info.presentationTimeUs)?.let {
                        latencies.addLast(SystemClock.elapsedRealtime() - it)
                        if (latencies.size > 1024) latencies.removeFirst()
                    }
                    outputs++
                    // Decode into the same surface path used for casting, but avoid display pacing.
                    codec.releaseOutputBuffer(index, false); advanced = true
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) advanced = true
                if (!advanced) Thread.sleep(1)
            }
        } catch (cancel: CancellationException) { throw cancel
        } catch (failureException: Exception) {
            failure = failureException.message?.take(160) ?: "解码测试失败"
        } finally {
            elapsed = SystemClock.elapsedRealtime() - trialStart
            if (!cancelled.get() && SystemClock.elapsedRealtime() >= deadline) progress(5 - elapsedSeconds, encoding)
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            surface?.release(); texture?.release()
        }
        // Unsupported codecs still keep the ten-second network sample and countdown consistent.
        while (SystemClock.elapsedRealtime() < deadline) { updateProgress(); Thread.sleep(50) }
        checkCancelled()
        val sorted = latencies.sorted()
        return DecodeTrial(encoding, device?.name.orEmpty(), device?.hardware == true, outputs,
            maxOf(elapsed, 1), sorted.getOrNull(((sorted.size - 1) * .95).toInt()) ?: 0, failure)
    }
}
