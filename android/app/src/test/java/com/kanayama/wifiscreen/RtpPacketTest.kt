package com.kanayama.wifiscreen

import org.junit.Assert.*
import org.junit.Test

class RtpPacketTest {
    private fun packet(header: Int = 12, padding: Int = 0): ByteArray = ByteArray(header + 188 + padding).apply {
        this[0] = 0x80.toByte(); this[1] = 33
        this[2] = 0xff.toByte(); this[3] = 0xff.toByte()
        this[header] = 0x47
    }
    @Test fun stripsHeaderAndKeepsUnsignedSequence() {
        val p = packet()
        val parsed = RtpPacket.parse(p, p.size)!!
        assertEquals(65535, parsed.sequence)
        assertEquals(188, parsed.payload.size)
        assertEquals(0x47.toByte(), parsed.payload[0])
    }
    @Test fun handlesCsrcExtensionAndPadding() {
        val p = packet(24, 4)
        p[0] = 0xb1.toByte() // CSRC + extension + padding
        p[19] = 1 // extension has one 32-bit word, after CSRC
        p[p.lastIndex] = 4
        assertEquals(188, RtpPacket.parse(p, p.size)!!.payload.size)
    }
    @Test fun rejectsUnsupportedOrTruncatedRtp() {
        val p = packet()
        p[1] = 96
        assertNull(RtpPacket.parse(p, p.size))
        p[1] = 33
        assertNull(RtpPacket.parse(p, 10))
        assertNull(RtpPacket.parse(p, p.size + 1))
        p[0] = 0xa0.toByte()
        assertNull(RtpPacket.parse(p, p.size)) // zero padding is invalid
    }
}
