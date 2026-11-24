package org.photoall.tether.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
import org.photoall.tether.camera.CameraHub
import org.photoall.tether.develop.DevelopQueue
import org.photoall.tether.develop.Prefs
import org.photoall.tether.develop.Roll

/** A page that lays itself out differently in landscape. */
interface Arrangeable {
    fun arrange(wide: Boolean)
}

/**
 * Four pages under a tab bar (a rail on the left in landscape): the camera, the darkroom, the
 * recipe, the settings. It also owns the feel of the camera's events, so each fires once: a
 * frame exposed, a print developed, a roll finished.
 */
class MainActivity : AdaptiveActivity() {
    private lateinit var content: FrameLayout
    private lateinit var root: LinearLayout
    private lateinit var nav: LinearLayout
    private var arrangedWide: Boolean? = null
    private val tabs = mutableListOf<Tab>()
    private val pages = arrayOfNulls<View>(4)
    private var current = -1
    private var started = false
    private val shownJobs = HashSet<Long>()
    private val celebrated = HashSet<Long>()
    private val queueListener: () -> Unit = { updateBadge(); onQueue() }
    private val hubListener: () -> Unit = { applyScreenOn(); updateRailStatus() }
    private val rollListener: (Roll.Event) -> Unit = { onRoll(it); updateRailStatus() }

    /** Landscape: the screen is wider than tall (pages lay themselves out in columns then). */
    val wide: Boolean get() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    /** A tab: its glyph over a small caption, a badge for work in progress; springs when chosen. */
    private inner class Tab(val glyph: Glyph, val name: String, val index: Int) : LinearLayout(this@MainActivity) {
        val icon = IconView(context, glyph, Ui.GREY, 26)
        val caption = context.label(name, 10.5f, Ui.GREY, bold = true)
        val badge = context.label("", 10f, android.graphics.Color.WHITE, bold = true).apply {
            typeface = Ui.mono
            gravity = Gravity.CENTER
            background = rounded(Ui.RED, dp(9).toFloat())
            setPadding(dp(5), dp(1), dp(5), dp(1))
            visibility = View.GONE
        }
        val iconBox = FrameLayout(context)

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            iconBox.addView(icon, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            badge.minWidth = dp(17)
            // the badge sits inside the box (over the glyph's corner), so no parent can clip it
            iconBox.addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(17), Gravity.TOP or Gravity.END))
            addView(iconBox, LayoutParams(dp(56), dp(34)))
            addView(caption, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
            contentDescription = name
            setOnClickListener {
                if (current != index) Haptic.tick(this)
                showTab(index)
            }
        }

