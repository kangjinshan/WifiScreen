package com.kanayama.wifiscreen

import android.content.Context
import android.net.wifi.WifiManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import android.util.Log
import android.util.AtomicFile
import android.view.Surface
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.File
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

/** Independent legacy LAN receiver: discovery, HTTP/RTSP session, AVC/HEVC and AAC-ELD. */
class LegacyReceiver(
    private val context: Context,
    private val surface: () -> Surface?,
    private val status: (String) -> Unit,
    private val rendered: (Int, Int) -> Unit,
    private val ended: (String) -> Unit
) {
    val name: String get() = identity.getString("display_name", null) ?: displayName
    val isRunning: Boolean get() = running
    val isReady: Boolean get() = running && discovery?.isReady == true
    @Volatile var muted = false
        private set
    private val main = Handler(Looper.getMainLooper())
    private val setup = Executors.newSingleThreadExecutor()
    private var workers = Executors.newCachedThreadPool()
    private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val controlByPeer = Collections.synchronizedMap(mutableMapOf<String, Socket>())
    private data class PendingMirror(val control: Socket, val sessionId: String, val streamTime: String,
        val uri: String, val createdMs: Long)
    private val pendingMirrors = mutableMapOf<String, PendingMirror>()
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
    @Volatile private var lastProtocolError = ""
    private val repairReportFile = AtomicFile(File(context.filesDir, "last-video-repair.txt"))
    @Volatile private var lastRepairReport = runCatching {
        repairReportFile.openRead().bufferedReader().use { it.readText().take(65_536) }
    }.getOrDefault("")
    @Volatile var address = ""
        private set
    private val identity = context.getSharedPreferences("receiver", Context.MODE_PRIVATE)
    val preferredEncoding: VideoEncoding get() = VideoEncoding.saved(identity.getString("video_encoding", null))
    val actualEncoding: VideoEncoding? get() = active?.takeUnless { it.closed }?.decoder?.encoding
    val encodingSummary: String get() = actualEncoding?.let {
        if (it == preferredEncoding) "实际 ${it.label}" else "实际 ${it.label}（首选 ${preferredEncoding.label} 未采用）"
    } ?: "首选 ${preferredEncoding.label} · 等待连接"
    private val hevcAvailable by lazy { DeviceCodecs.find(VideoEncoding.H265) != null }
    private val advertiseHevc: Boolean get() = preferredEncoding == VideoEncoding.H265 && hevcAvailable
    @Volatile var codecTestRunning = false
        private set
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

    private inner class Session(val peer: String, val videoSocket: Socket, val controlSocket: Socket?,
        val protocol: String = "legacy", val lelinkId: String = "", val uri: String = "") {
        val id = sessionIds.incrementAndGet()
        val receivedBytes = AtomicLong()
        val started = SystemClock.elapsedRealtime()
        @Volatile var hasPicture = false
        @Volatile var closed = false
        @Volatile private var audioAllowed = false
        @Volatile private var userVolume = 0.7f
        @Volatile private var ducked = false
        private val playbackClock = AvPlaybackClock()
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
        }, { failure -> if (!closed && active === this) { lastError = failure; reportState(failure) } }, playbackClock,
            if (protocol == "lelink-v2") LegacyAvc.V2_CIPHER else LegacyAvc.LEGACY_CIPHER)
        val sound = LegacyAudio({ hasPicture && audioAllowed && !closed }, { failure ->
            if (!closed && active === this) lastError = failure
        }, playbackClock)
        fun applyVolume() { sound.volume = if (muted) 0f else userVolume * if (ducked) 0.2f else 1f }
        fun volume(value: Float) { userVolume = value; applyVolume() }
        fun report(): String = "接收端：" + name + "\n手机 IP：" + peer +
            "\n时长：" + (SystemClock.elapsedRealtime() - started) / 1000 + " 秒" +
            "\n视频接收：" + decoder.received.get() + " 帧" +
            "\n视频输出：" + decoder.decoded.get() + " 帧" +
            "\n视频已显示：" + decoder.presented.get() + " 帧" +
            "\n视频尺寸：" + decoder.width + " × " + decoder.height +
            "\n视频编码：" + (decoder.encoding?.label ?: "等待参数") + "\n首选编码：" + preferredEncoding.label +
            "\n投屏协议：" + protocol +
            "\n解码状态：" + decoder.decoderState +
            "\n队列深度：" + decoder.queueDepth + "\n缓冲等待：" + decoder.backpressureWaits +
            "\n视频正在追帧：" + decoder.catchingUp + "\n画面接收至显示：" + decoder.receiverLatencyMs + " ms" +
            "\n待显示队列：" + decoder.presentationQueueDepth + "\n最近画面显示等待：" + decoder.presentationHoldMs + " ms" +
            "\n视频跟随音频：" + decoder.usingAudioClock + "\n音画时间差：" + decoder.audioSkewMs + " ms" +
            "\n接收关键帧：" + decoder.keyframes.get() + "\n距上次视频输出：" + decoder.outputAgeMs + " ms" +
            "\n音频包：" + sound.packets.get() + "\nPCM 写入：" + sound.pcmBytes.get() + " bytes" +
            "\n音频播放已启动：" + sound.playing + "\n实际播放样本：" + sound.playedFrames +
            "\n音频时间戳兼容回退：" + sound.timestampFallbacks + "\n音频时间轴重置：" + sound.timestampResets.get() +
            "\n音频播放欠载：" + sound.underruns + "\n音频缓冲：" + sound.bufferedMs + " ms" +
            "\n波形补偿样本：" + sound.concealedSamples.get() + "\n防欠载补偿样本：" + sound.starvationSamples.get() +
            "\n跳过音频缺口样本：" + sound.skippedSamples.get() + "\n已补偿的迟到样本：" + sound.overlapSamples.get() +
            "\n系统原有音频缓冲：" + sound.platformBufferMs + " ms" +
            "\n音频 RTP 缺包 / 乱序：" + sound.missingPackets + " / " + sound.reorderedPackets +
            "\n音频到达抖动估计：" + String.format(java.util.Locale.ROOT, "%.2f", sound.jitterMs) + " ms" +
            "\n音频解码等待：" + sound.inputRetries.get() + "\n新增缓冲目标：" + PlaybackTiming.BUFFER_MS + " ms" +
            "\n最近错误：" + lastError.ifEmpty { "无" } +
            "\n\n视频诊断（源颜色字段为 H.264 值，输出颜色字段为 Android 值；null 表示未知）：\n" +
            decoder.diagnosticSnapshot().toJson().toString(2) +
            "\n画面修复：\n" + decoder.repairStatus().toJson().toString(2)
        fun close(closeControl: Boolean = true) {
            if (closed) return
            closed = true
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
            lastReport = report()
            decoder.close(); sound.close()
            runCatching { videoSocket.close() }
            if (closeControl) runCatching { controlSocket?.close() }
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
                val mdns = LegacyDiscovery(bind, host, name, mac, uid, c.localPort, v.localPort, advertiseHevc)
                val accepted = synchronized(this) {
                    if (running && generation == token) { discovery = mdns; true } else false
                }
                if (!accepted) { mdns.close(); return@execute }
                mdns.updateEncoding(advertiseHevc)
                Log.i("WifiScreen", "Receiver discovery registered: " + name)
                reportState("正在发布接收设备…")
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
        var pairing: LelinkPairing? = null
        var v2Requested = false
        var lastRequestAt = SystemClock.elapsedRealtime()
        try {
            socket.soTimeout = 60000
            socket.tcpNoDelay = true
            val input = PushbackInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024), 4)
            val output = BufferedOutputStream(socket.getOutputStream())
            while (running && generation == token) {
                val prefix = ByteArray(4)
                val first = try { input.read() } catch (_: SocketTimeoutException) {
                    // V2's mirror control can be silent while TCP video/audio continue.
                    val hasMirror = active?.let { !it.closed && it.protocol == "lelink-v2" && it.peer == peer } == true
                    if (pairing?.ready == true && (hasMirror || SystemClock.elapsedRealtime() - lastRequestAt < 60_000)) continue
                    break
                }
                if (first < 0) break
                prefix[0] = first.toByte()
                // A timeout inside a partial prefix is a framing failure, never restart parsing it.
                DataInputStream(input).readFully(prefix, 1, 3)
                val verb = String(prefix, Charsets.US_ASCII)
                val encryptedRequest = pairing?.ready == true
                if (!encryptedRequest && verb !in listOf("GET ", "POST", "OPTI", "ANNO", "SETU", "RECO", "GET_", "SET_", "TEAR")) {
                    if (session == null && socket.localPort == VIDEO_PORT) synchronized(this) {
                        purgePendingMirrors()
                        val pending = pendingMirrors.remove(peer) ?: throw IOException("Video has no verified setup")
                        if (codecTestRunning || active?.closed == false) throw IOException("Receiver busy")
                        streamTime = pending.streamTime
                        muted = false; lastError = ""; lastProtocolError = ""
                        session = Session(peer, socket, pending.control, "lelink-v2", pending.sessionId, pending.uri)
                        active = session
                        reportState("手机已通过新版协商，正在接收画面…")
                    }
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
                val request = if (encryptedRequest) {
                    val size = LelinkRecords.size(prefix)
                    val record = ByteArray(size + 20)
                    prefix.copyInto(record)
                    DataInputStream(input).readFully(record, 4, record.size - 4)
                    val plain = ByteArrayInputStream(pairing!!.decrypt(record))
                    val message = Rtsp.read(plain, allowRepeatedLelinkDid = true) ?: throw IOException("Empty encrypted request")
                    if (plain.available() != 0) throw IOException("Multiple requests in encrypted record")
                    message
                } else {
                    input.unread(prefix)
                    Rtsp.read(input) ?: break
                }
                val parts = request.startLine.split(" ")
                lastRequestAt = SystemClock.elapsedRealtime()
                if (parts.size != 3 || parts[2] !in listOf("HTTP/1.1", "RTSP/1.0", "HTTP/1.0")) throw IOException("Invalid request line")
                val method = parts[0]
                val path = parts[1]
                val protocol = parts[2]
                if (path !in listOf("/status", "/diagnostics", "/")) lastHandshake = method + if (path.startsWith("/"))
                    " " + path.substringBefore('?').take(64) else " RTSP"
                request.header("stream-time")?.let { streamTime = it }
                var body = ""
                var binaryBody: ByteArray? = null
                var type = "text/parameters"
                var result = "200 OK"
                val extra = linkedMapOf("server" to "WifiScreen/0.1")
                request.header("cseq")?.let { extra["cseq"] = it }
                val lelinkSession = request.header("lelink-session-id").orEmpty().take(128)
                val v2Control = encryptedRequest && socket.localPort == CONTROL_PORT
                when {
                    path == "/lelink-player-info" && method == "GET" -> {
                        v2Requested = true
                        body = LelinkPlist.encode(mapOf("name" to name, "deviceid" to mac, "vv" to "2", "atv" to 0,
                            "htv" to 1, "hstv" to "500.0", "feature" to 31231,
                            "mst" to 1, "ast" to 1, "avformat_support" to if (advertiseHevc) 1 else 0,
                            "displays" to listOf(mapOf("width" to 1920, "height" to 1080, "refresh-rate" to 60.0))))
                        type = "application/plist+xml"
                    }
                    path in listOf("/lelink-setup", "/lelink-verify") && method == "POST" -> {
                        v2Requested = true
                        if (encryptedRequest || socket.localPort != CONTROL_PORT) throw IOException("Unexpected pairing channel")
                        val pair = pairing ?: LelinkPairing().also { pairing = it }
                        binaryBody = pair.handshake(path, request.binaryBody ?: ByteArray(0))
                        type = "application/octet-stream"
                    }
                    path.startsWith("/lelink-") && !v2Control -> {
                        v2Requested = true
                        result = "403 Forbidden"
                    }
                    v2Control && method == "POST" && path in listOf("/lelink-connect", "/lelink-reconnect") -> {
                        if (lelinkSession.isEmpty()) throw IOException("Missing Lelink session")
                        body = LelinkPlist.encode(mapOf("feature" to "31231"))
                        type = "application/plist+xml"
                    }
                    v2Control && method == "POST" && path == "/passth-reverse" -> {
                        result = "101 Switching Protocols"
                        extra["upgrade"] = "event"
                        extra["connection"] = "Upgrade"
                    }
                    v2Control && method == "SETUP" -> {
                        if (lelinkSession.isEmpty()) throw IOException("Missing Lelink session")
                        val parameters = LelinkPlist.decode(request.body)
                        val streams = parameters["streams"] as? List<*> ?: throw IOException("Missing mirror streams")
                        val videoRequested = streams.any { (it as? Map<*, *>)?.get("type") == 97L }
                        val audioRequested = streams.any { (it as? Map<*, *>)?.get("type") == 96L }
                        if (videoRequested == audioRequested) throw IOException("Invalid mirror setup")
                        if (audioRequested && streams.filterIsInstance<Map<*, *>>().filter { it["type"] == 96L }
                                .any { it["sample-rate"] != 44100L || it["sample-format"] != 212L })
                            throw IOException("Unsupported mirror audio format")
                        synchronized(this) {
                            purgePendingMirrors()
                            if (codecTestRunning) result = "503 Service Unavailable"
                            else if (videoRequested) {
                                val time = (parameters["stream-time"] as? String)?.takeIf { it.isNotEmpty() && it.length <= 128 }
                                    ?: throw IOException("Missing mirror stream time")
                                if (active?.closed == false || pendingMirrors.values.any { it.control !== socket })
                                    result = "453 Not Enough Bandwidth"
                                else pendingMirrors[peer] = PendingMirror(socket, lelinkSession, time,
                                    (parameters["uuid"] as? String).orEmpty().take(512), SystemClock.elapsedRealtime())
                            } else if (!hasV2Mirror(peer, lelinkSession))
                                result = "454 Session Not Found"
                        }
                        if (result == "200 OK") {
                            body = LelinkPlist.encode(mapOf("timing-port" to 0, "streams" to listOf(mapOf(
                                "type" to if (videoRequested) 97 else 96,
                                "data-port" to if (videoRequested) VIDEO_PORT else AUDIO_PORT,
                                "max-seq-num" to 10000))))
                        }
                        type = "application/plist+xml"
                    }
                    v2Control && method == "RECORD" -> {
                        if (!hasV2Mirror(peer, lelinkSession)) result = "454 Session Not Found"
                    }
                    v2Control && (method == "TEARDOWN" || path in listOf(
                        "/lelink-mirrormode", "/lelink-feedback", "/lelink-streaming", "/lelink-reverse", "/lelink-disconnect", "/lelink-stop")) -> Unit
                    v2Control && path in listOf("/lelink-retieve-play-info", "/lelink-playinfo", "/lelink-get-property") -> {
                        body = LelinkPlist.encode(mapOf("cast-type" to if (active?.hasPicture == true) "mirror" else "idle",
                            "item" to mapOf("uri" to (active?.takeUnless { it.closed }?.uri ?: ""))))
                        type = "application/plist+xml"
                    }
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
                            .put("lastProtocolError", lastProtocolError)
                            .put("connectionProtocol", current?.protocol ?: "")
                            .put("connected", current != null).put("sourceIp", current?.peer ?: "")
                            .put("sessionSeconds", current?.let { (SystemClock.elapsedRealtime() - it.started) / 1000 } ?: 0)
                            .put("ready", isReady).put("playing", current?.hasPicture == true)
                            .put("muted", muted)
                            .put("videoReceived", current?.decoder?.received?.get() ?: 0)
                            .put("videoDecoded", current?.decoder?.decoded?.get() ?: 0)
                            .put("videoPresented", current?.decoder?.presented?.get() ?: 0)
                            .put("videoPresentedAgeMs", current?.decoder?.presentedAgeMs ?: -1)
                            .put("videoPresentationQueueDepth", current?.decoder?.presentationQueueDepth ?: 0)
                            .put("videoPresentationHoldMs", current?.decoder?.presentationHoldMs ?: 0)
                            .put("videoRenderCallMs", current?.decoder?.renderCallMs ?: 0)
                            .put("videoForcedPresentations", current?.decoder?.forcedPresentations?.get() ?: 0)
                            .put("videoCodec", current?.decoder?.codecName ?: "")
                            .put("preferredVideoEncoding", preferredEncoding.name)
                            .put("videoEncoding", current?.decoder?.encoding?.name ?: "")
                            .put("codecTestRunning", codecTestRunning)
                            .put("videoDiagnostics", current?.decoder?.diagnosticSnapshot()?.toJson() ?: JSONObject.NULL)
                            .put("videoRepair", current?.decoder?.repairStatus()?.toJson() ?: JSONObject.NULL)
                            .put("videoRepairReportAvailable", lastRepairReport.isNotEmpty())
                            .put("videoWidth", current?.decoder?.width ?: 0)
                            .put("videoHeight", current?.decoder?.height ?: 0)
                            .put("videoQueueDepth", current?.decoder?.queueDepth ?: 0)
                            .put("videoCatchingUp", current?.decoder?.catchingUp ?: false)
                            .put("videoReceiverLatencyMs", current?.decoder?.receiverLatencyMs ?: -1)
                            .put("videoUsingAudioClock", current?.decoder?.usingAudioClock ?: false)
                            .put("videoAudioSkewMs", current?.decoder?.audioSkewMs ?: 0)
                            .put("videoAudioSyncDrops", current?.decoder?.audioSyncDrops?.get() ?: 0)
                            .put("videoAudioClockRejections", current?.decoder?.audioClockRejections ?: 0)
                            .put("videoAudioClockOffsetMs", current?.decoder?.audioClockOffsetMs ?: 0)
                            .put("videoBackpressureWaits", current?.decoder?.backpressureWaits ?: 0)
                            .put("videoKeyframes", current?.decoder?.keyframes?.get() ?: 0)
                            .put("videoOutputAgeMs", current?.decoder?.outputAgeMs ?: -1)
                            .put("videoDecoderState", current?.decoder?.decoderState ?: "等待投屏")
                            .put("videoPresentationDrops", current?.decoder?.dropped?.get() ?: 0)
                            .put("audioPackets", current?.sound?.packets?.get() ?: 0)
                            .put("audioPcmBytes", current?.sound?.pcmBytes?.get() ?: 0)
                            .put("audioDecodedSamples", current?.sound?.decodedSamples?.get() ?: 0)
                            .put("audioPlaying", current?.sound?.playing ?: false)
                            .put("audioPlayedFrames", current?.sound?.playedFrames ?: 0)
                            .put("audioHardwareClock", current?.sound?.hardwareClock ?: false)
                            .put("audioHardwareLagMs", current?.sound?.hardwareLagMs ?: 0)
                            .put("audioClockStable", current?.sound?.clockStable ?: false)
                            .put("audioClockSwitches", current?.sound?.clockSwitches ?: 0)
                            .put("audioClockCorrectionMs", current?.sound?.clockCorrectionMs ?: 0)
                            .put("audioPerformanceMode", current?.sound?.performanceMode ?: -1)
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
                            .put("audioStarvationSamples", current?.sound?.starvationSamples?.get() ?: 0)
                            .put("audioSkippedSamples", current?.sound?.skippedSamples?.get() ?: 0)
                            .put("audioOverlapSamples", current?.sound?.overlapSamples?.get() ?: 0)
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
                            // Extension for senders that negotiate HEVC. vv=1 clients may ignore it;
                            // keep their transport intact and report the actual received encoding.
                            "<key>avformat_support</key><integer>" + (if (advertiseHevc) 1 else 0) + "</integer>" +
                            "<key>streams</key><array><dict><key>type</key><integer>110</integer>" +
                            "<key>dataPort</key><integer>" + (video?.localPort ?: 0) + "</integer></dict></array>")
                        type = "text/x-apple-plist+xml"
                    }
                    method == "POST" && path == "/stream" -> {
                        synchronized(this) {
                            if (codecTestRunning) {
                                output.write(LegacyResponse.encode(protocol, "503 Service Unavailable", extra,
                                    "Codec test in progress; reconnect after the test.")); output.flush()
                                return
                            }
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
                val response = LegacyResponse.encode(protocol, result, extra, binaryBody ?: body.toByteArray(Charsets.UTF_8))
                output.write(if (encryptedRequest) pairing!!.encrypt(response) else response); output.flush()
                if (pairing?.ready == true) socket.soTimeout = 1000
                if (v2Control && (method == "TEARDOWN" || path in listOf("/lelink-stop", "/lelink-disconnect"))) {
                    synchronized(this) {
                        if (pendingMirrors[peer]?.sessionId == lelinkSession) pendingMirrors.remove(peer)
                        active?.takeIf { it.peer == peer && it.lelinkId == lelinkSession }?.close(closeControl = false)
                    }
                    // Keep the authenticated control channel for the sender's audio teardown.
                    continue
                }
                if (method == "TEARDOWN" || path == "/stop") {
                    active?.takeIf { it.peer == peer && it.protocol == "legacy" }?.close()
                    break
                }
            }
        } catch (_: EOFException) {
        } catch (failure: Exception) {
            if (v2Requested && running && !socket.isClosed)
                lastProtocolError = (failure.message ?: failure.javaClass.simpleName).take(180)
            if (session != null && !session!!.closed && running) lastError = failure.message ?: failure.javaClass.simpleName
        } finally {
            sockets.remove(socket)
            runCatching { socket.close() }
            synchronized(this) {
                pendingMirrors.entries.removeAll { it.value.control === socket }
                active?.takeIf { it.protocol == "lelink-v2" && it.controlSocket === socket }?.close()
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
    @Synchronized fun selectEncoding(value: VideoEncoding): String? {
        if (value == VideoEncoding.H265 && !hevcAvailable) return "这台设备没有可用的 H.265 1080p 解码器。"
        identity.edit().putString("video_encoding", value.name).apply()
        // Current session continues with its own parameter sets. New sessions see the new preference.
        if (running && !setup.isShutdown) {
            val token = generation
            setup.execute {
                if (running && generation == token) runCatching { discovery?.updateEncoding(advertiseHevc) }
                    .onFailure { failure ->
                        main.post {
                            if (running && generation == token) {
                                lastError = failure.message ?: failure.javaClass.simpleName
                                stop(); status("设备发现更新失败，正在恢复接收服务")
                            }
                        }
                    }
            }
        }
        return null
    }
    @Synchronized fun beginCodecTest(): Boolean {
        purgePendingMirrors()
        if (codecTestRunning || active?.closed == false || pendingMirrors.isNotEmpty()) return false
        codecTestRunning = true
        return true
    }
    @Synchronized fun endCodecTest() { codecTestRunning = false }
    @Synchronized private fun hasV2Mirror(peer: String, sessionId: String): Boolean {
        purgePendingMirrors()
        return sessionId.isNotEmpty() && (pendingMirrors[peer]?.sessionId == sessionId ||
            active?.let { !it.closed && it.protocol == "lelink-v2" && it.peer == peer && it.lelinkId == sessionId } == true)
    }
    private fun purgePendingMirrors() {
        val now = SystemClock.elapsedRealtime()
        pendingMirrors.entries.removeAll { it.value.control.isClosed || now - it.value.createdMs >= 15_000 }
    }
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
        "应用在前台时可读取，退出应用后关闭。\n\n" + encodingSummary + "\n新版协商错误：" + lastProtocolError.ifEmpty { "无" } + "\n\n" + report() + "\n\n" + deviceReport +
        "\n\n最近编码测试：\n" + context.getSharedPreferences("display", Context.MODE_PRIVATE).getString("codec_test_report", "尚未测试") +
        if (lastRepairReport.isEmpty()) "" else "\n\n最近一次修复前诊断（保留至下次修复）：\n" + lastRepairReport
    fun pictureRepairStatus(): VideoRepairStatus? = active?.takeUnless { it.closed }?.decoder?.repairStatus()
    val pictureRepairOwnsRecovery: Boolean get() = active?.takeUnless { it.closed }?.decoder?.repairOwnsRecovery == true
    /** Disk I/O belongs to the activity's background diagnostics executor, never to the codec/UI thread. */
    fun repairPicture(expectedSessionId: Long): String {
        val session = active?.takeUnless { it.closed || !it.hasPicture || it.id != expectedSessionId }
            ?: return "投屏已变化，请重新打开修复画面。"
        return session.decoder.requestRepair {
            val report = "保存时间（Unix ms）：${System.currentTimeMillis()}\nWifiScreen ${BuildConfig.VERSION_NAME}\n" + session.report()
            val stream = repairReportFile.startWrite()
            try {
                stream.write(report.toByteArray(Charsets.UTF_8))
                repairReportFile.finishWrite(stream)
                lastRepairReport = report
            } catch (failure: Exception) {
                repairReportFile.failWrite(stream)
                throw failure
            }
        }
    }
    fun disconnect() { active?.close() }
    @Synchronized fun stop() {
        running = false
        generation++
        active?.close(); active = null
        pendingMirrors.clear()
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
