package com.kanayama.wifiscreen

data class DecodeTrial(val encoding: VideoEncoding, val decoder: String = "", val hardware: Boolean = false,
    val frames: Int = 0, val elapsedMs: Long = 5000, val p95Ms: Long = 0, val error: String? = null) {
    val fps: Double get() = if (elapsedMs > 0) frames * 1000.0 / elapsedMs else 0.0
    val smooth: Boolean get() = error == null && frames >= 90 && fps >= 36 && p95Ms <= 100
}

data class NetworkTrial(val connected: Boolean, val changed: Boolean = false, val transport: String = "未知",
    val linkMbps: Int? = null, val rssi: Int? = null, val probes: Int = 0,
    val replies: Int = 0, val p95Ms: Long? = null) {
    val constrained: Boolean get() = (rssi != null && rssi < -70) || (linkMbps != null && linkMbps < 40) ||
        (p95Ms != null && p95Ms > 30) || (replies > 0 && replies < probes * 0.8)
    val observed: Boolean get() = linkMbps != null || rssi != null || replies > 0
}

data class CodecAdvice(val recommended: VideoEncoding?, val reason: String)

/** Conservative 1080p30 guidance. Never present PHY rate or gateway probes as end-to-end throughput. */
object CodecRecommendation {
    fun evaluate(avc: DecodeTrial, hevc: DecodeTrial, network: NetworkTrial): CodecAdvice {
        if (!network.connected || network.changed)
            return CodecAdvice(null, "测试期间网络断开或发生切换，请在网络稳定后重测。")
        if (!avc.smooth && !hevc.smooth)
            return CodecAdvice(null, "两种编码均未达到 1080p30 的测试余量，请降低手机投屏分辨率后观察。")
        if (!hevc.smooth)
            return CodecAdvice(VideoEncoding.H264, "H.265 不可用或解码余量不足，H.264 更适合这台设备。")
        if (!avc.smooth)
            return CodecAdvice(VideoEncoding.H265, "本机 H.265 通过解码测试，H.264 未达到测试余量。")
        if (!hevc.hardware)
            return CodecAdvice(VideoEncoding.H264, "H.265 使用软件解码，持续投屏优先选择 H.264。")
        if (network.constrained)
            return CodecAdvice(VideoEncoding.H265, "H.265 解码有余量，当前网络有弱信号或延迟迹象；可优先尝试更省带宽的 H.265。")
        return CodecAdvice(VideoEncoding.H264, if (network.observed)
            "两种编码均能流畅解码，当前链路较稳定，优先使用兼容性更广的 H.264。"
            else "两种编码均通过；网络质量信息不足，先保留兼容性更广的 H.264。")
    }
}
