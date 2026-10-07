package com.kanayama.wifiscreen

import java.util.Locale

/** Legacy clients match these header spellings literally, despite HTTP's normal rules. */
object LegacyResponse {
    private val names = listOf("Server", "CSeq", "Session", "Transport", "Upgrade", "Connection",
        "Content-Type", "Content-Length").associateBy { it.lowercase(Locale.ROOT) }

    fun encode(protocol: String, status: String, headers: Map<String, String>, body: String): ByteArray {
        val payload = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append(protocol).append(' ').append(status).append("\r\n")
            headers.forEach { (key, value) ->
                val normalized = key.lowercase(Locale.ROOT)
                if (normalized != "content-length")
                    append(names[normalized] ?: key).append(": ").append(value).append("\r\n")
            }
            // Zero is required too; the sender uses a case-sensitive Content-Length search.
            append("Content-Length: ").append(payload.size).append("\r\n\r\n")
        }
        return head.toByteArray(Charsets.UTF_8) + payload
    }
}
