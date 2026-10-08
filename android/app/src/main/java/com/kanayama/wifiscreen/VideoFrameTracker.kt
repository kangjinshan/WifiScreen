package com.kanayama.wifiscreen

import java.util.ArrayDeque

/** Decoder-thread timing metadata, never a count of pictures waiting inside an OEM codec. */
class VideoFrameTracker(private val capacity: Int = 64) {
    private data class Entry(val ptsUs: Long, val receivedNs: Long)
    private val entries = ArrayDeque<Entry>()
    val size: Int get() = entries.size

    fun record(ptsUs: Long, receivedNs: Long) {
        entries.addLast(Entry(ptsUs, receivedNs))
        while (entries.size > capacity) entries.removeFirst()
    }
    fun take(ptsUs: Long): Long? {
        var received: Long? = null
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.ptsUs == ptsUs) {
                // Multiple slices may form one picture. Its complete input is
                // available only after the last matching unit has arrived.
                received = maxOf(received ?: entry.receivedNs, entry.receivedNs)
                iterator.remove()
            }
        }
        return received
    }
    fun clear() { entries.clear() }
}
