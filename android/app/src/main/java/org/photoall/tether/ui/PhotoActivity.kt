package org.photoall.tether.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.photoall.tether.develop.DevelopQueue
import org.photoall.tether.develop.Library
import org.photoall.tether.develop.Preview
import org.photoall.tether.engine.RawFile
import org.photoall.tether.engine.Recipe
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** A print or an original on the phone: zoom in, read its record, share, develop again, delete. */
class PhotoActivity : AdaptiveActivity() {
    private lateinit var image: ZoomImageView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var bars: List<View>
    private var entries: List<Library.Entry> = emptyList()
    private var index = 0
    private var token = 0
    private lateinit var root: FrameLayout
    /** The picture on screen (what the histogram counts). */
    private var shown: Bitmap? = null
    /** The info panel while it is open (its scrim, holding the panel). */
    private var sheet: FrameLayout? = null
    private var backCallback: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra(KIND) ?: Library.PRINT
        entries = if (kind == Library.PRINT) Library.prints() else Library.originals()
        org.photoall.tether.develop.Prefs.applyOrientation(this)
        index = savedInstanceState?.getInt("index")?.takeIf { it in entries.indices }
            ?: entries.indexOfFirst { it.id == intent.getLongExtra(ID, 0) }.coerceAtLeast(0)

