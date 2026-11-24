package org.photoall.tether.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** Curves: a gentle spring (a little overshoot, settles), a snappy one, and the system's ease. */
object Ease {
    /** A damped spring over the animation's duration: [bounce] 0 (none) .. 0.5 (lively). */
    class Spring(private val bounce: Float = 0.18f) : TimeInterpolator {
        override fun getInterpolation(t: Float): Float {
            if (t >= 1f) return 1f
            val damping = 7f - bounce * 8f
            val freq = 9f + bounce * 10f
            return (1 - exp(-damping * t.toDouble()) * cos(freq * t.toDouble())).toFloat()
        }
    }

    val spring = Spring(0.18f)
    val bouncy = Spring(0.38f)
    val smooth = Spring(0f)
    /** iOS's default ease out (decelerating, no overshoot). */
    val out: TimeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    val inOut: TimeInterpolator = PathInterpolator(0.42f, 0f, 0.2f, 1f)

    /** Whether the person asked for less motion (animator scale off). */
    fun reduced(context: Context): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}

/** Presses that give back: the view sinks a little under the finger and springs up on release. */
@SuppressLint("ClickableViewAccessibility")
fun View.pressable(scale: Float = 0.95f): View = apply {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(scale).scaleY(scale).setDuration(120).setInterpolator(Ease.out).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(Ease.bouncy).start()
        }
        false
    }
}

/** Appears: fades in and rises a few dp ([delay] ms, for a staggered list). */
fun View.rise(delay: Long = 0, distance: Int = 14) {
    if (Ease.reduced(context)) return
    alpha = 0f
    translationY = context.dp(distance).toFloat()
    animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(460).setInterpolator(Ease.out).start()
}

/** A one-off pop: scales from [from] to 1 on the spring. */
fun View.pop(from: Float = 0.6f) {
    scaleX = from; scaleY = from
    animate().scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(Ease.bouncy).start()
}

/**
 * The feel of events, scaled to how rare they are: a tick for routine choices, a confirm for a
 * done action, the shutter for a new frame, success for a developed print, and the biggest for
 * a finished roll.
 */
object Haptic {
    private fun vibrator(v: View): Vibrator? = v.context.getSystemService(Vibrator::class.java)

    fun tick(v: View) = v.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)

    fun select(v: View) = v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

    fun toggle(v: View, on: Boolean) = v.performHapticFeedback(when {
        Build.VERSION.SDK_INT >= 34 -> if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
        else -> HapticFeedbackConstants.KEYBOARD_TAP
    })

    fun confirm(v: View) = v.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)

    fun reject(v: View) = v.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)

    /** A frame exposed on the camera: a crisp click with a low tail, like a focal-plane shutter. */
    fun shutter(v: View) = compose(v, listOf(VibrationEffect.Composition.PRIMITIVE_CLICK to 1f, VibrationEffect.Composition.PRIMITIVE_LOW_TICK to 0.6f))
        { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }

    /** A print out of the developer: a rise and a soft landing. */
    fun success(v: View) = compose(v, listOf(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE to 0.5f, VibrationEffect.Composition.PRIMITIVE_CLICK to 0.8f))
        { confirm(v) }

    /** A roll finished: the film rewinding (a spin) and the cartridge landing (a thud). */
    fun rollDone(v: View) = compose(v, listOf(VibrationEffect.Composition.PRIMITIVE_SPIN to 0.7f,
        VibrationEffect.Composition.PRIMITIVE_SPIN to 0.5f, VibrationEffect.Composition.PRIMITIVE_THUD to 1f)) { confirm(v) }

    private fun compose(v: View, prims: List<Pair<Int, Float>>, fallback: () -> Unit) {
        val vib = vibrator(v)
        if (Build.VERSION.SDK_INT >= 30 && vib != null && vib.areAllPrimitivesSupported(*prims.map { it.first }.toIntArray())) {
            val c = VibrationEffect.startComposition()
            prims.forEachIndexed { i, (p, s) -> c.addPrimitive(p, s, if (i == 0) 0 else 40) }
            runCatching { vib.vibrate(c.compose()) }.onFailure { fallback() }
        } else fallback()
    }
}

