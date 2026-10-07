package com.kanayama.wifiscreen

data class RtpPacket(val sequence: Int, val payload: ByteArray) {
    companion object {
        fun parse(data: ByteArray, size: Int): RtpPacket? {
            if (size !in 12..data.size || (data[0].toInt() and 0xc0) != 0x80 ||
                (data[1].toInt() and 0x7f) != 33) return null
            var start = 12 + (data[0].toInt() and 15) * 4
            if (start > size) return null
            if ((data[0].toInt() and 0x10) != 0) {
                if (start + 4 > size) return null
                val words = ((data[start + 2].toInt() and 255) shl 8) or (data[start + 3].toInt() and 255)
                start += 4 + words * 4
            }
            val padding = if ((data[0].toInt() and 0x20) != 0) data[size - 1].toInt() and 255 else 0
            if ((data[0].toInt() and 0x20) != 0 && padding == 0) return null
            val end = size - padding
            if (start >= end || (end - start) % 188 != 0) return null
            val sequence = ((data[2].toInt() and 255) shl 8) or (data[3].toInt() and 255)
            return RtpPacket(sequence, data.copyOfRange(start, end))
        }
    }
}
