package org.photoall.tether.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * The brightness the photographer saw: the camera JPEG's centre-weighted log-average linear
 * luminance, measured exactly as the phone app's viewfinder meter does (and as the engine's
 * raw_key measures the raw), so a develop can start from the camera's own exposure.
 */
object Brightness {
    private val linear = FloatArray(256) { i ->
        val v = i / 255f
        if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    fun key(bitmap: Bitmap): Float {
        val columns = 128
        val rows = 96
        val w = bitmap.width
        val h = bitmap.height
        var logSum = 0.0
        var weightSum = 0.0
        for (r in 0 until rows) {
            val y = (r * h) / rows
            val dy = (r + 0.5) / rows - 0.5
            for (c in 0 until columns) {
                val x = (c * w) / columns
                val p = bitmap.getPixel(x, y)
                val l = 0.2126f * linear[(p shr 16) and 255] + 0.7152f * linear[(p shr 8) and 255] +
                    0.0722f * linear[p and 255]
                val dx = (c + 0.5) / columns - 0.5
                val weight = 1.0 + 3.0 * exp(-(dx * dx + dy * dy) / (2 * 0.18 * 0.18))
                logSum += weight * ln(l + 1e-4)
                weightSum += weight
            }
        }
        return max(0.0, exp(logSum / weightSum) - 1e-4).toFloat()
    }

    /** The key of a JPEG, decoded small; 0 when it cannot be read. */
    fun key(jpeg: ByteArray): Float {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 512) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return 0f
        return key(bitmap).also { bitmap.recycle() }
    }
}
