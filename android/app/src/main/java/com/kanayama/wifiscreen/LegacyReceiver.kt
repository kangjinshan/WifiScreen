package com.kanayama.wifiscreen

import android.content.Context
import android.net.wifi.WifiManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import android.util.Log
import android.view.Surface
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.PushbackInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import org.json.JSONObject
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow

/** Independent legacy LAN receiver: discovery, HTTP/RTSP session, AVC and AAC-ELD. */
class LegacyReceiver(
    private val context: Context,
    private val surface: () -> Surface?,
    private val status: (String) -> Unit,
    private val rendered: (Int, Int) -> Unit,
    private val ended: (String) -> Unit
) {
    val name: String get() = identity.getString("display_name", null) ?: displayName
    val isRunning: Boolean get() = running
    val isReady: Boolean get() = running && discovery != null
    @Volatile var muted = false
        private set
    private val main = Handler(Looper.getMainLooper())
    private val setup = Executors.newSingleThreadExecutor()
    private var workers = Executors.newCachedThreadPool()
    private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val controlByPeer = Collections.synchronizedMap(mutableMapOf<String, Socket>())
    private var control: ServerSocket? = null
    private var video: ServerSocket? = null
    private var audio: DatagramSocket? = null
    @Volatile private var discovery: LegacyDiscovery? = null
    private var multicast: WifiManager.MulticastLock? = null
    @Volatile private var running = false
    @Volatile private var generation = 0
    @Volatile private var active: Session? = null
    private val sessionIds = AtomicLong()
    @Volatile private var lastReport = "尚未接收投屏"
    @Volatile private var lastError = ""
    @Volatile private var lastHandshake = ""
    @Volatile var address = ""
        private set
    private val identity = context.getSharedPreferences("receiver", Context.MODE_PRIVATE)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    // One-time migration from 0.1.0: Xiaomi retains a receiver's first discovered port.
    // A new identity lets existing phones discard the old ephemeral endpoint.
    private val id = identity.getString("stable_ports_id", null) ?: UUID.randomUUID().toString().also {
        identity.edit().putString("stable_ports_id", it).apply()
    }
    private val mac = "02:" + id.replace("-", "").take(10).chunked(2).joinToString(":")
    private val uid = (id.hashCode().toLong() and 0xffffffffL).plus(7000000000000000L).toString()
    private val host = "wifiscreen-" + id.take(8)
    private val deviceReport by lazy { DeviceProfile.report(context) }

    private inner class Session(val peer: String, val videoSocket: Socket, val controlSocket: Socket?) {
        val id = sessionIds.incrementAndGet()
        val receivedBytes = AtomicLong()
        val started = SystemClock.elapsedRealtime()
        @Volatile var hasPicture = false
        @Volatile var closed = false
        @Volatile private var audioAllowed = false
        @Volatile private var userVolume = 0.7f
        @Volatile private var ducked = false
        private val focusListener = AudioManager.OnAudioFocusChangeListener { focus ->
            audioAllowed = focus == AudioManager.AUDIOFOCUS_GAIN || focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            ducked = focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            applyVolume()
        }
        val decoder = LegacyVideo(surface, { width, height ->
            main.post {
                if (!closed && active === this) {
                    if (!hasPicture) {
                        @Suppress("DEPRECATION")
                        val focus = audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        audioAllowed = focus == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                        hasPicture = true
                    }
                    rendered(width, height)
                }
            }
        }, { failure -> if (!closed && active === this) { lastError = failure; reportState(failure) } })
        val sound = LegacyAudio({ hasPicture && audioAllowed && !closed }, { failure ->
            if (!closed && active === this) lastError = failure
        })
        fun applyVolume() { sound.volume = if (muted) 0f else userVolume * if (ducked) 0.2f else 1f }
        fun volume(value: Float) { userVolume = value; applyVolume() }
        fun report(): String = "接收端：" + name + "\n手机 IP：" + peer +
            "\n时长：" + (SystemClock.elapsedRealtime() - started) / 1000 + " 秒" +
            "\n视频接收：" + decoder.received.get() + " 帧" +
            "\n视频输出：" + decoder.decoded.get() + " 帧" +
            "\n视频尺寸：" + decoder.width + " × " + decoder.height +
            "\n解码状态：" + decoder.decoderState +
            "\n队列深度：" + decoder.queueDepth + "\n缓冲等待：" + decoder.backpressureWaits +
            "\n接收关键帧：" + decoder.keyframes.get() + "\n距上次视频输出：" + decoder.outputAgeMs + " ms" +
            "\n音频包：" + sound.packets.get() + "\nPCM 写入：" + sound.pcmBytes.get() + " bytes" +
            "\n音频播放已启动：" + sound.playing + "\n实际播放样本：" + sound.playedFrames +
            "\n音频时间戳兼容回退：" + sound.timestampFallbacks + "\n音频时间轴重置：" + sound.timestampResets.get() +
            "\n音频播放欠载：" + sound.underruns + "\n音频缓冲：" + sound.bufferedMs + " ms" +
            "\n系统原有音频缓冲：" + sound.platformBufferMs + " ms" +
            "\n音频 RTP 缺包 / 乱序：" + sound.missingPackets + " / " + sound.reorderedPackets +
            "\n音频到达抖动估计：" + String.format(java.util.Locale.ROOT, "%.2f", sound.jitterMs) + " ms" +
            "\n音频解码等待：" + sound.inputRetries.get() + "\n新增缓冲目标：" + PlaybackTiming.BUFFER_MS + " ms" +
            "\n最近错误：" + lastError.ifEmpty { "无" }
        fun close() {
            if (closed) return
            closed = true
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
            lastReport = report()
            decoder.close(); sound.close()
            runCatching { videoSocket.close() }; runCatching { controlSocket?.close() }
        }
    }

    private fun reportState(text: String) {
        val token = generation
        main.post { if (running && generation == token) status(text) }
    }

    @Synchronized fun start(preferredAddress: String? = null) {
        if (running) return
        val addresses = DeviceProfile.addresses(context)
        val ip = preferredAddress?.takeIf { it in addresses } ?: addresses.firstOrNull()
        if (ip == null) { status("请先连接 Wi-Fi 或有线网络"); return }
        address = ip
        running = true
        val token = ++generation
        lastError = ""
        if (workers.isShutdown) workers = Executors.newCachedThreadPool()
        status("正在启动接收服务…")
        setup.execute {
            var pendingControl: ServerSocket? = null
            var pendingVideo: ServerSocket? = null
            var pendingAudio: DatagramSocket? = null
            try {
                val bind = InetAddress.getByName(ip)
                // Keep these stable across background/foreground and process restarts.
                // Some sender SDKs never refresh port values for a known receiver identity.
                val c = ServerSocket().also { pendingControl = it }.apply {
                    reuseAddress = true; bind(InetSocketAddress(bind, CONTROL_PORT))
                }
                val v = ServerSocket().also { pendingVideo = it }.apply {
                    reuseAddress = true; bind(InetSocketAddress(bind, VIDEO_PORT))
                }
                val a = DatagramSocket(null).also { pendingAudio = it }.apply {
                    reuseAddress = false; bind(InetSocketAddress(bind, AUDIO_PORT)); soTimeout = 1000
                }
                synchronized(this) {
                    if (!running || generation != token) { c.close(); v.close(); a.close(); return@execute }
                    control = c; video = v; audio = a
                }
                Log.i("WifiScreen", "Listening IPv4 " + ip + " control=" + c.localPort +
                    " video=" + v.localPort + " audio=" + a.localPort)
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val lock = wifi?.createMulticastLock("WifiScreen").apply { this?.setReferenceCounted(false); this?.acquire() }
                synchronized(this) {
                    if (!running || generation != token) { lock?.release(); return@execute }
                    multicast = lock
                }
                workers.execute { accept(c, token) }
                workers.execute { accept(v, token) }
                workers.execute { receiveAudio(a, token) }
                val mdns = LegacyDiscovery(bind, host, name, mac, uid, c.localPort, v.localPort)
                val accepted = synchronized(this) {
                    if (running && generation == token) { discovery = mdns; true } else false
                }
                if (!accepted) { mdns.close(); return@execute }
                Log.i("WifiScreen", "Receiver advertised: " + name)
                reportState("等待手机投屏")
            } catch (failure: Exception) {
                runCatching { pendingControl?.close() }
                runCatching { pendingVideo?.close() }
                runCatching { pendingAudio?.close() }
                if (generation == token) {
                    lastError = failure.message ?: failure.javaClass.simpleName
                    main.post { if (generation == token) { stop(); status("接收启动失败：" + lastError) } }
                }
            }
        }
    }

    private fun accept(server: ServerSocket, token: Int) {
        while (running && generation == token) {
            val socket = try { server.accept() } catch (_: IOException) { break }
            if (sockets.size >= 16) { socket.close(); continue }
            sockets.add(socket)
            try { workers.execute { handle(socket, token) } }
            catch (_: java.util.concurrent.RejectedExecutionException) { sockets.remove(socket); socket.close() }
        }
    }

    private fun handle(socket: Socket, token: Int) {
        val peer = socket.inetAddress.hostAddress.orEmpty()
        var session: Session? = null
        var streamTime = ""
        try {
            socket.soTimeout = 60000
            socket.tcpNoDelay = true
            val input = PushbackInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024), 4)
            val output = BufferedOutputStream(socket.getOutputStream())
            while (running && generation == token) {
                val prefix = ByteArray(4)
                DataInputStream(input).readFully(prefix)
                val verb = String(prefix, Charsets.US_ASCII)
                if (verb !in listOf("GET ", "POST", "OPTI", "ANNO", "SETU", "RECO", "GET_", "SET_", "TEAR")) {
                    val owner = session ?: throw IOException("Video arrived before stream setup")
                    val header = ByteArray(LegacyAvc.HEADER_SIZE)
                    prefix.copyInto(header)
                    DataInputStream(input).readFully(header, 4, header.size - 4)
                    val payload = ByteArray(LegacyAvc.payloadSize(header))
                    DataInputStream(input).readFully(payload)
                    owner.receivedBytes.addAndGet((header.size + payload.size).toLong())
                    owner.decoder.offer(LegacyAvc.frame(header, payload), streamTime)
                    continue
                }
                input.unread(prefix)
                val request = Rtsp.read(input) ?: break
                val parts = request.startLine.split(" ")
                if (parts.size != 3 || parts[2] !in listOf("HTTP/1.1", "RTSP/1.0", "HTTP/1.0")) throw IOException("Invalid request line")
                val method = parts[0]
                val path = parts[1]
                val protocol = parts[2]
                if (path !in listOf("/status", "/diagnostics", "/")) lastHandshake = method + if (path.startsWith("/"))
                    " " + path.substringBefore('?').take(64) else " RTSP"
                request.header("stream-time")?.let { streamTime = it }
                var body = ""
                var type = "text/parameters"
                var result = "200 OK"
                val extra = linkedMapOf("server" to "WifiScreen/0.1")
                request.header("cseq")?.let { extra["cseq"] = it }
                when {
                    method == "GET" && path in listOf("/", "/diagnostics") -> {
                        body = diagnostics()
                        type = "text/plain; charset=utf-8"
                    }
                    method == "GET" && path == "/status" -> {
                        val current = active?.takeUnless { it.closed }
                        body = JSONObject().put("name", name).put("version", BuildConfig.VERSION_NAME)
                            .put("android", Build.VERSION.RELEASE).put("model", Build.MODEL)
                            .put("controlPort", CONTROL_PORT).put("videoPort", VIDEO_PORT).put("audioPort", AUDIO_PORT)
                            .put("lastHandshake", lastHandshake)
                            .put("connected", current != null).put("sourceIp", current?.peer ?: "")
                            .put("sessionSeconds", current?.let { (SystemClock.elapsedRealtime() - it.started) / 1000 } ?: 0)
                            .put("ready", running && discovery != null).put("playing", current?.hasPicture == true)
                            .put("muted", muted)
                            .put("videoReceived", current?.decoder?.received?.get() ?: 0)
                            .put("videoDecoded", current?.decoder?.decoded?.get() ?: 0)
                            .put("videoCodec", current?.decoder?.codecName ?: "")
                            .put("videoWidth", current?.decoder?.width ?: 0)
                            .put("videoHeight", current?.decoder?.height ?: 0)
                            .put("videoQueueDepth", current?.decoder?.queueDepth ?: 0)
                            .put("videoBackpressureWaits", current?.decoder?.backpressureWaits ?: 0)
                            .put("videoKeyframes", current?.decoder?.keyframes?.get() ?: 0)
                            .put("videoOutputAgeMs", current?.decoder?.outputAgeMs ?: -1)
                            .put("videoDecoderState", current?.decoder?.decoderState ?: "等待投屏")
                            .put("videoPresentationDrops", current?.decoder?.dropped?.get() ?: 0)
                            .put("audioPackets", current?.sound?.packets?.get() ?: 0)
                            .put("audioPcmBytes", current?.sound?.pcmBytes?.get() ?: 0)
                            .put("audioPlaying", current?.sound?.playing ?: false)
                            .put("audioPlayedFrames", current?.sound?.playedFrames ?: 0)
                            .put("audioTimestampFallbacks", current?.sound?.timestampFallbacks ?: 0)
                            .put("audioCodec", current?.sound?.codecName ?: "")
                            .put("audioReady", current?.sound?.ready ?: false)
                            .put("audioError", current?.sound?.errorMessage ?: "")
                            .put("playbackBufferMs", PlaybackTiming.BUFFER_MS)
                            .put("audioUnderruns", current?.sound?.underruns ?: 0)
                            .put("audioBufferedMs", current?.sound?.bufferedMs ?: 0)
                            .put("audioPlatformBufferMs", current?.sound?.platformBufferMs ?: 0)
                            .put("audioBufferCapacityMs", current?.sound?.bufferCapacityMs ?: 0)
                            .put("audioQueueDepth", current?.sound?.queueDepth ?: 0)
                            .put("audioRtpMissingPackets", current?.sound?.missingPackets ?: 0)
                            .put("audioReorderedPackets", current?.sound?.reorderedPackets ?: 0)
                            .put("audioDuplicatePackets", current?.sound?.duplicatePackets ?: 0)
                            .put("audioLatePackets", current?.sound?.latePackets ?: 0)
                            .put("audioQueueOverflows", current?.sound?.overflowPackets ?: 0)
                            .put("audioRtpJitterMs", current?.sound?.jitterMs ?: 0.0)
                            .put("audioInputRetries", current?.sound?.inputRetries?.get() ?: 0)
                            .put("audioConcealedSamples", current?.sound?.concealedSamples?.get() ?: 0)
                            .put("audioTimestampResets", current?.sound?.timestampResets?.get() ?: 0)
                            .put("lastError", lastError).toString()
                        type = "application/json"
                    }
                    method == "GET" && path == "/server-info" -> {
                        body = plist("<key>deviceid</key><string>" + mac + "</string><key>features</key><integer>1518338039</integer>" +
                            "<key>model</key><string>WifiScreen,1</string><key>protovers</key><string>1.0</string>" +
                            "<key>srcvers</key><string>220.68</string><key>name</key><string>" + ReceiverName.xml(name) + "</string><key>vv</key><string>1</string>")
                        type = "text/x-apple-plist+xml"
                    }
                    method == "GET" && path in listOf("/stream", "/stream.xml") -> {
                        if (path == "/stream") controlByPeer[peer] = socket
                        body = plist("<key>width</key><integer>1920</integer><key>height</key><integer>1080</integer>" +
                            "<key>refreshRate</key><real>60.0</real><key>overscanned</key><false/>" +
                            "<key>streams</key><array><dict><key>type</key><integer>110</integer>" +
                            "<key>dataPort</key><integer>" + (video?.localPort ?: 0) + "</integer></dict></array>")
                        type = "text/x-apple-plist+xml"
                    }
                    method == "POST" && path == "/stream" -> {
                        synchronized(this) {
                            if (active != null && active?.peer != peer) throw IOException("Receiver busy")
                            active?.close()
                            lastError = ""
                            muted = false
                            session = Session(peer, socket, controlByPeer[peer])
                            active = session
                        }
                        reportState("手机已连接，正在接收画面…")
                        // Framed video follows the plist body without an HTTP response.
                        continue
                    }
                    method == "POST" && path == "/reverse" -> {
                        result = "101 Switching Protocols"
                        extra["upgrade"] = "PTTH/1.0"
                        extra["connection"] = "Upgrade"
                    }
                    method == "SETUP" -> {
                        extra["session"] = "wifiscreen"
                        extra["transport"] = if (path.endsWith("/audio"))
                            "RTP/AVP/UDP;unicast;mode=record;server_port=" + audio!!.localPort +
                                ";control_port=" + audio!!.localPort + ";timing_port=" + audio!!.localPort
                        else "RTP/AVP/TCP;unicast;mode=record;server_port=" + video!!.localPort
                    }
                    method == "GET_PARAMETER" -> body = "volume: -3.000000\r\n"
                    method == "SET_PARAMETER" -> {
                        val db = Rtsp.parameters(request.body)["volume"]?.toDoubleOrNull()?.takeIf { it.isFinite() }
                        if (db != null) active?.takeIf { it.peer == peer }?.volume(
                            if (db <= -144) 0f else 10.0.pow(db / 20.0).toFloat().coerceIn(0f, 1f))
                    }
                    method in listOf("ANNOUNCE", "RECORD", "OPTIONS", "TEARDOWN") -> extra["session"] = "wifiscreen"
                    path in listOf("/feedback", "/heartbat", "/stop") -> Unit
                    else -> result = "501 Not Implemented"
                }
                extra["content-type"] = type
                extra["content-length"] = body.toByteArray(Charsets.UTF_8).size.toString()
                output.write(LegacyResponse.encode(protocol, result, extra, body)); output.flush()
                if (method == "TEARDOWN" || path == "/stop") {
                    active?.takeIf { it.peer == peer }?.close()
                    break
                }
            }
        } catch (_: EOFException) {
        } catch (failure: Exception) {
            if (session != null && !session!!.closed && running) lastError = failure.message ?: failure.javaClass.simpleName
        } finally {
            sockets.remove(socket)
            runCatching { socket.close() }
            synchronized(this) {
                if (controlByPeer[peer] === socket) controlByPeer.remove(peer)
                session?.close()
                if (session != null && active === session) {
                    active = null
                    val message = if (lastError.isEmpty()) "投屏已结束，等待手机重新连接"
                        else "投屏连接中断，请在手机上重新选择设备"
                    main.post { if (running && generation == token && active == null) ended(message) }
                }
            }
        }
    }

    private fun receiveAudio(datagram: DatagramSocket, token: Int) {
        val bytes = ByteArray(65536)
        val packet = DatagramPacket(bytes, bytes.size)
        while (running && generation == token) {
            packet.length = bytes.size
            try { datagram.receive(packet) } catch (_: SocketTimeoutException) { continue } catch (_: IOException) { break }
            val session = active ?: continue
            if (session.closed || packet.address.hostAddress != session.peer) continue
            session.receivedBytes.addAndGet(packet.length.toLong())
            session.sound.offer(packet.data.copyOfRange(0, packet.length))
        }
    }

    fun streamCounters(): StreamCounters? = active?.takeUnless { it.closed }?.let {
        StreamCounters(it.id, it.decoder.presented.get(), it.receivedBytes.get())
    }
    fun snapshot(): ReceiverSnapshot {
        val session = active?.takeUnless { it.closed }
        return ReceiverSnapshot(session?.id ?: 0, session?.peer.orEmpty(), session?.hasPicture == true,
            session?.decoder?.width ?: 0, session?.decoder?.height ?: 0,
            session?.decoder?.received?.get() ?: 0, session?.decoder?.decoded?.get() ?: 0,
            session?.decoder?.queueDepth ?: 0, session?.decoder?.outputAgeMs ?: -1,
            session?.let { SystemClock.elapsedRealtime() - it.started } ?: 0,
            session?.sound?.packets?.get() ?: 0, session?.sound?.pcmBytes?.get() ?: 0,
            session?.sound?.errorMessage.orEmpty(), lastError, session?.sound?.playing == true)
    }
    fun mute(value: Boolean) { muted = value; active?.applyVolume() }
    @Synchronized fun rename(value: String): String? {
        ReceiverName.error(value)?.let { return it }
        if (active?.closed == false) return "请先结束投屏再修改设备名称"
        val updated = value.trim()
        if (updated == name) return null
        identity.edit().putString("display_name", updated).apply()
        val restart = running
        val previousAddress = address
        if (restart) { stop(); start(previousAddress) }
        return null
    }
    fun report(): String = active?.report() ?: lastReport
    fun diagnostics(): String = "固定诊断地址：http://" + address + ":" + CONTROL_PORT + "/diagnostics\n" +
        "应用在前台时可读取，退出应用后关闭。\n\n" + report() + "\n\n" + deviceReport
    fun disconnect() { active?.close() }
    @Synchronized fun stop() {
        running = false
        generation++
        active?.close(); active = null
        runCatching { control?.close() }; runCatching { video?.close() }; runCatching { audio?.close() }
        synchronized(sockets) { sockets.toList().forEach { runCatching { it.close() } }; sockets.clear() }
        controlByPeer.clear()
        val old = discovery
        discovery = null
        if (!setup.isShutdown) setup.execute { old?.close() }
        multicast?.let { if (it.isHeld) it.release() }; multicast = null
        workers.shutdownNow()
    }
    fun release() { stop(); setup.shutdown() }
    companion object {
        // Emulators can forward mDNS onto the host LAN; never shadow a real projector.
        val displayName: String get() = if (Build.FINGERPRINT.startsWith("generic") || Build.MODEL.startsWith("sdk_"))
            "WifiScreen Emulator Test" else "WifiScreen 投影"
        const val CONTROL_PORT = 47110
        const val VIDEO_PORT = 47111
        const val AUDIO_PORT = 47112
    }
    private fun plist(dict: String) = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><plist version=\"1.0\"><dict>" + dict + "</dict></plist>"
}

data class ReceiverSnapshot(
    val sessionId: Long, val peer: String, val playing: Boolean,
    val width: Int, val height: Int, val received: Long, val decoded: Long,
    val queueDepth: Int, val outputAgeMs: Long, val sessionAgeMs: Long,
    val audioPackets: Long, val audioPcmBytes: Long, val audioError: String, val error: String,
    val audioPlaying: Boolean
)
