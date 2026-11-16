package org.photoall.tether.ui

import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.photoall.tether.camera.CameraHub
import org.photoall.tether.camera.CameraItem
import org.photoall.tether.develop.DevelopQueue
import org.photoall.tether.develop.Library
import org.photoall.tether.develop.Prefs
import org.photoall.tether.develop.Roll
import org.photoall.tether.engine.Recipe
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The camera: the loaded roll's cartridge turning beside the body's name, its frame counter,
 * the exposure as glyphs, the cards as capacity bars, and the pictures by day. Long-press and
 * slide to select; a floating toolbar acts on the selection.
 */
class CameraPage(private val activity: MainActivity) : LinearLayout(activity), Arrangeable {
    private val ctx = activity

    // ---- the head: cartridge, name, state, counter --------------------------------------------
    private val cartridge = CartridgeView(ctx, Roll.film(ctx).takeIf { it >= 0 } ?: Recipe.load(ctx).film, 1f).apply {
        onTap = { activity.showTab(2) }
    }
    private val title = ctx.label("", Ui.TITLE, bold = true).single()
    private val dot = PulseDot(ctx)
    private val state = ctx.label("", Ui.FOOTNOTE, Ui.SUB, bold = true).single()
    private val batteryIcon = BatteryView(ctx)
    private val batteryText = ctx.label("", Ui.FOOTNOTE, Ui.SUB, bold = true)
    private val counter = RollingCounter(ctx, 30f, Ui.TEXT)
    private val counterTotal = ctx.label("/${Roll.FRAMES}", Ui.SUBHEAD, Ui.SUB, bold = true).apply { typeface = Ui.mono }
    private val rollIcon = IconView(ctx, Glyph.ROLL, Ui.ACCENT, 18)
    private val exposure = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    // the status card: cartridge and name, then the exposure in equal cells, then the cards
    private val status = LinearLayout(ctx).apply {
        orientation = VERTICAL
        background = rounded(Ui.SURFACE, dp(18).toFloat())
        setPadding(dp(12), dp(10), dp(14), dp(12))
    }
    /** The cartridge and the name: in landscape it takes the card's spare height, the cartridge grown into it. */
    private val statusTop = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val statusLine = View(ctx).apply { setBackgroundColor(Ui.LINE) }
    private val storageBox = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val apertureIcon = IconView(ctx, Glyph.APERTURE, Ui.ACCENT, 16)

