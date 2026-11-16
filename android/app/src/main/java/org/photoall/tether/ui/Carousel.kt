package org.photoall.tether.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.photoall.tether.engine.FilmEngine
import kotlin.math.abs

/**
 * How a paper is packed, for its 3D model (assets/cart3d/papermodel.js): the six papers come in
 * 8 x 10 in boxes, each with its maker's band and its own accent; the three print films
 * (2383, 2393, 2302) come in cans, labelled and taped.
 */
object PaperLook {
    fun json(index: Int): JSONObject {
        val n = (FilmEngine.papers.getOrNull(index) ?: "").lowercase()
        val o = JSONObject()
        fun kodakBox(product: String, accent: String, surface: String, gloss: Double) {
            o.put("kind", "box"); o.put("maker", "KODAK PROFESSIONAL"); o.put("line", "COLOR PAPER")
            o.put("product", product); o.put("sub", "Digital & Optical Color Paper"); o.put("surface", surface)
            o.put("base", "#f3f0e8"); o.put("band", "#f2b705"); o.put("bandInk", "#141414"); o.put("rule", "#c8102e")
            o.put("accent", accent); o.put("ink", "#141414"); o.put("gloss", gloss)
        }
        fun can(line: String, code: String, labelBase: String, labelInk: String, tape: String, tapeInk: String) {
            o.put("kind", "can"); o.put("maker", "KODAK"); o.put("line", line); o.put("code", code)
            o.put("labelBase", labelBase); o.put("labelInk", labelInk); o.put("tape", tape); o.put("tapeInk", tapeInk)
        }
        when {
            "premier" in n && "endura" in n -> kodakBox("ENDURA PREMIER", "#1f3f8f", "LUSTRE E", 0.35)
            "supra" in n -> kodakBox("SUPRA ENDURA", "#b0124f", "LUSTRE E", 0.35)
            "ultra" in n -> kodakBox("ULTRA ENDURA", "#e0501a", "GLOSSY F", 0.6)
            "portra" in n -> kodakBox("PORTRA ENDURA", "#5d4a8f", "LUSTRE E", 0.35)
            "ektacolor" in n -> kodakBox("EKTACOLOR EDGE", "#0f7f7a", "GLOSSY F", 0.6)
            "crystal" in n -> {
                o.put("kind", "box"); o.put("maker", "FUJIFILM"); o.put("line", "PROFESSIONAL")
                o.put("product", "CRYSTAL ARCHIVE"); o.put("sub", "Type II · Color Paper"); o.put("surface", "LUSTER")
                o.put("base", "#f7f7f3"); o.put("band", "#00843d"); o.put("bandInk", "#ffffff"); o.put("rule", "#d7d7d0")
                o.put("accent", "#00843d"); o.put("ink", "#0d3b24"); o.put("gloss", 0.35)
            }
            "2393" in n -> can("VISION PREMIER COLOR PRINT FILM", "2393", "#1a1a1b", "#f2b705", "#1a1a1b", "#f2b705")
            "2302" in n -> can("BLACK-AND-WHITE RELEASE POSITIVE FILM", "2302", "#f4f2ec", "#141414", "#f4f2ec", "#141414")
            else -> can("VISION COLOR PRINT FILM", "2383", "#f2b705", "#141414", "#f2b705", "#141414")
        }
        return o
    }

    fun all(): JSONArray = JSONArray().apply {
        for (i in 0 until runCatching { FilmEngine.papers.size }.getOrDefault(0)) put(json(i))
    }
}

/**
 * The films or the papers as a row of 3D models to swipe through (assets/cart3d/carousel.html).
 * The one that settles in the middle is chosen ([onSelect]); [onCenter] as each passes the
 * middle (for the name under it and a tick); a tap on the middle one: [onOpen]. A sideways drag
 * is the row's; an upright one still scrolls the page.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class CarouselView(context: Context, paper: Boolean, items: JSONArray, selected: Int, footDp: Int? = null) : FrameLayout(context) {
    var onSelect: ((Int) -> Unit)? = null
    var onCenter: ((Int) -> Unit)? = null
    var onOpen: ((Int) -> Unit)? = null
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
                animate().alpha(1f).setDuration(450).setInterpolator(Ease.out).start()
            }
            @JavascriptInterface fun center(i: Int) = post { Haptic.tick(this@CarouselView); onCenter?.invoke(i) }
            @JavascriptInterface fun select(i: Int) = post { onSelect?.invoke(i) }
            @JavascriptInterface fun open(i: Int) = post { Haptic.select(this@CarouselView); onOpen?.invoke(i) }
        }, "CarouselBridge")
        val b = Uri.parse("file:///android_asset/cart3d/carousel.html").buildUpon()
            .appendQueryParameter("kind", if (paper) "paper" else "film")
        // the page's CSS px are the view's dp
        footDp?.let { b.appendQueryParameter("foot", it.toString()) }
        loadUrl(b.build().toString())
    }

    init {
        addView(web, frameMatch())
        js("carousel.init(${JSONObject.quote(items.toString())}, $selected)")
    }

    private fun js(code: String) {
        if (ready) web.evaluateJavascript(code, null) else pending += code
    }

    /** Brings [index] to the middle (chosen elsewhere): glides there when [animate]. */
    fun go(index: Int, animate: Boolean = true) = js("carousel.go($index, $animate)")

    override fun onAttachedToWindow() { super.onAttachedToWindow(); web.onResume(); js("carousel.pause(false)") }
    override fun onDetachedFromWindow() { js("carousel.pause(true)"); web.onPause(); super.onDetachedFromWindow() }

    /** The gesture so far: not yet decided, the row's (sideways), or the page's (upright). */
    private var claim = 0
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    // The first clear direction decides the whole gesture: once it has gone sideways it stays
    // the row's, however much it then drifts up or down (a diagonal swipe keeps swiping); only a
    // finger that goes clearly up or down first hands it to the page's scroll.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; claim = 0
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (claim == 0) {
                val dx = abs(ev.x - downX)
                val dy = abs(ev.y - downY)
                when {
                    dx > slop && dx >= dy * 0.7f -> claim = 1
                    dy > slop * 1.5f && dy > dx * 1.6f -> { claim = 2; parent?.requestDisallowInterceptTouchEvent(false) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> claim = 0
        }
        if (claim == 1) parent?.requestDisallowInterceptTouchEvent(true)
        return super.dispatchTouchEvent(ev)
    }

    fun destroy() = web.destroy()
}
