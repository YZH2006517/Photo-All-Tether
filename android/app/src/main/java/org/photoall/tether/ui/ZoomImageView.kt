package org.photoall.tether.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

/**
 * A picture to pinch, double-tap and drag; [onSwipe] gets -1 / +1 for a fling at fit size.
 * With [onVertical] set, a vertical drag at fit size takes the picture along (following the
 * finger, fading) and, let go far or fast enough, gives +1 (up) or -1 (down); otherwise it
 * springs back. The host animates the rest of the way.
 */
class ZoomImageView(context: Context) : ImageView(context) {
    private val m = Matrix()
    private var fitScale = 1f
    private var scale = 1f
    var onSwipe: ((Int) -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var onVertical: ((Int) -> Unit)? = null
    /**
     * With this set, a horizontal drag at fit size takes the picture along (a pager): it gets the
     * picture's offset as it moves (the host shows the neighbour beside it), and [onSwipe] is
     * called on a far or fast enough release toward a side [canSwipe] allows; else it springs back.
     */
    var onPeek: ((Float) -> Unit)? = null
    var canSwipe: ((Int) -> Boolean) = { true }
    private var horizontal = false
    /** The host is moving the picture (a turn under way): touches wait till it is done. */
    var busy = false
    private var springX: android.animation.ValueAnimator? = null
    private var downX = 0f
    private var downY = 0f
    private var vertical = false
    private var tracker: android.view.VelocityTracker? = null
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    /** Rounds the picture's own corners (where it is drawn, not the view's), in px. */
    var cornerRadius = 0f
        set(v) { field = v; invalidate() }
    private val clipPath = android.graphics.Path()
    private val shown = RectF()

    override fun onDraw(canvas: android.graphics.Canvas) {
        val d = drawable
        if (cornerRadius <= 0f || d == null || d.intrinsicWidth <= 0) return super.onDraw(canvas)
        shown.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        imageMatrix.mapRect(shown)
        if (!shown.intersect(0f, 0f, width.toFloat(), height.toFloat())) return super.onDraw(canvas)
        clipPath.reset()
        clipPath.addRoundRect(shown, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        super.onDraw(canvas)
        canvas.restore()
    }

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val target = (scale * d.scaleFactor).coerceIn(fitScale, fitScale * 8f)
            val f = target / scale
            scale = target
            m.postScale(f, f, d.focusX, d.focusY)
            clamp()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = if (scale > fitScale * 1.05f) fitScale else fitScale * 3f
            val f = target / scale
            scale = target
            m.postScale(f, f, e.x, e.y)
            clamp()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scale <= fitScale * 1.01f) return false
            m.postTranslate(-dx, -dy)
            clamp()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (onPeek != null) return false // the drag has it
            if (scale > fitScale * 1.01f || kotlin.math.abs(vx) < kotlin.math.abs(vy) || kotlin.math.abs(vx) < 400) return false
            onSwipe?.invoke(if (vx < 0) 1 else -1)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTap?.invoke()
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        @Suppress("SENSELESS_COMPARISON")
        if (m != null) fit() // also called from ImageView's constructor, before this class is set up
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    private fun fit() {
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0) return
        fitScale = minOf(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        scale = fitScale
        m.reset()
        m.postScale(scale, scale)
        m.postTranslate((width - d.intrinsicWidth * scale) / 2f, (height - d.intrinsicHeight * scale) / 2f)
        imageMatrix = m
    }

    private fun clamp() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(r)
        var dx = 0f
        var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2f - r.left
        else if (r.left > 0) dx = -r.left
        else if (r.right < width) dx = width - r.right
        if (r.height() <= height) dy = (height - r.height()) / 2f - r.top
        else if (r.top > 0) dy = -r.top
        else if (r.bottom < height) dy = height - r.bottom
        m.postTranslate(dx, dy)
        imageMatrix = m
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (busy) return true
        if ((onVertical != null || onPeek != null) && verticalDrag(event)) return true
        scaler.onTouchEvent(event)
        if (!scaler.isInProgress) gestures.onTouchEvent(event)
        return true
    }

    /** The vertical drag at fit size; true while it has the gesture. */
    private fun verticalDrag(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; vertical = false; horizontal = false
                springX?.cancel()
                tracker?.recycle()
                tracker = android.view.VelocityTracker.obtain().apply { addMovement(e) }
                animate().cancel()
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (vertical || horizontal) { settle(); return true }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                val dx = e.x - downX
                val dy = e.y - downY
                if (onPeek != null && !vertical && !horizontal && e.pointerCount == 1 && scale <= fitScale * 1.01f &&
                    kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.2f) {
                    horizontal = true
                    val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                    gestures.onTouchEvent(cancel)
                    cancel.recycle()
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (horizontal) {
                    // past the first or the last: it gives, but only a little
                    val dir = if (dx < 0) 1 else -1
                    translationX = if (canSwipe(dir)) dx else dx * 0.22f
                    onPeek?.invoke(translationX)
                    return true
                }
                if (onVertical != null && !vertical && e.pointerCount == 1 && scale <= fitScale * 1.01f &&
                    kotlin.math.abs(dy) > slop * 1.5f && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.3f) {
                    vertical = true
                    // the picture is the vertical gesture's now: the others let go of it
                    val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                    gestures.onTouchEvent(cancel)
                    cancel.recycle()
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (vertical) {
                    translationY = dy * 0.6f
                    alpha = (1f - kotlin.math.abs(dy) / height * 0.9f).coerceIn(0.25f, 1f)
                    val s = 1f - kotlin.math.abs(dy) / height * 0.08f
                    scaleX = s; scaleY = s
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (horizontal) {
                    tracker?.addMovement(e)
                    tracker?.computeCurrentVelocity(1000)
                    val vx = tracker?.xVelocity ?: 0f
                    tracker?.recycle(); tracker = null
                    horizontal = false
                    val dx = e.x - downX
                    val dir = if (dx < 0) 1 else -1
                    val far = kotlin.math.abs(dx) > width * 0.2f
                    val fast = kotlin.math.abs(vx) > 900f && kotlin.math.sign(vx) == kotlin.math.sign(dx)
                    if (e.actionMasked == MotionEvent.ACTION_UP && (far || fast) && canSwipe(dir)) onSwipe?.invoke(dir)
                    else springBackX()
                    return true
                }
                if (!vertical) { tracker?.recycle(); tracker = null; return false }
                tracker?.addMovement(e)
                tracker?.computeCurrentVelocity(1000)
                val vy = tracker?.yVelocity ?: 0f
                tracker?.recycle(); tracker = null
                vertical = false
                val dy = e.y - downY
                val far = kotlin.math.abs(dy) > height * 0.16f
                val fast = kotlin.math.abs(vy) > 1400f && kotlin.math.sign(vy) == kotlin.math.sign(dy)
                if (e.actionMasked == MotionEvent.ACTION_UP && (far || fast)) onVertical?.invoke(if (dy < 0) 1 else -1)
                else settle()
                return true
            }
        }
        return vertical || horizontal
    }

    /** Back to the middle across, the neighbour going with it. */
    private fun springBackX() {
        springX?.cancel()
        springX = android.animation.ValueAnimator.ofFloat(translationX, 0f).apply {
            duration = 320
            interpolator = Ease.spring
            addUpdateListener { translationX = it.animatedValue as Float; onPeek?.invoke(translationX) }
            start()
        }
    }

    /** Back in place, springing. */
    fun settle() {
        vertical = false
        if (horizontal || translationX != 0f) { horizontal = false; springBackX() }
        animate().translationY(0f).alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(Ease.spring).start()
    }
}
