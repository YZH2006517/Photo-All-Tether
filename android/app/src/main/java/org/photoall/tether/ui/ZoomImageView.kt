package org.photoall.tether.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

/** A picture to pinch, double-tap and drag; [onSwipe] gets -1 / +1 for a fling at fit size. */
class ZoomImageView(context: Context) : ImageView(context) {
    private val m = Matrix()
    private var fitScale = 1f
    private var scale = 1f
    var onSwipe: ((Int) -> Unit)? = null
    var onTap: (() -> Unit)? = null

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
        scaler.onTouchEvent(event)
        if (!scaler.isInProgress) gestures.onTouchEvent(event)
        return true
    }
}
