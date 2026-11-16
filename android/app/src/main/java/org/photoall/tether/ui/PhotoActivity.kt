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
class PhotoActivity : Activity() {
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
        spinner = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(spinner, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.SCRIM)
            setPadding(dp(8), dp(6), dp(14), dp(8))
        }
        top.addView(label("‹", 30f).apply { setPadding(dp(10), 0, dp(14), dp(4)); setOnClickListener { finish() } })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = label("", 15f, bold = true).single()
        subtitle = label("", 12f, Ui.SUB).single().apply { setPadding(0, dp(3), 0, 0) }
        texts.addView(title)
        texts.addView(subtitle)
        top.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val actions = if (kind == Library.PRINT) row(
            button("信息") { showInfo() },
            button("分享") { share() },
            button("用当前配方重冲", primary = true) { redevelop() },
            button("删除", danger = true) { delete() },
        ) else row(
            button("信息") { showInfo() },
            button("冲洗", primary = true) { redevelop() },
            button("设为配方预览") { usePreview() },
            button("分享") { share() },
            button("删除", danger = true) { delete() },
        )
        val wide = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val bottom: View
        if (wide) {
            // landscape: the actions stacked on the right edge, the picture keeps its height
            val buttons = (0 until actions.childCount).map { actions.getChildAt(it) }
            actions.removeAllViews()
            val rail = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                buttons.forEach { b -> addView(b, LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }) }
            }
            bottom = android.widget.ScrollView(this).apply { setBackgroundColor(Ui.SCRIM); addView(rail) }
            root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
            (top.layoutParams as FrameLayout.LayoutParams).marginEnd = dp(170)
        } else {
            bottom = hScroll(actions.apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }).apply { setBackgroundColor(Ui.SCRIM) }
            root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
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
        val time = (e.shotTime.takeIf { it > 0 } ?: e.time).let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(it)) }
        val recipe = e.recipe?.let { runCatching { Recipe.fromJson(it).summary() }.getOrNull() }
        subtitle.text = listOfNotNull("${i + 1} / ${entries.size}", time, recipe).joinToString(" · ")
        val t = ++token
        spinner.visibility = View.VISIBLE
        image.setImageBitmap(null)
        loader.execute {
            val bmp = runCatching { load(e) }.getOrNull()
            runOnUiThread {
                if (t != token) return@runOnUiThread
                spinner.visibility = View.GONE
                if (bmp != null) image.setImageBitmap(bmp) else Toast.makeText(this, "无法显示这张照片", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "这张的原片不在手机上（打开「保存原片到手机」后冲洗的才有）", Toast.LENGTH_LONG).show()
            return
        }
        DevelopQueue.redevelop(this, original)
        Toast.makeText(this, "已加入冲洗：${Recipe.load(this).summary()}", Toast.LENGTH_SHORT).show()
    }

    private fun usePreview() {
        val e = current() ?: return
        if (!e.isRaw) {
            Toast.makeText(this, "配方预览需要 RAW 原片", Toast.LENGTH_SHORT).show()
            return
        }
        Preview.useOriginal(this, e)
        Toast.makeText(this, "已设为配方页的预览图", Toast.LENGTH_SHORT).show()
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
