package org.photoall.tether.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Line icons in the manner of SF Symbols: drawn on a 24-unit grid with round caps and joins,
 * one stroke weight, so they sit with text of any size. Filled variants where the shape is a
 * state (a lit bolt, a battery's charge).
 */
enum class Glyph {
    CAMERA, FILM, DIAL, GEAR, REFRESH, EXPAND, CABLE, POWER, BOLT, BOLT_FILL, SDCARD, CHECK, CHECK_CIRCLE,
    CIRCLE, TRASH, LOCK, UNLOCK, IMPORT, SHARE, INFO, CLOSE, CHEVRON_RIGHT, CHEVRON_DOWN, CHEVRON_LEFT,
    APERTURE, TIMER, EV, SUN, BULB, SPARKLES, WAND, GRAIN, HALO, PHOTO, PHOTO_STACK, FOLDER_PLUS, PLUS,
    MINUS, THERMO, WAVE, ROTATE, LIST, COPY, CUBE, ROLL, EYE, SLIDERS, CONTRAST, SELECT_ALL, ARROW_UP_RIGHT,
}

class IconDrawable(var glyph: Glyph, var color: Int, private val weight: Float = 1.75f) : Drawable() {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val r = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        canvas.save()
        canvas.translate(b.left + (b.width() - 24 * s) / 2, b.top + (b.height() - 24 * s) / 2)
        canvas.scale(s, s)
        stroke.color = color
        stroke.alpha = (((color ushr 24) and 0xFF) * opacity / 255)
        stroke.strokeWidth = weight
        fill.color = color
        fill.alpha = stroke.alpha
        drawGlyph(canvas)
        canvas.restore()
    }


    private fun line(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) = c.drawLine(x0, y0, x1, y1, stroke)
    private fun poly(c: Canvas, vararg p: Float, close: Boolean = false) {
        path.reset()
        path.moveTo(p[0], p[1])
        var i = 2
        while (i < p.size) { path.lineTo(p[i], p[i + 1]); i += 2 }
        if (close) path.close()
        c.drawPath(path, stroke)
    }
    private fun rrect(c: Canvas, l: Float, t: Float, rt: Float, bt: Float, rad: Float, paint: Paint = stroke) {
        r.set(l, t, rt, bt); c.drawRoundRect(r, rad, rad, paint)
    }
    private fun circle(c: Canvas, x: Float, y: Float, rad: Float, paint: Paint = stroke) = c.drawCircle(x, y, rad, paint)
    private fun arc(c: Canvas, x: Float, y: Float, rad: Float, start: Float, sweep: Float) {
        r.set(x - rad, y - rad, x + rad, y + rad); c.drawArc(r, start, sweep, false, stroke)
    }
    private fun star(c: Canvas, x: Float, y: Float, s: Float, paint: Paint) {
        path.reset()
        path.moveTo(x, y - s)
        path.quadTo(x, y, x + s, y); path.quadTo(x, y, x, y + s)
        path.quadTo(x, y, x - s, y); path.quadTo(x, y, x, y - s)
        path.close()
        c.drawPath(path, paint)
    }

    private fun drawGlyph(c: Canvas) {
        when (glyph) {
            Glyph.CAMERA -> {
                rrect(c, 2.5f, 7f, 21.5f, 19.5f, 3f)
                poly(c, 8f, 7f, 9.6f, 4.5f, 14.4f, 4.5f, 16f, 7f)
                circle(c, 12f, 13f, 3.6f)
                circle(c, 18.2f, 9.6f, 0.6f, fill)
            }
            Glyph.FILM -> {
                rrect(c, 5f, 2.5f, 19f, 21.5f, 2.5f)
                rrect(c, 8.5f, 7f, 15.5f, 17f, 1f)
                for (y in listOf(4.8f, 8.6f, 12.4f, 16.2f, 20f)) { circle(c, 6.8f, y - 0.6f, 0.55f, fill); circle(c, 17.2f, y - 0.6f, 0.55f, fill) }
            }
            Glyph.ROLL -> { // a 135 cartridge from the front with its leader
                rrect(c, 3f, 5f, 13f, 20f, 2f)
                line(c, 5f, 3.5f, 11f, 3.5f); line(c, 5f, 21.5f, 11f, 21.5f)
                poly(c, 13f, 8f, 21f, 8f, 21f, 17f, 13f, 17f)
                for (x in listOf(15f, 18f)) { circle(c, x, 9.8f, 0.5f, fill); circle(c, x, 15.2f, 0.5f, fill) }
            }
            Glyph.DIAL, Glyph.SLIDERS -> {
                line(c, 3f, 6f, 12.5f, 6f); circle(c, 15f, 6f, 2.4f); line(c, 17.5f, 6f, 21f, 6f)
                line(c, 3f, 12f, 5.5f, 12f); circle(c, 8f, 12f, 2.4f); line(c, 10.5f, 12f, 21f, 12f)
                line(c, 3f, 18f, 10.5f, 18f); circle(c, 13f, 18f, 2.4f); line(c, 15.5f, 18f, 21f, 18f)
            }
            Glyph.GEAR -> {
                circle(c, 12f, 12f, 6.2f)
                circle(c, 12f, 12f, 2.4f)
                val saved = stroke.strokeWidth
                stroke.strokeWidth = 2.6f
                for (k in 0 until 8) {
                    val a = k * PI / 4
                    line(c, 12f + 6.6f * cos(a).toFloat(), 12f + 6.6f * sin(a).toFloat(), 12f + 9f * cos(a).toFloat(), 12f + 9f * sin(a).toFloat())
                }
                stroke.strokeWidth = saved
            }
            Glyph.REFRESH -> {
                arc(c, 12f, 12f, 7.5f, -60f, 300f)
                poly(c, 15.2f, 3.4f, 15.8f, 5.6f, 13.6f, 6.6f)
            }
            Glyph.EXPAND -> {
                poly(c, 14f, 4f, 20f, 4f, 20f, 10f); line(c, 20f, 4f, 13.5f, 10.5f)
                poly(c, 10f, 20f, 4f, 20f, 4f, 14f); line(c, 4f, 20f, 10.5f, 13.5f)
            }
            Glyph.CABLE -> {
                rrect(c, 7.5f, 8.5f, 16.5f, 16f, 1.8f)
                line(c, 10f, 5f, 10f, 8.5f); line(c, 14f, 5f, 14f, 8.5f)
                line(c, 12f, 16f, 12f, 21.5f)
            }
            Glyph.POWER -> {
                arc(c, 12f, 13f, 7.5f, -50f, 280f)
                line(c, 12f, 3f, 12f, 11f)
            }
            Glyph.BOLT -> poly(c, 13.5f, 2.5f, 4.5f, 13.8f, 11.5f, 13.8f, 10.5f, 21.5f, 19.5f, 10.2f, 12.5f, 10.2f, close = true)
            Glyph.BOLT_FILL -> {
                path.reset(); path.moveTo(13.5f, 2.5f); path.lineTo(4.5f, 13.8f); path.lineTo(11.5f, 13.8f)
                path.lineTo(10.5f, 21.5f); path.lineTo(19.5f, 10.2f); path.lineTo(12.5f, 10.2f); path.close()
                c.drawPath(path, fill); c.drawPath(path, stroke)
            }
            Glyph.SDCARD -> {
                poly(c, 9f, 3f, 17.5f, 3f, 19.5f, 5f, 19.5f, 21f, 4.5f, 21f, 4.5f, 7.5f, close = true)
                for (x in listOf(9.5f, 12.5f, 15.5f)) line(c, x, 5.5f, x, 8.5f)
            }
            Glyph.CHECK -> poly(c, 5f, 12.5f, 10f, 17.5f, 19.5f, 7f)
            Glyph.CHECK_CIRCLE -> { circle(c, 12f, 12f, 9.5f); poly(c, 7.6f, 12.3f, 10.6f, 15.3f, 16.6f, 9f) }
            Glyph.CIRCLE -> circle(c, 12f, 12f, 9.5f)
            Glyph.SELECT_ALL -> {
                rrect(c, 3f, 3f, 21f, 21f, 4f)
                poly(c, 7.6f, 12.3f, 10.6f, 15.3f, 16.6f, 9f)
            }
            Glyph.TRASH -> {
                line(c, 3.5f, 6.5f, 20.5f, 6.5f)
                poly(c, 9f, 6.5f, 9f, 4f, 15f, 4f, 15f, 6.5f)
                poly(c, 5.8f, 6.5f, 7f, 20.5f, 17f, 20.5f, 18.2f, 6.5f)
                line(c, 10f, 10.5f, 10f, 16.5f); line(c, 14f, 10.5f, 14f, 16.5f)
            }
            Glyph.LOCK, Glyph.UNLOCK -> {
                rrect(c, 5f, 10.5f, 19f, 21f, 2.5f)
                path.reset()
                path.moveTo(8f, 10.5f); path.lineTo(8f, 7.5f)
                r.set(8f, 3.5f, 16f, 11.5f)
                if (glyph == Glyph.LOCK) { path.arcTo(r, 180f, 180f); path.lineTo(16f, 10.5f) } else path.arcTo(r, 180f, 130f)
                c.drawPath(path, stroke)
                circle(c, 12f, 15.5f, 1.2f, fill)
            }
            Glyph.IMPORT -> {
                line(c, 12f, 3f, 12f, 14.5f); poly(c, 7.5f, 10f, 12f, 14.5f, 16.5f, 10f)
                poly(c, 4f, 14.5f, 4f, 20f, 20f, 20f, 20f, 14.5f)
            }
            Glyph.SHARE -> {
                poly(c, 8.5f, 9f, 5.5f, 9f, 5.5f, 21f, 18.5f, 21f, 18.5f, 9f, 15.5f, 9f)
                line(c, 12f, 15f, 12f, 2.8f); poly(c, 8.3f, 6.5f, 12f, 2.8f, 15.7f, 6.5f)
            }
            Glyph.INFO -> { circle(c, 12f, 12f, 9.5f); circle(c, 12f, 7.8f, 1.1f, fill); line(c, 12f, 11f, 12f, 16.8f) }
            Glyph.CLOSE -> { line(c, 6f, 6f, 18f, 18f); line(c, 18f, 6f, 6f, 18f) }
            Glyph.CHEVRON_RIGHT -> poly(c, 9f, 5f, 16f, 12f, 9f, 19f)
            Glyph.CHEVRON_DOWN -> poly(c, 5f, 9f, 12f, 16f, 19f, 9f)
            Glyph.CHEVRON_LEFT -> poly(c, 15f, 5f, 8f, 12f, 15f, 19f)
            Glyph.ARROW_UP_RIGHT -> { line(c, 6f, 18f, 18f, 6f); poly(c, 9f, 6f, 18f, 6f, 18f, 15f) }
            Glyph.APERTURE -> {
                circle(c, 12f, 12f, 9.5f)
                for (k in 0 until 6) {
                    val a = k * PI / 3 - PI / 2
                    val b = a + PI * 0.62
                    line(c, 12f + 9.3f * cos(a).toFloat(), 12f + 9.3f * sin(a).toFloat(),
                        12f + 4f * cos(b).toFloat(), 12f + 4f * sin(b).toFloat())
                }
            }
            Glyph.TIMER -> {
                circle(c, 12f, 13.5f, 8f)
                line(c, 12f, 13.5f, 12f, 9f)
                line(c, 9.8f, 2.8f, 14.2f, 2.8f); line(c, 12f, 2.8f, 12f, 5.5f)
            }
            Glyph.EV -> {
                rrect(c, 3.5f, 3.5f, 20.5f, 20.5f, 3.5f)
                line(c, 4.5f, 19.5f, 19.5f, 4.5f)
                line(c, 6.5f, 8.5f, 10.5f, 8.5f); line(c, 8.5f, 6.5f, 8.5f, 10.5f)
                line(c, 13.5f, 15.5f, 17.5f, 15.5f)
            }
            Glyph.SUN -> {
                circle(c, 12f, 12f, 4f)
                for (k in 0 until 8) {
                    val a = k * PI / 4
                    line(c, 12f + 6.6f * cos(a).toFloat(), 12f + 6.6f * sin(a).toFloat(), 12f + 8.8f * cos(a).toFloat(), 12f + 8.8f * sin(a).toFloat())
                }
            }
            Glyph.BULB -> {
                path.reset()
                r.set(6.5f, 3f, 17.5f, 14f)
                path.moveTo(9.5f, 16f); path.lineTo(9.5f, 14f)
                path.arcTo(r, 125f, 290f)
                path.lineTo(14.5f, 16f)
                c.drawPath(path, stroke)
                line(c, 9.5f, 18.5f, 14.5f, 18.5f); line(c, 10.5f, 21f, 13.5f, 21f)
            }
            Glyph.SPARKLES -> { star(c, 10f, 13f, 7f, stroke); star(c, 18.5f, 5.5f, 2.8f, fill) }
            Glyph.WAND -> {
                val saved = stroke.strokeWidth
                stroke.strokeWidth = 2.2f
                line(c, 4f, 20f, 14f, 10f)
                stroke.strokeWidth = saved
                star(c, 17.5f, 6.5f, 3.2f, fill); star(c, 20.5f, 13f, 1.6f, fill); star(c, 11f, 4f, 1.6f, fill)
            }
            Glyph.GRAIN -> for ((x, y, rad) in listOf(Triple(6f, 6f, 1.3f), Triple(12f, 5f, 0.9f), Triple(18f, 7f, 1.5f), Triple(8f, 12f, 1f),
                Triple(14f, 11.5f, 1.6f), Triple(19f, 14f, 0.9f), Triple(5f, 18f, 1.5f), Triple(11f, 18.5f, 1.2f), Triple(17f, 19f, 1.1f)))
                circle(c, x, y, rad, fill)
            Glyph.HALO -> {
                circle(c, 12f, 12f, 3.5f, fill)
                stroke.alpha = stroke.alpha * 2 / 3
                circle(c, 12f, 12f, 6.8f)
                stroke.alpha = stroke.alpha / 2
                circle(c, 12f, 12f, 9.8f)
            }
            Glyph.PHOTO -> {
                rrect(c, 2.5f, 4.5f, 21.5f, 19.5f, 3f)
                poly(c, 3f, 17f, 8.5f, 11.5f, 12.5f, 15.5f, 15.5f, 12.5f, 21f, 18f)
                circle(c, 16f, 8.8f, 1.6f)
            }
            Glyph.PHOTO_STACK -> {
                rrect(c, 2.5f, 7f, 17.5f, 20.5f, 2.5f)
                poly(c, 6f, 4f, 19f, 4f, 21f, 6f, 21f, 16.5f)
                poly(c, 3f, 18f, 7.5f, 13.5f, 10.5f, 16.5f, 13f, 14f, 17f, 18f)
            }
            Glyph.FOLDER_PLUS -> {
                poly(c, 2.5f, 6f, 2.5f, 19.5f, 21.5f, 19.5f, 21.5f, 8.5f, 11f, 8.5f, 9f, 5f, 3.5f, 5f, close = true)
                line(c, 12f, 11.5f, 12f, 16.5f); line(c, 9.5f, 14f, 14.5f, 14f)
            }
            Glyph.PLUS -> { line(c, 12f, 5f, 12f, 19f); line(c, 5f, 12f, 19f, 12f) }
            Glyph.MINUS -> line(c, 5f, 12f, 19f, 12f)
            Glyph.THERMO -> {
                rrect(c, 9.8f, 2.5f, 14.2f, 15f, 2.2f)
                circle(c, 12f, 17.5f, 3.6f)
                circle(c, 12f, 17.5f, 1.5f, fill)
                line(c, 12f, 15f, 12f, 8f)
            }
            Glyph.WAVE -> {
                path.reset(); path.moveTo(2.5f, 12f)
                path.cubicTo(5f, 5f, 7.5f, 5f, 10f, 12f); path.cubicTo(12.5f, 19f, 15f, 19f, 17.5f, 12f)
                path.cubicTo(19f, 8f, 20.5f, 8f, 21.5f, 10f)
                c.drawPath(path, stroke)
            }
            Glyph.ROTATE -> {
                rrect(c, 7f, 3f, 17f, 21f, 2.5f)
                arc(c, 12f, 12f, 10.5f, 200f, 70f)
                line(c, 12f, 18f, 12f, 18.01f)
            }
            Glyph.LIST -> for (y in listOf(6f, 12f, 18f)) { circle(c, 4.5f, y, 1.1f, fill); line(c, 8.5f, y, 20.5f, y) }
            Glyph.COPY -> { rrect(c, 8f, 8f, 20.5f, 20.5f, 2.5f); poly(c, 15.5f, 5f, 15.5f, 3.5f, 3.5f, 3.5f, 3.5f, 15.5f, 5f, 15.5f) }
            Glyph.CUBE -> {
                poly(c, 12f, 2.5f, 20.5f, 7f, 20.5f, 17f, 12f, 21.5f, 3.5f, 17f, 3.5f, 7f, close = true)
                poly(c, 3.5f, 7f, 12f, 11.5f, 20.5f, 7f); line(c, 12f, 11.5f, 12f, 21.5f)
            }
            Glyph.EYE -> {
                path.reset(); path.moveTo(2f, 12f); path.quadTo(12f, 2.5f, 22f, 12f); path.quadTo(12f, 21.5f, 2f, 12f); path.close()
                c.drawPath(path, stroke); circle(c, 12f, 12f, 3.2f)
            }
            Glyph.CONTRAST -> {
                circle(c, 12f, 12f, 9.5f)
                path.reset(); r.set(2.5f, 2.5f, 21.5f, 21.5f); path.arcTo(r, -90f, 180f); path.close()
                c.drawPath(path, fill)
            }
        }
    }

    private var opacity = 255
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun getAlpha(): Int = opacity
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** A glyph at a size (dp), tinted. */
class IconView(context: Context, glyph: Glyph, color: Int = Ui.TEXT, sizeDp: Int = 22) : View(context) {
    private val icon = IconDrawable(glyph, color)
    private val size = context.dp(sizeDp)

    var glyph: Glyph
        get() = icon.glyph
        set(v) { icon.glyph = v; invalidate() }
    var color: Int
        get() = icon.color
        set(v) { icon.color = v; invalidate() }

    // the glyph's size plus the padding round it (a button's touch area)
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(resolveSize(size + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(size + paddingTop + paddingBottom, heightMeasureSpec))

    override fun onDraw(canvas: Canvas) {
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        val s = minOf(w, h, size.coerceAtLeast(minOf(w, h) / 2))
        val cx = paddingLeft + w / 2
        val cy = paddingTop + h / 2
        icon.setBounds(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
        icon.draw(canvas)
    }
}
