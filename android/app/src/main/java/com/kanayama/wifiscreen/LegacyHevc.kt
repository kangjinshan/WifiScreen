package com.kanayama.wifiscreen

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/** HEVC configuration records and length-prefixed pictures; no AVC bit parsing on HEVC. */
object LegacyHevc {
    private val start = byteArrayOf(0, 0, 0, 1)
    fun type(first: Byte) = (first.toInt() and 126) shr 1
    fun isKeyframe(data: ByteArray): Boolean {
        var position = 0
        while (position + 6 <= data.size) {
            val size = ByteBuffer.wrap(data, position, 4).int
            if (size < 2 || size > data.size - position - 4) return false
            if (type(data[position + 4]) in 16..21) return true
            position += size + 4
        }
        return false
    }
    fun isLegacyConfiguration(data: ByteArray) = data.size >= 10 && data[0].toInt() == 1 &&
        data[4].toInt() and 255 == 255 && data[5].toInt() and 31 == 1 && type(data[8]) == 32

    private fun validNal(unit: ByteArray) = unit.size >= 2 && unit[0].toInt() and 128 == 0 &&
        unit[1].toInt() and 7 != 0

    private fun parameterSets(units: List<ByteArray>): ByteArray {
        if (units.isEmpty() || units.any { !validNal(it) } ||
            !units.map { type(it[0]) }.containsAll(listOf(32, 33, 34)))
            throw IOException("HEVC requires valid VPS/SPS/PPS")
        return ByteArrayOutputStream().apply {
            // MediaCodec expects all three parameter-set types together in csd-0.
            for (kind in 32..34) for (unit in units.filter { type(it[0]) == kind }) {
                write(start); write(unit)
            }
        }.toByteArray()
    }

    fun configuration(data: ByteArray): ByteArray {
        if (data.size !in 23..LegacyAvc.MAX_PAYLOAD || data[0].toInt() != 1)
            throw IOException("Invalid HEVC configuration")
        if (data[21].toInt() and 3 != 3) throw IOException("Unsupported HEVC length size")
        var position = 23
        val units = mutableListOf<ByteArray>()
        fun short(): Int {
            if (position + 2 > data.size) throw IOException("Truncated HEVC configuration")
            return (((data[position++].toInt() and 255) shl 8) or (data[position++].toInt() and 255))
        }
        repeat(data[22].toInt() and 255) {
            if (position >= data.size) throw IOException("Missing HEVC array")
            val kind = data[position++].toInt() and 63
            repeat(short()) {
                val size = short()
                if (size < 2 || position + size > data.size) throw IOException("Truncated HEVC parameter set")
                val unit = data.copyOfRange(position, position + size)
                if (!validNal(unit) || type(unit[0]) != kind) throw IOException("Invalid HEVC NAL type")
                units.add(unit); position += size
            }
        }
        if (position != data.size) throw IOException("Trailing HEVC configuration bytes")
        return parameterSets(units)
    }

    fun legacyConfiguration(data: ByteArray): ByteArray {
        if (!isLegacyConfiguration(data) || data.size > LegacyAvc.MAX_PAYLOAD)
            throw IOException("Invalid legacy HEVC configuration")
        val size = ((data[6].toInt() and 255) shl 8) or (data[7].toInt() and 255)
        // The legacy packer uses one blob containing all parameter sets, then one empty slot.
        if (size < 2 || 8 + size + 3 != data.size || data[8 + size].toInt() != 1 ||
            data[9 + size].toInt() != 0 || data[10 + size].toInt() != 0)
            throw IOException("Invalid legacy HEVC parameter sets")
        val annex = start + data.copyOfRange(8, 8 + size)
        val units = mutableListOf<ByteArray>()
        var begin = 4
        var p = 4
        while (p + 3 <= annex.size) {
            val prefix = if (annex[p].toInt() == 0 && annex[p + 1].toInt() == 0) {
                if (annex[p + 2].toInt() == 1) 3
                else if (p + 4 <= annex.size && annex[p + 2].toInt() == 0 && annex[p + 3].toInt() == 1) 4
                else 0
            } else 0
            if (prefix > 0) { units.add(annex.copyOfRange(begin, p)); begin = p + prefix; p = begin }
            else p++
        }
        units.add(annex.copyOfRange(begin, annex.size))
        return parameterSets(units)
    }

    fun accessUnit(data: ByteArray, streamTime: String, legacy: Boolean,
        cipherName: String = LegacyAvc.LEGACY_CIPHER, encrypted: Boolean = legacy): ByteArray {
        if (data.size < 6 || data.size > LegacyAvc.MAX_PAYLOAD)
            throw IOException("Invalid HEVC picture length")
        // The legacy sender encrypts IDR_W_RADL after the FIRST byte, including
        // HEVC's second header byte. Standard hvcC transport has plain NAL units.
        if (legacy) {
            if (ByteBuffer.wrap(data).int != data.size - 4) throw IOException("Invalid legacy HEVC picture length")
            val result = LegacyAvc.picture(data, streamTime, encrypted && type(data[4]) == 19, cipherName)
            if (!validNal(result.copyOfRange(4, 6))) throw IOException("Invalid HEVC picture header")
            return result
        }
        val result = data.copyOf()
        var position = 0
        while (position < result.size) {
            if (position + 6 > result.size) throw IOException("Truncated HEVC picture")
            val size = ByteBuffer.wrap(result, position, 4).int
            if (size < 2 || size > result.size - position - 4) throw IOException("Invalid HEVC NAL length")
            if (!validNal(result.copyOfRange(position + 4, position + 6))) throw IOException("Invalid HEVC picture header")
            start.copyInto(result, position)
            position += size + 4
        }
        return result
    }
}
