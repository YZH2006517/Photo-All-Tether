package org.photoall.tether.engine

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Matrix

internal object TetherNative {
    init {
        System.loadLibrary("tetherjni")
    }

    @JvmStatic external fun engineCreate(assets: AssetManager): Long
    @JvmStatic external fun engineDestroy(handle: Long)
    @JvmStatic external fun engineDevice(handle: Long): String
    @JvmStatic external fun names(papers: Boolean): Array<String>
    @JvmStatic external fun rawOpen(path: String, longEdgeHint: Int): Long
    @JvmStatic external fun rawRelease(handle: Long)
    @JvmStatic external fun rawMeta(handle: Long): String
    @JvmStatic external fun embeddedPreview(path: String): ByteArray?
    @JvmStatic external fun developRaw(
        engine: Long, raw: Long, names: Array<String>, values: FloatArray, maxLongEdge: Int, exposureEv: Float,
        seed: Int, matchKey: Float, white: Int, kelvin: Float, tint: Float, rawDenoise: Float, chromaDenoise: Float,
    ): LongArray
    @JvmStatic external fun developBitmap(
        engine: Long, bitmap: Bitmap, names: Array<String>, values: FloatArray, maxLongEdge: Int, exposureEv: Float,
        seed: Int, restore: Float,
    ): LongArray
    @JvmStatic external fun copyResult(engine: Long, bitmap: Bitmap)
    @JvmStatic external fun lastNote(engine: Long): String
    @JvmStatic external fun setCreativeLut(engine: Long, rgb: ByteArray?, size: Int, strength: Float)
}

/** A camera raw file decoded into sensor data (LibRaw), ready to develop any number of times. */
class RawFile private constructor(private var handle: Long) : AutoCloseable {
    /** make, model, lens, iso, shutter (s), aperture, focal, time (unix s), width, height, ... */
    val meta: Map<String, String> by lazy {
        TetherNative.rawMeta(checkOpen()).lineSequence().filter { '\t' in it }
            .associate { it.substringBefore('\t') to it.substringAfter('\t') }
    }

    internal fun checkOpen(): Long {
        check(handle != 0L) { "RAW 已关闭" }
        return handle
    }

    override fun close() {
        if (handle != 0L) TetherNative.rawRelease(handle)
        handle = 0L
    }

    companion object {
        /** [longEdgeHint]: the size it will be developed at (non-Bayer sensors are decoded smaller when that is enough). */
        fun open(path: String, longEdgeHint: Int = 0): RawFile = RawFile(TetherNative.rawOpen(path, longEdgeHint))

        /** The JPEG the camera embedded in a raw file (a NEF holds a full-size one), or null. */
        fun embeddedJpeg(path: String): ByteArray? = runCatching { TetherNative.embeddedPreview(path) }.getOrNull()
    }
}

class Developed(val bitmap: Bitmap, val note: String, val gpuMs: Double)

/**
 * The phone app's spectral film engine (vkdt filmsim / spektrafilm on Vulkan), unchanged.
 * One per process; every develop goes through [lock] because the creative LUT is engine state.
 */
class FilmEngine private constructor(private val handle: Long) {
    private val lock = Any()
    private var lutKey: String? = null

    val deviceName: String get() = TetherNative.engineDevice(handle)

    fun developRaw(raw: RawFile, recipe: Recipe, longEdge: Int, matchKey: Float, seed: Int, context: Context): Developed =
        synchronized(lock) {
            applyLut(recipe, context)
            val params = recipe.params()
            val info = TetherNative.developRaw(
                handle, raw.checkOpen(), params.keys.toTypedArray(), params.values.toFloatArray(), longEdge,
                recipe.ev, seed, if (recipe.matchCamera) matchKey else 0f,
                if (recipe.white == Recipe.WHITE_CAMERA) 0 else 1, recipe.kelvinFor(), recipe.tint,
                recipe.rawDenoise, recipe.chromaDenoise,
            )
            result(info, info[5].toInt())
        }

    fun developBitmap(source: Bitmap, recipe: Recipe, longEdge: Int, seed: Int, context: Context): Developed =
        synchronized(lock) {
            applyLut(recipe, context)
            val params = recipe.params()
            val rgba = if (source.config == Bitmap.Config.ARGB_8888) source else source.copy(Bitmap.Config.ARGB_8888, false)
            val info = TetherNative.developBitmap(
                handle, rgba, params.keys.toTypedArray(), params.values.toFloatArray(), longEdge, recipe.ev, seed, 1f,
            )
            result(info, 1)
        }

    private fun result(info: LongArray, orientation: Int): Developed {
        var out = Bitmap.createBitmap(info[0].toInt(), info[1].toInt(), Bitmap.Config.ARGB_8888)
        TetherNative.copyResult(handle, out)
        val degrees = when (orientation) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        if (degrees != 0f) {
            val turned = Bitmap.createBitmap(out, 0, 0, out.width, out.height, Matrix().apply { postRotate(degrees) }, true)
            out.recycle()
            out = turned
        }
        return Developed(out, TetherNative.lastNote(handle), info[4] / 1000.0)
    }

    private fun applyLut(recipe: Recipe, context: Context) {
        val id = recipe.lut.takeIf { recipe.lutOn }
        val key = id?.let { "$it@${recipe.lutStrength}" }
        if (key == lutKey) return
        val table = id?.let { LutCatalog.table(context, it) }
        TetherNative.setCreativeLut(handle, table, LutCatalog.size, recipe.lutStrength)
        lutKey = key
    }

    companion object {
        @Volatile private var shared: FilmEngine? = null
        @Volatile var failure: String? = null
            private set

        /** The engine, created on first use (compiles the GPU kernels: call off the main thread). */
        fun get(context: Context): FilmEngine = shared ?: synchronized(this) {
            shared ?: runCatching { FilmEngine(TetherNative.engineCreate(context.applicationContext.assets)) }
                .onFailure { failure = it.message ?: it.toString() }
                .getOrThrow().also { shared = it; failure = null }
        }

        val filmStocks: List<String> by lazy { TetherNative.names(false).toList() }
        val papers: List<String> by lazy { TetherNative.names(true).toList() }
    }
}
