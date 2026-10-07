package com.kanayama.wifiscreen

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class RtpTransport(private val source: InetAddress, val buffer: StreamBuffer) {
    private val socket = DatagramSocket(0).apply {
        soTimeout = 500
        receiveBufferSize = 1024 * 1024
    }
    val port: Int get() = socket.localPort
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    @Volatile var receivedBytes = 0L
        private set
    @Volatile var lostPackets = 0L
        private set
    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({
            val bytes = ByteArray(65536)
            val datagram = DatagramPacket(bytes, bytes.size)
            var expected = -1
            try {
                while (running.get()) {
                    datagram.length = bytes.size
                    try { socket.receive(datagram) } catch (_: SocketTimeoutException) { continue }
                    if (datagram.address != source) continue
                    val packet = RtpPacket.parse(bytes, datagram.length) ?: continue
                    if (expected >= 0) {
                        val distance = (packet.sequence - expected) and 65535
                        if (distance >= 32768) continue // late / duplicate, including wraparound
                        lostPackets += distance
                    }
                    expected = (packet.sequence + 1) and 65535
                    receivedBytes += packet.payload.size
                    buffer.offer(packet.payload)
                }
            } catch (_: Exception) {
                // Socket is closed by stop; no player or codec is touched on this thread.
            } finally {
                running.set(false)
                buffer.finish()
            }
        }, "WifiScreen-RTP").apply { start() }
    }
    fun stop() {
        running.set(false)
        socket.close()
        buffer.finish()
        worker?.interrupt()
    }
}
