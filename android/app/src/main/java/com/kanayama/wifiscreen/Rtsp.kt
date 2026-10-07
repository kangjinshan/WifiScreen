package com.kanayama.wifiscreen

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.IOException
import java.util.Locale

data class RtspMessage(val startLine: String, val headers: Map<String, String>, val body: String = "") {
    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]
    fun bytes(): ByteArray {
        require(!startLine.contains('\r') && !startLine.contains('\n'))
        val payload = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append(startLine).append("\r\n")
            headers.filterKeys { !it.equals("content-length", true) }.forEach { (key, value) ->
                require(!key.contains('\n') && !key.contains('\r') && !value.contains('\n') && !value.contains('\r'))
                append(key).append(": ").append(value).append("\r\n")
            }
            if (payload.isNotEmpty()) append("Content-Length: ").append(payload.size).append("\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)
        return head + payload
    }
}

/** Bounded, byte-counted RTSP framing; leaves following messages in the stream. */
object Rtsp {
    const val MAX_HEADER = 16 * 1024
    const val MAX_BODY = 64 * 1024

    fun read(input: InputStream): RtspMessage? {
        val head = ByteArrayOutputStream()
        var suffix = 0
        while (suffix != 0x0d0a0d0a) {
            val byte = input.read()
            if (byte < 0) {
                if (head.size() == 0) return null
                throw EOFException("Incomplete RTSP header")
            }
            head.write(byte)
            if (head.size() > MAX_HEADER) throw IOException("RTSP header exceeds limit")
            suffix = (suffix shl 8) or byte
        }
        val lines = head.toString("UTF-8").split("\r\n")
        val headers = linkedMapOf<String, String>()
        for (line in lines.drop(1).filter { it.isNotEmpty() }) {
            val separator = line.indexOf(':')
            if (separator <= 0) throw IOException("Malformed RTSP header")
            val key = line.substring(0, separator).trim().lowercase(Locale.ROOT)
            if (headers.containsKey(key)) throw IOException("Duplicate RTSP header: " + key)
            headers[key] = line.substring(separator + 1).trim()
        }
        val length = headers["content-length"]?.let {
            it.toIntOrNull() ?: throw IOException("Invalid Content-Length")
        } ?: 0
        if (length !in 0..MAX_BODY) throw IOException("RTSP body exceeds limit")
        val body = ByteArray(length)
        var offset = 0
        while (offset < body.size) {
            val count = input.read(body, offset, body.size - offset)
            if (count < 0) throw EOFException("Incomplete RTSP body")
            if (count > 0) offset += count
        }
        return RtspMessage(lines.first(), headers, String(body, Charsets.UTF_8))
    }

    fun parameters(body: String): Map<String, String> = body.lineSequence().mapNotNull { line ->
        val split = line.indexOf(':')
        if (split <= 0) null
        else line.substring(0, split).trim().lowercase(Locale.ROOT) to line.substring(split + 1).trim()
    }.toMap()
}
