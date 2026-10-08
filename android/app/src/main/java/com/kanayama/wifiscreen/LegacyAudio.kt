package com.kanayama.wifiscreen

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

class LegacyAudio(private val audible: () -> Boolean, private val error: (String) -> Unit,
    private val playbackClock: AvPlaybackClock = AvPlaybackClock()) {
    private val queue = AudioRtpQueue()
    private val sampleClock = AudioSampleClock()
    @Volatile private var running = true
    @Volatile private var lastPacketAtNs = 0L
    @Volatile var volume = 0.7f
    val packets = AtomicLong()
    val pcmBytes = AtomicLong()
    val decodedSamples = AtomicLong()
    val inputRetries = AtomicLong()
    val concealedSamples = AtomicLong()
    val starvationSamples = AtomicLong()
    val skippedSamples = AtomicLong()
    val overlapSamples = AtomicLong()
    val timestampResets = AtomicLong()
    val timestampFallbacks: Long get() = sampleClock.timestampFallbacks
    @Volatile var playing = false
        private set
    @Volatile var playedFrames = 0L
        private set
    @Volatile var hardwareClock = false
        private set
    @Volatile var hardwareLagMs = 0L
        private set
    val queueDepth: Int get() = queue.size
    val missingPackets: Long get() = queue.missingPackets
    val reorderedPackets: Long get() = queue.reorderedPackets
    val duplicatePackets: Long get() = queue.duplicatePackets
    val latePackets: Long get() = queue.latePackets
    val overflowPackets: Long get() = queue.overflowPackets
    val jitterMs: Double get() = queue.jitterMs
    @Volatile var underruns = 0
        private set
    @Volatile var bufferedMs = 0
        private set
    @Volatile var platformBufferMs = 0
        private set
    @Volatile var bufferCapacityMs = 0
        private set
    @Volatile var codecName = ""
        private set
    @Volatile var ready = false
        private set
    @Volatile var errorMessage = ""
        private set
    private val worker = Thread(::play, "WifiScreen-AAC").apply { start() }

    fun offer(rtp: ByteArray) {
        val data = payload(rtp) ?: return
        packets.incrementAndGet()
        if (!running || !audible() || !ready) return
        val receivedNs = System.nanoTime()
        lastPacketAtNs = receivedNs
        val header = ByteBuffer.wrap(rtp)
        queue.offer(AudioRtpFrame(header.getShort(2).toInt() and 65535,
            header.getInt(4).toLong() and 0xffffffffL, data,
            ssrc = header.getInt(8).toLong() and 0xffffffffL), receivedNs)
    }

    private fun play() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        var outputRate = AudioSampleClock.SAMPLE_RATE
        var outputChannels = 2
        var pending: AudioRtpFrame? = null
        var activeSsrc: Long? = null
        var firstOutputPts: Long? = null
        var writtenFrames = 0L
        var previousPlaybackHead = 0L
        var trackPlayedFrames = 0L
        val hardwareTimestamp = AudioTimestamp()
        var timestampPolledAt = 0L
        var hardwareFrame = -1L
        var hardwareAtNs = 0L
        var hardwareProgressAtNs = 0L
        var prerollWritten = 0
        var prerollDeadlineNs = 0L
        var prerollOriginNs = 0L
        var trackPlaying = false
        var savedUnderruns = 0
        var continuity = AudioContinuity(outputRate, outputChannels)
        var countedConcealed = 0L
        var countedStarvation = 0L
        var countedSkipped = 0L
        var countedOverlap = 0L
        var prerollBytes = 0
        var baselineFrames = 0
        var baselineRate = 0
        var baselineChannels = 0
        val info = MediaCodec.BufferInfo()

        fun updateContinuityCounters() {
            concealedSamples.addAndGet(continuity.concealedSamples - countedConcealed)
            starvationSamples.addAndGet(continuity.starvationSamples - countedStarvation)
            skippedSamples.addAndGet(continuity.skippedSamples - countedSkipped)
            overlapSamples.addAndGet(continuity.overlapSamples - countedOverlap)
            countedConcealed = continuity.concealedSamples; countedStarvation = continuity.starvationSamples
            countedSkipped = continuity.skippedSamples; countedOverlap = continuity.overlapSamples
        }
        fun resetContinuity() {
            updateContinuityCounters()
            continuity = AudioContinuity(outputRate, outputChannels)
            countedConcealed = 0; countedStarvation = 0; countedSkipped = 0; countedOverlap = 0
            playbackClock.resetAudio(outputRate)
        }

        fun clearTrackState() {
            trackPlaying = false; writtenFrames = 0; prerollWritten = 0
            playing = false; bufferedMs = 0; previousPlaybackHead = 0; trackPlayedFrames = 0
            hardwareClock = false; hardwareFrame = -1; hardwareAtNs = 0; timestampPolledAt = 0
            hardwareProgressAtNs = 0; hardwareLagMs = 0
            prerollDeadlineNs = 0; prerollOriginNs = 0
            firstOutputPts = null
            playbackClock.resetAudio(outputRate)
        }
        fun updatePlaybackState() {
            track?.let { output ->
                val head = output.playbackHeadPosition.toLong() and 0xffffffffL
                val advanced = (head - previousPlaybackHead) and 0xffffffffL
                playedFrames += advanced; trackPlayedFrames += advanced
                previousPlaybackHead = head
                playing = output.playState == AudioTrack.PLAYSTATE_PLAYING
                bufferedMs = ((writtenFrames - trackPlayedFrames).coerceAtLeast(0) * 1000 / outputRate).toInt()
                val nowNs = System.nanoTime()
                if (playing && nowNs - timestampPolledAt >= 20_000_000L) {
                    timestampPolledAt = nowNs
                    if (runCatching { output.getTimestamp(hardwareTimestamp) }.getOrDefault(false)) {
                        if (hardwareTimestamp.framePosition != hardwareFrame) hardwareProgressAtNs = nowNs
                        hardwareFrame = hardwareTimestamp.framePosition
                        hardwareAtNs = hardwareTimestamp.nanoTime
                    }
                }
                hardwareLagMs = if (hardwareFrame >= 0) (trackPlayedFrames - hardwareFrame) * 1000 / outputRate else 0
                hardwareClock = playing && AudioTimestampQuality.usable(trackPlayedFrames, hardwareFrame,
                    writtenFrames, hardwareAtNs, hardwareProgressAtNs, nowNs, outputRate)
                if (hardwareClock) playbackClock.updateAudio(hardwareFrame, hardwareAtNs, true)
                else playbackClock.updateAudio(trackPlayedFrames, nowNs, playing)
                if (Build.VERSION.SDK_INT >= 24) underruns = savedUnderruns + output.underrunCount
            }
        }
        fun closeTrack() {
            runCatching { updatePlaybackState() }
            if (Build.VERSION.SDK_INT >= 24) savedUnderruns += runCatching { track?.underrunCount ?: 0 }.getOrDefault(0)
            runCatching { track?.pause() }; runCatching { track?.flush() }
            runCatching { track?.stop() }; runCatching { track?.release() }
            track = null
            clearTrackState()
        }
        fun makeTrack(): AudioTrack {
            val mask = if (outputChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val bytesPerFrame = outputChannels * 2
            val minimum = AudioTrack.getMinBufferSize(outputRate, mask, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
            fun create(size: Int): AudioTrack = if (Build.VERSION.SDK_INT >= 23) AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(outputRate).setChannelMask(mask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(size).setTransferMode(AudioTrack.MODE_STREAM).build()
            else @Suppress("DEPRECATION") AudioTrack(AudioManager.STREAM_MUSIC, outputRate, mask,
                AudioFormat.ENCODING_PCM_16BIT, size, AudioTrack.MODE_STREAM)
            if (baselineRate != outputRate || baselineChannels != outputChannels) {
                // Keep the device's existing mixer buffer. The user budget is an
                // additional 20ms, not a replacement for the platform's required buffer.
                val probe = create(minimum)
                baselineFrames = if (Build.VERSION.SDK_INT >= 23) probe.bufferSizeInFrames else minimum / bytesPerFrame
                probe.release()
                baselineRate = outputRate; baselineChannels = outputChannels
            }
            platformBufferMs = baselineFrames * 1000 / outputRate
            prerollBytes = baselineFrames * bytesPerFrame + PlaybackTiming.audioPrerollBytes(outputRate, outputChannels)
            return create(prerollBytes).also {
                bufferCapacityMs = if (Build.VERSION.SDK_INT >= 23) it.bufferSizeInFrames * 1000 / outputRate
                    else prerollBytes * 1000 / (outputRate * bytesPerFrame)
            }
        }
        fun writePcm(block: PcmBlock) {
            val data = block.data
            if (track == null) {
                track = makeTrack()
            }
            if (prerollDeadlineNs == 0L) prerollDeadlineNs =
                (if (prerollOriginNs > 0) prerollOriginNs else System.nanoTime()) + PlaybackTiming.BUFFER_NS
            val output = track!!
            val preroll = prerollBytes
            output.setVolume(volume.coerceIn(0f, 1f))
            var offset = 0
            var noProgressSince = 0L
            while (running && offset < data.size) {
                if (!trackPlaying && prerollWritten >= preroll) {
                    while (running && System.nanoTime() < prerollDeadlineNs) Thread.sleep(1)
                    if (!running) return
                    output.play(); trackPlaying = true
                }
                val count = if (trackPlaying) data.size - offset else minOf(data.size - offset, preroll - prerollWritten)
                val written = output.write(data, offset, count)
                if (written < 0) throw IllegalStateException("AudioTrack write failed: " + written)
                if (written == 0) {
                    if (!running) return
                    if (noProgressSince == 0L) noProgressSince = System.nanoTime()
                    if (System.nanoTime() - noProgressSince > 200_000_000L)
                        throw IllegalStateException("AudioTrack stopped accepting audio")
                    Thread.sleep(2)
                    continue
                }
                noProgressSince = 0
                playbackClock.appendAudio(block.mediaSample + offset / (outputChannels * 2),
                    written / (outputChannels * 2), System.nanoTime())
                offset += written; prerollWritten += written
                writtenFrames += written / (outputChannels * 2)
                pcmBytes.addAndGet(written.toLong())
            }
            if (!trackPlaying && prerollWritten >= preroll && System.nanoTime() >= prerollDeadlineNs) {
                output.play(); trackPlaying = true
            }
            updatePlaybackState()
        }

        try {
            // Warm up while video is starting, so decoder construction cannot become
            // a permanent audio offset behind an already running video clock.
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AudioSampleClock.SAMPLE_RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
                setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0xf8.toByte(), 0xe8.toByte(), 0x50, 0)))
            }
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                codecName = it.name
                it.configure(format, null, null, 0); it.start()
            }
            track = makeTrack()
            resetContinuity()
            ready = true
            while (running) {
                val frame = pending ?: queue.poll(System.nanoTime())
                pending = null
                if (frame != null) {
                    if (activeSsrc != null && activeSsrc != frame.ssrc) {
                        codec!!.flush(); closeTrack(); sampleClock.reset(); resetContinuity()
                        timestampResets.incrementAndGet()
                    }
                    activeSsrc = frame.ssrc
                    val index = codec!!.dequeueInputBuffer(2_000)
                    if (index >= 0) {
                        val input = codec!!.getInputBuffer(index)!!
                        input.clear()
                        if (input.remaining() < frame.data.size) throw IllegalArgumentException("AAC packet too large")
                        input.put(frame.data)
                        if (prerollOriginNs == 0L) prerollOriginNs = frame.receivedNs
                        val samples = sampleClock.position(frame.sequence, frame.timestamp)
                        playbackClock.observeAudio(samples * outputRate / AudioSampleClock.SAMPLE_RATE, frame.receivedNs)
                        // The fixed-duration clock preserves gaps without trusting legacy RTP timestamp units.
                        codec!!.queueInputBuffer(index, 0, frame.data.size, samples * 1_000_000L / AudioSampleClock.SAMPLE_RATE, 0)
                    } else {
                        pending = frame
                        inputRetries.incrementAndGet()
                    }
                }
                val decoder = codec
                if (decoder != null) while (running) {
                    val outputIndex = decoder.dequeueOutputBuffer(info, 0)
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val output = decoder.outputFormat
                        val rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val changed = rate != outputRate || channels != outputChannels
                        if (changed) closeTrack()
                        outputRate = rate; outputChannels = channels
                        if (changed) resetContinuity()
                    } else if (outputIndex >= 0) {
                        val output = decoder.getOutputBuffer(outputIndex)!!
                        output.position(info.offset); output.limit(info.offset + info.size)
                        val pcm = ByteArray(info.size); output.get(pcm)
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if (pcm.isNotEmpty() && audible()) {
                            decodedSamples.addAndGet((pcm.size / (outputChannels * 2)).toLong())
                            if (firstOutputPts == null) firstOutputPts = info.presentationTimeUs
                            val position = ((info.presentationTimeUs - firstOutputPts!!) * outputRate + 500_000) / 1_000_000
                            continuity.accept(position, pcm, System.nanoTime()).forEach(::writePcm)
                            updateContinuityCounters()
                        }
                    } else break
                }
                track?.let { output ->
                    if (!trackPlaying && prerollWritten >= prerollBytes && System.nanoTime() >= prerollDeadlineNs) {
                        output.play(); trackPlaying = true
                    }
                    updatePlaybackState()
                }
                if (trackPlaying && audible()) {
                    val lowWater = maxOf(outputRate * 4 / 1000 + baselineFrames / 2,
                        AudioSampleClock.SAMPLES_PER_PACKET * outputRate / AudioSampleClock.SAMPLE_RATE)
                    continuity.protectBuffer((writtenFrames - trackPlayedFrames).coerceAtLeast(0), lowWater,
                        System.nanoTime(), lastPacketAtNs)?.let(::writePcm)
                    updateContinuityCounters()
                }
                if (frame == null) Thread.sleep(2)
            }
        } catch (failure: Exception) {
            if (running) {
                errorMessage = "音频解码：" + (failure.message ?: failure.javaClass.simpleName)
                error(errorMessage)
            }
            running = false
        } finally {
            ready = false
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            closeTrack()
        }
    }

    fun close() { running = false; queue.close(); worker.interrupt() }

    companion object {
        fun payload(packet: ByteArray): ByteArray? {
            if (packet.size < 12 || packet[0].toInt() and 0xc0 != 0x80 ||
                packet[1].toInt() and 0x7f != 96) return null
            var offset = 12 + (packet[0].toInt() and 15) * 4
            if (packet[0].toInt() and 16 != 0) {
                if (offset + 4 > packet.size) return null
                val words = ((packet[offset + 2].toInt() and 255) shl 8) or (packet[offset + 3].toInt() and 255)
                offset += 4 + words * 4
            }
            val padding = if (packet[0].toInt() and 32 != 0) packet.last().toInt() and 255 else 0
            if (packet[0].toInt() and 32 != 0 && padding == 0) return null
            val end = packet.size - padding
            if (offset >= end || padding > packet.size - offset) return null
            // MPEG4-generic may carry a 16-bit AU-size section.
            if (end - offset >= 4 && packet[offset].toInt() == 0 && packet[offset + 1].toInt() == 16) {
                val size = ((packet[offset + 2].toInt() and 255) shl 5) or ((packet[offset + 3].toInt() and 255) ushr 3)
                if (size == end - offset - 4) offset += 4
            }
            return packet.copyOfRange(offset, end)
        }
    }
}
