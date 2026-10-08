package com.kanayama.wifiscreen

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class PictureMode(val label: String) { FIT("完整显示"), FILL("铺满屏幕"), MANUAL("手动调整") }

data class PictureSettings(
    val mode: PictureMode = PictureMode.FIT,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f
) {
    fun normalized() = copy(
        zoom = if (zoom.isFinite()) zoom.coerceIn(.5f, 3f) else 1f,
        panX = if (panX.isFinite()) panX.coerceIn(-1f, 1f) else 0f,
        panY = if (panY.isFinite()) panY.coerceIn(-1f, 1f) else 0f
    )
}

data class VideoBounds(val width: Int, val height: Int, val left: Int, val top: Int)

object VideoViewport {
    fun bounds(screenWidth: Int, screenHeight: Int, videoWidth: Int, videoHeight: Int,
               settings: PictureSettings): VideoBounds? {
        if (minOf(screenWidth, screenHeight, videoWidth, videoHeight) <= 0) return null
        val options = settings.normalized()
        val fit = min(screenWidth.toFloat() / videoWidth, screenHeight.toFloat() / videoHeight)
        val scale = when (options.mode) {
            PictureMode.FIT -> fit
            PictureMode.FILL -> max(screenWidth.toFloat() / videoWidth, screenHeight.toFloat() / videoHeight)
            PictureMode.MANUAL -> fit * options.zoom
        }
        val width = (videoWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (videoHeight * scale).roundToInt().coerceAtLeast(1)
        val x = if (options.mode == PictureMode.MANUAL) options.panX * abs(screenWidth - width) / 2 else 0f
        val y = if (options.mode == PictureMode.MANUAL) options.panY * abs(screenHeight - height) / 2 else 0f
        return VideoBounds(width, height, ((screenWidth - width) / 2f + x).roundToInt(),
            ((screenHeight - height) / 2f + y).roundToInt())
    }
}
