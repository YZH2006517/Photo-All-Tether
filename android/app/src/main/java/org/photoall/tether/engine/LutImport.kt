package org.photoall.tether.engine

import android.graphics.BitmapFactory
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Reads colour LUTs from the files photographers and colourists pass around, and brings each to
 * the engine's form: a 33^3 RGB8 table, red fastest, display-referred in and out (as the
 * bundled ones):
 * - .cube (Adobe / Resolve): 3D of any size, or 1D (each channel its own curve); DOMAIN_MIN /
 *   DOMAIN_MAX and Resolve's LUT_3D_INPUT_RANGE honoured; values outside 0..1 clipped
 * - .3dl (Autodesk / Lustre / Flame): integer 3D tables of any bit depth, the mesh line read or
 *   inferred, blue fastest
 * - Hald CLUT images (.png / .jpg): a square of level L (L^3 pixels a side, L^2 per axis)
 * - .zip: every LUT inside, folders and all (macOS litter skipped)
 * Each is resampled to 33^3 with trilinear interpolation.
 */
object LutImport {
    const val SIZE = 33

    class Parsed(val name: String, val rgb: ByteArray, val format: String, val sourceSize: Int)
    class Failure(val name: String, val reason: String)

    private val known = setOf("cube", "3dl", "png", "jpg", "jpeg")

