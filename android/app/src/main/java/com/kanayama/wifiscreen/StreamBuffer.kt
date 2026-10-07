package com.kanayama.wifiscreen

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.InterruptedIOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class StreamBuffer : BaseDataSource(true) {
    private val queue = ArrayBlockingQueue<ByteArray>(128)
    @Volatile private var ended = false
    private var current = ByteArray(0)
    private var offset = 0
    private var opened = false
    private var uri: Uri? = null
    fun offer(bytes: ByteArray) {
        if (ended) return
        if (!queue.offer(bytes)) { queue.poll(); queue.offer(bytes) }
    }
    fun finish() { ended = true; queue.clear() }
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }
    override fun read(buffer: ByteArray, targetOffset: Int, length: Int): Int {
        if (length == 0) return 0
        while (offset == current.size) {
            if (ended) return C.RESULT_END_OF_INPUT
            current = try { queue.poll(200, TimeUnit.MILLISECONDS) ?: continue }
                catch (_: InterruptedException) { throw InterruptedIOException("Stream stopped") }
            offset = 0
        }
        val count = minOf(length, current.size - offset)
        current.copyInto(buffer, targetOffset, offset, offset + count)
        offset += count
        bytesTransferred(count)
        return count
    }
    override fun getUri(): Uri? = uri
    override fun close() {
        finish()
        if (opened) { opened = false; transferEnded() }
    }
}