/** A short notice in the middle of the screen: a glyph and a word or two, then gone. */
object Hud {
    fun show(activity: Activity, glyph: Glyph, text: String? = null, color: Int = Ui.TEXT) {
        val root = activity.window.decorView as? ViewGroup ?: return
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(Ui.HUD, activity.dp(22).toFloat(), if (Ui.light) 1 else 0, 0x14000000)
            setPadding(activity.dp(22), activity.dp(20), activity.dp(22), activity.dp(18))
            elevation = activity.dp(12).toFloat()
            addView(IconView(activity, glyph, color, 44))
            if (text != null) addView(activity.label(text, 14f, Ui.TEXT, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, activity.dp(10), 0, 0)
            })
        }
        val size = activity.dp(if (text != null) 150 else 110)
        root.addView(box, FrameLayout.LayoutParams(size, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        box.alpha = 0f
        box.scaleX = 0.8f; box.scaleY = 0.8f
        box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(Ease.spring).withEndAction {
            box.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setStartDelay(900).setDuration(260).setInterpolator(Ease.out)
                .withEndAction { root.removeView(box) }.start()
        }.start()
    }
}

/** A status dot that breathes while something is live (connected, working). */
class PulseDot(context: Context) : View(context) {
    var color: Int = Ui.GREEN
        set(v) { field = v; invalidate() }
    var live: Boolean = false
        set(v) { field = v; if (v) start() else stop(); invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var anim: ValueAnimator? = null

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(dp(18), dp(18))

    private fun start() {
        if (anim != null || !isAttachedToWindow) return
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            interpolator = Ease.out
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stop() { anim?.cancel(); anim = null; phase = 0f }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (live) start() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = dp(4).toFloat()
        if (live) {
            paint.color = color
            paint.alpha = ((1f - phase) * 110).toInt()
            canvas.drawCircle(cx, cy, r + phase * dp(5), paint)
        }
        paint.color = color
        paint.alpha = 255
        canvas.drawCircle(cx, cy, r, paint)
    }
}

/** A progress ring (round a thumbnail, a button): eases to each new value; spins when unknown. */
class RingProgress(context: Context) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = if (Ui.light) 0x1F000000 else 0x33FFFFFF }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Ui.ACCENT }
    private val oval = RectF()
    private var shown = 0f
    private var target = 0f
    private var spin = 0f
    private var anim: ValueAnimator? = null
    var indeterminate = false
        set(v) { field = v; ensure() }
    var color: Int
        get() = bar.color
        set(v) { bar.color = v; invalidate() }
    var thickness = context.dp(3).toFloat()

    fun setProgress(p: Float) {
        target = p.coerceIn(0f, 1f)
        ensure()
    }

    private fun ensure() {
        if (anim != null || !isAttachedToWindow) return
        // kept before it starts: start() runs the first frame at once, and that frame may already
        // be the last (nothing to move), which must find this animator to let it go
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                shown += (target - shown) * 0.18f
                if (indeterminate) spin = (spin + 9f) % 360f
                invalidate()
                if (!indeterminate && kotlin.math.abs(target - shown) < 0.002f) { shown = target; cancel(); if (anim === this) anim = null }
            }
        }
        anim = a
        a.start()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); ensure() }
    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        track.strokeWidth = thickness
        bar.strokeWidth = thickness
        val inset = thickness / 2 + 1
        oval.set(inset, inset, width - inset, height - inset)
        canvas.drawOval(oval, track)
        if (indeterminate) canvas.drawArc(oval, spin - 90f, 90f, false, bar)
        else canvas.drawArc(oval, -90f, 360f * shown, false, bar)
    }
}

/**
 * A number that rolls like a mechanical counter when it changes: the old digits leave upward,
 * the new ones come up from below (only the digits that changed move).
 */
