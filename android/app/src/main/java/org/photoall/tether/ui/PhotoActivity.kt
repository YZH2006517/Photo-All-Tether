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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra(KIND) ?: Library.PRINT
        entries = if (kind == Library.PRINT) Library.prints() else Library.originals()
        org.photoall.tether.develop.Prefs.applyOrientation(this)
        index = savedInstanceState?.getInt("index")?.takeIf { it in entries.indices }
            ?: entries.indexOfFirst { it.id == intent.getLongExtra(ID, 0) }.coerceAtLeast(0)

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
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
        if (entries.isEmpty()) finish() else go(index)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("index", index)
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
        loader.execute {
            val bmp = runCatching { load(e) }.getOrNull()
            runOnUiThread {
                if (t != token) return@runOnUiThread
                spinner.visibility = View.GONE
                if (bmp != null) {
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

    private fun showInfo() {
        val e = current() ?: return
        val text = buildString {
            appendLine(e.name)
            appendLine(if (e.kind == Library.PRINT) "成片 · ${e.width}×${e.height}" else "原片 · ${e.mime}")
            e.recipe?.let { appendLine("配方：" + runCatching { Recipe.fromJson(it).summary() }.getOrDefault("")) }
            if (e.meta.isNotEmpty()) {
                appendLine()
                appendLine("【拍摄】")
                val m = e.meta
                listOfNotNull(
                    m["make"]?.let { "${it} ${m["model"] ?: ""}" },
                    m["lens"]?.takeIf { it.isNotBlank() },
                    listOfNotNull(
                        m["aperture"]?.toFloatOrNull()?.takeIf { it > 0 }?.let { "f/%.1f".format(it) },
                        m["shutter"]?.toDoubleOrNull()?.takeIf { it > 0 }?.let { if (it >= 0.3) "%.1f s".format(it) else "1/${Math.round(1 / it)} s" },
                        m["iso"]?.let { "ISO $it" },
                        m["focal"]?.toFloatOrNull()?.takeIf { it > 0 }?.let { "%.0f mm".format(it) },
                    ).joinToString(" · ").ifEmpty { null },
                ).forEach { appendLine(it) }
            }
            if (e.note.isNotBlank()) {
                var section = ""
                for (line in e.note.lines()) {
                    val parts = line.split('\t')
                    if (parts.size < 3) continue
                    if (parts[0] != section) {
                        section = parts[0]
                        appendLine()
                        appendLine("【$section】")
                    }
                    appendLine("${parts[1]}：${parts[2]}")
                }
            }
        }
        val view = TextView(this).apply {
            this.text = text
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Ui.TEXT)
            setTextIsSelectable(true)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        AlertDialog.Builder(this).setTitle("照片档案").setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton("关闭", null).show()
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
