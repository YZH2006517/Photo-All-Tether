package org.photoall.tether.ui

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.photoall.tether.develop.Preview
import org.photoall.tether.develop.Roll
import org.photoall.tether.engine.FilmEngine
import org.photoall.tether.engine.FilmParamsCatalog
import org.photoall.tether.engine.Films
import org.photoall.tether.engine.LutCatalog
import org.photoall.tether.engine.Recipe

/**
 * The recipe: the latest raw developed small with it; the films and the papers as rows of 3D
 * models to swipe through (the one in the middle is the one); then printing, look, light,
 * texture and noise as grouped rows, and every other
 * parameter of the engine folded below.
 */
class RecipePage(private val activity: MainActivity) : FrameLayout(activity), Arrangeable {
    private val ctx = activity
    private var recipe = Recipe.load(ctx)
    private val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val previewImage = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val previewSpinner = RingProgress(ctx).apply { indeterminate = true; visibility = View.GONE }
    private val previewEmpty = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(IconView(ctx, Glyph.PHOTO, Ui.DIM, 40))
        addView(ctx.label("冲洗后在此预览", Ui.FOOTNOTE, Ui.SUB).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
    }
    private val summary = ctx.label("", Ui.SUBHEAD, Ui.ACCENT, bold = true).single()
    private val dynamic = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val heading = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val frame = FrameLayout(ctx).apply { background = rounded(Ui.SURFACE, dp(18).toFloat()); clipToOutline = true }
    // the films above, the papers below: rows of 3D models to swipe through, each name under its row
    private val filmRow = CarouselView(ctx, false, JSONArray().apply {
        for (i in 0 until runCatching { FilmEngine.filmStocks.size }.getOrDefault(0)) put(JSONObject(FilmLook.json(FilmLook.of(i))))
    }, recipe.film)
    private val paperRow = CarouselView(ctx, true, PaperLook.all(), recipe.paper, footDp = paperFootDp())
    /** The papers' row and its name over its foot: the row draws on under the name, so turning corners and shadows show. */
    private val paperBox = FrameLayout(ctx)
    private val filmName = ctx.label("", Ui.SUBHEAD, Ui.ACCENT, bold = true).single().apply { gravity = Gravity.CENTER }
    private val paperName = ctx.label("", Ui.SUBHEAD, Ui.ACCENT, bold = true).single().apply { gravity = Gravity.CENTER }
    /** Landscape: the right column's first screen (the films over the papers). */
    private val showcase = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val previewListener: () -> Unit = { renderPreview() }
    private var arrangedWide: Boolean? = null
    private var firstRender = true

    private companion object {
        /** Advanced groups the user opened (kept while the app runs). */
        val expanded = mutableSetOf<String>()
    }

