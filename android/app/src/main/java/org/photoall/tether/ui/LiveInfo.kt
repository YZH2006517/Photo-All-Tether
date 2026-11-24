package org.photoall.tether.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.photoall.tether.develop.DevelopQueue
import org.photoall.tether.develop.Prefs
import org.photoall.tether.develop.Reveal
import org.photoall.tether.engine.Films
import java.io.File
import java.util.Locale
import kotlin.math.exp

/**
 * The immersive view's live readout for one new shot ([job]) from the camera, in the picture's
 * place, while it comes over and develops.
 *
 *  - [showFrames] (设置 冲洗动画): the film engine's own render of each step comes up as soon as
 *    the engine has made it (the raw, the negative, the print in the developer, halation, grain…);
 *  - [showInfo] (设置 新片实时信息): over the whole place, liquid glass: the frames (or, before
 *    the first, the picture that was there) seen blurred through a tinted pane with light on it,
 *    and on the glass the shot and how far it has come (a large ring), the step it is at and what
 *    it does, every step as a pill (done, now, to come), and the figures chosen in 设置 (transfer,
 *    develop, GPU load and temperature, memory, the app's memory and CPU, the battery) as panes.
 *
 * The two go together: the step named is the step whose frame is on screen (it moves on past
 * what the engine is still rendering only once that frame has been shown). When the print is
 * ready ([finish]) the last frames play out, the ring closes, and it all fades away over the print.
 * Light or dark with the app's appearance, as the immersive view is.
 */
