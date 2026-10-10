package com.kanayama.wifiscreen

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var ui: CinemaUi
    private lateinit var root: FrameLayout
    private lateinit var idle: FrameLayout
    private lateinit var videoFrame: FrameLayout
    private lateinit var video: SurfaceView
    private lateinit var deviceName: TextView
    private lateinit var state: TextView
    private lateinit var hint: TextView
    private lateinit var network: TextView
    private lateinit var homeSettings: Button
    private lateinit var rateOverlay: TextView
    private lateinit var receiver: LegacyReceiver
    private lateinit var recovery: ReceiverRecovery
    private var overlay: View? = null
    private var overlayKind = ""
    private var overlayBack: () -> Unit = {}
    private var dialog: AlertDialog? = null
    private var resumed = false
    private var playing = false
    private var openingKey = -1
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var showRates = false
    private var latestRecovery: RecoveryState? = null
    private var currentRates: StreamRates? = null
    private var picture = PictureSettings()
    @Volatile private var surface: Surface? = null
    private val preferences by lazy { getSharedPreferences("display", MODE_PRIVATE) }
    private val diagnostics = Executors.newSingleThreadExecutor()
    private var reportBusy = false
    private var repairBusy = false
    private var repairMessage: TextView? = null
    private var repairMessageOverride: String? = null
    private var codecTest: CodecBenchmark? = null
    private var encodingMessage: TextView? = null
    private val rateMeter = StreamRateMeter()
    private val refreshRates = object : Runnable {
        override fun run() {
            if (!resumed) return
            encodingMessage?.text = receiver.encodingSummary
            val counters = receiver.streamCounters()
            currentRates = counters?.let { rateMeter.sample(it, System.nanoTime()) }
            if (counters == null) rateMeter.reset()
            rateOverlay.text = rateText()
            rateOverlay.visibility = if (playing && showRates) View.VISIBLE else View.GONE
            if (overlayKind == "repair" && !repairBusy) repairMessage?.text =
                repairMessageOverride ?: receiver.pictureRepairStatus()?.message ?: "投屏已结束"
            root.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = CinemaUi(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        showRates = preferences.getBoolean("show_realtime_rates", false)
        val mode = runCatching { PictureMode.valueOf(preferences.getString("picture_mode", "FIT")!!) }.getOrDefault(PictureMode.FIT)
        picture = PictureSettings(mode, preferences.getFloat("picture_zoom", 1f),
            preferences.getFloat("picture_x", 0f), preferences.getFloat("picture_y", 0f)).normalized()
        receiver = LegacyReceiver(this, { surface }, { message ->
            if (resumed && !playing) state.text = message
        }, { width, height ->
            if (resumed) {
                val first = !playing
                playing = true
                sourceWidth = width; sourceHeight = height
                video.holder.setFixedSize(width, height)
                applyPicture()
                idle.visibility = View.GONE
                if (first) { closeOverlay(false); root.requestFocus(); rateMeter.reset() }
                rateOverlay.visibility = if (showRates) View.VISIBLE else View.GONE
            }
        }, { message ->
            if (resumed) {
                returnHome()
                state.text = message
                hint.text = "在手机投屏列表中选择「${receiver.name}」"
            }
        })
        buildScreen()
        recovery = ReceiverRecovery(this, receiver, { updateRecovery(it) }, { returnHome() })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    overlay != null -> overlayBack()
                    playing -> showPlayerMenu()
                    else -> finish()
                }
            }
        })
    }

    private fun buildScreen() {
        val compact = resources.configuration.screenHeightDp < 500
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK); isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        videoFrame = FrameLayout(this).apply { clipChildren = true; clipToPadding = true }
        video = SurfaceView(this)
        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { surface = holder.surface }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { surface = holder.surface }
            override fun surfaceDestroyed(holder: SurfaceHolder) { surface = null }
        })
        videoFrame.addView(video, FrameLayout.LayoutParams(-1, -1))
        root.addView(videoFrame, FrameLayout.LayoutParams(-1, -1))
        rateOverlay = ui.text("", 12f, Color.rgb(96, 255, 112)).apply {
            setPadding(ui.dp(5), ui.dp(3), ui.dp(5), ui.dp(3))
            setBackgroundColor(Color.argb(145, 0, 0, 0)); visibility = View.GONE
            isFocusable = false
        }
        root.addView(rateOverlay, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = ui.dp(32); topMargin = ui.dp(26)
        })
        idle = FrameLayout(this)
        idle.addView(ImageView(this).apply {
            setImageResource(R.drawable.cinema_background); scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(-1, -1))
        val top = ui.row()
        top.addView(ui.text("投屏助手", 21f, ui.white, true).apply {
            setCompoundDrawables(ui.icon("cast", 26), null, null, null); compoundDrawablePadding = ui.dp(10)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        network = ui.text("正在检查网络…", 13f, ui.muted).apply {
            setCompoundDrawables(ui.icon("wifi", 18, ui.muted), null, null, null); compoundDrawablePadding = ui.dp(8)
        }
        top.addView(network)
        idle.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            leftMargin = ui.dp(40); rightMargin = ui.dp(40); topMargin = ui.dp(26)
        })
        val hero = ui.column().apply { gravity = Gravity.CENTER }
        state = ui.text("正在准备投屏", if (compact) 16f else 18f, ui.muted).apply { gravity = Gravity.CENTER }
        hero.addView(state)
        deviceName = ui.text(receiver.name, if (compact) 32f else 44f, ui.white, true).apply {
            gravity = Gravity.CENTER; maxLines = 2
        }
        hero.addView(deviceName, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
        hint = ui.text("在手机投屏列表中选择此设备", 15f, ui.muted).apply { gravity = Gravity.CENTER }
        hero.addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        hero.addView(ImageView(this).apply {
            setImageDrawable(ui.icon("cast", if (compact) 68 else 100, ui.white)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ui.dp(if (compact) 76 else 112), ui.dp(if (compact) 76 else 112)).apply { topMargin = ui.dp(if (compact) 12 else 25) })
        idle.addView(hero, FrameLayout.LayoutParams(-1, -1).apply {
            leftMargin = ui.dp(60); rightMargin = ui.dp(60); topMargin = ui.dp(if (compact) 62 else 80); bottomMargin = ui.dp(if (compact) 137 else 153)
        })
        val bottom = ui.column()
        val steps = ui.row()
        listOf("①  连接同一 Wi-Fi", "②  打开手机投屏", "③  选择此设备").forEach { step ->
            steps.addView(ui.text(step, 14f, ui.white).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        bottom.addView(steps, ui.item(30))
        val actions = ui.row()
        homeSettings = ui.button("设备设置", "settings") { showSettings() }
        listOf(homeSettings, ui.button("连接帮助", "help") { showHelp() }, ui.button("退出应用", "power") { finish() })
            .forEachIndexed { index, button -> actions.addView(button, LinearLayout.LayoutParams(0, ui.dp(54), 1f).apply {
                if (index > 0) leftMargin = ui.dp(14)
            }) }
        bottom.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
        bottom.addView(ui.text("WifiScreen  ·  ${BuildConfig.VERSION_NAME}   /   系统投屏 · 无需手机安装应用", 11f, ui.muted)
            .apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(16) })
        idle.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = ui.dp(40); rightMargin = ui.dp(40); bottomMargin = ui.dp(23)
        })
        root.addView(idle, FrameLayout.LayoutParams(-1, -1))
        root.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) applyPicture()
        }
        setContentView(root)
        homeSettings.requestFocus()
    }

    private fun updateRecovery(value: RecoveryState) {
        latestRecovery = value
        if (!resumed) return
        network.text = if (value.address.isEmpty()) "网络未连接" else "局域网已连接 · ${value.address}"
        if (!playing) {
            state.text = value.title
            hint.text = value.detail
        }
        if (overlayKind == "recovery") showRecovery()
    }

    private fun returnHome() {
        playing = false
        rateOverlay.visibility = View.GONE
        currentRates = null; rateMeter.reset()
        dialog?.dismiss(); dialog = null
        closeOverlay(false)
        idle.visibility = View.VISIBLE
        homeSettings.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == openingKey) { openingKey = -1; return true }
        if (playing && overlay == null && event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_DOWN)) {
            openingKey = event.keyCode
            showPlayerMenu()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun closeOverlay(restoreFocus: Boolean = true) {
        if (overlayKind == "codec-test") codecTest?.cancel()
        encodingMessage = null
        repairMessage = null
        overlay?.let { root.removeView(it) }
        overlay = null; overlayKind = ""
        idle.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        idle.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        if (restoreFocus) { if (playing) root.requestFocus() else homeSettings.requestFocus() }
    }

    private fun present(kind: String, view: View, params: FrameLayout.LayoutParams,
                        focus: View, back: () -> Unit = { closeOverlay() }) {
        closeOverlay(false)
        overlay = view; overlayKind = kind; overlayBack = back
        // Only the active panel participates in DPAD focus search.
        idle.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        idle.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        root.addView(view, params)
        focus.requestFocus()
    }

    private fun showPlayerMenu(focusIndex: Int = 1) {
        if (!playing) return
        val sheet = ui.column().apply { setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(14)); background = ui.shape() }
        val heading = ui.row()
        heading.addView(ui.text("手机 · 正在投屏", 13f, ui.muted), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(ui.text("返回  收起菜单", 12f, ui.muted))
        sheet.addView(heading)
        val row = ui.row()
        val mute = ui.button(if (receiver.muted) "恢复声音" else "静音", if (receiver.muted) "volume" else "mute") {
            receiver.mute(!receiver.muted); showPlayerMenu(0)
        }
        val adjust = ui.button("画面调整", "picture") { showPicture() }
        val repair = ui.button("修复画面", "picture") { repairPicture() }
        val info = ui.button("编码 / 信息", "info") { showCodecs() }
        val end = ui.button("结束投屏", "stop") { confirmEnd() }
        listOf(mute, adjust, repair, info, end).forEachIndexed { index, button ->
            row.addView(button, LinearLayout.LayoutParams(0, ui.dp(58), 1f).apply { if (index > 0) leftMargin = ui.dp(10) })
        }
        sheet.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        present("menu", sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = ui.dp(40); rightMargin = ui.dp(40); bottomMargin = ui.dp(26)
        }, listOf(mute, adjust, repair, info, end)[focusIndex])
    }

    private fun repairPicture() {
        if (!playing) return
        val sheet = ui.column().apply { setPadding(ui.dp(24), ui.dp(20), ui.dp(24), ui.dp(20)); background = ui.shape() }
        sheet.addView(ui.text("修复画面", 22f, ui.white, true))
        val message = ui.text("正在保存诊断…", 16f, ui.muted)
        sheet.addView(message, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
        val back = ui.button("返回投屏", "back") { closeOverlay() }
        sheet.addView(back, LinearLayout.LayoutParams(-1, ui.dp(50)).apply { topMargin = ui.dp(18) })
        present("repair", sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = ui.dp(40); rightMargin = ui.dp(40); bottomMargin = ui.dp(26)
        }, back) { showPlayerMenu(2) }
        repairMessage = message
        repairMessageOverride = null
        if (repairBusy) return
        repairBusy = true
        val sessionId = receiver.snapshot().sessionId
        diagnostics.execute {
            val previousAttempts = receiver.pictureRepairStatus()?.attempts
            val result = runCatching { receiver.repairPicture(sessionId) }.getOrElse { "未能开始修复，请稍后重试。" }
            val accepted = receiver.pictureRepairStatus()?.attempts != previousAttempts
            runOnUiThread {
                repairBusy = false
                if (!resumed || isFinishing || isDestroyed) return@runOnUiThread
                if (receiver.snapshot().sessionId != sessionId) return@runOnUiThread
                // Save/cooldown errors must remain visible instead of being overwritten by an old state.
                repairMessageOverride = if (accepted || receiver.pictureRepairStatus()?.active == true) null else result
                if (overlayKind == "repair") repairMessage?.text = result
            }
        }
    }

    private fun confirmEnd() {
        showDialog("结束本次投屏？", "结束后将回到等待连接页面。", "结束投屏", {
            receiver.disconnect(); returnHome(); state.text = "投屏已结束，等待手机重新连接"
        })
    }

    private fun page(title: String, subtitle: String = ""): Pair<FrameLayout, LinearLayout> {
        val frame = FrameLayout(this).apply { setBackgroundColor(ui.ink) }
        val column = ui.column().apply { setPadding(ui.dp(44), ui.dp(28), ui.dp(44), ui.dp(28)) }
        column.addView(ui.text(title, 28f, ui.white, true))
        if (subtitle.isNotEmpty()) column.addView(ui.text(subtitle, 13f, ui.muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(column) }
        frame.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        return frame to column
    }

    private fun showSettings(focusIndex: Int = 0) {
        val (frame, column) = page("设备设置", "让这台投影，以你习惯的方式工作。")
        val nameButton = ui.button("设备名称     ${receiver.name}", "edit") { renameDialog() }
        column.addView(nameButton, ui.item(56, 24))
        val ratesButton = ui.button("显示实时速率     ${if (showRates) "已开启" else "已关闭"}", "info") {
            showRates = !showRates
            preferences.edit().putBoolean("show_realtime_rates", showRates).apply()
            showSettings(1)
        }
        column.addView(ratesButton, ui.item(56, 10))
        val codecButton = ui.button("视频编码     ${receiver.preferredEncoding.label} / 10 秒测试", "settings") { showCodecs() }
        column.addView(codecButton, ui.item(56, 10))
        column.addView(ui.button("默认画面     ${picture.mode.label}", "picture") { showPicture() }, ui.item(56, 10))
        column.addView(ui.button("设备诊断", "report") { showDiagnostics { showSettings() } }, ui.item(56, 10))
        val lower = ui.row()
        lower.addView(ui.button("网络设置", "wifi") { openWifi() }, LinearLayout.LayoutParams(0, ui.dp(52), 1f))
        lower.addView(ui.button("返回首页", "back") { closeOverlay() }, LinearLayout.LayoutParams(0, ui.dp(52), 1f).apply { leftMargin = ui.dp(14) })
        column.addView(lower, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        present("settings", frame, FrameLayout.LayoutParams(-1, -1), when (focusIndex) {
            1 -> ratesButton; 2 -> codecButton; else -> nameButton
        })
    }

    private fun showCodecs(message: String = "", focusEncoding: VideoEncoding = receiver.preferredEncoding) {
        val (frame, column) = page("视频编码", "选择编码后重新投屏。H.265 会为小米 / Redmi HyperOS 启用新版连接协商。")
        val actual = ui.text(receiver.encodingSummary, 17f, ui.gold)
        column.addView(actual, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(18) })
        if (message.isNotEmpty()) column.addView(ui.text(message, 14f, ui.muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        val choices = ui.row()
        var selected: View? = null
        for (encoding in VideoEncoding.values()) {
            val decoder = DeviceCodecs.find(encoding)
            val label = when {
                decoder == null -> "${encoding.label} · 本机不支持"
                encoding == VideoEncoding.H264 -> "H.264 · 兼容优先"
                else -> "H.265 · 节省带宽"
            }
            val button = ui.button((if (receiver.preferredEncoding == encoding) "✓  " else "") + label) {
                val error = receiver.selectEncoding(encoding)
                showCodecs(error ?: "已选择 ${encoding.label}，下次投屏时请求使用。", encoding)
            }.apply {
                isSelected = receiver.preferredEncoding == encoding
                isEnabled = decoder != null; alpha = if (isEnabled) 1f else 0.45f
                ui.style(this)
            }
            if (encoding == focusEncoding && button.isEnabled) selected = button
            choices.addView(button, LinearLayout.LayoutParams(0, ui.dp(58), 1f).apply {
                if (encoding == VideoEncoding.H265) leftMargin = ui.dp(14)
            })
        }
        column.addView(choices, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(18) })
        column.addView(ui.text("连接后以上方实际编码为准。若未切换，请在手机关闭再打开系统投屏，刷新设备列表。", 14f, ui.muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
        val connected = receiver.snapshot().sessionId != 0L
        val test = ui.button(when {
            receiver.codecTestRunning -> "正在结束上次测试…"
            connected -> "10 秒测试 · 请先结束投屏"
            else -> "开始 10 秒测试"
        }, "retry") { startCodecTest() }.apply {
            isEnabled = !connected && !receiver.codecTestRunning
            alpha = if (isEnabled) 1f else 0.45f
        }
        column.addView(test, ui.item(54, 22))
        column.addView(ui.text("两种编码各实测 5 秒，同时检查局域网。测试期间暂停接入新的投屏。", 14f, ui.muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        preferences.getString("codec_test_report", null)?.let {
            column.addView(ui.button("查看上次测试结果", "report") { showCodecResult() }, ui.item(50, 14))
        }
        val lower = ui.row()
        lower.addView(ui.button("连接信息", "info") { showDiagnostics { showCodecs() } }, LinearLayout.LayoutParams(0, ui.dp(50), 1f))
        val back = ui.button("返回", "back") { if (playing) showPlayerMenu(3) else showSettings(2) }
        lower.addView(back, LinearLayout.LayoutParams(0, ui.dp(50), 1f).apply { leftMargin = ui.dp(14) })
        column.addView(lower, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        present("codecs", frame, FrameLayout.LayoutParams(-1, -1), selected ?: back) {
            if (playing) showPlayerMenu(3) else showSettings(2)
        }
        encodingMessage = actual
    }

    private fun startCodecTest() {
        if (!receiver.beginCodecTest()) { showCodecs("请先结束投屏，等待解码器空闲后测试。"); return }
        val benchmark = CodecBenchmark(applicationContext, receiver.address)
        val (frame, column) = page("10 秒编码测试", "请保持网络连接。测试不会自动更改首选编码。")
        val countdown = ui.text("正在准备测试片段…", 30f, ui.gold, true)
        column.addView(countdown, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(36) })
        column.addView(ui.text("H.264  5 秒  →  H.265  5 秒\n同时采样 Wi-Fi / 有线网络与网关响应。", 18f, ui.muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(24) })
        column.addView(ui.text("部分设备切换与释放解码器时需额外等待片刻。", 14f, ui.muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
        val cancel = ui.button("取消测试", "back") { benchmark.cancel(); showCodecs("测试已取消，正在释放解码器。") }
        column.addView(cancel, ui.item(54, 30))
        present("codec-test", frame, FrameLayout.LayoutParams(-1, -1), cancel) {
            benchmark.cancel(); showCodecs("测试已取消，正在释放解码器。")
        }
        codecTest = benchmark
        diagnostics.execute {
            val result = runCatching { benchmark.run { remaining, encoding ->
                runOnUiThread { if (codecTest === benchmark && overlayKind == "codec-test")
                    countdown.text = if (remaining == 0) "采样完成，正在释放解码器…"
                        else if (remaining == 5 && encoding == VideoEncoding.H264) "正在切换到 H.265…"
                        else "剩余 ${remaining} 秒 · 正在测试 ${encoding.label}" }
            } }
            receiver.endCodecTest()
            runOnUiThread {
                if (codecTest !== benchmark) return@runOnUiThread
                codecTest = null
                if (!resumed || isFinishing || isDestroyed) return@runOnUiThread
                if (overlayKind != "codec-test") {
                    if (overlayKind == "codecs") showCodecs("可重新开始测试。")
                    return@runOnUiThread
                }
                if (benchmark.isCancelled) { showCodecs("测试已取消。"); return@runOnUiThread }
                result.onSuccess { measured ->
                    val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(java.util.Date())
                    preferences.edit().putString("codec_test_report", "$timestamp\n\n${measured.description()}")
                        .putString("codec_test_recommendation", measured.advice.recommended?.name).apply()
                    showCodecResult()
                }.onFailure {
                    showCodecs(if (it is java.util.concurrent.CancellationException) "测试已取消。"
                        else "测试未完成：${it.message?.take(120) ?: "无法启动解码器"}")
                }
            }
        }
    }

    private fun showCodecResult() {
        val (frame, column) = page("编码测试结果", "更换手机、网络或投屏清晰度后，建议重新测试。")
        val row = ui.row()
        val recommended = preferences.getString("codec_test_recommendation", null)?.let { VideoEncoding.saved(it) }
        var focus: View? = null
        if (recommended != null) {
            val apply = ui.button("使用推荐 ${recommended.label}", "check") {
                val error = receiver.selectEncoding(recommended)
                showCodecs(error ?: "已选择 ${recommended.label}，下次投屏生效。")
            }
            row.addView(apply, LinearLayout.LayoutParams(0, ui.dp(52), 1f)); focus = apply
        }
        val back = ui.button("返回编码设置", "back") { showCodecs() }
        row.addView(back, LinearLayout.LayoutParams(0, ui.dp(52), 1f).apply { if (focus != null) leftMargin = ui.dp(14) })
        column.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        column.addView(ui.text(preferences.getString("codec_test_report", "尚未测试").orEmpty(), 16f, ui.muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        present("codec-result", frame, FrameLayout.LayoutParams(-1, -1), focus ?: back) { showCodecs() }
    }

    private fun renameDialog() {
        val column = ui.column().apply { setPadding(ui.dp(26), ui.dp(24), ui.dp(26), ui.dp(22)); background = ui.shape() }
        column.addView(ui.text("修改设备名称", 23f, ui.white, true))
        val field = EditText(this).apply {
            setText(receiver.name); setTextColor(ui.white); textSize = 20f; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(48)); setSelectAllOnFocus(true)
            setPadding(ui.dp(14), 0, ui.dp(14), 0); background = ui.shape(ui.ink, 8, ui.gold)
        }
        column.addView(field, ui.item(54, 20))
        val error = ui.text("手机投屏列表将显示此名称", 13f, ui.muted)
        column.addView(error, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        val buttons = ui.row()
        val cancel = ui.button("取消") { dialog?.dismiss() }
        val save = ui.button("保存") {
            val failure = receiver.rename(field.text.toString())
            if (failure != null) { error.text = failure; error.setTextColor(ui.gold); field.requestFocus() }
            else {
                deviceName.text = receiver.name
                dialog?.dismiss()
                showSettings()
                state.text = "设备名称已更新"
                hint.text = "在手机投屏列表中选择「${receiver.name}」"
            }
        }
        buttons.addView(cancel, LinearLayout.LayoutParams(0, ui.dp(50), 1f))
        buttons.addView(save, LinearLayout.LayoutParams(0, ui.dp(50), 1f).apply { leftMargin = ui.dp(12) })
        column.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(22) })
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this).setView(column).create().also {
            it.show(); it.window?.setBackgroundDrawableResource(android.R.color.transparent)
            it.window?.setLayout(ui.dp(440).coerceAtMost(root.width - ui.dp(64)), -2)
            it.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            save.requestFocus()
        }
    }

    private fun showPicture() {
        val sheet = ui.column().apply { setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(22)); background = ui.shape(ui.ink, 0) }
        sheet.addView(ui.text("画面调整", 25f, ui.white, true))
        sheet.addView(ui.text(if (playing) "调整会立即应用到当前画面" else "设置将在下次投屏时生效", 12f, ui.muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        val modes = ui.row()
        val modeButtons = mutableListOf<Button>()
        PictureMode.values().forEachIndexed { index, mode ->
            val button = ui.button(mode.label) {
                picture = picture.copy(mode = mode); savePicture()
                modeButtons.forEachIndexed { i, b -> b.isSelected = PictureMode.values()[i] == picture.mode; ui.style(b) }
            }.apply { textSize = 13f; setPadding(ui.dp(5), 0, ui.dp(5), 0); isSelected = mode == picture.mode; ui.style(this) }
            modeButtons.add(button)
            modes.addView(button, LinearLayout.LayoutParams(0, ui.dp(50), 1f).apply { if (index > 0) leftMargin = ui.dp(7) })
        }
        sheet.addView(modes, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(22) })
        sheet.addView(ui.text("缩放", 14f, ui.muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(18) })
        val zoomRow = ui.row()
        val zoomText = ui.text("${(picture.zoom * 100).roundToInt()}%", 20f).apply { gravity = Gravity.CENTER }
        fun update(options: PictureSettings) {
            picture = options.copy(mode = PictureMode.MANUAL).normalized(); savePicture()
            zoomText.text = "${(picture.zoom * 100).roundToInt()}%"
            modeButtons.forEachIndexed { i, b -> b.isSelected = PictureMode.values()[i] == picture.mode; ui.style(b) }
        }
        zoomRow.addView(ui.button("−") { update(picture.copy(zoom = picture.zoom - .05f)) }, LinearLayout.LayoutParams(ui.dp(58), ui.dp(44)))
        zoomRow.addView(zoomText, LinearLayout.LayoutParams(0, -2, 1f))
        zoomRow.addView(ui.button("+") { update(picture.copy(zoom = picture.zoom + .05f)) }, LinearLayout.LayoutParams(ui.dp(58), ui.dp(44)))
        sheet.addView(zoomRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        sheet.addView(ui.text("画面位置", 14f, ui.muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(16) })
        val pad = ui.column().apply { gravity = Gravity.CENTER }
        pad.addView(ui.button("↑") { update(picture.copy(panY = picture.panY - .1f)) }, LinearLayout.LayoutParams(ui.dp(52), ui.dp(40)))
        val middle = ui.row()
        middle.addView(ui.button("←") { update(picture.copy(panX = picture.panX - .1f)) }, LinearLayout.LayoutParams(ui.dp(52), ui.dp(40)))
        middle.addView(ui.button("居中") { update(picture.copy(panX = 0f, panY = 0f)) }.apply { textSize = 12f; setPadding(0, 0, 0, 0) },
            LinearLayout.LayoutParams(ui.dp(58), ui.dp(40)).apply { leftMargin = ui.dp(6); rightMargin = ui.dp(6) })
        middle.addView(ui.button("→") { update(picture.copy(panX = picture.panX + .1f)) }, LinearLayout.LayoutParams(ui.dp(52), ui.dp(40)))
        pad.addView(middle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(5); bottomMargin = ui.dp(5) })
        pad.addView(ui.button("↓") { update(picture.copy(panY = picture.panY + .1f)) }, LinearLayout.LayoutParams(ui.dp(52), ui.dp(40)))
        sheet.addView(pad, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        sheet.addView(ui.text("铺满屏幕可能裁切画面；缩放或移动会切换为手动调整。", 11f, ui.muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(13) })
        val bottom = ui.row()
        bottom.addView(ui.button("恢复默认", "retry") { picture = PictureSettings(); savePicture(); showPicture() }.apply { textSize = 13f },
            LinearLayout.LayoutParams(0, ui.dp(46), 1f))
        bottom.addView(ui.button("完成") { if (playing) showPlayerMenu() else showSettings() },
            LinearLayout.LayoutParams(0, ui.dp(46), 1f).apply { leftMargin = ui.dp(8) })
        sheet.addView(bottom, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(14) })
        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(ui.ink); addView(sheet) }
        val width = minOf(ui.dp(372), (resources.displayMetrics.widthPixels * .47f).toInt())
        present("picture", scroll, FrameLayout.LayoutParams(width, -1, Gravity.END), modeButtons[picture.mode.ordinal]) {
            if (playing) showPlayerMenu() else showSettings()
        }
    }

    private fun savePicture() {
        preferences.edit().putString("picture_mode", picture.mode.name).putFloat("picture_zoom", picture.zoom)
            .putFloat("picture_x", picture.panX).putFloat("picture_y", picture.panY).apply()
        applyPicture()
    }

    private fun applyPicture() {
        if (!::root.isInitialized || !::video.isInitialized) return
        val bounds = VideoViewport.bounds(root.width, root.height, sourceWidth, sourceHeight, picture) ?: return
        video.layoutParams = FrameLayout.LayoutParams(bounds.width, bounds.height, Gravity.TOP or Gravity.START).apply {
            leftMargin = bounds.left; topMargin = bounds.top
        }
        (rateOverlay.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = maxOf(ui.dp(24), bounds.left + ui.dp(12))
            topMargin = maxOf(ui.dp(24), bounds.top + ui.dp(12))
            rateOverlay.layoutParams = this
        }
    }

    private fun showHelp() {
        val (frame, column) = page("连接帮助", "支持已验证的小米 / Redmi 系统投屏。手机与投影需要处于可互相访问的局域网。")
        listOf("01   手机与投影连接同一 Wi-Fi", "02   手机下拉控制中心，打开「投屏」", "03   在列表中选择「${receiver.name}」").forEach {
            column.addView(ui.text(it, 20f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(26) })
        }
        column.addView(ui.text("找不到设备：关闭并重新打开手机投屏列表。访客网络或路由器的设备隔离可能阻止发现。", 14f, ui.muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(25) })
        val retry = ui.button("重新发布接收设备", "retry") { recovery.retry(); showRecovery() }
        column.addView(retry, ui.item(52, 24))
        val row = ui.row()
        row.addView(ui.button("打开 Wi-Fi 设置", "wifi") { openWifi() }, LinearLayout.LayoutParams(0, ui.dp(50), 1f))
        row.addView(ui.button("返回首页", "back") { closeOverlay() }, LinearLayout.LayoutParams(0, ui.dp(50), 1f).apply { leftMargin = ui.dp(14) })
        column.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
        present("help", frame, FrameLayout.LayoutParams(-1, -1), retry)
    }

    private fun showRecovery() {
        val value = latestRecovery
        val ready = value?.ready == true
        val container = FrameLayout(this).apply { setBackgroundColor(Color.argb(100, 0, 0, 0)) }
        val card = ui.column().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(ui.dp(30), ui.dp(25), ui.dp(30), ui.dp(25)); background = ui.shape() }
        card.addView(ImageView(this).apply { setImageDrawable(ui.icon(if (ready) "check" else "wifi", 42, ui.gold)) },
            LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)))
        card.addView(ui.text(if (ready) "接收服务已恢复" else value?.title ?: "正在恢复连接", 28f, ui.white, true),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(15) })
        card.addView(ui.text(if (ready) "网络连接正常\n接收服务已就绪\n请在手机重新选择「${receiver.name}」" else value?.detail ?: "正在重新发布设备…", 16f, ui.muted).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(22) })
        val home = ui.button("返回首页", "home") { closeOverlay() }
        card.addView(home, ui.item(52, 25))
        card.addView(ui.button("连接帮助", "help") { showHelp() }, ui.item(48, 10))
        container.addView(card, FrameLayout.LayoutParams(minOf(ui.dp(510), root.width - ui.dp(80)), -2, Gravity.CENTER))
        present("recovery", container, FrameLayout.LayoutParams(-1, -1), home)
    }

    private fun showDiagnostics(back: () -> Unit = { showSettings() }) {
        val snapshot = receiver.snapshot()
        val (frame, column) = page("设备诊断", "${Build.MODEL} · Android ${Build.VERSION.RELEASE} · WifiScreen ${BuildConfig.VERSION_NAME}")
        val facts = listOf(
            "连接状态" to if (snapshot.playing) "正在投屏" else if (receiver.isReady) "等待连接" else "正在恢复",
            "接收画面" to if (snapshot.width > 0) "${snapshot.width} × ${snapshot.height}" else "—",
            "视频编码" to receiver.encodingSummary,
            "实时速率" to if (snapshot.playing) rateText() else "—",
            "声音" to when { receiver.muted -> "已静音"; snapshot.audioError.isNotEmpty() -> "音频异常"; snapshot.audioPlaying -> "播放已启动"; snapshot.audioPackets > 0 -> "正在准备"; else -> "未接收音频" },
            "局域网 IP" to (latestRecovery?.address?.ifEmpty { "未连接" } ?: "未连接")
        )
        facts.forEachIndexed { index, (label, value) ->
            val row = ui.row().apply { background = ui.shape(); setPadding(ui.dp(18), 0, ui.dp(18), 0) }
            row.addView(ui.text(label, 16f, ui.muted), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(ui.text(value, 17f, ui.white))
            column.addView(row, ui.item(46, if (index == 0) 22 else 8))
        }
        val row = ui.row()
        val report = ui.button("查看详细报告", "report") { showDetailedReport(back) }
        row.addView(report, LinearLayout.LayoutParams(0, ui.dp(52), 1f))
        row.addView(ui.button("返回", "back") { back() }, LinearLayout.LayoutParams(0, ui.dp(52), 1f).apply { leftMargin = ui.dp(14) })
        column.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        present("diagnostics", frame, FrameLayout.LayoutParams(-1, -1), report, back)
    }

    private fun showDetailedReport(back: () -> Unit) {
        if (reportBusy) return
        reportBusy = true
        diagnostics.execute {
            val report = runCatching { receiver.diagnostics() }.getOrElse { "无法读取报告：${it.message}" }
            runOnUiThread {
                reportBusy = false
                if (!resumed || isFinishing || isDestroyed || overlayKind != "diagnostics") return@runOnUiThread
                val (frame, column) = page("详细诊断", "http://${receiver.address}:${LegacyReceiver.CONTROL_PORT}/diagnostics")
                val returnButton = ui.button("返回设备诊断", "back") { showDiagnostics(back) }
                column.addView(returnButton, ui.item(48, 18))
                column.addView(ui.text(report, 13f, ui.muted).apply { setTextIsSelectable(true) },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
                present("report", frame, FrameLayout.LayoutParams(-1, -1), returnButton) { showDiagnostics(back) }
            }
        }
    }

    private fun showDialog(title: String, body: String, positive: String, action: () -> Unit) {
        val column = ui.column().apply { background = ui.shape(); setPadding(ui.dp(26), ui.dp(24), ui.dp(26), ui.dp(22)) }
        column.addView(ui.text(title, 23f, ui.white, true))
        column.addView(ui.text(body, 15f, ui.muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(16) })
        val row = ui.row()
        val cancel = ui.button("继续投屏") { dialog?.dismiss() }
        row.addView(cancel, LinearLayout.LayoutParams(0, ui.dp(50), 1f))
        row.addView(ui.button(positive) { dialog?.dismiss(); action() }, LinearLayout.LayoutParams(0, ui.dp(50), 1f).apply { leftMargin = ui.dp(12) })
        column.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(24) })
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this).setView(column).create().also {
            it.show(); it.window?.setBackgroundDrawableResource(android.R.color.transparent)
            it.window?.setLayout(minOf(ui.dp(460), root.width - ui.dp(64)), -2)
            cancel.requestFocus()
        }
    }

    private fun openWifi() {
        runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            .onFailure { state.text = "请从投影仪系统设置进入 Wi-Fi 设置"; closeOverlay() }
    }

    private fun rateText(): String {
        val rates = currentRates ?: return "— FPS · — KB/s"
        val megabytes = rates.bytesPerSecond >= 1_000_000
        return String.format(Locale.US, "%.0f FPS · %.2f %s", rates.framesPerSecond,
            rates.bytesPerSecond / if (megabytes) 1_000_000 else 1_000, if (megabytes) "MB/s" else "KB/s")
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        idle.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        recovery.start()
        root.removeCallbacks(refreshRates); refreshRates.run()
    }

    override fun onStop() {
        resumed = false
        recovery.stop(); receiver.stop()
        root.removeCallbacks(refreshRates)
        returnHome()
        super.onStop()
    }

    override fun onPause() {
        // HOME/another activity can pause us well before onStop (or resume without a stop).
        // Cancel now so a quick trip to the launcher cannot save a background measurement.
        codecTest?.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        recovery.stop(); receiver.release(); diagnostics.shutdownNow()
        super.onDestroy()
    }
}
