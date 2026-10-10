package com.kanayama.wifiscreen

import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class LelinkVideoCipherTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    // Four consecutive native-format keyframe segments, generated independently with PyCryptodome.
    private val wires = listOf(
        "000000616598106e61383363d1bd51542cfb76bb44be168c18dc8102f13e6bea78c48b30afe2b35b801ee420a671138310624e7d40303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f",
        "0000006165308d410fe6b4aa9bad707131e20a2cf031b7289b7df9665fd7b034cd3deefbb34d6a4a89eab3f68fce7765aeb41886273132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f60",
        "0000006126a63b48efbb137c14fdbeaea856d3ee2da6656cd2dc215bea7a148e1d22e908e0e231dc102a2b539a36fb904ade0d7452303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f",
        "00000061262f1e40a66c51f461e91d170e89f4b0c0a77d0779e1367fae0ce8a8d4dfc10b483eeb527b2a5bb0b11717f36e13c05de23132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f60"
    ).map(::hex)
    private fun plain(index: Int): ByteArray = ByteBuffer.allocate(4).putInt(97).array() +
        byteArrayOf(if (index < 2) 0x65 else 0x26, 1) + (1..95).map { (it + index % 2).toByte() }.toByteArray()
    @Test fun preservesCbcAcrossMultipleKeyframesAndCodecChanges() {
        val cipher = LelinkVideoCipher()
        for (i in wires.indices) {
            val original = wires[i].copyOf()
            assertArrayEquals(plain(i), cipher.clearPicture(wires[i], "v2-fixture-time"))
            assertArrayEquals(original, wires[i])
        }
    }
    @Test fun streamTimeChangeAndNewSessionResetTheCipher() {
        val cipher = LelinkVideoCipher()
        cipher.clearPicture(wires[0], "v2-fixture-time")
        cipher.clearPicture(hex("000000026501"), "another-stream")
        assertArrayEquals(plain(0), cipher.clearPicture(wires[0], "v2-fixture-time"))
        assertArrayEquals(plain(0), LelinkVideoCipher().clearPicture(wires[0], "v2-fixture-time"))
    }
    @Test fun invalidPacketDoesNotAdvanceOrReplaceTheStreamState() {
        val cipher = LelinkVideoCipher()
        cipher.clearPicture(wires[0], "v2-fixture-time")
        assertThrows(IOException::class.java) { cipher.clearPicture(wires[1].dropLast(1).toByteArray(), "bad-stream") }
        assertThrows(IOException::class.java) { cipher.clearPicture(wires[1], "") }
        assertArrayEquals(plain(1), cipher.clearPicture(wires[1], "v2-fixture-time"))
    }
}
