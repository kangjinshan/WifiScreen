package com.kanayama.wifiscreen

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.util.Locale

/** Sink-side M1–M7 negotiation. Only a successful PLAY response starts playback. */
class WfdSession(
    private val input: InputStream,
    private val output: OutputStream,
    private val rtpPort: Int,
    private val videoFormats: String,
    private val onPlaying: () -> Unit
) {
    private var cseq = 0
    private val pending = mutableMapOf<Int, String>()
    private var session = ""
    private var presentationUrl = ""
    private var optionsSent = false
    private var setupSent = false
    var playing = false
        private set

    fun run() {
        require(rtpPort in 1..65535)
        while (true) {
            val message = Rtsp.read(input) ?: return
            if (message.startLine.startsWith("RTSP/1.0 ")) response(message)
            else if (!request(message)) return
        }
    }

    private fun request(message: RtspMessage): Boolean {
        val parts = message.startLine.split(' ')
        if (parts.size != 3 || parts[2] != "RTSP/1.0") throw IOException("Invalid RTSP request")
        val incomingCseq = message.header("cseq")?.toIntOrNull()
            ?: throw IOException("Missing CSeq")
        val headers = mapOf("cseq" to incomingCseq.toString())
        fun reply(code: String = "200 OK", body: String = "", extras: Map<String, String> = emptyMap()) {
            send(RtspMessage("RTSP/1.0 " + code, headers + extras, body))
        }
        when (parts[0]) {
            "OPTIONS" -> {
                reply(extras = mapOf("public" to "org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER, TEARDOWN"))
                if (!optionsSent) {
                    optionsSent = true
                    sendRequest("OPTIONS", "*", mapOf("require" to "org.wfa.wfd1.0"))
                }
            }
            "GET_PARAMETER" -> {
                val supported = linkedMapOf(
                    "wfd_video_formats" to videoFormats,
                    "wfd_audio_codecs" to "AAC 00000001 00",
                    "wfd_client_rtp_ports" to ("RTP/AVP/UDP;unicast " + rtpPort + " 0 mode=play"),
                    "wfd_content_protection" to "none",
                    "wfd_display_edid" to "none",
                    "wfd_uibc_capability" to "none",
                    "wfd_connector_type" to "05"
                )
                val requested = message.body.lineSequence().map { it.trim().lowercase(Locale.ROOT) }.toSet()
                val body = supported.filterKeys { it in requested }.entries.joinToString("") {
                    it.key + ": " + it.value + "\r\n"
                }
                reply(body = body, extras = if (body.isNotEmpty()) mapOf("content-type" to "text/parameters") else emptyMap())
            }
            "SET_PARAMETER" -> {
                val params = Rtsp.parameters(message.body)
                if (params["wfd_audio_codecs"]?.let { !it.startsWith("AAC ", true) } == true) {
                    reply("406 Not Acceptable")
                    throw IOException("手机选择了当前未实现的音频编码")
                }
                if (params["wfd_content_protection"]?.let { !it.equals("none", true) } == true) {
                    reply("406 Not Acceptable")
                    throw IOException("此会话要求受保护的视频传输，当前接收端不支持")
                }
                params["wfd_presentation_url"]?.let { value ->
                    val url = value.split(Regex("\\s+")).first()
                    val parsed = runCatching { URI(url) }.getOrNull()
                    if (parsed?.scheme != "rtsp" || parsed.host == null) throw IOException("Invalid presentation URL")
                    presentationUrl = url
                }
                val trigger = params["wfd_trigger_method"]?.uppercase(Locale.ROOT)
                if (trigger == "SETUP" && presentationUrl.isEmpty()) {
                    reply("455 Method Not Valid in This State")
                    return true
                }
                reply()
                if (trigger == "TEARDOWN") return false
                if (trigger == "SETUP" && !setupSent) {
                    setupSent = true
                    sendRequest("SETUP", presentationUrl,
                        mapOf("transport" to ("RTP/AVP/UDP;unicast;client_port=" + rtpPort)))
                }
            }
            "TEARDOWN" -> { reply(); return false }
            else -> reply("501 Not Implemented")
        }
        return true
    }

    private fun response(message: RtspMessage) {
        val sequence = message.header("cseq")?.toIntOrNull() ?: throw IOException("Response missing CSeq")
        val method = pending.remove(sequence) ?: throw IOException("Unmatched RTSP response")
        val status = message.startLine.split(' ').getOrNull(1)?.toIntOrNull()
        if (status == null || status !in 200..299) throw IOException(method + " rejected: " + message.startLine)
        when (method) {
            "SETUP" -> {
                session = message.header("session")?.substringBefore(';')?.trim().orEmpty()
                if (session.isEmpty()) throw IOException("SETUP missing Session")
                sendRequest("PLAY", presentationUrl, mapOf("session" to session))
            }
            "PLAY" -> {
                playing = true
                onPlaying()
            }
        }
    }

    private fun sendRequest(method: String, url: String, headers: Map<String, String>) {
        val id = ++cseq
        pending[id] = method
        send(RtspMessage(method + " " + url + " RTSP/1.0", headers + ("cseq" to id.toString())))
    }
    private fun send(message: RtspMessage) {
        output.write(message.bytes())
        output.flush()
    }
}
