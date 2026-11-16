package org.photoall.tether.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.photoall.tether.camera.CameraHub
import org.photoall.tether.develop.DevelopQueue
import org.photoall.tether.develop.Library
import org.photoall.tether.develop.Prefs
import org.photoall.tether.engine.Films
import org.photoall.tether.engine.Recipe
import java.util.concurrent.Executors

/**
 * Immersive: nothing but the developed pictures, full screen, system bars hidden. While a
 * camera shoots, each new print takes the screen as it is ready (unless you are looking back
 * through earlier ones: then a note offers to jump to it). Swipe for others, pinch to zoom,
 * tap for the caption, back to leave.
 */
class ImmersiveActivity : AdaptiveActivity() {
    override val alwaysFullscreen = true
    private lateinit var image: ZoomImageView
    private lateinit var fade: ZoomImageView
    private lateinit var caption: LinearLayout
    private lateinit var title: TextView
    private lateinit var detail: TextView
    private lateinit var newNote: TextView
    private lateinit var empty: TextView
    private var prints: List<Library.Entry> = emptyList()
    private var index = 0
    private var token = 0
    /** The next picture shown is a new print: it comes up as in the developer. */
    private var revealNext = false
    private val hideCaption = Runnable { caption.animate().alpha(0f).setDuration(400).start() }
    private val libraryListener: () -> Unit = { onLibrary() }
    private val queueListener: () -> Unit = { refreshDetail() }
    private val hubListener: () -> Unit = { panel?.refresh(prints.getOrNull(index), index, prints.size) }
    private val rollListener: (org.photoall.tether.develop.Roll.Event) -> Unit = { hubListener() }
    /** Landscape only: the column of data beside the picture. */
    private var panel: InfoPanel? = null
    /** The opening is playing: the picture stays hidden until it comes up at its end. */
    private var introRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.applyOrientation(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        fade = ZoomImageView(this)
        image = ZoomImageView(this)
        image.onSwipe = { go(index + it) }
        image.onTap = { toggleCaption() }
        // the picture's corners a little rounded, as a print's
        image.cornerRadius = dp(14).toFloat()
        fade.cornerRadius = dp(14).toFloat()
        root.addView(fade, frameMatch())
        root.addView(image, frameMatch())

        empty = label("等待第一张冲洗完成…\n\n连接相机拍摄，冲好的照片会出现在这里", 15f, Ui.SUB).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.4f)
        }
        root.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        caption = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(10), dp(14))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xAA000000.toInt(), 0x00000000))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = label("", 15f, bold = true).single()
        detail = label("", 12f, Ui.SUB).single().apply { setPadding(0, dp(4), 0, 0) }
        texts.addView(title)
        texts.addView(detail)
        caption.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        caption.addView(label("✕", 22f).apply {
            setPadding(dp(14), dp(4), dp(10), dp(4))
            setOnClickListener { finish() }
        })
        root.addView(caption, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        newNote = label("新照片 ›", 14f, Ui.BG, bold = true).apply {
            background = rounded(Ui.ACCENT, dp(18).toFloat())
            setPadding(dp(16), dp(9), dp(16), dp(9))
            visibility = View.GONE
            setOnClickListener { go(0) }
        }
        root.addView(newNote, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(36) })

        // Landscape: the picture takes four fifths; the last fifth is a column of what is going on.
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            caption.visibility = View.GONE
            image.onTap = { togglePanel() }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xFF000000.toInt()) }
            row.addView(root, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 4f))
            val p = InfoPanel(this) { finish() }
            panel = p
            // the column as a rounded card a little off the edges
            row.addView(p, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(dp(8), dp(8), dp(8), dp(8))
            })
            setContentView(row)
            padForSystemBars(row)
        } else {
            // portrait: the picture above, the same data as a two-column card under it
            caption.visibility = View.GONE
            image.onTap = { togglePanel() }
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF000000.toInt()) }
            column.addView(root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            val p = InfoPanel(this, wideCard = true) { finish() }
            panel = p
            column.addView(p, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(8), dp(8), dp(8), dp(8))
            })
            setContentView(column)
            padForSystemBars(column)
        }
        hideBars()
        prints = Library.prints()
        index = savedInstanceState?.getInt("index") ?: 0
        if (prints.isNotEmpty()) go(index.coerceIn(0, prints.lastIndex), animate = false) else showEmpty()
        // arriving (not turning round): the opening with the cartridge; turned round while it
        // played (the page was built again): it plays again, whole
        val introAgain = savedInstanceState?.getBoolean(KEY_INTRO) == true
        if ((savedInstanceState == null || introAgain) && !Ease.reduced(this)) playIntro()
    }

    /**
     * The opening: on black, the stock's 3D cartridge comes in from far off, spinning, the
     * camera closing on it; the film is drawn out of its lips (felt as a click); then the
     * cartridge rushes past and the picture comes up behind it. A tap skips it.
     */
    private fun playIntro() {
        val decor = window.decorView as? ViewGroup ?: return
        val film = prints.firstOrNull()?.recipe?.let { runCatching { Recipe.fromJson(it).film }.getOrNull() }
            ?: org.photoall.tether.develop.Roll.film(this).takeIf { it >= 0 } ?: Recipe.load(this).film
        val overlay = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()); isClickable = true }
        val side = (minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 0.7f).toInt()
        val cart = CartridgeView(this, film, 1f)
        overlay.addView(cart, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
        val name = label(Films.short(film), 13f, 0xFF8E8E93.toInt(), bold = true).apply { alpha = 0f; letterSpacing = 0.12f }
        overlay.addView(name, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = dp(36) })
        decor.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // the picture waits a little back and dim, to come up when the cartridge goes
        listOf(image, fade).forEach { it.scaleX = 0.94f; it.scaleY = 0.94f }
        image.alpha = 0f

        var done = false
        var ready = false
        introRunning = true
        fun finishIntro() {
            if (done) return
            done = true
            introRunning = false
            cart.animate().scaleX(1.5f).scaleY(1.5f).alpha(0f).setDuration(420).setInterpolator(Ease.out).start()
            name.animate().alpha(0f).setDuration(200).start()
            overlay.animate().alpha(0f).setStartDelay(160).setDuration(520).setInterpolator(Ease.out).withEndAction {
                cart.destroy()
                decor.removeView(overlay)
            }.start()
            listOf(image, fade).forEach { it.animate().scaleX(1f).scaleY(1f).setStartDelay(160).setDuration(700).setInterpolator(Ease.spring).start() }
            image.animate().alpha(1f).setStartDelay(160).setDuration(520).start()
        }
        cart.onReady = {
            ready = true
            cart.intro()
            name.animate().alpha(1f).setStartDelay(500).setDuration(600).start()
            overlay.postDelayed({ if (!done) Haptic.tick(overlay) }, 950)
            overlay.postDelayed({ finishIntro() }, 2100)
        }
        overlay.setOnClickListener { finishIntro() }
        // the model never came (no WebGL): don't keep the picture waiting
        overlay.postDelayed({ if (!ready) finishIntro() }, 2500)
        overlay.postDelayed({ finishIntro() }, 4500)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("index", index)
        outState.putBoolean(KEY_INTRO, introRunning)
    }

    override fun onStart() {
        super.onStart()
        Library.addListener(libraryListener)
        DevelopQueue.addListener(queueListener)
        CameraHub.addListener(hubListener)
        org.photoall.tether.develop.Roll.addListener(rollListener)
        hubListener()
        showing = true
    }

    override fun onStop() {
        Library.removeListener(libraryListener)
        DevelopQueue.removeListener(queueListener)
        CameraHub.removeListener(hubListener)
        org.photoall.tether.develop.Roll.removeListener(rollListener)
        showing = false
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun showEmpty() {
        empty.visibility = View.VISIBLE
        image.setImageBitmap(null)
        title.text = "沉浸模式"
        refreshDetail()
        caption.alpha = 1f
    }

    // A new print arrived: follow it when looking at the newest, otherwise offer it.
    private fun onLibrary() {
        val old = prints
        val now = Library.prints()
        val current = old.getOrNull(index)
        prints = now
        if (now.isEmpty()) return showEmpty()
        val added = now.firstOrNull()?.uri != old.firstOrNull()?.uri
        when {
            current == null || (added && index == 0) -> { revealNext = added; go(0) }
            else -> {
                index = now.indexOfFirst { it.uri == current.uri }.takeIf { it >= 0 } ?: 0
                if (added) newNote.visibility = View.VISIBLE
                refreshDetail()
            }
        }
    }

    private fun go(i: Int, animate: Boolean = true) {
        if (i !in prints.indices) return
        index = i
        if (i == 0) newNote.visibility = View.GONE
        empty.visibility = View.GONE
        val e = prints[i]
        title.text = e.recipe?.let { runCatching { Recipe.fromJson(it).summary() }.getOrNull() } ?: e.name
        refreshDetail()
        showCaption()
        val t = ++token
        val cached = cache.get(e.uri)
        if (cached != null) show(cached, animate) else loader.execute {
            val bmp = load(e)
            runOnUiThread {
                if (t != token) return@runOnUiThread
                if (bmp != null) {
                    cache.put(e.uri, bmp)
                    show(bmp, animate)
                }
            }
        }
        // the neighbours ready for a swipe
        listOf(i - 1, i + 1).mapNotNull { prints.getOrNull(it) }.filter { cache.get(it.uri) == null }.forEach { n ->
            loader.execute { load(n)?.let { cache.put(n.uri, it) } }
        }
    }

    private fun show(bitmap: Bitmap, animate: Boolean) {
        if (revealNext) {
            // a new print: out of the dark, settling from a slight closeness, felt as it lands
            revealNext = false
            fade.setImageDrawable(null)
            image.alpha = 1f
            image.setImageBitmap(bitmap)
            if (!Ease.reduced(this)) {
                image.scaleX = 1.06f; image.scaleY = 1.06f
                image.animate().scaleX(1f).scaleY(1f).setDuration(2600).setInterpolator(Ease.out).start()
                image.develop(2400)
            }
            Haptic.success(image)
            return
        }
        if (animate && image.drawable != null) {
            fade.setImageDrawable(image.drawable)
            fade.alpha = 1f
            image.alpha = 0f
            image.setImageBitmap(bitmap)
            image.animate().alpha(1f).setDuration(350).start()
            fade.animate().alpha(0f).setDuration(350).start()
        } else {
            image.alpha = if (introRunning) 0f else 1f
            image.setImageBitmap(bitmap)
        }
    }

    private fun load(e: Library.Entry): Bitmap? = runCatching {
        val target = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 3 / 2
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, Uri.parse(e.uri))) { d, info, _ ->
            val long = maxOf(info.size.width, info.size.height)
            if (long > target) d.setTargetSampleSize(Integer.highestOneBit(long / target).coerceAtLeast(1))
        }
    }.getOrNull()

    private fun refreshDetail() {
        val active = DevelopQueue.jobs.firstOrNull { !it.finished }
        val parts = mutableListOf<String>()
        if (prints.isNotEmpty()) parts += "${index + 1} / ${prints.size}"
        prints.getOrNull(index)?.let { e ->
            e.meta["model"]?.let { parts += it }
            listOfNotNull(
                e.meta["aperture"]?.toFloatOrNull()?.takeIf { it > 0 }?.let { "f/%.1f".format(it) },
                e.meta["shutter"]?.toDoubleOrNull()?.takeIf { it > 0 }?.let { if (it >= 0.3) "%.1fs".format(it) else "1/${Math.round(1 / it)}" },
                e.meta["iso"]?.let { "ISO $it" },
            ).takeIf { it.isNotEmpty() }?.let { parts += it.joinToString(" ") }
        }
        if (active != null) parts += "正在${active.status.text} ${active.title}"
        else if (CameraHub.state == CameraHub.State.CONNECTED) parts += "联机中"
        detail.text = parts.joinToString("  ·  ")
        panel?.refresh(prints.getOrNull(index), index, prints.size)
    }

    // the column slides away for the picture alone, and back
    private fun togglePanel() {
        val p = panel ?: return
        Haptic.tick(p)
        // landscape: off to the right; portrait: down off the foot
        val side = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (p.visibility == View.VISIBLE) {
            val a = p.animate().alpha(0f).setDuration(260).setInterpolator(Ease.out)
            if (side) a.translationX(p.width.toFloat()) else a.translationY(p.height.toFloat())
            a.withEndAction { p.visibility = View.GONE }.start()
        } else {
            p.visibility = View.VISIBLE
            if (side) p.translationX = dp(80).toFloat() else p.translationY = dp(80).toFloat()
            p.alpha = 0f
            p.animate().translationX(0f).translationY(0f).alpha(1f).setDuration(460).setInterpolator(Ease.spring).start()
        }
    }

    private fun showCaption() {
        caption.removeCallbacks(hideCaption)
        caption.animate().cancel()
        caption.alpha = 1f
        caption.postDelayed(hideCaption, 3000)
    }

    private fun toggleCaption() {
        if (caption.alpha > 0.5f) {
            caption.removeCallbacks(hideCaption)
            caption.animate().alpha(0f).setDuration(200).start()
        } else showCaption()
    }

    companion object {
        /** True while the immersive view is on screen. */
        @Volatile var showing = false
            private set
        private const val KEY_INTRO = "intro"
        private val loader = Executors.newSingleThreadExecutor()
        private val cache = object : LruCache<String, Bitmap>(96 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }

        fun open(context: Context) {
            context.startActivity(Intent(context, ImmersiveActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }
}
