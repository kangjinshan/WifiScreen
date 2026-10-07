package com.kanayama.wifiscreen

import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** User-started, five-minute, read-only report endpoint. */
class DiagnosticServer(private val report: () -> String) {
    constructor(report: String) : this({ report })
    private val server = ServerSocket(0).apply { soTimeout = 1000 }
    val port = server.localPort
    val code = (100000 + SecureRandom().nextInt(900000)).toString()
    private val running = AtomicBoolean(true)
    private val deadline = System.nanoTime() + 300_000_000_000L
    private val thread = Thread({
        try {
            while (running.get() && System.nanoTime() < deadline) {
                val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                socket.use {
                    try {
                        it.soTimeout = 1500
                        val input = it.getInputStream()
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n\r\n") && head.length < 2048) {
                            val byte = input.read()
                            if (byte < 0) break
                            head.append(byte.toChar())
                        }
                        val firstLine = head.toString().substringBefore("\r\n")
                        val ok = firstLine == "GET /" + code + " HTTP/1.1" || firstLine == "GET /" + code + " HTTP/1.0"
                        val body = if (ok) report().toByteArray(Charsets.UTF_8) else "Not found".toByteArray()
                        val header = "HTTP/1.1 " + (if (ok) "200 OK" else "404 Not Found") +
                            "\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: " +
                            body.size + "\r\n\r\n"
                        it.getOutputStream().apply { write(header.toByteArray()); write(body); flush() }
                    } catch (_: Exception) { /* Invalid requests do not close the report window. */ }
                }
            }
        } catch (_: Exception) {
            // close() interrupts accept.
        } finally { close() }
    }, "WifiScreen-report").apply { start() }
    fun close() {
        running.set(false)
        runCatching { server.close() }
    }
}
