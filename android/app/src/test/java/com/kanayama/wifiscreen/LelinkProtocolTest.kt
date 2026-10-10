package com.kanayama.wifiscreen

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class LelinkProtocolTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val key = ByteArray(32) { it.toByte() }
    private val nonce = ByteArray(8) { it.toByte() }
    // Independent PyCryptodome ChaCha20 + Poly1305 vectors, spanning partial blocks.
    private val plain1 = hex("474554202f6c656c696e6b2d706c617965722d696e666f20485454502f312e310d0a435365713a20310d0a0d0a")
    private val record1 = hex("2d0000007f45dfba09d050f8774a2f3a0ce6bf1fecacb84f27e0b678c1af34b86918e78c9750884fa46984760fb4b9a9ae38f346ef7895993cbfa1a4f5a6b995c6")
    private val record2 = hex("610000000eae44f469a83f7eedcc3c073f1a18650c985e87b764917d8cc66c33e253b4edec935f798ec58b5df8d0fe9d81167a26b777e661790d764d55d2008cb39646f6025224c8ff96d28296daf2185bc79399e92ecdf2b86b009754c485abf8177e1d217f9ce9bdf1fefcf1566c6059a00a8999")

    @Test fun completePairingMatchesIndependentTranscriptAndAuthenticatesTheChannel() {
        val fixture = java.util.Properties().apply {
            LelinkProtocolTest::class.java.classLoader!!.getResourceAsStream("lelink-v2.properties")!!.use { load(it) }
        }
        fun bytes(name: String) = hex(fixture.getProperty(name))
        val testRandom = object : java.security.SecureRandom() {
            private var next = 0
            override fun nextBytes(bytes: ByteArray) { for (i in bytes.indices) bytes[i] = (next++).toByte() }
        }
        val pair = LelinkPairing(testRandom)
        assertArrayEquals(bytes("setupResponse"), pair.handshake("/lelink-setup", bytes("setupRequest")))
        assertArrayEquals(bytes("verifyResponse"), pair.handshake("/lelink-verify", bytes("verifyRequest")))
        assertFalse(pair.ready)
        assertArrayEquals(bytes("finishResponse"), pair.handshake("/lelink-verify", bytes("finishRequest")))
        assertTrue(pair.ready)
        assertArrayEquals(bytes("plainRequest"), pair.decrypt(bytes("encryptedRequest")))
        assertArrayEquals(bytes("encryptedResponse"), pair.encrypt(bytes("plainResponse")))
        val bad = bytes("nextRequest").apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertThrows(IOException::class.java) { pair.decrypt(bad) }
        assertFalse(pair.ready)
    }

    @Test fun encryptedRecordsMatchIndependentVectorsAcrossBlockBoundaries() {
        val writer = LelinkRecords(key, nonce)
        assertArrayEquals(record1, writer.encrypt(plain1))
        assertArrayEquals(record2, writer.encrypt(ByteArray(97) { it.toByte() }))
        val reader = LelinkRecords(key, nonce)
        assertArrayEquals(plain1, reader.decrypt(record1))
        assertArrayEquals(ByteArray(97) { it.toByte() }, reader.decrypt(record2))
    }
    @Test fun rejectsTamperingReplayAndUnboundedRecordLengths() {
        for (at in listOf(5, record1.lastIndex)) {
            val reader = LelinkRecords(key, nonce)
            val bad = record1.copyOf().apply { this[at] = (this[at].toInt() xor 1).toByte() }
            assertThrows(IOException::class.java) { reader.decrypt(bad) }
            assertThrows(IOException::class.java) { reader.decrypt(record1) }
        }
        val reader = LelinkRecords(key, nonce)
        reader.decrypt(record1)
        assertThrows(IOException::class.java) { reader.decrypt(record1) }
        for (prefix in listOf("ffffffff", "ffffff7f", "00000000", "01140000"))
            assertThrows(IOException::class.java) { LelinkRecords.size(hex(prefix)) }
        assertThrows(IOException::class.java) { LelinkRecords(key, nonce).decrypt(record1.dropLast(1).toByteArray()) }
    }
    @Test fun binaryPairingBodySurvivesHttpWithoutUtf8Replacement() {
        val payload = ByteArray(256) { it.toByte() }
        val wire = LegacyResponse.encode("HTTP/1.1", "200 OK", emptyMap(), payload)
        val decoded = Rtsp.read(ByteArrayInputStream(wire))!!
        assertArrayEquals(payload, decoded.binaryBody)
        assertArrayEquals(payload, Rtsp.read(ByteArrayInputStream(decoded.bytes()))!!.binaryBody)
    }
    @Test fun allowsOnlyTheIdenticalDuplicatedHyperOsMetadataField() {
        fun request(fields: String) = ByteArrayInputStream(("GET /lelink-player-info HTTP/1.1\r\n$fields\r\n\r\n").toByteArray())
        val same = "LeLink-Client-DID: test-device\r\nLeLink-Client-DID: test-device"
        assertEquals("test-device", Rtsp.read(request(same), true)!!.header("lelink-client-did"))
        assertThrows(IOException::class.java) { Rtsp.read(request(same)) }
        for (fields in listOf("LeLink-Client-DID: a\r\nLeLink-Client-DID: b", "Content-Length: 0\r\nContent-Length: 0",
            "LeLink-Session-ID: x\r\nLeLink-Session-ID: x"))
            assertThrows(IOException::class.java) { Rtsp.read(request(fields), true) }
    }
    @Test fun tlvRejectsTruncatedDuplicateAndOversizedFields() {
        val valid = LelinkTlv.encode(mapOf(1 to byteArrayOf(1), 2 to byteArrayOf(3), 4 to ByteArray(64)))
        assertEquals(64, LelinkTlv.decode(valid).getValue(4).size)
        assertThrows(IOException::class.java) { LelinkTlv.decode(valid.dropLast(1).toByteArray()) }
        assertThrows(IOException::class.java) { LelinkTlv.decode(valid + valid) }
        assertThrows(IOException::class.java) { LelinkTlv.decode(hex("01000000ffffffff")) }
        assertThrows(IOException::class.java) { LelinkTlv.decode(ByteArray(5000)) }
    }
    @Test fun pairingNeverAcceptsOutOfOrderOrRepeatedSteps() {
        val pair = LelinkPairing()
        assertThrows(IOException::class.java) { pair.encrypt(plain1) }
        val verify = LelinkTlv.encode(mapOf(1 to byteArrayOf(1), 2 to byteArrayOf(5), 5 to ByteArray(64)))
        assertThrows(IOException::class.java) { pair.handshake("/lelink-verify", verify) }
        assertFalse(pair.ready)
        val setup = LelinkTlv.encode(mapOf(0 to byteArrayOf(0, 1, 0, 1), 1 to byteArrayOf(1), 2 to byteArrayOf(1), 3 to ByteArray(64)))
        assertThrows(IOException::class.java) { pair.handshake("/lelink-setup", setup) }
        val fresh = LelinkPairing()
        fresh.handshake("/lelink-setup", setup)
        assertThrows(IOException::class.java) { fresh.handshake("/lelink-setup", setup) }
    }
    @Test fun rejectsLowOrderCurveKeysAndInvalidClientSignatures() {
        fun setup(pair: LelinkPairing) = pair.handshake("/lelink-setup", LelinkTlv.encode(mapOf(
            0 to byteArrayOf(0, 1, 0, 1), 1 to byteArrayOf(1), 2 to byteArrayOf(1), 3 to ByteArray(64))))
        val lowOrder = LelinkPairing(); setup(lowOrder)
        assertThrows(IOException::class.java) { lowOrder.handshake("/lelink-verify", LelinkTlv.encode(mapOf(
            1 to byteArrayOf(1), 2 to byteArrayOf(3), 4 to ByteArray(64)))) }
        assertFalse(lowOrder.ready)
        val pair = LelinkPairing(); setup(pair)
        val curve = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(java.security.SecureRandom())
        pair.handshake("/lelink-verify", LelinkTlv.encode(mapOf(1 to byteArrayOf(1), 2 to byteArrayOf(3),
            4 to (curve.generatePublicKey().encoded + ByteArray(32)))))
        assertThrows(IOException::class.java) { pair.handshake("/lelink-verify", LelinkTlv.encode(mapOf(
            1 to byteArrayOf(1), 2 to byteArrayOf(5), 5 to ByteArray(64)))) }
        assertFalse(pair.ready)
    }
    @Test fun readsXiaomiPlistWithoutFetchingItsStandardDtd() {
        val doc = "<?xml version=\"1.0\"?><!DOCTYPE plist PUBLIC \"-//apple//DTD PLIST 1.0//EN\" \"https://www.apple.com/DTDs/PropertyList-1.0.dtd\">" +
            "<plist><dict><key>stream-time</key><string>fixture</string><key>streams</key><array><dict><key>type</key><integer>97</integer></dict></array></dict></plist>"
        val read = LelinkPlist.decode(doc)
        assertEquals("fixture", read["stream-time"])
        assertEquals(97L, ((read["streams"] as List<*>).single() as Map<*, *>)["type"])
        val encoded = LelinkPlist.encode(mapOf("name" to "电视 & 投影", "ast" to 1, "displays" to listOf(mapOf("width" to 1920))))
        assertEquals("电视 & 投影", LelinkPlist.decode(encoded)["name"])
    }
    @Test fun refusesExternalEntitiesDuplicateKeysAndDeepPlists() {
        for (doc in listOf(
            "<!DOCTYPE plist SYSTEM 'https://example.invalid/evil'><plist><dict/></plist>",
            "<!DOCTYPE plist [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><plist><dict><key>x</key><string>&x;</string></dict></plist>",
            "<plist><dict><key>ast</key><integer>1</integer><key>ast</key><integer>2</integer></dict></plist>",
            "<plist>" + "<array>".repeat(20) + "<dict/>" + "</array>".repeat(20) + "</plist>"))
            assertThrows(Exception::class.java) { LelinkPlist.decode(doc) }
    }
}
