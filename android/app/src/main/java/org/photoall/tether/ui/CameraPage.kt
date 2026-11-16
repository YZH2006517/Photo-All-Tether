package org.photoall.tether.ui

import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.photoall.tether.camera.CameraHub
import org.photoall.tether.camera.CameraItem
import org.photoall.tether.develop.DevelopQueue
import org.photoall.tether.develop.Library
import org.photoall.tether.develop.Prefs
import org.photoall.tether.engine.Recipe
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The camera's cards: what is on them by day, with the camera's state on top. Tap a shot to
 * look at it; long-press to select several and import, develop, protect or delete them.
 */
class CameraPage(private val activity: MainActivity) : LinearLayout(activity), Arrangeable {
    private val ctx = activity
    private val title = ctx.label("", 20f, bold = true).single()
    private val battery = ctx.label("", 13f, Ui.SUB)
    private val status = ctx.label("", 13f, Ui.SUB)
    private val exposure = ctx.label("", 13f, Ui.ACCENT).apply { typeface = Ui.mono }
    private val errorButton = ctx.label("查看错误日志 ›", 13f, Ui.RED, bold = true).apply {
        setPadding(0, dp(8), 0, dp(2))
        setOnClickListener { ctx.showErrorLog() }
    }
    private val connectButton = ctx.button("连接相机") { onConnectButton() }
    private val tetherChip = Chip(ctx, "联机自动冲洗")
    private val storageRow = LinearLayout(ctx).apply { orientation = HORIZONTAL }
    private val filterNames = listOf("全部", "RAW", "JPEG", "未导入", "受保护")
    private val filter = Segmented(ctx, filterNames, 0) { refreshGrid() }
    private val selectionBar = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val selectionCount = ctx.label("", 14f, bold = true)
    private val listingText = ctx.label("", 12f, Ui.SUB)
    private val list = DragSelectList(ctx)
    private lateinit var tools: FrameLayout
    private val empty = LinearLayout(ctx)
    private val selection = DragSelection<CameraItem> { it.key }
    private val selected get() = selection.keys
    private var shown: List<CameraItem> = emptyList()
    private var imported: Set<String> = emptySet()
    private val dayFormat = SimpleDateFormat("yyyy 年 M 月 d 日 EEEE", Locale.CHINA)

    private val grid = SectionGrid<CameraItem>(ctx, 4,
        bind = { cell, item -> bindCell(cell, item) },
        onClick = { item, _ -> if (selected.isNotEmpty()) toggle(item) else open(item) },
        onLongClick = { _, index -> startSlide(index) },
        headerAction = { _, items -> selectDay(items) },
        headerActionText = { if (selected.isNotEmpty()) "选这天" else null },
    )

    private val hubListener: () -> Unit = { refresh() }
    private val libraryListener: () -> Unit = { imported = Library.importedKeys(); grid.notifyDataSetChanged() }
    private val queueListener: () -> Unit = { }

    private val head = ctx.card()
    private val buttons = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val refreshButton = ctx.button("刷新") { CameraHub.refresh() }
    private val immersiveButton = ctx.button("沉浸") { ImmersiveActivity.open(ctx) }
    private val storageScroll = ctx.hScroll(LinearLayout(ctx))
    private val hint = ctx.label("", 12f, Ui.SUB).apply { setLineSpacing(0f, 1.3f) }
    private val body = FrameLayout(ctx)
    private var arrangedWide: Boolean? = null

