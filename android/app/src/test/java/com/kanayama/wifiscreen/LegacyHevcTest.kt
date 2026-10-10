package com.kanayama.wifiscreen

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

class LegacyHevcTest {
    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val vps = hex("40010c01ffff01600000030090000003000003005dac09")
    private val sps = hex("42010101600000030090000003000003005da00280802d165959a4932bc05a020000030002000003003210")
    private val pps = hex("4401c073c089")
    private val start = hex("00000001")
    private fun hvcc(units: List<ByteArray> = listOf(vps, sps, pps)): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { writer ->
            writer.write(hex("0101600000009000000000005df000fcfdf8f800000f"))
            writer.writeByte(units.size)
            for (unit in units) {
                writer.writeByte(128 or LegacyHevc.type(unit[0])); writer.writeShort(1)
                writer.writeShort(unit.size); writer.write(unit)
            }
        }
        return out.toByteArray()
    }
    private fun wrapped(): ByteArray {
        val blob = vps + start + sps + start + pps
        return ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
            it.write(hex("010c01ffffe1")); it.writeShort(blob.size); it.write(blob); it.write(hex("010000"))
        } }.toByteArray()
    }
    @Test fun parsesStandardHevcAndLegacyWrappedParameterSets() {
        for (data in listOf(hvcc(), wrapped())) {
            val parsed = VideoConfiguration.parse(data)
            assertEquals(VideoEncoding.H265, parsed.encoding)
            assertEquals(1, parsed.csd.size)
            assertArrayEquals(start + vps + start + sps + start + pps, parsed.csd[0])
            assertTrue(parsed.sameAs(VideoConfiguration.parse(data)))
        }
        assertFalse(VideoConfiguration.parse(hvcc()).legacyHevc)
        assertTrue(VideoConfiguration.parse(wrapped()).legacyHevc)
    }
    @Test fun avcKeepsItsSeparateCsdAndIsNeverMistakenForHevc() {
        val config = VideoConfiguration.parse(hex("0142001effe100056742001e8001000468ce06e2"))
        assertEquals(VideoEncoding.H264, config.encoding)
        assertEquals(2, config.csd.size)
        assertFalse(config.sameAs(VideoConfiguration.parse(hvcc())))
    }
    @Test fun rejectsTruncationMissingSetsWrongTypesAndLengthSize() {
        for (size in 0 until hvcc().size) {
            assertThrows(IOException::class.java) { LegacyHevc.configuration(hvcc().copyOf(size)) }
        }
        assertThrows(IOException::class.java) { LegacyHevc.configuration(hvcc(listOf(vps, sps))) }
        assertThrows(IOException::class.java) { LegacyHevc.configuration(hvcc().apply { this[21] = 0 }) }
        assertThrows(IOException::class.java) { LegacyHevc.configuration(hvcc().apply { this[23] = 34 }) }
        assertThrows(IOException::class.java) { LegacyHevc.configuration(hvcc() + byteArrayOf(0)) }
        assertThrows(IOException::class.java) { LegacyHevc.legacyConfiguration(wrapped().dropLast(1).toByteArray()) }
        assertThrows(IOException::class.java) { LegacyHevc.legacyConfiguration(wrapped().apply { this[7] = 127 }) }
    }
    @Test fun recognizesHevcRandomAccessWithoutAvcNalMasks() {
        assertTrue(LegacyHevc.isKeyframe(hex("000000042601aabb"))) // IDR_W_RADL
        assertTrue(LegacyHevc.isKeyframe(hex("000000042801aabb"))) // IDR_N_LP
        assertTrue(LegacyHevc.isKeyframe(hex("000000042a01aabb"))) // CRA
        assertFalse(LegacyHevc.isKeyframe(hex("000000040201aabb")))
        assertArrayEquals(hex("000000012601aabb"), LegacyHevc.accessUnit(hex("000000042601aabb"), "", false))
        assertThrows(IOException::class.java) { LegacyHevc.accessUnit(hex("000000042601aabb"), "", true) }
        assertThrows(IOException::class.java) { LegacyHevc.accessUnit(hex("000000052601aabb"), "", false) }
        assertThrows(IOException::class.java) { LegacyHevc.accessUnit(hex("000000042600aabb"), "", false) }
    }
    @Test fun convertsMultipleNalUnitsAndFindsKeyframeAfterSei() {
        val packet = hex("000000034e0180000000042601aabb")
        assertTrue(LegacyHevc.isKeyframe(packet))
        assertArrayEquals(hex("000000014e0180000000012601aabb"), LegacyHevc.accessUnit(packet, "", false))
        assertThrows(IOException::class.java) { LegacyHevc.accessUnit(packet.dropLast(1).toByteArray(), "", false) }
        assertFalse(LegacyHevc.isKeyframe(hex("7fffffff2601")))
    }
    @Test fun decryptsLegacyHevcIncludingItsSecondHeaderByte() {
        // Independently encrypted with Python/PyCryptodome, including the 0x01 header byte.
        val wire = hex("0000004126cf963b028a7e644bd611051822aef9b6ea9597af48ef8051a06a222f6afb4dd2202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f")
        val original = wire.copyOf()
        val expected = start + byteArrayOf(0x26, 1) + (1..63).map { it.toByte() }.toByteArray()
        assertArrayEquals(expected, LegacyHevc.accessUnit(wire, "fixture-time", true))
        assertArrayEquals(original, wire)
    }
}
