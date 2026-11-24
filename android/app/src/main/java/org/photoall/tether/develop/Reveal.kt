package org.photoall.tether.develop

import android.content.Context
import android.graphics.Bitmap
import org.photoall.tether.engine.FilmEngine
import org.photoall.tether.engine.Films
import org.photoall.tether.engine.RawFile
import org.photoall.tether.engine.Recipe
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How a new print comes up in the immersive view: the film engine's own renders of the same raw,
 * one for each step of the process, rather than a filter painted over the picture. Each step is
 * the recipe with one more stage of the engine switched on (the same raw, size and grain seed):
 *
 *  1. the raw as decoded, no film (the scene the camera recorded);
 *  2. the developed negative as it is, orange base and all (the engine's "stage negative");
 *  3. the print in the developer: the dyes of the engine's clean print (no halation, grain,
 *     diffusion or look yet) building up. A dye's density grows with the time in the developer,
 *     and the light the print sends back is 10^-density, so at a fraction t of the development
 *     each channel is the clean print's reflectance raised to t: white paper first, then the print;
 *  4. then halation, grain, the diffusion filter and the LUT, each added on in its own render,
 *     as far as the recipe has them on; the last is the whole recipe.
 *
 * (Ramping the paper's exposure instead does not work: the engine sets every print's own white
 * and black points, so an under-exposed frame comes out stretched, not paler.)
 * Made only while the immersive view is open and the animation is on (设置), at about its screen size.
 */
object Reveal {
    /** One frame of it: the engine's render, the step it belongs to; [quick]: one of a run (the developer's). */
    class Frame(val bitmap: Bitmap, val label: String, val quick: Boolean = false)
    class Sequence(val uri: String, val frames: List<Frame>)

    /** Set by the immersive view while it is on screen (and the animation is on): only then is a sequence made. */
    @Volatile var wanted = false
    @Volatile private var pending: Sequence? = null

    /**
     * The immersive view's live darkroom: each frame as soon as the engine has rendered it, with
     * the id of the develop job it is for (called on the main thread).
     */
    @Volatile var sink: ((Long, Frame) -> Unit)? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Fractions of the development shown (eased: the image appears quickly, then deepens). */
    private val STEPS = floatArrayOf(0.08f, 0.22f, 0.4f, 0.6f, 0.8f)

    /** The steps' names, in order, for [recipe] (what [render] will go through). */
    fun plan(recipe: Recipe): List<String> = stages(recipe).map { it.first }

    // each step: its name and the recipe rendered for it (null: the clean print's developer run)
    private fun stages(recipe: Recipe): List<Pair<String, Recipe?>> {
        val kind = Films.kind(recipe.film)
        val out = mutableListOf<Pair<String, Recipe?>>()
        // 1. the raw, no film, no look (the same exposure and white)
        out += "原始画面" to recipe.copy(filmOn = false, lut = null, diffusion = 0, localTone = 0f)
        val clean = recipe.copy(grain = false, halation = false, diffusion = 0, lut = null)
        if (recipe.filmOn) {
            // 2. the developed film itself, before any print or scan (colour negatives: a slide is
            // its own positive, a black and white negative has no mask to show); the engine shows
            // a negative uninverted only on its scan path (print off)
            if (kind != "反转片" && kind != "黑白")
                out += "负片" to recipe.copy(lut = null, printOn = false, diffusion = 0).withParam("stage negative", 1f)
            // 3. the clean print coming up in the developer
            out += (if (recipe.printOn && kind != "反转片") "相纸显影" else "冲洗") to null
        }
        // 4. what the recipe adds on, one by one
        var acc = if (recipe.filmOn) clean else recipe.copy(filmOn = false, lut = null, diffusion = 0)
        if (recipe.filmOn && recipe.halation) { acc = acc.copy(halation = true); out += "光晕" to acc }
        if (recipe.filmOn && recipe.grain) { acc = acc.copy(grain = true); out += "颗粒" to acc }
        if (recipe.diffusion > 0) { acc = acc.copy(diffusion = recipe.diffusion, diffusionAmount = recipe.diffusionAmount); out += "柔光滤镜" to acc }
        if (recipe.lut != null && recipe.lutOn) out += "风格 LUT" to recipe
        return out
    }

    /**
     * Renders the steps for [recipe] (blocking, on the develop thread), each frame handed to the
     * live darkroom (job [jobId]) as it is made; [onStep] hears each step as it starts.
     */
    fun render(engine: FilmEngine, raw: RawFile, recipe: Recipe, cameraKey: Float, seed: Int, context: Context,
               edge: Int, displayP3: Boolean, jobId: Long, onStep: (String) -> Unit = {}): List<Frame> {
        val frames = mutableListOf<Frame>()
        val clean = recipe.copy(grain = false, halation = false, diffusion = 0, lut = null)
        fun put(f: Frame) {
            frames += f
            sink?.let { s -> main.post { s(jobId, f) } }
        }
        for ((label, r) in stages(recipe)) {
            onStep(label)
            if (r != null) {
                put(Frame(engine.developRaw(raw, r, edge, cameraKey, seed, context, displayP3).bitmap, label))
            } else {
                val print = engine.developRaw(raw, clean, edge, cameraKey, seed, context, displayP3).bitmap
                for (t in STEPS) put(Frame(developing(print, t), label, quick = true))
                put(Frame(print, label))
            }
        }
        return frames
    }

    /** [print] at a fraction [t] of its development: each channel's reflectance to the power t. */
    private fun developing(print: Bitmap, t: Float): Bitmap {
        // per 8-bit code: decode (sRGB / Display P3 share the curve), R^t, encode
        val lut = IntArray(256) { v ->
            val c = v / 255f
            val lin = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
            val r = lin.coerceAtLeast(1e-5f).pow(t)
            val e = if (r <= 0.0031308f) r * 12.92f else 1.055f * r.pow(1f / 2.4f) - 0.055f
            (e * 255f).roundToInt().coerceIn(0, 255)
        }
        val w = print.width
        val h = print.height
        val px = IntArray(w * h)
        print.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]
            px[i] = (p and -0x1000000) or (lut[(p shr 16) and 255] shl 16) or (lut[(p shr 8) and 255] shl 8) or lut[p and 255]
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888, true, print.colorSpace ?: android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
            .apply { setPixels(px, 0, w, 0, 0, w, h) }
    }

    fun offer(uri: String, frames: List<Frame>) {
        if (frames.isNotEmpty()) pending = Sequence(uri, frames)
    }

    /** The sequence for the print at [uri], once (null when there is none for it). */
    fun take(uri: String): List<Frame>? {
        val p = pending ?: return null
        if (p.uri != uri) return null
        pending = null
        return p.frames
    }

    fun clear() { pending = null }
}
