package com.kanayama.wifiscreen

enum class VideoEncoding(val label: String, val mime: String, val asset: String) {
    H264("H.264", "video/avc", "codec-test/h264.mp4"),
    H265("H.265", "video/hevc", "codec-test/h265.mp4");

    companion object {
        fun saved(value: String?): VideoEncoding = values().firstOrNull { it.name == value } ?: H264
    }
}

data class VideoConfiguration(val encoding: VideoEncoding, val csd: List<ByteArray>,
    val legacyHevc: Boolean = false) {
    fun sameAs(other: VideoConfiguration?) = other != null && encoding == other.encoding &&
        legacyHevc == other.legacyHevc && csd.size == other.csd.size &&
        csd.indices.all { csd[it].contentEquals(other.csd[it]) }

    companion object {
        fun parse(data: ByteArray): VideoConfiguration {
            // Legacy HEVC senders wrap Annex B VPS/SPS/PPS in an AVC-shaped record,
            // with an empty final PPS slot. The actual NAL headers identify HEVC.
            if (LegacyHevc.isLegacyConfiguration(data))
                return VideoConfiguration(VideoEncoding.H265, listOf(LegacyHevc.legacyConfiguration(data)), true)
            if (data.size >= 7 && data[4].toInt() and 255 == 255 && data[5].toInt() and 224 == 224) {
                val avc = LegacyAvc.configuration(data)
                return VideoConfiguration(VideoEncoding.H264, listOf(avc.sps, avc.pps))
            }
            return VideoConfiguration(VideoEncoding.H265, listOf(LegacyHevc.configuration(data)))
        }
    }
}
