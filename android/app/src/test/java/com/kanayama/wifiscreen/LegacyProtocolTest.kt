package com.kanayama.wifiscreen

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class LegacyProtocolTest {
    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun readsWireHeaderEndianAndFractionalTimestamp() {
        val header = hex("230000000100060000000080050000000000f04300008744").copyOf(128)
        val frame = LegacyAvc.frame(header, ByteArray(35))
        assertEquals(1, frame.kind)
        assertEquals(480, frame.width)
        assertEquals(1080, frame.height)
        assertEquals(5_500_000L, frame.timeUs)
    }

    @Test fun extractsCodecParameterSets() {
        val config = LegacyAvc.configuration(hex("0142001effe100056742001e8001000468ce06e2"))
        assertArrayEquals(hex("000000016742001e80"), config.sps)
        assertArrayEquals(hex("0000000168ce06e2"), config.pps)
    }

    @Test fun decryptsSyntheticLegacyKeyframeVector() {
        val wire = hex("000000416599a46469990338fd56fc548341aef86cea53d3d4c5cc083fe7d0f75fc95638b52122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f40")
        val expected = hex("0000000165") + (1..64).map { it.toByte() }.toByteArray()
        assertArrayEquals(expected, LegacyAvc.accessUnit(wire, "fixture-time"))
    }

    @Test fun leavesNonKeyPicturesUnencrypted() {
        assertArrayEquals(hex("0000000141010203"), LegacyAvc.accessUnit(hex("0000000441010203"), ""))
    }

    @Test fun rejectsTruncatedAndUnboundedFrames() {
        assertThrows(IOException::class.java) { LegacyAvc.payloadSize(ByteArray(127)) }
        assertThrows(IOException::class.java) { LegacyAvc.payloadSize(hex("ffffff7f").copyOf(128)) }
        assertThrows(IOException::class.java) { LegacyAvc.configuration(hex("0142001effe100ff6742")) }
        assertThrows(IOException::class.java) { LegacyAvc.accessUnit(hex("000000104100"), "") }
        assertThrows(IOException::class.java) { LegacyAvc.accessUnit(hex("0000000165"), "") }
    }

    @Test fun stripsAudioRtpAndOptionalAuHeader() {
        val header = hex("806000010000000000000001")
        assertArrayEquals(hex("11223344"), LegacyAudio.payload(header + hex("0010002011223344")))
        assertArrayEquals(hex("11223344"), LegacyAudio.payload(header + hex("11223344")))
    }

    @Test fun rejectsInvalidAudioPaddingAndPayloadType() {
        assertNull(LegacyAudio.payload(hex("80610001000000000000000111223344")))
        assertNull(LegacyAudio.payload(hex("a0600001000000000000000111223300")))
        assertNull(LegacyAudio.payload(ByteArray(4)))
    }
}