    // ---- actions ----------------------------------------------------------------------------------
    private val connectButton = ctx.iconButton(Glyph.CABLE, Ui.ACCENT, 42, description = "连接相机") { onConnectButton() }
    private val autoButton = ctx.iconButton(Glyph.BOLT, Ui.ACCENT, 42, description = "联机自动冲洗") { toggleAuto() }
    // the immersive view, the main way to watch a shoot: filled in the accent, with its name
    private val immersiveButton: View = LinearLayout(ctx).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        background = pressable(ctx, Ui.ACCENT, dp(14).toFloat())
        addView(IconView(ctx, Glyph.EXPAND, Ui.ON_ACCENT, 22), LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(7) })
        addView(ctx.label("沉浸模式", Ui.SUBHEAD, Ui.ON_ACCENT, bold = true).single())
        contentDescription = "沉浸模式"
        setOnClickListener { Haptic.select(this); ImmersiveActivity.open(ctx) }
        pressable(0.94f)
    }
    private val refreshButton: IconView = ctx.iconButton(Glyph.REFRESH, Ui.ACCENT, 42, description = "刷新") {
        CameraHub.refresh()
        refreshButton.animate().rotationBy(360f).setDuration(700).setInterpolator(Ease.spring).start()
    }
    private val infoButton = ctx.iconButton(Glyph.INFO, Ui.ACCENT, 42, description = "相机信息") { CameraInfoActivity.open(ctx) }
    private val errorBanner = LinearLayout(ctx).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = pressable(ctx, 0x33FF453A, dp(12).toFloat())
        setPadding(dp(12), dp(10), dp(12), dp(10))
        addView(IconView(ctx, Glyph.INFO, Ui.RED, 18), LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
        addView(ctx.label("", Ui.FOOTNOTE, Ui.RED, bold = true).single(), LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(IconView(ctx, Glyph.CHEVRON_RIGHT, Ui.RED, 14))
        setOnClickListener { ctx.showErrorLog() }
        visibility = View.GONE
    }

    // ---- cards, filters, grid ----------------------------------------------------------------------
    private val filter = Segmented(ctx, listOf("全部", "RAW", "JPG", "未导入", "受保护"), 0,
        listOf(null, null, null, Glyph.IMPORT, Glyph.LOCK)) { refreshGrid() }
    private val listingBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x00000000)
    }
    private val list = DragSelectList(ctx)
    private val empty = LinearLayout(ctx)
    private val emptyButtons = LinearLayout(ctx).apply { gravity = Gravity.CENTER }
    private val toolbar = LinearLayout(ctx)
    private val selectionCount = ctx.label("", Ui.SUBHEAD, Ui.TEXT, bold = true)
    private val protectTool = ctx.iconButton(Glyph.LOCK, Ui.ACCENT, 44, filled = false, description = "保护") { protectSelected() }
    private val selection = DragSelection<CameraItem> { it.key }
    private val selected get() = selection.keys
    private var shown: List<CameraItem> = emptyList()
    private var imported: Set<String> = emptySet()
    private val dayFormat = SimpleDateFormat("M月d日 EEEE", Locale.CHINA)
    private val head = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val headScroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; isFillViewport = true }
    private val actions = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val body = FrameLayout(ctx)
    private val tools = LinearLayout(ctx).apply { orientation = VERTICAL }
    private var arrangedWide: Boolean? = null

    private val grid = SectionGrid<CameraItem>(ctx, 4,
        bind = { cell, item -> bindCell(cell, item) },
        onClick = { item, _ -> if (selected.isNotEmpty()) { selection.toggle(item); Haptic.tick(this); refreshSelection() } else open(item) },
        onLongClick = { _, index -> startSlide(index) },
        headerAction = { _, items -> selectDay(items) },
        headerActionText = { if (selected.isNotEmpty()) "选择" else null },
    )

    private val hubListener: () -> Unit = { refresh() }
    private val libraryListener: () -> Unit = { imported = Library.importedKeys(); grid.notifyDataSetChanged() }
    private val rollListener: (Roll.Event) -> Unit = { onRoll(it) }

    init {
        buildHead()
        buildEmpty()
        buildToolbar()
        list.adapter = grid
        list.setPadding(0, 0, 0, dp(96))
        list.onDragTo = { index ->
            selection.dragTo(grid.flat, index)
            Haptic.tick(this)
            refreshSelection()
        }
        list.onWidth = { w -> grid.setColumns((w / dp(92)).coerceIn(4, 10)) }
        body.addView(list, frameMatch())
        body.addView(empty, frameMatch())
        body.addView(toolbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(14)
        })
        tools.addView(filter, matchWrap())
        tools.addView(listingBar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply { topMargin = dp(6) })
        counter.set(Roll.frames(ctx).toString(), false)
        arrange(activity.wide)
        refresh()
    }

    private fun buildHead() {
        val names = LinearLayout(ctx).apply { orientation = VERTICAL }
        names.addView(title)
        names.addView(ctx.row(dot, state, spacer(ctx), batteryIcon, batteryText, gap = 4).apply { setPadding(0, dp(4), 0, 0) }, matchWrap())
        val count = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(8), 0, 0)
            addView(rollIcon, LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(6); bottomMargin = dp(5) })
            addView(counter)
            addView(counterTotal, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(2); bottomMargin = dp(4)
            })
        }
        names.addView(count)
        val top = statusTop
        top.addView(cartridge, LayoutParams(dp(120), dp(143)))
        top.addView(names, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        status.addView(top, matchWrap())
        status.addView(statusLine, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(8); marginStart = dp(4) })
        status.addView(exposure, matchWrap().apply { topMargin = dp(10) })
        status.addView(storageBox, matchWrap())
        head.addView(status, matchWrap())
        // one capsule as wide as the card, the actions spread evenly across it
        actions.background = rounded(Ui.SURFACE, dp(18).toFloat())
        actions.setPadding(dp(4), 0, dp(4), 0)
        listOf(connectButton, autoButton, immersiveButton, refreshButton, infoButton).forEach { b ->
            if (b === immersiveButton) {
                actions.addView(b, LayoutParams(0, dp(42), 2.2f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            } else {
                b.background = pressable(ctx, android.graphics.Color.TRANSPARENT, dp(14).toFloat())
                actions.addView(b, LayoutParams(0, dp(50), 1f))
            }
        }
        head.addView(actions, matchWrap().apply { topMargin = dp(10) })
        head.addView(errorBanner, matchWrap().apply { topMargin = dp(10) })
    }

    private fun buildEmpty() {
        empty.orientation = VERTICAL
        empty.gravity = Gravity.CENTER
        empty.setPadding(dp(28), 0, dp(28), dp(60))
        val art = FrameLayout(ctx).apply {
            background = rounded(Ui.SURFACE, dp(48).toFloat())
            addView(IconView(ctx, Glyph.CAMERA, Ui.ACCENT, 46), FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER))
            addView(IconView(ctx, Glyph.CABLE, Ui.SUB, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = -dp(2)
            })
        }
        empty.addView(art, LayoutParams(dp(96), dp(96)))
        empty.addView(ctx.label("连接相机", Ui.TITLE, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0) })
        empty.addView(ctx.label("USB-C 数据线 · 打开相机", Ui.SUBHEAD, Ui.SUB).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(22)) })
        emptyButtons.addView(ctx.button("连接", primary = true, glyph = Glyph.CABLE) { CameraHub.connect() })
        emptyButtons.addView(ctx.button("手机里的 RAW", glyph = Glyph.FOLDER_PLUS) { activity.pickLocal() })
        empty.addView(emptyButtons)
        // a slow breath on the camera, to say it is waiting
        art.animate().scaleX(1.04f).scaleY(1.04f).setDuration(1400).setInterpolator(Ease.inOut).withEndAction {
            art.animate().scaleX(1f).scaleY(1f).setDuration(1400).setInterpolator(Ease.inOut).start()
        }.start()
    }

    // The selection's floating toolbar (Photos style): the count, then its actions as glyphs.
    private fun buildToolbar() {
        toolbar.orientation = HORIZONTAL
        toolbar.gravity = Gravity.CENTER_VERTICAL
        toolbar.background = rounded(Ui.BAR, dp(28).toFloat(), 1, Ui.LINE)
        toolbar.elevation = dp(10).toFloat()
        toolbar.setPadding(dp(16), 0, dp(8), 0)
        toolbar.addView(selectionCount, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        listOf(
            ctx.iconButton(Glyph.SELECT_ALL, Ui.ACCENT, 44, filled = false, description = "全选") {
                shown.forEach { selected += it.key }; refreshSelection()
            },
            ctx.iconButton(Glyph.IMPORT, Ui.ACCENT, 44, filled = false, description = "导入原片") { importSelected(false) },
            ctx.iconButton(Glyph.WAND, Ui.ACCENT, 44, filled = false, description = "冲洗") { importSelected(true) },
            protectTool,
            ctx.iconButton(Glyph.TRASH, Ui.RED, 44, filled = false, description = "删除") { deleteSelected() },
            ctx.iconButton(Glyph.CLOSE, Ui.SUB, 44, filled = false, description = "取消选择") { selection.clear(); refreshSelection() },
        ).forEach { toolbar.addView(it, LayoutParams(dp(44), dp(44))) }
        toolbar.visibility = View.GONE
    }

    /**
     * Portrait: the head over the cards, the filter and the grid. Landscape: the head and the
     * cards in a column on the left, the filter and a wide grid on the right at full height.
     */
    override fun arrange(wide: Boolean) {
        if (arrangedWide == wide) return
        arrangedWide = wide
        // the empty state's two buttons: stacked when tall, side by side when the screen is low
        emptyButtons.orientation = if (wide) HORIZONTAL else VERTICAL
        for (i in 0 until emptyButtons.childCount) emptyButtons.getChildAt(i).layoutParams =
            LayoutParams(if (wide) ViewGroup.LayoutParams.WRAP_CONTENT else dp(200), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) { if (wide) marginStart = dp(10) else topMargin = dp(10) }
            }
        empty.setPadding(dp(28), 0, dp(28), if (wide) dp(8) else dp(60))
        empty.getChildAt(2)?.setPadding(0, dp(8), 0, if (wide) dp(14) else dp(22))
        listOf(head, tools, body).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        removeAllViews()
        if (!wide) {
            orientation = VERTICAL
            setPadding(dp(16), dp(6), dp(16), 0)
            headScroll.removeAllViews()
            head.setPadding(0, 0, 0, 0)
            status.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            statusTop.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            cartridge.layoutParams = LinearLayout.LayoutParams(dp(120), dp(143))
            addView(head, matchWrap())
            addView(tools, matchWrap().apply { topMargin = dp(12) })
            addView(body, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            orientation = HORIZONTAL
            setPadding(dp(14), dp(8), dp(14), 0)
            // the left column takes a share of the screen (within bounds), so it fits any phone
            val screen = resources.displayMetrics.widthPixels / resources.displayMetrics.density
            val column = (screen * 0.36f).toInt().coerceIn(280, 400)
            headScroll.removeAllViews()
            headScroll.addView(head, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // the column fills the height (as the right side does): the status card takes all the
            // room the actions leave, its cartridge growing into it; the actions stand at the foot
            head.setPadding(0, 0, 0, dp(14))
            status.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            statusTop.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            cartridge.layoutParams = LinearLayout.LayoutParams(dp(160), ViewGroup.LayoutParams.MATCH_PARENT)
            addView(headScroll, LayoutParams(dp(column), ViewGroup.LayoutParams.MATCH_PARENT))
            val right = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(dp(14), 0, 0, 0) }
            right.addView(tools, matchWrap())
            right.addView(body, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(right, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        CameraHub.addListener(hubListener)
        Library.addListener(libraryListener)
        Roll.addListener(rollListener)
        counter.set(Roll.frames(ctx).toString(), false)
        Roll.film(ctx).takeIf { it >= 0 }?.let { cartridge.setFilm(it, false) }
        refresh()
    }

    override fun onDetachedFromWindow() {
        CameraHub.removeListener(hubListener)
        Library.removeListener(libraryListener)
        Roll.removeListener(rollListener)
        super.onDetachedFromWindow()
    }

    // The roll: the counter rolls on, the cartridge's leader steps out, the aperture blinks;
    // at the end it rewinds.
    private fun onRoll(e: Roll.Event) {
        when (e) {
            is Roll.Event.Advanced -> {
                counter.set(e.frame.toString())
                cartridge.advance()
                apertureIcon.shutterBlink()
                rollIcon.nudge()
            }
            is Roll.Event.Finished -> {
                cartridge.rewind()
                counter.color = Ui.ACCENT
            }
            is Roll.Event.Loaded -> {
                cartridge.setFilm(e.film, true)
                counter.color = Ui.TEXT
                counter.set(Roll.frames(ctx).toString())
            }
        }
    }

    // ---- state -----------------------------------------------------------------------------------

    private fun refresh() {
        val st = CameraHub.state
        val device = CameraHub.device
        val connected = st == CameraHub.State.CONNECTED
        title.text = device?.model?.replace('_', ' ')?.let { m ->
            val maker = device.manufacturer.substringBefore(' ').lowercase().replaceFirstChar { it.uppercase() }
            if (m.startsWith(maker, true)) m else "$maker $m"
        } ?: "未连接"
        dot.color = when (st) {
            CameraHub.State.CONNECTED -> Ui.GREEN
            CameraHub.State.CONNECTING, CameraHub.State.WAITING_PERMISSION -> Ui.ACCENT
            CameraHub.State.ERROR -> Ui.RED
            else -> Ui.DIM
        }
        dot.live = connected || st == CameraHub.State.CONNECTING
        state.text = when (st) {
            CameraHub.State.CONNECTED -> if (CameraHub.listing) "读取中" else "已连接"
            CameraHub.State.CONNECTING -> "连接中"
            CameraHub.State.WAITING_PERMISSION -> "等待授权"
            CameraHub.State.ERROR -> "连接失败"
            else -> "离线"
        }
        val s = CameraHub.settings
        batteryIcon.level = s.battery
        batteryIcon.visibility = if (s.battery != null && connected) View.VISIBLE else View.GONE
        batteryText.text = s.battery?.let { "$it%" } ?: ""
        batteryText.visibility = batteryIcon.visibility

        // exposure: equal cells across the card, a glyph over its figure
        exposure.removeAllViews()
        if (connected) {
            fun cell(glyph: Glyph, text: String, icon: IconView? = null) {
                val c = LinearLayout(ctx).apply {
                    orientation = VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    val g = icon ?: IconView(ctx, glyph, Ui.ACCENT, 16)
                    (g.parent as? ViewGroup)?.removeView(g)
                    addView(g, LayoutParams(dp(16), dp(16)))
                    addView(ctx.label(text, Ui.SUBHEAD, Ui.TEXT, bold = true).single().apply { typeface = Ui.mono; gravity = Gravity.CENTER },
                        LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
                }
                exposure.addView(c, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            s.program?.let { cell(Glyph.DIAL, it) }
            s.shutter?.let { cell(Glyph.TIMER, it) }
            s.aperture?.let { cell(Glyph.APERTURE, "f/%.1f".format(it).replace(".0", ""), apertureIcon) }
            s.iso?.let { cell(Glyph.SUN, it.toString()) }
            s.bias?.let { cell(Glyph.EV, if (it == 0f) "±0" else "%+.1f".format(it)) }
        }
        exposure.visibility = if (exposure.childCount > 0) View.VISIBLE else View.GONE

        storageBox.removeAllViews()
        if (connected) CameraHub.storages.sortedBy { it.id }.forEachIndexed { i, card ->
            storageBox.addView(StorageRow(ctx, i + 1, card.maxCapacity, card.freeBytes, card.readOnly), matchWrap().apply { topMargin = dp(12) })
        }
        storageBox.visibility = if (storageBox.childCount > 0) View.VISIBLE else View.GONE
        statusLine.visibility = if (connected && (exposure.childCount > 0 || storageBox.childCount > 0)) View.VISIBLE else View.GONE

        connectButton.glyph = if (connected) Glyph.POWER else Glyph.CABLE
        connectButton.color = if (connected) Ui.SUB else Ui.ACCENT
        val auto = Prefs.tether(ctx)
        autoButton.glyph = if (auto) Glyph.BOLT_FILL else Glyph.BOLT
        autoButton.color = if (auto) Ui.ACCENT else Ui.SUB
        listOf(immersiveButton, refreshButton, infoButton).forEach { it.visibility = if (connected) View.VISIBLE else View.GONE }
        (errorBanner.getChildAt(1) as TextView).text = CameraHub.message
        errorBanner.visibility = if (st == CameraHub.State.ERROR) View.VISIBLE else View.GONE

        listingBar.visibility = if (CameraHub.listing) View.VISIBLE else View.INVISIBLE
        if (CameraHub.listing && CameraHub.listTotal > 0) listingBar.progress = CameraHub.listDone * 1000 / CameraHub.listTotal
        tools.visibility = if (connected) View.VISIBLE else View.GONE
        empty.visibility = if (connected) View.GONE else View.VISIBLE
        list.visibility = if (connected) View.VISIBLE else View.GONE
        imported = Library.importedKeys()
        refreshGrid()
    }

    private fun toggleAuto() {
        val on = !Prefs.tether(ctx)
        Prefs.setTether(ctx, on)
        autoButton.pop(0.7f)
        Hud.show(activity, if (on) Glyph.BOLT_FILL else Glyph.BOLT, if (on) "自动冲洗 开" else "自动冲洗 关", if (on) Ui.ACCENT else Ui.SUB)
        refresh()
    }

    private fun onConnectButton() {
        if (CameraHub.state == CameraHub.State.CONNECTED) {
            AlertDialog.Builder(ctx).setMessage("断开相机？")
                .setPositiveButton("断开") { _, _ -> CameraHub.disconnect() }
                .setNegativeButton("取消", null).show()
        } else CameraHub.connect()
    }

    private fun filtered(): List<CameraItem> {
        val all = CameraHub.items
        return when (filter.selected) {
            1 -> all.filter { it.raw != null }
            2 -> all.filter { it.jpeg != null }
            3 -> all.filter { it.key !in imported && !it.isVideo }
            4 -> all.filter { it.isProtected }
            else -> all
        }
    }

    private fun refreshGrid() {
        shown = filtered()
        selected.retainAll(CameraHub.items.map { it.key }.toSet())
        val sections = shown.groupBy { day(it.time) }.map { (dayText, items) -> "$dayText · ${items.size}" to items }
        grid.setSections(sections)
        refreshSelection()
    }

    private fun day(time: Long) = if (time > 0) dayFormat.format(Date(time)) else "日期未知"

    private fun bindCell(cell: ThumbCell, item: CameraItem) {
        cell.key = item.key
        cell.badge.text = item.badge
        cell.marks(item.key in imported, item.isProtected)
        cell.setSelected(selected.isNotEmpty(), item.key in selected)
        val info = item.jpeg ?: item.primary
        val cached = CameraHub.cachedThumb(info)
        cell.show(cached)
        if (cached == null) CameraHub.loadThumb(info) { bmp -> if (cell.key == item.key) cell.show(bmp, fade = true) }
    }

    // ---- selection -------------------------------------------------------------------------------

    // Long press: select it, and the finger sliding on selects (or clears) everything it passes.
    private fun startSlide(index: Int) {
        selection.begin(grid.flat, index)
        list.startDrag(index)
        Haptic.confirm(this)
        refreshSelection()
    }

    private fun selectDay(items: List<CameraItem>) {
        val keys = items.map { it.key }
        if (selected.containsAll(keys)) selected.removeAll(keys.toSet()) else selected.addAll(keys)
        Haptic.tick(this)
        refreshSelection()
    }

    private fun refreshSelection() {
        val selecting = selected.isNotEmpty()
        val was = toolbar.visibility == View.VISIBLE
        selectionCount.text = selected.size.toString()
        protectTool.glyph = if (selectedItems().any { !it.isProtected }) Glyph.LOCK else Glyph.UNLOCK
        if (selecting && !was) {
            toolbar.visibility = View.VISIBLE
            toolbar.translationY = dp(90).toFloat()
            toolbar.animate().translationY(0f).setDuration(460).setInterpolator(Ease.spring).start()
        } else if (!selecting && was) {
            toolbar.animate().translationY(dp(90).toFloat()).setDuration(240).setInterpolator(Ease.out)
                .withEndAction { toolbar.visibility = View.GONE }.start()
        }
        grid.notifyDataSetChanged()
    }

    private fun selectedItems(): List<CameraItem> = CameraHub.items.filter { it.key in selected }

    private fun importSelected(develop: Boolean) {
        val items = selectedItems().filter { it.canDevelop || !develop }
        CameraHub.note("${if (develop) "冲洗" else "导入"}：选中 ${selected.size} 张，可处理 ${items.size} 张")
        if (items.isEmpty()) { Haptic.reject(this); Hud.show(activity, Glyph.CLOSE, "无可冲洗文件", Ui.RED); return }
        items.forEach { DevelopQueue.addCamera(ctx, it, develop) }
        Hud.show(activity, if (develop) Glyph.WAND else Glyph.IMPORT, "${items.size} 张")
        selection.clear()
        refreshSelection()
        activity.showTab(1)
    }

    private fun protectSelected() {
        val items = selectedItems()
        val on = items.any { !it.isProtected }
        CameraHub.run {
            val errors = CameraHub.protect(items, on)
            post { report(if (on) Glyph.LOCK else Glyph.UNLOCK, items.size, errors) }
        }
        selection.clear()
        refreshSelection()
    }

    private fun deleteSelected() {
        val items = selectedItems()
        val files = items.sumOf { it.files.size }
        AlertDialog.Builder(ctx)
            .setTitle("从相机删除 ${items.size} 张？")
            .setMessage("$files 个文件，无法撤销。受保护的不会删除。")
            .setPositiveButton("删除") { _, _ ->
                CameraHub.run {
                    val errors = CameraHub.delete(items)
                    post { report(Glyph.TRASH, items.size, errors) }
                }
                selection.clear()
                refreshSelection()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun report(glyph: Glyph, count: Int, errors: List<String>) {
        if (errors.isEmpty()) {
            Haptic.confirm(this)
            Hud.show(activity, glyph, "$count 张")
        } else {
            Haptic.reject(this)
            AlertDialog.Builder(ctx).setTitle("${errors.size} 个文件没有完成")
                .setMessage(errors.take(12).joinToString("\n")).setPositiveButton("好", null).show()
        }
    }

    private fun open(item: CameraItem) {
        CameraPage.visibleKeys = shown.map { it.key }
        CameraPhotoActivity.open(ctx, item.key)
    }

    companion object {
        /** The shots the page lists, in order (the viewer pages through these). */
        @Volatile var visibleKeys: List<String> = emptyList()
    }
}

/** A battery glyph that shows its charge (red at a fifth or less). */
class BatteryView(context: android.content.Context) : View(context) {
    var level: Int? = null
        set(v) { field = v; invalidate() }
    /** Drawn white-on-dark whatever the theme (the immersive view). */
    var onDark = false
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val r = android.graphics.RectF()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(dp(26), dp(14))

    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = width - dp(3).toFloat()
        val h = height.toFloat()
        val lv = (level ?: 0).coerceIn(0, 100)
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = dp(1).toFloat() * 1.2f
        paint.color = if (onDark) 0xFF8E8E93.toInt() else Ui.SUB
        r.set(1f, 1f, w, h - 1f)
        canvas.drawRoundRect(r, dp(4).toFloat(), dp(4).toFloat(), paint)
        paint.style = android.graphics.Paint.Style.FILL
        r.set(w + dp(1), h * 0.32f, w + dp(3), h * 0.68f)
        canvas.drawRoundRect(r, dp(1).toFloat(), dp(1).toFloat(), paint)
        paint.color = if (lv <= 20) Ui.RED else if (onDark) 0xFFFFFFFF.toInt() else Ui.TEXT
        val inset = dp(2) + 1f
        r.set(inset, inset, inset + (w - 2 * inset) * lv / 100f, h - inset)
        canvas.drawRoundRect(r, dp(2).toFloat(), dp(2).toFloat(), paint)
    }
}

/** A card in its slot: a glyph and the slot's number, a capacity bar, the free space. */
class StorageRow(context: android.content.Context, slot: Int, capacity: Long, free: Long, readOnly: Boolean) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(IconView(context, Glyph.SDCARD, Ui.ACCENT, 16), LayoutParams(dp(16), dp(16)).apply { marginStart = dp(4); marginEnd = dp(6) })
        addView(context.label(slot.toString(), Ui.FOOTNOTE, Ui.TEXT, bold = true).apply { typeface = Ui.mono })
        if (readOnly) addView(IconView(context, Glyph.LOCK, Ui.SUB, 13), LayoutParams(dp(13), dp(13)).apply { marginStart = dp(4) })
        val used = if (capacity > 0) ((capacity - free) * 1000 / capacity).toInt() else 0
        addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = used
            progressTintList = android.content.res.ColorStateList.valueOf(if (used > 900) Ui.RED else Ui.ACCENT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ui.SURFACE3)
        }, LayoutParams(0, dp(4), 1f).apply { marginStart = dp(10); marginEnd = dp(10) })
        addView(context.label(formatBytes(free), Ui.FOOTNOTE, Ui.SUB, bold = true).single().apply { typeface = Ui.mono })
    }
}
