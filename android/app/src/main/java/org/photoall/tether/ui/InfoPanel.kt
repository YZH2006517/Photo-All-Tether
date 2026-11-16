package org.photoall.tether.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.photoall.tether.camera.CameraHub
import org.photoall.tether.develop.DevelopQueue
import org.photoall.tether.develop.Library
import org.photoall.tether.develop.Roll
import org.photoall.tether.engine.Films
import org.photoall.tether.engine.Recipe
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The column beside a picture in landscape immersive view: the camera (its light,
 * name, battery, card); the roll (the stock and its 36 frames as a strip that fills); this
 * picture (aperture, shutter, ISO in three cells, the lens, the recipe, how long it took); and
 * at the foot the darkroom (a ring while it works). Always dark, as the view around it is.
 */
@SuppressLint("ViewConstructor")
class InfoPanel(context: Context, onClose: () -> Unit) : FrameLayout(context) {
    private object C {
        const val TEXT = 0xFFFFFFFF.toInt()
        const val SUB = 0xFF8E8E93.toInt()
        const val DIM = 0xFF48484A.toInt()
        const val LINE = 0x1AFFFFFF
        const val CELL = 0xFF151517.toInt()
        const val ACCENT = 0xFFFF9F0A.toInt()
        const val GREEN = 0xFF30D158.toInt()
        const val RED = 0xFFFF453A.toInt()
    }

    private val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val powerRow = LinearLayout(context)

    private val camDot = PulseDot(context)
    private val camName = text(14f, C.TEXT, bold = true).single()
    private val battery = BatteryView(context).apply { onDark = true }
    private val batteryText = text(12f, C.SUB, bold = true).apply { typeface = Ui.mono }
    private val cardText = text(12f, C.SUB, bold = true).apply { typeface = Ui.mono }
    private val cardIcon = IconView(context, Glyph.SDCARD, C.SUB, 13)

    // a long stock name ("Ultramax 400", "VISION3 500T") shrinks to fit rather than be cut
    private val film = text(13f, C.ACCENT, bold = true).apply {
        isSingleLine = true
        addOnLayoutChangeListener { v, l, _, r, _, ol, _, or_, _ -> if (r - l != or_ - ol) fit(v as TextView) }
    }

    /** Steps the size down from 13 sp to 9 sp until the words fit the width they have. */
    private fun fit(t: TextView) {
        val room = t.width - t.paddingLeft - t.paddingRight
        if (room <= 0) return
        var size = 13f
        t.textSize = size
        while (size > 9f && t.paint.measureText(t.text.toString()) > room) {
            size -= 0.5f
            t.textSize = size
        }
    }
    private val frames = text(12f, C.SUB, bold = true).apply { fontFeatureSettings = "tnum" }
    private val strip = FrameStrip(context)

