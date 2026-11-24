package org.photoall.tether.develop

import android.content.Context

/** App settings besides the recipe. */
object Prefs {
    /** The develop's long edge; [FULL_SIZE]: the sensor's own (as large as the GPU's images go). */
    const val FULL_SIZE = 1 shl 16
    val longEdges = listOf(2048, 3072, 4096, FULL_SIZE)
    val longEdgeNames = listOf("2048", "3072", "4096", "原尺寸")

    private fun p(context: Context) = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** New shots are developed as they arrive. */
    fun tether(c: Context) = p(c).getBoolean("tether", true)
    fun setTether(c: Context, on: Boolean) = p(c).edit().putBoolean("tether", on).apply()

    /** A shot that came without a raw (JPEG only) is developed from its JPEG. */
    fun developJpeg(c: Context) = p(c).getBoolean("developJpeg", true)
    fun setDevelopJpeg(c: Context, on: Boolean) = p(c).edit().putBoolean("developJpeg", on).apply()

    /** The original (NEF) is kept on the phone next to the print. */
    fun keepOriginal(c: Context) = p(c).getBoolean("keepOriginal", true)
    fun setKeepOriginal(c: Context, on: Boolean) = p(c).edit().putBoolean("keepOriginal", on).apply()

    /** Full size unless chosen otherwise: every pixel the sensor recorded. */
    fun longEdgeIndex(c: Context) = p(c).getInt("longEdge", longEdges.lastIndex).coerceIn(0, longEdges.lastIndex)
    fun setLongEdgeIndex(c: Context, i: Int) = p(c).edit().putInt("longEdge", i).apply()
    fun longEdge(c: Context) = longEdges[longEdgeIndex(c)]

    /** Each print also as a 16-bit TIFF (lossless, every gradation the engine computed), beside the JPEG. */
    fun lossless(c: Context) = p(c).getBoolean("lossless", false)
    fun setLossless(c: Context, on: Boolean) = p(c).edit().putBoolean("lossless", on).apply()

    /** Prints in Display P3 (wider gamut, the JPEG carries its profile) instead of sRGB. */
    fun displayP3(c: Context) = p(c).getBoolean("displayP3", false)
    fun setDisplayP3(c: Context, on: Boolean) = p(c).edit().putBoolean("displayP3", on).apply()

    fun jpegQuality(c: Context) = p(c).getInt("jpegQuality", 94).coerceIn(70, 100)
    fun setJpegQuality(c: Context, q: Int) = p(c).edit().putInt("jpegQuality", q).apply()

    /** A print finished while tethering opens the immersive view. */
    fun autoImmersive(c: Context) = p(c).getBoolean("autoImmersive", false)
    fun setAutoImmersive(c: Context, on: Boolean) = p(c).edit().putBoolean("autoImmersive", on).apply()

    /** The screen stays on while a camera is connected. */
    /** The immersive view: a new print comes up as the engine renders each step of it. */
    fun revealSteps(c: Context) = p(c).getBoolean("revealSteps", true)
    fun setRevealSteps(c: Context, on: Boolean) = p(c).edit().putBoolean("revealSteps", on).apply()

    /** The immersive view: while a new shot comes over and develops, the picture's place shows live figures. */
    fun liveInfo(c: Context) = p(c).getBoolean("liveInfo", true)
    fun setLiveInfo(c: Context, on: Boolean) = p(c).edit().putBoolean("liveInfo", on).apply()
    /** Which figures (ids of org.photoall.tether.ui.LiveInfo.items). */
    fun liveItems(c: Context): Set<String> = p(c).getStringSet("liveItems", null)?.toSet()
        ?: setOf("transfer", "develop", "gpu", "memory", "app")
    fun setLiveItems(c: Context, ids: Set<String>) = p(c).edit().putStringSet("liveItems", ids).apply()

    fun keepScreenOn(c: Context) = p(c).getBoolean("keepScreenOn", true)
    fun setKeepScreenOn(c: Context, on: Boolean) = p(c).edit().putBoolean("keepScreenOn", on).apply()

    /** 0 follows the system, 1 dark, 2 light. */
    val themeNames = listOf("跟随系统", "深色", "浅色")
    fun theme(c: Context) = p(c).getInt("theme", 1).coerceIn(0, 2)
    fun setTheme(c: Context, t: Int) = p(c).edit().putInt("theme", t).apply()

    /** The app hides the system bars (swipe from an edge brings them back for a moment). */
    fun fullscreen(c: Context) = p(c).getBoolean("fullscreen", true)
    fun setFullscreen(c: Context, on: Boolean) = p(c).edit().putBoolean("fullscreen", on).apply()

    /** 0 rotates with the phone (as the system's rotation lock allows), 1 portrait, 2 landscape. */
    val orientationNames = listOf("自动旋转", "竖屏", "横屏")
    fun orientation(c: Context) = p(c).getInt("orientation", 0).coerceIn(0, 2)
    fun setOrientation(c: Context, o: Int) = p(c).edit().putInt("orientation", o).apply()

    fun applyOrientation(activity: android.app.Activity) {
        activity.requestedOrientation = when (orientation(activity)) {
            1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }
}
