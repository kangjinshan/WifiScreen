package com.kanayama.wifiscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Uses WFD only when the OS already grants the required privilege. Never invokes su or ADB. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class NativeReceiver(
    private val context: Context,
    private val status: (String) -> Unit,
    private val media: (StreamBuffer?) -> Unit,
    private val deviceName: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val connecting = AtomicBoolean(false)
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    @Volatile private var transport: RtpTransport? = null
    private var generation = 0
    private var advertised = false
    private var videoFormats = ""

    fun start() {
        if (running) return
        if (!DeviceProfile.hasP2p(context)) { status("系统没有提供 Wi-Fi Direct 接收能力"); return }
        if (!DeviceProfile.hasWfdPermission(context)) { status("系统未向本应用开放 Miracast 接收权限"); return }
        videoFormats = DeviceProfile.videoFormats() ?: run { status("没有找到可用的 H.264 解码器"); return }
        manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager!!.initialize(context, Looper.getMainLooper()) {
            stop()
            status("Wi-Fi Direct 通道已断开，请重新开始接收")
        }
        running = true
        generation++
        val token = generation
        receiver = object : BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(c: Context, intent: Intent) {
                if (!running || token != generation) return
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        val device = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        device?.deviceName?.takeIf { it.isNotBlank() }?.let(deviceName)
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> queryConnection(token)
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        if (intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                            stop()
                            status("Wi-Fi Direct 已关闭，请检查 Wi-Fi 设置")
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        try {
            manager!!.requestGroupInfo(channel) { group ->
                if (!running || token != generation) return@requestGroupInfo
                if (group != null) {
                    stop()
                    status("Wi-Fi Direct 正被其他连接使用，请先结束原来的投屏")
                } else configureWfd(true, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (!running || token != generation) return
                        advertised = true
                        try {
                            manager!!.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                                override fun onSuccess() {
                                    if (running && token == generation) status("已发布 Miracast 接收信息，等待手机连接")
                                }
                                override fun onFailure(reason: Int) {
                                    if (running && token == generation) {
                                        stop()
                                        status("设备发现启动失败（" + reason + "），请检查 Wi-Fi 和定位开关")
                                    }
                                }
                            })
                        } catch (error: SecurityException) {
                            stop()
                            status("系统拒绝 Wi-Fi Direct 发现，请检查附近设备 / 定位权限")
                        }
                    }
                    override fun onFailure(reason: Int) {
                        if (running && token == generation) {
                            stop()
                            status("系统拒绝 Miracast 设备发布（" + reason + "）")
                        }
                    }
                })
            }
        } catch (_: SecurityException) {
            stop()
            status("系统拒绝访问 Wi-Fi Direct，请检查权限")
        }
    }

    private fun configureWfd(enabled: Boolean, listener: WifiP2pManager.ActionListener) {
        try {
            val type = Class.forName("android.net.wifi.p2p.WifiP2pWfdInfo")
            val info = type.getDeclaredConstructor().newInstance()
            type.getMethod("setWfdEnabled", Boolean::class.javaPrimitiveType).invoke(info, enabled)
            type.getMethod("setDeviceType", Int::class.javaPrimitiveType).invoke(info, 1)
            type.getMethod("setSessionAvailable", Boolean::class.javaPrimitiveType).invoke(info, enabled)
            type.getMethod("setControlPort", Int::class.javaPrimitiveType).invoke(info, 7236)
            type.getMethod("setMaxThroughput", Int::class.javaPrimitiveType).invoke(info, 50)
            val method = WifiP2pManager::class.java.methods.first {
                it.name.equals("setWfdInfo", true) && it.parameterTypes.size == 3
            }
            method.invoke(manager, channel, info, listener)
        } catch (_: Exception) { listener.onFailure(WifiP2pManager.ERROR) }
    }

    private fun queryConnection(token: Int) {
        try {
            manager?.requestConnectionInfo(channel) { info ->
                if (!running || token != generation || !advertised) return@requestConnectionInfo
                if (!info.groupFormed) {
                    if (connecting.get()) {
                        socket?.close()
                        transport?.stop()
                        status("手机已断开，等待重新连接")
                    }
                    return@requestConnectionInfo
                }
                manager?.requestGroupInfo(channel) { group ->
                    if (!running || token != generation || group == null || !connecting.compareAndSet(false, true))
                        return@requestGroupInfo
                    status("Wi-Fi Direct 已连接，正在协商画面")
                    worker.execute {
                        try {
                            val address = if (!info.isGroupOwner) info.groupOwnerAddress?.hostAddress
                                else resolveClient(group, info.groupOwnerAddress?.hostAddress.orEmpty())
                            require(!address.isNullOrEmpty()) { "未取得手机的 Wi-Fi Direct 地址，需要针对该固件适配" }
                            connectSource(address, sourcePort(group), token)
                        } catch (error: Exception) {
                            main.post { if (running && generation == token) status("连接未完成：" + error.message) }
                        } finally {
                            socket?.close()
                            socket = null
                            transport?.stop()
                            transport = null
                            connecting.set(false)
                            main.post { if (generation == token) media(null) }
                        }
                    }
                }
            }
        } catch (_: SecurityException) {
            status("系统未允许读取 Wi-Fi Direct 连接信息")
        }
    }

    private fun connectSource(address: String, port: Int, token: Int) {
        if (!running || token != generation) return
        val client = Socket()
        socket = client
        client.connect(InetSocketAddress(address, port), 8000)
        client.soTimeout = 60000
        client.tcpNoDelay = true
        if (!running || token != generation) return
        val buffer = StreamBuffer()
        val rtp = RtpTransport(client.inetAddress, buffer)
        transport = rtp
        rtp.start()
        WfdSession(client.getInputStream(), client.getOutputStream(), rtp.port, videoFormats) {
            main.post {
                if (running && token == generation) {
                    status("已开始接收数据，等待第一帧画面")
                    media(buffer)
                }
            }
        }.run()
        main.post { if (running && token == generation) status("投屏会话已结束，等待重新连接") }
    }

    private fun sourcePort(group: WifiP2pGroup): Int = runCatching {
        val device = if (group.isGroupOwner) group.clientList.first() else group.owner
        val wfd = device.javaClass.getField("wfdInfo").get(device)
        (wfd.javaClass.getMethod("getControlPort").invoke(wfd) as Int).takeIf { it in 1..65535 } ?: 7236
    }.getOrDefault(7236)

    private fun resolveClient(group: WifiP2pGroup, groupIp: String): String? {
        val clients = group.clientList.map { it.deviceAddress.lowercase().substringAfter(':') }.toSet()
        val prefix = groupIp.substringBeforeLast('.', "") + "."
        repeat(6) {
            if (!running) return null
            val result = runCatching { File("/proc/net/arp").readLines() }.getOrDefault(emptyList())
                .drop(1).map { it.trim().split(Regex("\\s+")) }
                .firstOrNull { fields ->
                    fields.size >= 6 && fields[0].startsWith(prefix) && fields[0] != groupIp &&
                        fields[3].lowercase().substringAfter(':') in clients && fields[2] == "0x2"
                }?.first()
            if (result != null) return result
            Thread.sleep(250)
        }
        return null
    }

    fun stop() {
        running = false
        generation++
        if (advertised) {
            configureWfd(false, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {}
            })
            runCatching { manager?.stopPeerDiscovery(channel, null) }
            if (connecting.get()) runCatching { manager?.removeGroup(channel, null) }
        }
        advertised = false
        runCatching { socket?.close() }
        transport?.stop()
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        media(null)
    }
    fun release() { stop(); worker.shutdownNow() }
}
