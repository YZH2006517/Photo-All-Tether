package org.photoall.tether.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import org.photoall.tether.develop.Prefs
import org.photoall.tether.develop.Roll
import org.photoall.tether.engine.FilmEngine
import org.json.JSONObject
import kotlin.math.abs

/**
 * How a stock's 135 cartridge looks: its label's colours and words (the phone app's table, for
 * the engine's stock names).
 */
object FilmLook {
    class Look(val base: Int, val band: Int, val ink: Int, val maker: String, val title: String, val iso: String, val name: String)

    fun of(index: Int): Look = of(FilmEngine.filmStocks.getOrNull(index) ?: "kodak portra 400")

    fun of(stock: String): Look {
        val n = stock.lowercase().substringBefore(" (").replace(Regex("""\s*(push|pull)\s*\d+"""), "").trim()
        val iso = Regex("\\d{2,4}").findAll(n).lastOrNull()?.value ?: if ("double-x" in n) "250" else ""
        val maker = n.substringBefore(' ').uppercase()
        val title = n.substringAfter(' ', n).replace(iso, "").trim().uppercase().ifEmpty { maker }
        val name = n.split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        fun look(base: Long, band: Long, ink: Long) = Look(base.toInt(), band.toInt(), ink.toInt(), maker, title, iso, name)
        return when {
            "portra" in n -> look(0xffe9dfc6, 0xff5d4a8f, 0xff1c1a1a)
            "ektar" in n -> look(0xffecb220, 0xffb9262b, 0xff1a1a1a)
            "gold" in n -> look(0xffe2a31c, 0xff7f1d1d, 0xff1a1a1a)
            "ultramax" in n -> look(0xffecb220, 0xff1f4fa8, 0xff1a1a1a)
            "kodachrome" in n -> look(0xffecb220, 0xffc0392b, 0xff1a1a1a)
            "ektachrome" in n -> look(0xfff1efe8, 0xff1f4fa8, 0xff161616)
            "tri-x" in n || "double-x" in n -> look(0xffecb220, 0xff161616, 0xff161616)
            "vision" in n -> look(0xff1b1b1c, 0xffecb220, 0xfff2eedf)
            "verita" in n -> look(0xff2a2a2c, 0xffd95f2a, 0xfff2eedf)
            "kodak" in n -> look(0xffecb220, 0xffb9262b, 0xff1a1a1a)
            "velvia" in n -> look(0xff1f7a45, 0xffa82a66, 0xfff7f4ea)
            "provia" in n -> look(0xff1f7a45, 0xff2a5fb0, 0xfff7f4ea)
            "fuji" in n -> look(0xff1f7a45, 0xfff1efe8, 0xfff7f4ea)
            else -> look(0xff8e8e93, 0xff2c2c2e, 0xff161616)
        }
    }

    fun json(l: Look): String = JSONObject().apply {
        put("name", l.name); put("maker", l.maker); put("title", l.title); put("iso", l.iso)
        put("base", hex(l.base)); put("band", hex(l.band)); put("ink", hex(l.ink))
    }.toString()

    fun hex(c: Int) = String.format("%06x", c and 0xffffff)

    /** The phone app's pages take the look in their address. */
    fun query(b: Uri.Builder, l: Look): Uri.Builder = b.appendQueryParameter("name", l.name).appendQueryParameter("maker", l.maker)
        .appendQueryParameter("title", l.title).appendQueryParameter("iso", l.iso).appendQueryParameter("base", hex(l.base))
        .appendQueryParameter("band", hex(l.band)).appendQueryParameter("ink", hex(l.ink))
}

