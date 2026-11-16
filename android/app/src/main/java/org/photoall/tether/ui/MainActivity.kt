package org.photoall.tether.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.photoall.tether.camera.CameraHub
import org.photoall.tether.develop.DevelopQueue
import org.photoall.tether.develop.Prefs

/** A page that lays itself out differently in landscape. */
interface Arrangeable {
    fun arrange(wide: Boolean)
}

/** Four pages: the camera's cards, the darkroom (queue and library), the recipe, the settings. */
class MainActivity : Activity() {
    private lateinit var content: FrameLayout
    private lateinit var root: LinearLayout
    private lateinit var nav: LinearLayout
    private var arrangedWide: Boolean? = null

    /** Landscape: the screen is wider than tall (pages lay themselves out in columns then). */
    val wide: Boolean get() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // Portrait: the pages above a tab bar. Landscape: a navigation rail on the left, the page
    // beside it, so the page keeps its full height.
    private fun arrange() {
        val w = wide
        if (arrangedWide == w) return
        arrangedWide = w
        root.removeAllViews()
        nav.removeAllViews()
        root.orientation = if (w) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        nav.orientation = if (w) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        nav.gravity = if (w) Gravity.CENTER_VERTICAL else Gravity.NO_GRAVITY
        nav.setPadding(dp(8), dp(4), dp(8), dp(4))
        tabs.forEach { tab ->
            tab.setPadding(0, dp(if (w) 16 else 12), 0, dp(if (w) 16 else 12))
            nav.addView(tab, if (w) LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
            else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        if (w) {
            root.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        } else {
            root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        styleTabs()
        pages.forEach { (it as? Arrangeable)?.arrange(w) }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        arrange()
    }

    private fun styleTabs() {
        tabs.forEachIndexed { k, t ->
            val on = k == current
            t.setTextColor(if (on) Ui.ACCENT else Ui.SUB)
            t.typeface = Typeface.create(Typeface.DEFAULT, if (on) Typeface.BOLD else Typeface.NORMAL)
            // the rail marks the page with a pill, the bar with the colour alone
            t.background = if (on && arrangedWide == true) rounded(Ui.ACCENT_DEEP, dp(14).toFloat()) else null
        }
    }
    private val tabs = mutableListOf<TextView>()
    private val pages = arrayOfNulls<View>(4)
    private var current = -1
    private val queueListener: () -> Unit = { updateBadge(); maybeImmersive() }
    private val hubListener: () -> Unit = { applyScreenOn() }
    private var started = false
    private val shownJobs = HashSet<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.applyOrientation(this)
        root = LinearLayout(this).apply { setBackgroundColor(Ui.BG) }
        content = FrameLayout(this)
        nav = LinearLayout(this).apply { setBackgroundColor(Ui.SURFACE) }
        listOf("相机", "暗房", "配方", "设置").forEachIndexed { i, name ->
            val tab = TextView(this).apply {
                text = name
                textSize = 15f
                gravity = Gravity.CENTER
                setOnClickListener { showTab(i) }
            }
            tabs += tab
        }
        arrange()
        setContentView(root)
        padForSystemBars(root)
        showTab(savedInstanceState?.getInt("tab") ?: 0)
        handleUsb(intent)
        if (org.photoall.tether.ErrorLog.takeCrashFlag()) {
            android.app.AlertDialog.Builder(this).setTitle("上次意外退出")
                .setMessage("错误已记录。复制诊断报告发给开发者，可以帮助修复。")
                .setPositiveButton("复制报告") { _, _ -> copy("Photo All 联机诊断", diagnosticReport()) }
                .setNeutralButton("查看") { _, _ -> showErrorLog() }
                .setNegativeButton("关闭", null).show()
        }
        if (CameraHub.state == CameraHub.State.DISCONNECTED) CameraHub.findCamera()?.let { CameraHub.connect(it) }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsb(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", current)
    }

    override fun onStart() {
        super.onStart()
        DevelopQueue.addListener(queueListener)
        CameraHub.addListener(hubListener)
        started = true
        // prints already done before coming back do not open the immersive view
        DevelopQueue.jobs.filter { it.finished }.forEach { shownJobs += it.id }
        updateBadge()
        applyScreenOn()
    }

    override fun onStop() {
        DevelopQueue.removeListener(queueListener)
        CameraHub.removeListener(hubListener)
        started = false
        super.onStop()
    }

    // Plugging the camera in starts the app with the device (and its USB permission).
    private fun handleUsb(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            CameraHub.deviceOf(intent)?.let { CameraHub.connect(it) }
            showTab(0)
        }
    }

    fun showTab(i: Int) {
        if (i == current) return
        current = i
        val page = pages[i] ?: when (i) {
            0 -> CameraPage(this)
            1 -> DarkroomPage(this)
            2 -> RecipePage(this)
            else -> SettingsPage(this)
        }.also { pages[i] = it }
        content.removeAllViews()
        (page as? Arrangeable)?.arrange(wide)
        content.addView(page, frameMatch())
        styleTabs()
        updateBadge()
    }

    /** Keeps the screen on while a camera is connected, when the setting asks for it. */
    fun applyScreenOn() {
        val on = Prefs.keepScreenOn(this) && CameraHub.state == CameraHub.State.CONNECTED
        if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // A shot from the camera developed while this screen is in front: show it immersive.
    private fun maybeImmersive() {
        if (!started || !Prefs.autoImmersive(this) || ImmersiveActivity.showing) return
        val done = DevelopQueue.jobs.filter { it.auto && it.status == DevelopQueue.Status.DONE && it.id !in shownJobs }
        if (done.isEmpty()) return
        done.forEach { shownJobs += it.id }
        ImmersiveActivity.open(this)
    }

    /** Android asks the user before deleting files of an earlier install; then they leave the library. */
    fun confirmDelete(sender: android.content.IntentSender, entries: List<org.photoall.tether.develop.Library.Entry>) {
        pendingDelete = entries
        startIntentSenderForResult(sender, REQUEST_DELETE, null, 0, 0, 0)
    }

    private var pendingDelete: List<org.photoall.tether.develop.Library.Entry> = emptyList()

    private fun updateBadge() {
        val active = DevelopQueue.active
        tabs.getOrNull(1)?.text = if (active > 0) "暗房 · $active" else "暗房"
    }

    /** The system file picker, for raw files (or JPEGs) already on the phone. */
    fun pickLocal() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/octet-stream"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, REQUEST_PICK)
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DELETE) {
            if (resultCode == RESULT_OK) org.photoall.tether.develop.Library.forget(this, pendingDelete)
            pendingDelete = emptyList()
            return
        }
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK || data == null) return
        val uris = buildList {
            data.clipData?.let { c -> for (i in 0 until c.itemCount) add(c.getItemAt(i).uri) }
            if (isEmpty()) data.data?.let { add(it) }
        }
        var added = 0
        for (uri in uris) {
            val name = displayName(uri) ?: continue
            val ext = name.substringAfterLast('.', "").uppercase()
            if (ext !in org.photoall.tether.ptp.ObjectInfo.RAW_EXTENSIONS && ext != "JPG" && ext != "JPEG") {
                Toast.makeText(this, "不支持 $name", Toast.LENGTH_SHORT).show()
                continue
            }
            DevelopQueue.addLocal(this, uri, name)
            added++
        }
        if (added > 0) {
            Toast.makeText(this, "$added 张加入冲洗", Toast.LENGTH_SHORT).show()
            showTab(1)
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private companion object {
        const val REQUEST_PICK = 11
        const val REQUEST_DELETE = 12
    }
}