    init {
        // the camera card
        head.addView(ctx.row(title, battery).apply {
            (title.layoutParams as LayoutParams).apply { width = 0; weight = 1f }
        }, matchWrap())
        head.addView(status.apply { setPadding(0, dp(5), 0, 0) })
        head.addView(exposure.apply { setPadding(0, dp(6), 0, 0) })
        head.addView(hint.apply { setPadding(0, dp(6), 0, 0) })
        head.addView(errorButton)
        head.addView(buttons, matchWrap())
        tetherChip.on = Prefs.tether(ctx)
        tetherChip.onClick = {
            val on = !tetherChip.on
            Prefs.setTether(ctx, on)
            tetherChip.on = on
            Toast.makeText(ctx, if (on) "新拍的照片会自动传到手机并冲洗" else "新拍的照片只显示在列表里", Toast.LENGTH_SHORT).show()
        }
        storageScroll.removeAllViews()

        // filters, or the selection's actions
        tools = FrameLayout(ctx)
        tools.addView(filter, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        buildSelectionBar()
        tools.addView(selectionBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        listingText.setPadding(dp(4), dp(6), 0, 0)

        list.adapter = grid
        list.setPadding(0, 0, 0, dp(16))
        list.onDragTo = { index ->
            selection.dragTo(grid.flat, index)
            refreshSelection()
        }
        // more columns when there is room (landscape, tablets)
        list.onWidth = { w -> grid.setColumns((w / dp(92)).coerceIn(4, 10)) }
        body.addView(list, frameMatch())
        buildEmpty()
        body.addView(empty, frameMatch())
        arrange(activity.wide)
        refresh()
    }

    /**
     * Portrait: the camera card, the cards in the camera, the filters and the grid, one under the
     * other. Landscape: the camera card and its cards in a column on the left (with the listing
     * progress), the filters and a wide grid on the right, at full height.
     */
    override fun arrange(wide: Boolean) {
        if (arrangedWide == wide) return
        arrangedWide = wide
        listOf(head, storageScroll, storageRow, tools, listingText, body).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        removeAllViews()
        buttons.removeAllViews()
        (connectButton.parent as? ViewGroup)?.removeView(connectButton)
        (tetherChip.parent as? ViewGroup)?.removeView(tetherChip)
        (immersiveButton.parent as? ViewGroup)?.removeView(immersiveButton)
        (refreshButton.parent as? ViewGroup)?.removeView(refreshButton)
        if (!wide) {
            orientation = VERTICAL
            setPadding(dp(14), dp(8), dp(14), 0)
            buttons.addView(ctx.row(connectButton, tetherChip, spacer(ctx), immersiveButton, refreshButton, gap = 6)
                .apply { setPadding(0, dp(10), 0, dp(2)) }, matchWrap())
            addView(head, matchWrap())
            storageRow.orientation = HORIZONTAL
            storageScroll.addView(storageRow)
            addView(storageScroll.apply { setPadding(0, dp(10), 0, 0) }, matchWrap())
            addView(tools, matchWrap().apply { topMargin = dp(10) })
            addView(listingText)
            addView(body, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            orientation = HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), 0)
            buttons.addView(ctx.row(connectButton, tetherChip, gap = 6).apply { setPadding(0, dp(10), 0, 0) }, matchWrap())
            buttons.addView(ctx.row(immersiveButton, refreshButton, gap = 6).apply { setPadding(0, dp(8), 0, dp(2)) }, matchWrap())
            val left = LinearLayout(ctx).apply { orientation = VERTICAL }
            left.addView(head, matchWrap())
            storageRow.orientation = VERTICAL
            left.addView(storageRow, matchWrap().apply { topMargin = dp(10) })
            left.addView(listingText)
            val leftScroll = android.widget.ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(left) }
            addView(leftScroll, LayoutParams(dp(330), ViewGroup.LayoutParams.MATCH_PARENT))
            val right = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(dp(12), 0, 0, 0) }
            right.addView(tools, matchWrap())
            right.addView(body, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
            addView(right, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        if (CameraHub.state == CameraHub.State.CONNECTED) refresh()
    }

    // Long press: select it, and the finger sliding on selects (or clears) everything it passes.
    private fun startSlide(index: Int) {
        selection.begin(grid.flat, index)
        list.startDrag(index)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        refreshSelection()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        CameraHub.addListener(hubListener)
        Library.addListener(libraryListener)
        DevelopQueue.addListener(queueListener)
        tetherChip.on = Prefs.tether(ctx)
        refresh()
    }

    override fun onDetachedFromWindow() {
        CameraHub.removeListener(hubListener)
        Library.removeListener(libraryListener)
        DevelopQueue.removeListener(queueListener)
        super.onDetachedFromWindow()
    }

    private fun buildEmpty() {
        empty.orientation = VERTICAL
        empty.gravity = Gravity.CENTER
        empty.setPadding(dp(28), 0, dp(28), dp(40))
        empty.addView(ctx.label("连接相机", 20f, bold = true).apply { gravity = Gravity.CENTER })
        empty.addView(ctx.label(
            "1. 用 USB-C 数据线连接相机和手机，打开相机\n" +
                "2. 手机弹出提示时选「Photo All 联机」并允许\n\n" +
                "连接后这里列出存储卡里的照片；按下快门，RAW 自动传到手机并冲洗。",
            14f, Ui.SUB).apply { setLineSpacing(0f, 1.35f); setPadding(0, dp(14), 0, dp(20)) })
        empty.addView(ctx.button("打开手机里的 RAW 冲洗") { activity.pickLocal() })
    }

    private fun buildSelectionBar() {
        selectionBar.background = rounded(Ui.SURFACE2, dp(18).toFloat(), 1, Ui.ACCENT)
        selectionBar.setPadding(dp(12), dp(4), dp(4), dp(4))
        fun action(text: String, danger: Boolean = false, block: () -> Unit) = ctx.label(text, 14f, if (danger) Ui.RED else Ui.ACCENT).apply {
            setPadding(dp(9), dp(8), dp(9), dp(8))
            setOnClickListener { block() }
        }
        selectionBar.addView(selectionCount, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        selectionBar.addView(action("全选") { selected.addAll(shown.map { it.key }); refreshSelection() })
        selectionBar.addView(action("导入") { importSelected(develop = false) })
        selectionBar.addView(action("冲洗") { importSelected(develop = true) })
        selectionBar.addView(action("保护") { protectSelected() })
        selectionBar.addView(action("删除", danger = true) { deleteSelected() })
        selectionBar.addView(action("✕") { selected.clear(); refreshSelection() })
        selectionBar.visibility = View.GONE
    }

    // ---- state -----------------------------------------------------------------------------------

    private fun refresh() {
        val st = CameraHub.state
        val device = CameraHub.device
        val connected = st == CameraHub.State.CONNECTED
        title.text = device?.let { "${it.manufacturer.substringBefore(' ').lowercase().replaceFirstChar { c -> c.uppercase() }} ${it.model}" }
            ?: when (st) {
                CameraHub.State.CONNECTING -> "正在连接…"
                CameraHub.State.WAITING_PERMISSION -> "等待授权"
                else -> "未连接相机"
            }
        status.text = CameraHub.message
        status.setTextColor(if (st == CameraHub.State.ERROR) Ui.RED else Ui.SUB)
        errorButton.visibility = if (st == CameraHub.State.ERROR) View.VISIBLE else View.GONE
        val brandHint = if (connected) CameraHub.profile?.hint else null
        hint.text = brandHint
        hint.visibility = if (brandHint.isNullOrEmpty()) View.GONE else View.VISIBLE
        val s = CameraHub.settings
        battery.text = s.battery?.let { "电量 $it%" } ?: ""
        battery.setTextColor(if ((s.battery ?: 100) <= 20) Ui.RED else Ui.SUB)
        val parts = listOfNotNull(s.program, s.aperture?.let { "f/%.1f".format(it).replace(".0", "") }, s.shutter,
            s.iso?.let { "ISO $it" }, s.bias?.takeIf { it != 0f }?.let { "%+.1f EV".format(it) })
        exposure.text = parts.joinToString("  ")
        exposure.visibility = if (connected && parts.isNotEmpty()) View.VISIBLE else View.GONE
        connectButton.text = if (connected) "断开" else "连接相机"

        storageRow.removeAllViews()
        CameraHub.storages.sortedBy { it.id }.forEachIndexed { i, st2 ->
            val text = "卡槽 ${i + 1} · ${st2.description.ifBlank { st2.label }.ifBlank { "存储卡" }} · 剩 ${formatBytes(st2.freeBytes)}" +
                (if (st2.freeImages in 1..999999) " · 约 ${st2.freeImages} 张" else "") + if (st2.readOnly) " · 只读" else ""
            storageRow.addView(ctx.label(text, 12f, Ui.SUB).apply {
                background = rounded(Ui.SURFACE, dp(12).toFloat(), 1, Ui.LINE)
                setPadding(dp(10), dp(6), dp(10), dp(6))
            }, if (storageRow.orientation == VERTICAL) LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
            else LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        storageRow.visibility = if (CameraHub.storages.isEmpty()) View.GONE else View.VISIBLE

        listingText.text = when {
            CameraHub.listing -> "正在读取存储卡… ${CameraHub.listDone} / ${CameraHub.listTotal}"
            connected -> "${CameraHub.items.size} 张 · ${CameraHub.items.sumOf { it.files.size }} 个文件"
            else -> ""
        }
        listingText.visibility = if (connected) View.VISIBLE else View.GONE
        empty.visibility = if (connected) View.GONE else View.VISIBLE
        tools.visibility = if (connected) View.VISIBLE else View.GONE
        list.visibility = if (connected) View.VISIBLE else View.GONE
        imported = Library.importedKeys()
        refreshGrid()
    }

    private fun onConnectButton() {
        if (CameraHub.state == CameraHub.State.CONNECTED) {
            AlertDialog.Builder(ctx).setMessage("断开相机？正在进行的传输会中断。")
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
        visibleKeys = shown.map { it.key }
        selected.retainAll(CameraHub.items.map { it.key }.toSet())
        val sections = shown.groupBy { day(it.time) }.map { (dayText, items) -> "$dayText · ${items.size} 张" to items }
        grid.setSections(sections)
        refreshSelection()
    }

    private fun day(time: Long) = if (time > 0) dayFormat.format(Date(time)) else "日期未知"

    private fun bindCell(cell: ThumbCell, item: CameraItem) {
        cell.key = item.key
        cell.badge.text = item.badge
        val marks = listOfNotNull(
            if (item.key in imported) "已存" else null,
            if (item.isProtected) "保护" else null,
        )
        cell.mark(marks.joinToString(" ").ifEmpty { null }, if (item.isProtected && item.key !in imported) Ui.ACCENT else Ui.GREEN)
        cell.setSelected(selected.isNotEmpty(), item.key in selected)
        val info = item.jpeg ?: item.primary
        val cached = CameraHub.cachedThumb(info)
        cell.show(cached)
        if (cached == null) CameraHub.loadThumb(info) { bmp -> if (cell.key == item.key) cell.show(bmp) }
    }

    // ---- selection -------------------------------------------------------------------------------

    private fun toggle(item: CameraItem) {
        if (!selected.remove(item.key)) selected += item.key
        refreshSelection()
    }

    private fun selectDay(items: List<CameraItem>) {
        val keys = items.map { it.key }
        if (selected.containsAll(keys)) selected.removeAll(keys.toSet()) else selected.addAll(keys)
        refreshSelection()
    }

    private fun refreshSelection() {
        val selecting = selected.isNotEmpty()
        selectionBar.visibility = if (selecting) View.VISIBLE else View.GONE
        filter.visibility = if (selecting) View.INVISIBLE else View.VISIBLE
        selectionCount.text = "已选 ${selected.size}"
        grid.notifyDataSetChanged()
    }

    private fun selectedItems(): List<CameraItem> = CameraHub.items.filter { it.key in selected }

    private fun importSelected(develop: Boolean) {
        val items = selectedItems().filter { it.canDevelop || !develop }
        if (items.isEmpty()) return
        items.forEach { DevelopQueue.addCamera(ctx, it, develop) }
        Toast.makeText(ctx, if (develop) "${items.size} 张加入冲洗（${Recipe.load(ctx).summary()}）" else "${items.size} 张开始导入原片",
            Toast.LENGTH_SHORT).show()
        selected.clear()
        refreshSelection()
        activity.showTab(1)
    }

    private fun protectSelected() {
        val items = selectedItems()
        val on = items.any { !it.isProtected }
        CameraHub.run {
            val errors = CameraHub.protect(items, on)
            post { report(if (on) "已保护" else "已取消保护", items.size, errors) }
        }
        selected.clear()
        refreshSelection()
    }

    private fun deleteSelected() {
        val items = selectedItems()
        val files = items.sumOf { it.files.size }
        val protectedCount = items.count { it.isProtected }
        AlertDialog.Builder(ctx)
            .setTitle("从相机删除 ${items.size} 张？")
            .setMessage("将从存储卡上删除 $files 个文件，无法撤销。" +
                (if (protectedCount > 0) "\n其中 $protectedCount 张受保护，相机不会删除它们。" else ""))
            .setPositiveButton("删除") { _, _ ->
                CameraHub.run {
                    val errors = CameraHub.delete(items)
                    post { report("已删除", items.size, errors) }
                }
                selected.clear()
                refreshSelection()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun report(done: String, count: Int, errors: List<String>) {
        if (errors.isEmpty()) Toast.makeText(ctx, "$done $count 张", Toast.LENGTH_SHORT).show()
        else AlertDialog.Builder(ctx).setTitle("有 ${errors.size} 个文件没有完成")
            .setMessage(errors.take(12).joinToString("\n")).setPositiveButton("好", null).show()
    }

    private fun open(item: CameraItem) {
        CameraPhotoActivity.open(ctx, item.key)
    }

    companion object {
        /** The shots the page lists, in order (the viewer pages through these). */
        @Volatile var visibleKeys: List<String> = emptyList()
    }
}
