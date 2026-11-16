package org.photoall.tether.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import org.photoall.tether.develop.Prefs

/**
 * What every screen shares:
 * - fits the phone: laid out for one at least [Adaptive.MIN_WIDTH_DP] across with fonts near
 *   their standard size; a narrower phone (or "display size" turned up) gets a slightly smaller
 *   dp, and a very large system font is held at [Adaptive.MAX_FONT];
 * - light or dark, as set (or as the system is);
 * - full screen by default (the system bars come back for a moment with a swipe from an edge);
 * - turning the phone the other way than the screen shows a rotate button for five seconds
 *   (as Android does with rotation locked): a tap turns the app to match.
 */
open class AdaptiveActivity : Activity() {
    private var rotateButton: View? = null
    private var physical = Configuration.ORIENTATION_UNDEFINED
    private var pending = Configuration.ORIENTATION_UNDEFINED
    private val settle = Runnable { onTurned(pending) }
    private val hideRotate = Runnable { dismissRotate() }
    private var turns: OrientationEventListener? = null
    /** Screens that always fill the screen whatever the setting (the immersive view). */
    protected open val alwaysFullscreen = false
    /** Screens that keep their own orientation (the film loader is landscape only). */
    protected open val offersRotation = true

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        Ui.light = Adaptive.isLight(newBase)
        applyOverrideConfiguration(Adaptive.override(newBase.resources.configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.light = Adaptive.isLight(this)
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Ui.BG))
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    override fun onResume() {
        super.onResume()
        applyBars()
        if (offersRotation) startWatchingTurns()
    }

    override fun onPause() {
        turns?.disable()
        turns = null
        window.decorView.removeCallbacks(settle)
        dismissRotate(animate = false)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyBars()
    }

    /** Hides the system bars (or shows them, when full screen is off), and colours their icons. */
    fun applyBars() {
        val full = alwaysFullscreen || Prefs.fullscreen(this)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                if (full) {
                    it.hide(WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else it.show(WindowInsets.Type.systemBars())
                val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                it.setSystemBarsAppearance(if (Ui.light) lightBars else 0, lightBars)
            }
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (full) (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
            else (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    // ---- the rotate button ------------------------------------------------------------------------

    private fun startWatchingTurns() {
        if (turns != null) return
        turns = object : OrientationEventListener(this) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                // with some slack round the diagonals, so a tilt doesn't flicker it
                val o = when {
                    deg < 30 || deg > 330 || deg in 150..210 -> Configuration.ORIENTATION_PORTRAIT
                    deg in 60..120 || deg in 240..300 -> Configuration.ORIENTATION_LANDSCAPE
                    else -> return
                }
                if (o == pending) return
                pending = o
                window.decorView.removeCallbacks(settle)
                window.decorView.postDelayed(settle, 450)
            }
        }.also { if (it.canDetectOrientation()) it.enable() }
    }

    private fun onTurned(o: Int) {
        if (o == physical) return
        physical = o
        // set to rotate with the phone and the system lets it: the screen turns by itself
        val systemRotates = runCatching {
            android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION) == 1
        }.getOrDefault(false)
        if (Prefs.orientation(this) == 0 && systemRotates) return
        val shown = resources.configuration.orientation
        if (o != shown) showRotate(o) else dismissRotate()
    }

    private fun showRotate(target: Int) {
        val root = window.decorView as? ViewGroup ?: return
        dismissRotate(animate = false)
        val size = dp(56)
        val box = FrameLayout(this).apply {
            background = pressable(this@AdaptiveActivity, Ui.HUD, size / 2f, 1, if (Ui.light) 0x1A000000 else 0x33FFFFFF)
            elevation = dp(10).toFloat()
            contentDescription = if (target == Configuration.ORIENTATION_LANDSCAPE) "切换到横屏" else "切换到竖屏"
        }
        // the five seconds, running out round the edge
        val ring = RingProgress(this).apply { thickness = dp(2.5f).toFloat(); color = Ui.ACCENT }
        box.addView(ring, FrameLayout.LayoutParams(size, size))
        val icon = IconView(this, Glyph.ROTATE, Ui.ACCENT, 26)
        box.addView(icon, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        box.setOnClickListener {
            Haptic.confirm(it)
            Prefs.setOrientation(this, if (target == Configuration.ORIENTATION_LANDSCAPE) 2 else 1)
            dismissRotate()
            Prefs.applyOrientation(this)
        }
        val margin = dp(22)
        root.addView(box, FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(margin, margin, margin, margin + dp(64))
        })
        rotateButton = box
        ring.setProgress(1f)
        box.alpha = 0f
        box.scaleX = 0.5f; box.scaleY = 0.5f
        box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(Ease.spring).start()
        // a quarter turn of the glyph, to say which way
        icon.animate().rotationBy(if (target == Configuration.ORIENTATION_LANDSCAPE) -90f else 90f)
            .setStartDelay(300).setDuration(700).setInterpolator(Ease.spring).start()
        // the ring empties over the five seconds
        android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 5000
            addUpdateListener { ring.setProgress(it.animatedValue as Float) }
            start()
        }
        Haptic.tick(box)
        box.postDelayed(hideRotate, 5000)
    }

    private fun dismissRotate(animate: Boolean = true) {
        val b = rotateButton ?: return
        rotateButton = null
        b.removeCallbacks(hideRotate)
        val root = b.parent as? ViewGroup ?: return
        if (!animate) { root.removeView(b); return }
        b.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(220).setInterpolator(Ease.out)
            .withEndAction { root.removeView(b) }.start()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == physical) dismissRotate()
    }
}

object Adaptive {
    const val MIN_WIDTH_DP = 360
    const val MAX_FONT = 1.15f

    /** Light when chosen so, or when following a system in its light mode. */
    fun isLight(c: Context): Boolean = when (Prefs.theme(c)) {
        2 -> true
        1 -> false
        else -> (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES
    }

    /** Only the fields set here override the system's (the rest follow rotation and so on). */
    fun override(c: Configuration): Configuration {
        val o = Configuration()
        o.fontScale = 0f
        val sw = c.smallestScreenWidthDp
        if (sw in 1 until MIN_WIDTH_DP && c.densityDpi > 0)
            o.densityDpi = (c.densityDpi * sw / MIN_WIDTH_DP.toFloat()).toInt()
        if (c.fontScale > MAX_FONT) o.fontScale = MAX_FONT
        return o
    }
}