    fun extension(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    fun accepts(name: String) = extension(name) in known || extension(name) == "zip"

    /**
     * Everything in [input] called [name]: one LUT, or each in a zip. Good ones go to [onLut],
     * the rest to [onFail] with why.
     */
    fun read(name: String, input: InputStream, onLut: (Parsed) -> Unit, onFail: (Failure) -> Unit) {
        if (extension(name) == "zip") {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val inner = e.name.substringAfterLast('/')
                    if (e.isDirectory || inner.startsWith(".") || e.name.contains("__MACOSX")) continue
                    if (extension(inner) !in known) continue
                    // the zip's stream must not be closed by the reader
                    val bytes = zip.readBytes()
                    one(inner, bytes.inputStream(), onLut, onFail)
                }
            }
        } else one(name, input, onLut, onFail)
    }

    private fun one(name: String, input: InputStream, onLut: (Parsed) -> Unit, onFail: (Failure) -> Unit) {
        val label = name.substringBeforeLast('.')
        try {
            val parsed = when (extension(name)) {
                "cube" -> cube(label, input)
                "3dl" -> threeDl(label, input)
                "png", "jpg", "jpeg" -> hald(label, input)
                else -> throw IllegalArgumentException("不认识的格式")
            }
            onLut(parsed)
        } catch (e: Throwable) {
            onFail(Failure(name, e.message ?: e.javaClass.simpleName))
        }
    }

    // ---- .cube ------------------------------------------------------------------------------------

    private fun cube(label: String, input: InputStream): Parsed {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        var title: String? = null
        var size3 = 0
        var size1 = 0
        val lo = floatArrayOf(0f, 0f, 0f)
        val hi = floatArrayOf(1f, 1f, 1f)
        var data = FloatArray(0)
        var n = 0
        reader.forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine
            val first = line[0]
            if (first.isLetter()) {
                val parts = line.split(Regex("\\s+"))
                when (parts[0].uppercase(Locale.ROOT)) {
                    "TITLE" -> title = line.substringAfter("TITLE").trim().trim('"').ifEmpty { null }
                    "LUT_3D_SIZE" -> { size3 = parts[1].toInt(); data = FloatArray(size3 * size3 * size3 * 3) }
                    "LUT_1D_SIZE" -> if (size3 == 0) { size1 = parts[1].toInt(); data = FloatArray(size1 * 3) }
                    "DOMAIN_MIN" -> for (i in 0..2) lo[i] = parts[i + 1].toFloat()
                    "DOMAIN_MAX" -> for (i in 0..2) hi[i] = parts[i + 1].toFloat()
                    "LUT_3D_INPUT_RANGE", "LUT_1D_INPUT_RANGE" -> {
                        val a = parts[1].toFloat(); val b = parts[2].toFloat()
                        for (i in 0..2) { lo[i] = a; hi[i] = b }
                    }
                }
                return@forEachLine
            }
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 3 || data.isEmpty()) return@forEachLine
            if (n + 3 > data.size) throw IllegalArgumentException("数据比声明的多")
            data[n] = parts[0].toFloat(); data[n + 1] = parts[1].toFloat(); data[n + 2] = parts[2].toFloat()
            n += 3
        }
        if (data.isEmpty()) throw IllegalArgumentException("没有 LUT_3D_SIZE / LUT_1D_SIZE")
        if (n != data.size) throw IllegalArgumentException("数据不完整（${n / 3} / ${data.size / 3} 行）")
        val name = title?.takeIf { it.isNotBlank() && it.length <= 60 } ?: label
        return if (size3 > 0) {
            Parsed(name, resample3d(data, size3, lo, hi), "cube", size3)
        } else {
            Parsed(name, from1d(data, size1, lo, hi), "cube 1D", size1)
        }
    }

    // ---- .3dl -------------------------------------------------------------------------------------

    private fun threeDl(label: String, input: InputStream): Parsed {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        var mesh: IntArray? = null
        val values = ArrayList<Int>(110_000)
        reader.forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line[0].isLetter()) return@forEachLine
            val parts = line.split(Regex("\\s+"))
            val ints = parts.mapNotNull { it.toIntOrNull() }
            if (ints.size != parts.size) return@forEachLine
            if (mesh == null && ints.size > 3) { mesh = ints.toIntArray(); return@forEachLine }
            if (ints.size == 3) values.addAll(ints)
        }
        val count = values.size / 3
        val n = mesh?.size ?: Math.round(Math.cbrt(count.toDouble())).toInt()
        if (n < 2 || n * n * n != count) throw IllegalArgumentException("3DL 数据不是立方体（$count 行）")
        // the output's bit depth: the smallest of the usual that holds the largest value
        val top = values.maxOrNull() ?: 1
        val depth = listOf(255, 1023, 4095, 16383, 65535).firstOrNull { top <= it } ?: top
        // blue fastest in the file; red fastest in ours
        val data = FloatArray(count * 3)
        var k = 0
        for (r in 0 until n) for (g in 0 until n) for (b in 0 until n) {
            val o = (r + g * n + b * n * n) * 3
            data[o] = values[k] / depth.toFloat()
            data[o + 1] = values[k + 1] / depth.toFloat()
            data[o + 2] = values[k + 2] / depth.toFloat()
            k += 3
        }
        return Parsed(label, resample3d(data, n, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f)), "3dl", n)
    }

    // ---- Hald CLUT ---------------------------------------------------------------------------------

    private fun hald(label: String, input: InputStream): Parsed {
        val bmp = BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inScaled = false })
            ?: throw IllegalArgumentException("读不出图片")
        try {
            val w = bmp.width
            val level = Math.round(Math.cbrt(w.toDouble())).toInt()
            if (bmp.height != w || level * level * level != w || level < 2)
                throw IllegalArgumentException("不是 Hald CLUT（${bmp.width}×${bmp.height}）")
            val n = level * level
            val px = IntArray(w * w)
            bmp.getPixels(px, 0, w, 0, 0, w, w)
            val data = FloatArray(n * n * n * 3)
            for (i in 0 until n * n * n) {
                val c = px[i]
                data[i * 3] = ((c shr 16) and 255) / 255f
                data[i * 3 + 1] = ((c shr 8) and 255) / 255f
                data[i * 3 + 2] = (c and 255) / 255f
            }
            return Parsed(label, resample3d(data, n, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f)), "Hald", n)
        } finally {
            bmp.recycle()
        }
    }

    // ---- to 33^3 ----------------------------------------------------------------------------------

    /** Samples a 3D table (floats, red fastest, over the domain [lo, hi]) at our 33^3 grid. */
    private fun resample3d(src: FloatArray, n: Int, lo: FloatArray, hi: FloatArray): ByteArray {
        val out = ByteArray(SIZE * SIZE * SIZE * 3)
        val c = FloatArray(3)
        var o = 0
        for (b in 0 until SIZE) for (g in 0 until SIZE) for (r in 0 until SIZE) {
            val x = r / (SIZE - 1f)
            val y = g / (SIZE - 1f)
            val z = b / (SIZE - 1f)
            sample(src, n, coord(x, lo[0], hi[0], n), coord(y, lo[1], hi[1], n), coord(z, lo[2], hi[2], n), c)
            out[o] = byte(c[0]); out[o + 1] = byte(c[1]); out[o + 2] = byte(c[2])
            o += 3
        }
        return out
    }

    /** A 1D table: each channel through its own curve, as a 3D table. */
    private fun from1d(src: FloatArray, n: Int, lo: FloatArray, hi: FloatArray): ByteArray {
        fun curve(ch: Int, v: Float): Float {
            val t = coord(v, lo[ch], hi[ch], n)
            val i = t.toInt().coerceIn(0, n - 2)
            val f = t - i
            return src[i * 3 + ch] * (1 - f) + src[(i + 1) * 3 + ch] * f
        }
        val out = ByteArray(SIZE * SIZE * SIZE * 3)
        var o = 0
        for (b in 0 until SIZE) for (g in 0 until SIZE) for (r in 0 until SIZE) {
            out[o] = byte(curve(0, r / (SIZE - 1f)))
            out[o + 1] = byte(curve(1, g / (SIZE - 1f)))
            out[o + 2] = byte(curve(2, b / (SIZE - 1f)))
            o += 3
        }
        return out
    }

    private fun coord(v: Float, lo: Float, hi: Float, n: Int): Float {
        val t = if (hi > lo) (v - lo) / (hi - lo) else v
        return t.coerceIn(0f, 1f) * (n - 1)
    }

    private fun sample(src: FloatArray, n: Int, x: Float, y: Float, z: Float, out: FloatArray) {
        val x0 = x.toInt().coerceIn(0, n - 2); val fx = x - x0
        val y0 = y.toInt().coerceIn(0, n - 2); val fy = y - y0
        val z0 = z.toInt().coerceIn(0, n - 2); val fz = z - z0
        for (ch in 0..2) {
            fun at(i: Int, j: Int, k: Int) = src[((i + j * n + k * n * n) * 3) + ch]
            val c00 = at(x0, y0, z0) * (1 - fx) + at(x0 + 1, y0, z0) * fx
            val c10 = at(x0, y0 + 1, z0) * (1 - fx) + at(x0 + 1, y0 + 1, z0) * fx
            val c01 = at(x0, y0, z0 + 1) * (1 - fx) + at(x0 + 1, y0, z0 + 1) * fx
            val c11 = at(x0, y0 + 1, z0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1, z0 + 1) * fx
            val c0 = c00 * (1 - fy) + c10 * fy
            val c1 = c01 * (1 - fy) + c11 * fy
            out[ch] = c0 * (1 - fz) + c1 * fz
        }
    }

    private fun byte(v: Float): Byte = Math.round(v.coerceIn(0f, 1f) * 255f).toByte()

    /**
     * Applies a 33^3 RGB8 [table] to [pixels] (ARGB) in place, at [strength] (0..1): trilinear,
     * as the engine does. For the LUT page's previews.
     */
    fun apply(table: ByteArray, size: Int, pixels: IntArray, strength: Float) {
        val n = size
        val k = (n - 1) / 255f
        val s = strength.coerceIn(0f, 1f)
        fun t(i: Int) = table[i].toInt() and 255
        for (p in pixels.indices) {
            val c = pixels[p]
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            val x = r * k; val y = g * k; val z = b * k
            val x0 = x.toInt().coerceAtMost(n - 2); val fx = x - x0
            val y0 = y.toInt().coerceAtMost(n - 2); val fy = y - y0
            val z0 = z.toInt().coerceAtMost(n - 2); val fz = z - z0
            val i000 = (x0 + y0 * n + z0 * n * n) * 3
            val dx = 3; val dy = n * 3; val dz = n * n * 3
            var outC = c and 0xFF000000.toInt()
            for (ch in 0..2) {
                val c00 = t(i000 + ch) * (1 - fx) + t(i000 + dx + ch) * fx
                val c10 = t(i000 + dy + ch) * (1 - fx) + t(i000 + dy + dx + ch) * fx
                val c01 = t(i000 + dz + ch) * (1 - fx) + t(i000 + dz + dx + ch) * fx
                val c11 = t(i000 + dz + dy + ch) * (1 - fx) + t(i000 + dz + dy + dx + ch) * fx
                val v = (c00 * (1 - fy) + c10 * fy) * (1 - fz) + (c01 * (1 - fy) + c11 * fy) * fz
                val orig = when (ch) { 0 -> r; 1 -> g; else -> b }
                val m = (orig + (v - orig) * s).toInt().coerceIn(0, 255)
                outC = outC or (m shl (16 - ch * 8))
            }
            pixels[p] = outC
        }
    }
}