class RollingCounter(context: Context, private val sizeSp: Float = 28f, color: Int = Ui.TEXT) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sizeSp * resources.displayMetrics.scaledDensity
        this.color = color
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    var color: Int
        get() = paint.color
        set(v) { paint.color = v; invalidate() }
    private var old = ""
    private var now = ""
    private var t = 1f
    private var anim: ValueAnimator? = null

    fun set(text: String, animate: Boolean = true) {
        if (text == now) return
        old = now
        now = text
        requestLayout()
        anim?.cancel()
        if (!animate || old.isEmpty() || Ease.reduced(context)) { t = 1f; invalidate(); return }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            interpolator = Ease.spring
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        val fm = paint.fontMetrics
        setMeasuredDimension((paint.measureText(now.ifEmpty { "0" }) + dp(2)).toInt(), (fm.descent - fm.ascent).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val fm = paint.fontMetrics
        val base = -fm.ascent
        val h = height.toFloat()
        val cw = paint.measureText("0")
        canvas.save()
        canvas.clipRect(0, 0, width, height)
        val n = now.length
        for (i in 0 until n) {
            val ch = now[i].toString()
            val oi = old.length - (n - i)
            val prev = if (oi >= 0) old[oi].toString() else ""
            val x = i * cw
            if (prev == ch || t >= 1f) {
                canvas.drawText(ch, x, base, paint)
            } else {
                val a = paint.alpha
                paint.alpha = ((1f - t.coerceIn(0f, 1f)) * 255).toInt()
                if (prev.isNotEmpty()) canvas.drawText(prev, x, base - h * t, paint)
                paint.alpha = (min(1f, t * 1.4f).coerceIn(0f, 1f) * 255).toInt()
                canvas.drawText(ch, x, base + h * (1f - t), paint)
                paint.alpha = a
            }
        }
        canvas.restore()
    }
}

/**
 * A print coming up in the developer: from a pale, flat, faintly blue latent image to its full
 * density and colour, with a slow settle, the way paper changes in the tray.
 */
fun ImageView.develop(duration: Long = 2200, done: (() -> Unit)? = null) {
    if (Ease.reduced(context)) { colorFilter = null; done?.invoke(); return }
    val m = ColorMatrix()
    val sat = ColorMatrix()
    ValueAnimator.ofFloat(0f, 1f).apply {
        this.duration = duration
        interpolator = PathInterpolator(0.35f, 0f, 0.25f, 1f)
        addUpdateListener {
            val t = it.animatedValue as Float
            // density builds: from washed out (a lifted, low-contrast grey) to the print
            val contrast = 0.35f + 0.65f * t
            val lift = (1f - t) * 150f
            sat.setSaturation(t * t)
            m.set(floatArrayOf(
                contrast, 0f, 0f, 0f, lift,
                0f, contrast, 0f, 0f, lift,
                0f, 0f, contrast, 0f, lift + (1f - t) * 12f,
                0f, 0f, 0f, 1f, 0f))
            m.preConcat(sat)
            colorFilter = ColorMatrixColorFilter(m)
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { colorFilter = null; done?.invoke() }
        })
        start()
    }
}

/** The aperture closing and opening once: a frame was taken. */
fun View.shutterBlink() {
    if (Ease.reduced(context)) return
    animate().cancel()
    animate().rotationBy(60f).scaleX(0.72f).scaleY(0.72f).setDuration(110).setInterpolator(Ease.out).withEndAction {
        animate().scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(Ease.bouncy).start()
    }.start()
}

/** A gentle sway (a wiggle) to draw the eye. */
fun View.nudge() {
    if (Ease.reduced(context)) return
    val a = ValueAnimator.ofFloat(0f, 1f)
    a.duration = 600
    a.addUpdateListener {
        val t = it.animatedValue as Float
        rotation = (sin(t * PI * 4) * 6 * (1 - t)).toFloat()
    }
    a.start()
}