    private val frameTitle = sectionLabel("本张")
    private val cells = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val lens = text(12f, C.SUB).single()
    private val recipe = text(12.5f, C.TEXT, bold = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val took = text(12f, C.SUB).single()

    private val ring = RingProgress(context).apply { thickness = dp(3).toFloat() }
    private val ringIcon = IconView(context, Glyph.WAND, C.ACCENT, 14)
    private val work = text(13f, C.TEXT, bold = true).single()
    private val workSub = text(11f, C.SUB, bold = true).single()

    init {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF0F0F11.toInt(), 0xFF050506.toInt()))
        val edge = View(context).apply { setBackgroundColor(C.LINE) }
        addView(edge, LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; isFillViewport = true; overScrollMode = OVER_SCROLL_NEVER }
        col.setPadding(dp(14), dp(12), dp(12), dp(10))
        scroll.addView(col)
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // the camera (its light and name) beside the way out; battery and card under it when there
        val top = row(camDot.also { it.layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)) }, camName, gap = 6)
        top.addView(context.iconButton(Glyph.CLOSE, C.SUB, 30, description = "退出沉浸") { onClose() }.apply {
            background = pressable(context, 0xFF1C1C1E.toInt(), dp(15).toFloat())
        }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(6) })
        col.addView(top, match())
        val power = row(battery, batteryText, gap = 5)
        val card = row(cardIcon.also { it.layoutParams = LinearLayout.LayoutParams(dp(13), dp(13)) }, cardText, gap = 4)
        col.addView(powerRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(power)
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(card)
        }, match().apply { topMargin = dp(7) })

        // the roll: the stock and the count on one line, the strip of frames under it
        col.addView(rule())
        col.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(IconView(context, Glyph.ROLL, C.ACCENT, 14), LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(5) })
            addView(film, LinearLayout.LayoutParams(0, dp(18), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
            addView(frames, lp().apply { marginStart = dp(6) })
        }, match())
        col.addView(strip, match().apply { topMargin = dp(8) })

        // this picture
        col.addView(rule())
        col.addView(frameTitle)
        col.addView(cells, match().apply { topMargin = dp(7) })
        col.addView(recipe, match().apply { topMargin = dp(8) })
        col.addView(lens, match().apply { topMargin = dp(3) })
        col.addView(took, match().apply { topMargin = dp(2) })

        // the darkroom, at the foot
        col.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f).apply { topMargin = dp(8) })
        val ringBox = FrameLayout(context)
        ringBox.addView(ring, LayoutParams(dp(30), dp(30)))
        ringBox.addView(ringIcon, LayoutParams(dp(14), dp(14), Gravity.CENTER))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(work)
            addView(workSub, lp().apply { topMargin = dp(2) })
        }
        col.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(C.CELL); cornerRadius = dp(16).toFloat() }
            setPadding(dp(10), dp(9), dp(10), dp(9))
            addView(ringBox, LinearLayout.LayoutParams(dp(30), dp(30)))
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        }, match())
    }


    /** Everything again: the camera and roll as they are now, [entry] as the picture shown. */
    fun refresh(entry: Library.Entry?, index: Int, count: Int) {
        // camera
        val st = CameraHub.state
        val connected = st == CameraHub.State.CONNECTED
        camDot.color = when (st) {
            CameraHub.State.CONNECTED -> C.GREEN
            CameraHub.State.CONNECTING, CameraHub.State.WAITING_PERMISSION -> C.ACCENT
            CameraHub.State.ERROR -> C.RED
            else -> C.DIM
        }
        camDot.live = connected
        camName.text = CameraHub.device?.model?.replace('_', ' ')?.takeIf { connected } ?: "未连接"
        camName.setTextColor(if (connected) C.TEXT else C.SUB)
        val level = CameraHub.settings.battery?.takeIf { connected }
        battery.level = level
        battery.visibility = if (level != null) View.VISIBLE else View.GONE
        batteryText.text = level?.let { "$it%" } ?: ""
        val free = if (connected) CameraHub.storages.sumOf { it.freeBytes } else 0L
        cardText.text = if (free > 0) formatBytes(free) else ""
        cardIcon.visibility = if (free > 0) View.VISIBLE else View.GONE
        powerRow.visibility = if (level != null || free > 0) View.VISIBLE else View.GONE

        // roll
        val loaded = Roll.film(context)
        val n = Roll.frames(context)
        film.text = if (loaded >= 0) Films.short(loaded) else "未装片"
        fit(film)
        frames.text = "$n/${Roll.FRAMES}"
        strip.set(n, Roll.FRAMES)

        // this picture
        frameTitle.text = if (count > 0) "本张 · ${index + 1}/$count" else "本张"
        cells.removeAllViews()
        val m = entry?.meta ?: emptyMap()
        cell(Glyph.APERTURE, m["aperture"]?.toFloatOrNull()?.takeIf { it > 0 }?.let { "f/" + "%.1f".format(it).removeSuffix(".0") })
        cell(Glyph.TIMER, m["shutter"]?.toDoubleOrNull()?.takeIf { it > 0 }?.let { if (it >= 0.3) "%.1f″".format(it).replace(".0″", "″") else "1/${Math.round(1 / it)}" })
        cell(Glyph.SUN, m["iso"]?.toFloatOrNull()?.takeIf { it > 0 }?.let { it.toInt().toString() })
        val r = entry?.recipe?.let { runCatching { Recipe.fromJson(it) }.getOrNull() }
        recipe.text = r?.summary() ?: (entry?.name ?: "—")
        recipe.setTextColor(if (r != null) C.ACCENT else C.SUB)
        // the focal length and how long the print took; when it was taken under that
        val seconds = entry?.note?.let { Regex("总用时\\t([0-9.]+)").find(it)?.groupValues?.get(1) }
        val shot = entry?.shotTime?.takeIf { it > 0 }?.let { SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(it)) }
        lens.text = listOfNotNull(
            m["focal"]?.toDoubleOrNull()?.takeIf { it > 0 }?.let { "%.0fmm".format(it) },
            seconds?.let { "冲洗 ${it}s" },
        ).joinToString(" · ")
        lens.visibility = if (lens.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        took.text = shot?.let { "拍于 $it" } ?: ""
        took.visibility = if (shot != null) View.VISIBLE else View.GONE

        // darkroom
        val active = DevelopQueue.jobs.firstOrNull { !it.finished }
        val waiting = DevelopQueue.active
        if (active != null) {
            ring.indeterminate = active.status == DevelopQueue.Status.DEVELOPING || active.status == DevelopQueue.Status.WAITING
            ring.setProgress(active.progress)
            ring.color = C.ACCENT
            ringIcon.glyph = if (active.status == DevelopQueue.Status.DOWNLOADING) Glyph.IMPORT else Glyph.WAND
            ringIcon.color = C.ACCENT
            work.text = "${active.status.text} ${active.title}"
            workSub.text = if (waiting > 1) "还有 ${waiting - 1} 张" else active.detail
        } else {
            ring.indeterminate = false
            ring.setProgress(1f)
            ring.color = C.GREEN
            ringIcon.glyph = Glyph.CHECK
            ringIcon.color = C.GREEN
            work.text = "暗房空闲"
            val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
            workSub.text = "今日 ${Library.prints().count { it.time >= today }} 张"
        }
    }

    // ---- pieces -------------------------------------------------------------------------------

    private fun cell(glyph: Glyph, value: String?) {
        val c = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply { setColor(C.CELL); cornerRadius = dp(12).toFloat() }
            setPadding(dp(1), dp(6), dp(1), dp(6))
            addView(IconView(context, glyph, if (value != null) C.ACCENT else C.DIM, 13), LinearLayout.LayoutParams(dp(13), dp(13)))
            // the figure shrinks to its cell rather than be cut ("1/8000", "12800")
            addView(text(13f, if (value != null) C.TEXT else C.DIM, bold = true).apply {
                this.text = value ?: "—"
                fontFeatureSettings = "tnum"
                gravity = Gravity.CENTER
                isSingleLine = true
                setAutoSizeTextTypeUniformWithConfiguration(8, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(17)).apply { topMargin = dp(4) })
        }
        cells.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (cells.childCount > 0) marginStart = dp(5)
        })
    }

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        typeface = if (bold) Ui.semibold else Typeface.DEFAULT
    }

    private fun sectionLabel(t: String) = text(10.5f, C.SUB, bold = true).apply { text = t; letterSpacing = 0.08f }

    private fun rule() = View(context).apply {
        setBackgroundColor(C.LINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(12); bottomMargin = dp(11) }
    }

    private fun row(vararg views: View, gap: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { i, v ->
            val p = (v.layoutParams as? LinearLayout.LayoutParams)
                ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (i > 0) p.marginStart = dp(gap)
            if (v is TextView && v.ellipsize != null) { p.width = 0; p.weight = 1f }
            addView(v, p)
        }
    }

    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun match() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}

