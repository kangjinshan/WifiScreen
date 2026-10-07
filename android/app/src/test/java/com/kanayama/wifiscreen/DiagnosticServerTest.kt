package com.kanayama.wifiscreen

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket

class DiagnosticServerTest {
    private fun get(server: DiagnosticServer, path: String): String =
        Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = 2000
            socket.getOutputStream().write(("GET /" + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n").toByteArray())
            socket.getInputStream().bufferedReader().readText()
        }
    @Test fun reportRequiresTheDisplayedCode() {
        val server = DiagnosticServer("model=test\nprivate-report-marker")
        try {
            assertTrue(get(server, server.code).contains("private-report-marker"))
            val rejected = get(server, "incorrect")
            assertTrue(rejected.startsWith("HTTP/1.1 404"))
            assertFalse(rejected.contains("private-report-marker"))
        } finally { server.close() }
    }
    @Test fun noCommandEndpointExists() {
        val server = DiagnosticServer("model=test")
        try {
            assertTrue(get(server, "shell").startsWith("HTTP/1.1 404"))
            assertTrue(get(server, "../").startsWith("HTTP/1.1 404"))
        } finally { server.close() }
    }
}
