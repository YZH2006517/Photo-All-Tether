package org.photoall.tether.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The design tokens: Apple's dark system palette (true-black ground, grouped greys, the system
 * label greys) with the system orange as the film's accent; a type scale from large title to
 * caption; and the radii of grouped content.
 */
object Ui {
    /**
     * Light (iOS's grouped light palette) or dark. Set before an activity builds its views
     * ([AdaptiveActivity]); every colour below follows it.
     */
    @Volatile var light = false

    private fun c(dark: Long, light: Long) = (if (this.light) light else dark).toInt()

    val BG get() = c(0xFF000000, 0xFFF2F2F7)
    val SURFACE get() = c(0xFF1C1C1E, 0xFFFFFFFF)      // grouped content
    val SURFACE2 get() = c(0xFF2C2C2E, 0xFFE9E9EE)     // controls on it
    val SURFACE3 get() = c(0xFF3A3A3C, 0xFFD8D8DE)
    val LINE get() = c(0xFF38383A, 0xFFD6D6DA)         // separators
    val TEXT get() = c(0xFFFFFFFF, 0xFF000000)
    val SUB get() = c(0xFF98989F, 0xFF6C6C72)          // secondary label
    val DIM get() = c(0xFF636366, 0xFFAEAEB2)          // tertiary
    val ACCENT get() = c(0xFFFF9F0A, 0xFFF08A00)       // system orange: the film, the shutter
    val ACCENT_DEEP get() = c(0xFF3D2706, 0xFFFFE9C7)
    val RED get() = c(0xFFFF453A, 0xFFFF3B30)
    val GREEN get() = c(0xFF30D158, 0xFF34C759)
    val BLUE get() = c(0xFF0A84FF, 0xFF007AFF)
    val TEAL get() = c(0xFF64D2FF, 0xFF32ADE6)
    val INDIGO get() = c(0xFF5E5CE6, 0xFF5856D6)
    val PURPLE get() = c(0xFFBF5AF2, 0xFFAF52DE)
    val PINK get() = c(0xFFFF375F, 0xFFFF2D55)
    val YELLOW get() = c(0xFFFFD60A, 0xFFFFCC00)
    val GREY get() = c(0xFF8E8E93, 0xFF8E8E93)
    val SCRIM get() = c(0xB3000000, 0x66000000)
    /** The control layer (tab bar, toolbars) over content: slightly see-through. */
    val BAR get() = c(0xEB1C1C1E, 0xEBF9F9FB)
    /** A segmented control's track and its sliding thumb. */
    val SEG_BG get() = c(0xFF242426, 0xFFE4E4E9)
    val SEG_THUMB get() = c(0xFF636366, 0xFFFFFFFF)
    /** The touch highlight, and an unlit track (switch off, slider rail, progress ring). */
    val RIPPLE get() = c(0x1FFFFFFF, 0x1A000000)
    val TRACK get() = c(0xFF39393D, 0xFFE3E3E8)
    val HUD get() = c(0xE62C2C2E, 0xF2FFFFFF)
    /** Words on the accent (a filled button, a chosen chip). */
    val ON_ACCENT get() = c(0xFF000000, 0xFFFFFFFF)

    val mono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    val semibold: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val bold: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    // type scale (sp)
    const val LARGE_TITLE = 32f
    const val TITLE = 22f
    const val HEADLINE = 17f
    const val BODY = 16f
    const val SUBHEAD = 14f
    const val FOOTNOTE = 13f
    const val CAPTION = 11f
}

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())
fun View.dp(v: Int): Int = context.dp(v)

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (stroke > 0) setStroke(stroke, strokeColor)
}

/** A background that answers a touch with a soft highlight. */
fun pressable(context: Context, color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) =
    RippleDrawable(ColorStateList.valueOf(Ui.RIPPLE), rounded(color, radius, stroke, strokeColor), rounded(Color.WHITE, radius))

fun Context.label(text: CharSequence, size: Float = Ui.BODY, color: Int = Ui.TEXT, bold: Boolean = false) = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(color)
    typeface = if (bold) (if (size >= Ui.TITLE) Ui.bold else Ui.semibold) else Typeface.DEFAULT
    includeFontPadding = false
    if (size >= Ui.TITLE) letterSpacing = -0.01f
}

fun TextView.single(): TextView = apply {
    isSingleLine = true
    ellipsize = TextUtils.TruncateAt.END
}

