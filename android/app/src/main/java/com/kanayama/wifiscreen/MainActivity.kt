package com.kanayama.wifiscreen

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val ink = Color.rgb(16, 25, 39)
    private val accent = Color.rgb(117, 219, 196)
    private lateinit var root: FrameLayout
    private lateinit var idle: View
    private lateinit var state: TextView
    private lateinit var details: TextView
    private lateinit var video: SurfaceView
    private lateinit var videoFrame: FrameLayout
    private lateinit var rateOverlay: TextView
    private lateinit var receiver: LegacyReceiver
    @Volatile private var surface: Surface? = null
    private val diagnostics = Executors.newSingleThreadExecutor()
    private var reportBusy = false
    private var resumed = false
    private var playing = false
    private val preferences by lazy { getSharedPreferences("display", MODE_PRIVATE) }
    private var showRates = false
    private val rateMeter = StreamRateMeter()
    private val refreshRates = object : Runnable {
        override fun run() {
            if (!resumed || !playing || !showRates) return
            val counters = receiver.streamCounters()
            val rates = counters?.let { rateMeter.sample(it, System.nanoTime()) }
            if (counters == null) rateMeter.reset()
            rateOverlay.text = if (rates == null) "-- FPS · -- KB/s" else {
                val megabytes = rates.bytesPerSecond >= 1_000_000
                String.format(Locale.US, "%.0f FPS · %.2f %s", rates.framesPerSecond,
                    rates.bytesPerSecond / if (megabytes) 1_000_000 else 1_000,
                    if (megabytes) "MB/s" else "KB/s")
            }
            rateOverlay.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        showRates = preferences.getBoolean("show_realtime_rates", false)
        buildScreen()
        receiver = LegacyReceiver(this, { surface }, { message ->
            if (resumed) state.text = message
        }, { width, height ->
            if (resumed) {
                playing = true
                fitVideo(width, height)
                idle.visibility = View.GONE
                updateRateOverlay()
            }
        }, { message ->
            playing = false
            updateRateOverlay()
            idle.visibility = View.VISIBLE
            state.text = message
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (playing) {
                    receiver.disconnect()
                    playing = false
                    updateRateOverlay()
                    idle.visibility = View.VISIBLE
                    state.text = "投屏已结束，等待手机重新连接"
                } else finish()
            }
        })
    }

    private fun buildScreen() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        videoFrame = FrameLayout(this)
        video = SurfaceView(this)
        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { surface = holder.surface }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { surface = holder.surface }
            override fun surfaceDestroyed(holder: SurfaceHolder) { surface = null }
        })
        videoFrame.addView(video, FrameLayout.LayoutParams(-1, -1))
        rateOverlay = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(96, 255, 112))
            setShadowLayer(dp(2).toFloat(), 0f, 0f, Color.BLACK)
            setPadding(dp(4), dp(2), dp(4), dp(2))
            setBackgroundColor(Color.argb(96, 0, 0, 0))
            isFocusable = false
            isClickable = false
            visibility = View.GONE
        }
        videoFrame.addView(rateOverlay, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            marginStart = dp(12); topMargin = dp(12)
        })
        root.addView(videoFrame, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(28), dp(40), dp(28))
        }
        content.addView(text("投屏助手", 30f, Color.WHITE, true))
        content.addView(text("Android 手机系统投屏 · " + BuildConfig.VERSION_NAME, 13f), margin(top = 6))
        val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(columns, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = shape(Color.rgb(27, 42, 61))
        }
        columns.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        info.addView(text("在手机投屏列表中选择", 12f))
        info.addView(text(LegacyReceiver.displayName, 28f, accent, true), margin(top = 8))
        state = text("正在启动接收服务…", 18f, Color.WHITE)
        info.addView(state, margin(top = 24))
        details = text("", 13f)
        info.addView(details, margin(top = 12))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        columns.addView(buttons, LinearLayout.LayoutParams(dp(224), -2).apply { leftMargin = dp(24) })
        val restart = button("重新发布接收设备") { receiver.stop(); receiver.start() }
        buttons.addView(restart, margin(height = 48))
        buttons.addView(button("设备信息 / 诊断") { showDiagnostics() }, margin(top = 12, height = 48))
        buttons.addView(button("打开 Wi-Fi 设置") {
            runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                .onFailure { state.text = "系统没有开放 Wi-Fi 设置入口，请从投影仪设置进入" }
        }, margin(top = 12, height = 48))
        buttons.addView(SwitchCompat(this).apply {
            text = "显示实时速率"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(12), 0, dp(12), 0)
            background = shape(Color.rgb(27, 42, 61))
            isFocusable = true
            val tintStates = arrayOf(intArrayOf(android.R.attr.state_focused),
                intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(tintStates, intArrayOf(ink, accent, Color.rgb(188, 199, 211)))
            trackTintList = ColorStateList(tintStates,
                intArrayOf(Color.argb(100, 16, 25, 39), Color.rgb(57, 110, 103), Color.rgb(84, 103, 122)))
            isChecked = showRates
            setOnFocusChangeListener { _, focus ->
                background = shape(if (focus) accent else Color.rgb(27, 42, 61))
                setTextColor(if (focus) ink else Color.WHITE)
            }
            setOnCheckedChangeListener { _, checked ->
                showRates = checked
                preferences.edit().putBoolean("show_realtime_rates", checked).apply()
                updateRateOverlay()
            }
        }, margin(top = 12, height = 48))
        buttons.addView(button("退出应用") { finish() }, margin(top = 12, height = 48))
        content.addView(text(
            "1. 手机与投影连接同一 Wi-Fi。\n2. 手机下拉控制中心，打开「投屏」，选择 " + LegacyReceiver.displayName + "。\n3. 连接后自动全屏显示；按遥控器返回键结束本次投屏。", 14f), margin(top = 24))
        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(ink); addView(content) }
        idle = scroll
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        restart.requestFocus()
    }

    private fun fitVideo(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || root.width == 0) return
        val scale = minOf(root.width.toFloat() / width, root.height.toFloat() / height)
        videoFrame.layoutParams = FrameLayout.LayoutParams((width * scale).toInt(), (height * scale).toInt(), Gravity.CENTER)
    }

    private fun updateRateOverlay() {
        val visible = resumed && playing && showRates
        if (visible && rateOverlay.visibility == View.VISIBLE) return
        rateOverlay.removeCallbacks(refreshRates)
        rateMeter.reset()
        rateOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) refreshRates.run()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        details.text = Build.MODEL + " · Android " + Build.VERSION.RELEASE +
            "\n局域网 IP：" + DeviceProfile.addresses(this).joinToString().ifEmpty { "未连接" }
        receiver.start()
    }

    private fun showDiagnostics() {
        if (reportBusy) return
        reportBusy = true
        diagnostics.execute {
            val device = runCatching { DeviceProfile.report(this) }.getOrElse { "设备信息：" + it.message }
            runOnUiThread {
                reportBusy = false
                if (isFinishing || isDestroyed || !resumed) return@runOnUiThread
                val view = TextView(this).apply {
                    text = receiver.report() + "\n\n" + device
                    textSize = 13f
                    setPadding(dp(20), dp(12), dp(20), dp(12))
                }
                AlertDialog.Builder(this).setTitle("设备与接收状态")
                    .setView(ScrollView(this).apply { addView(view) })
                    .setPositiveButton("固定诊断地址") { _, _ -> showReportAddress() }
                    .setNegativeButton("返回", null).show()
                    .getButton(android.content.DialogInterface.BUTTON_POSITIVE).requestFocus()
            }
        }
    }

    private fun showReportAddress() {
        val address = receiver.address
        if (address.isEmpty()) { state.text = "请先连接网络"; return }
        AlertDialog.Builder(this).setTitle("固定诊断读取地址")
            .setMessage("http://" + address + ":" + LegacyReceiver.CONTROL_PORT + "/diagnostics" +
                "\n\n只读诊断，无需重复生成链接。应用在前台时可读取，退出应用后关闭。")
            .setPositiveButton("知道了", null).show()
    }

    override fun onStop() {
        resumed = false
        playing = false
        updateRateOverlay()
        receiver.stop()
        idle.visibility = View.VISIBLE
        super.onStop()
    }
    override fun onDestroy() { receiver.release(); diagnostics.shutdownNow(); super.onDestroy() }

    private fun text(value: String, size: Float, color: Int = Color.rgb(167, 185, 204), bold: Boolean = false) =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 14f; isAllCaps = false; isFocusable = true
        setTextColor(Color.WHITE); background = shape(Color.rgb(27, 42, 61))
        setOnFocusChangeListener { _, focus ->
            background = shape(if (focus) accent else Color.rgb(27, 42, 61))
            setTextColor(if (focus) ink else Color.WHITE)
        }
        setOnClickListener { action() }
    }
    private fun shape(color: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(12).toFloat(); setStroke(dp(1), Color.rgb(49, 76, 94))
    }
    private fun margin(top: Int = 0, height: Int = -2) = LinearLayout.LayoutParams(-1, if (height < 0) height else dp(height))
        .apply { topMargin = dp(top) }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
}
