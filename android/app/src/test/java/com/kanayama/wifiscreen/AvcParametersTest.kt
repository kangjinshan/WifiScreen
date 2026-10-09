package com.kanayama.wifiscreen

import org.junit.Assert.*
import org.junit.Test

class AvcParametersTest {
    // Independently encoded libx264 160x96, two slices per picture, 8-bit 4:2:0 fixtures.
    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val baseline = hex("000000016742c00bda0a36c05a808080a0000003002000000791e28554")
    private val pps = hex("0000000168ce0fc8")
    private val first = hex("000000016588843a0c6000f02013bad8208ba9321f5e00a602146cdda8b1008c96201300")
    private val second = hex("00000001650f88843a118a00022471c000407e3800087746603800083380008250000807")
    private val next = hex("00000001419a20cbc97c182d9784fe03d37c04c4c8d0cbfe4888de001636ed8e24afa66e")

    @Test fun parsesBaseline709AndHigh2020WithoutConfusingAndroidEnums() {
        val s = AvcParameters.sps(baseline)!!
        assertEquals(AvcColor(1, 1, 1, false), s.color)
        assertEquals(60, s.macroblocks)
        assertEquals(66, s.profile)
        val high = AvcParameters.sps(hex("000000016764000bacb2051b602dc2440250000003001000000303c8f142a480"))!!
        assertEquals(100, high.profile)
        assertEquals(AvcColor(9, 16, 9, true), high.color)
        assertEquals(8, high.bitDepthLuma)
        assertEquals(1, high.chromaFormat)
    }
    @Test fun absentColorRemainsUnknownInsteadOfInventing709OrLimitedRange() {
        val s = AvcParameters.sps(hex("000000016742c00bda0a36c044000003000400000300f23c50aa80"))!!
        assertEquals(AvcColor(), s.color)
    }
    @Test fun identifiesProgressiveIdrStartContinuationAndNextPicture() {
        val s = AvcParameters.sps(baseline); val p = AvcParameters.pps(pps)
        val a = AvcParameters.slice(first, s, p)!!
        val b = AvcParameters.slice(second, s, p)!!
        val c = AvcParameters.slice(next, s, p)!!
        assertTrue(a.idr); assertEquals(0, a.firstMb)
        assertTrue(b.idr); assertEquals(30, b.firstMb)
        assertEquals(a, b.copy(firstMb = 0))
        assertFalse(c.idr); assertEquals(0, c.firstMb)
    }
    @Test fun repairSubmitsAllIdrSlicesAsOneUnmodifiedAccessUnit() {
        val a = first.copyOf(); val b = second.copyOf()
        assertArrayEquals(first + second, AvcParameters.joinIdr(listOf(a, b)))
        assertArrayEquals(first, a); assertArrayEquals(second, b)
        assertThrows(IllegalArgumentException::class.java) { AvcParameters.joinIdr(listOf(first, next)) }
        assertThrows(IllegalArgumentException::class.java) { AvcParameters.joinIdr(emptyList()) }
    }
    @Test fun rejectsAuxiliarySlicesFieldsFmoAndUnknownParameterReferencesForRepair() {
        val s = AvcParameters.sps(baseline)!!; val p = AvcParameters.pps(pps)!!
        assertNull(AvcParameters.slice(first.copyOf().apply { this[4] = 0x73 }, s, p))
        assertNull(AvcParameters.slice(first, s.copy(frameOnly = false), p))
        assertNull(AvcParameters.slice(first, s, p.copy(sliceGroups = 2)))
        assertNull(AvcParameters.slice(first, s, p.copy(id = 1)))
        assertNull(AvcParameters.slice(first, s, p.copy(spsId = 1)))
    }
    @Test fun malformedAndTruncatedMetadataCannotThrowOrMutateSource() {
        val saved = baseline.copyOf()
        for (length in 0..10) assertNull(AvcParameters.sps(baseline.copyOf(length)))
        assertNull(AvcParameters.sps(hex("0000000167420000000000000000000000000000")))
        assertNull(AvcParameters.pps(ByteArray(0)))
        assertNull(AvcParameters.slice(hex("0000000165"), AvcParameters.sps(baseline), AvcParameters.pps(pps)))
        AvcParameters.sps(baseline)
        assertArrayEquals(saved, baseline)
    }
}
