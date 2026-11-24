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
import org.photoall.tether.engine.LutCatalog
import org.photoall.tether.engine.Films
import org.photoall.tether.engine.Recipe
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * What is going on, beside the picture in the immersive view: a column on its right in
 * landscape, a two-column card under it in portrait ([wideCard]). The camera (its light,
 * name, battery, card); the film and the paper this picture was developed on (and its look);
 * the picture (aperture, shutter, ISO in three cells, the lens, how long it took); and at the
 * foot the darkroom (a ring while it works). Light or dark with the app's appearance.
 */
@SuppressLint("ViewConstructor")
class InfoPanel(context: Context, private val wideCard: Boolean = false, onClose: () -> Unit) : FrameLayout(context) {
    /** Its colours, following the app's appearance (light or dark), as the immersive view does. */
    private object C {
        val TEXT get() = Ui.TEXT
        val SUB get() = Ui.SUB
        val DIM get() = if (Ui.light) 0xFFC7C7CC.toInt() else 0xFF48484A.toInt()
        val LINE get() = if (Ui.light) 0x1A000000 else 0x1AFFFFFF
        val CELL get() = if (Ui.light) 0xFFF2F2F7.toInt() else 0xFF151517.toInt()
        val ACCENT get() = Ui.ACCENT
        val GREEN get() = Ui.GREEN
        val RED get() = Ui.RED
    }

    private val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val powerRow = LinearLayout(context)

