package com.kanayama.wifiscreen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatButton
import androidx.core.graphics.drawable.DrawableCompat

class CinemaUi(private val context: Context) {
    val ink = Color.rgb(16, 18, 20)
    val panel = Color.rgb(25, 28, 31)
    val white = Color.rgb(246, 242, 233)
    val muted = Color.rgb(181, 177, 169)
    val gold = Color.rgb(242, 189, 102)
    val line = Color.rgb(55, 57, 59)
    fun dp(value: Int) = (context.resources.displayMetrics.density * value).toInt()
    fun text(value: String, size: Float = 16f, color: Int = white, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun shape(fill: Int = panel, radius: Int = 12, border: Int = line, thickness: Int = 1) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat(); setStroke(dp(thickness), border)
    }
    fun icon(kind: String, size: Int = 24, color: Int = white): Drawable {
        val drawable = if (kind == "cast") {
            requireNotNull(AppCompatResources.getDrawable(context, R.drawable.ic_cast)).mutate().also {
                DrawableCompat.setTint(it, color)
            }
        } else CinemaIcon(kind, color)
        return drawable.apply { setBounds(0, 0, dp(size), dp(size)) }
    }
    fun button(label: String, glyph: String? = null, action: () -> Unit): Button = CinemaButton(context).apply {
        id = View.generateViewId()
        text = label; textSize = 16f; isAllCaps = false; isFocusable = true
        gravity = Gravity.CENTER; includeFontPadding = false
        minimumHeight = 0; minHeight = 0; minimumWidth = 0; minWidth = 0
        stateListAnimator = null
        if (android.os.Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        // Reserve equal space on both sides; the icon must not offset the text's center.
        val horizontal = dp(if (glyph == null) 16 else 46)
        setPadding(horizontal, dp(6), horizontal, dp(6))
        iconInset = dp(16)
        tag = glyph
        style(this)
        setOnFocusChangeListener { _, _ -> style(this) }
        setOnClickListener { action() }
    }
    fun style(button: Button) {
        val focused = button.hasFocus()
        button.setTextColor(if (focused) ink else white)
        button.background = shape(if (focused) gold else panel, 10,
            if (focused || button.isSelected) gold else line, if (focused) 2 else 1)
        val glyph = button.tag as? String
        (button as? CinemaButton)?.leadingIcon = glyph?.let { icon(it, 22, if (focused) ink else white) }
        button.invalidate()
    }
    fun item(height: Int = 52, top: Int = 0) = LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(top) }
}

private class CinemaButton(context: Context) : AppCompatButton(context) {
    var leadingIcon: Drawable? = null
    var iconInset = 0
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        leadingIcon?.let {
            val size = it.bounds.width()
            it.setBounds(iconInset, (height - size) / 2, iconInset + size, (height + size) / 2)
            it.draw(canvas)
        }
    }
}

/** Small original vector icons keep controls sharp and allow the focus color to change. */
class CinemaIcon(private val kind: String, color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.65f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun draw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        fun line(x: Float, y: Float, a: Float, b: Float) = canvas.drawLine(x, y, a, b, paint)
        fun path(vararg points: Float) {
            val p = Path(); p.moveTo(points[0], points[1])
            for (i in 2 until points.size step 2) p.lineTo(points[i], points[i + 1])
            canvas.drawPath(p, paint)
        }
        when (kind) {
            "settings" -> {
                canvas.drawCircle(12f, 12f, 7f, paint); canvas.drawCircle(12f, 12f, 2.5f, paint)
                repeat(8) { canvas.save(); canvas.rotate(it * 45f, 12f, 12f); line(12f, 2f, 12f, 5f); canvas.restore() }
            }
            "help", "info" -> {
                canvas.drawCircle(12f, 12f, 9f, paint)
                if (kind == "info") { line(12f, 11f, 12f, 17f); canvas.drawPoint(12f, 7f, paint) }
                else { canvas.drawArc(RectF(9f, 6f, 15f, 12f), 180f, 245f, false, paint); line(12f, 12f, 12f, 14f); canvas.drawPoint(12f, 18f, paint) }
            }
            "power" -> { canvas.drawArc(RectF(3f, 3f, 21f, 21f), -50f, 280f, false, paint); line(12f, 2f, 12f, 11f) }
            "wifi" -> {
                canvas.drawArc(RectF(0f, 4f, 24f, 28f), 225f, 90f, false, paint)
                canvas.drawArc(RectF(5f, 9f, 19f, 23f), 225f, 90f, false, paint)
                canvas.drawArc(RectF(9f, 14f, 15f, 20f), 225f, 90f, false, paint)
                canvas.drawPoint(12f, 20f, paint)
            }
            "mute", "volume" -> {
                path(3f, 9f, 7f, 9f, 12f, 5f, 12f, 19f, 7f, 15f, 3f, 15f, 3f, 9f)
                if (kind == "mute") { line(17f, 9f, 22f, 15f); line(22f, 9f, 17f, 15f) }
                else canvas.drawArc(RectF(9f, 5f, 22f, 19f), -65f, 130f, false, paint)
            }
            "picture" -> { canvas.drawRect(4f, 5f, 20f, 19f, paint); line(8f, 2f, 8f, 22f); line(2f, 15f, 23f, 15f) }
            "stop" -> canvas.drawRoundRect(RectF(5f, 5f, 19f, 19f), 1f, 1f, paint)
            "back" -> { path(9f, 5f, 3f, 11f, 9f, 17f); path(3f, 11f, 15f, 11f, 20f, 16f, 20f, 20f) }
            "check" -> path(5f, 12f, 10f, 17f, 20f, 6f)
            "report" -> { path(5f, 3f, 15f, 3f, 20f, 8f, 20f, 21f, 5f, 21f, 5f, 3f); path(15f, 3f, 15f, 8f, 20f, 8f); line(9f, 12f, 16f, 12f); line(9f, 16f, 16f, 16f) }
            "retry" -> { canvas.drawArc(RectF(4f, 4f, 20f, 20f), 30f, 285f, false, paint); path(14f, 3f, 20f, 4f, 20f, 10f) }
            "home" -> { path(3f, 11f, 12f, 3f, 21f, 11f); path(6f, 9f, 6f, 21f, 10f, 21f, 10f, 15f, 14f, 15f, 14f, 21f, 18f, 21f, 18f, 9f) }
            "edit" -> { path(4f, 16f, 16f, 4f, 20f, 8f, 8f, 20f, 3f, 21f, 4f, 16f); line(13f, 7f, 17f, 11f) }
            else -> canvas.drawCircle(12f, 12f, 8f, paint)
        }
        canvas.restoreToCount(saved)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
