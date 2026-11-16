package org.photoall.tether.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.photoall.tether.camera.CameraHub
import org.photoall.tether.develop.Prefs
import org.photoall.tether.develop.Preview
import org.photoall.tether.engine.Films
import org.photoall.tether.engine.FilmEngine
import org.photoall.tether.engine.LutCatalog
import org.photoall.tether.engine.Recipe

/**
 * The recipe every new photo is developed with: film, paper and look, exposure and white
 * balance, texture, and how the result is saved. The picture on top is the latest raw
 * developed small with it, redrawn as anything changes.
 */
class RecipePage(private val activity: MainActivity) : FrameLayout(activity), Arrangeable {
    private val ctx = activity
    private var recipe = Recipe.load(ctx)
    private val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val previewImage = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val previewSpinner = ProgressBar(ctx).apply { isIndeterminate = true; visibility = View.GONE }
    private val previewText = ctx.label("", 12f, Ui.SUB).apply { gravity = Gravity.CENTER }
    private val summary = ctx.label("", 15f, Ui.ACCENT, bold = true)
    private val dynamic = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val previewListener: () -> Unit = { renderPreview() }

    private val heading = ctx.label("配方", 22f, bold = true)
    private val frame = FrameLayout(ctx).apply { background = rounded(0xFF000000.toInt(), dp(14).toFloat()); clipToOutline = true }
    private var arrangedWide: Boolean? = null

