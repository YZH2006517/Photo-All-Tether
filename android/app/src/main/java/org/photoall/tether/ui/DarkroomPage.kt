package org.photoall.tether.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.photoall.tether.camera.CameraHub
import org.photoall.tether.develop.DevelopQueue
import org.photoall.tether.develop.Library
import org.photoall.tether.engine.Films
import org.photoall.tether.engine.Recipe
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The darkroom: the trays (photos on their way, each a thumbnail ringed by its progress, a
 * print coming up as it finishes), then the prints and the originals by day. Long-press and
 * slide to select; a floating toolbar shares, develops again or deletes.
 */
class DarkroomPage(private val activity: MainActivity) : LinearLayout(activity), Arrangeable {
    private val ctx = activity
    private val kinds = Segmented(ctx, listOf("成片", "原片"), 0, listOf(Glyph.PHOTO, Glyph.PHOTO_STACK)) {
        selection.clear(); refreshGrid()
    }
    private val trays = LinearLayout(ctx).apply { orientation = HORIZONTAL }
    private val traysBox = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val list = DragSelectList(ctx)
    private val empty = LinearLayout(ctx)
    private val toolbar = LinearLayout(ctx)
    private val selectionCount = ctx.label("", Ui.SUBHEAD, Ui.TEXT, bold = true)
    private val dayFormat = SimpleDateFormat("M月d日 EEEE", Locale.CHINA)
    private val selection = DragSelection<Library.Entry> { it.uri }
    private var shown: List<Library.Entry> = emptyList()
    private val trayViews = LinkedHashMap<Long, Tray>()
    private val head = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val titleButtons = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    // ---- landscape's left column: the latest print large, the counts, the two ways in ----------
    private val left = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val hero = FrameLayout(ctx).apply { background = rounded(Ui.SURFACE, dp(18).toFloat()); clipToOutline = true }
    private val heroImage = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val heroTitle = ctx.label("", Ui.SUBHEAD, android.graphics.Color.WHITE, bold = true).single()
    private val heroSub = ctx.label("", Ui.FOOTNOTE, 0xCCFFFFFF.toInt()).single()
    private val heroCaption = LinearLayout(ctx).apply {
        orientation = VERTICAL
        setPadding(dp(14), dp(28), dp(14), dp(12))
        background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0xB3000000.toInt(), 0x00000000))
    }
    private val heroEmpty = LinearLayout(ctx).apply { orientation = VERTICAL; gravity = Gravity.CENTER }
    private var heroUri: String? = null
    private val statPrints = ctx.label("0", Ui.HEADLINE, Ui.TEXT, bold = true).apply { fontFeatureSettings = "tnum" }
    private val statOriginals = ctx.label("0", Ui.HEADLINE, Ui.TEXT, bold = true).apply { fontFeatureSettings = "tnum" }
    private val statToday = ctx.label("0", Ui.HEADLINE, Ui.TEXT, bold = true).apply { fontFeatureSettings = "tnum" }
    private val stats = LinearLayout(ctx).apply { orientation = HORIZONTAL; background = rounded(Ui.SURFACE, dp(18).toFloat()) }
    private val actionsBar = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val body = FrameLayout(ctx)
    private var arrangedWide: Boolean? = null

    private val grid: SectionGrid<Library.Entry> = SectionGrid(ctx, 3,
        bind = { cell, e -> bindCell(cell, e) },
        onClick = { e, _ ->
            if (selection.active) { selection.toggle(e); Haptic.tick(this); refreshSelection() }
            else PhotoActivity.open(ctx, e.id, kind())
        },
        onLongClick = { _, index ->
            selection.begin(grid.flat, index)
            list.startDrag(index)
            Haptic.confirm(this)
            refreshSelection()
        },
        headerAction = { _, items ->
            if (items.all { it in selection }) items.forEach { selection.keys -= it.uri } else items.forEach { selection.keys += it.uri }
            Haptic.tick(this)
            refreshSelection()
        },
        headerActionText = { if (selection.active) "选择" else null },
    )

    private val libraryListener: () -> Unit = { refreshGrid() }
    private val queueListener: () -> Unit = { refreshTrays() }

    init {
        val titleRow = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(ctx.largeTitle("暗房"), LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // the immersive view as a filled pill with its name; the phone's RAW beside it as a glyph
        titleButtons.addView(ctx.button("沉浸模式", primary = true, glyph = Glyph.EXPAND) { ImmersiveActivity.open(ctx) },
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
        titleButtons.addView(ctx.iconButton(Glyph.FOLDER_PLUS, Ui.ACCENT, 42, description = "打开手机里的 RAW") { activity.pickLocal() },
            LayoutParams(dp(42), dp(42)).apply { marginStart = dp(10) })
        titleRow.addView(titleButtons)
        head.addView(titleRow, matchWrap())
        traysBox.addView(ctx.hScroll(trays), matchWrap())
        head.addView(traysBox, matchWrap().apply { topMargin = dp(14) })
        head.addView(kinds, matchWrap().apply { topMargin = dp(14) })

        list.adapter = grid
        list.setPadding(0, 0, 0, dp(96))
        // landscape: the first day's title on the line of the 成片 / 原片 switch beside it
        kinds.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> post { alignListTop() } }
        body.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> post { alignListTop() } }
        hero.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> post { alignListTop() } }
        list.onDragTo = { index ->
            selection.dragTo(grid.flat, index)
            Haptic.tick(this)
            refreshSelection()
        }
        list.onWidth = { w -> grid.setColumns((w / dp(118)).coerceIn(3, 8)) }
        buildEmpty()
        buildToolbar()
        body.addView(list, frameMatch())
        body.addView(empty, frameMatch())
        body.addView(toolbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(14)
        })
        buildLeft()
        arrange(activity.wide)
        refreshTrays()
        refreshGrid()
    }

    private fun buildLeft() {
        // the latest print, as large as the column allows; its recipe and when over its foot
        hero.addView(heroImage, frameMatch())
        heroCaption.addView(heroTitle)
        heroCaption.addView(heroSub, matchWrap().apply { topMargin = dp(3) })
        hero.addView(heroCaption, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        heroEmpty.addView(IconView(ctx, Glyph.PHOTO, Ui.DIM, 40), LayoutParams(dp(40), dp(40)))
        heroEmpty.addView(ctx.label("冲好的照片会出现在这里", Ui.FOOTNOTE, Ui.SUB).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
        hero.addView(heroEmpty, frameMatch())
        hero.setOnClickListener { Library.prints().firstOrNull()?.let { PhotoActivity.open(ctx, it.id, Library.PRINT) } }
        hero.pressable(0.98f)
        // the counts: prints, originals, today's
        fun stat(glyph: Glyph, value: android.widget.TextView, caption: String) {
            // one line each: the glyph, the figure, what it counts
            stats.addView(LinearLayout(ctx).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(11), 0, dp(11))
                addView(IconView(ctx, glyph, Ui.ACCENT, 15), LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(6) })
                addView(value)
                addView(ctx.label(caption, Ui.CAPTION, Ui.SUB, bold = true), LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
            }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        stat(Glyph.PHOTO, statPrints, "成片")
        stat(Glyph.PHOTO_STACK, statOriginals, "原片")
        stat(Glyph.SUN, statToday, "今天")
        // the two ways in, as one capsule (as the camera page's actions)
        actionsBar.background = rounded(Ui.SURFACE, dp(18).toFloat())
        // the immersive view is the main one: filled in the accent, larger, its name in full
        fun action(glyph: Glyph, text: String, main: Boolean, onClick: () -> Unit) {
            val fg = if (main) Ui.ON_ACCENT else Ui.ACCENT
            actionsBar.addView(LinearLayout(ctx).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                background = pressable(ctx, if (main) Ui.ACCENT else android.graphics.Color.TRANSPARENT, dp(14).toFloat())
                val icon = if (main) 24 else 20
                addView(IconView(ctx, glyph, fg, icon), LayoutParams(dp(icon), dp(icon)).apply { marginEnd = dp(8) })
                addView(ctx.label(text, if (main) Ui.BODY else Ui.SUBHEAD, fg, bold = true).single())
                contentDescription = text
                setOnClickListener { Haptic.select(this); onClick() }
                pressable(0.94f)
            }, LayoutParams(0, dp(if (main) 46 else 50), if (main) 1.15f else 1f).apply { if (main) setMargins(dp(4), dp(4), 0, dp(4)) })
        }
        action(Glyph.EXPAND, "沉浸模式", true) { ImmersiveActivity.open(ctx) }
        action(Glyph.FOLDER_PLUS, "手机里的 RAW", false) { activity.pickLocal() }
    }

    /**
     * Landscape: the grid's top padding that puts its first day title's words on the middle
     * of the 成片 / 原片 switch in the left column (portrait: none).
     */
    private fun alignListTop() {
        if (arrangedWide != true || kinds.height == 0 || body.height == 0 || hero.height == 0) {
            if (list.paddingTop != 0) list.setPadding(0, 0, 0, dp(96))
            grid.headerBottomPx = null
            return
        }
        val a = IntArray(2)
        val b = IntArray(2)
        val c = IntArray(2)
        kinds.getLocationInWindow(a)
        body.getLocationInWindow(b)
        hero.getLocationInWindow(c)
        val centre = a[1] + kinds.height / 2 - b[1]
        val heroTop = c[1] - b[1]
        // a day title: 18 dp of air above its words (SectionGrid), then the words
        val probe = ctx.label("日", Ui.HEADLINE, bold = true)
        probe.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val words = probe.measuredHeight
        // the words on the switch's middle; the first pictures' top on the large picture's top
        val pad = (centre - dp(18) - words / 2).coerceAtLeast(0)
        if (list.paddingTop != pad) list.setPadding(0, pad, 0, dp(96))
        grid.headerBottomPx = (heroTop - pad - dp(18) - words).coerceAtLeast(dp(4))
    }

    /** The latest print and the counts (landscape's left column). */
    private fun refreshLeft() {
        if (arrangedWide != true) return
        val prints = Library.prints()
        statPrints.text = prints.size.toString()
        statOriginals.text = Library.originals().size.toString()
        val today = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
        }.timeInMillis
        statToday.text = prints.count { it.time >= today }.toString()
        val latest = prints.firstOrNull()
        heroEmpty.visibility = if (latest == null) View.VISIBLE else View.GONE
        heroCaption.visibility = if (latest == null) View.GONE else View.VISIBLE
        if (latest == null) { heroImage.setImageDrawable(null); heroUri = null; return }
        heroTitle.text = latest.recipe?.let { runCatching { Recipe.fromJson(it).summary() }.getOrNull() } ?: latest.name
        heroSub.text = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(latest.time)) + "  ·  " + latest.name.substringBeforeLast('_')
        if (heroUri == latest.uri) return
        heroUri = latest.uri
        val uri = latest.uri
        loader.execute {
            val bmp = runCatching {
                ctx.contentResolver.loadThumbnail(Uri.parse(uri), Size(1200, 1200), null)
            }.getOrNull()
            post {
                if (heroUri != uri || bmp == null) return@post
                heroImage.setImageBitmap(bmp)
                // a new print comes up as in the developer
                heroImage.develop(1200)
            }
        }
    }

    private fun buildEmpty() {
        empty.orientation = VERTICAL
        empty.gravity = Gravity.CENTER
        empty.setPadding(dp(28), 0, dp(28), dp(80))
        val art = FrameLayout(ctx).apply {
            background = rounded(Ui.SURFACE, dp(44).toFloat())
            addView(IconView(ctx, Glyph.PHOTO, Ui.ACCENT, 42), FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))
        }
        empty.addView(art, LayoutParams(dp(88), dp(88)))
        empty.addView(ctx.label("", Ui.TITLE, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) })
        empty.addView(ctx.label("", Ui.SUBHEAD, Ui.SUB).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
    }

    private fun buildToolbar() {
        toolbar.orientation = HORIZONTAL
        toolbar.gravity = Gravity.CENTER_VERTICAL
        toolbar.background = rounded(Ui.BAR, dp(28).toFloat(), 1, Ui.LINE)
        toolbar.elevation = dp(10).toFloat()
        toolbar.setPadding(dp(16), 0, dp(8), 0)
        toolbar.addView(selectionCount, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        listOf(
            ctx.iconButton(Glyph.SELECT_ALL, Ui.ACCENT, 44, filled = false, description = "全选") {
                shown.forEach { selection.keys += it.uri }; refreshSelection()
            },
            ctx.iconButton(Glyph.SHARE, Ui.ACCENT, 44, filled = false, description = "分享") { shareSelected() },
            ctx.iconButton(Glyph.WAND, Ui.ACCENT, 44, filled = false, description = "用当前配方重冲") { redevelopSelected() },
            ctx.iconButton(Glyph.TRASH, Ui.RED, 44, filled = false, description = "删除") { deleteSelected() },
            ctx.iconButton(Glyph.CLOSE, Ui.SUB, 44, filled = false, description = "取消选择") { selection.clear(); refreshSelection() },
        ).forEach { toolbar.addView(it, LayoutParams(dp(44), dp(44))) }
        toolbar.visibility = View.GONE
    }

    override fun arrange(wide: Boolean) {
        if (arrangedWide == wide) return
        arrangedWide = wide
        listOf(head, body, hero, stats, actionsBar, left).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        left.removeAllViews()
        removeAllViews()
        if (!wide) {
            orientation = VERTICAL
            setPadding(dp(16), dp(6), dp(16), 0)
            titleButtons.visibility = View.VISIBLE
            addView(head, matchWrap())
            addView(body, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) })
        } else {
            // the left column fills the height: the title and the switch, the latest print taking
            // what is left, the counts, and the two ways in at the foot
            orientation = HORIZONTAL
            setPadding(dp(14), dp(8), dp(14), 0)
            titleButtons.visibility = View.GONE
            left.setPadding(0, 0, 0, dp(14))
            left.addView(head, matchWrap())
            left.addView(hero, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(14) })
            left.addView(stats, matchWrap().apply { topMargin = dp(10) })
            left.addView(actionsBar, matchWrap().apply { topMargin = dp(10) })
            val screen = resources.displayMetrics.widthPixels / resources.displayMetrics.density
            val column = (screen * 0.36f).toInt().coerceIn(280, 400)
            addView(left, LayoutParams(dp(column), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(body, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(14) })
            refreshLeft()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Library.addListener(libraryListener)
        DevelopQueue.addListener(queueListener)
        refreshTrays()
        refreshGrid()
    }

    override fun onDetachedFromWindow() {
        Library.removeListener(libraryListener)
        DevelopQueue.removeListener(queueListener)
        super.onDetachedFromWindow()
    }

    private fun kind() = if (kinds.selected == 0) Library.PRINT else Library.ORIGINAL

    // ---- the trays -----------------------------------------------------------------------------

    /** A photo on its way: its picture ringed by progress, a glyph for the stage. */
    private inner class Tray(val job: DevelopQueue.Job) : LinearLayout(ctx) {
        val ring = RingProgress(ctx).apply { thickness = dp(3).toFloat() }
        val picture = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            // round, inside the ring with a gap, so the progress ring frames it cleanly
            background = rounded(Ui.SURFACE2, dp(30).toFloat())
            clipToOutline = true
        }
        val stage = IconView(ctx, Glyph.IMPORT, android.graphics.Color.WHITE, 12).apply {
            background = rounded(Ui.ACCENT, dp(10).toFloat())
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val name = ctx.label(job.title, Ui.CAPTION, Ui.SUB, bold = true).single()
        var shownStatus: DevelopQueue.Status? = null

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val box = FrameLayout(ctx)
            box.addView(ring, FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER))
            box.addView(picture, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER))
            box.addView(stage, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(2), dp(2)) })
            addView(box, LayoutParams(dp(78), dp(78)))
            addView(name, LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            name.gravity = Gravity.CENTER
            setOnClickListener {
                Haptic.select(this)
                when {
                    job.status == DevelopQueue.Status.FAILED ->
                        AlertDialog.Builder(ctx).setTitle(job.title).setMessage(job.error ?: "").setPositiveButton("好", null).show()
                    job.result != null -> job.result?.let { r -> PhotoActivity.open(ctx, r.id, r.kind) }
                }
            }
            pressable()
        }

        fun bind() {
            val st = job.status
            picture.setImageBitmap(job.thumb)
            ring.indeterminate = st == DevelopQueue.Status.DEVELOPING || st == DevelopQueue.Status.WAITING
            ring.setProgress(if (st == DevelopQueue.Status.DONE) 1f else job.progress)
            ring.color = when (st) {
                DevelopQueue.Status.FAILED -> Ui.RED
                DevelopQueue.Status.DONE -> Ui.GREEN
                else -> Ui.ACCENT
            }
            stage.glyph = when (st) {
                DevelopQueue.Status.WAITING -> Glyph.TIMER
                DevelopQueue.Status.DOWNLOADING -> Glyph.IMPORT
                DevelopQueue.Status.DEVELOPING -> Glyph.WAND
                DevelopQueue.Status.DONE -> if (job.develop) Glyph.CHECK else Glyph.IMPORT
                DevelopQueue.Status.FAILED -> Glyph.CLOSE
            }
            stage.background = rounded(when (st) {
                DevelopQueue.Status.FAILED -> Ui.RED
                DevelopQueue.Status.DONE -> Ui.GREEN
                else -> Ui.ACCENT
            }, dp(10).toFloat())
            // the moment a print comes out of the developer: it comes up in the tray
            if (st != shownStatus) {
                if (st == DevelopQueue.Status.DONE && shownStatus != null && job.develop) {
                    picture.develop(1800)
                    stage.pop(0.4f)
                }
                if (st == DevelopQueue.Status.FAILED && shownStatus != null) nudge()
                shownStatus = st
            }
        }
    }

    private fun refreshTrays() {
        val jobs = DevelopQueue.jobs.toList().reversed().take(24)
        traysBox.visibility = if (jobs.isEmpty()) View.GONE else View.VISIBLE
        val ids = jobs.map { it.id }.toSet()
        trayViews.keys.filter { it !in ids }.forEach { id -> trayViews.remove(id)?.let { trays.removeView(it) } }
        jobs.forEachIndexed { i, job ->
            val view = trayViews.getOrPut(job.id) {
                Tray(job).also { t ->
                    trays.addView(t, i.coerceAtMost(trays.childCount), LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
                    t.pop(0.5f)
                }
            }
            view.bind()
        }
        // a broom for the finished ones, once there are some
        val done = jobs.count { it.finished }
        val clear = trays.findViewWithTag<View>("clear")
        if (done > 0 && clear == null) trays.addView(ctx.iconButton(Glyph.TRASH, Ui.SUB, 44, description = "清除已完成") {
            DevelopQueue.clearFinished()
        }.apply { tag = "clear" }, LayoutParams(dp(44), dp(44)).apply { topMargin = dp(17); marginStart = dp(4) })
        else if (done == 0 && clear != null) trays.removeView(clear)
        else if (clear != null && trays.indexOfChild(clear) != trays.childCount - 1) { trays.removeView(clear); trays.addView(clear) }
    }

    // ---- selection -----------------------------------------------------------------------------

    private fun refreshSelection() {
        val active = selection.active
        val was = toolbar.visibility == View.VISIBLE
        selectionCount.text = selection.keys.size.toString()
        if (active && !was) {
            toolbar.visibility = View.VISIBLE
            toolbar.translationY = dp(90).toFloat()
            toolbar.animate().translationY(0f).setDuration(460).setInterpolator(Ease.spring).start()
        } else if (!active && was) {
            toolbar.animate().translationY(dp(90).toFloat()).setDuration(240).setInterpolator(Ease.out)
                .withEndAction { toolbar.visibility = View.GONE }.start()
        }
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
        ctx.startActivity(Intent.createChooser(intent, null))
    }

    private fun redevelopSelected() {
        val originals = selectedEntries().mapNotNull { e ->
            if (e.kind == Library.ORIGINAL) e
            else e.original?.let { Library.byUri(it) } ?: e.cameraKey?.let { Library.originalOf(it) }
        }.distinctBy { it.uri }
        if (originals.isEmpty()) {
            Haptic.reject(this)
            Hud.show(activity, Glyph.PHOTO_STACK, "没有原片", Ui.RED)
            return
        }
        originals.forEach { DevelopQueue.redevelop(ctx, it) }
        Hud.show(activity, Glyph.WAND, "${originals.size} 张 · ${Films.short(Recipe.load(ctx).film)}")
        selection.clear()
        refreshSelection()
    }

    private fun deleteSelected() {
        val entries = selectedEntries()
        if (entries.isEmpty()) return
        AlertDialog.Builder(ctx)
            .setTitle("删除 ${entries.size} 张？")
            .setMessage("从手机里删除。相机存储卡上的不受影响。")
            .setPositiveButton("删除") { _, _ ->
                val consent = Library.delete(ctx, entries)
                if (consent != null) activity.confirmDelete(consent, entries)
                else { Haptic.confirm(this); Hud.show(activity, Glyph.TRASH, "${entries.size} 张") }
                selection.clear()
                refreshSelection()
            }
            .setNegativeButton("取消", null).show()
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
        }.map { (day, items) -> "$day · ${items.size}" to items }
        grid.setSections(sections)
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        ((empty.getChildAt(0) as FrameLayout).getChildAt(0) as IconView).glyph = if (prints) Glyph.PHOTO else Glyph.PHOTO_STACK
        (empty.getChildAt(1) as android.widget.TextView).text = if (prints) "还没有成片" else "还没有原片"
        (empty.getChildAt(2) as android.widget.TextView).text = if (prints) "拍一张，或打开手机里的 RAW" else "在相机页选照片导入"
        refreshSelection()
        refreshLeft()
    }

    private fun bindCell(cell: ThumbCell, e: Library.Entry) {
        cell.key = e.uri
        cell.badge.text = if (e.kind == Library.PRINT) shortRecipe(e) else e.name.substringAfterLast('.').uppercase()
        cell.marks(false, false)
        cell.setSelected(selection.active, e in selection)
        val cached = thumbs.get(e.uri)
        cell.show(cached)
        if (cached != null) return
        loader.execute {
            val bmp = loadThumb(e)
            if (bmp != null) thumbs.put(e.uri, bmp)
            post { if (cell.key == e.uri) cell.show(bmp, fade = true) }
        }
    }

    private fun shortRecipe(e: Library.Entry): String {
        val r = e.recipe ?: return "JPG"
        return runCatching { Films.short(r.getInt("film")) }.getOrDefault("JPG")
    }

    private fun loadThumb(e: Library.Entry): Bitmap? {
        val uri = Uri.parse(e.uri)
        runCatching { return ctx.contentResolver.loadThumbnail(uri, Size(320, 320), null) }
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
