package com.kanayama.wifiscreen

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import java.net.Inet4Address

data class SystemReceiver(val label: String, val packageName: String, val intent: Intent)

object DeviceProfile {
    const val WFD_PERMISSION = "android.permission.CONFIGURE_WIFI_DISPLAY"
    fun hasWfdPermission(context: Context) =
        context.packageManager.checkPermission(WFD_PERMISSION, context.packageName) == PackageManager.PERMISSION_GRANTED
    fun hasP2p(context: Context) =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) &&
            context.getSystemService(Context.WIFI_P2P_SERVICE) != null

    @Suppress("DEPRECATION")
    fun addresses(context: Context): List<String> = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        manager.allNetworks.flatMap { network ->
            val caps = manager.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true)
                manager.getLinkProperties(network)?.linkAddresses.orEmpty().map { it.address }
            else emptyList()
        }.filterIsInstance<Inet4Address>().filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }.distinct()
    }.getOrDefault(emptyList())

    @Suppress("DEPRECATION")
    fun systemReceivers(context: Context): List<SystemReceiver> =
        context.packageManager.getInstalledApplications(0).mapNotNull { app ->
            val label = app.loadLabel(context.packageManager).toString()
            val name = app.packageName.lowercase()
            val resemblesReceiver = listOf("miracast", "wifidisplay", "miralink", ".wfd").any { it in name } ||
                label.contains("Miracast", true) || label.contains("投屏") || label.contains("同屏")
            if (!resemblesReceiver || app.packageName == context.packageName || !app.enabled) null
            else (context.packageManager.getLeanbackLaunchIntentForPackage(app.packageName)
                ?: context.packageManager.getLaunchIntentForPackage(app.packageName))
                ?.let { SystemReceiver(label, app.packageName, it) }
        }.sortedBy { it.label }

    fun videoFormats(): String? = runCatching {
        val modes = listOf(
            Triple(0, 640 to 480, 60.0), Triple(5, 1280 to 720, 30.0),
            Triple(6, 1280 to 720, 60.0), Triple(7, 1920 to 1080, 30.0),
            Triple(8, 1920 to 1080, 60.0)
        )
        val caps = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            .mapNotNull { runCatching { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull() }
        val supported = modes.filter { mode -> caps.any { cap ->
            runCatching { cap.videoCapabilities.areSizeAndRateSupported(mode.second.first, mode.second.second, mode.third) }.getOrDefault(false)
        } }
        if (supported.isEmpty()) return null
        val bitmap = supported.fold(0) { acc, mode -> acc or (1 shl mode.first) }
        val native = supported.last().first shl 3
        val profile = if (caps.any { cap -> cap.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh } }) 2 else 1
        // Level 4.2 covers the advertised maximum; actual size/rate came from device codecs.
        "%02x 00 %02x 10 %08x 00000000 00000000 00 0000 0000 00 none none".format(native, profile, bitmap)
    }.getOrNull()

    @Suppress("DEPRECATION")
    fun report(context: Context): String = buildString {
        appendLine("WifiScreen " + BuildConfig.VERSION_NAME)
        appendLine("Manufacturer: " + Build.MANUFACTURER)
        appendLine("Model: " + Build.MODEL)
        appendLine("Android: " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT)
        appendLine("ABIs: " + Build.SUPPORTED_ABIS.joinToString())
        appendLine("IP: " + addresses(context).joinToString())
        appendLine("Wi-Fi Direct feature and service: " + hasP2p(context))
        appendLine("CONFIGURE_WIFI_DISPLAY granted: " + hasWfdPermission(context))
        appendLine("Video capability: " + (videoFormats() ?: "unavailable"))
        VideoEncoding.values().forEach { appendLine("${it.label} 1080p decoder: ${DeviceCodecs.find(it) ?: "unavailable"}") }
        appendLine("ADB switch: " + runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, -1) }.getOrDefault(-1))
        appendLine("Possible receiver launchers (not proof of Miracast support):")
        systemReceivers(context).forEach { appendLine(it.label + " | " + it.packageName + " | " + it.intent.component) }
        appendLine("Relevant installed components:")
        context.packageManager.getInstalledPackages(PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES)
            .filter { pkg ->
                val label = pkg.applicationInfo?.loadLabel(context.packageManager)?.toString().orEmpty()
                listOf("xgimi", "miracast", "wifidisplay", "cast", "wfd", "hpplay", "happyplay", "eshare").any { it in pkg.packageName.lowercase() } ||
                    label.contains("投屏") || label.contains("同屏")
            }
            .sortedBy { pkg ->
                if (listOf("hpplay", "happyplay", "miracast", "wfd", "eshare").any { it in pkg.packageName.lowercase() }) 0 else 1
            }
            .take(40).forEach { pkg ->
                appendLine(pkg.applicationInfo?.loadLabel(context.packageManager).toString() + " | " + pkg.packageName + " " + pkg.versionName)
                appendLine("  system app: " + (((pkg.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0))
                appendLine("  WFD permission granted: " + (context.packageManager.checkPermission(WFD_PERMISSION, pkg.packageName) == PackageManager.PERMISSION_GRANTED))
                pkg.activities.orEmpty().filter { it.exported }.forEach { appendLine("  activity: " + it.name + " permission=" + it.permission) }
                pkg.services.orEmpty().filter { it.exported }.forEach { appendLine("  service: " + it.name + " permission=" + it.permission) }
            }
        appendLine("No root, settings writes, or system-service calls were used to collect this report.")
    }
}