/**
 * The loaded film's cartridge in 3D (the phone app's model on a turntable, assets/cart3d): it
 * turns slowly and floats; a finger spins it; a tap calls [onTap], a double tap opens the full
 * viewer. [setFilm] swaps the label with a turn, [advance] steps the leader for a new frame,
 * [rewind] winds a finished roll back in.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class CartridgeView(context: Context, film: Int, scale: Float = 1f) : FrameLayout(context) {
    var onTap: (() -> Unit)? = null
    private var film = film
    private var ready = false
    private val pending = mutableListOf<String>()
    private var downX = 0f
    private var downY = 0f
    private val web = WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.allowFileAccess = true
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        alpha = 0f
        addJavascriptInterface(object {
            @JavascriptInterface fun ready() = post {
                ready = true
                pending.forEach { evaluateJavascript(it, null) }
                pending.clear()
                animate().alpha(1f).setDuration(500).setInterpolator(Ease.out).start()
            }
            @JavascriptInterface fun tap() = post { Haptic.select(this@CartridgeView); onTap?.invoke() }
            @JavascriptInterface fun open() = post { Haptic.confirm(this@CartridgeView); Cartridge3DActivity.open(context, this@CartridgeView.film) }
        }, "CartBridge")
        val url = FilmLook.query(Uri.parse("file:///android_asset/cart3d/stage.html").buildUpon(), FilmLook.of(film))
            .appendQueryParameter("scale", scale.toString()).build().toString()
        loadUrl(url)
    }

    init {
        addView(web, frameMatch())
        contentDescription = "3D 胶卷暗盒"
    }

    private fun js(code: String) {
        if (ready) web.evaluateJavascript(code, null) else pending += code
    }

    fun setFilm(index: Int, swap: Boolean = true) {
        if (index == film && swap) return
        film = index
        js("stage.look(${JSONObject.quote(FilmLook.json(FilmLook.of(index)))}, $swap)")
    }

    fun advance() = js("stage.advance()")
    fun rewind() = js("stage.rewind()")

    override fun onAttachedToWindow() { super.onAttachedToWindow(); web.onResume(); js("stage.pause(false)") }
    override fun onDetachedFromWindow() { js("stage.pause(true)"); web.onPause(); super.onDetachedFromWindow() }

    // a sideways drag turns the cartridge; an upright one still scrolls the page
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> if (abs(ev.y - downY) > abs(ev.x - downX) * 1.3f && abs(ev.y - downY) > dp(10))
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.dispatchTouchEvent(ev)
    }

    fun destroy() = web.destroy()
}

/**
 * A film's cartridge as a solid to hold, full screen (the phone app's viewer,
 * assets/cartridge3d/index.html): turned with a finger, brought nearer with two, its film drawn
 * out with a double tap.
 */
class Cartridge3DActivity : AdaptiveActivity() {
    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.applyOrientation(this)
        val look = FilmLook.of(intent.getIntExtra(EXTRA_FILM, 4))
        val url = FilmLook.query(Uri.parse("file:///android_asset/cartridge3d/index.html").buildUpon(), look).build().toString()
        val view = WebView(this).apply {
            setBackgroundColor(Color.rgb(20, 19, 17))
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            overScrollMode = View.OVER_SCROLL_NEVER
            loadUrl(url)
        }
        web = view
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(20, 19, 17)) }
        root.addView(view, frameMatch())
        val close = iconButton(Glyph.CLOSE, Ui.TEXT, 40, description = "关闭") { finish() }
        root.addView(close, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.END or Gravity.TOP).apply { setMargins(0, dp(48), dp(16), 0) })
        setContentView(root)
        window.statusBarColor = Color.rgb(20, 19, 17)
        window.navigationBarColor = Color.rgb(20, 19, 17)
    }

    override fun onPause() { web?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web?.onResume() }
    override fun onDestroy() { web?.destroy(); web = null; super.onDestroy() }

    companion object {
        const val EXTRA_FILM = "film"
        fun open(context: Context, film: Int) =
            context.startActivity(Intent(context, Cartridge3DActivity::class.java).putExtra(EXTRA_FILM, film))
    }
}

/**
 * Loading a roll, the ritual (the phone app's camera back, assets/cartridge3d/back.html): the
 * back open, the cartridge dragged into its chamber, the leader pulled to the spool, wound on,
 * the back closed. When the film is in, the roll starts at frame 0.
 */
class LoadFilmActivity : AdaptiveActivity() {
    private var web: WebView? = null
    override val alwaysFullscreen = true
    override val offersRotation = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val film = intent.getIntExtra(EXTRA_FILM, 4)
        val url = FilmLook.query(Uri.parse("file:///android_asset/cartridge3d/back.html").buildUpon(), FilmLook.of(film)).build().toString()
        val view = WebView(this).apply {
            setBackgroundColor(Color.rgb(14, 13, 12))
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addJavascriptInterface(object {
                @JavascriptInterface fun done() = runOnUiThread {
                    Roll.load(this@LoadFilmActivity, film)
                    Haptic.rollDone(this@apply)
                    finishFade()
                }
                @JavascriptInterface fun skip() = runOnUiThread {
                    Roll.load(this@LoadFilmActivity, film)
                    finishFade()
                }
            }, "AndroidBack")
            loadUrl(url)
        }
        web = view
        setContentView(view)
        window.statusBarColor = Color.rgb(14, 13, 12)
        window.navigationBarColor = Color.rgb(14, 13, 12)
        window.decorView.post {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    private fun finishFade() {
        if (isFinishing) return
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onPause() { web?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web?.onResume() }
    override fun onDestroy() { web?.destroy(); web = null; super.onDestroy() }

    companion object {
        const val EXTRA_FILM = "film"
        fun open(context: Context, film: Int) =
            context.startActivity(Intent(context, LoadFilmActivity::class.java).putExtra(EXTRA_FILM, film))
    }
}

@Suppress("unused")
private fun ViewGroup.noClip() { clipChildren = false; clipToPadding = false }
