package com.kanayama.wifiscreen

import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/** IPv4-only service records avoid unscoped link-local IPv6 addresses in older senders. */
class LegacyDiscovery(
    address: InetAddress, host: String, name: String, mac: String, uid: String,
    controlPort: Int, videoPort: Int
) {
    private val dns = JmDNS.create(address, host)
    init {
        try {
            val remote = mapOf(
                "port" to controlPort.toString(), "version" to "3.2", "w" to "1920", "h" to "1080",
                "raop" to controlPort.toString(), "airplay" to videoPort.toString(),
                "remote" to controlPort.toString(), "lelinkport" to controlPort.toString(),
                "devicemac" to mac, "mirror" to videoPort.toString(), "channel" to "WifiScreen-0.1",
                "feature" to "162303", "lebofeature" to "162303", "packagename" to "com.kanayama.wifiscreen",
                "u" to uid, "ver" to "1.0", "appInfo" to "0", "vv" to "1",
                "htv" to "1", "atv" to "0", "etv" to "1", "hmd" to "WifiScreen", "hstv" to "150.33"
            )
            dns.registerService(ServiceInfo.create("_leboremote._tcp.local.", name, controlPort, 0, 0, remote))
            // The same endpoint also exposes the metadata records used by legacy discovery.
            dns.registerService(ServiceInfo.create("_airplay._tcp.local.", name, videoPort, 0, 0,
                mapOf("deviceid" to mac, "features" to "0x5A7FFFF7", "model" to "WifiScreen,1",
                    "srcvers" to "220.68", "vv" to "1", "flags" to "0x4", "pw" to "0", "protovers" to "1.0")))
            dns.registerService(ServiceInfo.create("_raop._tcp.local.", mac.replace(":", "") + "@" + name,
                controlPort, 0, 0, mapOf("ch" to "2", "cn" to "0", "et" to "0", "sr" to "44100",
                    "ss" to "16", "tp" to "UDP", "vv" to "1", "am" to "WifiScreen,1", "sv" to "false", "vs" to "220.68")))
        } catch (failure: Exception) {
            dns.close()
            throw failure
        }
    }
    fun close() { runCatching { dns.unregisterAllServices(); dns.close() } }
}
