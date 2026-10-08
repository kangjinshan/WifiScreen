package com.kanayama.wifiscreen

/** A static phone screen is healthy: only queued/arriving media without decoder progress is a stall. */
class StreamStallWatchdog {
    private var session = 0L
    private var lastDecoded = 0L
    private var lastReceived = 0L
    private var pendingSince = 0L
    fun reset() { session = 0; lastDecoded = 0; lastReceived = 0; pendingSince = 0 }
    fun check(now: Long, sessionId: Long, received: Long, decoded: Long,
              queue: Int, sessionAge: Long, playing: Boolean): Boolean {
        if (sessionId == 0L || sessionId != session) {
            reset(); session = sessionId; lastDecoded = decoded; lastReceived = received
            return false
        }
        if (!playing && sessionAge > 25_000) return true
        val progress = decoded != lastDecoded
        val pending = queue > 0 || (received > decoded && received > lastReceived)
        if (progress || !pending) pendingSince = 0
        else if (pendingSince == 0L) pendingSince = now
        lastDecoded = decoded; lastReceived = received
        return pendingSince > 0 && now - pendingSince >= 12_000
    }
}
