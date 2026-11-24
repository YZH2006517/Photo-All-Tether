package org.photoall.tether.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.View
import kotlin.math.max

/**
 * A picture's histogram: luminance as a grey area, red, green and blue as coloured areas
 * blended where they overlap (white where all three meet), the shadow and highlight thirds
 * marked. Bins are square-rooted so the mid-tones show beside a tall spike.
 */
class HistogramView(context: Context) : View(context) {
    /** 256 bins each: luminance, red, green, blue; and the share of pixels at 0 and at 255. */
    class Data(val luma: IntArray, val red: IntArray, val green: IntArray, val blue: IntArray, val dark: Float, val bright: Float)

    var data: Data? = null
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * resources.displayMetrics.density
        strokeJoin = Paint.Join.ROUND
    }
    private val box = RectF()
    private val path = Path()

    override fun onDraw(c: Canvas) {
        box.set(0f, 0f, width.toFloat(), height.toFloat())
        // always dark, in both themes, as a photo editor shows it: the colours add up on black
        fill.color = 0xFF1C1C1E.toInt()
        c.drawRoundRect(box, 10 * d, 10 * d, fill)
        // quarters: shadows to highlights
        grid.color = 0x2EFFFFFF
        grid.strokeWidth = 1 * d
        for (i in 1..3) c.drawLine(width * i / 4f, 6 * d, width * i / 4f, height - 6 * d, grid)
        val h = data ?: return
        val top = 8 * d
        val bottom = height - 4 * d
        val peak = listOf(h.luma, h.red, h.green, h.blue).maxOf { bins -> bins.sortedArray()[253].coerceAtLeast(1) }
        val scale = kotlin.math.sqrt(peak.toFloat())
        fun area(bins: IntArray, color: Int, mode: PorterDuff.Mode?) {
            path.reset()
            path.moveTo(0f, bottom)
            for (i in 0..255) {
                val v = (kotlin.math.sqrt(bins[i].toFloat()) / scale).coerceAtMost(1f)
                path.lineTo(i / 255f * width, bottom - v * (bottom - top))
            }
            path.lineTo(width.toFloat(), bottom)
            path.close()
            fill.color = color
            fill.xfermode = mode?.let { PorterDuffXfermode(it) }
            c.drawPath(path, fill)
            fill.xfermode = null
        }
        // additive colour, half strength: where red, green and blue overlap it reads grey, as a
        // photo editor shows it, and each channel's own shape stays visible at its edges
        val save = c.saveLayer(box, null)
        area(h.red, 0x80FF453A.toInt(), PorterDuff.Mode.ADD)
        area(h.green, 0x8030D158.toInt(), PorterDuff.Mode.ADD)
        area(h.blue, 0x800A84FF.toInt(), PorterDuff.Mode.ADD)
        c.restoreToCount(save)
        // luminance as a line over them
        path.reset()
        for (i in 0..255) {
            val v = (kotlin.math.sqrt(h.luma[i].toFloat()) / scale).coerceAtMost(1f)
            val x = i / 255f * width
            val y = bottom - v * (bottom - top)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        line.color = 0xD9FFFFFF.toInt()
        c.drawPath(path, line)
    }

    companion object {
        /** Counts [bitmap]'s pixels (about 250 000 of them, evenly spread). Off the main thread. */
        fun measure(bitmap: Bitmap): Data {
            val luma = IntArray(256)
            val red = IntArray(256)
            val green = IntArray(256)
            val blue = IntArray(256)
            val w = bitmap.width
            val h = bitmap.height
            val step = max(1, kotlin.math.sqrt(w.toDouble() * h / 250_000.0).toInt())
            val row = IntArray(w)
            var n = 0
            var dark = 0
            var bright = 0
            var y = 0
            while (y < h) {
                bitmap.getPixels(row, 0, w, 0, y, w, 1)
                var x = 0
                while (x < w) {
                    val p = row[x]
                    val r = (p shr 16) and 255
                    val g = (p shr 8) and 255
                    val b = p and 255
                    red[r]++; green[g]++; blue[b]++
                    // Rec.709 / sRGB luma weights on the encoded values, as cameras show it
                    val l = (r * 54 + g * 183 + b * 19) shr 8
                    luma[l]++
                    if (r == 0 && g == 0 && b == 0) dark++
                    if (r == 255 || g == 255 || b == 255) bright++
                    n++
                    x += step
                }
                y += step
            }
            return Data(luma, red, green, blue, dark / n.coerceAtLeast(1).toFloat(), bright / n.coerceAtLeast(1).toFloat())
        }
    }
}
