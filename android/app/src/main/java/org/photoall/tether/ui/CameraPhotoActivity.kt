package org.photoall.tether.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import org.photoall.tether.camera.CameraHub
import org.photoall.tether.camera.CameraItem
import org.photoall.tether.develop.DevelopQueue
import org.photoall.tether.develop.Library
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** One shot on the camera: a large preview, its files and what can be done with it. Swipe for the next. */
class CameraPhotoActivity : Activity() {
    private lateinit var image: ImageView
    private lateinit var spinner: ProgressBar
    private lateinit var title: android.widget.TextView
    private lateinit var info: android.widget.TextView
    private lateinit var protectButton: android.widget.TextView
    private var keys: List<String> = emptyList()
    private var index = 0
    private var item: CameraItem? = null
    private var loadToken = 0
    private val hubListener: () -> Unit = { if (CameraHub.state != CameraHub.State.CONNECTED) finish() else refreshInfo() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keys = CameraPage.visibleKeys
        org.photoall.tether.develop.Prefs.applyOrientation(this)
        index = savedInstanceState?.getInt("index")?.takeIf { it in keys.indices }
            ?: keys.indexOf(intent.getStringExtra(KEY)).coerceAtLeast(0)
        // landscape: the picture on the left, the panel on the right
        val wide = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            if (wide) {
                marginEnd = dp(320)
                topMargin = dp(8)
                bottomMargin = dp(8)
            } else {
                bottomMargin = dp(190)
                topMargin = dp(56)
            }
        })
        spinner = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(spinner, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(14), dp(6))
        }
        top.addView(label("‹", 30f).apply { setPadding(dp(10), 0, dp(14), dp(4)); setOnClickListener { finish() } })
        title = label("", 16f, bold = true).single()
        top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(top, FrameLayout.LayoutParams(if (wide) dp(320) else ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, if (wide) Gravity.TOP or Gravity.END else Gravity.TOP))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Ui.SURFACE, dp(18).toFloat())
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        info = label("", 13f, Ui.SUB).apply { setLineSpacing(0f, 1.35f) }
        panel.addView(info)
        protectButton = button("保护") { protect() }
        val actions = row(
            button("冲洗", primary = true) { develop(true) },
            button("导入原片") { develop(false) },
            protectButton,
            button("删除", danger = true) { delete() },
        ).apply { setPadding(0, dp(12), 0, 0) }
        panel.addView(hScroll(actions))
        if (wide) {
            panel.removeView(info)
            panel.addView(android.widget.ScrollView(this).apply { addView(info) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(panel, FrameLayout.LayoutParams(dp(304), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END).apply {
                setMargins(0, dp(60), dp(8), dp(8))
            })
        } else root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(10), 0, dp(10), dp(10))
        })

        val swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) < abs(vy) || abs(vx) < 400) return false
                go(if (vx < 0) index + 1 else index - 1)
                return true
            }
        })
        image.setOnTouchListener { _, e -> swipe.onTouchEvent(e); true }
        setContentView(root)
        padForSystemBars(root)
        go(index)
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

    private fun go(i: Int) {
        if (i !in keys.indices) return
        index = i
        val it = current() ?: return finish()
        item = it
        refreshInfo()
        val token = ++loadToken
        val small = CameraHub.cachedThumb(it.jpeg ?: it.primary)
        image.setImageBitmap(small)
        spinner.visibility = View.VISIBLE
        CameraHub.run {
            val big: Bitmap? = runCatching { CameraHub.preview(it, 2400) }.getOrNull()
            runOnUiThread {
                if (token != loadToken) return@runOnUiThread
                spinner.visibility = View.GONE
                if (big != null) image.setImageBitmap(big)
                else if (small == null) Toast.makeText(this, "相机没有给出预览", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshInfo() {
        val it = current() ?: return
        item = it
        title.text = "${it.name}   ${index + 1} / ${keys.size}"
        val time = if (it.time > 0) SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(it.time)) else "未知"
        val onPhone = Library.originalOf(it.key) != null
        val prints = Library.printsOf(it.key)
        val lines = mutableListOf<String>()
        it.files.forEach { f ->
            val dims = if (f.imageWidth > 0) " · ${f.imageWidth}×${f.imageHeight}" else ""
            lines += "${f.filename}  ${formatBytes(f.size)}$dims"
        }
        lines += "拍摄 $time · ${CameraHub.storageName(it.storageId)}"
        lines += listOfNotNull(
            if (it.isProtected) "受保护" else null,
            if (onPhone) "原片已存到手机" else "原片未导入",
            if (prints > 0) "已冲洗 $prints 张" else null,
        ).joinToString(" · ")
        info.text = lines.joinToString("\n")
        protectButton.text = if (it.isProtected) "取消保护" else "保护"
    }

    private fun develop(develop: Boolean) {
        val it = item ?: return
        if (develop && !it.canDevelop) {
            Toast.makeText(this, "这个文件不能冲洗（${it.badge}）", Toast.LENGTH_SHORT).show()
            return
        }
        DevelopQueue.addCamera(this, it, develop)
        Toast.makeText(this, if (develop) "已加入冲洗" else "开始导入原片", Toast.LENGTH_SHORT).show()
    }

    private fun protect() {
        val it = item ?: return
        CameraHub.run {
            val errors = CameraHub.protect(listOf(it), !it.isProtected)
            runOnUiThread {
                if (errors.isNotEmpty()) Toast.makeText(this, errors.first(), Toast.LENGTH_LONG).show()
                refreshInfo()
            }
        }
    }

    private fun delete() {
        val it = item ?: return
        AlertDialog.Builder(this).setTitle("从相机删除 ${it.name}？")
            .setMessage("将从存储卡删除 ${it.files.joinToString("、") { f -> f.filename }}，无法撤销。")
            .setPositiveButton("删除") { _, _ ->
                CameraHub.run {
                    val errors = CameraHub.delete(listOf(it))
                    runOnUiThread {
                        if (errors.isNotEmpty()) {
                            Toast.makeText(this, errors.first(), Toast.LENGTH_LONG).show()
                            return@runOnUiThread
                        }
                        keys = keys - it.key
                        if (keys.isEmpty()) finish() else go(index.coerceAtMost(keys.lastIndex))
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