    init {
        setBackgroundColor(Ui.BG)
        heading.addView(ctx.largeTitle("配方"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        heading.addView(ctx.button("调参台", glyph = Glyph.SLIDERS) { TuneActivity.open(ctx) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        heading.addView(ctx.iconButton(Glyph.REFRESH, Ui.SUB, 40, description = "恢复默认配方") {
            AlertDialog.Builder(ctx).setMessage("恢复默认配方？")
                .setPositiveButton("恢复") { _, _ -> update(rebuild = true) { Recipe() } }
                .setNegativeButton("取消", null).show()
        })
        frame.addView(previewImage, frameMatch())
        frame.addView(previewEmpty, frameMatch())
        frame.addView(previewSpinner, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(12), dp(12), 0) })

        filmRow.onCenter = { i -> name(filmName, Films.short(i), i == recipe.film && recipe.filmOn) }
        filmRow.onSelect = { i -> if (i != recipe.film || !recipe.filmOn) update(rebuild = true) { it.copy(film = i, filmOn = true) } }
        filmRow.onOpen = { i -> Cartridge3DActivity.open(ctx, i) }
        paperRow.onCenter = { i -> name(paperName, Films.paperShort(i), i == recipe.paper && recipe.printOn) }
        paperRow.onSelect = { i -> if (i != recipe.paper || !recipe.printOn) update(rebuild = true) { it.copy(paper = i, printOn = true, filmOn = true) } }
        filmName.text = Films.short(recipe.film)
        paperName.text = Films.paperShort(recipe.paper)
        arrange(activity.wide)
        build()
    }

    /**
     * Portrait: the picture on top, the films and the papers under it, the controls after, in
     * one scroll. Landscape: the picture large on the left staying put; on the right a first
     * screen of the films over the papers — the papers standing on the line of the picture's
     * foot, their name on the line of the picture's caption — and the controls scrolling up
     * under it.
     */
    override fun arrange(wide: Boolean) {
        if (arrangedWide == wide) return
        val again = arrangedWide != null
        arrangedWide = wide
        listOf(heading, summary, frame, filmRow, filmName, paperBox, showcase, dynamic, content)
            .forEach { (it.parent as? ViewGroup)?.removeView(it) }
        if (paperBox.childCount == 0) {
            paperBox.addView(paperRow, frameMatch())
            paperBox.addView(paperName, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
        content.removeAllViews()
        showcase.removeAllViews()
        removeAllViews()
        if (!wide) {
            content.setPadding(dp(16), dp(6), dp(16), dp(48))
            content.addView(heading, matchWrap())
            content.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250)).apply { topMargin = dp(10) })
            content.addView(summary, matchWrap().apply { topMargin = dp(10) })
            content.addView(filmRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)).apply {
                topMargin = dp(8); marginStart = -dp(16); marginEnd = -dp(16)
            })
            content.addView(filmName, matchWrap().apply { topMargin = dp(2) })
            content.addView(paperBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160 + paperFootDp())).apply {
                topMargin = dp(14); marginStart = -dp(16); marginEnd = -dp(16)
            })
            content.addView(dynamic)
            val scroll = ScrollView(ctx).apply { isFillViewport = true; addView(content) }
            // the first screen holds the picture and both rows whole, whatever the phone's height
            // (or its display size): their heights are shares of what the screen has
            scroll.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.post { fitPortrait(v.height) } }
            addView(scroll, frameMatch())
        } else {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val left = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(8), dp(8), BOTTOM)
                addView(heading, matchWrap())
                addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })
                addView(summary, matchWrap().apply { topMargin = CAPTION_GAP })
            }
            row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f))
            content.setPadding(dp(8), dp(8), dp(16), dp(48))
            // the first screen: films over papers, each its name under it; the papers' row ends
            // on the picture's foot and its name sits on the picture's caption line
            showcase.addView(filmRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.1f).apply {
                marginStart = -dp(8); marginEnd = -dp(16)
            })
            showcase.addView(filmName, matchWrap().apply { topMargin = dp(2) })
            // the papers' row runs down to the column's foot, its name over it at the bottom: the
            // models stand on the picture's foot line, the name on the picture's caption line
            showcase.addView(paperBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(6); marginStart = -dp(8); marginEnd = -dp(16)
            })
            content.addView(showcase, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))
            content.addView(dynamic)
            val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(content) }
            // as tall as the screen less the same top and foot as the left column
            scroll.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val h = v.height - content.paddingTop - BOTTOM
                if (h > 0 && showcase.layoutParams.height != h) v.post { showcase.layoutParams = showcase.layoutParams.apply { height = h } }
            }
            row.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(row, frameMatch())
        }
        if (again) build()
    }

    /** A row's name changes: the old slides out, the new in; the chosen one in the accent. */
    private fun name(view: TextView, text: String, chosen: Boolean) {
        view.setTextColor(if (chosen) Ui.ACCENT else Ui.SUB)
        if (view.text == text) return
        if (Ease.reduced(ctx)) { view.text = text; return }
        view.animate().cancel()
        view.animate().alpha(0f).translationY(-dp(4).toFloat()).setDuration(90).withEndAction {
            view.text = text
            view.translationY = dp(4).toFloat()
            view.animate().alpha(1f).translationY(0f).setDuration(160).setInterpolator(Ease.out).start()
        }.start()
    }

    private val BOTTOM get() = dp(14)

    /**
     * Portrait: shares out the screen's height (less the title, the captions and the gaps)
     * between the picture, the films and the papers, so all three are whole on opening.
     */
    private fun fitPortrait(screen: Int) {
        if (arrangedWide != false || screen <= 0 || heading.height == 0) return
        val foot = dp(paperFootDp())
        val fixed = content.paddingTop + heading.height + dp(10) + dp(10) + summary.height +
            dp(8) + dp(2) + filmName.height + dp(14) + foot + dp(12)
        val flex = (screen - fixed).coerceAtLeast(dp(380))
        val frameH = (flex * 0.40f).toInt().coerceIn(dp(140), dp(260))
        val filmH = (flex * 0.32f).toInt().coerceIn(dp(120), dp(210))
        val paperH = (flex - frameH - filmH).coerceIn(dp(100), dp(180))
        fun set(v: View, h: Int) {
            val lp = v.layoutParams ?: return
            if (lp.height != h) { lp.height = h; v.layoutParams = lp }
        }
        set(frame, frameH)
        set(filmRow, filmH)
        set(paperBox, paperH + foot)
    }

    /** The name's height and the gap above it, in dp: where the papers stand above the row's foot. */
    private fun paperFootDp(): Int {
        val probe = ctx.label("Endura", Ui.SUBHEAD, bold = true).single()
        probe.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return Math.round(probe.measuredHeight / resources.displayMetrics.density) + 10
    }
    private val CAPTION_GAP get() = dp(10)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Preview.addListener(previewListener)
        recipe = Recipe.load(ctx)
        filmRow.go(recipe.film, false)
        paperRow.go(recipe.paper, false)
        build()
        renderPreview()
    }

    override fun onDetachedFromWindow() {
        Preview.removeListener(previewListener)
        super.onDetachedFromWindow()
    }

    private fun update(rebuild: Boolean = false, change: (Recipe) -> Recipe) {
        val before = recipe
        recipe = change(recipe)
        Recipe.save(ctx, recipe)
        summary.text = recipe.summary()
        if (recipe.film != before.film) {
            filmRow.go(recipe.film)
            Roll.follow(ctx, recipe.film)
        }
        if (recipe.paper != before.paper) paperRow.go(recipe.paper)
        name(filmName, Films.short(recipe.film), recipe.filmOn)
        name(paperName, Films.paperShort(recipe.paper), recipe.filmOn && recipe.printOn)
        if (rebuild) build()
        renderPreview()
    }

    private fun renderPreview() {
        summary.text = recipe.summary()
        val source = Preview.source(ctx)
        previewEmpty.visibility = if (source == null) View.VISIBLE else View.GONE
        if (source == null) return
        previewSpinner.visibility = View.VISIBLE
        Preview.render(ctx, recipe) { bitmap: Bitmap?, _: String? ->
            previewSpinner.visibility = View.GONE
            if (bitmap != null) {
                val had = previewImage.drawable != null
                previewImage.setImageBitmap(bitmap)
                // the first picture comes up like a print; later ones just change
                if (!had || firstRender) previewImage.develop(1600)
                firstRender = false
            }
        }
    }

    // ---- the controls ----------------------------------------------------------------------------

    // made on first use, not as a field initialiser: the page's init (above) builds it already,
    // before the initialisers down here have run
    private var controlsMade: RecipeControls? = null
    private val controls: RecipeControls
        get() = controlsMade ?: RecipeControls(ctx, { recipe }, { rebuild, change -> update(rebuild, change) }, { pickLut() })
            .also { controlsMade = it }

    private fun build() {
        summary.text = recipe.summary()
        controls.build(dynamic)
    }

    /** The LUT page: every LUT as a small print of the latest picture, import and manage. */
    private fun pickLut() = LutActivity.open(ctx)

    /** Back from a page that changed the recipe (the LUT page): show it as it is now. */
    fun reload() {
        val now = Recipe.load(ctx)
        if (now.toJson().toString() == recipe.toJson().toString()) return
        recipe = now
        build()
        renderPreview()
    }
}