    private val camDot = PulseDot(context)
    private val camName = text(14f, C.TEXT, bold = true).single()
    private val battery = BatteryView(context).apply { onDark = !Ui.light }
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
    /** The paper (or how it was scanned) under the film's name. */
    private val paper = text(12f, C.SUB, bold = true).single()

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
        // a rounded card, lit a little from the top, with a hairline rim
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            if (Ui.light) intArrayOf(0xFFFFFFFF.toInt(), 0xFFF7F7F9.toInt()) else intArrayOf(0xFF141416.toInt(), 0xFF0A0A0B.toInt())).apply {
            cornerRadius = dp(20).toFloat()
            setStroke(1, C.LINE)
        }
        clipToOutline = true

        // the pieces, the same in both shapes
        val top = row(camDot.also { it.layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)) }, camName, gap = 6)
        top.addView(context.iconButton(Glyph.CLOSE, C.SUB, 30, description = "退出沉浸") { onClose() }.apply {
            background = pressable(context, Ui.SURFACE2, dp(15).toFloat())
        }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(6) })
        val power = row(battery, batteryText, gap = 5)
        val card = row(cardIcon.also { it.layoutParams = LinearLayout.LayoutParams(dp(13), dp(13)) }, cardText, gap = 4)
        // a narrow column (a large font): the card's free space takes what is left of the line and
        // shrinks to fit it rather than be cut ("55.3 GB")
        cardText.apply {
            isSingleLine = true
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setAutoSizeTextTypeUniformWithConfiguration(8, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            layoutParams = LinearLayout.LayoutParams(0, dp(15), 1f).apply { marginStart = dp(4) }
        }
        card.gravity = Gravity.CENTER_VERTICAL or Gravity.END
        powerRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(power)
            addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        }
        // what this picture was developed on: the film, the paper under it
        val roll = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(IconView(context, Glyph.ROLL, C.ACCENT, 14), LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(5) })
                addView(film, LinearLayout.LayoutParams(0, dp(18), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
            })
            addView(paper, lp().apply { topMargin = dp(3); marginStart = dp(19) })
        }
        val ringBox = FrameLayout(context)
        ringBox.addView(ring, LayoutParams(dp(30), dp(30)))
        ringBox.addView(ringIcon, LayoutParams(dp(14), dp(14), Gravity.CENTER))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(work)
            addView(workSub, lp().apply { topMargin = dp(2) })
        }
        val darkroom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(C.CELL); cornerRadius = dp(16).toFloat() }
            setPadding(dp(10), dp(9), dp(10), dp(9))
            addView(ringBox, LinearLayout.LayoutParams(dp(30), dp(30)))
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        }

        if (!wideCard) {
            // landscape: one column, the darkroom at its foot
            val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; isFillViewport = true; overScrollMode = OVER_SCROLL_NEVER }
            col.setPadding(dp(14), dp(12), dp(12), dp(10))
            scroll.addView(col)
            addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            col.addView(top, match())
            col.addView(powerRow, match().apply { topMargin = dp(7) })
            col.addView(rule())
            col.addView(roll, match())
            col.addView(rule())
            col.addView(frameTitle)
            col.addView(cells, match().apply { topMargin = dp(7) })
            col.addView(recipe, match().apply { topMargin = dp(8) })
            col.addView(lens, match().apply { topMargin = dp(3) })
            col.addView(took, match().apply { topMargin = dp(2) })
            col.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f).apply { topMargin = dp(8) })
            col.addView(darkroom, match())
        } else {
            // portrait: a card under the picture — the camera across the top, then two columns:
            // the camera's power and the roll and what the picture is on the left; its figures
            // and the darkroom on the right
            col.setPadding(dp(14), dp(10), dp(10), dp(12))
            addView(col, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            col.addView(top, match())
            col.addView(rule().apply { (layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(8); bottomMargin = dp(10) } })
            val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            left.addView(powerRow, match())
            left.addView(roll, match().apply { topMargin = dp(8) })
            left.addView(recipe, match().apply { topMargin = dp(10) })
            left.addView(lens, match().apply { topMargin = dp(3) })
            left.addView(took, match().apply { topMargin = dp(2) })
            val right = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            right.addView(frameTitle)
            right.addView(cells, match().apply { topMargin = dp(6) })
            right.addView(darkroom, match().apply { topMargin = dp(8) })
            col.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(View(context).apply { setBackgroundColor(C.LINE) }, LinearLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    marginStart = dp(12); marginEnd = dp(12)
                })
                addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.05f))
            }, match())
        }
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

        // the film and paper this picture was developed on (not the roll counter)
        val r = entry?.recipe?.let { runCatching { Recipe.fromJson(it) }.getOrNull() }
        film.text = when {
            r == null -> entry?.name ?: "—"
            !r.filmOn -> "无胶片"
            else -> Films.short(r.film)
        }
        fit(film)
        paper.text = when {
            r == null || !r.filmOn -> ""
            Films.kind(r.film) == "反转片" || !r.printOn -> "直接扫描"
            else -> Films.paperShort(r.paper)
        }
        paper.visibility = if (paper.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        // this picture
        frameTitle.text = "本张"
        cells.removeAllViews()
        val m = entry?.meta ?: emptyMap()
        cell(Glyph.APERTURE, m["aperture"]?.toFloatOrNull()?.takeIf { it > 0 }?.let { "f/" + "%.1f".format(it).removeSuffix(".0") })
        cell(Glyph.TIMER, m["shutter"]?.toDoubleOrNull()?.takeIf { it > 0 }?.let { if (it >= 0.3) "%.1f″".format(it).replace(".0″", "″") else "1/${Math.round(1 / it)}" })
        cell(Glyph.SUN, m["iso"]?.toFloatOrNull()?.takeIf { it > 0 }?.let { it.toInt().toString() })
        // the look on top of film and paper, when there is one (LUT, diffusion filter)
        recipe.text = r?.let {
            listOfNotNull(
                it.lut?.takeIf { _ -> it.lutOn }?.let { id -> "LUT " + LutCatalog.label(id) },
                Recipe.diffusionNames.getOrNull(it.diffusion)?.takeIf { _ -> it.filmOn && it.diffusion > 0 }?.let { d -> "$d 滤镜" },
            ).joinToString(" · ")
        } ?: ""
        recipe.setTextColor(C.SUB)
        recipe.visibility = if (recipe.text.isNullOrEmpty()) View.GONE else View.VISIBLE
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
