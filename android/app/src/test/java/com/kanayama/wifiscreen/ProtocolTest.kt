package com.kanayama.wifiscreen

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class ProtocolTest {
    private fun request(method: String, seq: Int, body: String = "") =
        RtspMessage(method + " * RTSP/1.0", mapOf("cseq" to seq.toString()), body).bytes()
    private fun response(seq: Int, status: String = "200 OK", session: String? = null) =
        RtspMessage("RTSP/1.0 " + status, mapOf("cseq" to seq.toString()) +
            if (session == null) emptyMap() else mapOf("session" to session)).bytes()
    private fun handshake(playStatus: String = "200 OK") =
        request("OPTIONS", 10) + response(1) +
            request("GET_PARAMETER", 11, "wfd_video_formats\r\nwfd_audio_codecs\r\nwfd_client_rtp_ports\r\n") +
            request("SET_PARAMETER", 12, "wfd_presentation_URL: rtsp://192.168.49.1/wfd1.0/streamid=0 none\r\n") +
            request("SET_PARAMETER", 13, "wfd_trigger_method: SETUP\r\n") +
            response(2, session = "session42;timeout=30") + response(3, playStatus)

    @Test fun completesM1ToM7AndPreservesPresentationUrl() {
        val input = ByteArrayInputStream(handshake() + request("GET_PARAMETER", 14) + request("TEARDOWN", 15))
        val output = ByteArrayOutputStream()
        var started = 0
        WfdSession(input, output, 19000, "test-video-formats") { started++ }.run()
        assertEquals(1, started)
        val wire = ByteArrayInputStream(output.toByteArray())
        val messages = generateSequence { Rtsp.read(wire) }.toList()
        assertTrue(messages.any { it.startLine == "SETUP rtsp://192.168.49.1/wfd1.0/streamid=0 RTSP/1.0" })
        assertTrue(messages.any { it.startLine.startsWith("PLAY ") && it.header("session") == "session42" })
        assertTrue(messages.any { "unicast 19000 0 mode=play" in it.body })
        assertEquals("14", messages[messages.size - 2].header("cseq"))
    }

    @Test fun failedPlayNeverClaimsPlayback() {
        var started = false
        assertThrows(IOException::class.java) {
            WfdSession(ByteArrayInputStream(handshake("455 Method Not Valid")), ByteArrayOutputStream(),
                19000, "test") { started = true }.run()
        }
        assertFalse(started)
    }

    @Test fun unmatchedResponsesCannotStartPlayback() {
        assertThrows(IOException::class.java) {
            WfdSession(ByteArrayInputStream(response(99)), ByteArrayOutputStream(), 19000, "test") {
                fail("Must not play")
            }.run()
        }
    }

    @Test fun requiresUrlBeforeSetup() {
        val output = ByteArrayOutputStream()
        WfdSession(ByteArrayInputStream(request("SET_PARAMETER", 1, "wfd_trigger_method: SETUP\r\n")),
            output, 19000, "test") { fail("Must not play") }.run()
        assertTrue(output.toString("UTF-8").contains("455 Method Not Valid"))
        assertFalse(output.toString("UTF-8").contains("client_port"))
    }

    @Test fun rejectsProtectedSessions() {
        val output = ByteArrayOutputStream()
        assertThrows(IOException::class.java) {
            WfdSession(ByteArrayInputStream(request("SET_PARAMETER", 1, "wfd_content_protection: HDCP2.1 port=1024\r\n")),
                output, 19000, "test") { fail("Must not play") }.run()
        }
        assertTrue(output.toString("UTF-8").contains("406 Not Acceptable"))
    }

    @Test fun byteLengthsHandleUnicodeAndPipelining() {
        val first = RtspMessage("SET_PARAMETER * RTSP/1.0", mapOf("cseq" to "1"), "名称：投屏\r\n")
        val wire = ByteArrayInputStream(first.bytes() + request("OPTIONS", 2))
        assertEquals(first.body, Rtsp.read(wire)!!.body)
        assertEquals("2", Rtsp.read(wire)!!.header("CSEQ"))
        assertNull(Rtsp.read(wire))
    }

    @Test fun rejectsUnboundedAndAmbiguousMessages() {
        for (header in listOf("Content-Length: 999999999", "Content-Length: -1",
            "Content-Length: 0\r\nContent-Length: 1", "BadHeader")) {
            assertThrows(IOException::class.java) {
                Rtsp.read(ByteArrayInputStream(("OPTIONS * RTSP/1.0\r\n" + header + "\r\n\r\n").toByteArray()))
            }
        }
        assertThrows(IOException::class.java) {
            Rtsp.read(ByteArrayInputStream(("OPTIONS * RTSP/1.0\r\nX:" + "a".repeat(20000)).toByteArray()))
        }
    }

    @Test fun truncatedBodiesAreNotAccepted() {
        assertThrows(IOException::class.java) {
            Rtsp.read(ByteArrayInputStream("OPTIONS * RTSP/1.0\r\nContent-Length: 4\r\n\r\nx".toByteArray()))
        }
    }
}
