package org.photoall.tether.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import org.photoall.tether.camera.CameraHub
import org.photoall.tether.develop.DevelopQueue
import org.photoall.tether.develop.Library
import org.photoall.tether.engine.Recipe
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The phone side: photos on their way (transfer, develop), the prints, and the originals.
 * Long-press and slide to select several; share, develop again or delete them together.
 */
class DarkroomPage(private val activity: MainActivity) : LinearLayout(activity), Arrangeable {
    private val ctx = activity
    private val kinds = Segmented(ctx, listOf("成片", "原片"), 0) { selection.clear(); refreshGrid() }
    private val selectionBar = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val selectionCount = ctx.label("", 14f, bold = true)
    private val queueBox = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val queueTitle = ctx.label("", 13f, Ui.SUB, bold = true)
    private val queueRows = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val list = DragSelectList(ctx)
    private val emptyText = ctx.label("", 14f, Ui.SUB).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.3f) }
    private val dayFormat = SimpleDateFormat("yyyy 年 M 月 d 日", Locale.CHINA)
    private val selection = DragSelection<Library.Entry> { it.uri }
    private var shown: List<Library.Entry> = emptyList()

    private val grid: SectionGrid<Library.Entry> = SectionGrid(ctx, 3,
        bind = { cell, e -> bindCell(cell, e) },
        onClick = { e, _ ->
            if (selection.active) {
                selection.toggle(e)
                refreshSelection()
            } else PhotoActivity.open(ctx, e.id, kind())
        },
        onLongClick = { _, index ->
            selection.begin(grid.flat, index)
            list.startDrag(index)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            refreshSelection()
        },
        headerAction = { _, items ->
            if (items.all { it in selection }) items.forEach { selection.keys -= it.uri } else items.forEach { selection.keys += it.uri }
            refreshSelection()
        },
        headerActionText = { if (selection.active) "选这天" else null },
    )

    private val libraryListener: () -> Unit = { refreshGrid() }
    private val queueListener: () -> Unit = { refreshQueue() }

    private val head = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val tools = FrameLayout(ctx)
    private val body = FrameLayout(ctx)
    private var arrangedWide: Boolean? = null

    init {
        head.addView(ctx.label("暗房", 22f, bold = true), LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(ctx.button("沉浸") { ImmersiveActivity.open(ctx) })
        head.addView(ctx.button("打开手机 RAW") { activity.pickLocal() }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8)
        })

        queueBox.background = rounded(Ui.SURFACE, dp(16).toFloat(), 1, Ui.LINE)
        queueBox.setPadding(dp(12), dp(10), dp(12), dp(8))
        val qHead = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        qHead.addView(queueTitle, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        qHead.addView(ctx.label("清除已完成", 13f, Ui.ACCENT).apply {
            setPadding(dp(8), dp(4), 0, dp(4))
            setOnClickListener { DevelopQueue.clearFinished() }
        })
        queueBox.addView(qHead)
        queueBox.addView(queueRows)
        buildSelectionBar()

        list.adapter = grid
        list.setPadding(0, 0, 0, dp(16))
        list.onDragTo = { index ->
            selection.dragTo(grid.flat, index)
            refreshSelection()
        }
        list.onWidth = { w -> grid.setColumns((w / dp(118)).coerceIn(3, 8)) }
        body.addView(list, frameMatch())
        body.addView(emptyText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(dp(24), 0, dp(24), dp(60))
        })
        arrange(activity.wide)
        refreshQueue()
        refreshGrid()
    }

    /**
     * Portrait: title, queue, prints / originals and the grid, one under the other. Landscape: the
     * title, the prints / originals switch and a longer queue in a column on the left; the
     * selection's actions and a wide grid on the right.
     */
    override fun arrange(wide: Boolean) {
        if (arrangedWide == wide) return
        arrangedWide = wide
        listOf(head, queueBox, kinds, selectionBar, tools, body).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        removeAllViews()
        if (!wide) {
            orientation = VERTICAL
            setPadding(dp(14), dp(8), dp(14), 0)
            addView(head, matchWrap())
            addView(queueBox, matchWrap().apply { topMargin = dp(12) })
            tools.addView(kinds, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            tools.addView(selectionBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(tools, matchWrap().apply { topMargin = dp(12) })
            addView(body, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            orientation = HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), 0)
            val left = LinearLayout(ctx).apply { orientation = VERTICAL }
            left.addView(head, matchWrap())
            left.addView(kinds, matchWrap().apply { topMargin = dp(12) })
            left.addView(queueBox, matchWrap().apply { topMargin = dp(12) })
            addView(android.widget.ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(left) },
                LayoutParams(dp(330), ViewGroup.LayoutParams.MATCH_PARENT))
            val right = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(dp(12), 0, 0, 0) }
            right.addView(selectionBar, matchWrap().apply { bottomMargin = dp(8) })
            right.addView(body, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(right, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        refreshQueue()
        refreshSelection()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Library.addListener(libraryListener)
        DevelopQueue.addListener(queueListener)
        refreshQueue()
        refreshGrid()
    }

    override fun onDetachedFromWindow() {
        Library.removeListener(libraryListener)
        DevelopQueue.removeListener(queueListener)
        super.onDetachedFromWindow()
    }

    private fun kind() = if (kinds.selected == 0) Library.PRINT else Library.ORIGINAL

    // ---- selection -----------------------------------------------------------------------------

    private fun buildSelectionBar() {
        selectionBar.background = rounded(Ui.SURFACE2, dp(18).toFloat(), 1, Ui.ACCENT)
        selectionBar.setPadding(dp(12), dp(4), dp(4), dp(4))
        fun action(text: String, danger: Boolean = false, block: () -> Unit) = ctx.label(text, 14f, if (danger) Ui.RED else Ui.ACCENT).apply {
            setPadding(dp(9), dp(8), dp(9), dp(8))
            setOnClickListener { block() }
        }
        selectionBar.addView(selectionCount, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        selectionBar.addView(action("全选") { shown.forEach { selection.keys += it.uri }; refreshSelection() })
        selectionBar.addView(action("分享") { shareSelected() })
        selectionBar.addView(action("重冲") { redevelopSelected() })
        selectionBar.addView(action("删除", danger = true) { deleteSelected() })
        selectionBar.addView(action("✕") { selection.clear(); refreshSelection() })
        selectionBar.visibility = View.GONE
    }

    private fun refreshSelection() {
        val active = selection.active
        selectionBar.visibility = if (active) View.VISIBLE else View.GONE
        // the switch sits apart from the actions in landscape: it can stay
        kinds.visibility = if (active && arrangedWide != true) View.INVISIBLE else View.VISIBLE
        selectionCount.text = "已选 ${selection.keys.size}"
        grid.notifyDataSetChanged()
    }

    private fun selectedEntries() = shown.filter { it in selection }

    private fun shareSelected() {
        val entries = selectedEntries()
        if (entries.isEmpty()) return
        val uris = ArrayList(entries.map { Uri.parse(it.uri) })
        val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        intent.type = if (entries.all { it.mime == "image/jpeg" }) "image/jpeg" else "*/*"
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(intent, "分享 ${uris.size} 张"))
    }

    private fun redevelopSelected() {
        val originals = selectedEntries().mapNotNull { e ->
            if (e.kind == Library.ORIGINAL) e
            else e.original?.let { Library.byUri(it) } ?: e.cameraKey?.let { Library.originalOf(it) }
        }.distinctBy { it.uri }
        if (originals.isEmpty()) {
            Toast.makeText(ctx, "选中的照片没有原片在手机上（设置里选「JPG + 原片」后冲洗的才有）", Toast.LENGTH_LONG).show()
            return
        }
        originals.forEach { DevelopQueue.redevelop(ctx, it) }
        Toast.makeText(ctx, "${originals.size} 张加入冲洗：${Recipe.load(ctx).summary()}", Toast.LENGTH_SHORT).show()
        selection.clear()
        refreshSelection()
    }

    private fun deleteSelected() {
        val entries = selectedEntries()
        if (entries.isEmpty()) return
        AlertDialog.Builder(ctx)
            .setTitle("删除 ${entries.size} 张${if (kind() == Library.PRINT) "成片" else "原片"}？")
            .setMessage("会从手机里删除。相机存储卡上的不受影响。")
            .setPositiveButton("删除") { _, _ ->
                val consent = Library.delete(ctx, entries)
                if (consent != null) activity.confirmDelete(consent, entries)
                selection.clear()
                refreshSelection()
            }
            .setNegativeButton("取消", null).show()
    }

    // ---- the queue -----------------------------------------------------------------------------

    private fun refreshQueue() {
        val jobs = DevelopQueue.jobs.toList().reversed()
        queueBox.visibility = if (jobs.isEmpty()) View.GONE else View.VISIBLE
        val active = jobs.count { !it.finished }
        val failed = jobs.count { it.status == DevelopQueue.Status.FAILED }
        queueTitle.text = buildString {
            append(if (active > 0) "处理中 $active 张" else "全部完成")
            append(" · 共 ${jobs.size}")
            if (failed > 0) append(" · 失败 $failed")
        }
        val shownJobs = jobs.sortedBy { if (it.finished) 1 else 0 }.take(if (arrangedWide == true) 8 else 4)
        while (queueRows.childCount > shownJobs.size) queueRows.removeViewAt(queueRows.childCount - 1)
        while (queueRows.childCount < shownJobs.size) queueRows.addView(jobRow())
        shownJobs.forEachIndexed { i, job -> bindJob(queueRows.getChildAt(i) as LinearLayout, job) }
    }

    private fun jobRow(): LinearLayout = LinearLayout(ctx).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, 0)
        addView(ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Ui.SURFACE2, dp(6).toFloat())
            clipToOutline = true
        }, LayoutParams(dp(44), dp(44)))
        val texts = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(dp(10), 0, 0, 0)
            addView(ctx.label("", 14f).single())
            addView(ctx.label("", 12f, Ui.SUB).single().apply { setPadding(0, dp(3), 0, dp(4)) })
            addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ui.LINE)
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        }
        addView(texts, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun bindJob(row: LinearLayout, job: DevelopQueue.Job) {
        val thumb = row.getChildAt(0) as ImageView
        thumb.setImageBitmap(job.thumb)
        val texts = row.getChildAt(1) as LinearLayout
        (texts.getChildAt(0) as android.widget.TextView).text =
            "${job.title}  ·  ${job.status.text}${if (!job.develop && job.status != DevelopQueue.Status.FAILED) "（仅导入）" else ""}"
        (texts.getChildAt(1) as android.widget.TextView).apply {
            text = job.detail
            setTextColor(if (job.status == DevelopQueue.Status.FAILED) Ui.RED else Ui.SUB)
        }
        val bar = texts.getChildAt(2) as ProgressBar
        bar.isIndeterminate = job.status == DevelopQueue.Status.DEVELOPING
        bar.progress = (job.progress * 1000).toInt()
        bar.visibility = if (job.finished) View.INVISIBLE else View.VISIBLE
        row.setOnClickListener {
            job.result?.let { r -> PhotoActivity.open(ctx, r.id, r.kind) }
        }
    }

    // ---- the library -----------------------------------------------------------------------------

    private fun refreshGrid() {
        val prints = kinds.selected == 0
        val entries = if (prints) Library.prints() else Library.originals()
        shown = entries
        selection.keys.retainAll(entries.map { it.uri }.toSet())
        val sections = entries.groupBy {
            val t = if (prints) it.time else it.shotTime.takeIf { s -> s > 0 } ?: it.time
            dayFormat.format(Date(t))
        }.map { (day, items) -> "$day · ${items.size} 张" to items }
        grid.setSections(sections)
        emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        emptyText.text = if (prints) "还没有冲洗的照片。\n连接相机拍一张，或在「相机」里选照片冲洗。"
        else "还没有导入的原片。\n在「相机」里长按选择照片，点「导入」；\n或在设置里选「JPG + 原片」，冲洗时原片也会存到手机。"
        refreshSelection()
    }

    private fun bindCell(cell: ThumbCell, e: Library.Entry) {
        cell.key = e.uri
        cell.badge.text = if (e.kind == Library.PRINT) shortRecipe(e) else e.name.substringAfterLast('.').uppercase()
        cell.mark(null, 0)
        cell.setSelected(selection.active, e in selection)
        val cached = thumbs.get(e.uri)
        cell.show(cached)
        if (cached != null) return
        loader.execute {
            val bmp = loadThumb(e)
            if (bmp != null) thumbs.put(e.uri, bmp)
            post { if (cell.key == e.uri) cell.show(bmp) }
        }
    }

    private fun shortRecipe(e: Library.Entry): String {
        val r = e.recipe ?: return "JPG"
        return runCatching { org.photoall.tether.engine.Films.short(r.getInt("film")) }.getOrDefault("JPG")
    }

    private fun loadThumb(e: Library.Entry): Bitmap? {
        val uri = Uri.parse(e.uri)
        runCatching { return ctx.contentResolver.loadThumbnail(uri, Size(320, 320), null) }
        // originals the media store cannot read: the camera's thumbnail kept for them
        e.cameraKey?.let { key ->
            val f = CameraHub.diskThumb(key)
            if (f.exists()) return BitmapFactory.decodeFile(f.path)
        }
        return null
    }

    companion object {
        private val loader = Executors.newFixedThreadPool(2)
        val thumbs = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
    }
}
