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
import org.photoall.tether.develop.Preview
import org.photoall.tether.develop.Roll
import org.photoall.tether.engine.FilmEngine
import org.photoall.tether.engine.FilmParamsCatalog
import org.photoall.tether.engine.Films
import org.photoall.tether.engine.LutCatalog
import org.photoall.tether.engine.Recipe

/**
 * The recipe: the latest raw developed small with it, the stock's cartridge turning above a
 * carousel of films, then paper, look, light, texture and noise as grouped rows, and every other
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
    private val cartridge = CartridgeView(ctx, recipe.film, 1f).apply { onTap = { Cartridge3DActivity.open(ctx, recipe.film) } }
    private val stage = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private var filmStrip: HorizontalScrollView? = null
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
        heading.addView(ctx.iconButton(Glyph.REFRESH, Ui.SUB, 40, description = "恢复默认配方") {
            AlertDialog.Builder(ctx).setMessage("恢复默认配方？")
                .setPositiveButton("恢复") { _, _ -> update(rebuild = true) { Recipe() } }
                .setNegativeButton("取消", null).show()
        })
        frame.addView(previewImage, frameMatch())
        frame.addView(previewEmpty, frameMatch())
        frame.addView(previewSpinner, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(12), dp(12), 0) })

        // the stock's cartridge, and the two ways to hold it
        val stageButtons = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(ctx.iconButton(Glyph.CUBE, Ui.ACCENT, 44, description = "3D 细看") { Cartridge3DActivity.open(ctx, recipe.film) })
            addView(ctx.iconButton(Glyph.ROLL, Ui.ACCENT, 44, description = "装进相机") { LoadFilmActivity.open(ctx, recipe.film) },
                LinearLayout.LayoutParams(dp(44), dp(44)).apply { topMargin = dp(12) })
        }
        val stageRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        stageRow.addView(View(ctx), LinearLayout.LayoutParams(dp(44), 1))
        stageRow.addView(cartridge, LinearLayout.LayoutParams(0, dp(200), 1f))
        stageRow.addView(stageButtons, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT))
        stage.addView(stageRow, matchWrap())
        arrange(activity.wide)
        build()
    }

    /**
     * Portrait: the picture on top, the cartridge and the controls under it in one scroll.
     * Landscape: the picture large on the left staying put; the cartridge and controls beside it.
     */
    override fun arrange(wide: Boolean) {
        if (arrangedWide == wide) return
        arrangedWide = wide
        listOf(heading, summary, frame, stage, dynamic, content).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        content.removeAllViews()
        removeAllViews()
        if (!wide) {
            content.setPadding(dp(16), dp(6), dp(16), dp(48))
            content.addView(heading, matchWrap())
            content.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250)).apply { topMargin = dp(10) })
            content.addView(summary, matchWrap().apply { topMargin = dp(10) })
            content.addView(stage, matchWrap().apply { topMargin = dp(4) })
            content.addView(dynamic)
            addView(ScrollView(ctx).apply { isFillViewport = true; addView(content) }, frameMatch())
        } else {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val left = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(8), dp(8), dp(14))
                addView(heading, matchWrap())
                addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })
                addView(summary, matchWrap().apply { topMargin = dp(10) })
            }
            row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f))
            content.setPadding(dp(8), dp(8), dp(16), dp(48))
            content.addView(stage, matchWrap())
            content.addView(dynamic)
            row.addView(ScrollView(ctx).apply { addView(content) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(row, frameMatch())
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Preview.addListener(previewListener)
        recipe = Recipe.load(ctx)
        cartridge.setFilm(recipe.film, false)
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
            cartridge.setFilm(recipe.film, true)
            Roll.follow(ctx, recipe.film)
        }
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

    private fun build() {
        dynamic.removeAllViews()
        val r = recipe
        summary.text = r.summary()

        // the film carousel
        val films = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(2), dp(6), dp(2), dp(10)) }
        val count = runCatching { FilmEngine.filmStocks.size }.getOrDefault(0)
        for (i in 0 until count) films.addView(FilmCard(i, i == r.film && r.filmOn),
            LinearLayout.LayoutParams(dp(124), dp(156)).apply { marginEnd = dp(10) })
        // the carousel runs to the screen's edges, its first and last cards on the page's margin
        films.setPadding(dp(16), dp(6), dp(16), dp(10))
        val strip = ctx.hScroll(films)
        val bleed = if (arrangedWide == true) dp(8) else dp(16)
        dynamic.addView(strip, matchWrap().apply { marginStart = -bleed; marginEnd = -dp(16) })
        filmStrip = strip
        strip.post {
            val sel = films.getChildAt(r.film) ?: return@post
            strip.scrollTo((sel.left - (strip.width - sel.width) / 2).coerceAtLeast(0), 0)
        }

        // film and paper
        dynamic.addView(ctx.section("胶片与相纸"))
        val stock = ctx.card()
        stock.addView(ctx.toggleRow("胶片模拟", null, r.filmOn, Glyph.FILM, Ui.ACCENT) { on -> update(rebuild = true) { it.copy(filmOn = on) } })
        if (r.filmOn) {
            stock.addView(ctx.separator(42))
            stock.addView(ctx.toggleRow("印放到相纸", null, r.printOn, Glyph.PHOTO, Ui.INDIGO) { on -> update(rebuild = true) { it.copy(printOn = on) } })
            if (r.printOn) {
                val papers = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(10)) }
                for (i in 0 until runCatching { FilmEngine.papers.size }.getOrDefault(0)) {
                    papers.addView(Chip(ctx, Films.paperShort(i)).apply {
                        on = i == r.paper
                        onClick = { update(rebuild = true) { it.copy(paper = i) } }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
                }
                stock.addView(ctx.hScroll(papers))
            }
            stock.addView(ctx.separator(42))
            stock.addView(ctx.listRow("放大机滤色", null, Glyph.CONTRAST, Ui.TEAL))
            stock.addView(Segmented(ctx, Recipe.balanceNames.map { it.removePrefix("保留") }, r.balance.coerceIn(0, 2)) { i ->
                update(rebuild = true) { it.copy(balance = i) }
            }, matchWrap().apply { bottomMargin = dp(8) })
        }
        dynamic.addView(stock, matchWrap())

        // the look
        dynamic.addView(ctx.section("风格"))
        val look = ctx.card()
        look.addView(ctx.listRow(if (r.lut == null) "无 LUT" else LutCatalog.label(r.lut), null, Glyph.SPARKLES, Ui.PURPLE) { pickLut() })
        if (r.lut != null) {
            look.addView(ctx.sliderRow("强度", 0f, 1f, r.lutStrength, 0.05f, { "%.0f%%".format(it * 100) }, indent = true) { v -> update { it.copy(lutStrength = v) } })
            look.addView(ctx.separator(42))
            look.addView(ctx.toggleRow("使用 LUT", null, r.lutOn, Glyph.CHECK_CIRCLE, Ui.GREEN) { on -> update { it.copy(lutOn = on) } })
        }
        dynamic.addView(look, matchWrap())

        // light
        dynamic.addView(ctx.section("曝光与白平衡"))
        val light = ctx.card()
        light.addView(ctx.sliderRow("曝光", -3f, 3f, r.ev, 1f / 3f, { "%+.1f".format(it) }, default = 0f, glyph = Glyph.EV, tint = Ui.ACCENT) { v ->
            update { it.copy(ev = v) }
        })
        light.addView(ctx.separator(42))
        light.addView(ctx.toggleRow("对齐相机亮度", null, r.matchCamera, Glyph.CAMERA, Ui.BLUE) { on -> update { it.copy(matchCamera = on) } })
        light.addView(ctx.separator(42))
        light.addView(Segmented(ctx, Recipe.whiteNames, r.white) { i -> update(rebuild = true) { it.copy(white = i) } },
            matchWrap().apply { topMargin = dp(10); bottomMargin = dp(6) })
        if (r.white == Recipe.WHITE_KELVIN)
            light.addView(ctx.sliderRow("色温", 2500f, 10000f, r.kelvin, 50f, { "%.0f K".format(it) }, default = 5500f, glyph = Glyph.THERMO, tint = Ui.PINK) { v ->
                update { it.copy(kelvin = v) }
            })
        if (r.white != Recipe.WHITE_CAMERA)
            light.addView(ctx.sliderRow("色调", -1f, 1f, r.tint, 0.05f, { "%+.2f".format(it) }, default = 0f, indent = r.white == Recipe.WHITE_KELVIN) { v -> update { it.copy(tint = v) } })
        dynamic.addView(light, matchWrap())

        // texture
        dynamic.addView(ctx.section("质感"))
        val tex = ctx.card()
        tex.addView(ctx.toggleRow("颗粒", null, r.grain, Glyph.GRAIN, Ui.GREY) { on -> update(rebuild = true) { it.copy(grain = on) } })
        if (r.grain) tex.addView(ctx.sliderRow("大小", 0.2f, 2.5f, r.grainSize, 0.05f, default = 1f, indent = true) { v -> update { it.copy(grainSize = v) } })
        tex.addView(ctx.separator(42))
        tex.addView(ctx.toggleRow("光晕", null, r.halation, Glyph.HALO, Ui.RED) { on -> update(rebuild = true) { it.copy(halation = on) } })
        if (r.halation) tex.addView(ctx.sliderRow("强度", 0f, 4f, r.halationAmount, 0.1f, default = 1f, indent = true) { v -> update { it.copy(halationAmount = v) } })
        tex.addView(ctx.separator(42))
        tex.addView(ctx.toggleRow("DIR 耦合剂", null, r.couplers, Glyph.CONTRAST, Ui.TEAL) { on -> update { it.copy(couplers = on) } })
        dynamic.addView(tex, matchWrap())

        // noise
        dynamic.addView(ctx.section("降噪"))
        val noise = ctx.card()
        noise.addView(ctx.sliderRow("RAW", 0f, 0.6f, r.rawDenoise, 0.05f, { if (it == 0f) "关" else "%.2f".format(it) }, glyph = Glyph.WAVE, tint = Ui.BLUE) { v ->
            update { it.copy(rawDenoise = v) }
        })
        noise.addView(ctx.separator(42))
        noise.addView(ctx.sliderRow("彩色", 0f, 1f, r.chromaDenoise, 0.05f, { if (it == 0f) "关" else "%.2f".format(it) }, glyph = Glyph.SPARKLES, tint = Ui.PURPLE) { v ->
            update { it.copy(chromaDenoise = v) }
        })
        dynamic.addView(noise, matchWrap())

        // everything else the engine has
        dynamic.addView(ctx.section("高级参数"))
        for (group in FilmParamsCatalog.groups) dynamic.addView(advancedGroup(group), matchWrap().apply { bottomMargin = dp(8) })
    }

    /**
     * A film in the carousel: its box's colours as a band, the name, the speed large, a glyph for
     * its kind. The chosen one stands out (larger, ringed); choosing turns the cartridge.
     */
    private inner class FilmCard(val index: Int, chosen: Boolean) : LinearLayout(ctx) {
        init {
            val look = FilmLook.of(index)
            orientation = VERTICAL
            background = rounded(Ui.SURFACE, dp(16).toFloat(), if (chosen) dp(2) else 0, Ui.ACCENT)
            clipToOutline = true
            addView(object : View(ctx) {
                private val p = Paint(Paint.ANTI_ALIAS_FLAG)
                private val r = RectF()
                override fun onDraw(c: Canvas) {
                    p.color = look.base
                    c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
                    p.color = look.band
                    c.drawRect(0f, 0f, width.toFloat(), height * 0.3f, p)
                    c.drawRect(0f, height * 0.86f, width.toFloat(), height.toFloat(), p)
                    p.color = look.ink
                    p.textSize = height * 0.34f
                    p.typeface = Ui.bold
                    p.textAlign = Paint.Align.CENTER
                    c.drawText(look.iso, width / 2f, height * 0.73f, p)
                    r.set(0f, 0f, 0f, 0f)
                }
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
            val text = LinearLayout(ctx).apply {
                orientation = VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                addView(ctx.label(Films.short(index), Ui.SUBHEAD, Ui.TEXT, bold = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1.05f) })
                val row = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(IconView(ctx, kindGlyph(index), if (chosen) Ui.ACCENT else Ui.SUB, 14), LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(5) })
                row.addView(ctx.label(Films.kind(index), Ui.CAPTION, Ui.SUB, bold = true).single())
                addView(row, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
            }
            addView(text, matchWrap())
            if (!chosen) { scaleX = 0.94f; scaleY = 0.94f; alpha = 0.85f }
            contentDescription = Films.name(index)
            setOnClickListener {
                if (index == recipe.film && recipe.filmOn) { Cartridge3DActivity.open(ctx, index); return@setOnClickListener }
                Haptic.select(this)
                update(rebuild = true) { it.copy(film = index, filmOn = true) }
            }
            pressable()
        }
    }

    private fun kindGlyph(i: Int): Glyph = when (Films.kind(i)) {
        "黑白" -> Glyph.CONTRAST
        "反转片" -> Glyph.SUN
        "电影负片" -> Glyph.CAMERA
        else -> Glyph.FILM
    }

    /** One folded group of advanced parameters: a header (with what changed) and its sliders. */
    private fun advancedGroup(group: FilmParamsCatalog.Group): View {
        val box = ctx.card()
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
        }
        val chevron = IconView(ctx, Glyph.CHEVRON_RIGHT, Ui.SUB, 16)
        val changedBadge = ctx.label("", Ui.CAPTION, Ui.ON_ACCENT, bold = true).apply {
            background = rounded(Ui.ACCENT, dp(9).toFloat())
            setPadding(dp(7), dp(2), dp(7), dp(2))
        }
        val reset = ctx.iconButton(Glyph.REFRESH, Ui.ACCENT, 34, filled = false, description = "恢复本组默认") {}
        header.addView(ctx.label(group.title, Ui.BODY), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(changedBadge)
        header.addView(reset, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(6) })
        header.addView(chevron, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(6) })
        box.addView(header, matchWrap())
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(body, matchWrap())

        fun refreshHeader() {
            val n = recipe.changed(group)
            changedBadge.text = n.toString()
            changedBadge.visibility = if (n > 0) View.VISIBLE else View.GONE
            reset.visibility = if (n > 0) View.VISIBLE else View.GONE
            chevron.animate().rotation(if (group.id in expanded) 90f else 0f).setDuration(260).setInterpolator(Ease.spring).start()
        }
        fun fill() {
            body.removeAllViews()
            if (group.id !in expanded) return
            for (prm in group.params) {
                val v = recipe.value(prm.key)
                val row = if (prm.toggle) ctx.toggleRow(prm.label, null, v >= 0.5f) { on ->
                    update { it.withParam(prm.key, if (on) 1f else 0f) }
                    refreshHeader()
                } else ctx.sliderRow(prm.label, prm.min, prm.max, v, prm.step, prm.format, default = prm.default) { nv ->
                    update { it.withParam(prm.key, nv) }
                    refreshHeader()
                }
                body.addView(row)
                row.rise(body.childCount * 25L, 8)
            }
        }
        header.setOnClickListener {
            Haptic.tick(this)
            if (!expanded.remove(group.id)) expanded += group.id
            refreshHeader()
            fill()
        }
        reset.setOnClickListener {
            Haptic.confirm(this)
            update { it.copy(extra = it.extra - group.params.map { prm -> prm.key }.toSet()) }
            refreshHeader()
            fill()
        }
        chevron.rotation = if (group.id in expanded) 90f else 0f
        refreshHeader()
        fill()
        return box
    }

    private fun pickLut() {
        LutCatalog.load(ctx)
        val groups = listOf("无 LUT") + LutCatalog.groups()
        AlertDialog.Builder(ctx).setTitle("风格 LUT").setItems(groups.toTypedArray()) { _, g ->
            if (g == 0) {
                update(rebuild = true) { it.copy(lut = null) }
                return@setItems
            }
            val luts = LutCatalog.all.filter { it.group == groups[g] }
            AlertDialog.Builder(ctx).setTitle(groups[g]).setItems(luts.map { it.label }.toTypedArray()) { _, i ->
                update(rebuild = true) { it.copy(lut = luts[i].id, lutOn = true) }
            }.setNegativeButton("返回") { _, _ -> pickLut() }.show()
        }.show()
    }
}