/**
 * A roll's frames as a strip: three rows of twelve, the exposed ones lit in the accent, the
 * last one exposed brightest; the rest outlined. A newly exposed frame lights up.
 */
class FrameStrip(context: Context) : View(context) {
    private var exposed = 0
    private var total = 36
    private var glow = 1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    fun set(n: Int, of: Int) {
        val grew = n > exposed && exposed > 0
        exposed = n
        total = of.coerceAtLeast(1)
        if (grew && !Ease.reduced(context)) {
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900
                interpolator = Ease.out
                addUpdateListener { glow = it.animatedValue as Float; invalidate() }
                start()
            }
        } else invalidate()
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(3 * 6 + 2 * 3))

    override fun onDraw(canvas: Canvas) {
        val perRow = 12
        val rows = (total + perRow - 1) / perRow
        val gap = dp(3).toFloat()
        val cw = (width - gap * (perRow - 1)) / perRow
        val ch = dp(6).toFloat()
        val rad = context.dp(1.5f).toFloat()
        for (i in 0 until total) {
            val row = i / perRow
            val k = i % perRow
            if (row >= rows) break
            val x = k * (cw + gap)
            val y = row * (ch + dp(3))
            r.set(x, y, x + cw, y + ch)
            when {
                i < exposed - 1 -> {
                    paint.style = Paint.Style.FILL
                    paint.color = 0xB3FF9F0A.toInt()
                    canvas.drawRoundRect(r, rad, rad, paint)
                }
                i == exposed - 1 -> {
                    paint.style = Paint.Style.FILL
                    paint.color = blend(0xFFFFFFFF.toInt(), 0xFFFF9F0A.toInt(), glow)
                    canvas.drawRoundRect(r, rad, rad, paint)
                }
                else -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dp(1).toFloat()
                    paint.color = 0x33FFFFFF
                    r.inset(0.5f, 0.5f)
                    canvas.drawRoundRect(r, rad, rad, paint)
                }
            }
        }
    }
}
