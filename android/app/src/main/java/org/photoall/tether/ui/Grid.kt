package org.photoall.tether.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** A frame as tall as it is wide. */
open class SquareFrame(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = super.onMeasure(widthMeasureSpec, widthMeasureSpec)
}

/**
 * A thumbnail cell in the manner of Photos: the picture edge to edge, its kind in a small tag,
 * glyphs for what is true of it (on the phone, protected). In selection, every cell shows a
 * circle; a chosen one shrinks a little and its circle fills with a check that pops.
 */
class ThumbCell(context: Context) : SquareFrame(context) {
    val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(Ui.SURFACE)
    }
    val badge = TextView(context).apply {
        textSize = 9f
        setTextColor(Ui.TEXT)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        background = rounded(0x99000000.toInt(), dp(4).toFloat())
        setPadding(dp(4), dp(1), dp(4), dp(1))
    }
    private val marks = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val onPhone = IconView(context, Glyph.CHECK_CIRCLE, Ui.GREEN, 15)
    private val locked = IconView(context, Glyph.LOCK, Ui.TEXT, 14)
    private val check = IconView(context, Glyph.CIRCLE, android.graphics.Color.WHITE, 22)
    private var wasSelected = false
    private var scaleTarget = 1f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        image.animate().cancel()
        image.scaleX = scaleTarget
        image.scaleY = scaleTarget
    }
    var key: String? = null
    /** Position in the grid's flat list (for drag selection). */
    var index = -1

    init {
        addView(image, frameMatch())
        addView(badge, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(4), 0, 0, dp(4))
        })
        marks.addView(locked, LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(3) })
        marks.addView(onPhone, LinearLayout.LayoutParams(dp(15), dp(15)))
        addView(marks, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(4), dp(4))
        })
        addView(check, LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(5), dp(5), 0) })
        setSelected(false, false)
    }

    fun setSelected(selecting: Boolean, selected: Boolean) {
        check.visibility = if (selecting) View.VISIBLE else View.GONE
        check.glyph = if (selected) Glyph.CHECK_CIRCLE else Glyph.CIRCLE
        check.background = if (selected) rounded(Ui.ACCENT, dp(12).toFloat()) else rounded(0x33000000, dp(12).toFloat())
        val target = if (selecting && selected) 0.9f else 1f
        scaleTarget = target
        // animate only where it can be seen; off screen (the page left for the darkroom) snap,
        // or the cell would come back still shrunk
        if (image.scaleX != target) {
            if (isAttachedToWindow && isShown) image.animate().scaleX(target).scaleY(target).setDuration(320).setInterpolator(Ease.spring).start()
            else { image.animate().cancel(); image.scaleX = target; image.scaleY = target }
        }
        image.alpha = 1f
        if (selected && !wasSelected && selecting) check.pop(0.5f)
        wasSelected = selected && selecting
    }

    /** What is true of the picture: on the phone already, protected on the card. */
    fun marks(imported: Boolean, protected: Boolean) {
        onPhone.visibility = if (imported) View.VISIBLE else View.GONE
        locked.visibility = if (protected) View.VISIBLE else View.GONE
    }

    @Deprecated("marks(imported, protected)")
    fun mark(text: String?, color: Int) {
        marks(text?.contains("已存") == true, text?.contains("保护") == true)
    }

    /** The picture: a newly loaded one fades in; a cached one is simply there. */
    fun show(bitmap: Bitmap?, fade: Boolean = false) {
        image.setImageBitmap(bitmap)
        if (fade && bitmap != null && !Ease.reduced(context)) {
            image.alpha = 0f
            image.animate().alpha(1f).setDuration(260).setInterpolator(Ease.out).start()
        }
    }
}


/**
 * A grid with date headers in a ListView: rows of [columns] cells, and a header row before
 * each section. Scales to thousands of pictures (rows are recycled). [columns] can change
 * (the page turned to landscape).
 */