        root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        image = ZoomImageView(this)
        image.onSwipe = { go(index + it) }
        image.onTap = { bars.forEach { b -> b.visibility = if (b.visibility == View.VISIBLE) View.GONE else View.VISIBLE } }
        root.addView(image, frameMatch())
        spinner = ProgressBar(this).apply { isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.SUB) }
        root.addView(spinner, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.BAR)
            setPadding(dp(6), dp(8), dp(16), dp(8))
        }
        top.addView(iconButton(Glyph.CHEVRON_LEFT, Ui.ACCENT, 40, filled = false, description = "返回") { finish() })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        title = label("", Ui.HEADLINE, bold = true).single()
        subtitle = label("", Ui.CAPTION, Ui.SUB, bold = true).single().apply { setPadding(0, dp(2), 0, 0) }
        texts.addView(title)
        texts.addView(subtitle)
        top.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(View(this), LinearLayout.LayoutParams(dp(40), 1))
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // glyphs, not words: share, develop again, (originals: preview source), info, delete
        val tools = if (kind == Library.PRINT) listOf(
            iconButton(Glyph.SHARE, Ui.ACCENT, 46, filled = false, description = "分享") { share() },
            iconButton(Glyph.WAND, Ui.ACCENT, 46, filled = false, description = "用当前配方重冲") { redevelop() },
            iconButton(Glyph.INFO, Ui.ACCENT, 46, filled = false, description = "信息") { showInfo() },
            iconButton(Glyph.TRASH, Ui.RED, 46, filled = false, description = "删除") { delete() },
        ) else listOf(
            iconButton(Glyph.WAND, Ui.ACCENT, 46, filled = false, description = "冲洗") { redevelop() },
            iconButton(Glyph.DIAL, Ui.ACCENT, 46, filled = false, description = "设为配方预览") { usePreview() },
            iconButton(Glyph.SHARE, Ui.ACCENT, 46, filled = false, description = "分享") { share() },
            iconButton(Glyph.INFO, Ui.ACCENT, 46, filled = false, description = "信息") { showInfo() },
            iconButton(Glyph.TRASH, Ui.RED, 46, filled = false, description = "删除") { delete() },
        )
        val wide = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val bottom = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Ui.BAR)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            tools.forEach { b -> addView(b, if (wide) LinearLayout.LayoutParams(dp(46), 0, 1f) else LinearLayout.LayoutParams(0, dp(46), 1f)) }
        }
        if (wide) {
            root.addView(bottom, FrameLayout.LayoutParams(dp(68), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
            (top.layoutParams as FrameLayout.LayoutParams).marginEnd = dp(68)
        } else root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        bars = listOf(top, bottom)
        setContentView(root)
        padForSystemBars(root)
        if (entries.isEmpty()) finish() else {
            go(index)
            if (savedInstanceState?.getBoolean("info") == true) openInfo(animate = false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("index", index)
        outState.putBoolean("info", sheet != null) // the panel stays open when the phone turns
    }

    private fun current() = entries.getOrNull(index)

    private fun go(i: Int) {
        if (i !in entries.indices) return
        index = i
        val e = entries[i]
        title.text = e.name
        val time = (e.shotTime.takeIf { it > 0 } ?: e.time).let { SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(it)) }
        val recipe = e.recipe?.let { runCatching { Recipe.fromJson(it).summary() }.getOrNull() }
        title.text = recipe ?: e.name
        subtitle.text = listOf("${i + 1} / ${entries.size}", time).joinToString(" · ")
        val t = ++token
        spinner.visibility = View.VISIBLE
        image.setImageBitmap(null)
        shown = null
        if (sheet != null) openInfo(animate = false) // the panel follows the swipe
        loader.execute {
            val bmp = runCatching { load(e) }.getOrNull()
            runOnUiThread {
                if (t != token) return@runOnUiThread
                spinner.visibility = View.GONE
                if (bmp != null) {
                    shown = bmp
                    sheet?.let { refreshHistogram() }
                    image.setImageBitmap(bmp)
                    image.alpha = 0f
                    image.animate().alpha(1f).setDuration(260).start()
                } else Hud.show(this, Glyph.PHOTO, "无法显示", Ui.RED)
            }
        }
    }

    private fun load(e: Library.Entry): Bitmap? {
        val uri = Uri.parse(e.uri)
        val target = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 2
        if (!e.isRaw || e.mime == "image/heif") {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, info, _ ->
                val long = maxOf(info.size.width, info.size.height)
                if (long > target) d.setTargetSampleSize(Integer.highestOneBit(long / target).coerceAtLeast(1))
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        // a raw: the camera's full-size JPEG inside it
        val file = Library.copyToCache(this, uri, "view.${e.name.substringAfterLast('.')}")
        val jpeg = RawFile.embeddedJpeg(file.path)
        file.delete()
        jpeg ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= target) sample *= 2
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** The photo's whole record as plain text (copied from the info panel). */
    private fun recordText(e: Library.Entry): String = buildString {
        appendLine(e.name)
        appendLine(if (e.kind == Library.PRINT) "成片 · ${e.width}×${e.height}" else "原片 · ${e.mime}")
        e.recipe?.let { appendLine("配方：" + runCatching { Recipe.fromJson(it).summary() }.getOrDefault("")) }
        shotLines(e).forEach { appendLine(it) }
        for ((section, rows) in record(e)) {
            appendLine()
            appendLine("【$section】")
            rows.forEach { (k, v) -> appendLine("$k：$v") }
        }
    }

    private fun shotLines(e: Library.Entry): List<String> {
        val m = e.meta
        return listOfNotNull(
            m["make"]?.let { "$it ${m["model"] ?: ""}".trim() },
            m["lens"]?.takeIf { it.isNotBlank() },
        )
    }

    /** The develop's record ("section\tkey\tvalue" lines), by section in order. */
    private fun record(e: Library.Entry): List<Pair<String, List<Pair<String, String>>>> {
        val out = mutableListOf<Pair<String, MutableList<Pair<String, String>>>>()
        for (line in e.note.lines()) {
            val parts = line.split('\t')
            if (parts.size < 3) continue
            if (out.lastOrNull()?.first != parts[0]) out += parts[0] to mutableListOf()
            out.last().second += parts[1] to parts.drop(2).joinToString(" ")
        }
        return out
    }

    // ---- the info panel ------------------------------------------------------------------------

    private val wide get() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    private var histogram: HistogramView? = null
    private var clipLine: TextView? = null

    private fun showInfo() = if (sheet != null) closeInfo() else openInfo(animate = true)

    /**
     * The photo's information in the app's own colours (light or dark as set): its histogram
     * first, the shot, then the develop's record by section. Portrait: a sheet from the foot;
     * landscape: a column on the right, beside the tools.
     */
    private fun openInfo(animate: Boolean) {
        val e = current() ?: return
        sheet?.let { root.removeView(it) }
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x55000000)
            setOnClickListener { closeInfo() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true // taps inside do not close it
            background = rounded(Ui.SURFACE, dp(20).toFloat(), 1, Ui.LINE)
            elevation = dp(12).toFloat()
            setPadding(dp(16), dp(10), dp(10), dp(6))
        }
        // the head: what it is, copy, close
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("照片信息", Ui.HEADLINE, Ui.TEXT, bold = true).single(),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(iconButton(Glyph.COPY, Ui.ACCENT, 38, filled = false, description = "复制全部信息") { copy("照片信息", recordText(e)) })
        head.addView(iconButton(Glyph.CLOSE, Ui.SUB, 38, filled = false, description = "关闭") { closeInfo() })
        panel.addView(head, matchWrap())

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(6), dp(12)) }
        // the histogram, and how much is clipped at each end
        histogram = HistogramView(this)
        body.addView(histogram, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (wide) 120 else 110)).apply { topMargin = dp(4) })
        clipLine = label("", Ui.CAPTION, Ui.SUB).apply { setPadding(dp(2), dp(6), 0, 0) }
        body.addView(clipLine)
        refreshHistogram()

        // what and when
        val recipe = e.recipe?.let { runCatching { Recipe.fromJson(it).summary() }.getOrNull() }
        body.addView(label(recipe ?: e.name, Ui.BODY, Ui.TEXT, bold = true).apply { setPadding(0, dp(14), 0, 0) })
        val time = (e.shotTime.takeIf { it > 0 } ?: e.time).let { SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).format(Date(it)) }
        val size = if (e.width > 0 && e.height > 0) "${e.width}×${e.height}（%.1f MP）".format(e.width * e.height / 1e6) else null
        body.addView(label(listOfNotNull(if (recipe != null) e.name else null, size, time).joinToString("  ·  "), Ui.FOOTNOTE, Ui.SUB)
            .apply { setPadding(0, dp(2), 0, 0) })

        // the shot: four cells, then the camera and the lens
        val m = e.meta
        val cells = listOf(
            "光圈" to m["aperture"]?.toFloatOrNull()?.takeIf { it > 0 }?.let { "f/" + "%.1f".format(it).removeSuffix(".0") },
            "快门" to m["shutter"]?.toDoubleOrNull()?.takeIf { it > 0 }?.let { if (it >= 0.3) "%.1f″".format(it).replace(".0″", "″") else "1/${Math.round(1 / it)}" },
            "ISO" to m["iso"]?.toFloatOrNull()?.takeIf { it > 0 }?.let { it.toInt().toString() },
            "焦距" to m["focal"]?.toFloatOrNull()?.takeIf { it > 0 }?.let { "%.0fmm".format(it) },
        )
        if (cells.any { it.second != null }) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
            cells.forEachIndexed { i, (name, value) ->
                row.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    background = rounded(Ui.SURFACE2, dp(12).toFloat())
                    setPadding(dp(2), dp(8), dp(2), dp(8))
                    addView(label(value ?: "—", Ui.SUBHEAD, if (value != null) Ui.TEXT else Ui.DIM, bold = true).single().apply {
                        typeface = Ui.mono; gravity = Gravity.CENTER
                    })
                    addView(label(name, Ui.CAPTION, Ui.SUB).single().apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(6) })
            }
            body.addView(row, matchWrap())
        }
        val shot = shotLines(e)
        if (shot.isNotEmpty()) body.addView(infoGroup(null, shot.mapIndexed { i, v -> (if (i == 0) "相机" else "镜头") to v }))
        if (e.kind == Library.ORIGINAL) body.addView(infoGroup(null, listOf("文件" to e.mime)))
        if (e.meta["lossless"] != null) body.addView(infoGroup(null, listOf("无损母版" to "16 位 TIFF（与 JPEG 存在同一文件夹）")))
        // the develop's record, a group per section
        for ((section, rows) in record(e)) body.addView(infoGroup(section, rows))

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(body) }
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val screenH = resources.displayMetrics.heightPixels
        val lp = if (wide) FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.40f).toInt(),
            ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END).apply { setMargins(0, dp(10), dp(68 + 10), dp(10)) }
        else FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (screenH * 0.68f).toInt(), Gravity.BOTTOM)
            .apply { setMargins(dp(8), 0, dp(8), dp(8)) }
        scrim.addView(panel, lp)
        root.addView(scrim, frameMatch())
        sheet = scrim
        if (animate && !Ease.reduced(this)) {
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(180).start()
            if (wide) panel.translationX = dp(40).toFloat() else panel.translationY = dp(60).toFloat()
            panel.animate().translationX(0f).translationY(0f).setDuration(260).setInterpolator(Ease.out).start()
        }
        // back closes the panel first
        if (backCallback == null && android.os.Build.VERSION.SDK_INT >= 33) {
            val cb = android.window.OnBackInvokedCallback { closeInfo() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, cb)
            backCallback = cb
        }
    }

    /** A titled group of label / value rows, the values selectable. */
    private fun infoGroup(title: String?, rows: List<Pair<String, String>>): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(14), 0, 0)
        if (title != null) addView(label(title, Ui.FOOTNOTE, Ui.ACCENT, bold = true).apply { setPadding(dp(2), 0, 0, dp(6)) })
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Ui.SURFACE2, dp(12).toFloat())
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        rows.forEachIndexed { i, (k, v) ->
            if (i > 0) card.addView(View(context).apply { setBackgroundColor(Ui.LINE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            card.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(7), 0, dp(7))
                addView(label(k, Ui.FOOTNOTE, Ui.SUB), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f))
                addView(label(v, Ui.FOOTNOTE, Ui.TEXT).apply { gravity = Gravity.END; setTextIsSelectable(true) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f).apply { marginStart = dp(8) })
            })
        }
        addView(card, matchWrap())
    }

    private fun refreshHistogram() {
        val view = histogram ?: return
        val line = clipLine ?: return
        val bmp = shown
        val e = current()
        if (bmp == null) { view.data = null; line.text = "载入中…"; return }
        val what = if (e?.kind == Library.ORIGINAL) "相机内嵌 JPEG 的直方图" else "成片直方图"
        loader.execute {
            val h = runCatching { HistogramView.measure(bmp) }.getOrNull()
            runOnUiThread {
                if (histogram !== view || h == null) return@runOnUiThread
                view.data = h
                line.text = "$what　·　暗部裁切 %.1f%%　·　高光裁切 %.1f%%".format(h.dark * 100, h.bright * 100)
            }
        }
    }

    private fun closeInfo() {
        val s = sheet ?: return
        sheet = null
        histogram = null
        clipLine = null
        if (android.os.Build.VERSION.SDK_INT >= 33) (backCallback as? android.window.OnBackInvokedCallback)?.let {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
        }
        backCallback = null
        if (Ease.reduced(this)) { root.removeView(s); return }
        s.animate().alpha(0f).setDuration(160).withEndAction { root.removeView(s) }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (sheet != null) closeInfo() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun share() {
        val e = current() ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = e.mime
            putExtra(Intent.EXTRA_STREAM, Uri.parse(e.uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "分享"))
    }

    private fun originalOf(e: Library.Entry): Library.Entry? =
        if (e.kind == Library.ORIGINAL) e
        else e.original?.let { Library.byUri(it) } ?: e.cameraKey?.let { Library.originalOf(it) }

    private fun redevelop() {
        val e = current() ?: return
        val original = originalOf(e)
        if (original == null) {
            Haptic.reject(image)
            Hud.show(this, Glyph.PHOTO_STACK, "没有原片", Ui.RED)
            return
        }
        DevelopQueue.redevelop(this, original)
        Haptic.confirm(image)
        Hud.show(this, Glyph.WAND, org.photoall.tether.engine.Films.short(Recipe.load(this).film))
    }

    private fun usePreview() {
        val e = current() ?: return
        if (!e.isRaw) {
            Hud.show(this, Glyph.PHOTO, "需要 RAW", Ui.RED)
            return
        }
        Preview.useOriginal(this, e)
        Haptic.confirm(image)
        Hud.show(this, Glyph.DIAL, "配方预览")
    }

    private fun delete() {
        val e = current() ?: return
        AlertDialog.Builder(this).setTitle("删除这张${if (e.kind == Library.PRINT) "成片" else "原片"}？")
            .setMessage("${e.name} 会从手机里删除。" + if (e.kind == Library.ORIGINAL) "相机存储卡上的不受影响。" else "")
            .setPositiveButton("删除") { _, _ ->
                val consent = Library.delete(this, listOf(e))
                if (consent != null) {
                    pendingDelete = e
                    startIntentSenderForResult(consent, REQUEST_DELETE, null, 0, 0, 0)
                } else removed()
            }
            .setNegativeButton("取消", null).show()
    }

    private var pendingDelete: Library.Entry? = null

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DELETE && resultCode == RESULT_OK) {
            pendingDelete?.let { Library.forget(this, listOf(it)) }
            removed()
        }
        pendingDelete = null
    }

    private fun removed() {
        entries = entries.filterIndexed { i, _ -> i != index }
        if (entries.isEmpty()) finish() else go(index.coerceAtMost(entries.lastIndex))
    }

    companion object {
        private const val ID = "id"
        private const val KIND = "kind"
        private const val REQUEST_DELETE = 3
        private val loader = Executors.newSingleThreadExecutor()

        fun open(context: Context, id: Long, kind: String) =
            context.startActivity(Intent(context, PhotoActivity::class.java).putExtra(ID, id).putExtra(KIND, kind))
    }
}