        fun style(on: Boolean, animate: Boolean) {
            val c = if (on) Ui.ACCENT else Ui.GREY
            icon.color = c
            caption.setTextColor(c)
            if (on && animate && !Ease.reduced(context)) {
                icon.scaleX = 0.72f; icon.scaleY = 0.72f
                icon.animate().scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(Ease.bouncy).start()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.applyOrientation(this)
        root = LinearLayout(this).apply { setBackgroundColor(Ui.BG) }
        content = FrameLayout(this)
        nav = LinearLayout(this)
        listOf(Glyph.CAMERA to "相机", Glyph.FILM to "暗房", Glyph.DIAL to "配方", Glyph.GEAR to "设置")
            .forEachIndexed { i, (g, n) -> tabs += Tab(g, n, i) }
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

    // ---- the rail (landscape): a floating column — the four tabs in the middle with a lit pill
    // that slides to the chosen one, the camera's state at the foot.
    private val railFrame by lazy { FrameLayout(this) }
    private val railPill by lazy { View(this) }
    private val railDot by lazy { PulseDot(this) }
    private val railState by lazy { label("", 10f, Ui.SUB, bold = true).apply { gravity = Gravity.CENTER } }
    private val railBattery by lazy { BatteryView(this) }
    private val railFrames by lazy { label("", 11f, Ui.TEXT, bold = true).apply { typeface = Ui.mono; gravity = Gravity.CENTER } }
    // the bar (portrait): the same pill, behind the chosen tab's glyph
    private val barPill by lazy { View(this) }

    // Portrait: the pages above a tab bar. Landscape: the rail on the left, the page beside it
    // at full height.
    private fun arrange() {
        val w = wide
        if (arrangedWide == w) return
        arrangedWide = w
        root.removeAllViews()
        nav.removeAllViews()
        (nav.parent as? ViewGroup)?.removeView(nav)
        railFrame.removeAllViews()
        (barPill.parent as? ViewGroup)?.removeView(barPill)
        root.orientation = if (w) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (w) buildRail() else buildBar()
        tabs.forEach { it.style(it.index == current, false) }
        pages.forEach { (it as? Arrangeable)?.arrange(w) }
        root.post { placePill(false) }
    }

    private fun buildRail() {
        nav.orientation = LinearLayout.VERTICAL
        nav.gravity = Gravity.CENTER_HORIZONTAL
        nav.background = null
        nav.setPadding(0, dp(10), 0, dp(10))
        nav.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        tabs.forEach { tab ->
            nav.addView(tab, LinearLayout.LayoutParams(dp(62), dp(58)).apply { topMargin = dp(2); bottomMargin = dp(2) })
        }
        nav.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        // the camera at the foot: its light, its battery, the frame on the roll. These views live
        // on across rotations, so out of the rail they sat in last time before going in again.
        listOf(railDot, railState, railBattery, railFrames).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        val status = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(Ui.SURFACE2, dp(14).toFloat())
            setPadding(dp(2), dp(6), dp(2), dp(8))
            // the light and the state on one line, the battery (when there is one), the frame
            val light = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(railDot, LinearLayout.LayoutParams(dp(14), dp(14)))
                addView(railState, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(1) })
            }
            addView(light, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(railBattery, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            addView(railFrames, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
            setOnClickListener { showTab(0) }
        }
        nav.addView(status, LinearLayout.LayoutParams(dp(54), ViewGroup.LayoutParams.WRAP_CONTENT))

        railPill.background = rounded(blend(Ui.SURFACE, Ui.ACCENT, if (Ui.light) 0.16f else 0.22f), dp(16).toFloat())
        railFrame.background = rounded(Ui.SURFACE, dp(24).toFloat(), if (Ui.light) 1 else 0, Ui.LINE)
        railFrame.addView(railPill, FrameLayout.LayoutParams(dp(62), dp(58)))
        railFrame.addView(nav, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(railFrame, LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            setMargins(dp(8), dp(8), dp(4), dp(8))
        })
        root.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        updateRailStatus()
    }

    private fun buildBar() {
        nav.orientation = LinearLayout.HORIZONTAL
        nav.gravity = Gravity.CENTER
        nav.background = null
        nav.setPadding(dp(8), dp(6), dp(8), dp(4))
        tabs.forEach { tab -> nav.addView(tab, LinearLayout.LayoutParams(0, dp(54), 1f)) }
        barPill.background = rounded(blend(Ui.BG, Ui.ACCENT, if (Ui.light) 0.14f else 0.2f), dp(15).toFloat())
        val bar = FrameLayout(this).apply { setBackgroundColor(Ui.BAR) }
        bar.addView(barPill, FrameLayout.LayoutParams(dp(56), dp(32)))
        bar.addView(nav, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val hairline = View(this).apply { setBackgroundColor(Ui.LINE) }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(hairline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** The lit pill moves (on a spring) to the chosen tab: the whole tab on the rail, its glyph on the bar. */
    private fun placePill(animate: Boolean) {
        val tab = tabs.getOrNull(current) ?: return
        if (tab.width == 0) { tab.post { placePill(false) }; return }
        val pill = if (wide) railPill else barPill
        val x: Float
        val y: Float
        if (wide) {
            x = (nav.left + tab.left).toFloat()
            y = (nav.top + tab.top).toFloat()
        } else {
            val box = tab.iconBox
            x = (nav.left + tab.left + box.left + (box.width - pill.width) / 2).toFloat()
            y = (nav.top + tab.top + box.top + (box.height - pill.height) / 2).toFloat()
        }
        if (animate && !Ease.reduced(this)) {
            pill.animate().translationX(x).translationY(y).setDuration(460).setInterpolator(Ease.spring).start()
        } else {
            pill.animate().cancel()
            pill.translationX = x
            pill.translationY = y
        }
    }

    private fun updateRailStatus() {
        if (arrangedWide != true) return
        val st = CameraHub.state
        val connected = st == CameraHub.State.CONNECTED
        railDot.color = when (st) {
            CameraHub.State.CONNECTED -> Ui.GREEN
            CameraHub.State.CONNECTING, CameraHub.State.WAITING_PERMISSION -> Ui.ACCENT
            CameraHub.State.ERROR -> Ui.RED
            else -> Ui.DIM
        }
        railDot.live = connected || st == CameraHub.State.CONNECTING
        railState.text = when (st) {
            CameraHub.State.CONNECTED -> "联机"
            CameraHub.State.CONNECTING, CameraHub.State.WAITING_PERMISSION -> "连接中"
            CameraHub.State.ERROR -> "出错"
            else -> "离线"
        }
        railState.setTextColor(if (connected) Ui.GREEN else Ui.SUB)
        val battery = CameraHub.settings.battery
        railBattery.level = battery
        railBattery.visibility = if (connected && battery != null) View.VISIBLE else View.GONE
        railFrames.text = "${Roll.frames(this)}/${Roll.FRAMES}"
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // following the system's light or dark: build again in the new colours
        if (Prefs.theme(this) == 0 && Adaptive.isLight(this) != Ui.light) { recreate(); return }
        arrange()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsb(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", current)
    }

    override fun onResume() {
        super.onResume()
        // back from the LUT page (or anything else that changed the recipe)
        (pages[2] as? RecipePage)?.reload()
        if (pendingTab >= 0) { showTab(pendingTab); pendingTab = -1 }
    }

    override fun onStart() {
        super.onStart()
        DevelopQueue.addListener(queueListener)
        CameraHub.addListener(hubListener)
        Roll.addListener(rollListener)
        started = true
        // prints already done before coming back neither celebrate nor open the immersive view
        DevelopQueue.jobs.filter { it.finished }.forEach { shownJobs += it.id; celebrated += it.id }
        updateBadge()
        applyScreenOn()
        updateRailStatus()
    }

    override fun onStop() {
        DevelopQueue.removeListener(queueListener)
        CameraHub.removeListener(hubListener)
        Roll.removeListener(rollListener)
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

    /** Shows a page: it fades in and rises a little, the tab's glyph springs. */
    fun showTab(i: Int) {
        if (i == current) return
        val first = current < 0
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
        if (!first) page.rise(0, 10)
        tabs.forEach { it.style(it.index == i, !first) }
        placePill(!first)
        updateBadge()
    }

    /** Keeps the screen on while a camera is connected, when the setting asks for it. */
    fun applyScreenOn() {
        val on = Prefs.keepScreenOn(this) && CameraHub.state == CameraHub.State.CONNECTED
        if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // A print developed: felt once (a rise and a landing); opened immersive when set to.
    private fun onQueue() {
        if (!started) return
        val done = DevelopQueue.jobs.filter { it.develop && it.status == DevelopQueue.Status.DONE && it.id !in celebrated }
        if (done.isNotEmpty()) {
            done.forEach { celebrated += it.id }
            Haptic.success(root)
        }
        if (!Prefs.autoImmersive(this) || ImmersiveActivity.showing) return
        val auto = DevelopQueue.jobs.filter { it.auto && it.status == DevelopQueue.Status.DONE && it.id !in shownJobs }
        if (auto.isEmpty()) return
        auto.forEach { shownJobs += it.id }
        ImmersiveActivity.open(this)
    }

    // The roll: a click for each frame, a rewind for the last.
    private fun onRoll(e: Roll.Event) {
        if (!started) return
        when (e) {
            is Roll.Event.Advanced -> if (e.frame < Roll.FRAMES) Haptic.shutter(root)
            is Roll.Event.Finished -> {
                Haptic.rollDone(root)
                Hud.show(this, Glyph.ROLL, "一卷拍完 · ${Roll.FRAMES} 张", Ui.ACCENT)
            }
            is Roll.Event.Loaded -> {}
        }
    }

    /** Android asks the user before deleting files of an earlier install; then they leave the library. */
    fun confirmDelete(sender: android.content.IntentSender, entries: List<org.photoall.tether.develop.Library.Entry>) {
        pendingDelete = entries
        startIntentSenderForResult(sender, REQUEST_DELETE, null, 0, 0, 0)
    }

    private var pendingDelete: List<org.photoall.tether.develop.Library.Entry> = emptyList()

    private fun updateBadge() {
        val active = DevelopQueue.active
        val badge = tabs.getOrNull(1)?.badge ?: return
        val was = badge.visibility == View.VISIBLE
        badge.text = active.toString()
        badge.visibility = if (active > 0) View.VISIBLE else View.GONE
        if (active > 0 && !was) badge.pop(0.3f)
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
            if (resultCode == RESULT_OK) {
                org.photoall.tether.develop.Library.forget(this, pendingDelete)
                Hud.show(this, Glyph.TRASH, "已删除")
            }
            pendingDelete = emptyList()
            return
        }
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK || data == null) return
        val uris = buildList {
            data.clipData?.let { c -> for (i in 0 until c.itemCount) add(c.getItemAt(i).uri) }
            if (isEmpty()) data.data?.let { add(it) }
        }
        var added = 0
        var refused = 0
        for (uri in uris) {
            val name = displayName(uri) ?: continue
            val ext = name.substringAfterLast('.', "").uppercase()
            if (ext !in org.photoall.tether.ptp.ObjectInfo.RAW_EXTENSIONS && ext !in setOf("JPG", "JPEG", "HIF", "HEIC", "HEIF")) { refused++; continue }
            DevelopQueue.addLocal(this, uri, name)
            added++
        }
        if (added > 0) {
            Hud.show(this, Glyph.WAND, "$added 张冲洗中")
            showTab(1)
        } else if (refused > 0) Hud.show(this, Glyph.CLOSE, "格式不支持", Ui.RED)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    companion object {
        private const val REQUEST_PICK = 11
        private const val REQUEST_DELETE = 12
        /** A page to show when the main screen comes back (the immersive view swiped down to the recipe). */
        @Volatile var pendingTab = -1
    }
}

