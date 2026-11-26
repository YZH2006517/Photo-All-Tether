package org.photoall.tether.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import org.photoall.tether.engine.FilmParamsCatalog
import org.photoall.tether.engine.LutCatalog
import org.photoall.tether.engine.Recipe

/**
 * The recipe's controls (film and paper switches, look, light, texture, noise, every advanced
 * parameter), built into a column: the recipe page's, and the immersive view's recipe sheet.
 * [recipe] reads the recipe as it is; [update] changes it (rebuild: the controls again).
 * [top]: something of the host's at the head (the immersive sheet's film and paper chips).
 */
class RecipeControls(
    private val ctx: Context,
    private val read: () -> Recipe,
    private val update: (rebuild: Boolean, change: (Recipe) -> Recipe) -> Unit,
    private val pickLut: () -> Unit,
    private val top: ((LinearLayout, Recipe) -> Unit)? = null,
) {
    private val recipe get() = read()
    private fun update(rebuild: Boolean = false, change: (Recipe) -> Recipe) = update.invoke(rebuild, change)

    private companion object {
        /** Advanced groups the user opened (kept while the app runs). */
        val expanded = mutableSetOf<String>()
    }

    /** Fills [into] with every control, for the recipe as it is now. */
    fun build(into: LinearLayout) {
        val dynamic = into
        dynamic.removeAllViews()
        val r = recipe
        top?.invoke(dynamic, r)

        // film and paper
        dynamic.addView(ctx.section("胶片与相纸"))
        val stock = ctx.card()
        stock.addView(ctx.toggleRow("胶片模拟", null, r.filmOn, Glyph.FILM, Ui.ACCENT) { on -> update(rebuild = true) { it.copy(filmOn = on) } })
        if (r.filmOn) {
            stock.addView(ctx.separator(42))
            stock.addView(ctx.toggleRow("印放到相纸", null, r.printOn, Glyph.PHOTO, Ui.INDIGO) { on -> update(rebuild = true) { it.copy(printOn = on) } })
            stock.addView(ctx.separator(42))
            stock.addView(ctx.listRow("放大机滤色", null, Glyph.CONTRAST, Ui.TEAL))
            stock.addView(Segmented(ctx, Recipe.balanceNames.map { it.removePrefix("保留") }, r.balance.coerceIn(0, 2)) { i ->
                update(rebuild = true) { it.copy(balance = i) }
            }, matchWrap().apply { bottomMargin = ctx.dp(8) })
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
        // where the brightness starts: the camera JPEG's (the reference meter when the raw has
        // none), the reference spektrafilm's own meter, or the raw as exposed
        light.addView(ctx.listRow("起始亮度", null, Glyph.CAMERA, Ui.BLUE))
        light.addView(Segmented(ctx, Recipe.meterNames, r.meter.coerceIn(0, Recipe.meterNames.lastIndex)) { i ->
            update { it.copy(meter = i) }
        }, matchWrap().apply { bottomMargin = ctx.dp(8) })
        // single-frame HDR tone: exposure fusion before the film
        light.addView(ctx.sliderRow("局部影调（HDR）", 0f, 1f, r.localTone, 0.05f, { if (it == 0f) "关" else "%.2f".format(it) },
            default = 0f, glyph = Glyph.CONTRAST, tint = Ui.INDIGO) { v -> update { it.copy(localTone = v) } })
        light.addView(ctx.separator(42))
        light.addView(Segmented(ctx, Recipe.whiteNames, r.white) { i -> update(rebuild = true) { it.copy(white = i) } },
            matchWrap().apply { topMargin = ctx.dp(10); bottomMargin = ctx.dp(6) })
        if (r.white == Recipe.WHITE_KELVIN)
            light.addView(ctx.sliderRow("色温", 2500f, 10000f, r.kelvin, 50f, { "%.0f K".format(it) }, default = 5500f, glyph = Glyph.THERMO, tint = Ui.PINK) { v ->
                update { it.copy(kelvin = v) }
            })
        if (r.white != Recipe.WHITE_CAMERA)
            light.addView(ctx.sliderRow("色调", -1f, 1f, r.tint, 0.05f, { "%+.2f".format(it) }, default = 0f, indent = r.white == Recipe.WHITE_KELVIN) { v -> update { it.copy(tint = v) } })
        // 微调 (as Photo All's import): a little warmer / cooler, greener / more magenta, on top
        // of the white balance; RAW and JPEG alike
        light.addView(ctx.separator(42))
        light.addView(ctx.sliderRow("冷暖微调", -1f, 1f, r.value("stage temp"), 0.01f, FilmParamsCatalog.warmth, default = 0f,
            glyph = Glyph.THERMO, tint = Ui.YELLOW) { v -> update { it.withParam("stage temp", v) } })
        light.addView(ctx.sliderRow("色调微调", -1f, 1f, r.value("stage tint"), 0.01f, FilmParamsCatalog.greenness, default = 0f,
            indent = true) { v -> update { it.withParam("stage tint", v) } })
        dynamic.addView(light, matchWrap())

        // texture
        dynamic.addView(ctx.section("质感"))
        val tex = ctx.card()
        tex.addView(ctx.toggleRow("颗粒", null, r.grain, Glyph.GRAIN, Ui.GREY) { on -> update(rebuild = true) { it.copy(grain = on) } })
        if (r.grain) {
            val d = Recipe()
            // 1 / 1 is the reference spektrafilm's grain
            tex.addView(ctx.sliderRow("大小", 0.1f, 2.5f, r.grainSize, 0.05f, default = d.grainSize, indent = true) { v -> update { it.copy(grainSize = v) } })
            tex.addView(ctx.sliderRow("强度", 0f, 3f, r.grainAmount, 0.05f, default = d.grainAmount, indent = true) { v -> update { it.copy(grainAmount = v) } })
        }
        tex.addView(ctx.separator(42))
        tex.addView(ctx.toggleRow("光晕", null, r.halation, Glyph.HALO, Ui.RED) { on -> update(rebuild = true) { it.copy(halation = on) } })
        if (r.halation) tex.addView(ctx.sliderRow("强度", 0f, 4f, r.halationAmount, 0.1f, default = 1f, indent = true) { v -> update { it.copy(halationAmount = v) } })
        tex.addView(ctx.separator(42))
        tex.addView(ctx.toggleRow("DIR 耦合剂", null, r.couplers, Glyph.CONTRAST, Ui.TEAL) { on -> update { it.copy(couplers = on) } })
        tex.addView(ctx.separator(42))
        // a diffusion filter in front of the lens: Pro-Mist, Black Pro-Mist, Glimmerglass, Cinebloom
        tex.addView(ctx.listRow("扩散滤镜", null, Glyph.SUN, Ui.YELLOW))
        tex.addView(Segmented(ctx, Recipe.diffusionNames, r.diffusion.coerceIn(0, Recipe.diffusionNames.lastIndex)) { i ->
            update(rebuild = (i == 0) != (r.diffusion == 0)) { it.copy(diffusion = i) }
        }, matchWrap().apply { bottomMargin = ctx.dp(8) })
        if (r.diffusion > 0)
            tex.addView(ctx.sliderRow("强度", 0f, 1f, r.diffusionAmount, 0.05f, default = 0.5f, indent = true) { v -> update { it.copy(diffusionAmount = v) } })
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
        for (group in FilmParamsCatalog.groups) dynamic.addView(advancedGroup(group), matchWrap().apply { bottomMargin = ctx.dp(8) })
    }

    /** One folded group of advanced parameters: a header (with what changed) and its sliders. */
    private fun advancedGroup(group: FilmParamsCatalog.Group): View {
        val box = ctx.card()
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(44)
        }
        val chevron = IconView(ctx, Glyph.CHEVRON_RIGHT, Ui.SUB, 16)
        val changedBadge = ctx.label("", Ui.CAPTION, Ui.ON_ACCENT, bold = true).apply {
            background = rounded(Ui.ACCENT, ctx.dp(9).toFloat())
            setPadding(ctx.dp(7), ctx.dp(2), ctx.dp(7), ctx.dp(2))
        }
        val reset = ctx.iconButton(Glyph.REFRESH, Ui.ACCENT, 34, filled = false, description = "恢复本组默认") {}
        header.addView(ctx.label(group.title, Ui.BODY), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(changedBadge)
        header.addView(reset, LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34)).apply { marginStart = ctx.dp(6) })
        header.addView(chevron, LinearLayout.LayoutParams(ctx.dp(16), ctx.dp(16)).apply { marginStart = ctx.dp(6) })
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
                val choices = prm.choices
                val row: View = if (choices != null) LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(ctx.listRow(prm.label))
                    val at = choices.indexOfFirst { kotlin.math.abs(it.second - v) < 1e-3f }.coerceAtLeast(0)
                    addView(Segmented(ctx, choices.map { it.first }, at) { i ->
                        update { it.withParam(prm.key, choices[i].second) }
                        refreshHeader()
                    }, matchWrap().apply { bottomMargin = ctx.dp(8) })
                } else if (prm.toggle) ctx.toggleRow(prm.label, null, v >= 0.5f) { on ->
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
            Haptic.tick(header)
            if (!expanded.remove(group.id)) expanded += group.id
            refreshHeader()
            fill()
        }
        reset.setOnClickListener {
            Haptic.confirm(reset)
            update { it.copy(extra = it.extra - group.params.map { prm -> prm.key }.toSet()) }
            refreshHeader()
            fill()
        }
        chevron.rotation = if (group.id in expanded) 90f else 0f
        refreshHeader()
        fill()
        return box
    }

}