class SectionGrid<T>(
    private val context: Context,
    columns: Int,
    private val bind: (ThumbCell, T) -> Unit,
    private val onClick: (T, Int) -> Unit,
    private val onLongClick: (T, Int) -> Unit,
    private val headerAction: ((String, List<T>) -> Unit)? = null,
    private val headerActionText: () -> String? = { null },
) : BaseAdapter() {
    private sealed class Row
    private class Header(val title: String, val items: List<Any?>) : Row()
    private class Cells(val items: List<Any?>, val first: Int) : Row()

    private var rows: List<Row> = emptyList()
    private var sections: List<Pair<String, List<T>>> = emptyList()
    var columns = columns
        private set

    /** Every item in display order (cells' [ThumbCell.index] point into it). */
    var flat: List<T> = emptyList()
        private set

    fun setSections(sections: List<Pair<String, List<T>>>) {
        this.sections = sections
        val out = mutableListOf<Row>()
        val all = mutableListOf<T>()
        for ((title, items) in sections) {
            if (title.isNotEmpty()) out += Header(title, items)
            items.chunked(columns).forEach { out += Cells(it, all.size + 0); all.addAll(it) }
        }
        rows = out
        flat = all
        notifyDataSetChanged()
    }

    fun setColumns(n: Int) {
        if (n == columns || n < 1) return
        columns = n
        setSections(sections)
    }

    override fun getCount() = rows.size
    override fun getItem(position: Int): Any = rows[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getViewTypeCount() = 2
    override fun getItemViewType(position: Int) = if (rows[position] is Header) 0 else 1
    override fun isEnabled(position: Int) = false

    @Suppress("UNCHECKED_CAST")
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = rows[position]
        if (row is Header) {
            val v = convertView as? LinearLayout ?: LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(4), context.dp(18), context.dp(4), context.dp(8))
                addView(context.label("", Ui.HEADLINE, Ui.TEXT, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(context.label("", Ui.FOOTNOTE, Ui.ACCENT, bold = true).apply { setPadding(context.dp(8), 0, 0, 0) })
            }
            (v.getChildAt(0) as TextView).text = row.title
            val action = v.getChildAt(1) as TextView
            val actionText = headerActionText()
            action.visibility = if (headerAction != null && actionText != null) View.VISIBLE else View.GONE
            action.text = actionText
            action.setOnClickListener { headerAction?.invoke(row.title, row.items as List<T>) }
            return v
        }
        row as Cells
        var v = convertView as? LinearLayout
        if (v == null || v.childCount != columns) v = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            repeat(columns) { i ->
                addView(ThumbCell(context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = context.dp(2)
                    bottomMargin = context.dp(2)
                })
            }
        }
        for (i in 0 until columns) {
            val cell = v.getChildAt(i) as ThumbCell
            val item = row.items.getOrNull(i) as T?
            if (item == null) {
                cell.visibility = View.INVISIBLE
                cell.index = -1
                cell.setOnClickListener(null)
                cell.setOnLongClickListener(null)
                continue
            }
            val index = row.first + i
            cell.visibility = View.VISIBLE
            cell.index = index
            bind(cell, item)
            cell.setOnClickListener { onClick(item, index) }
            cell.setOnLongClickListener { onLongClick(item, index); true }
        }
        return v
    }
}

/**
 * The ListView of a [SectionGrid] that, after a long press, selects by sliding the finger:
 * every cell passed over reports its index, and the list scrolls when the finger nears an edge.
 */
class DragSelectList(context: Context) : android.widget.ListView(context) {
    var onDragTo: ((Int) -> Unit)? = null
    /** The list's width changed (columns follow the room the grid really has). */
    var onWidth: ((Int) -> Unit)? = null
    private var dragging = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw && w > 0) post { onWidth?.invoke(w) }
    }
    private var lastIndex = -1
    private var lastY = 0f
    private val scroller = object : Runnable {
        override fun run() {
            if (!dragging) return
            val edge = dp(64)
            val step = when {
                lastY < edge -> -dp(14)
                lastY > height - edge -> dp(14)
                else -> 0
            }
            if (step != 0) {
                smoothScrollBy(step, 0)
                report(width / 2f, lastY)
            }
            postDelayed(this, 16)
        }
    }

    init {
        divider = null
        selector = android.graphics.drawable.ColorDrawable(0)
        isFastScrollEnabled = true
        clipToPadding = false
    }

    /** Called from a cell's long press: the finger still down now drives the selection. */
    fun startDrag(index: Int) {
        dragging = true
        lastIndex = index
        parent?.requestDisallowInterceptTouchEvent(true)
        removeCallbacks(scroller)
        post(scroller)
    }

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean =
        dragging || super.onInterceptTouchEvent(ev)

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_MOVE -> {
                lastY = ev.y
                report(ev.x, ev.y)
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                dragging = false
                removeCallbacks(scroller)
            }
        }
        return true
    }

    private fun report(x: Float, y: Float) {
        val index = indexAt(x, y)
        if (index >= 0 && index != lastIndex) {
            lastIndex = index
            onDragTo?.invoke(index)
        }
    }

    private fun indexAt(x: Float, y: Float): Int {
        for (i in 0 until childCount) {
            val row = getChildAt(i) as? LinearLayout ?: continue
            if (y < row.top || y >= row.bottom) continue
            for (k in 0 until row.childCount) {
                val cell = row.getChildAt(k) as? ThumbCell ?: continue
                if (x >= cell.left && x < cell.right) return cell.index
            }
        }
        return -1
    }
}

/**
 * Selection by key with long-press-and-slide: the long-pressed item decides whether the slide
 * selects or clears, and everything between it and the finger follows.
 */
class DragSelection<T>(private val key: (T) -> String) {
    val keys = LinkedHashSet<String>()
    private var anchor = -1
    private var adding = true
    private var before: Set<String> = emptySet()

    val active: Boolean get() = keys.isNotEmpty()

    fun toggle(item: T) {
        val k = key(item)
        if (!keys.remove(k)) keys += k
    }

    /** A long press at [index] of [flat]: toggles it and starts a slide from there. */
    fun begin(flat: List<T>, index: Int) {
        val k = flat.getOrNull(index)?.let(key) ?: return
        adding = k !in keys
        before = keys.toSet()
        anchor = index
        if (adding) keys += k else keys -= k
    }

    /** The finger is over [index]: the range from the anchor follows the slide. */
    fun dragTo(flat: List<T>, index: Int) {
        if (anchor < 0) return
        val range = (minOf(anchor, index)..maxOf(anchor, index)).mapNotNull { flat.getOrNull(it)?.let(key) }.toSet()
        keys.clear()
        keys.addAll(before)
        if (adding) keys.addAll(range) else keys.removeAll(range)
    }

    fun clear() {
        keys.clear()
        anchor = -1
    }

    operator fun contains(item: T) = key(item) in keys
}