/** A large title, as at the top of an iOS screen. */
fun Context.largeTitle(text: String) = label(text, Ui.LARGE_TITLE, Ui.TEXT, bold = true).apply { typeface = Ui.bold }

/**
 * A pill button: filled accent for the screen's main action, tinted otherwise; an optional glyph
 * before the words. Sinks under the finger and springs back.
 */
fun Context.button(text: String, primary: Boolean = false, danger: Boolean = false, glyph: Glyph? = null, onClick: () -> Unit): View {
    val fg = when {
        primary -> Ui.ON_ACCENT
        danger -> Ui.RED
        else -> Ui.ACCENT
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = pressable(context, if (primary) Ui.ACCENT else Ui.SURFACE2, dp(20).toFloat())
        setPadding(dp(if (glyph != null) 13 else 16), dp(9), dp(16), dp(9))
        minimumHeight = dp(38)
        if (glyph != null) addView(IconView(context, glyph, fg, 18), LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(6) })
        addView(label(text, Ui.SUBHEAD, fg, bold = true).single())
        setOnClickListener { Haptic.select(this); onClick() }
        contentDescription = text
        pressable()
    }
    return box
}

/** A round glyph button (a toolbar's, a card's corner). [filled]: on a grey disc. */
fun Context.iconButton(glyph: Glyph, color: Int = Ui.ACCENT, sizeDp: Int = 40, filled: Boolean = true, description: String? = null,
                       onClick: () -> Unit): IconView = IconView(this, glyph, color, (sizeDp * 0.52f).toInt()).apply {
    val pad = dp((sizeDp - sizeDp * 0.52f).toInt() / 2)
    setPadding(pad, pad, pad, pad)
    layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
    background = if (filled) pressable(context, Ui.SURFACE2, dp(sizeDp).toFloat() / 2) else null
    contentDescription = description
    setOnClickListener { Haptic.select(this); onClick() }
    pressable(0.88f)
}

/** A selectable pill (filters, options): fills with the accent when on. */
class Chip(context: Context, text: String, var onClick: (() -> Unit)? = null, glyph: Glyph? = null) : LinearLayout(context) {
    private val textView = context.label(text, Ui.SUBHEAD, Ui.TEXT, bold = true).single()
    private val icon = glyph?.let { IconView(context, it, Ui.TEXT, 16) }
    var on = false
        set(value) {
            field = value
            val fg = if (value) Ui.ON_ACCENT else Ui.TEXT
            textView.setTextColor(fg)
            icon?.color = fg
            background = pressable(context, if (value) Ui.ACCENT else Ui.SURFACE2, dp(18).toFloat())
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8))
        icon?.let { addView(it, LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) }) }
        addView(textView)
        on = false
        setOnClickListener { Haptic.tick(this); onClick?.invoke() }
        pressable()
    }
}

/**
 * The segmented control: a grey track with a lighter thumb that slides (on a spring) to the
 * chosen segment. Segments are words, or glyphs where the meaning is visual.
 */
