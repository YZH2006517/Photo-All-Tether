package org.photoall.tether.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.DecelerateInterpolator
import android.view.animation.LayoutAnimationController
import android.view.animation.TranslateAnimation
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.photoall.tether.develop.Preview
import org.photoall.tether.engine.LutCatalog
import org.photoall.tether.engine.LutImport
import org.photoall.tether.engine.Recipe
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The creative LUTs: the latest picture large with the chosen one on it (hold it to see it
 * without), its strength, then every LUT as a small print of that same picture — searched,
 * by group, the favourites, the ones used lately. Import .cube / .3dl / Hald / .zip; a long
 * press keeps one as a favourite, and renames, moves or deletes the imported ones.
 */
class LutActivity : AdaptiveActivity() {
    private var recipe = Recipe()
    private var base: Bitmap? = null          // the picture as developed, without a LUT
    private var small: Bitmap? = null         // the same, small, for the cards
    private var shown: List<LutCatalog.Lut?> = emptyList()   // null: "none"
    private var group = ALL
    private var query = ""
    private val token = AtomicInteger()

    private lateinit var preview: ImageView
    private lateinit var previewBack: ImageView
    private lateinit var previewName: TextView
    private lateinit var previewHint: TextView
    private lateinit var spinner: RingProgress
    private lateinit var strengthValue: TextView
    private lateinit var slider: IosSlider
    private lateinit var chips: LinearLayout
    private lateinit var chipScroll: HorizontalScrollView
    private lateinit var grid: GridView
    private lateinit var emptyNote: TextView
    private lateinit var search: EditText
    private lateinit var importing: LinearLayout
    private lateinit var importRing: RingProgress
    private lateinit var importText: TextView
    private val adapter = Cards()
    private val catalogListener: () -> Unit = { runOnUiThread { refreshChips(); refilter(animate = false) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs0.apply(this)
        recipe = Recipe.load(this)
        LutCatalog.load(this)
        val wide = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        // ---- the bar: back, the title, import
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(iconButton(Glyph.CHEVRON_LEFT, Ui.ACCENT, 40, filled = false, description = "返回") { finish() })
        bar.addView(label("风格 LUT", Ui.TITLE, Ui.TEXT, bold = true).single(),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
        bar.addView(button("导入", primary = true, glyph = Glyph.PLUS) { pickFiles() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)))

        // ---- the picture, with the chosen LUT; held, without
        val frame = FrameLayout(this).apply { background = rounded(Ui.SURFACE, dp(18).toFloat()); clipToOutline = true }
        previewBack = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        preview = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(previewBack, frameMatch())
        frame.addView(preview, frameMatch())
        previewName = label("", Ui.HEADLINE, Color.WHITE, bold = true).single()
        val caption = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(26), dp(14), dp(12))
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0x99000000.toInt(), 0x00000000))
            addView(previewName)
        }
        frame.addView(caption, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        previewHint = label("按住看原图", Ui.CAPTION, Color.WHITE, bold = true).apply {
            background = rounded(0x66000000, dp(10).toFloat())
            setPadding(dp(9), dp(4), dp(9), dp(4))
        }
        frame.addView(previewHint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END).apply { setMargins(0, dp(10), dp(10), 0) })
        spinner = RingProgress(this).apply { indeterminate = true }
        frame.addView(spinner, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        frame.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { compare(true); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { compare(false); true }
                else -> true
            }
        }

        // ---- strength, and "none"
        strengthValue = label("", Ui.SUBHEAD, Ui.ACCENT, bold = true).apply { typeface = Ui.mono }
        slider = IosSlider(this, 0f, 1f, recipe.lutStrength, 0.05f, 0.8f).apply {
            onDrag = { v -> strengthValue.text = pct(v); recipe = recipe.copy(lutStrength = v); renderPreview(fast = true) }
            onRelease = { v -> recipe = recipe.copy(lutStrength = v); Recipe.save(this@LutActivity, recipe); renderPreview() }
        }
        val strength = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(IconView(this@LutActivity, Glyph.SLIDERS, Ui.SUB, 18), LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(4) })
            addView(slider, LinearLayout.LayoutParams(0, dp(36), 1f))
            addView(strengthValue, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
            addView(button("无", glyph = Glyph.CLOSE) { choose(null) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // ---- search, the groups
        search = EditText(this).apply {
            hint = "搜索 LUT"
            setHintTextColor(Ui.DIM)
            setTextColor(Ui.TEXT)
            textSize = Ui.BODY
            isSingleLine = true
            background = null
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim().orEmpty(); refilter(animate = false) }
            })
        }
        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Ui.SURFACE2, dp(12).toFloat())
            setPadding(dp(10), 0, dp(6), 0)
            addView(IconView(this@LutActivity, Glyph.SEARCH, Ui.SUB, 17), LinearLayout.LayoutParams(dp(17), dp(17)).apply { marginEnd = dp(6) })
            addView(search, LinearLayout.LayoutParams(0, dp(40), 1f))
            addView(iconButton(Glyph.CLOSE, Ui.SUB, 28, filled = false, description = "清除") { search.setText(""); hideKeyboard() },
                LinearLayout.LayoutParams(dp(28), dp(28)))
        }
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(2)) }
        chipScroll = hScroll(chips)

        // ---- the cards
        grid = GridView(this).apply {
            adapter = this@LutActivity.adapter
            verticalSpacing = dp(12)
            horizontalSpacing = dp(10)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(0)
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(32))
            isVerticalScrollBarEnabled = false
            layoutAnimation = LayoutAnimationController(AnimationSet(true).apply {
                addAnimation(AlphaAnimation(0f, 1f))
                addAnimation(TranslateAnimation(0f, 0f, dp(22).toFloat(), 0f))
                duration = 380
                interpolator = DecelerateInterpolator(2f)
            }, 0.07f)
            setOnItemClickListener { _, view, i, _ -> Haptic.select(view); choose(shown.getOrNull(i)); (view as? Card)?.chosenPop() }
            setOnItemLongClickListener { _, view, i, _ -> shown.getOrNull(i)?.let { Haptic.confirm(view); manage(it) }; true }
            if (!wide) addOnLayoutChangeListener { v, l, _, r, _, ol, _, or_, _ ->
                if (r - l != or_ - ol) numColumns = (v.width / dp(104)).coerceIn(3, 7)
            }
        }
        emptyNote = label("", Ui.SUBHEAD, Ui.SUB).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        val gridBox = FrameLayout(this).apply {
            addView(grid, frameMatch())
            addView(emptyNote, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { topMargin = dp(40) })
        }

        // ---- the import's progress, over everything
        importRing = RingProgress(this).apply { thickness = dp(3).toFloat() }
        importText = label("", Ui.SUBHEAD, Ui.TEXT, bold = true)
        importing = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Ui.HUD, dp(22).toFloat(), 1, Ui.LINE)
            elevation = dp(10).toFloat()
            setPadding(dp(16), dp(12), dp(20), dp(12))
            addView(importRing, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) })
            addView(importText)
            visibility = View.GONE
        }

        // ---- the page: portrait in one column; landscape the picture on the left
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        if (!wide) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(6), dp(16), 0) }
            col.addView(bar, matchWrap())
            val h = (resources.displayMetrics.heightPixels * 0.3f).toInt().coerceIn(dp(170), dp(300))
            col.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply { topMargin = dp(8) })
            col.addView(strength, matchWrap().apply { topMargin = dp(8) })
            col.addView(searchBox, matchWrap().apply { topMargin = dp(6) })
            col.addView(chipScroll, matchWrap().apply { topMargin = dp(10) })
            col.addView(gridBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })
            root.addView(col, frameMatch())
        } else {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(8), dp(16), 0) }
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(14)) }
            left.addView(bar, matchWrap())
            left.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
            left.addView(strength, matchWrap().apply { topMargin = dp(8) })
            // the right column on the left's lines: the search level with the title bar, two rows
            // of LUTs exactly as tall as the picture (top on its top, foot on its foot), the
            // groups under them level with the strength
            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            right.addView(searchBox, matchWrap().apply { topMargin = dp(4) })
            right.addView(gridBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)).apply { topMargin = dp(8) })
            right.addView(chipScroll, matchWrap().apply { topMargin = dp(8) })
            row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            row.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.25f).apply { marginStart = dp(16) })
            root.addView(row, frameMatch())
            grid.setPadding(0, 0, 0, 0)
            grid.verticalSpacing = ROW_GAP
            val align = { alignToPicture(frame, gridBox, strength, chipScroll) }
            frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> frame.post(align) }
            right.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> right.post(align) }
        }
        root.addView(importing, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(28) })
        setContentView(root)
        padForSystemBars(root)

        strengthValue.text = pct(recipe.lutStrength)
        // start where the chosen one is (or all of them)
        group = recipe.lut?.let { LutCatalog.find(it)?.group } ?: ALL
        refreshChips()
        refilter(animate = true)
        loadBase()
    }

    override fun onStart() { super.onStart(); LutCatalog.addListener(catalogListener) }
    override fun onStop() { LutCatalog.removeListener(catalogListener); super.onStop() }

    private fun pct(v: Float) = "%.0f%%".format(v * 100)

    /** Landscape: each card's picture height, so two rows fill the picture's height exactly. */
    private var cardImageHeight = 0
    private val ROW_GAP get() = dp(12)

    /**
     * Landscape: the grid's top on the picture's top and its height the picture's, two rows of
     * cards filling it; the groups' strip on the strength row's middle.
     */
    private fun alignToPicture(frame: View, gridBox: View, strength: View, chipStrip: View) {
        if (frame.height == 0 || gridBox.width == 0) return
        val a = IntArray(2); val b = IntArray(2)
        frame.getLocationInWindow(a)
        gridBox.getLocationInWindow(b)
        val lp = gridBox.layoutParams as LinearLayout.LayoutParams
        var changed = false
        val top = lp.topMargin + (a[1] - b[1])
        if (top != lp.topMargin) { lp.topMargin = top.coerceAtLeast(0); changed = true }
        if (lp.height != frame.height) { lp.height = frame.height; changed = true }
        // a card: its picture, 6 dp, its name; two of them and the gap between fill the height
        val probe = label("名", Ui.FOOTNOTE, bold = true)
        probe.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val nameBlock = dp(6) + probe.measuredHeight
        val imageH = ((frame.height - ROW_GAP) / 2 - nameBlock).coerceAtLeast(dp(40))
        val columns = ((gridBox.width + grid.horizontalSpacing) / (imageH * 4 / 3 + grid.horizontalSpacing)).coerceIn(2, 8)
        if (imageH != cardImageHeight || grid.numColumns != columns) {
            cardImageHeight = imageH
            grid.numColumns = columns
            adapter.notifyDataSetChanged()
        }
        // the groups' strip centred on the strength row
        val c = IntArray(2); val d = IntArray(2)
        strength.getLocationInWindow(c); chipStrip.getLocationInWindow(d)
        val clp = chipStrip.layoutParams as LinearLayout.LayoutParams
        val want = clp.topMargin + (c[1] + strength.height / 2) - (d[1] + chipStrip.height / 2)
        if (want != clp.topMargin && want >= 0) { clp.topMargin = want; changed = true }
        if (changed) { gridBox.requestLayout(); chipStrip.requestLayout() }
    }

    // ---- the picture ------------------------------------------------------------------------------

    /** The latest raw developed with the recipe but no LUT (or, before any, a colour chart). */
    private fun loadBase() {
        spinner.visibility = View.VISIBLE
        Preview.render(this, recipe.copy(lutOn = false)) { bmp, _ ->
            val b = bmp ?: chart()
            base = b
            val w = dp(220)
            small = Bitmap.createScaledBitmap(b, w, (w.toFloat() * b.height / b.width).toInt().coerceAtLeast(1), true)
            spinner.visibility = View.GONE
            thumbs.evictAll()
            adapter.notifyDataSetChanged()
            renderPreview(first = true)
        }
    }

    private val worker = Executors.newSingleThreadExecutor()

    /** The large picture with the chosen LUT at its strength; [fast] while the slider moves. */
    private fun renderPreview(fast: Boolean = false, first: Boolean = false) {
        val b = base ?: return
        val id = recipe.lut
        previewName.text = id?.let { LutCatalog.label(it) } ?: "无 LUT"
        val t = token.incrementAndGet()
        val strength = recipe.lutStrength
        worker.execute {
            if (t != token.get()) return@execute
            val table = id?.let { LutCatalog.table(this, it) }
            val src = if (fast) scaled(b, 640) else scaled(b, 1400)
            val out = if (table == null) src else {
                val px = IntArray(src.width * src.height)
                src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
                LutImport.apply(table, LutCatalog.size, px, strength)
                Bitmap.createBitmap(px, src.width, src.height, Bitmap.Config.ARGB_8888)
            }
            runOnUiThread {
                if (t != token.get()) return@runOnUiThread
                if (fast || first) { preview.setImageBitmap(out); if (first) preview.develop(1100); return@runOnUiThread }
                // the new one fades in over the old
                previewBack.setImageDrawable(preview.drawable)
                preview.alpha = 0f
                preview.setImageBitmap(out)
                preview.animate().alpha(1f).setDuration(260).start()
            }
        }
    }

    private fun scaled(b: Bitmap, long: Int): Bitmap {
        val k = long.toFloat() / maxOf(b.width, b.height)
        return if (k >= 1f) b.copy(Bitmap.Config.ARGB_8888, false) else Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true)
    }

    private fun compare(on: Boolean) {
        val b = base ?: return
        if (on) {
            previewBack.setImageDrawable(preview.drawable)
            preview.setImageBitmap(scaled(b, 1400))
            previewHint.text = "原图"
            Haptic.tick(preview)
        } else {
            previewHint.text = "按住看原图"
            renderPreview(fast = false)
        }
    }

    /** Before any raw is developed: a colour chart and a grey ramp to judge a LUT by. */
    private fun chart(): Bitmap {
        val w = 1200; val h = 800
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(0xFF2A2A2A.toInt())
        // the 24 patches of a colour checker (sRGB)
        val patches = intArrayOf(
            0x735244, 0xc29682, 0x627a9d, 0x576c43, 0x8580b1, 0x67bdaa,
            0xd67e2c, 0x505ba6, 0xc15a63, 0x5e3c6c, 0x9dbc40, 0xe0a32e,
            0x383d96, 0x469449, 0xaf363c, 0xe7c71f, 0xbb5695, 0x0885a1,
            0xf3f3f2, 0xc8c8c8, 0xa0a0a0, 0x7a7a79, 0x555555, 0x343434)
        val cw = 170f; val ch = 140f; val gap = 22f
        val x0 = (w - 6 * cw - 5 * gap) / 2; val y0 = 60f
        for (i in patches.indices) {
            p.color = 0xFF000000.toInt() or patches[i]
            val x = x0 + (i % 6) * (cw + gap); val y = y0 + (i / 6) * (ch + gap)
            c.drawRoundRect(x, y, x + cw, y + ch, 14f, 14f, p)
        }
        // a ramp, black to white, and a hue sweep
        p.shader = LinearGradient(x0, 0f, w - x0, 0f, Color.BLACK, Color.WHITE, Shader.TileMode.CLAMP)
        c.drawRoundRect(x0, 712f, w - x0, 740f, 10f, 10f, p)
        p.shader = LinearGradient(x0, 0f, w - x0, 0f, intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED),
            null, Shader.TileMode.CLAMP)
        c.drawRoundRect(x0, 752f, w - x0, 780f, 10f, 10f, p)
        return bmp
    }

    // ---- choosing ---------------------------------------------------------------------------------

    private fun choose(lut: LutCatalog.Lut?) {
        if (lut?.id == recipe.lut && (lut == null || recipe.lutOn)) return
        recipe = recipe.copy(lut = lut?.id, lutOn = lut != null || recipe.lutOn)
        Recipe.save(this, recipe)
        lut?.let { LutCatalog.used(this, it.id) }
        adapter.notifyDataSetChanged()
        renderPreview()
        Haptic.tick(preview)
    }

    // ---- groups and search ------------------------------------------------------------------------

    private fun refreshChips() {
        chips.removeAllViews()
        val keys = listOf(FAVOURITES, RECENT, ALL) + LutCatalog.userGroups() + LutCatalog.groups().filter { it !in LutCatalog.userGroups() }
        if (group !in keys) group = ALL
        keys.forEach { key ->
            val (text, glyph) = when (key) {
                FAVOURITES -> "收藏" to Glyph.STAR
                RECENT -> "最近" to Glyph.TIMER
                ALL -> "全部" to null
                else -> key to (if (key in LutCatalog.userGroups()) Glyph.FOLDER_PLUS else null)
            }
            val chip = Chip(this, text, glyph = glyph).apply { on = key == group }
            chip.onClick = {
                if (group != key) {
                    group = key
                    for (i in 0 until chips.childCount) (chips.getChildAt(i) as Chip).on = chips.getChildAt(i) === chip
                    chip.pop(0.86f)
                    // the chosen one glides to the middle of the strip
                    chipScroll.smoothScrollTo((chip.left - (chipScroll.width - chip.width) / 2).coerceAtLeast(0), 0)
                    refilter(animate = true)
                }
            }
            chips.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        chipScroll.post {
            val i = keys.indexOf(group)
            chips.getChildAt(i)?.let { chipScroll.scrollTo((it.left - (chipScroll.width - it.width) / 2).coerceAtLeast(0), 0) }
        }
    }

    private fun refilter(animate: Boolean) {
        val fav = LutCatalog.favourites(this)
        val q = query.lowercase()
        val list: List<LutCatalog.Lut?> = if (q.isNotEmpty()) {
            // a search looks everywhere
            LutCatalog.all.filter { it.label.lowercase().contains(q) || it.group.lowercase().contains(q) }
        } else when (group) {
            FAVOURITES -> LutCatalog.all.filter { it.id in fav }
            RECENT -> LutCatalog.recent(this).mapNotNull { LutCatalog.find(it) }
            ALL -> listOf<LutCatalog.Lut?>(null) + LutCatalog.all
            else -> LutCatalog.all.filter { it.group == group }
        }
        shown = list
        emptyNote.text = when {
            q.isNotEmpty() -> "没有找到“$query”"
            group == FAVOURITES -> "还没有收藏\n长按任意 LUT 收藏它"
            group == RECENT -> "还没有用过 LUT"
            else -> "这个分组是空的"
        }
        emptyNote.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
        if (animate) { grid.setSelection(0); grid.scheduleLayoutAnimation() }
    }

    // ---- managing -----------------------------------------------------------------------------------

    private fun manage(lut: LutCatalog.Lut) {
        val fav = LutCatalog.isFavourite(this, lut.id)
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += (if (fav) "取消收藏" else "收藏") to {
            val on = LutCatalog.toggleFavourite(this, lut.id)
            Hud.show(this, if (on) Glyph.STAR_FILL else Glyph.STAR, if (on) "已收藏" else "已取消收藏", Ui.ACCENT)
        }
        if (lut.user) {
            actions += "重命名" to { rename(lut) }
            actions += "移到分组…" to { move(lut) }
            actions += "删除" to { delete(lut) }
        }
        val info = if (lut.user) "导入的 ${lut.format.uppercase()} · 原 ${lut.sourceSize}³ → ${LutCatalog.size}³" else lut.group
        AlertDialog.Builder(this).setTitle(lut.label).setMessage(null)
            .setItems((actions.map { it.first } + info).toTypedArray()) { _, i -> actions.getOrNull(i)?.second?.invoke() }
            .show()
    }

    private fun field(text: String) = EditText(this).apply {
        setText(text); setSelection(text.length); isSingleLine = true
        setPadding(dp(20), dp(12), dp(20), dp(12))
    }

    private fun rename(lut: LutCatalog.Lut) {
        val input = field(lut.label)
        AlertDialog.Builder(this).setTitle("重命名").setView(input)
            .setPositiveButton("好") { _, _ -> LutCatalog.rename(this, lut.id, input.text.toString()); if (lut.id == recipe.lut) renderPreview() }
            .setNegativeButton("取消", null).show()
    }

    private fun move(lut: LutCatalog.Lut) {
        val groups = (LutCatalog.userGroups() + LutCatalog.MY_GROUP).distinct().filter { it != lut.group }
        AlertDialog.Builder(this).setTitle("移到分组").setItems((groups + "新分组…").toTypedArray()) { _, i ->
            if (i < groups.size) { LutCatalog.move(this, lut.id, groups[i]); Hud.show(this, Glyph.FOLDER_PLUS, groups[i]) }
            else {
                val input = field("")
                AlertDialog.Builder(this).setTitle("新分组").setView(input)
                    .setPositiveButton("好") { _, _ ->
                        val g = input.text.toString().trim()
                        if (g.isNotEmpty()) { LutCatalog.move(this, lut.id, g); Hud.show(this, Glyph.FOLDER_PLUS, g) }
                    }.setNegativeButton("取消", null).show()
            }
        }.show()
    }

    private fun delete(lut: LutCatalog.Lut) {
        AlertDialog.Builder(this).setTitle("删除“${lut.label}”？").setMessage("导入的文件会从手机里删掉。")
            .setPositiveButton("删除") { _, _ ->
                if (recipe.lut == lut.id) { recipe = recipe.copy(lut = null); Recipe.save(this, recipe); renderPreview() }
                LutCatalog.delete(this, listOf(lut.id))
                Haptic.confirm(preview)
                Hud.show(this, Glyph.TRASH, "已删除")
            }.setNegativeButton("取消", null).show()
    }

    // ---- importing --------------------------------------------------------------------------------

    private fun pickFiles() {
        hideKeyboard()
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }, REQUEST_IMPORT)
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK || data == null) return
        val uris = buildList {
            data.clipData?.let { c -> for (i in 0 until c.itemCount) add(c.getItemAt(i).uri) }
            if (isEmpty()) data.data?.let { add(it) }
        }
        if (uris.isNotEmpty()) importAll(uris)
    }

    private fun importAll(uris: List<Uri>) {
        importing.visibility = View.VISIBLE
        importing.alpha = 0f; importing.translationY = dp(30).toFloat()
        importing.animate().alpha(1f).translationY(0f).setDuration(380).setInterpolator(Ease.spring).start()
        importRing.indeterminate = true
        importText.text = "导入中…"
        Executors.newSingleThreadExecutor().execute {
            val done = mutableListOf<LutCatalog.Lut>()
            val failed = mutableListOf<LutImport.Failure>()
            uris.forEachIndexed { k, uri ->
                val name = displayName(uri) ?: "LUT"
                runOnUiThread { importText.text = "导入中 ${k + 1} / ${uris.size}  $name" }
                if (!LutImport.accepts(name)) { failed += LutImport.Failure(name, "不是 LUT 文件（支持 .cube .3dl Hald .zip）"); return@forEachIndexed }
                val group = if (LutImport.extension(name) == "zip") name.substringBeforeLast('.') else LutCatalog.MY_GROUP
                runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        LutImport.read(name, input, { done += LutCatalog.add(this, it, group) }, { failed += it })
                    } ?: throw IllegalStateException("打不开")
                }.onFailure { failed += LutImport.Failure(name, it.message ?: "读取失败") }
            }
            runOnUiThread { importFinished(done, failed) }
        }
    }

    private fun importFinished(done: List<LutCatalog.Lut>, failed: List<LutImport.Failure>) {
        importing.animate().alpha(0f).translationY(dp(30).toFloat()).setDuration(240).withEndAction { importing.visibility = View.GONE }.start()
        LutCatalog.announce()
        if (done.isNotEmpty()) {
            Haptic.success(preview)
            Hud.show(this, Glyph.CHECK_CIRCLE, "导入 ${done.size} 个", Ui.GREEN)
            // show where they went, the first of them chosen
            group = done.first().group
            query = ""; search.setText("")
            refreshChips()
            refilter(animate = true)
            choose(done.first())
        }
        if (failed.isNotEmpty()) {
            AlertDialog.Builder(this).setTitle("${failed.size} 个没有导入")
                .setMessage(failed.take(20).joinToString("\n") { "${it.name}：${it.reason}" })
                .setPositiveButton("好", null).show()
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    // ---- the cards --------------------------------------------------------------------------------

    private val thumbs = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val thumbWorker = Executors.newFixedThreadPool(2)

    /** One LUT: the picture through it, its name; a ring and a check when chosen, a star when kept. */
    private inner class Card : LinearLayout(this@LutActivity) {
        val image = object : ImageView(this@LutActivity) {
            override fun onMeasure(w: Int, h: Int) {
                val width = MeasureSpec.getSize(w)
                // landscape: the height that makes two rows fill the picture's; else 4:3
                setMeasuredDimension(width, if (cardImageHeight > 0) cardImageHeight else width * 3 / 4)
            }
        }.apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val box = FrameLayout(this@LutActivity).apply { background = rounded(Ui.SURFACE, dp(12).toFloat()); clipToOutline = true }
        val ring = View(this@LutActivity)
        val check = IconView(this@LutActivity, Glyph.CHECK, Ui.ON_ACCENT, 12).apply {
            background = rounded(Ui.ACCENT, dp(10).toFloat()); setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val star = IconView(this@LutActivity, Glyph.STAR_FILL, Color.WHITE, 13)
        val noneMark = IconView(this@LutActivity, Glyph.CLOSE, Ui.SUB, 26)
        val name = label("", Ui.FOOTNOTE, Ui.TEXT, bold = true).single()
        var key: String? = null

        init {
            orientation = VERTICAL
            box.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            box.addView(noneMark, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            box.addView(ring, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            box.addView(star, FrameLayout.LayoutParams(dp(13), dp(13), Gravity.TOP or Gravity.START).apply { setMargins(dp(7), dp(7), 0, 0) })
            box.addView(check, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
            addView(box, matchWrap())
            addView(name, matchWrap().apply { topMargin = dp(6) })
        }

        fun bind(lut: LutCatalog.Lut?, chosen: Boolean, fav: Boolean) {
            val k = lut?.id ?: NONE
            val fresh = key != k
            key = k
            name.text = lut?.label ?: "无 LUT"
            name.setTextColor(if (chosen) Ui.ACCENT else Ui.TEXT)
            ring.background = if (chosen) rounded(Color.TRANSPARENT, dp(12).toFloat(), dp(3), Ui.ACCENT) else null
            check.visibility = if (chosen) View.VISIBLE else View.GONE
            star.visibility = if (fav) View.VISIBLE else View.GONE
            noneMark.visibility = if (lut == null) View.VISIBLE else View.GONE
            if (lut == null) { image.setImageBitmap(small); image.alpha = 0.35f; return }
            image.alpha = 1f
            val cached = thumbs.get(k)
            if (cached != null) { image.setImageBitmap(cached); return }
            if (fresh) image.setImageDrawable(null)
            val src = small ?: return
            thumbWorker.execute {
                val table = LutCatalog.table(this@LutActivity, lut.id) ?: return@execute
                val px = IntArray(src.width * src.height)
                src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
                LutImport.apply(table, LutCatalog.size, px, 1f)
                val out = Bitmap.createBitmap(px, src.width, src.height, Bitmap.Config.ARGB_8888)
                thumbs.put(lut.id, out)
                runOnUiThread {
                    if (key != lut.id) return@runOnUiThread
                    image.setImageBitmap(out)
                    // a new print comes up as in the developer
                    image.develop(700)
                }
            }
        }

        fun chosenPop() { check.pop(0.4f); box.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).withEndAction {
            box.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(Ease.bouncy).start() }.start() }
    }

    private inner class Cards : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int): Any? = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val card = convertView as? Card ?: Card()
            val lut = shown[position]
            val chosen = if (lut == null) recipe.lut == null || !recipe.lutOn else lut.id == recipe.lut && recipe.lutOn
            card.bind(lut, chosen, lut != null && LutCatalog.isFavourite(this@LutActivity, lut.id))
            return card
        }
    }

    override fun onDestroy() {
        worker.shutdownNow(); thumbWorker.shutdownNow()
        super.onDestroy()
    }

    /** Applies the app's orientation choice (the activity's own page). */
    private object Prefs0 { fun apply(a: AdaptiveActivity) = org.photoall.tether.develop.Prefs.applyOrientation(a) }

    companion object {
        private const val REQUEST_IMPORT = 41
        private const val FAVOURITES = "\u0001fav"
        private const val RECENT = "\u0001recent"
        private const val ALL = "\u0001all"
        private const val NONE = "\u0001none"
        fun open(context: Context) = context.startActivity(Intent(context, LutActivity::class.java))
    }
}