class LiveInfo(
    ctx: Context,
    val job: DevelopQueue.Job,
    private val showInfo: Boolean,
    private val showFrames: Boolean,
) : FrameLayout(ctx) {

    companion object {
        /** The figures there are (the ids are kept in Prefs.liveItems). */
        val items = listOf(
            "transfer" to "传输速度", "develop" to "冲洗用时", "gpu" to "GPU 占用", "gputemp" to "GPU 温度",
            "memory" to "手机内存", "app" to "App 内存", "cpu" to "App CPU", "battery" to "电池",
        )
        private val glyphs = mapOf(
            "transfer" to Glyph.IMPORT, "develop" to Glyph.TIMER, "gpu" to Glyph.CUBE, "gputemp" to Glyph.THERMO,
            "memory" to Glyph.SDCARD, "app" to Glyph.PHOTO_STACK, "cpu" to Glyph.BOLT, "battery" to Glyph.POWER,
        )
        /** What each step does, in a line. */
        private val about = mapOf(
            "传输" to "从相机取回原片",
            "解码 RAW" to "读出传感器的原始数据",
            "原始画面" to "还没有胶片的画面",
            "负片" to "曝光到彩色负片上，带着橙色片基",
            "相纸显影" to "印放到相纸，染料一层层显出",
            "冲洗" to "冲洗胶片",
            "光晕" to "亮处边缘泛起红晕",
            "颗粒" to "银盐颗粒",
            "柔光滤镜" to "镜前柔光镜，亮处泛起一层光",
            "风格 LUT" to "叠加风格 LUT",
            "全尺寸冲洗" to "按原尺寸完整冲洗",
            "存储" to "存进相册",
        )
        private const val TICK = 350L
    }

    /** Its colours: the app's, on glass. */
    private object C {
        val TEXT get() = Ui.TEXT
        val SUB get() = if (Ui.light) 0xFF4A4A50.toInt() else 0xFFC7C7CC.toInt()
        val ACCENT get() = Ui.ACCENT
        val GREEN get() = Ui.GREEN
        val RED get() = Ui.RED
        /** The glass's own tint over what is under it. */
        val TINT get() = if (Ui.light) 0x40F2F2F7 else 0x47000000
        /** A pane of glass on the glass: a little lighter, its rim catching the light. */
        val PANE get() = if (Ui.light) 0xA6FFFFFF.toInt() else 0x59141416
        val RIM get() = if (Ui.light) 0xE6FFFFFF.toInt() else 0x40FFFFFF
        /** Round the words standing on the glass itself (not on a pane), so they read over any picture. */
        val HALO get() = if (Ui.light) 0xCCFFFFFF.toInt() else 0xB3000000.toInt()
        val LINE get() = if (Ui.light) 0x1F000000 else 0x2EFFFFFF
    }

    /** Nothing more to show (a failure shown, or the shot gone without a print): the host takes it away. */
    var onIdle: (() -> Unit)? = null

    private val shown = Prefs.liveItems(ctx)

    // ---- layers: the ground, the engine's frames (under glass when the readout is up), the glass's
    // tint and sheen, the readout over all of it -------------------------------------------------
    private val ground = View(ctx).apply { setBackgroundColor(Ui.BG) }
    private val back = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val front = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
    // a light blur: the engine's frames stay plain to see through the glass, only softened
    private val blurPx = ctx.dp(5).toFloat()
    /** The blur: the system's where it has one (Android 12 on), else a picture made small and drawn large. */
    private val nativeBlur = android.os.Build.VERSION.SDK_INT >= 31
    private val tint = View(ctx).apply { setBackgroundColor(C.TINT) }
    private val sheen = View(ctx).apply {
        // light on the glass from the top left, and its rim
        background = android.graphics.drawable.LayerDrawable(arrayOf(
            GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
                if (Ui.light) 0x66FFFFFF else 0x29FFFFFF, 0x00FFFFFF, 0x00FFFFFF, if (Ui.light) 0x26FFFFFF else 0x0FFFFFFF)),
            rounded(0, ctx.dp(14).toFloat(), ctx.dp(1), C.RIM),
        ))
    }
    /** The frames alone (no readout): the step's name in a corner, as a print's caption. */
    private val chip = ctx.label("", Ui.CAPTION + 1, Ui.TEXT, bold = true).apply {
        background = rounded(Ui.HUD, ctx.dp(12).toFloat())
        setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(12), ctx.dp(6))
        letterSpacing = 0.04f
        elevation = ctx.dp(4).toFloat()
        alpha = 0f
    }

    // ---- the readout, filling the place ------------------------------------------------------------
    private val content = FrameLayout(ctx)
    private val sideA = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val sideB = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    // head: what this is, the shot, the time so far
    private val headTile = FrameLayout(ctx).apply { background = rounded(C.ACCENT, ctx.dp(14).toFloat()) }
    private val headIcon = IconView(ctx, Glyph.IMPORT, android.graphics.Color.WHITE, 16)
    private val title = text(15f, C.TEXT, bold = true).single()
    private val subtitle = text(12f, C.SUB).single().apply { gravity = Gravity.CENTER }
    private val clock = text(15f, C.TEXT, bold = true).apply { fontFeatureSettings = "tnum" }

    // the step: a large ring with the share done, the step's name and what it does under it
    private val ring = RingProgress(ctx).apply { thickness = ctx.dp(7).toFloat(); color = C.ACCENT }
    private val ringBox = FrameLayout(ctx)
    private val percent = text(34f, C.TEXT, bold = true).apply {
        gravity = Gravity.CENTER; maxLines = 1; fontFeatureSettings = "tnum"
        setAutoSizeTextTypeUniformWithConfiguration(12, 40, 1, TypedValue.COMPLEX_UNIT_SP)
    }
    private val count = text(12f, C.SUB, bold = true).apply {
        gravity = Gravity.CENTER; maxLines = 1; fontFeatureSettings = "tnum"
        setAutoSizeTextTypeUniformWithConfiguration(7, 13, 1, TypedValue.COMPLEX_UNIT_SP)
    }
    private val stepName = text(24f, C.TEXT, bold = true).apply {
        maxLines = 1; gravity = Gravity.CENTER
        setAutoSizeTextTypeUniformWithConfiguration(14, 26, 1, TypedValue.COMPLEX_UNIT_SP)
    }
    private val stepAbout = text(13f, C.SUB).apply { maxLines = 2; gravity = Gravity.CENTER; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val stepTime = text(13f, C.TEXT, bold = true).apply { fontFeatureSettings = "tnum"; gravity = Gravity.CENTER; alpha = 0.85f }
    private val stepBlock = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }

    // every step, as pills on one line: the one now in the middle, the line fading out at both ends
    private val pills = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val strip = object : android.widget.HorizontalScrollView(ctx) {
        override fun onTouchEvent(ev: android.view.MotionEvent?) = false // it follows the work, not the finger
        override fun onInterceptTouchEvent(ev: android.view.MotionEvent?) = false
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            // room either side, so the first and the last can come to the middle too
            pills.setPadding(w / 2, 0, w / 2, 0)
            post { centre(false) }
        }
    }.apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(ctx.dp(72))
        addView(pills, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val stepsLabel = sectionLabel("流程")

    // the figures, as panes
    private val figuresLabel = sectionLabel("实时")
    private val cells = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val footer = text(12f, C.SUB).single()
    private val values = mutableMapOf<String, TextView>()
    private val sparks = mutableMapOf<String, Spark>()

    init {
        isClickable = showInfo // over the picture while the readout is up; the frames alone let touches through
        addView(ground, frameMatch())
        addView(back, frameMatch())
        addView(front, frameMatch())
        if (!showInfo) {
            ground.alpha = 0f // the frames alone: the picture as it was until the first comes
            back.scaleType = ImageView.ScaleType.FIT_CENTER
            front.scaleType = ImageView.ScaleType.FIT_CENTER
            if (showFrames) addView(chip, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START).apply { setMargins(ctx.dp(14), 0, 0, ctx.dp(14)) })
        } else {
            if (nativeBlur) {
                val fx = android.graphics.RenderEffect.createBlurEffect(blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP)
                back.setRenderEffect(fx); front.setRenderEffect(fx)
            }
            addView(tint, frameMatch())
            addView(sheen, frameMatch())
            buildReadout()
            addView(content, frameMatch())
        }
    }

    /** What is under the glass before the engine's first frame: the picture that was on screen. */
    fun backdrop(b: android.graphics.Bitmap?) {
        if (b == null || onScreen != null) return
        back.setImageBitmap(glass(b))
    }

    /** A frame as the glass shows it: as it is (the system blurs it), or small (drawn large, it is blurred). */
    private fun glass(b: android.graphics.Bitmap): android.graphics.Bitmap {
        if (!showInfo || nativeBlur) return b
        val s = 320f / maxOf(b.width, b.height)
        return android.graphics.Bitmap.createScaledBitmap(b, maxOf(1, (b.width * s).toInt()), maxOf(1, (b.height * s).toInt()), true)
    }

    private fun buildReadout() {
        val ctx = context
        headTile.addView(headIcon, LayoutParams(ctx.dp(16), ctx.dp(16), Gravity.CENTER))
        ringBox.addView(ring, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            val m = ctx.dp(9); setMargins(m, m, m, m)
        })
        ringBox.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(percent, LinearLayout.LayoutParams(ctx.dp(90), ctx.dp(40)))
            addView(count, LinearLayout.LayoutParams(ctx.dp(90), ctx.dp(15)).apply { topMargin = ctx.dp(2) })
        }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        // the ring on a round lens of glass; the step's words on a pane under it
        ringBox.background = pane(oval = true)
        stepBlock.background = pane(18)
        stepBlock.setPadding(ctx.dp(18), ctx.dp(10), ctx.dp(18), ctx.dp(10))
        stepBlock.addView(stepName, LinearLayout.LayoutParams(ctx.dp(240), ctx.dp(30)))
        stepBlock.addView(stepAbout, LinearLayout.LayoutParams(ctx.dp(240), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(5) })
        stepBlock.addView(stepTime, LinearLayout.LayoutParams(ctx.dp(240), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(6) })
        content.setPadding(ctx.dp(18), ctx.dp(16), ctx.dp(18), ctx.dp(16))
        buildPills()
    }

    /** The head, centred: a capsule (the glyph, what is happening, the time so far) and the shot under it. */
    private fun head(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val capsule = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pane(20)
            setPadding(context.dp(6), context.dp(6), context.dp(14), context.dp(6))
            addView(headTile.detach(), LinearLayout.LayoutParams(context.dp(28), context.dp(28)))
            addView(title.detach(), lp().apply { marginStart = context.dp(9) })
            addView(View(context).apply { background = rounded(C.LINE, context.dp(1).toFloat()) },
                LinearLayout.LayoutParams(context.dp(1), context.dp(14)).apply { marginStart = context.dp(10); marginEnd = context.dp(10) })
            addView(clock.detach(), lp())
        }
        addView(capsule, lp())
        addView(subtitle.detach(), lp().apply { topMargin = context.dp(7) })
    }

    private fun <T : View> T.detach(): T = apply { (parent as? ViewGroup)?.removeView(this) }
    private fun space(weight: Float) = View(context).apply { layoutParams = LinearLayout.LayoutParams(1, 0, weight) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (showInfo && w > 0 && h > 0) post { arrange(w, h) }
    }

    private var arranged = ""

    /**
     * The readout over the whole place. Tall: the shot at the top, the ring and the step in the
     * middle, the steps and the figures below. Wide: the shot, the ring and the step on the left;
     * the steps and the figures on the right. The ring and the figures' columns to the room there is.
     */
    private fun arrange(w: Int, h: Int) {
        val ctx = context
        val key = "$w×$h"
        if (key == arranged) return
        arranged = key
        val n = items.count { it.first in shown }
        val wide = w > h * 1.05f
        content.removeAllViews()
        sideA.removeAllViews(); sideB.removeAllViews()
        val innerW = w - ctx.dp(36)
        val innerH = h - ctx.dp(32)
        if (!wide) {
            // what the rest takes (the head, the step's pane, the steps' line, headings, air), so
            // the figures fit with the ring at least its least; the ring takes what is left
            val fs = resources.configuration.fontScale
            val fixed = ctx.dp(232) + ((12f + 13f * 3) * fs * resources.displayMetrics.density * 1.3f).toInt()
            val leastRing = ctx.dp(104)
            fun fits(c: Int, small: Boolean): Boolean {
                val rows = (n + c - 1) / c
                return rows * cellHeight(small) + (rows - 1) * ctx.dp(8) + fixed + leastRing <= innerH
            }
            val choices = if (n <= 4) listOf(n.coerceAtLeast(1)) else (3..minOf(4, n)).toList()
            val full = choices.firstOrNull { fits(it, false) }
            compact = full == null
            val cols = full ?: choices.firstOrNull { fits(it, true) } ?: choices.last()
            val rows = if (n == 0) 0 else (n + cols - 1) / cols
            val used = rows * cellHeight(compact) + (rows - 1).coerceAtLeast(0) * ctx.dp(8) + fixed
            val ringSize = (innerH - used).coerceIn(leastRing, minOf(ctx.dp(220), (innerW * 0.58f).toInt()))
            sideA.addView(head(), match())
            sideA.addView(space(1f))
            sideA.addView(ringBox.detach(), LinearLayout.LayoutParams(ringSize, ringSize).apply { gravity = Gravity.CENTER_HORIZONTAL })
            sideA.addView(stepBlock.detach(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ctx.dp(14); gravity = Gravity.CENTER_HORIZONTAL
            })
            sideA.addView(space(1f))
            sideA.addView(stepsLabel.detach(), match())
            sideA.addView(strip.detach(), match().apply { topMargin = ctx.dp(8) })
            sideA.addView(figuresLabel.detach(), match().apply { topMargin = ctx.dp(16) })
            sideA.addView(cells.detach(), match().apply { topMargin = ctx.dp(8) })
            sideA.addView(footer.detach(), match().apply { topMargin = ctx.dp(8) })
            content.addView(sideA, frameMatch())
            buildCells(cols)
        } else {
            val bW = (innerW - ctx.dp(37)) / 2
            // the figures to the room on the right: the fewest columns whose rows fit its height,
            // the full panes if they can, else the compact ones (no line of readings, less air)
            val room = innerH - ctx.dp(118) // the steps' line, the headings, the air between
            fun fits(c: Int, small: Boolean): Boolean {
                val rows = (n + c - 1) / c
                return (bW - (c - 1) * ctx.dp(8)) / c >= ctx.dp(76) && rows * cellHeight(small) + (rows - 1) * ctx.dp(8) <= room
            }
            val choices = (2..maxOf(2, minOf(4, n))).toList()
            val full = choices.firstOrNull { fits(it, false) }
            compact = full == null
            val cols = full ?: choices.firstOrNull { fits(it, true) } ?: choices.last()
            val ringSize = minOf((innerH * 0.5f).toInt(), (innerW * 0.22f).toInt(), ctx.dp(200)).coerceAtLeast(ctx.dp(96))
            sideA.addView(head(), match())
            sideA.addView(space(1f))
            sideA.addView(ringBox.detach(), LinearLayout.LayoutParams(ringSize, ringSize).apply { gravity = Gravity.CENTER_HORIZONTAL })
            sideA.addView(stepBlock.detach(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ctx.dp(12); gravity = Gravity.CENTER_HORIZONTAL
            })
            sideA.addView(space(1f))
            sideB.addView(space(1f))
            sideB.addView(stepsLabel.detach(), match())
            sideB.addView(strip.detach(), match().apply { topMargin = ctx.dp(8) })
            sideB.addView(space(0.7f))
            sideB.addView(figuresLabel.detach(), match())
            sideB.addView(cells.detach(), match().apply { topMargin = ctx.dp(8) })
            sideB.addView(footer.detach(), match().apply { topMargin = ctx.dp(8) })
            sideB.addView(space(1f))
            content.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(sideA, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                addView(View(ctx).apply { setBackgroundColor(C.LINE) }, LinearLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    marginStart = ctx.dp(18); marginEnd = ctx.dp(18); topMargin = ctx.dp(6); bottomMargin = ctx.dp(6)
                })
                addView(sideB, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }, frameMatch())
            buildCells(cols)
        }
        if (!entered) { entered = true; enter() }
    }

    private var entered = false

    /** The glass comes over, the readout's parts after it. */
    private fun enter() {
        if (Ease.reduced(context)) return
        listOf(tint, sheen).forEach { it.alpha = 0f; it.animate().alpha(1f).setDuration(420).start() }
        val parts = (0 until sideA.childCount).map { sideA.getChildAt(it) } + (0 until sideB.childCount).map { sideB.getChildAt(it) }
        parts.filter { it.layoutParams.height != 0 }.forEachIndexed { i, v -> v.rise(60L + i * 45L, 12) }
    }

    private var columns = 0
    /** The figures' panes without their line of readings and with less air (a low place). */
    private var compact = false

    /** A pane's height, about (its name's line grows with the font size). */
    private fun cellHeight(small: Boolean): Int {
        val name = maxOf(context.dp(if (small) 18 else 20), (11f * resources.configuration.fontScale * resources.displayMetrics.density * 1.25f).toInt())
        return if (small) context.dp(13 + 4 + 20 + 16) else context.dp(19 + 7 + 22 + 5 + 12) + name
    }

    /**
     * The figures in rows, symmetric: as many rows as [cols] needs, the figures shared out evenly
     * (the fuller rows first: five as three over two), every pane one width, a shorter row centred.
     */
    /** The panes are narrow (their glyph left out, so the name has the width). */
    private var narrow = false

    private fun buildCells(cols: Int) {
        columns = cols
        val avail = (if (width > height * 1.05f) (width - context.dp(36) - context.dp(37)) / 2 else width - context.dp(36))
        narrow = (avail - (cols - 1) * context.dp(8)) / cols.coerceAtLeast(1) < context.dp(112)
        cells.removeAllViews()
        values.clear(); sparks.clear()
        val ids = items.filter { it.first in shown }
        figuresLabel.visibility = if (ids.isEmpty()) View.GONE else View.VISIBLE
        if (ids.isEmpty()) return
        val rows = (ids.size + cols - 1) / cols
        val base = ids.size / rows
        val extra = ids.size % rows
        val widest = base + if (extra > 0) 1 else 0
        var at = 0
        for (r in 0 until rows) {
            val count = base + if (r < extra) 1 else 0
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            // a shorter row: half the missing room on each side
            val half = (widest - count) / 2f
            if (half > 0f) row.addView(View(context), LinearLayout.LayoutParams(0, 1, half))
            for (k in 0 until count) {
                val (id, name) = ids[at++]
                row.addView(cell(id, name), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = context.dp(4); marginEnd = context.dp(4)
                })
            }
            if (half > 0f) row.addView(View(context), LinearLayout.LayoutParams(0, 1, half))
            cells.addView(row, match().apply { if (r > 0) topMargin = context.dp(8); marginStart = -context.dp(4); marginEnd = -context.dp(4) })
        }
    }

    /** A pane of glass: lighter than the glass, lit along its top, its rim bright above and fading below. */
    private fun pane(radius: Int = 14, oval: Boolean = false): android.graphics.drawable.Drawable = Glass(context, radius, oval)

    /** A figure's pane: its glyph and name, its value (shrinking to fit), a line of the last readings. */
    private fun cell(id: String, name: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = pane(16)
        if (compact) setPadding(context.dp(6), context.dp(7), context.dp(6), context.dp(6))
        else setPadding(context.dp(8), context.dp(10), context.dp(8), context.dp(9))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            // compact or narrow: the name alone, shrinking to the pane rather than be cut
            val bare = compact || narrow
            if (!bare) addView(FrameLayout(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((C.ACCENT and 0x00FFFFFF) or 0x29000000) }
                addView(IconView(context, glyphs[id] ?: Glyph.INFO, C.ACCENT, 11), LayoutParams(context.dp(11), context.dp(11), Gravity.CENTER))
            }, LinearLayout.LayoutParams(context.dp(20), context.dp(20)))
            // one size for every name (a narrow pane's a little smaller), so the panes read as a set
            addView(text(if (bare) 10f else 11f, C.SUB, bold = true).apply {
                text = name; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = if (bare) Gravity.CENTER else Gravity.START or Gravity.CENTER_VERTICAL
            }, if (bare) LinearLayout.LayoutParams(0, context.dp(16), 1f)
                else LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(16)).apply { marginStart = context.dp(5) })
        }, match())
        val v = text(17f, C.TEXT, bold = true).apply {
            text = "—"
            gravity = Gravity.CENTER
            maxLines = 1
            fontFeatureSettings = "tnum"
            setAutoSizeTextTypeUniformWithConfiguration(8, 17, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        values[id] = v
        addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(if (compact) 20 else 22)).apply {
            topMargin = context.dp(if (compact) 4 else 7)
        })
        if (compact) return@apply
        val s = Spark(context, C.ACCENT)
        if (id == "gpu" || id == "transfer" || id == "cpu") sparks[id] = s
        else s.visibility = View.INVISIBLE // a row of panes one height
        addView(s, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(12)).apply { topMargin = context.dp(5) })
    }

    // ---- the steps as pills ---------------------------------------------------------------------

    private var pillPlan: List<String> = emptyList()
    private var pillAt = -2

    private fun buildPills() {
        val plan = job.plan
        if (plan == pillPlan) return
        pillPlan = plan
        pillAt = -2
        pills.removeAllViews()
        plan.forEachIndexed { i, p ->
            pills.addView(text(12f, C.SUB, bold = true).apply {
                text = p
                gravity = Gravity.CENTER
                setPadding(context.dp(13), 0, context.dp(13), 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(30)).apply {
                if (i > 0) marginStart = context.dp(7)
                topMargin = context.dp(6); bottomMargin = context.dp(8) // room for the glow of the one now
            })
        }
    }

    /** The step now to the middle of the line (eased along when it moves on). */
    private fun centre(smooth: Boolean) {
        val at = pillAt.coerceIn(0, (pills.childCount - 1).coerceAtLeast(0))
        val p = pills.getChildAt(at) ?: return
        val x = (p.left + p.width / 2 - strip.width / 2).coerceAtLeast(0)
        if (smooth) strip.smoothScrollTo(x, 0) else strip.scrollTo(x, 0)
    }

    /** Done: tinted with a tick; now: filled; to come: plain. */
    private fun paintPills(at: Int) {
        buildPills()
        if (at == pillAt) return
        val moved = at > pillAt && pillAt >= -1
        pillAt = at
        strip.post { centre(moved) }
        for (i in 0 until pills.childCount) {
            val t = pills.getChildAt(i) as TextView
            val name = pillPlan[i]
            when {
                i < at -> {
                    t.text = "✓ $name"
                    // the app's chosen-but-quiet look (the accent's deep tint), solid so it reads on the glass
                    t.setTextColor(if (Ui.light) 0xFFB35F00.toInt() else C.ACCENT)
                    t.background = rounded(Ui.ACCENT_DEEP, context.dp(15).toFloat(), 1, if (Ui.light) 0x33F08A00 else 0x33FF9F0A)
                    t.elevation = 0f
                }
                i == at -> {
                    t.text = name
                    t.setTextColor(Ui.ON_ACCENT)
                    t.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(lighter(C.ACCENT), C.ACCENT)).apply { cornerRadius = context.dp(15).toFloat() }
                    t.elevation = context.dp(6).toFloat()
                    if (android.os.Build.VERSION.SDK_INT >= 28) { t.outlineSpotShadowColor = C.ACCENT; t.outlineAmbientShadowColor = C.ACCENT }
                    if (moved) t.pop(0.86f)
                }
                else -> {
                    t.text = name
                    t.setTextColor(C.SUB)
                    t.background = pane(15)
                    t.elevation = 0f
                }
            }
        }
    }

    // ---- the engine's frames: each as it comes, one after another --------------------------------

    private val queue = ArrayDeque<Reveal.Frame>()
    private var playing = false
    /** The step whose frame is on screen (null before the first). */
    private var onScreen: String? = null
    private val playerBusy get() = playing || queue.isNotEmpty()

    /** A frame the engine has just rendered for this shot. */
    fun push(f: Reveal.Frame) {
        if (!showFrames || gone) return
        queue.addLast(f)
        if (!playing) next()
    }

    private fun next() {
        val f = queue.removeFirstOrNull()
        if (f == null) { playing = false; maybeFinish(); return }
        playing = true
        val first = onScreen == null
        onScreen = f.label
        if (first) {
            ground.animate().alpha(1f).setDuration(260).start()
            chip.animate().alpha(1f).setDuration(260).start()
        }
        chip.text = f.label
        stepChanged()
        val shownBitmap = glass(f.bitmap)
        front.setImageBitmap(shownBitmap)
        front.alpha = 0f
        front.animate().alpha(1f).setDuration(if (f.quick) 150L else if (first) 360L else 320L).withEndAction {
            back.setImageBitmap(shownBitmap)
            front.alpha = 0f
            // a step's own frame held a moment when more are waiting, so it reads; the developer's run on
            val hold = if (f.quick) 30L else if (queue.isEmpty()) 0L else 420L
            postDelayed({ next() }, hold)
        }.start()
    }

    // ---- the readout's content ------------------------------------------------------------------

    /**
     * The step to name: the one on screen while frames are still playing out, else the job's
     * (the engine working on what comes next, or the steps with no frame: transfer, decode, the
     * full-size develop, storing).
     */
    private fun currentStep(): Int {
        val plan = job.plan
        val shownAt = onScreen?.let { plan.indexOf(it) } ?: -1
        val c = if (playerBusy && shownAt >= 0) shownAt else maxOf(job.step, shownAt)
        return c.coerceIn(0, (plan.size - 1).coerceAtLeast(0))
    }

    private var lastStep = -1
    private var lastStepAt = System.currentTimeMillis()

    private fun stepChanged() {
        if (!showInfo || gone) return
        val c = currentStep()
        if (c == lastStep && job.plan == pillPlan) return
        val changed = c != lastStep
        lastStep = c
        if (changed) lastStepAt = System.currentTimeMillis()
        val name = job.plan.getOrNull(c) ?: ""
        stepName.text = name
        stepAbout.text = about[name] ?: ""
        if (changed) {
            stepName.translationY = context.dp(8).toFloat()
            stepName.alpha = 0f
            stepName.animate().translationY(0f).alpha(1f).setDuration(320).setInterpolator(Ease.out).start()
            stepAbout.alpha = 0f
            stepAbout.animate().alpha(1f).setStartDelay(60).setDuration(260).start()
        }
        paintPills(c)
    }

    private var finishing = false
    private var gone = false
    private var onGone: (() -> Unit)? = null

    /** The print is ready (and under this): the frames play out, the ring closes, it all goes. */
    fun finish(onGone: () -> Unit) {
        if (finishing) return
        finishing = true
        this.onGone = onGone
        maybeFinish()
    }

    private fun maybeFinish() {
        if (!finishing || gone || playerBusy) return
        gone = true
        val total = ((if (job.developEnded > 0) job.developEnded else System.currentTimeMillis()) - job.created) / 1000.0
        if (showInfo) {
            ring.color = C.GREEN
            ring.setProgress(1f)
            percent.text = "100%"
            count.text = "完成"
            headTile.background = rounded(C.GREEN, context.dp(14).toFloat())
            headIcon.glyph = Glyph.CHECK
            title.text = "冲洗完成"
            stepName.text = "成片 · 共 %.1f 秒".format(Locale.US, total)
            stepAbout.text = "已存进相册"
            stepTime.text = ""
            paintPills(pillPlan.size)
            headTile.pop(0.8f)
        }
        chip.text = "成片"
        // a moment on the finished readout; then the glass clears off the last frame (the print as
        // the engine made it) and all of it fades into the print itself
        postDelayed({
            content.animate().alpha(0f).scaleX(1.03f).scaleY(1.03f).setDuration(380).setInterpolator(Ease.out).start()
            tint.animate().alpha(0f).setDuration(460).start()
            sheen.animate().alpha(0f).setDuration(460).start()
            if (showInfo && nativeBlur) android.animation.ValueAnimator.ofFloat(blurPx, 0.1f).apply {
                duration = 460
                interpolator = Ease.out
                addUpdateListener {
                    val r = it.animatedValue as Float
                    back.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP))
                }
                start()
            }
            animate().alpha(0f).setDuration(480).setStartDelay(if (showInfo) 420L else 0L).withEndAction { onGone?.invoke() }.start()
        }, if (showInfo) 800L else 250L)
    }

    private fun failed() {
        if (gone) return
        gone = true
        ring.color = C.RED
        ring.setProgress(1f)
        percent.text = "!"
        count.text = "失败"
        headTile.background = rounded(C.RED, context.dp(14).toFloat())
        headIcon.glyph = Glyph.CLOSE
        title.text = "冲洗失败"
        stepAbout.text = job.error ?: "冲洗失败"
        stepTime.text = ""
        postDelayed({ onIdle?.invoke() }, 2800)
    }

    // ---- sampling: off the main thread (memory takes a few ms to read), shown on it ---------------

    private class Sample(
        val gpu: Int?, val gpuTemp: Float?, val memAvail: Long, val memTotal: Long, val lowMem: Boolean,
        val appPss: Long?, val appNative: Long, val cpu: Float?, val battery: Int?, val batteryTemp: Float?,
    )

    private val main = Handler(Looper.getMainLooper())
    private var sampler: HandlerThread? = null
    private var lastCpuTicks = -1L
    private var lastCpuTime = 0L
    private var lastPssAt = 0L
    private var pss: Long? = null
    private var lastBytes = 0L
    private var lastBytesAt = 0L
    private var speed = 0.0
    private var doneSince = 0L

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val t = HandlerThread("live-info").also { it.start() }
        sampler = t
        val h = Handler(t.looper)
        val tick = object : Runnable {
            override fun run() {
                val s = if (showInfo) runCatching { sample() }.getOrNull() else null
                main.post { if (isAttachedToWindow) show(s) }
                if (sampler != null) h.postDelayed(this, TICK)
            }
        }
        h.post(tick)
    }

    override fun onDetachedFromWindow() {
        sampler?.quitSafely()
        sampler = null
        super.onDetachedFromWindow()
    }

    private fun sample(): Sample {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val now = System.currentTimeMillis()
        // the app's whole footprint: every second and a half (it takes a few ms to read)
        if ("app" in shown && now - lastPssAt > 1500) {
            lastPssAt = now
            runCatching { pss = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss * 1024L }
        }
        return Sample(
            gpu = if ("gpu" in shown) Gpu.busy() else null,
            gpuTemp = if ("gputemp" in shown) Gpu.temperature() else null,
            memAvail = mi.availMem, memTotal = mi.totalMem, lowMem = mi.lowMemory,
            appPss = pss, appNative = Debug.getNativeHeapAllocatedSize(),
            cpu = if ("cpu" in shown) appCpu() else null,
            battery = if ("battery" in shown) batteryLevel() else null,
            batteryTemp = if ("battery" in shown) batteryTemp() else null,
        )
    }

    /** The app's own CPU, as a share of all the cores, from its /proc/self/stat times. */
    private fun appCpu(): Float? = runCatching {
        val f = File("/proc/self/stat").readText().substringAfterLast(')').trim().split(' ')
        val ticks = f[11].toLong() + f[12].toLong() // utime, stime (fields 14 and 15)
        val now = android.os.SystemClock.elapsedRealtime()
        val prev = lastCpuTicks
        val prevAt = lastCpuTime
        lastCpuTicks = ticks; lastCpuTime = now
        if (prev < 0 || now <= prevAt) return@runCatching null
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        ((ticks - prev) / 100f) / ((now - prevAt) / 1000f) / cores * 100f // USER_HZ 100
    }.getOrNull()

    private fun batteryLevel(): Int? = runCatching {
        (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrNull()?.takeIf { it in 0..100 }

    private fun batteryTemp(): Float? = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.div(10f)
    }.getOrNull()

    private fun show(s: Sample?) {
        val now = System.currentTimeMillis()
        val j = job
        if (j.status == DevelopQueue.Status.FAILED) { if (showInfo) failed() else onIdle?.invoke(); return }
        // done, and the host never came with the print (it went elsewhere): away after a while
        if (j.status == DevelopQueue.Status.DONE && !finishing) {
            if (doneSince == 0L) doneSince = now
            if (now - doneSince > 4000) onIdle?.invoke()
        }
        if (!showInfo || gone) return
        stepChanged()
        val c = currentStep()
        val plan = j.plan
        val name = plan.getOrNull(c) ?: ""
        // the head: what is happening, to which shot, on what
        val downloading = j.status == DevelopQueue.Status.DOWNLOADING
        title.text = when (j.status) {
            DevelopQueue.Status.DOWNLOADING -> "正在传输"
            DevelopQueue.Status.DEVELOPING, DevelopQueue.Status.DONE -> "正在冲洗"
            else -> "排队中"
        }
        headIcon.glyph = if (downloading) Glyph.IMPORT else Glyph.WAND
        val r = j.recipe
        subtitle.text = listOfNotNull(
            j.fileName ?: j.title,
            if (r.filmOn) Films.short(r.film) + (if (r.printOn && Films.kind(r.film) != "反转片") " · " + Films.paperShort(r.paper) else "") else null,
        ).joinToString("  ·  ")
        clock.text = "%.1f s".format(Locale.US, (now - j.created) / 1000.0)
        // how far: whole steps done, and this one as far as can be told (the transfer's bytes;
        // otherwise easing on toward its end while it takes)
        val within = when {
            name == "传输" && j.totalBytes > 0 -> j.bytes.toFloat() / j.totalBytes
            else -> {
                val tau = if (name == "全尺寸冲洗") 1.6f else 0.6f
                0.85f * (1f - exp(-(now - lastStepAt) / 1000f / tau))
            }
        }
        val share = ((c + within.coerceIn(0f, 1f)) / plan.size.coerceAtLeast(1)).coerceIn(0f, 0.99f)
        ring.setProgress(share)
        percent.text = "%d%%".format(Locale.US, (share * 100).toInt())
        count.text = "%d / %d".format(Locale.US, c + 1, plan.size)
        stepTime.text = if (name == "传输" && j.totalBytes > 0) "%.1f / %.1f MB".format(Locale.US, j.bytes / 1048576.0, j.totalBytes / 1048576.0)
            else "%.1f 秒".format(Locale.US, (now - lastStepAt) / 1000.0)
        val others = DevelopQueue.jobs.count { !it.finished && it.develop && it !== j }
        footer.text = if (others > 0) "后面还有 $others 张" else ""
        footer.visibility = if (others > 0) View.VISIBLE else View.GONE
        values["transfer"]?.let { transfer(j, it, now) }
        values["develop"]?.text = when {
            j.developStarted > 0 -> "%.1f s".format(Locale.US, ((if (j.developEnded > 0) j.developEnded else now) - j.developStarted) / 1000.0)
            else -> "等待"
        }
        if (s == null) return
        values["gpu"]?.text = s.gpu?.let { "$it%" } ?: "未开放"
        s.gpu?.let { sparks["gpu"]?.add(it / 100f) }
        values["gputemp"]?.text = s.gpuTemp?.let { "%.0f°C".format(Locale.US, it) } ?: "未开放"
        values["memory"]?.text = "%.1f / %.0f GB".format(Locale.US, s.memAvail / 1073741824.0, s.memTotal / 1073741824.0)
        values["memory"]?.setTextColor(if (s.lowMem) C.RED else C.TEXT)
        values["app"]?.text = mb(s.appPss ?: s.appNative)
        values["cpu"]?.text = s.cpu?.let { "%.0f%%".format(Locale.US, it) } ?: "…"
        s.cpu?.let { sparks["cpu"]?.add((it / 100f).coerceIn(0f, 1f)) }
        values["battery"]?.text = listOfNotNull(s.battery?.let { "$it%" }, s.batteryTemp?.let { "%.0f°C".format(Locale.US, it) })
            .joinToString(" · ").ifEmpty { "—" }
    }

    private fun transfer(j: DevelopQueue.Job, v: TextView, now: Long) {
        val total = j.totalBytes
        v.text = when {
            j.transferNote != null -> "已在手机"
            j.transferEnded > 0 -> {
                val secs = (j.transferEnded - j.transferStarted) / 1000.0
                "%.1f MB/s".format(Locale.US, total / 1048576.0 / secs.coerceAtLeast(0.01))
            }
            j.status == DevelopQueue.Status.DOWNLOADING && j.transferStarted > 0 -> {
                // the speed now: over the last ticks, smoothed
                if (lastBytesAt > 0 && now > lastBytesAt && j.bytes >= lastBytes) {
                    val inst = (j.bytes - lastBytes) / 1048576.0 / ((now - lastBytesAt) / 1000.0)
                    speed = if (speed == 0.0) inst else speed * 0.6 + inst * 0.4
                    sparks["transfer"]?.add((speed / 60.0).toFloat().coerceIn(0f, 1f))
                }
                lastBytes = j.bytes; lastBytesAt = now
                "%.1f MB/s".format(Locale.US, speed)
            }
            else -> "等待"
        }
    }

    private fun mb(b: Long) = if (b >= 1024L * 1048576L) "%.2f GB".format(Locale.US, b / 1073741824.0) else "%.0f MB".format(Locale.US, b / 1048576.0)

    // ---- pieces ---------------------------------------------------------------------------------

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        typeface = if (bold) Ui.semibold else android.graphics.Typeface.DEFAULT
    }

    /** A heading, centred between two hairlines that fade out toward the sides. */
    private fun sectionLabel(t: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val fade = { o: GradientDrawable.Orientation -> GradientDrawable(o, intArrayOf(0x00000000 or (C.LINE and 0x00FFFFFF), C.LINE)) }
        addView(View(context).apply { background = fade(GradientDrawable.Orientation.LEFT_RIGHT) }, LinearLayout.LayoutParams(0, context.dp(1), 1f))
        addView(headingText(t), lp().apply { marginStart = context.dp(10); marginEnd = context.dp(10) })
        addView(View(context).apply { background = fade(GradientDrawable.Orientation.RIGHT_LEFT) }, LinearLayout.LayoutParams(0, context.dp(1), 1f))
    }

    private fun headingText(t: String) = text(10.5f, C.SUB, bold = true).apply {
        text = t; letterSpacing = 0.08f
        setTextColor(C.TEXT)
        alpha = 0.85f
        setShadowLayer(context.dp(6).toFloat(), 0f, 0f, C.HALO)
    }
    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun match() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** [c] a little lighter (the top of a filled pill). */
    private fun lighter(c: Int): Int {
        val f = 0.22f
        val r = (c shr 16 and 255); val g = (c shr 8 and 255); val b = (c and 255)
        return (c and -0x1000000) or ((r + ((255 - r) * f)).toInt() shl 16) or ((g + ((255 - g) * f)).toInt() shl 8) or (b + ((255 - b) * f)).toInt()
    }

    /**
     * A pane of liquid glass: its fill, a soft light down from the top, and a rim of light that is
     * bright along the top edge and fades toward the bottom (as glass catches light from above).
     */
    private class Glass(ctx: Context, radiusDp: Int, private val oval: Boolean) : android.graphics.drawable.Drawable() {
        private val d = ctx.resources.displayMetrics.density
        private val r = radiusDp * d
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.PANE }
        private val light = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = maxOf(1f, 0.9f * d) }
        private val box = android.graphics.RectF()
        override fun onBoundsChange(b: android.graphics.Rect) {
            val h = b.height().toFloat()
            light.shader = android.graphics.LinearGradient(0f, b.top.toFloat(), 0f, b.top + h * 0.55f,
                if (Ui.light) 0x59FFFFFF else 0x1FFFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP)
            rim.shader = android.graphics.LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
                intArrayOf(C.RIM, (C.RIM and 0x00FFFFFF) or 0x26000000, (C.RIM and 0x00FFFFFF) or 0x4D000000),
                floatArrayOf(0f, 0.6f, 1f), android.graphics.Shader.TileMode.CLAMP)
        }
        override fun draw(c: Canvas) {
            val inset = rim.strokeWidth / 2
            box.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
            if (oval) {
                c.drawOval(box, fill); c.drawOval(box, light); c.drawOval(box, rim)
            } else {
                val rr = minOf(r, box.height() / 2)
                c.drawRoundRect(box, rr, rr, fill); c.drawRoundRect(box, rr, rr, light); c.drawRoundRect(box, rr, rr, rim)
            }
        }
        override fun getOutline(outline: android.graphics.Outline) {
            if (oval) outline.setOval(bounds) else outline.setRoundRect(bounds, minOf(r, bounds.height() / 2f))
        }
        override fun setAlpha(alpha: Int) { fill.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { fill.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** The last readings as a line (0 to 1), its area faintly filled. */
    private class Spark(ctx: Context, private val color: Int) : View(ctx) {
        private val points = ArrayDeque<Float>()
        private val d = ctx.resources.displayMetrics.density
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; strokeJoin = Paint.Join.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val area = Path()
        fun add(v: Float) {
            points.addLast(v)
            while (points.size > 30) points.removeFirst()
            invalidate()
        }
        override fun onDraw(c: Canvas) {
            if (points.size < 2) return
            path.reset(); area.reset()
            val step = width / 29f
            val x0 = width - step * (points.size - 1)
            points.forEachIndexed { i, v ->
                val x = x0 + step * i
                val y = height - 1f - v * (height - 2f)
                if (i == 0) { path.moveTo(x, y); area.moveTo(x, height.toFloat()); area.lineTo(x, y) } else { path.lineTo(x, y); area.lineTo(x, y) }
            }
            area.lineTo(width.toFloat(), height.toFloat()); area.close()
            fill.color = (color and 0x00FFFFFF) or 0x2E000000
            c.drawPath(area, fill)
            line.color = color
            c.drawPath(path, line)
        }
    }

    /**
     * The GPU's load and temperature where the system lets an app read them (Adreno's kgsl
     * nodes; Mali's utilisation); null where it does not.
     */
    private object Gpu {
        private val busyFiles = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
            "/sys/kernel/gpu/gpu_busy", "/sys/class/misc/mali0/device/utilization",
        )
        private val tempFiles = listOf("/sys/class/kgsl/kgsl-3d0/temp", "/sys/kernel/gpu/gpu_tmu")
        @Volatile private var busyAt: String? = null
        @Volatile private var tempAt: String? = null
        @Volatile private var noBusy = false
        @Volatile private var noTemp = false

        fun busy(): Int? {
            if (noBusy) return null
            val f = busyAt ?: busyFiles.firstOrNull { read(it) != null }?.also { busyAt = it } ?: run { noBusy = true; return null }
            return read(f)?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }?.coerceIn(0, 100)
        }

        fun temperature(): Float? {
            if (noTemp) return null
            val f = tempAt ?: tempFiles.firstOrNull { read(it) != null }?.also { tempAt = it } ?: run { noTemp = true; return null }
            val v = read(f)?.trim()?.toFloatOrNull() ?: return null
            return if (v > 1000f) v / 1000f else v // millidegrees on most
        }

        private fun read(path: String): String? = runCatching { File(path).readText().takeIf { it.isNotBlank() } }.getOrNull()
    }
}
