package com.kanayama.wifiscreen

/** A clock correction alone must not turn a live 30fps stream into missing pictures. */
class VideoSyncPolicy {
    private var lateSinceNs: Long? = null
    fun reset() { lateSinceNs = null }
    fun shouldDrop(skewNs: Long, nowNs: Long, hasNewerOutput: Boolean, skippedAudio: Boolean): Boolean {
        if (skippedAudio) return true
        if (skewNs >= -40_000_000L) { reset(); return false }
        val since = lateSinceNs ?: nowNs.also { lateSinceNs = it }
        return hasNewerOutput && nowNs - since >= 100_000_000L
    }
}
