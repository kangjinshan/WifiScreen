package com.kanayama.wifiscreen

import org.junit.Assert.*
import org.junit.Test

class LegacyResponseTest {
    @Test fun senderCanFindCaseSensitiveLengthForUtf8Plist() {
        val body = "<string>投影</string>"
        val response = String(LegacyResponse.encode("HTTP/1.1", "200 OK", mapOf(
            "content-type" to "text/x-apple-plist+xml", "content-length" to "999"), body), Charsets.UTF_8)
        val lines = response.substringBefore("\r\n\r\n").split("\r\n")
        val length = lines.single { it.startsWith("Content-Length:") }.substringAfter(':').trim().toInt()
        assertEquals(body.toByteArray(Charsets.UTF_8).size, length)
        assertTrue(lines.contains("Content-Type: text/x-apple-plist+xml"))
        assertEquals(body, response.substringAfter("\r\n\r\n"))
    }

    @Test fun emptyRtspReplyHasExplicitLengthAndCanonicalNegotiationHeaders() {
        val response = String(LegacyResponse.encode("RTSP/1.0", "200 OK", mapOf(
            "cseq" to "4", "session" to "wifiscreen", "transport" to "RTP/AVP/UDP"), ""), Charsets.UTF_8)
        assertEquals("RTSP/1.0 200 OK\r\nCSeq: 4\r\nSession: wifiscreen\r\nTransport: RTP/AVP/UDP\r\nContent-Length: 0\r\n\r\n", response)
    }
}
