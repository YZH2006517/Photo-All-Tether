package org.photoall.tether.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.photoall.tether.engine.Brightness
import org.photoall.tether.engine.FilmEngine
import org.photoall.tether.engine.FilmParamsCatalog
import org.photoall.tether.engine.Films
import org.photoall.tether.engine.LutCatalog
import org.photoall.tether.engine.RawFile
import org.photoall.tether.engine.Recipe
import org.photoall.tether.ptp.ObjectInfo
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The tuning bench itself, without a screen of its own (after Photo All's): one picture developed
 * with the film engine while the recipe changes, shown as the original and the develop split by a
 * divider ([frame]), and the controls ([modes] over [scroll], [actions] at the foot). Its hosts
 * place these views: the bench page (TuneActivity) and the immersive view's tuning sheet.
 *
 * [start] is the recipe it starts from; [saved] is the recipe as saved (what 还原 goes back to,
 * what 应用 writes). [extraAction]: one more action at the foot (the immersive view's 应用并重冲).
 */
class TuneBench(
    private val ctx: Context,
    start: Recipe,
    private val host: Host,
    private val extraAction: Pair<String, () -> Unit>? = null,
) {
    interface Host {
        /** The empty state's button: the host's way of choosing a picture (or null: none offered). */
        val pickSource: (() -> Unit)?
        /** False once the host is gone (results arriving then are dropped). */
        val alive: Boolean
    }

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "tune") }
    private val main = Handler(Looper.getMainLooper())

    var saved: Recipe = Recipe.load(ctx)
        private set
    var recipe: Recipe = start
        private set
    /**
     * The original's develop: the recipe the bench started from with no film and no look, fixed,
     * so the left side stays the original while the right side follows every change.
     */
    private val plainRecipe = start.copy(filmOn = false, lut = null, diffusion = 0)

    // the picture: a raw (decoded once, developed any number of times) or an image
    private var sourceFile: File? = null
    private var raw: RawFile? = null
    private var cameraKey = 0f
    private var image: Bitmap? = null
    private var imageEdge = 0
    private var edge = PREVIEW_EDGE
    private var original: Bitmap? = null
    private var originalKey = ""
    private var ticket = 0
    private var sourceName: String? = null

    private var mode = ctx.getSharedPreferences("tune_bench", Context.MODE_PRIVATE).getInt("mode", 0).coerceIn(0, 1)

    val compare: CompareView = CompareView(ctx).apply {
        // looked at closely: developed near the photo's own size, so grain and detail are the print's
        onZoom = { z -> setEdge(if (z >= 1.8f) DETAIL_EDGE else if (z <= 1.2f) PREVIEW_EDGE else edge) }
    }
    private val empty: View = emptyState()
    private val spinner = RingProgress(ctx).apply { indeterminate = true; visibility = View.GONE }
    /** The picture: the original and the develop, an empty state before one is loaded. */
    val frame: FrameLayout = FrameLayout(ctx).apply {
        background = rounded(Ui.SURFACE, ctx.dp(16).toFloat())
        clipToOutline = true
        addView(compare, frameMatch())
        addView(empty, frameMatch())
        addView(spinner, FrameLayout.LayoutParams(ctx.dp(26), ctx.dp(26), Gravity.BOTTOM or Gravity.START).apply {
            setMargins(ctx.dp(12), 0, 0, ctx.dp(12))
        })
    }
    val recipeLine: TextView = ctx.label("", Ui.FOOTNOTE, Ui.SUB).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
    // 常用: the plain-words knobs; 完整配方: the whole recipe (every control the recipe page has)
    val modes: Segmented = Segmented(ctx, listOf("常用", "完整配方"), mode) { m ->
        if (m != mode) {
            mode = m
            ctx.getSharedPreferences("tune_bench", Context.MODE_PRIVATE).edit().putInt("mode", m).apply()
            rebuildControls(keepScroll = false)
        }
    }
    private val controls = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val scroll: ScrollView = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(controls) }
    // short labels: the column is narrow in landscape, and a large font must still fit
    private val applyButton: View = ctx.button("应用", primary = extraAction == null, glyph = Glyph.CHECK) { apply() }
    private val extraButton: View? = extraAction?.let { (label, run) ->
        ctx.button(label, primary = true, glyph = Glyph.WAND) { if (recipe != saved) apply(); run() }
    }
    val actions: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ctx.button("还原") { recipe = saved; changedRecipe(rebuild = true) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (extraButton == null)
            addView(ctx.button("默认") { recipe = Recipe(); changedRecipe(rebuild = true) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ctx.dp(8) })
        addView(applyButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f).apply { marginStart = ctx.dp(8) })
        extraButton?.let {
            addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f).apply { marginStart = ctx.dp(8) })
        }
    }

    private val develop = Runnable { developNow() }

    /** Stops its work and lets the raw go. */
    fun destroy() {
        main.removeCallbacks(develop)
        ticket++
        worker.execute { raw?.close(); raw = null }
        worker.shutdown()
    }

    /** Not applied to the recipe yet. */
    val dirty: Boolean get() = recipe != saved

    // ---- the picture ---------------------------------------------------------------------------

    /** Before a picture: how to get one. */
    private fun emptyState(): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(ctx.dp(18), ctx.dp(12), ctx.dp(18), ctx.dp(12))
        addView(IconView(ctx, Glyph.SLIDERS, Ui.DIM, 36), LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(36)))
        addView(ctx.label("选一张照片，边调边看", Ui.HEADLINE, Ui.TEXT, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(10), 0, 0) })
        addView(ctx.label("左边原图，右边是冲洗效果；拖动中间的线对比，双指放大看颗粒", Ui.FOOTNOTE, Ui.SUB).apply {
            gravity = Gravity.CENTER; setPadding(0, ctx.dp(4), 0, ctx.dp(12))
        })
        host.pickSource?.let { pick -> addView(ctx.button("从手机选 RAW / JPEG / HEIF", primary = true, glyph = Glyph.FOLDER_PLUS) { pick() }) }
    }

    /** Shown in place of the picture when there is none to tune (and why). */
    fun showEmpty(why: String? = null, title: String? = null, waiting: Boolean = false) {
        empty.visibility = View.VISIBLE
        if (title != null) ((empty as LinearLayout).getChildAt(1) as TextView).text = title
        if (why != null) ((empty as LinearLayout).getChildAt(2) as TextView).text = why
        busy(waiting)
    }

    /** What the left side is called (the picture is not always this photo's own original). */
    private var sourceLabel: String? = null

    /** A content uri's file, copied to the bench's folder, as its picture. */
    fun loadUri(uri: Uri, name: String, label: String? = null) {
        sourceLabel = label
        busy(true)
        worker.execute {
            val copy = runCatching {
                File(File(ctx.cacheDir, "tune").apply { mkdirs() }, "pick." + name.substringAfterLast('.', "jpg")).also { f ->
                    ctx.contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
                }
            }.getOrNull()
            main.post { if (copy != null) load(copy, name) else { busy(false); toast("读不出这个文件") } }
        }
    }

    /** Takes [file] (a raw, or an image the system decodes) as the bench's picture. */
    fun load(file: File, name: String, label: String? = sourceLabel) {
        sourceLabel = label
        busy(true)
        val t = ++ticket
        worker.execute {
            // copied: the preview's latest raw is replaced when the next shot comes in
            val own = File(File(ctx.cacheDir, "tune").apply { mkdirs() }, "src." + file.extension.lowercase(Locale.ROOT))
            val result = runCatching {
                if (file.absolutePath != own.absolutePath) file.copyTo(own, overwrite = true)
                raw?.close(); raw = null; image = null; original = null; originalKey = ""
                if (own.extension.uppercase(Locale.ROOT) in ObjectInfo.RAW_EXTENSIONS) {
                    cameraKey = RawFile.embeddedJpeg(own.path)?.let { Brightness.key(it) } ?: 0f
                    raw = RawFile.open(own.path, DETAIL_EDGE)
                } else {
                    image = decode(own, edge) ?: throw IllegalStateException("读不出这张图片")
                    imageEdge = edge
                }
                sourceFile = own
            }
            main.post {
                if (!host.alive) return@post
                result.onSuccess {
                    empty.visibility = View.GONE
                    compare.originalLabel = sourceLabel ?: if (raw != null) "原图（无胶片）" else "原图"
                    compare.original = null
                    compare.developed = null
                    syncLine(name)
                    if (t == ticket) developNow() else changed(0)
                }.onFailure { busy(false); toast("读不出 $name：${it.message}") }
            }
        }
    }

    private fun decode(file: File, edge: Int): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val s = minOf(1f, edge.toFloat() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(maxOf(1, (info.size.width * s).roundToInt()), maxOf(1, (info.size.height * s).roundToInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }
    }.getOrNull()

    private fun changed(delay: Long = 160L) {
        if (sourceFile == null) return
        main.removeCallbacks(develop)
        main.postDelayed(develop, delay)
    }

    private fun developNow() {
        if (sourceFile == null) return
        val t = ++ticket
        val r = recipe
        val size = edge
        busy(true)
        val app = ctx.applicationContext
        worker.execute {
            if (t != ticket) return@execute
            val result = runCatching {
                val engine = FilmEngine.get(app)
                val rf = raw
                var plain: Bitmap? = null
                val out = if (rf != null) {
                    // the original: the raw as it started (no film, no look), whatever is changed since
                    val neutral = plainRecipe
                    val key = "${neutral.toJson()}@$size"
                    if (key != originalKey) {
                        plain = engine.developRaw(rf, neutral, size, cameraKey, SEED, app).bitmap
                        original = plain
                        originalKey = key
                    }
                    engine.developRaw(rf, r, size, cameraKey, SEED, app).bitmap
                } else {
                    val src = sourceFile!!
                    if (imageEdge != size || image == null) { image = decode(src, size); imageEdge = size }
                    plain = image
                    engine.developBitmap(image!!, r, size, SEED, app).bitmap
                }
                Pair(plain, out)
            }
            main.post {
                if (t != ticket || !host.alive) return@post
                busy(false)
                result.onSuccess { (plain, out) ->
                    if (plain != null) compare.original = plain
                    compare.developed = out
                }.onFailure { toast("冲洗失败：${it.message}") }
            }
        }
    }

    /** A new develop size: the picture (and the plain one) again at that size. */
    private fun setEdge(next: Int) {
        if (next == edge || sourceFile == null) return
        edge = next
        changed(0)
    }

    private fun busy(on: Boolean) {
        spinner.visibility = if (on) View.VISIBLE else View.GONE
        compare.busy = on
    }

    // ---- the recipe ----------------------------------------------------------------------------

    private fun changedRecipe(rebuild: Boolean = false) {
        if (rebuild) rebuildControls(keepScroll = true)
        syncLine()
        changed()
    }

    private fun update(rebuild: Boolean = false, change: (Recipe) -> Recipe) {
        recipe = change(recipe)
        changedRecipe(rebuild)
    }

    private fun syncLine(name: String? = null) {
        if (name != null) sourceName = name
        recipeLine.text = "当前：" + recipe.summary() + (sourceName?.let { "　·　$it" } ?: "")
        val differs = recipe != saved
        applyButton.alpha = if (differs) 1f else 0.5f
        applyButton.isEnabled = differs
    }

    /** The tuned recipe becomes the recipe (the next shots develop with it). */
    fun apply() {
        if (recipe == saved) return
        Recipe.save(ctx, recipe)
        saved = recipe
        syncLine()
        Haptic.confirm(frame)
        toast("已应用：接下来的照片按这套配方冲洗")
    }

    // ---- the controls --------------------------------------------------------------------------

    /** One plain-words control: what it is, what it does, what each end of it looks like. */
    private class Knob(
        val title: String, val what: String, val low: String, val high: String,
        val min: Float, val max: Float, val step: Float, val default: Float,
        val format: (Float) -> String,
        val get: (Recipe) -> Float, val set: (Recipe, Float) -> Recipe,
    )

    private val f2: (Float) -> String = { "%.2f".format(Locale.US, it) }
    private val d = Recipe()

    private val knobs = listOf(
        Knob("曝光", "冲洗时整体的亮度（档）", "暗", "亮", -3f, 3f, 1f / 3f, 0f, { "%+.1f EV".format(Locale.US, it) },
            { it.ev }, { r, v -> r.copy(ev = v) }),
        Knob("局部影调（HDR）", "暗部提亮、高光收住，保留局部反差（单张曝光融合）", "关", "强", 0f, 1f, 0.05f, 0f,
            { if (it == 0f) "关" else f2(it) }, { it.localTone }, { r, v -> r.copy(localTone = v) }),
        Knob("颗粒", "胶片的颗粒感：1 是原版胶片的颗粒，0 没有颗粒", "细腻", "粗糙", 0f, 2f, 0.05f, d.grainAmount, f2,
            { if (it.grain) it.grainAmount else 0f }, { r, v -> r.copy(grain = v > 0f, grainAmount = if (v > 0f) v else r.grainAmount) }),
        Knob("颗粒大小", "颗粒一粒粒的大小", "细密", "粗大", 0.1f, 2f, 0.05f, d.grainSize, f2,
            { it.grainSize }, { r, v -> r.copy(grainSize = v) }),
        Knob("反差", "明暗之间的对比", "柔和", "硬朗", 0.6f, 1.4f, 0.02f, 1f, f2,
            { it.value("g film") }, { r, v -> r.withParam("g film", v) }),
        Knob("色彩浓度", "胶片各层之间互相抑制（DIR 耦合剂），越强颜色越纯、越浓", "淡雅", "浓郁", 0f, 2f, 0.05f, 1f, f2,
            { if (it.couplers) it.value("cp amt") else 0f }, { r, v -> r.copy(couplers = v > 0f).withParam("cp amt", if (v > 0f) v else r.value("cp amt")) }),
        Knob("光晕", "亮光边缘泛出的红晕，拍灯光和逆光最明显", "无", "明显", 0f, 4f, 0.1f, d.halationAmount, f2,
            { if (it.halation) it.halationAmount else 0f }, { r, v -> r.copy(halation = v > 0f, halationAmount = if (v > 0f) v else r.halationAmount) }),
        Knob("锐度", "扫描底片时的锐化", "柔和", "清晰", 0f, 1.5f, 0.05f, 0.3f, f2,
            { it.value("stage scan sharp") }, { r, v -> r.withParam("stage scan sharp", v) }),
        Knob("降噪", "彩色噪点与 RAW 噪点一起（干净的照片引擎自动少降）", "留细节", "更干净", 0f, 1f, 0.05f, d.chromaDenoise, f2,
            { it.chromaDenoise }, { r, v -> r.copy(chromaDenoise = v, rawDenoise = (v * d.rawDenoise / d.chromaDenoise).coerceIn(0f, 0.6f)) }),
    )

    private fun rebuildControls(keepScroll: Boolean) {
        val y = scroll.scrollY
        controls.removeAllViews()
        if (mode == 0) commonControls() else fullRecipe.build(controls)
        controls.addView(View(ctx), LinearLayout.LayoutParams(1, ctx.dp(12)))
        if (keepScroll) scroll.post { scroll.scrollTo(0, y) } else scroll.scrollTo(0, 0)
    }

    private fun panel(): LinearLayout = ctx.card().apply { setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(8)) }
    private fun addCard(v: View) = controls.addView(v, matchWrap().apply { bottomMargin = ctx.dp(8) })

    private fun commonControls() {
        val r = recipe
        // the stock and the paper, as chips
        addCard(panel().apply {
            stockChips(this, r)
            if (r.lut != null) {
                addView(ctx.separator().apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ctx.dp(8) })
                addView(ctx.toggleRow("风格 LUT：" + LutCatalog.label(r.lut), null, r.lutOn) { on -> update { it.copy(lutOn = on) } })
            }
        })
        for (k in knobs) {
            addCard(panel().apply { knob(this, k) })
            if (k.title == "光晕") addCard(diffusionCard())
        }
        addCard(panel().apply {
            addView(ctx.label("画幅", Ui.BODY, Ui.TEXT, bold = true))
            addView(ctx.label("底片越大，颗粒、光晕在照片上越细小", Ui.FOOTNOTE, Ui.SUB).apply { setPadding(0, ctx.dp(2), 0, ctx.dp(4)) })
            val sizes = FilmParamsCatalog.byKey.getValue("frame mm").choices!!
            val at = sizes.indexOfFirst { abs(it.second - r.value("frame mm")) < 1e-3f }.coerceAtLeast(0)
            addView(chips(ctx, sizes.mapIndexed { i, (n, _) -> n to i }, at) { i -> update(rebuild = true) { it.withParam("frame mm", sizes[i].second) } })
        })
    }

    /** The stock and the paper, as chips. */
    private fun stockChips(card: LinearLayout, r: Recipe) {
        card.addView(ctx.label("胶片", Ui.BODY, Ui.TEXT, bold = true))
        card.addView(filmChips(ctx, r) { i -> update(rebuild = true) { if (i < 0) it.copy(filmOn = false) else it.copy(film = i, filmOn = true) } })
        if (r.filmOn) {
            card.addView(ctx.label("相纸", Ui.BODY, Ui.TEXT, bold = true).apply { setPadding(0, ctx.dp(8), 0, 0) })
            card.addView(paperChips(ctx, r) { i -> update(rebuild = true) { if (i < 0) it.copy(printOn = false) else it.copy(paper = i, printOn = true) } })
        }
    }

    private fun knob(card: LinearLayout, k: Knob) {
        val v = k.get(recipe)
        val fmt: (Float) -> String = { x -> if (abs(x - k.default) < 1e-4f) "默认" else k.format(x) }
        card.addView(ctx.sliderRow(k.title, k.min, k.max, v, k.step, fmt, default = k.default) { nv -> update { k.set(it, nv) } })
        card.addView(ctx.label(k.what, Ui.FOOTNOTE, Ui.SUB))
        card.addView(LinearLayout(ctx).apply {
            setPadding(0, ctx.dp(2), 0, 0)
            addView(ctx.label(k.low, Ui.CAPTION, Ui.DIM), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ctx.label(k.high, Ui.CAPTION, Ui.DIM).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
    }

    /** 柔光滤镜: none or one of four, and how strong. */
    private fun diffusionCard() = panel().apply {
        val r = recipe
        addView(ctx.label("柔光滤镜", Ui.BODY, Ui.TEXT, bold = true))
        addView(ctx.label("镜头前加一片柔光镜：亮处泛起一层光，人像和逆光更柔", Ui.FOOTNOTE, Ui.SUB).apply { setPadding(0, ctx.dp(2), 0, ctx.dp(4)) })
        addView(chips(ctx, Recipe.diffusionNames.mapIndexed { i, n -> n to i }, r.diffusion) { i -> update(rebuild = true) { it.copy(diffusion = i) } })
        if (r.diffusion > 0)
            knob(this, Knob("滤镜强度", "这片柔光镜有多强", "淡", "浓", 0f, 1f, 0.05f, 0.5f, f2,
                { it.diffusionAmount }, { rr, v -> rr.copy(diffusionAmount = v) }))
    }

    /** The whole recipe, the stock and the paper as chips at its head. */
    private val fullRecipe by lazy {
        RecipeControls(ctx, { recipe }, { rebuild, change -> update(rebuild, change) }, { pickLut() }, top = { into, r ->
            into.addView(panel().apply { stockChips(this, r) }, matchWrap().apply { topMargin = ctx.dp(4) })
        })
    }

    /** The LUT is chosen on its own page (which writes the saved recipe): [resumed] takes it back. */
    private var lutPending = false
    private fun pickLut() { lutPending = true; LutActivity.open(ctx) }

    /** The host is back on screen: a LUT chosen meanwhile comes into the bench's recipe. */
    fun resumed() {
        if (!lutPending) return
        lutPending = false
        val now = Recipe.load(ctx)
        recipe = recipe.copy(lut = now.lut, lutStrength = now.lutStrength, lutOn = now.lutOn)
        saved = saved.copy(lut = now.lut, lutStrength = now.lutStrength, lutOn = now.lutOn)
        changedRecipe(rebuild = true)
    }

    // last: everything above (the knobs among it) is set up by now
    init {
        rebuildControls(keepScroll = false)
        syncLine()
    }

    private fun toast(text: String) = android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_LONG).show()

    companion object {
        private const val PREVIEW_EDGE = 1280
        private const val DETAIL_EDGE = 3072
        private const val SEED = 7 // the same grain at every change: only the settings move

        /** A horizontal row of choices; [selected] is a choice's id. */
        fun chips(ctx: Context, choices: List<Pair<String, Int>>, selected: Int, onPick: (Int) -> Unit): View {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ctx.dp(4), 0, ctx.dp(4)) }
            var chosen: View? = null
            for ((name, id) in choices) {
                val chip = Chip(ctx, name, { if (id != selected) onPick(id) }).apply { on = id == selected }
                if (id == selected) chosen = chip
                row.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.dp(6) })
            }
            return ctx.hScroll(row).also { s -> chosen?.let { c -> s.post { s.scrollTo((c.left - ctx.dp(40)).coerceAtLeast(0), 0) } } }
        }

        /** The stocks as chips ("无胶片" first, id -1). */
        fun filmChips(ctx: Context, r: Recipe, onPick: (Int) -> Unit): View =
            chips(ctx, listOf("无胶片" to -1) + (0 until FilmEngine.filmStocks.size).map { Films.short(it) to it },
                if (r.filmOn) r.film else -1, onPick)

        /** The papers as chips ("直接扫描" first, id -1). */
        fun paperChips(ctx: Context, r: Recipe, onPick: (Int) -> Unit): View =
            chips(ctx, listOf("直接扫描" to -1) + (0 until FilmEngine.papers.size).map { Films.paperShort(it) to it },
                if (r.printOn) r.paper else -1, onPick)
    }
}