    init {
        setBackgroundColor(Ui.BG)
        summary.setPadding(0, dp(6), 0, dp(10))
        frame.addView(previewImage, frameMatch())
        frame.addView(previewSpinner, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(10), dp(10), 0) })
        frame.addView(previewText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(dp(20), 0, dp(20), 0)
        })
        arrange(activity.wide)
        build()
    }

    /**
     * Portrait: the picture on top and the controls under it, all in one scroll. Landscape: the
     * picture large on the left, at full height and staying put, the controls scrolling beside it,
     * so every change shows while the hand is on its slider.
     */
    override fun arrange(wide: Boolean) {
        if (arrangedWide == wide) return
        arrangedWide = wide
        listOf(heading, summary, frame, dynamic, content).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        content.removeAllViews()
        removeAllViews()
        if (!wide) {
            content.setPadding(dp(14), dp(8), dp(14), dp(40))
            content.addView(heading)
            content.addView(summary)
            content.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)))
            content.addView(dynamic)
            addView(ScrollView(ctx).apply { isFillViewport = true; addView(content) }, frameMatch())
        } else {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val left = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(8), dp(12))
                addView(heading)
                addView(summary)
                addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
            row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.25f))
            content.setPadding(dp(8), 0, dp(14), dp(40))
            content.addView(dynamic)
            row.addView(ScrollView(ctx).apply { addView(content) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(row, frameMatch())
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Preview.addListener(previewListener)
        recipe = Recipe.load(ctx)
        build()
        renderPreview()
    }

    override fun onDetachedFromWindow() {
        Preview.removeListener(previewListener)
        super.onDetachedFromWindow()
    }

    private fun update(rebuild: Boolean = false, change: (Recipe) -> Recipe) {
        recipe = change(recipe)
        Recipe.save(ctx, recipe)
        summary.text = recipe.summary()
        if (rebuild) build()
        renderPreview()
    }

    private fun renderPreview() {
        summary.text = recipe.summary()
        val source = Preview.source(ctx)
        if (source == null) {
            previewText.text = "冲洗第一张 RAW 后，这里用它实时预览配方。\n也可以在「暗房 → 原片」里打开一张，设为预览图。"
            previewText.visibility = View.VISIBLE
            return
        }
        previewSpinner.visibility = View.VISIBLE
        Preview.render(ctx, recipe) { bitmap: Bitmap?, error: String? ->
            previewSpinner.visibility = View.GONE
            if (bitmap != null) {
                previewImage.setImageBitmap(bitmap)
                previewText.visibility = View.GONE
            } else if (error != null) {
                previewText.text = "预览失败：$error"
                previewText.visibility = View.VISIBLE
            }
        }
    }

    // ---- the controls ----------------------------------------------------------------------------

    private fun build() {
        dynamic.removeAllViews()
        val r = recipe
        summary.text = r.summary()

        // film
        dynamic.addView(ctx.section("胶卷"))
        val films = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val count = runCatching { FilmEngine.filmStocks.size }.getOrDefault(0)
        for (i in 0 until count) films.addView(filmCard(i, i == r.film && r.filmOn))
        dynamic.addView(ctx.hScroll(films))
        dynamic.addView(ctx.toggleRow("胶片模拟", "关闭则直出：RAW 加中性影调曲线，只保留 LUT", r.filmOn) { on ->
            update(rebuild = true) { it.copy(filmOn = on) }
        })

        // paper
        if (r.filmOn) {
            dynamic.addView(ctx.section("相纸"))
            val papers = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until runCatching { FilmEngine.papers.size }.getOrDefault(0)) {
                papers.addView(Chip(ctx, Films.paper(i)).apply {
                    on = i == r.paper && r.printOn
                    onClick = { update(rebuild = true) { it.copy(paper = i, printOn = true) } }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
            }
            dynamic.addView(ctx.hScroll(papers))
            dynamic.addView(ctx.toggleRow("印放到相纸", "关闭则直接扫描底片（负片按扫描仪方式反转）", r.printOn) { on ->
                update(rebuild = true) { it.copy(printOn = on) }
            })
            dynamic.addView(ctx.label("放大机滤色", 15f).apply { setPadding(0, dp(8), 0, dp(8)) })
            dynamic.addView(Segmented(ctx, listOf("自动中性", "保留胶片本色"), if (r.balance == Recipe.BALANCE_FILM) 1 else 0) { i ->
                update { it.copy(balance = if (i == 1) Recipe.BALANCE_FILM else Recipe.BALANCE_NEUTRAL) }
            })
        }

        // look
        dynamic.addView(ctx.section("风格 LUT"))
        val lutCard = ctx.card()
        val lutName = ctx.label(if (r.lut == null) "无 LUT" else LutCatalog.label(r.lut), 15f).single()
        lutCard.addView(ctx.row(lutName, ctx.button("选择") { pickLut() }).apply {
            (lutName.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f }
        }, matchWrap())
        if (r.lut != null) {
            lutCard.addView(ctx.sliderRow("强度", 0f, 1f, r.lutStrength, 0.05f, { "%.0f%%".format(it * 100) }) { v ->
                update { it.copy(lutStrength = v) }
            })
            lutCard.addView(ctx.toggleRow("使用 LUT", null, r.lutOn) { on -> update { it.copy(lutOn = on) } })
        }
        dynamic.addView(lutCard, matchWrap())

        // exposure and white balance
        dynamic.addView(ctx.section("曝光与白平衡"))
        val exp = ctx.card()
        exp.addView(ctx.sliderRow("曝光补偿", -3f, 3f, r.ev, 1f / 3f, { "%+.1f EV".format(it) }) { v -> update { it.copy(ev = v) } })
        exp.addView(ctx.toggleRow("按相机 JPEG 亮度对齐", "从相机机内预览的亮度开始冲洗（尼康 RAW 通常偏暗 1–2 档）", r.matchCamera) { on ->
            update { it.copy(matchCamera = on) }
        })
        exp.addView(ctx.label("白平衡", 15f).apply { setPadding(0, dp(10), 0, dp(8)) })
        exp.addView(Segmented(ctx, Recipe.whiteNames, r.white) { i -> update(rebuild = true) { it.copy(white = i) } })
        if (r.white == Recipe.WHITE_KELVIN) {
            exp.addView(ctx.sliderRow("色温", 2500f, 10000f, r.kelvin, 50f, { "%.0f K".format(it) }) { v -> update { it.copy(kelvin = v) } })
        }
        if (r.white != Recipe.WHITE_CAMERA) {
            exp.addView(ctx.sliderRow("色调（绿 ← → 品红）", -1f, 1f, r.tint, 0.05f, { "%+.2f".format(it) }) { v -> update { it.copy(tint = v) } })
        }
        exp.addView(ctx.label(when (r.white) {
            Recipe.WHITE_CAMERA -> "用相机记录的白平衡"
            Recipe.WHITE_FILM -> "胶片本是日光平衡（灯光片 3200 K）：室内暖光会像胶片一样偏暖"
            else -> "固定为 ${r.kelvinFor().toInt()} K"
        }, 12f, Ui.SUB).apply { setPadding(0, dp(6), 0, dp(4)) })
        dynamic.addView(exp, matchWrap())

        // texture
        dynamic.addView(ctx.section("质感"))
        val tex = ctx.card()
        tex.addView(ctx.toggleRow("颗粒", null, r.grain) { on -> update(rebuild = true) { it.copy(grain = on) } })
        if (r.grain) tex.addView(ctx.sliderRow("颗粒大小", 0.2f, 2.5f, r.grainSize, 0.05f) { v -> update { it.copy(grainSize = v) } })
        tex.addView(ctx.toggleRow("光晕（halation）", "高光边缘的红色晕", r.halation) { on -> update(rebuild = true) { it.copy(halation = on) } })
        if (r.halation) tex.addView(ctx.sliderRow("光晕强度", 0f, 4f, r.halationAmount, 0.1f) { v -> update { it.copy(halationAmount = v) } })
        tex.addView(ctx.toggleRow("DIR 耦合剂", "层间抑制：边缘更锐、色彩更分离", r.couplers) { on -> update { it.copy(couplers = on) } })
        dynamic.addView(tex, matchWrap())

        // noise
        dynamic.addView(ctx.section("降噪"))
        val noise = ctx.card()
        noise.addView(ctx.sliderRow("RAW 降噪", 0f, 0.6f, r.rawDenoise, 0.05f, { if (it == 0f) "关" else "%.2f".format(it) }) { v ->
            update { it.copy(rawDenoise = v) }
        })
        noise.addView(ctx.sliderRow("彩色降噪", 0f, 1f, r.chromaDenoise, 0.05f, { if (it == 0f) "关" else "%.2f".format(it) }) { v ->
            update { it.copy(chromaDenoise = v) }
        })
        dynamic.addView(noise, matchWrap())

        dynamic.addView(ctx.row(
            ctx.button("恢复默认配方") {
                AlertDialog.Builder(ctx).setMessage("恢复默认配方（Portra 400 · Endura）？")
                    .setPositiveButton("恢复") { _, _ -> update(rebuild = true) { Recipe() } }
                    .setNegativeButton("取消", null).show()
            },
        ).apply { setPadding(0, dp(18), 0, dp(4)) })
        dynamic.addView(ctx.label("保存方式、冲洗尺寸、联机与屏幕方向在「设置」里。", 12f, Ui.SUB).apply { setPadding(dp(4), dp(10), 0, 0) })
    }

    private fun filmCard(index: Int, selected: Boolean): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = pressable(ctx, if (selected) Ui.ACCENT else Ui.SURFACE, dp(12).toFloat(), if (selected) 0 else 1, Ui.LINE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { update(rebuild = true) { it.copy(film = index, filmOn = true) } }
        }
        val fg = if (selected) Ui.BG else Ui.TEXT
        val sub = if (selected) 0xFF4A3410.toInt() else Ui.SUB
        card.addView(ctx.label(Films.maker(index), 11f, sub))
        card.addView(ctx.label(Films.short(index), 15f, fg, bold = true).apply { setPadding(0, dp(3), 0, dp(3)) })
        card.addView(ctx.label("${Films.kind(index)} · ISO ${Films.iso(index)}", 11f, sub))
        return FrameLayout(ctx).apply {
            addView(card, FrameLayout.LayoutParams(dp(132), ViewGroup.LayoutParams.WRAP_CONTENT))
            setPadding(0, 0, dp(8), 0)
        }
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
