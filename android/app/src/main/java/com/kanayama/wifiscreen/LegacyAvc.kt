package com.kanayama.wifiscreen

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class LegacyFrame(val kind: Int, val timeUs: Long, val width: Int, val height: Int, val data: ByteArray)
data class AvcConfiguration(val sps: ByteArray, val pps: ByteArray)

/** Wire-format helpers. Configuration records are AVC; picture units use a length prefix. */
object LegacyAvc {
    const val HEADER_SIZE = 128
    const val MAX_PAYLOAD = 2 * 1024 * 1024
    private val startCode = byteArrayOf(0, 0, 0, 1)

    fun payloadSize(header: ByteArray): Int {
        if (header.size != HEADER_SIZE) throw IOException("Invalid video header")
        val size = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
        if (size !in 0..MAX_PAYLOAD) throw IOException("Video packet exceeds limit")
        return size
    }

    fun frame(header: ByteArray, payload: ByteArray): LegacyFrame {
        if (payloadSize(header) != payload.size) throw IOException("Truncated video packet")
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val kind = buffer.getShort(4).toInt() and 65535
        val fraction = buffer.getInt(8).toLong() and 0xffffffffL
        val seconds = buffer.getInt(12).toLong() and 0xffffffffL
        fun dimension(offset: Int, fallback: Int): Int {
            val value = buffer.getFloat(offset)
            return if (value.isFinite() && value in 16f..4096f) value.toInt() else fallback
        }
        return LegacyFrame(kind, seconds * 1_000_000L + fraction * 1_000_000L / 0x100000000L,
            dimension(16, 1920), dimension(20, 1080), payload)
    }

    fun configuration(data: ByteArray): AvcConfiguration {
        if (data.size < 7 || data[0].toInt() != 1) throw IOException("Invalid AVC configuration")
        if (data[4].toInt() and 3 != 3) throw IOException("Unsupported AVC length size")
        var position = 6
        fun unit(): ByteArray {
            if (position + 2 > data.size) throw IOException("Missing AVC parameter set")
            val size = ((data[position].toInt() and 255) shl 8) or (data[position + 1].toInt() and 255)
            position += 2
            if (size == 0 || position + size > data.size) throw IOException("Truncated AVC parameter set")
            return data.copyOfRange(position, position + size).also { position += size }
        }
        val sps = List(data[5].toInt() and 31) { unit() }
        if (position >= data.size) throw IOException("Missing PPS count")
        val pps = List(data[position++].toInt() and 255) { unit() }
        if (sps.isEmpty() || pps.isEmpty() || sps[0][0].toInt() and 31 != 7 || pps[0][0].toInt() and 31 != 8)
            throw IOException("Invalid SPS/PPS")
        return AvcConfiguration(startCode + sps[0], startCode + pps[0])
    }

    fun isKeyframe(data: ByteArray): Boolean = data.size > 4 && (data[4].toInt() and 31) in listOf(5, 19)

    fun accessUnit(data: ByteArray, streamTime: String): ByteArray {
        if (data.size < 5 || ByteBuffer.wrap(data).int != data.size - 4) throw IOException("Invalid AVC picture length")
        val result = data.copyOf()
        if (isKeyframe(result)) {
            if (streamTime.isEmpty()) throw IOException("Missing stream initialization")
            val size = (result.size - 5) / 32 * 16
            if (size > 0) {
                fun derive(text: String) = MessageDigest.getInstance("MD5")
                    .digest(text.toByteArray(Charsets.UTF_8)).map { (it.toInt() xor 0x78).toByte() }.toByteArray()
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derive("Happycast/1.0"), "AES"),
                    IvParameterSpec(derive(streamTime)))
                cipher.doFinal(result, 5, size).copyInto(result, 5)
            }
        }
        startCode.copyInto(result)
        return result
    }
}
