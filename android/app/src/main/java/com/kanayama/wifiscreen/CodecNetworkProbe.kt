package com.kanayama.wifiscreen

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import java.net.Inet4Address

/** Samples only the LAN used by the receiver; an unanswered ICMP probe is unknown, not proven loss. */
class CodecNetworkProbe(private val context: Context, private val address: String) {
    private var identity: String? = null
    private var changed = false
    private var connected = true
    private var samples = 0
    private var transport = "未知"
    private var linkMbps: Int? = null
    private var rssi: Int? = null
    private var probes = 0
    private val replies = mutableListOf<Long>()

    @Suppress("DEPRECATION")
    @Synchronized fun sample() {
        samples++
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.allNetworks.firstOrNull { network ->
            val caps = manager.getNetworkCapabilities(network)
            val lan = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            lan && (address.isEmpty() || manager.getLinkProperties(network)?.linkAddresses?.any {
                it.address.hostAddress == address } == true)
        }
        if (network == null) { connected = false; return }
        val links = manager.getLinkProperties(network)
        val key = network.toString() + ":" + links?.linkAddresses.toString()
        if (identity != null && identity != key) changed = true
        identity = key
        val wifi = manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        transport = if (wifi) "Wi-Fi" else "有线网络"
        if (wifi) runCatching {
            val info = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
            info?.linkSpeed?.takeIf { it > 0 }?.let { linkMbps = minOf(linkMbps ?: it, it) }
            info?.rssi?.takeIf { it in -100..-1 }?.let { rssi = minOf(rssi ?: it, it) }
        }
        val gateway = links?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway ?: return
        probes++
        val start = SystemClock.elapsedRealtime()
        if (runCatching { gateway.isReachable(200) }.getOrDefault(false))
            replies.add(SystemClock.elapsedRealtime() - start)
    }

    @Synchronized fun result() = NetworkTrial(connected && samples > 0 && identity != null, changed, transport, linkMbps, rssi,
        probes, replies.size, replies.sorted().let { if (it.isEmpty()) null else it[((it.size - 1) * 0.95).toInt()] })
}
