package com.kanayama.wifiscreen

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class LegacyAudio(private val audible: () -> Boolean, private val error: (String) -> Unit) {
    private val queue = ArrayBlockingQueue<ByteArray>(32)
    @Volatile private var running = true
    @Volatile var volume = 0.7f
    val packets = AtomicLong()
    val pcmBytes = AtomicLong()
    @Volatile var codecName = ""
        private set
    private val worker = Thread(::play, "WifiScreen-AAC").apply { start() }

    fun offer(rtp: ByteArray) {
        val data = payload(rtp) ?: return
        packets.incrementAndGet()
        if (!running || !audible()) return
        if (!queue.offer(data)) { queue.poll(); queue.offer(data) }
    }

    private fun play() {
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        var outputRate = 44100
        var outputChannels = 2
        var sampleTime = 0L
        val info = MediaCodec.BufferInfo()
        fun makeTrack(rate: Int, channels: Int): AudioTrack {
            val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val size = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
            return if (Build.VERSION.SDK_INT >= 23) AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(size).setTransferMode(AudioTrack.MODE_STREAM).build()
            else @Suppress("DEPRECATION") AudioTrack(AudioManager.STREAM_MUSIC, rate, mask,
                AudioFormat.ENCODING_PCM_16BIT, size, AudioTrack.MODE_STREAM)
        }
        try {
            while (running) {
                val frame = try { queue.poll(20, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null } ?: continue
                if (codec == null) {
                    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
                        // ISO AudioSpecificConfig: ELD, 44.1kHz, stereo, 480 samples.
                        setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0xf8.toByte(), 0xe8.toByte(), 0x50, 0)))
                    }
                    codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                        codecName = it.name
                        it.configure(format, null, null, 0); it.start()
                    }
                }
                val index = codec!!.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val input = codec!!.getInputBuffer(index)!!
                    input.clear()
                    if (input.remaining() < frame.size) throw IllegalArgumentException("AAC packet too large")
                    input.put(frame)
                    codec!!.queueInputBuffer(index, 0, frame.size, sampleTime, 0)
                    sampleTime += 480L * 1_000_000L / 44100
                }
                while (running) {
                    val outputIndex = codec!!.dequeueOutputBuffer(info, 0)
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val output = codec!!.outputFormat
                        outputRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outputChannels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        track?.stop(); track?.release(); track = null
                    } else if (outputIndex >= 0) {
                        val output = codec!!.getOutputBuffer(outputIndex)!!
                        output.position(info.offset); output.limit(info.offset + info.size)
                        val pcm = ByteArray(info.size)
                        output.get(pcm)
                        codec!!.releaseOutputBuffer(outputIndex, false)
                        if (pcm.isNotEmpty() && audible()) {
                            if (track == null) track = makeTrack(outputRate, outputChannels).also { it.play() }
                            track!!.setVolume(volume.coerceIn(0f, 1f))
                            var offset = 0
                            while (running && offset < pcm.size) {
                                val written = track!!.write(pcm, offset, pcm.size - offset)
                                if (written <= 0) break
                                offset += written
                                pcmBytes.addAndGet(written.toLong())
                            }
                        }
                    } else break
                }
            }
        } catch (failure: Exception) {
            if (running) error("音频解码：" + (failure.message ?: failure.javaClass.simpleName))
            running = false
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            runCatching { track?.stop() }; runCatching { track?.release() }
        }
    }

    fun close() { running = false; queue.clear(); worker.interrupt() }

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
