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
 * through earlier ones: then a note offers to jump to it). Swipe across for others, pinch to
 * zoom, tap for the caption, back to leave. Swiped down, the recipe drops in over the picture;
 * swiped up, the picture becomes the tuning bench's (the original against its develop) with the
 * bench's controls risen under or beside it: both without leaving the view.
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
    private val queueListener: () -> Unit = { refreshDetail(); checkLive() }
    private val hubListener: () -> Unit = { panel?.refresh(prints.getOrNull(index), index, prints.size) }
    private val rollListener: (org.photoall.tether.develop.Roll.Event) -> Unit = { hubListener() }
    /** Landscape only: the column of data beside the picture. */
    private var panel: InfoPanel? = null
    /** The opening is playing: the picture stays hidden until it comes up at its end. */
    private var introRunning = false
    /** The picture's place (the stage): the print, or the tuning bench's comparison while it is open. */
    private lateinit var stage: FrameLayout
    /** Everything, with room over it for the sheets. */
    private val shell by lazy { FrameLayout(this).apply { setBackgroundColor(Ui.BG) } }
    private val wide get() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    /** The sheet open (recipe or tuning), its parts, and the back callback while it is. */
    private var sheet: View? = null
    private var bench: TuneBench? = null
    private var sheetBack: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.applyOrientation(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // light or dark with the app's appearance (Ui.BG: black, or the light theme's grey-white)
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        stage = root
        // the picture off the screen's edges, rounded, as a print laid down
        stagePad = if (wide) intArrayOf(dp(16), dp(14), dp(10), dp(14)) else intArrayOf(dp(14), dp(22), dp(14), dp(6))
        root.setPadding(stagePad[0], stagePad[1], stagePad[2], stagePad[3])
        root.clipToPadding = true
        fade = ZoomImageView(this)
        image = ZoomImageView(this)
        // across: the picture follows the finger, the next one beside it (a pager);
        // up: the recipe, tuned on this picture; down: out of the immersive view
        image.canSwipe = { d -> sheet == null && (index + d) in prints.indices }
        image.onPeek = { tx -> peek(tx) }
        image.onSwipe = { d -> turn(d) }
        image.onVertical = { dir ->
            when {
                sheet != null -> { closeSheet(); image.settle() } // the sheet open: the swipe puts it away
                dir > 0 -> openDesk()
                else -> leaveDown()
            }
        }
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

        newNote = label("新照片 ›", 14f, Ui.ON_ACCENT, bold = true).apply {
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
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Ui.BG) }
            row.addView(root, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 4f))
            val p = InfoPanel(this) { finish() }
            panel = p
            // the column as a rounded card a little off the edges
            row.addView(p, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(dp(8), dp(8), dp(8), dp(8))
            })
            shell.addView(row, frameMatch())
            setContentView(shell)
            padForSystemBars(row)
        } else {
            // portrait: the picture above, the same data as a two-column card under it
            caption.visibility = View.GONE
            image.onTap = { togglePanel() }
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
            column.addView(root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            val p = InfoPanel(this, wideCard = true) { finish() }
            panel = p
            column.addView(p, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(8), dp(8), dp(8), dp(8))
            })
            shell.addView(column, frameMatch())
            setContentView(shell)
            padForSystemBars(column)
        }
        hideBars()
        prints = Library.prints()
        index = savedInstanceState?.getInt("index") ?: 0
        if (prints.isNotEmpty()) go(index.coerceIn(0, prints.lastIndex), animate = false) else showEmpty()
        if (java.io.File(filesDir, "demo_live").exists()) image.postDelayed({ demoLive() }, 900)
        // arriving (not turning round): the opening with the cartridge; turned round while it
        // played (the page was built again): it plays again, whole
        val introAgain = savedInstanceState?.getBoolean(KEY_INTRO) == true
        // once each time the app is started (not each time this view opens); again only if turned round mid-way
        if (((savedInstanceState == null && !introPlayed) || introAgain) && !Ease.reduced(this)) {
            introPlayed = true
            playIntro()
        }
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
        val overlay = FrameLayout(this).apply { setBackgroundColor(Ui.BG); isClickable = true }
        val side = (minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 0.7f).toInt()
        val cart = CartridgeView(this, film, 1f)
        overlay.addView(cart, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
        val name = label(Films.short(film), 13f, Ui.SUB, bold = true).apply { alpha = 0f; letterSpacing = 0.12f }
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
        // the engine's steps for each new print, unless turned off in 设置
        org.photoall.tether.develop.Reveal.wanted = Prefs.revealSteps(this)
        // the engine's frames, as it renders them, to the darkroom following that shot
        org.photoall.tether.develop.Reveal.sink = { id, f -> live?.takeIf { it.job.id == id }?.push(f) }
        checkLive()
    }

    override fun onStop() {
        Library.removeListener(libraryListener)
        DevelopQueue.removeListener(queueListener)
        CameraHub.removeListener(hubListener)
        org.photoall.tether.develop.Roll.removeListener(rollListener)
        showing = false
        org.photoall.tether.develop.Reveal.wanted = false
        org.photoall.tether.develop.Reveal.sink = null
        org.photoall.tether.develop.Reveal.clear()
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

    private fun go(i: Int, animate: Boolean = true, have: Bitmap? = null) {
        if (i !in prints.indices) return
        index = i
        if (i == 0) newNote.visibility = View.GONE
        empty.visibility = View.GONE
        val e = prints[i]
        title.text = e.recipe?.let { runCatching { Recipe.fromJson(it).summary() }.getOrNull() } ?: e.name
        refreshDetail()
        showCaption()
        val t = ++token
        val cached = have?.also { cache.put(e.uri, it) } ?: cache.get(e.uri)
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

    // ---- the live readout: while a new shot comes over and develops ------------------------------

    private var live: LiveInfo? = null
    private val liveSeen = mutableSetOf<Long>()
    @Volatile private var revealing = false
    private var revealEnded = 0L

    /**
     * A new shot on its way and the newest print on screen: the darkroom takes the picture's
     * place for it (LiveInfo): the engine's frames of each step as they are rendered (设置 冲洗动画)
     * and, in the middle, the process live (设置 新片实时信息), the two in step, until the print is
     * ready. Not over a sheet, the opening, or a print just come up.
     */
    private fun checkLive() {
        if (live != null || !showing) return
        val info = Prefs.liveInfo(this)
        val frames = Prefs.revealSteps(this) && org.photoall.tether.develop.Reveal.wanted
        if (!info && !frames) return
        if (sheet != null || introRunning || revealing) return
        if (prints.isNotEmpty() && index != 0) return
        if (System.currentTimeMillis() - revealEnded < 2500) return
        val job = DevelopQueue.jobs.firstOrNull {
            !it.finished && it.develop && it.id !in liveSeen && it.source is DevelopQueue.Source.Camera
        } ?: return
        liveSeen += job.id
        val v = LiveInfo(this, job, showInfo = info, showFrames = frames)
        v.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
        }
        v.clipToOutline = true
        v.onIdle = { hideLive() }
        if (info) v.setOnClickListener { hideLive() } // a tap: back to the picture
        live = v
        // under the glass until the engine's first frame: the picture that was on screen
        v.backdrop((image.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap)
        stage.addView(v, stage.indexOfChild(image) + 1, frameMatch())
        if (info) { v.alpha = 0f; v.animate().alpha(1f).setDuration(320).start() }
        newNote.visibility = View.GONE
    }

    /**
     * Debug builds only (a file demo_live in the app's files; demo_hold stops it part way): the live readout with a made-up shot going
     * through its steps, the picture on screen standing in for the engine's frames, to look at it
     * without a camera.
     */
    private fun demoLive() {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val job = DevelopQueue.Job(Library.nextId(), "DSC_1380", DevelopQueue.Source.Local(Uri.EMPTY, "DSC_1380.NEF"), true, false, Recipe.load(this))
        job.fileName = "DSC_1380.NEF"
        job.totalBytes = 30L * 1048576
        job.status = DevelopQueue.Status.DOWNLOADING
        job.transferStarted = System.currentTimeMillis()
        val steps = org.photoall.tether.develop.Reveal.plan(job.recipe)
        job.plan = listOf("传输", "解码 RAW") + steps + listOf("全尺寸冲洗", "存储")
        job.at("传输")
        val v = LiveInfo(this, job, showInfo = Prefs.liveInfo(this), showFrames = Prefs.revealSteps(this))
        v.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
        }
        v.clipToOutline = true
        live = v
        val bmp = (image.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        v.backdrop(bmp)
        stage.addView(v, stage.indexOfChild(image) + 1, frameMatch())
        val h = android.os.Handler(mainLooper)
        var t = 0
        val tick = object : Runnable {
            override fun run() {
                t++
                if (job.status == DevelopQueue.Status.DOWNLOADING) {
                    job.bytes = minOf(job.totalBytes, job.bytes + 3L * 1048576)
                    if (job.bytes >= job.totalBytes) {
                        job.transferEnded = System.currentTimeMillis()
                        job.status = DevelopQueue.Status.DEVELOPING
                        job.developStarted = System.currentTimeMillis()
                        job.at("解码 RAW")
                    }
                } else if (t % 6 == 0 && job.step < job.plan.lastIndex) {
                    job.step++
                    job.stepStarted = System.currentTimeMillis()
                    val name = job.plan[job.step]
                    if (bmp != null && name in steps) v.push(org.photoall.tether.develop.Reveal.Frame(bmp, name))
                }
                if (java.io.File(filesDir, "demo_hold").exists() && job.step >= 4) return
                if (job.step < job.plan.lastIndex) h.postDelayed(this, 150) else { job.developEnded = System.currentTimeMillis(); v.finish { stage.removeView(v); live = null } }
            }
        }
        h.postDelayed(tick, 400)
    }

    private fun hideLive() {
        val v = live ?: return
        live = null
        v.onIdle = null
        v.animate().alpha(0f).setDuration(320).withEndAction { stage.removeView(v) }.start()
    }

    // ---- the pager: across between prints, the picture following the finger --------------------

    private val pageGap get() = dp(20).toFloat()
    /** Which neighbour is beside the picture now (+1 the next, -1 the one before, 0 none). */
    private var peekDir = 0

    /** The picture is [tx] off across: the neighbour on the side it uncovers comes in beside it. */
    private fun peek(tx: Float) {
        val d = if (tx < 0f) 1 else if (tx > 0f) -1 else 0
        if (d == 0 || (index + d) !in prints.indices) { clearPeek(); return }
        if (d != peekDir) {
            peekDir = d
            fade.animate().cancel()
            fade.alpha = 1f
            val n = cache.get(prints[index + d].uri)
            if (n != null) fade.setImageBitmap(n) else fade.setImageDrawable(null)
        }
        fade.translationX = tx + d * (image.width + pageGap)
    }

    private fun clearPeek() {
        if (peekDir == 0 && fade.translationX == 0f) return
        peekDir = 0
        fade.setImageDrawable(null)
        fade.translationX = 0f
    }

    /** Let go toward a side: the picture goes on out, the neighbour comes the rest of the way in. */
    private fun turn(d: Int) {
        val i = index + d
        if (i !in prints.indices) return image.settle()
        if (Ease.reduced(this)) { image.translationX = 0f; clearPeek(); return go(i) }
        Haptic.tick(image)
        peek(image.translationX)
        image.busy = true
        val w = image.width + pageGap
        val from = image.translationX
        val to = -d * w
        val next = cache.get(prints[i].uri)
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (170 + 190 * kotlin.math.abs(to - from) / w).toLong()
            interpolator = Ease.out
            addUpdateListener {
                image.translationX = from + (to - from) * (it.animatedValue as Float)
                fade.translationX = image.translationX + d * w
            }
            doOnEnd {
                // the neighbour is the picture now: the same one where it came to rest (held
                // through the turn, so the cache letting it go meanwhile cannot bring the old one back)
                image.busy = false
                if (next != null) image.setImageBitmap(next) else image.setImageDrawable(null)
                image.translationX = 0f
                go(i, animate = false, have = next)
                clearPeek()
            }
            start()
        }
    }

    private inline fun android.animation.Animator.doOnEnd(crossinline run: () -> Unit) =
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = run()
        })

    /** Swiped down: the picture goes on down, and the immersive view with it. */
    private fun leaveDown() {
        Haptic.tick(image)
        image.animate().translationY(image.height * 0.6f).alpha(0f).setDuration(220).setInterpolator(Ease.out).withEndAction {
            finish()
            @Suppress("DEPRECATION") overridePendingTransition(0, android.R.anim.fade_out)
        }.start()
    }

    private fun show(bitmap: Bitmap, animate: Boolean) {
        if (revealNext) {
            // a new print: as the engine rendered it coming up (negative, then the print in the
            // developer), when it did; otherwise out of the dark, settling, felt as it lands
            val frames = prints.getOrNull(index)?.let { org.photoall.tether.develop.Reveal.take(it.uri) }
            val darkroom = live
            if (darkroom != null) {
                // the darkroom has shown its steps as they were made: the print goes under it,
                // its last frames play out and it fades away over the print
                revealNext = false
                revealing = true
                clearPeek()
                fade.setImageDrawable(null)
                image.alpha = 1f
                image.setImageBitmap(bitmap)
                darkroom.finish {
                    stage.removeView(darkroom)
                    if (live === darkroom) live = null
                    revealing = false
                    revealEnded = System.currentTimeMillis()
                    Haptic.success(image)
                    checkLive()
                }
                return
            }
            if (frames != null && frames.isNotEmpty() && !Ease.reduced(this)) {
                revealNext = false
                playReveal(frames, bitmap)
                return
            }
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
        clearPeek()
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

    /**
     * The engine's own frames of a new print, one after another: the negative held a moment,
     * then the print's density building in the developer, each frame cross-fading into the next,
     * and last the print itself at full size. A swipe or a new picture stops it.
     */
    private fun playReveal(frames: List<org.photoall.tether.develop.Reveal.Frame>, print: Bitmap) {
        val t = token
        revealing = true
        val stage = label("", 12f, 0xFFFFFFFF.toInt(), bold = true).apply {
            background = rounded(0x99000000.toInt(), dp(12).toFloat())
            setPadding(dp(12), dp(5), dp(12), dp(5))
            letterSpacing = 0.08f
            alpha = 0f
        }
        val host = image.parent as ViewGroup
        host.addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(16), 0, 0, dp(16)) })
        stage.animate().alpha(1f).setDuration(200).start()
        fade.setImageDrawable(null)
        fade.alpha = 1f
        image.alpha = 1f
        image.scaleX = 1.04f; image.scaleY = 1.04f
        image.animate().scaleX(1f).scaleY(1f).setDuration(3200L).setInterpolator(Ease.out).start()
        fun end() {
            host.removeView(stage)
            fade.setImageDrawable(null)
            revealing = false
            revealEnded = System.currentTimeMillis()
        }
        fun step(i: Int) {
            if (t != token || isFinishing) { end(); return }
            val last = i >= frames.size
            val next = if (last) print else frames[i].bitmap
            stage.text = if (last) "成片" else frames[i].label
            // the frame on screen goes underneath; the next fades in over it
            fade.setImageDrawable(image.drawable)
            fade.alpha = 1f
            image.alpha = 0f
            image.setImageBitmap(next)
            val first = i == 0
            image.animate().alpha(1f).setDuration(if (first) 260L else if (!last && frames[i].quick) 150L else 300L).withEndAction {
                if (last) {
                    Haptic.success(image)
                    stage.animate().alpha(0f).setStartDelay(500).setDuration(300).withEndAction { end() }.start()
                    return@withEndAction
                }
                // each step of the engine held a moment, so it reads; the developer's frames run on
                val hold = when {
                    frames[i].quick -> 40L
                    frames[i].label == "负片" -> 700L
                    else -> 480L
                }
                image.postDelayed({ step(i + 1) }, hold)
            }.start()
        }
        image.setImageDrawable(null)
        step(0)
    }

    // ---- the recipe sheet (up), tuned on the picture, inside the immersive view --------------------

    private var stagePad = intArrayOf(0, 0, 0, 0)

    /** The print on screen and its original (kept on the phone), when there is one. */
    private fun current(): Library.Entry? = prints.getOrNull(index)
    private fun originalOf(e: Library.Entry): Library.Entry? =
        e.original?.let { Library.byUri(it) } ?: e.cameraKey?.let { Library.originalOf(it) }

    /**
     * Swiped up: the recipe, tuned on this picture (one sheet, where the recipe and the tuning
     * bench were two). The picture's place becomes the bench's comparison, a clean original
     * against its develop with the recipe as it is tuned, so every change shows. The original,
     * best first: this photo's own on the phone; its RAW (or JPEG) still on the camera (copied
     * over now); the camera's latest RAW when this is that shot. With none of this photo's own,
     * a stand-in (the camera's latest RAW, the newest original on the phone, or last this print
     * itself, film and all) shows what the recipe does, and the recipe starts as it is now.
     * 应用 makes it the recipe; 应用并重冲 develops this one again too (when its original is here).
     */
    private fun openDesk() {
        hideLive()
        val e = current() ?: return image.settle()
        if (sheet != null) return image.settle()
        Haptic.tick(image)
        image.settle()
        val original = originalOf(e)
        val onCamera = if (original != null) null else e.cameraKey?.takeIf { it.isNotEmpty() }?.let { k ->
            CameraHub.items.firstOrNull { it.key == k }?.let { it.raw ?: it.jpeg }
        }?.takeIf { CameraHub.cachedOriginal(it) != null || CameraHub.state == CameraHub.State.CONNECTED }
        val latest = if (original == null && onCamera == null && org.photoall.tether.develop.Preview.latestPrint == e.uri)
            org.photoall.tether.develop.Preview.source(this) else null
        val own = original != null || onCamera != null || latest != null
        val recipe = if (own) e.recipe?.let { runCatching { Recipe.fromJson(it) }.getOrNull() } ?: Recipe.load(this)
            else Recipe.load(this)
        val b = TuneBench(this, recipe, object : TuneBench.Host {
            override val pickSource: (() -> Unit)? = null
            override val alive: Boolean get() = !isFinishing && bench != null
        }, extraAction = original?.let { o ->
            "应用并重冲" to {
                DevelopQueue.redevelop(this, o)
                Hud.show(this, Glyph.WAND, "重冲中")
                closeSheet()
            }
        })
        bench = b
        // the picture: the bench's comparison in the print's place
        stage.addView(b.frame, frameMatch())
        b.frame.background = null
        b.frame.alpha = 0f
        b.frame.animate().alpha(1f).setDuration(260).start()
        image.animate().alpha(0f).setDuration(200).start()
        when {
            original != null -> b.loadUri(Uri.parse(original.uri), original.name)
            onCamera != null -> {
                b.showEmpty("从相机取这张的原片（${onCamera.filename}）", title = "正在取原片…", waiting = true)
                val app = applicationContext
                Thread {
                    val file = runCatching {
                        java.io.File(java.io.File(app.cacheDir, "tune").apply { mkdirs() }, "cam." + onCamera.extension.lowercase()).also { f ->
                            f.outputStream().buffered(1 shl 20).use { out -> CameraHub.download(onCamera, out) { _, _ -> } }
                        }
                    }
                    runOnUiThread {
                        if (bench !== b) return@runOnUiThread
                        file.onSuccess { b.load(it, onCamera.filename) }
                            .onFailure { standIn(b, e, "取不到原片：${it.message}") }
                    }
                }.apply { name = "desk-fetch" }.start()
            }
            latest != null -> b.load(latest, e.name.substringBefore('_'))
            else -> standIn(b, e, null)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Ui.BG, dp(22).toFloat(), 1, Ui.LINE)
            elevation = dp(16).toFloat()
            setPadding(dp(12), dp(6), dp(12), dp(10))
            isClickable = true
        }
        // the head: the title, the 常用 / 完整配方 switch beside it, the close button
        body.addView(sheetHead("配方", b.modes), matchWrap().apply { bottomMargin = dp(8) })
        body.addView(b.scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(b.actions, matchWrap().apply { topMargin = dp(8) })
        showSheet(body)
    }

    /**
     * A sheet's head: its grip (drag it down to put the sheet away), the title, [beside] it
     * (the modes switch), and a close button, all on one line.
     */
    /**
     * No original of this photo to hand: the recipe shown on a stand-in, so a change still shows
     * (the camera's latest RAW, else the newest original on the phone, else this print itself).
     */
    private fun standIn(b: TuneBench, e: Library.Entry, why: String?) {
        if (why != null) Hud.show(this, Glyph.SLIDERS, why)
        val latest = org.photoall.tether.develop.Preview.source(this)
        val newest = Library.originals().firstOrNull()
        when {
            latest != null -> b.load(latest, latest.name, label = "替身：相机最近一张")
            newest != null -> b.loadUri(Uri.parse(newest.uri), newest.name, label = "替身：" + newest.name.substringBeforeLast('.'))
            else -> b.loadUri(Uri.parse(e.uri), e.name, label = "本张成片（已含胶片）")
        }
    }

    private fun sheetHead(title: String, beside: View): View {
        val head = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val grip = View(this).apply { background = rounded(Ui.DIM, dp(3).toFloat()) }
        val gripBox = FrameLayout(this).apply {
            addView(grip, FrameLayout.LayoutParams(dp(40), dp(5), Gravity.CENTER))
            minimumHeight = dp(18)
        }
        head.addView(gripBox, matchWrap())
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(label(title, Ui.TITLE, Ui.TEXT, bold = true).single().apply { setPadding(dp(4), 0, 0, 0) })
        row.addView(beside, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginStart = dp(12); marginEnd = dp(4) })
        row.addView(iconButton(Glyph.CHEVRON_DOWN, Ui.SUB, 36, description = "收起") { closeSheet() })
        head.addView(row, matchWrap())
        // drag the head back the way the sheet came to put it away
        var startY = 0f
        head.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { startY = ev.rawY; true }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - startY
                    val s = sheet ?: return@setOnTouchListener true
                    s.translationY = dy.coerceAtLeast(0f)
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val dy = ev.rawY - startY
                    if (dy > dp(70)) closeSheet()
                    else sheet?.animate()?.translationY(0f)?.setDuration(240)?.setInterpolator(Ease.spring)?.start()
                    if (kotlin.math.abs(dy) < dp(6)) v.performClick()
                    true
                }
                else -> false
            }
        }
        return head
    }

    /**
     * Puts [body] in: up from the foot (portrait) or as a column at the right (landscape).
     * The data card steps aside and the picture's place
     * shrinks to what is left, so the picture (or the bench's comparison) stays whole.
     */
    private fun showSheet(body: View) {
        val h = shell.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val w = shell.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val m = dp(10)
        val lp: FrameLayout.LayoutParams
        val pad = stagePad.copyOf()
        if (wide) {
            // landscape: a column at the right, the picture (or comparison) left of it
            val sw = (w * 0.42f).toInt()
            lp = FrameLayout.LayoutParams(sw, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END).apply { setMargins(m, m, m, m) }
            pad[2] = sw + 2 * m
        } else {
            val sh = (h * 0.56f).toInt()
            lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, sh,
                Gravity.BOTTOM).apply { setMargins(m, m, m, m) }
            // the data card goes, so the stage has the whole height: the sheet's side is taken off it
            pad[3] = sh + 2 * m
        }
        panel?.animate()?.alpha(0f)?.setDuration(160)?.withEndAction { panel?.visibility = View.GONE }?.start()
        shell.addView(body, lp)
        sheet = body
        body.translationY = h.toFloat()
        body.animate().translationY(0f).setDuration(380).setInterpolator(Ease.spring).start()
        animateStagePadding(pad)
        if (android.os.Build.VERSION.SDK_INT >= 33 && sheetBack == null) {
            val cb = android.window.OnBackInvokedCallback { closeSheet() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, cb)
            sheetBack = cb
        }
    }

    private fun closeSheet() {
        val s = sheet ?: return
        sheet = null
        val h = shell.height.toFloat()
        s.animate().translationY(h).setDuration(260).setInterpolator(Ease.out)
            .withEndAction { shell.removeView(s) }.start()
        bench?.let { b ->
            if (b.dirty) Hud.show(this, Glyph.SLIDERS, "调整未应用")
            b.frame.animate().alpha(0f).setDuration(200).withEndAction { stage.removeView(b.frame); b.destroy() }.start()
            image.animate().alpha(1f).setDuration(260).start()
        }
        bench = null
        panel?.let { it.visibility = View.VISIBLE; it.animate().alpha(1f).setDuration(260).start() }
        animateStagePadding(stagePad)
        if (android.os.Build.VERSION.SDK_INT >= 33) (sheetBack as? android.window.OnBackInvokedCallback)?.let {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
        }
        sheetBack = null
        image.settle()
    }

    /** The stage's padding to [to], eased (the picture or comparison refits as it goes). */
    private fun animateStagePadding(to: IntArray) {
        val from = intArrayOf(stage.paddingLeft, stage.paddingTop, stage.paddingRight, stage.paddingBottom)
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340
            interpolator = Ease.out
            addUpdateListener {
                val t = it.animatedValue as Float
                val p = IntArray(4) { i -> (from[i] + (to[i] - from[i]) * t).toInt() }
                stage.setPadding(p[0], p[1], p[2], p[3])
            }
            start()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (sheet != null) closeSheet() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        // back from the LUT page: what was chosen there comes into the sheet
        bench?.resumed()
        // the picture where it was
        if (sheet == null && (image.translationY != 0f || image.alpha < 1f)) image.settle()
    }

    private fun load(e: Library.Entry): Bitmap? = runCatching {
        val target = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 5 / 4
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
        /** The opening has played since the app was started. */
        private var introPlayed = false
        private val loader = Executors.newSingleThreadExecutor()
        // room for the print on screen and both neighbours (a picture is about 40 MB)
        private val cache = object : LruCache<String, Bitmap>(192 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }

        fun open(context: Context) {
            context.startActivity(Intent(context, ImmersiveActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }
}
