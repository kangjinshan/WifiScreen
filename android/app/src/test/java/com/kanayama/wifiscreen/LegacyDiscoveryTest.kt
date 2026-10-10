package com.kanayama.wifiscreen

import java.net.InetAddress
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real JmDNS timers on loopback: no device, LAN broadcast or Android runtime is needed. */
class LegacyDiscoveryTest {
    private fun discovery(hevc: Boolean): LegacyDiscovery {
        val id = UUID.randomUUID().toString().take(8)
        return LegacyDiscovery(InetAddress.getByName("127.0.0.1"), "wifiscreen-qa-$id",
            "WifiScreen QA $id", "02:00:00:00:00:01", "7000000000000001", 47110, 47111, hevc)
    }

    private fun awaitReady(discovery: LegacyDiscovery) {
        // The library owns the real probe/announce timers; bound this integration wait.
        val deadline = System.nanoTime() + 8_000_000_000L
        while (!discovery.isReady && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("Lelink discovery did not finish announcing", discovery.isReady)
    }

    @Test fun startupH264RefreshRemainsDiscoverable() {
        val discovery = discovery(false)
        try {
            assertFalse(discovery.isReady)
            discovery.updateEncoding(false)
            discovery.updateEncoding(false)
            awaitReady(discovery)
            discovery.updateEncoding(false)
            assertTrue(discovery.isReady)
        } finally { discovery.close() }
        assertFalse(discovery.isReady)
    }

    @Test fun startupH265RefreshRemainsDiscoverable() {
        val discovery = discovery(true)
        try {
            discovery.updateEncoding(true)
            awaitReady(discovery)
        } finally { discovery.close() }
    }

    @Test fun encodingChangesDuringProbingAndAnnouncingRecover() {
        val discovery = discovery(false)
        try {
            discovery.updateEncoding(true)
            discovery.updateEncoding(false)
            discovery.updateEncoding(true)
            awaitReady(discovery)
            discovery.updateEncoding(false)
            awaitReady(discovery)
        } finally { discovery.close() }
    }
}
