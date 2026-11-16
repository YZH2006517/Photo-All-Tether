package org.photoall.tether.ui

import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import org.photoall.tether.ErrorLog
import org.photoall.tether.camera.CameraHub
import org.photoall.tether.develop.Prefs
import org.photoall.tether.develop.Roll
import org.photoall.tether.engine.FilmEngine
import org.photoall.tether.engine.Films

/** Settings as iOS lays them out: grouped rows, each led by a coloured glyph tile. */
class SettingsPage(private val activity: MainActivity) : ScrollView(activity), Arrangeable {
    private val ctx = activity
    private val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private var wide = false

    init {
        setBackgroundColor(Ui.BG)
        isFillViewport = true
        content.setPadding(dp(16), dp(6), dp(16), dp(48))
        addView(content)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        build()
    }

    override fun arrange(wide: Boolean) {
        if (this.wide == wide && content.childCount > 0) return
        this.wide = wide
        if (isAttachedToWindow) build()
    }

    private fun build() {
        wide = activity.wide
        content.removeAllViews()
        content.addView(ctx.largeTitle("设置"))

        val blocks = mutableListOf<LinearLayout>()
        fun block(title: String, fill: LinearLayout.() -> Unit) {
            val b = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            // the first block of each column needs less air above it than the rest
            b.addView(ctx.section(title).apply { if (blocks.isEmpty() || (wide && blocks.size == 1)) setPadding(dp(16), dp(12), dp(16), dp(8)) })
            val card = ctx.card()
            card.fill()
            b.addView(card, matchWrap())
            blocks += b
        }

        block("保存") {
            addView(Segmented(ctx, listOf("只存 JPG", "JPG + 原片"), if (Prefs.keepOriginal(ctx)) 1 else 0,
                listOf(Glyph.PHOTO, Glyph.PHOTO_STACK)) { Prefs.setKeepOriginal(ctx, it == 1) }, matchWrap().apply { topMargin = dp(8); bottomMargin = dp(8) })
            addView(ctx.separator(0))
            addView(ctx.listRow("尺寸", null, Glyph.EXPAND, Ui.BLUE))
            addView(Segmented(ctx, Prefs.longEdgeNames, Prefs.longEdgeIndex(ctx)) { Prefs.setLongEdgeIndex(ctx, it) },
                matchWrap().apply { bottomMargin = dp(8) })
            addView(ctx.separator(42))
            addView(ctx.sliderRow("JPEG 质量", 80f, 100f, Prefs.jpegQuality(ctx).toFloat(), 1f, { "%.0f".format(it) }, glyph = Glyph.PHOTO, tint = Ui.TEAL) { v ->
                Prefs.setJpegQuality(ctx, v.toInt())
            })
        }

        block("联机") {
            addView(ctx.toggleRow("自动冲洗", null, Prefs.tether(ctx), Glyph.BOLT_FILL, Ui.ACCENT) { Prefs.setTether(ctx, it) })
            addView(ctx.separator(42))
            addView(ctx.toggleRow("只有 JPEG 也冲洗", null, Prefs.developJpeg(ctx), Glyph.PHOTO, Ui.GREEN) { Prefs.setDevelopJpeg(ctx, it) })
            addView(ctx.separator(42))
            addView(ctx.toggleRow("冲好后进入沉浸", null, Prefs.autoImmersive(ctx), Glyph.EXPAND, Ui.INDIGO) { Prefs.setAutoImmersive(ctx, it) })
            addView(ctx.separator(42))
            addView(ctx.toggleRow("屏幕常亮", null, Prefs.keepScreenOn(ctx), Glyph.SUN, Ui.YELLOW) {
                Prefs.setKeepScreenOn(ctx, it)
                activity.applyScreenOn()
            })
        }

        block("胶卷") {
            val film = Roll.film(ctx).takeIf { it >= 0 }
            addView(ctx.listRow(film?.let { Films.short(it) } ?: "未装片", "${Roll.frames(ctx)} / ${Roll.FRAMES}",
                Glyph.ROLL, Ui.ACCENT) { film?.let { Cartridge3DActivity.open(ctx, it) } })
            addView(ctx.separator(42))
            addView(ctx.listRow("已拍完", null, Glyph.CHECK_CIRCLE, Ui.GREEN,
                ctx.label("${Roll.finished(ctx)} 卷", Ui.BODY, Ui.SUB)))
        }

        block("屏幕") {
            addView(ctx.listRow("外观", null, Glyph.CONTRAST, Ui.INDIGO))
            addView(Segmented(ctx, Prefs.themeNames, Prefs.theme(ctx)) {
                Prefs.setTheme(ctx, it)
                // the whole app builds again in the new colours
                if (Adaptive.isLight(ctx) != Ui.light) activity.recreate()
            }, matchWrap().apply { bottomMargin = dp(8) })
            addView(ctx.separator(42))
            addView(ctx.listRow("方向", null, Glyph.ROTATE, Ui.PINK))
            addView(Segmented(ctx, Prefs.orientationNames, Prefs.orientation(ctx)) {
                Prefs.setOrientation(ctx, it)
                Prefs.applyOrientation(activity)
            }, matchWrap().apply { bottomMargin = dp(8) })
            addView(ctx.separator(42))
            addView(ctx.toggleRow("全屏", null, Prefs.fullscreen(ctx), Glyph.EXPAND, Ui.TEAL) {
                Prefs.setFullscreen(ctx, it)
                activity.applyBars()
            })
        }

        block("诊断") {
            val gpu = ctx.label("…", Ui.BODY, Ui.SUB).single()
            addView(ctx.listRow("GPU", null, Glyph.CUBE, Ui.GREY, gpu))
            Thread {
                val text = runCatching { FilmEngine.get(ctx).deviceName }.getOrElse { "不可用" }
                post { gpu.text = text }
            }.start()
            CameraHub.device?.let { d ->
                addView(ctx.separator(42))
                addView(ctx.listRow(d.model.replace('_', ' '), "固件 ${d.version}", Glyph.CAMERA, Ui.BLUE) { CameraInfoActivity.open(ctx) })
            }
            addView(ctx.separator(42))
            val errors = ErrorLog.count
            addView(ctx.listRow("错误日志", null, Glyph.LIST, if (errors > 0) Ui.RED else Ui.GREY,
                ctx.label(if (errors > 0) errors.toString() else "", Ui.BODY, Ui.SUB).apply { setPadding(0, 0, dp(8), 0) }) { ctx.showErrorLog() })
            addView(ctx.separator(42))
            addView(ctx.listRow("连接日志", null, Glyph.CABLE, Ui.GREY) { ctx.showConnectionLog() })
            addView(ctx.separator(42))
            addView(ctx.listRow("复制诊断报告", null, Glyph.COPY, Ui.GREY) { ctx.copy("Photo All 联机诊断", diagnosticReport()) })
        }

        if (!wide) blocks.forEach { content.addView(it, matchWrap()) }
        else {
            // landscape: two columns of about equal height, both starting on the same line —
            // what is kept and the screen on the left; the camera link, the roll, diagnosis on the right
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val columns = List(2) { LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL } }
            val rightSide = setOf(1, 2, 4)
            blocks.forEachIndexed { i, b -> columns[if (i in rightSide) 1 else 0].addView(b, matchWrap()) }
            columns.forEachIndexed { i, c ->
                row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(16) })
            }
            content.addView(row, matchWrap())
        }
        content.addView(ctx.label("Photo All 联机 " + runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty(), Ui.FOOTNOTE, Ui.DIM).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, 0)
        }, matchWrap())
        blocks.forEachIndexed { i, b -> b.rise(i * 50L) }
    }
}
