package com.kanayama.wifiscreen

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Foreground-only recovery. LAN casting deliberately does not require Internet validation. */
class ReceiverRecovery(
    private val context: Context,
    private val receiver: LegacyReceiver,
    private val changed: (RecoveryState) -> Unit,
    private val interrupted: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var running = false
    private var registered = false
    private var hadAddress = false
    private var recovering = false
    private var attempts = 0
    private var nextAttempt = 0L
    private var startingAt = 0L
    private var lastState: RecoveryState? = null
    private val stalled = StreamStallWatchdog()
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = schedule()
        override fun onLost(network: Network) = schedule()
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = schedule()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = schedule()
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            check()
            handler.removeCallbacks(this)
            handler.postDelayed(this, 2000)
        }
    }
    private fun schedule() {
        handler.post {
            if (running) { handler.removeCallbacks(tick); handler.postDelayed(tick, 500) }
        }
    }
    fun start() {
        if (running) return
        running = true
        attempts = 0; nextAttempt = 0; startingAt = 0; lastState = null
        registered = runCatching {
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build()
            connectivity.registerNetworkCallback(request, callback)
            true
        }.getOrDefault(false)
        tick.run()
    }
    private fun announce(state: RecoveryState) {
        if (state != lastState) { lastState = state; changed(state) }
    }
    private fun check() {
        val now = SystemClock.elapsedRealtime()
        val addresses = DeviceProfile.addresses(context)
        // A second network becoming available must not interrupt a healthy active LAN.
        val address = addresses.firstOrNull { it == receiver.address } ?: addresses.firstOrNull()
        if (address == null) {
            if (receiver.isRunning) { receiver.stop(); interrupted() }
            recovering = recovering || hadAddress
            nextAttempt = 0; attempts = 0; startingAt = 0; stalled.reset()
            announce(RecoveryState("", "网络未连接", "连接 Wi-Fi 或有线网络后，将自动恢复接收", false, recovering))
            return
        }
        if (receiver.isRunning && receiver.address != address) {
            receiver.stop(); interrupted(); recovering = true; nextAttempt = 0; startingAt = 0
            stalled.reset()
        }
        hadAddress = true
        if (!receiver.isRunning) {
            if (now >= nextAttempt) {
                receiver.start(address)
                attempts++
                startingAt = now
                nextAttempt = now + minOf(30_000L, 1000L shl minOf(attempts, 5))
            }
            announce(RecoveryState(address, "正在恢复接收服务", "请稍候，设备会自动出现在手机投屏列表中", false, recovering))
            return
        }
        if (!receiver.isReady) {
            if (startingAt == 0L) startingAt = now
            if (now - startingAt > 25_000) {
                receiver.stop(); interrupted(); recovering = true; startingAt = 0
            }
            announce(RecoveryState(address, "正在准备投屏", "正在发布接收设备…", false, recovering))
            return
        }
        attempts = 0; startingAt = 0
        val snapshot = receiver.snapshot()
        // A user-requested picture repair has its own deadline and reconnect guidance.
        // Do not disconnect its still-running audio/control session through the stall watchdog.
        if (receiver.pictureRepairOwnsRecovery) stalled.reset()
        else if (stalled.check(now, snapshot.sessionId, snapshot.received, snapshot.decoded,
                snapshot.queueDepth, snapshot.sessionAgeMs, snapshot.playing)) {
            receiver.disconnect(); interrupted(); recovering = true
            announce(RecoveryState(address, "画面接收异常", "接收服务已就绪，请在手机重新选择投屏设备", true, true))
            return
        }
        if (snapshot.playing) recovering = false
        announce(RecoveryState(address, if (recovering) "接收服务已恢复" else "等待手机投屏",
            if (recovering) "请在手机投屏列表中重新选择「${receiver.name}」" else "在手机投屏列表中选择此设备", true, recovering))
    }
    fun retry() {
        receiver.stop(); interrupted(); recovering = true; nextAttempt = 0; attempts = 0
        startingAt = 0; stalled.reset(); lastState = null
        if (running) { handler.removeCallbacks(tick); tick.run() }
    }
    fun stop() {
        running = false
        if (registered) runCatching { connectivity.unregisterNetworkCallback(callback) }
        registered = false
        handler.removeCallbacksAndMessages(null)
        stalled.reset()
    }
}

data class RecoveryState(val address: String, val title: String, val detail: String,
                         val ready: Boolean, val recovered: Boolean)
