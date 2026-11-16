package org.photoall.tether.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

/** Colours and building blocks of the app's screens (plain views, built in code). */
object Ui {
    const val BG = 0xFF0F0E0C.toInt()
    const val SURFACE = 0xFF1A1815.toInt()
    const val SURFACE2 = 0xFF25211C.toInt()
    const val LINE = 0xFF342F28.toInt()
    const val TEXT = 0xFFEFE8DC.toInt()
    const val SUB = 0xFF9B9286.toInt()
    const val DIM = 0xFF6A635A.toInt()
    const val ACCENT = 0xFFE9A23B.toInt()
    const val ACCENT_DEEP = 0xFF3B2A12.toInt()
    const val RED = 0xFFE2574C.toInt()
    const val GREEN = 0xFF7BC47F.toInt()
    const val SCRIM = 0xCC0F0E0C.toInt()

    val mono: Typeface = Typeface.MONOSPACE
}

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())
fun View.dp(v: Int): Int = context.dp(v)

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (stroke > 0) setStroke(stroke, strokeColor)
}

/** A background that ripples when pressed. */
fun pressable(context: Context, color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) =
    RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(color, radius, stroke, strokeColor), rounded(Color.WHITE, radius))

fun Context.label(text: CharSequence, size: Float = 14f, color: Int = Ui.TEXT, bold: Boolean = false) = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(color)
    if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    includeFontPadding = false
}

fun TextView.single(): TextView = apply {
    isSingleLine = true
    ellipsize = TextUtils.TruncateAt.END
}

fun Context.button(text: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
    this.text = text
    textSize = 14f
    gravity = Gravity.CENTER
    isSingleLine = true
    val fg = when {
        primary -> Ui.BG
        danger -> Ui.RED
        else -> Ui.TEXT
    }
    setTextColor(fg)
    typeface = Typeface.create(Typeface.DEFAULT, if (primary) Typeface.BOLD else Typeface.NORMAL)
    background = pressable(context, if (primary) Ui.ACCENT else Ui.SURFACE2, dp(20).toFloat(), if (primary) 0 else 1, Ui.LINE)
    setPadding(dp(16), dp(9), dp(16), dp(9))
    minHeight = dp(38)
    setOnClickListener { onClick() }
}

/** A selectable pill. */
class Chip(context: Context, text: String, var onClick: (() -> Unit)? = null) : TextView(context) {
    var on = false
        set(value) {
            field = value
            setTextColor(if (value) Ui.BG else Ui.TEXT)
            background = pressable(context, if (value) Ui.ACCENT else Ui.SURFACE2, dp(16).toFloat(), if (value) 0 else 1, Ui.LINE)
        }

    init {
        this.text = text
        textSize = 13f
        isSingleLine = true
        gravity = Gravity.CENTER
        setPadding(dp(13), dp(7), dp(13), dp(7))
        on = false
        setOnClickListener { onClick?.invoke() }
    }
}

/** One choice of several, side by side. */
class Segmented(context: Context, private val options: List<String>, selected: Int, val onSelect: (Int) -> Unit) : LinearLayout(context) {
    private val cells = mutableListOf<TextView>()
    var selected = selected
        private set

    init {
        orientation = HORIZONTAL
        background = rounded(Ui.SURFACE2, dp(18).toFloat(), 1, Ui.LINE)
        setPadding(dp(3), dp(3), dp(3), dp(3))
        options.forEachIndexed { i, text ->
            val cell = TextView(context).apply {
                this.text = text
                textSize = 13f
                gravity = Gravity.CENTER
                isSingleLine = true
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener {
                    if (i != this@Segmented.selected) {
                        select(i)
                        onSelect(i)
                    }
                }
            }
            cells += cell
            addView(cell, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        select(selected)
    }

    fun select(i: Int) {
        selected = i
        cells.forEachIndexed { k, cell ->
            cell.setTextColor(if (k == i) Ui.BG else Ui.SUB)
            cell.background = if (k == i) rounded(Ui.ACCENT, dp(15).toFloat()) else null
            cell.typeface = Typeface.create(Typeface.DEFAULT, if (k == i) Typeface.BOLD else Typeface.NORMAL)
        }
    }
}

fun Context.section(title: String): TextView = label(title, 12f, Ui.SUB, bold = true).apply {
    letterSpacing = 0.08f
    setPadding(dp(4), dp(22), dp(4), dp(8))
}

fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(Ui.SURFACE, dp(16).toFloat(), 1, Ui.LINE)
    setPadding(dp(14), dp(10), dp(14), dp(10))
}

/** A label with a switch at the end. */
fun Context.toggleRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 15f))
            if (sub != null) addView(label(sub, 12f, Ui.SUB).apply { setPadding(0, dp(3), 0, 0) })
        }
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val s = Switch(context).apply {
            isChecked = checked
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Ui.ACCENT, Ui.SUB))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Ui.ACCENT_DEEP, Ui.LINE))
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        addView(s)
        setOnClickListener { s.toggle() }
    }

/** A titled slider with its value shown; [onChange] while dragging when [live], else on release. */
fun Context.sliderRow(
    title: String, min: Float, max: Float, value: Float, step: Float = 0f,
    format: (Float) -> String = { "%.2f".format(it) }, live: Boolean = false, onChange: (Float) -> Unit,
): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(0, dp(6), 0, dp(4))
    val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    val valueText = label(format(value), 13f, Ui.ACCENT).apply { typeface = Ui.mono }
    head.addView(label(title, 15f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    head.addView(valueText)
    addView(head)
    val steps = 1000
    fun toValue(p: Int): Float {
        var v = min + (max - min) * p / steps
        if (step > 0) v = (Math.round(v / step) * step)
        return v.coerceIn(min, max)
    }
    val bar = SeekBar(context).apply {
        this.max = steps
        progress = (((value - min) / (max - min)) * steps).roundToInt()
        progressTintList = ColorStateList.valueOf(Ui.ACCENT)
        thumbTintList = ColorStateList.valueOf(Ui.ACCENT)
        progressBackgroundTintList = ColorStateList.valueOf(Ui.LINE)
        setPadding(dp(4), dp(10), dp(4), dp(6))
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                valueText.text = format(toValue(p))
                if (fromUser && live) onChange(toValue(p))
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) { onChange(toValue(s.progress)) }
        })
    }
    addView(bar)
}

fun Context.hScroll(content: View): HorizontalScrollView = HorizontalScrollView(this).apply {
    isHorizontalScrollBarEnabled = false
    clipToPadding = false
    addView(content)
}

fun Context.row(vararg views: View, gap: Int = 8): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    views.forEachIndexed { i, v ->
        // a view that brought its own (a spacer's weight) keeps it
        val lp = (v.layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (i > 0) lp.marginStart = dp(gap)
        addView(v, lp)
    }
}

fun spacer(context: Context): View = View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }

fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
fun frameMatch() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

/** Pads [view] by the system bars (the app draws edge to edge). */
fun Activity.padForSystemBars(view: View, top: Boolean = true, bottom: Boolean = true) {
    view.setOnApplyWindowInsetsListener { v, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, if (top) bars.top else 0, bars.right, if (bottom) bars.bottom else 0)
        } else {
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, if (top) insets.systemWindowInsetTop else 0,
                insets.systemWindowInsetRight, if (bottom) insets.systemWindowInsetBottom else 0)
        }
        insets
    }
    view.requestApplyInsets()
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    else -> "${bytes / 1024} KB"
}
