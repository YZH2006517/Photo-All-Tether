package org.photoall.tether.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.photoall.tether.develop.Preview
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
 * 调参台 (after Photo All's tune bench): one picture — the latest RAW from the camera, or any
 * RAW / JPEG / HEIF on the phone — developed with the film engine while the recipe changes, the
 * original and the develop in one frame split by a divider to drag (pinch to look closely: it
 * then develops near full size, so grain and sharpness are what the print will have). The
 * controls are the few that matter, each with what its ends do (常用), or every engine
 * parameter a group to a tab (全部参数). Nothing reaches the recipe until 应用到配方.
 *
 * Landscape first: the picture fills the left at the screen's full height, the controls run
 * down a column on the right with the actions at its foot; portrait stacks them. Turning the
 * phone only rearranges the same views (no new develop).
 */
class TuneActivity : AdaptiveActivity(), TuneBench.Host {
    private lateinit var bench: TuneBench
    private var wide: Boolean? = null
    private lateinit var bar: LinearLayout
    /** Landscape: back, the modes and a source button in one row, so the controls keep the height. */
    private lateinit var wideBar: LinearLayout
    private val root by lazy { FrameLayout(this) }

    override val pickSource: (() -> Unit) = { pickFile() }
    override val alive: Boolean get() = !isFinishing && !isDestroyed

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        org.photoall.tether.develop.Prefs.applyOrientation(this)
        // opened on a print: its own recipe to start from
        val start = intent.getStringExtra(EXTRA_RECIPE)?.let { runCatching { Recipe.fromJson(org.json.JSONObject(it)) }.getOrNull() }
            ?: Recipe.load(this)
        bench = TuneBench(this, start, this)
        build()
        padForSystemBars(root)
        // Android 13+: back comes as a callback (with a target SDK of 36 onBackPressed is not called)
        if (android.os.Build.VERSION.SDK_INT >= 33)
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { leave() }
        setContentView(root)
        arrange(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        val uri = intent.getStringExtra(EXTRA_URI)
        val name = intent.getStringExtra(EXTRA_NAME)
        when {
            uri != null -> bench.loadUri(Uri.parse(uri), name ?: "原片")
            // opened on a print: only that print's own raw, never another one
            intent.getBooleanExtra(EXTRA_PINNED, false) ->
                if (intent.getBooleanExtra(EXTRA_LATEST, false)) Preview.source(this)?.let { bench.load(it, name ?: it.name) }
                else Toast.makeText(this, "这张照片的原片没有存在手机上：可以点「换素材」选一张", Toast.LENGTH_LONG).show()
            else -> Preview.source(this)?.let { bench.load(it, it.name) }
        }
    }

    override fun onResume() {
        super.onResume()
        bench.resumed()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        arrange(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    override fun onDestroy() {
        super.onDestroy()
        bench.destroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = leave()

    override fun finish() {
        super.finish()
        // came up from below: goes back down
        if (intent.getBooleanExtra(EXTRA_BELOW, false)) {
            @Suppress("DEPRECATION")
            overridePendingTransition(org.photoall.tether.R.anim.stay, org.photoall.tether.R.anim.fall_out)
        }
    }

    /** Back: straight out, or asked first when the tuning has not reached the recipe. */
    private fun leave() {
        if (!bench.dirty) { finish(); return }
        AlertDialog.Builder(this).setMessage("调整还没应用到配方")
            .setPositiveButton("应用并返回") { _, _ -> bench.apply(); finish() }
            .setNegativeButton("放弃") { _, _ -> finish() }
            .setNeutralButton("继续调", null)
            .show()
    }

    private fun build() {
        bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(iconButton(Glyph.CHEVRON_LEFT, Ui.ACCENT, 40, filled = false, description = "返回") { leave() })
        bar.addView(label("调参台", Ui.TITLE, Ui.TEXT, bold = true).single(),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
        bar.addView(button("换素材", glyph = Glyph.PHOTO) { chooseSource() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        wideBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        wideBar.addView(iconButton(Glyph.CHEVRON_LEFT, Ui.ACCENT, 40, filled = false, description = "返回") { leave() })
    }

    /** Portrait: the bar, the picture, the line and modes, the controls, the actions. Landscape: the picture left, the rest a column right. */
    private fun arrange(wide: Boolean) {
        if (this.wide == wide) return
        this.wide = wide
        val b = bench
        listOf(bar, wideBar, b.frame, b.recipeLine, b.modes, b.scroll, b.actions).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        while (wideBar.childCount > 1) wideBar.removeViewAt(1)
        root.removeAllViews()
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (!wide) {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(4), dp(12), dp(10))
                addView(bar, matchWrap())
                // the picture a fixed share of the height, whatever the display size
                addView(b.frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels * 0.38f).roundToInt()).apply { topMargin = dp(4) })
                modeRow.addView(b.recipeLine, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                modeRow.addView(b.modes, LinearLayout.LayoutParams(dp(170), dp(34)).apply { marginStart = dp(10) })
                addView(modeRow, matchWrap().apply { topMargin = dp(8); bottomMargin = dp(4) })
                addView(b.scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(b.actions, matchWrap().apply { topMargin = dp(8) })
            }
            root.addView(column, frameMatch())
        } else {
            // the picture as large as the screen's height allows; the controls a column beside it
            val left = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(6), dp(8))
                addView(b.frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(b.recipeLine, matchWrap().apply { topMargin = dp(6) })
            }
            val right = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(2), dp(12), dp(8))
                wideBar.addView(b.modes, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginStart = dp(2); marginEnd = dp(8) })
                wideBar.addView(iconButton(Glyph.PHOTO, Ui.ACCENT, 38, description = "换素材") { chooseSource() })
                addView(wideBar, matchWrap().apply { topMargin = dp(4); bottomMargin = dp(8) })
                addView(b.scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(b.actions, matchWrap().apply { topMargin = dp(8) })
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.55f))
                addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
            root.addView(row, frameMatch())
        }
    }

    private fun chooseSource() {
        val latest = Preview.source(this)
        val choices = buildList {
            if (latest != null) add("相机最近一张 RAW" to { bench.load(latest, latest.name) })
            add("从手机选文件（RAW / JPEG / HEIF）" to { pickFile() })
        }
        AlertDialog.Builder(this).setTitle("换素材")
            .setItems(choices.map { it.first }.toTypedArray()) { _, i -> choices[i].second() }
            .show()
    }

    private fun pickFile() {
        runCatching {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, REQUEST_PICK)
        }
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        bench.loadUri(uri, displayName(uri) ?: "picked")
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    companion object {
        private const val REQUEST_PICK = 52

        private const val EXTRA_URI = "uri"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_RECIPE = "recipe"
        private const val EXTRA_LATEST = "latest"
        private const val EXTRA_PINNED = "pinned"
        private const val EXTRA_BELOW = "below"

        /**
         * The bench; on a given picture ([uri], a raw or image) and [recipe] (JSON) when given.
         * [fromBelow]: it rises from below and goes back down (opened by a swipe up).
         */
        fun open(context: Context, uri: String? = null, name: String? = null, recipe: String? = null, fromBelow: Boolean = false,
                 useLatest: Boolean = false, pinned: Boolean = false) {
            context.startActivity(Intent(context, TuneActivity::class.java).apply {
                uri?.let { putExtra(EXTRA_URI, it) }
                name?.let { putExtra(EXTRA_NAME, it) }
                recipe?.let { putExtra(EXTRA_RECIPE, it) }
                putExtra(EXTRA_LATEST, useLatest)
                putExtra(EXTRA_PINNED, pinned)
                putExtra(EXTRA_BELOW, fromBelow)
            })
            if (fromBelow && context is android.app.Activity) {
                @Suppress("DEPRECATION")
                context.overridePendingTransition(org.photoall.tether.R.anim.rise_in, org.photoall.tether.R.anim.stay)
            }
        }
    }
}
