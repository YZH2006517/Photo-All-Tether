package org.photoall.tether.ui

import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import org.photoall.tether.ErrorLog
import org.photoall.tether.camera.CameraHub
import org.photoall.tether.develop.Prefs
import org.photoall.tether.engine.FilmEngine

/** How photos are saved, how tethering behaves, the screen, and diagnostics. */
class SettingsPage(private val activity: MainActivity) : ScrollView(activity), Arrangeable {
    private val ctx = activity
    private val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    init {
        setBackgroundColor(Ui.BG)
        isFillViewport = true
        content.setPadding(dp(14), dp(8), dp(14), dp(40))
        addView(content)
    }

    private var wide = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        build()
    }

    override fun arrange(wide: Boolean) {
        if (this.wide == wide && content.childCount > 0) return
        this.wide = wide
        if (isAttachedToWindow) build()
    }

    // Landscape: the sections (a title and its card each) in two columns, side by side.
    private fun twoColumns() {
        val title = content.getChildAt(0)
        val rest = (1 until content.childCount).map { content.getChildAt(it) }
        content.removeAllViews()
        content.addView(title)
        val blocks = rest.chunked(2).map { pair ->
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                pair.forEach { addView(it, matchWrap()) }
            }
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val columns = List(2) { LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL } }
        blocks.forEachIndexed { i, b -> columns[i % 2].addView(b, matchWrap()) }
        columns.forEachIndexed { i, c ->
            row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(14) })
        }
        content.addView(row, matchWrap())
    }

    private fun build() {
        wide = activity.wide
        content.removeAllViews()
        content.addView(ctx.label("设置", 22f, bold = true))

        // saving
        content.addView(ctx.section("保存到手机"))
        val save = ctx.card()
        val saveHint = ctx.label("", 12f, Ui.SUB).apply { setPadding(0, dp(8), 0, dp(4)); setLineSpacing(0f, 1.3f) }
        fun hint() {
            saveHint.text = if (Prefs.keepOriginal(ctx))
                "成片存到 Pictures/PhotoAll Tether，原片（NEF）存到它下面的「原片」文件夹。之后可以换配方重冲。"
            else "手机上只留冲洗好的 JPG（Pictures/PhotoAll Tether），不存原片，省空间。原片仍在相机存储卡上；需要时在「相机」页长按选择后点「导入」。"
        }
        save.addView(Segmented(ctx, listOf("只存冲洗后的 JPG", "JPG + 原片 NEF"), if (Prefs.keepOriginal(ctx)) 1 else 0) {
            Prefs.setKeepOriginal(ctx, it == 1)
            hint()
        }, matchWrap().apply { topMargin = dp(6) })
        hint()
        save.addView(saveHint)
        save.addView(ctx.label("冲洗尺寸（长边像素）", 15f).apply { setPadding(0, dp(12), 0, dp(8)) })
        save.addView(Segmented(ctx, Prefs.longEdgeNames, Prefs.longEdgeIndex(ctx)) { Prefs.setLongEdgeIndex(ctx, it) })
        save.addView(ctx.sliderRow("JPEG 质量", 80f, 100f, Prefs.jpegQuality(ctx).toFloat(), 1f, { "%.0f".format(it) }) { v ->
            Prefs.setJpegQuality(ctx, v.toInt())
        })
        content.addView(save, matchWrap())

        // tethering
        content.addView(ctx.section("联机"))
        val tether = ctx.card()
        tether.addView(ctx.toggleRow("联机自动冲洗", "相机每拍一张，自动传到手机并按当前配方冲洗", Prefs.tether(ctx)) { Prefs.setTether(ctx, it) })
        tether.addView(ctx.toggleRow("只有 JPEG 时也冲洗", "相机设为仅 JPEG 时，从 JPEG 还原后冲洗（画质不如 RAW）", Prefs.developJpeg(ctx)) {
            Prefs.setDevelopJpeg(ctx, it)
        })
        tether.addView(ctx.toggleRow("冲好后自动进入沉浸模式", "联机拍摄时，第一张冲好就全屏显示，之后每张自动换上", Prefs.autoImmersive(ctx)) {
            Prefs.setAutoImmersive(ctx, it)
        })
        tether.addView(ctx.toggleRow("连接相机时屏幕常亮", null, Prefs.keepScreenOn(ctx)) {
            Prefs.setKeepScreenOn(ctx, it)
            activity.applyScreenOn()
        })
        content.addView(tether, matchWrap())

        // screen
        content.addView(ctx.section("屏幕"))
        val screen = ctx.card()
        screen.addView(ctx.label("屏幕方向", 15f).apply { setPadding(0, dp(8), 0, dp(8)) })
        screen.addView(Segmented(ctx, Prefs.orientationNames, Prefs.orientation(ctx)) {
            Prefs.setOrientation(ctx, it)
            Prefs.applyOrientation(activity)
        })
        screen.addView(ctx.label("「自动旋转」跟随手机的方向锁定；横屏下网格会显示更多列。", 12f, Ui.SUB).apply {
            setPadding(0, dp(8), 0, dp(4))
        })
        content.addView(screen, matchWrap())

        // diagnostics
        content.addView(ctx.section("诊断"))
        val about = ctx.card()
        val gpu = ctx.label("GPU：检测中…", 13f, Ui.SUB)
        about.addView(gpu)
        Thread {
            val text = runCatching { "GPU：" + FilmEngine.get(ctx).deviceName }.getOrElse { "GPU 引擎不可用：${it.message}" }
            post { gpu.text = text }
        }.start()
        CameraHub.device?.let { d ->
            about.addView(ctx.label("相机：${d.manufacturer} ${d.model} · 固件 ${d.version} · 序列号 ${d.serial}", 13f, Ui.SUB).apply {
                setPadding(0, dp(6), 0, 0)
            })
        }
        val errors = ErrorLog.count
        about.addView(ctx.hScroll(ctx.row(
            ctx.button(if (errors > 0) "错误日志 · $errors" else "错误日志", danger = errors > 0) { ctx.showErrorLog() },
            ctx.button("连接日志") { ctx.showConnectionLog() },
            ctx.button("复制诊断报告") { ctx.copy("Photo All 联机诊断", diagnosticReport()) },
        ).apply { setPadding(0, dp(12), 0, dp(4)) }))
        content.addView(about, matchWrap())
        if (wide) twoColumns()
    }
}
