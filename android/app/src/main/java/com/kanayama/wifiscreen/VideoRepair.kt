package com.kanayama.wifiscreen

enum class VideoRepairState { IDLE, WAITING, REBUILDING, VERIFYING, PRESENTED, TIMED_OUT, FAILED, STOPPED }
data class VideoRepairStatus(val state: VideoRepairState, val message: String, val attempts: Long,
    val requestedAtMs: Long?, val completedAtMs: Long?) {
    val active: Boolean get() = state in listOf(VideoRepairState.WAITING, VideoRepairState.REBUILDING, VideoRepairState.VERIFYING)
}

/** Retains a bounded copy of an IDR picture while the old decoder continues consuming every unit. */
class IdrPictureCollector<T>(private val maxUnits: Int = 32, private val maxBytes: Int = 4 * 1024 * 1024) {
    private val units = mutableListOf<T>()
    private var first: AvcSlice? = null
    private var lastMb = -1
    private var bytes = 0
    val size: Int get() = units.size
    fun clear() { units.clear(); first = null; lastMb = -1; bytes = 0 }
    fun offer(value: T, slice: AvcSlice?, retainedBytes: Int): List<T>? {
        if (slice == null) { clear(); return null }
        if (slice.firstMb == 0) {
            // The next picture's first slice is the ordered TCP boundary of the retained IDR.
            val complete = if (units.isNotEmpty()) units.toList() else null
            clear()
            if (slice.idr) { first = slice; append(value, slice, retainedBytes) }
            return complete
        }
        val start = first
        if (!slice.idr || start == null || slice.copy(firstMb = 0) != start || slice.firstMb <= lastMb) clear()
        else append(value, slice, retainedBytes)
        return null
    }
    private fun append(value: T, slice: AvcSlice, retainedBytes: Int) {
        if (retainedBytes < 0 || units.size >= maxUnits || retainedBytes > maxBytes - bytes) clear()
        else { units.add(value); bytes += retainedBytes; lastMb = slice.firstMb }
    }
}

/** UI requests never touch MediaCodec. The decoder thread owns collection and the one rebuild attempt. */
class VideoRepair<T>(private val waitMs: Long = 2000, private val verifyMs: Long = 3000,
    private val cooldownMs: Long = 30_000) {
    private var status = VideoRepairStatus(VideoRepairState.IDLE, "尚未请求修复", 0, null, null)
    private var deadlineMs = 0L
    private val collector = IdrPictureCollector<T>()
    @Synchronized fun snapshot(): VideoRepairStatus = status
    @Synchronized fun rejection(nowMs: Long): String? = when {
        status.state == VideoRepairState.STOPPED -> "投屏已结束"
        status.active -> "正在修复，请稍候"
        status.requestedAtMs?.let { nowMs - it < cooldownMs } == true -> "请稍后再试，修复间隔至少 30 秒"
        else -> null
    }
    @Synchronized fun request(nowMs: Long): Boolean {
        if (rejection(nowMs) != null) return false
        collector.clear()
        status = VideoRepairStatus(VideoRepairState.WAITING, "正在等待新的完整画面，声音继续播放…",
            status.attempts + 1, nowMs, null)
        deadlineMs = nowMs + waitMs
        return true
    }
    @Synchronized fun configurationChanged() { collector.clear() }
    @Synchronized fun offer(value: T, slice: AvcSlice?, retainedBytes: Int, nowMs: Long,
        receivedAfterRequest: Boolean): List<T>? {
        tick(nowMs)
        if (status.state != VideoRepairState.WAITING || !receivedAfterRequest) return null
        val complete = collector.offer(value, slice, retainedBytes) ?: return null
        collector.clear()
        status = status.copy(state = VideoRepairState.REBUILDING, message = "正在重新启动画面…")
        deadlineMs = nowMs + verifyMs
        return complete
    }
    @Synchronized fun started() {
        if (status.state == VideoRepairState.REBUILDING)
            status = status.copy(state = VideoRepairState.VERIFYING, message = "正在等待画面恢复…")
    }
    @Synchronized fun presented(nowMs: Long) {
        if (status.state == VideoRepairState.VERIFYING) {
            status = status.copy(state = VideoRepairState.PRESENTED, completedAtMs = nowMs,
                message = "视频已重新启动，请检查颜色是否恢复；若仍异常，请在手机断开后重新投屏。")
        }
    }
    @Synchronized fun fail(nowMs: Long, message: String = "画面未能重新启动，请在手机断开后重新投屏。") {
        collector.clear()
        status = status.copy(state = VideoRepairState.FAILED, message = message, completedAtMs = nowMs)
    }
    @Synchronized fun tick(nowMs: Long) {
        if (!status.active || nowMs < deadlineMs) return
        if (status.state == VideoRepairState.WAITING) {
            collector.clear()
            status = status.copy(state = VideoRepairState.TIMED_OUT, completedAtMs = nowMs,
                message = "未等到可修复的完整画面，当前投屏继续。请在手机断开后重新投屏。")
        } else fail(nowMs)
    }
    @Synchronized fun stop(nowMs: Long) {
        collector.clear()
        status = status.copy(state = VideoRepairState.STOPPED, message = "投屏已结束", completedAtMs = nowMs)
    }
}
