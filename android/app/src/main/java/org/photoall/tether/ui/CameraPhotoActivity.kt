package org.photoall.tether.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.photoall.tether.camera.CameraHub
import org.photoall.tether.camera.CameraItem
import org.photoall.tether.develop.DevelopQueue
import org.photoall.tether.develop.Library
import org.photoall.tether.develop.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One shot on the camera, as Photos shows a picture: black, a bar above with its name, a
 * toolbar of glyphs below (develop, import, protect, delete, info). Swipe for the next; tap to
 * hide the bars; the info rises as a card from the bottom.
 */
class CameraPhotoActivity : AdaptiveActivity() {
    private lateinit var image: ZoomImageView
    private lateinit var spinner: ProgressBar
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var protectButton: IconView
    private lateinit var bars: List<View>
    private lateinit var sheet: LinearLayout
    private lateinit var sheetText: TextView
    private var keys: List<String> = emptyList()
    private var index = 0
    private var item: CameraItem? = null
    private var loadToken = 0
    private var direction = 0
    private val hubListener: () -> Unit = { if (CameraHub.state != CameraHub.State.CONNECTED) finish() else refreshInfo() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.applyOrientation(this)
        keys = CameraPage.visibleKeys
        index = savedInstanceState?.getInt("index")?.takeIf { it in keys.indices }
            ?: keys.indexOf(intent.getStringExtra(KEY)).coerceAtLeast(0)
        val wide = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        image = ZoomImageView(this)
        image.onSwipe = { go(index + it, it) }
        image.onTap = { toggleBars() }
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
        subtitle = label("", Ui.CAPTION, Ui.SUB, bold = true).single()
        texts.addView(title)
        texts.addView(subtitle)
        top.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(View(this), LinearLayout.LayoutParams(dp(40), 1))
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        protectButton = iconButton(Glyph.LOCK, Ui.ACCENT, 46, filled = false, description = "保护") { protect() }
        val tools = listOf(
            iconButton(Glyph.WAND, Ui.ACCENT, 46, filled = false, description = "冲洗") { develop(true) },
            iconButton(Glyph.IMPORT, Ui.ACCENT, 46, filled = false, description = "导入原片") { develop(false) },
            protectButton,
            iconButton(Glyph.INFO, Ui.ACCENT, 46, filled = false, description = "信息") { toggleSheet() },
            iconButton(Glyph.TRASH, Ui.RED, 46, filled = false, description = "删除") { delete() },
        )
        val toolbar = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Ui.BAR)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            tools.forEach { b ->
                addView(b, if (wide) LinearLayout.LayoutParams(dp(46), 0, 1f) else LinearLayout.LayoutParams(0, dp(46), 1f))
            }
        }
        if (wide) {
            root.addView(toolbar, FrameLayout.LayoutParams(dp(68), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
            (top.layoutParams as FrameLayout.LayoutParams).marginEnd = dp(68)
        } else root.addView(toolbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        bars = listOf(top, toolbar)

        // the info card, risen from the bottom on demand
        sheet = card().apply {
            setPadding(dp(18), dp(16), dp(18), dp(18))
            elevation = dp(16).toFloat()
            visibility = View.GONE
        }
        sheetText = label("", Ui.SUBHEAD, Ui.TEXT).apply { setLineSpacing(0f, 1.4f); setTextIsSelectable(true) }
        sheet.addView(sheetText)
        root.addView(sheet, FrameLayout.LayoutParams(if (wide) dp(380) else ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(10), 0, dp(10), dp(if (wide) 10 else 76)) })

        setContentView(root)
        padForSystemBars(root)
        go(index, 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("index", index)
    }

    override fun onStart() {
        super.onStart()
        CameraHub.addListener(hubListener)
    }

    override fun onStop() {
        CameraHub.removeListener(hubListener)
        super.onStop()
    }

    private fun current(): CameraItem? = keys.getOrNull(index)?.let { k -> CameraHub.items.firstOrNull { it.key == k } }

    private fun toggleBars() {
        val show = bars.first().alpha < 0.5f
        bars.forEach { it.animate().alpha(if (show) 1f else 0f).setDuration(200).start() }
        if (!show && sheet.visibility == View.VISIBLE) toggleSheet()
    }

    private fun toggleSheet() {
        if (sheet.visibility == View.VISIBLE) {
            sheet.animate().translationY(sheet.height.toFloat() + dp(40)).alpha(0f).setDuration(220).setInterpolator(Ease.out)
                .withEndAction { sheet.visibility = View.GONE }.start()
        } else {
            refreshInfo()
            sheet.visibility = View.VISIBLE
            sheet.alpha = 0f
            sheet.translationY = dp(120).toFloat()
            sheet.animate().translationY(0f).alpha(1f).setDuration(460).setInterpolator(Ease.spring).start()
        }
    }

    private fun go(i: Int, dir: Int) {
        if (i !in keys.indices) {
            if (dir != 0) { Haptic.reject(image); image.nudge() }
            return
        }
        index = i
        direction = dir
        val it = current() ?: return finish()
        item = it
        refreshInfo()
        val token = ++loadToken
        val small = CameraHub.cachedThumb(it.jpeg ?: it.primary)
        showImage(small, slide = dir != 0)
        spinner.visibility = View.VISIBLE
        CameraHub.run {
            val big: Bitmap? = runCatching { CameraHub.preview(it, 2400) }.getOrNull()
            runOnUiThread {
                if (token != loadToken) return@runOnUiThread
                spinner.visibility = View.GONE
                if (big != null) {
                    image.setImageBitmap(big)
                    image.alpha = 0.6f
                    image.animate().alpha(1f).setDuration(280).start()
                }
            }
        }
    }

    // the next picture slides in from the side it was swiped toward
    private fun showImage(bmp: Bitmap?, slide: Boolean) {
        image.setImageBitmap(bmp)
        if (slide && !Ease.reduced(this)) {
            image.translationX = direction * image.width * 0.35f
            image.alpha = 0f
            image.animate().translationX(0f).alpha(1f).setDuration(380).setInterpolator(Ease.out).start()
            Haptic.tick(image)
        }
    }

    private fun refreshInfo() {
        val it = current() ?: return
        item = it
        title.text = it.name
        val time = if (it.time > 0) SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(it.time)) else ""
        subtitle.text = "${index + 1} / ${keys.size}" + if (time.isNotEmpty()) " · $time" else ""
        protectButton.glyph = if (it.isProtected) Glyph.UNLOCK else Glyph.LOCK
        val onPhone = Library.originalOf(it.key) != null
        val prints = Library.printsOf(it.key)
        sheetText.text = buildString {
            it.files.forEach { f ->
                append(f.filename).append("    ").append(formatBytes(f.size))
                if (f.imageWidth > 0) append("    ${f.imageWidth}×${f.imageHeight}")
                append('\n')
            }
            append(CameraHub.storageName(it.storageId))
            if (time.isNotEmpty()) append("  ·  ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(it.time)))
            append('\n')
            append(listOfNotNull(
                if (it.isProtected) "受保护" else null,
                if (onPhone) "原片在手机上" else null,
                if (prints > 0) "已冲洗 $prints 张" else null,
            ).joinToString("  ·  ").ifEmpty { "未导入" })
        }
    }

    private fun develop(develop: Boolean) {
        val it = item ?: return
        if (develop && !it.canDevelop) {
            Haptic.reject(image)
            Hud.show(this, Glyph.CLOSE, it.badge, Ui.RED)
            return
        }
        CameraHub.note("${if (develop) "冲洗" else "导入"}：${it.name}（${it.badge}）")
        DevelopQueue.addCamera(this, it, develop)
        Haptic.confirm(image)
        Hud.show(this, if (develop) Glyph.WAND else Glyph.IMPORT, if (develop) "冲洗中" else "导入中")
    }

    private fun protect() {
        val it = item ?: return
        CameraHub.run {
            val errors = CameraHub.protect(listOf(it), !it.isProtected)
            runOnUiThread {
                if (errors.isNotEmpty()) Hud.show(this, Glyph.CLOSE, "未完成", Ui.RED)
                else {
                    Haptic.confirm(image)
                    Hud.show(this, if (it.isProtected) Glyph.UNLOCK else Glyph.LOCK)
                    protectButton.pop(0.6f)
                }
                refreshInfo()
            }
        }
    }

    private fun delete() {
        val it = item ?: return
        AlertDialog.Builder(this).setTitle("从相机删除 ${it.name}？")
            .setMessage(it.files.joinToString("、") { f -> f.filename })
            .setPositiveButton("删除") { _, _ ->
                CameraHub.run {
                    val errors = CameraHub.delete(listOf(it))
                    runOnUiThread {
                        if (errors.isNotEmpty()) {
                            Hud.show(this, Glyph.CLOSE, "未删除", Ui.RED)
                            return@runOnUiThread
                        }
                        Haptic.confirm(image)
                        // the picture falls away
                        image.animate().scaleX(0.6f).scaleY(0.6f).alpha(0f).translationY(dp(80).toFloat()).setDuration(320)
                            .setInterpolator(Ease.out).withEndAction {
                                image.scaleX = 1f; image.scaleY = 1f; image.translationY = 0f
                                keys = keys - it.key
                                if (keys.isEmpty()) finish() else go(index.coerceAtMost(keys.lastIndex), 0)
                                image.alpha = 1f
                            }.start()
                    }
                }
            }
            .setNegativeButton("取消", null).show()
    }

    companion object {
        private const val KEY = "key"
        fun open(context: Context, key: String) =
            context.startActivity(Intent(context, CameraPhotoActivity::class.java).putExtra(KEY, key))
    }
}
