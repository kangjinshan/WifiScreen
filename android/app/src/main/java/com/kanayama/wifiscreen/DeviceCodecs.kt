package com.kanayama.wifiscreen

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

data class DeviceDecoder(val name: String, val hardware: Boolean)

object DeviceCodecs {
    private fun hardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated
        val name = info.name.lowercase(java.util.Locale.ROOT)
        return !name.startsWith("omx.google.") && !name.startsWith("c2.android.") &&
            !name.startsWith("c2.google.") && !name.contains(".sw.") && !name.contains("ffmpeg")
    }

    fun find(encoding: VideoEncoding, width: Int = 1920, height: Int = 1080): DeviceDecoder? = runCatching {
        val format = MediaFormat.createVideoFormat(encoding.mime, width, height)
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
            .filter { info -> info.supportedTypes.any { it.equals(encoding.mime, true) } &&
                runCatching { info.getCapabilitiesForType(encoding.mime).isFormatSupported(format) }.getOrDefault(false) }
            .sortedByDescending { hardware(it) }.firstOrNull()?.let { DeviceDecoder(it.name, hardware(it)) }
    }.getOrNull()
}