class Segmented(context: Context, private val options: List<String>, selected: Int, val glyphs: List<Glyph?>? = null,
                val onSelect: (Int) -> Unit) : FrameLayout(context) {
    constructor(context: Context, options: List<String>, selected: Int, onSelect: (Int) -> Unit) : this(context, options, selected, null, onSelect)

    private val thumb = View(context).apply { background = rounded(Ui.SEG_THUMB, dp(8).toFloat(), if (Ui.light) 1 else 0, 0x1A000000) }
    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val cells = mutableListOf<View>()
    var selected = selected
        private set

    init {
        background = rounded(Ui.SEG_BG, dp(10).toFloat())
        setPadding(dp(2), dp(2), dp(2), dp(2))
        addView(thumb, LayoutParams(0, LayoutParams.MATCH_PARENT))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        options.forEachIndexed { i, text ->
            val glyph = glyphs?.getOrNull(i)
            val words = context.label(text, Ui.FOOTNOTE, Ui.TEXT, bold = true).apply {
                gravity = Gravity.CENTER
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                minimumHeight = dp(32)
                setPadding(dp(4), dp(6), dp(4), dp(6))
                contentDescription = text
                if (glyph != null) addView(IconView(context, glyph, Ui.TEXT, 15), LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(5) })
                addView(words, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            cell.setOnClickListener {
                if (i != this.selected) {
                    Haptic.tick(this)
                    select(i)
                    onSelect(i)
                }
            }
            cells += cell
            row.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    // The row decides the height; the thumb only follows it. (A plain View under AT_MOST would
    // otherwise claim all the room it is offered and the control would fill the page.)
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val inner = (w - paddingLeft - paddingRight).coerceAtLeast(0)
        // on a narrow screen an option that has a glyph drops its words rather than be cut off
        if (cells.isNotEmpty() && inner > 0) {
            val room = inner / cells.size
            cells.forEach { c ->
                val g = c as ViewGroup
                val icon = (0 until g.childCount).map { g.getChildAt(it) }.firstOrNull { it is IconView }
                val words = (0 until g.childCount).map { g.getChildAt(it) }.firstOrNull { it is TextView } as? TextView ?: return@forEach
                if (icon == null) return@forEach
                val need = g.paddingLeft + g.paddingRight + dp(20) + words.paint.measureText(words.text.toString()).toInt()
                words.visibility = if (need > room) View.GONE else View.VISIBLE
                (icon.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = if (need > room) 0 else dp(5)
            }
        }
        row.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val h = row.measuredHeight
        thumb.measure(MeasureSpec.makeMeasureSpec(thumb.layoutParams.width.coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        row.layout(paddingLeft, paddingTop, paddingLeft + row.measuredWidth, paddingTop + row.measuredHeight)
        thumb.layout(paddingLeft, paddingTop, paddingLeft + thumb.measuredWidth, paddingTop + thumb.measuredHeight)
        place(false)
    }

    fun select(i: Int) {
        selected = i
        place(true)
    }

    private fun place(animate: Boolean) {
        val cell = cells.getOrNull(selected) ?: return
        if (cell.width == 0) return
        val lp = thumb.layoutParams
        if (lp.width != cell.width) {
            lp.width = cell.width
            thumb.layoutParams = lp
        }
        val x = cell.left.toFloat()
        if (animate && !Ease.reduced(context)) thumb.animate().translationX(x).setDuration(380).setInterpolator(Ease.spring).start()
        else thumb.translationX = x
        cells.forEachIndexed { k, c ->
            val on = k == selected
            val g = c as ViewGroup
            for (j in 0 until g.childCount) {
                (g.getChildAt(j) as? TextView)?.setTextColor(if (on) Ui.TEXT else Ui.SUB)
                (g.getChildAt(j) as? IconView)?.color = if (on) Ui.TEXT else Ui.SUB
            }
        }
    }
}

/** The iOS switch: a pill that fills green, its white knob sliding over on a spring. */
@SuppressLint("ViewConstructor")
class IosSwitch(context: Context, checked: Boolean, private val tint: Int = Ui.GREEN) : View(context) {
    var checked = checked
        private set
    var onChange: ((Boolean) -> Unit)? = null
    private var pos = if (checked) 1f else 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var anim: ValueAnimator? = null

    init {
        setOnClickListener { toggle() }
        contentDescription = "开关"
    }

    fun toggle() = set(!checked, true)

    fun set(v: Boolean, fromUser: Boolean = false) {
        if (v == checked) return
        checked = v
        if (fromUser) Haptic.toggle(this, v)
        anim?.cancel()
        anim = ValueAnimator.ofFloat(pos, if (v) 1f else 0f).apply {
            duration = 360
            interpolator = Ease.spring
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
        if (fromUser) onChange?.invoke(v)
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(dp(51), dp(31))

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val p = pos.coerceIn(0f, 1f)
        paint.color = blend(Ui.TRACK, tint, p)
        r.set(0f, 0f, w, h)
        canvas.drawRoundRect(r, h / 2, h / 2, paint)
        val knob = h / 2 - dp(2)
        val cx = h / 2 + (w - h) * pos
        paint.color = 0x33000000
        canvas.drawCircle(cx, h / 2 + dp(1), knob, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(cx, h / 2, knob, paint)
    }
}

/**
 * The iOS slider: a thin track filled with the accent up to a white knob with a soft shadow.
 * A tick of feel at the default value; [live] reports while dragging, otherwise on release.
 */
@SuppressLint("ViewConstructor")
class IosSlider(context: Context, private val min: Float, private val max: Float, value: Float, private val step: Float,
                private val default: Float? = null) : View(context) {
    var value = value
        private set
    var onDrag: ((Float) -> Unit)? = null
    var onRelease: ((Float) -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var dragging = false
    private var knobScale = 1f

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(36))

    private fun frac() = ((value - min) / (max - min)).coerceIn(0f, 1f)

    private var downX = 0f
    private var downY = 0f
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    // A finger on the knob takes it at once. Anywhere else the slider waits: a sideways move
    // takes it, an upright one is the page scrolling (the slider lets go and changes nothing),
    // and a tap without moving sets the value where it landed.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pad = dp(14).toFloat()
        val f = ((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                val knobX = pad + (width - 2 * pad) * frac()
                if (kotlin.math.abs(e.x - knobX) <= dp(22)) begin(f)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val dx = kotlin.math.abs(e.x - downX)
                    val dy = kotlin.math.abs(e.y - downY)
                    if (dy > slop && dy > dx) return false        // the page's scroll: let it go
                    if (dx > slop) begin(f) else return true
                }
                update(f)
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    // a tap: jump there
                    if (kotlin.math.abs(e.x - downX) <= slop && kotlin.math.abs(e.y - downY) <= slop) {
                        update(f)
                        onRelease?.invoke(value)
                    }
                    return true
                }
                end()
                onRelease?.invoke(value)
            }
            MotionEvent.ACTION_CANCEL -> if (dragging) { end(); onRelease?.invoke(value) }
        }
        return true
    }

    private fun begin(f: Float) {
        dragging = true
        parent?.requestDisallowInterceptTouchEvent(true)
        ValueAnimator.ofFloat(1f, 1.18f).apply {
            duration = 160
            addUpdateListener { knobScale = it.animatedValue as Float; invalidate() }
            start()
        }
        update(f)
    }

    private fun end() {
        dragging = false
        ValueAnimator.ofFloat(knobScale, 1f).apply {
            duration = 360
            interpolator = Ease.bouncy
            addUpdateListener { knobScale = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun update(f: Float) {
        var v = min + (max - min) * f
        if (step > 0) v = Math.round(v / step) * step
        v = v.coerceIn(min, max)
        if (v == value) return
        val d = default
        if (d != null && (value - d) * (v - d) <= 0f && v != value) Haptic.tick(this)
        value = v
        invalidate()
        onDrag?.invoke(v)
    }

    override fun onDraw(canvas: Canvas) {
        val pad = dp(14).toFloat()
        val cy = height / 2f
        val track = dp(4).toFloat()
        val x = pad + (width - 2 * pad) * frac()
        paint.color = if (Ui.light) 0xFFDCDCE1.toInt() else 0xFF3A3A3C.toInt()
        r.set(pad, cy - track / 2, width - pad, cy + track / 2)
        canvas.drawRoundRect(r, track, track, paint)
        paint.color = Ui.ACCENT
        val d = default
        val from = if (d != null && d > min && d < max) pad + (width - 2 * pad) * ((d - min) / (max - min)) else pad
        r.set(minOf(from, x), cy - track / 2, maxOf(from, x), cy + track / 2)
        canvas.drawRoundRect(r, track, track, paint)
        if (d != null && d > min && d < max) {
            paint.color = 0xFF8E8E93.toInt()
            canvas.drawCircle(from, cy, dp(2).toFloat(), paint)
        }
        val kr = dp(13) * knobScale
        paint.color = if (Ui.light) 0x2E000000 else 0x40000000
        canvas.drawCircle(x, cy + dp(1), kr + if (Ui.light) dp(1) * 0.5f else 0f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(x, cy, kr, paint)
    }
}

/** A section title above grouped content: small, grey, with a little air above. */
fun Context.section(title: String): TextView = label(title, Ui.FOOTNOTE, Ui.SUB, bold = true).apply {
    setPadding(dp(16), dp(24), dp(16), dp(8))
}

/** Grouped content: a rounded block on the black ground. */
fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(Ui.SURFACE, dp(14).toFloat())
    setPadding(dp(16), dp(8), dp(16), dp(8))
}

/** The coloured rounded square with a white glyph that leads a settings row. */
fun Context.glyphTile(glyph: Glyph, color: Int, sizeDp: Int = 30): View = FrameLayout(this).apply {
    background = rounded(color, dp(sizeDp).toFloat() * 0.24f)
    addView(IconView(context, glyph, Color.WHITE, (sizeDp * 0.62f).toInt()),
        FrameLayout.LayoutParams(dp((sizeDp * 0.62f).toInt()), dp((sizeDp * 0.62f).toInt()), Gravity.CENTER))
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/**
 * A row of grouped content: an optional glyph tile, the title (and a short detail under it),
 * and whatever control sits at the end (a switch, a value, a chevron).
 */
fun Context.listRow(title: String, detail: String? = null, glyph: Glyph? = null, tint: Int = Ui.GREY, trailing: View? = null,
                    onClick: (() -> Unit)? = null): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = dp(48)
    setPadding(0, dp(8), 0, dp(8))
    if (glyph != null) addView(glyphTile(glyph, tint), LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(12) })
    val texts = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(title, Ui.BODY))
        if (detail != null) addView(label(detail, Ui.FOOTNOTE, Ui.SUB).apply { setPadding(0, dp(3), 0, 0) })
    }
    addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    if (trailing != null) addView(trailing)
    if (onClick != null) {
        if (trailing == null) addView(IconView(context, Glyph.CHEVRON_RIGHT, Ui.DIM, 16))
        background = pressable(context, Color.TRANSPARENT, dp(10).toFloat())
        setOnClickListener { Haptic.select(this); onClick() }
    }
}

/** A switch row (iOS settings style). */
fun Context.toggleRow(title: String, sub: String?, checked: Boolean, glyph: Glyph? = null, tint: Int = Ui.GREY,
                      onChange: (Boolean) -> Unit): LinearLayout {
    val sw = IosSwitch(this, checked).apply { this.onChange = onChange }
    return listRow(title, sub, glyph, tint, sw).apply { setOnClickListener { sw.toggle() } }
}

/** A titled slider with its value at the end; [onChange] while dragging when [live], else on release. */
fun Context.sliderRow(
    title: String, min: Float, max: Float, value: Float, step: Float = 0f,
    format: (Float) -> String = { "%.2f".format(it) }, live: Boolean = false, default: Float? = null, glyph: Glyph? = null,
    tint: Int = Ui.GREY, indent: Boolean = false,
    onChange: (Float) -> Unit,
): LinearLayout = LinearLayout(this).apply {
    // With a glyph it leads like a list row (the same tile, the same type); [indent] puts a
    // setting that belongs to the row above under that row's title.
    orientation = LinearLayout.VERTICAL
    setPadding(if (indent) dp(42) else 0, dp(8), 0, dp(4))
    val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(32) }
    if (glyph != null) head.addView(glyphTile(glyph, tint), LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(12) })
    val valueText = label(format(value), Ui.SUBHEAD, Ui.ACCENT, bold = true).apply { typeface = Ui.mono }
    head.addView(label(title, if (indent) Ui.SUBHEAD else Ui.BODY, if (indent) Ui.SUB else Ui.TEXT).single(),
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    head.addView(valueText)
    addView(head)
    val bar = IosSlider(context, min, max, value, step, default)
    bar.onDrag = { v -> valueText.text = format(v); if (live) onChange(v) }
    bar.onRelease = { v -> if (!live) onChange(v) }
    addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply { if (glyph != null) marginStart = dp(42) })
}

/** A hairline between rows of grouped content. */
fun Context.separator(inset: Int = 0): View = View(this).apply {
    setBackgroundColor(Ui.LINE)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(inset) }
}

fun Context.hScroll(content: View): HorizontalScrollView = HorizontalScrollView(this).apply {
    isHorizontalScrollBarEnabled = false
    clipToPadding = false
    overScrollMode = View.OVER_SCROLL_NEVER
    addView(content)
}

fun Context.row(vararg views: View, gap: Int = 8): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    views.forEachIndexed { i, v ->
        // a view that brought its own (a spacer's weight) keeps it
        val lp = (v.layoutParams as? LinearLayout.LayoutParams)
            ?: v.layoutParams?.let { LinearLayout.LayoutParams(it.width, it.height) }
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

fun blend(a: Int, b: Int, t: Float): Int {
    val u = t.coerceIn(0f, 1f)
    fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - u) + ((b shr s) and 0xFF) * u).roundToInt() shl s
    return ch(24) or ch(16) or ch(8) or ch(0)
}

@Suppress("unused")
private fun near(a: Float, b: Float) = abs(a - b) < 1e-4f
