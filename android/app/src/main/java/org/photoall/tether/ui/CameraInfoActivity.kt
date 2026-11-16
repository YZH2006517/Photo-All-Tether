package org.photoall.tether.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.photoall.tether.camera.CameraHub
import org.photoall.tether.develop.DevelopQueue
import org.photoall.tether.develop.Library
import org.photoall.tether.develop.Prefs
import org.photoall.tether.ptp.Ptp
import org.photoall.tether.ptp.PtpReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything the camera tells about itself: body and firmware, battery and the current shooting
 * settings, each card, the pictures on them, this connection's USB figures, and (on request)
 * every device property it has.
 */
class CameraInfoActivity : AdaptiveActivity() {
    private lateinit var columns: List<LinearLayout>
    private lateinit var status: TextView
    private var props: Map<Int, ByteArray> = emptyMap()
    private var allProps: Map<Int, ByteArray>? = null
    private var showAll = false
    private var showOps = false
    private val hubListener: () -> Unit = { if (CameraHub.state != CameraHub.State.CONNECTED) finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.applyOrientation(this)
        val wide = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(12), dp(6))
        }
        top.addView(iconButton(Glyph.CHEVRON_LEFT, Ui.ACCENT, 40, filled = false, description = "返回") { finish() })
        top.addView(label("相机信息", Ui.HEADLINE, bold = true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(iconButton(Glyph.REFRESH, Ui.ACCENT, 40, filled = false, description = "刷新") { load() })
        top.addView(iconButton(Glyph.COPY, Ui.ACCENT, 40, filled = false, description = "复制") { copy("相机信息", asText()) })
        root.addView(top, matchWrap())
        status = label("正在读取相机…", 12f, Ui.SUB).apply { setPadding(dp(18), 0, dp(18), dp(6)) }
        root.addView(status)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), 0, dp(14), dp(40)) }
        columns = List(if (wide) 2 else 1) { LinearLayout(this).apply { orientation = LinearLayout.VERTICAL } }
        columns.forEachIndexed { i, c ->
            row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(14) })
        }
        root.addView(ScrollView(this).apply { addView(row) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        padForSystemBars(root)
        render()
        load()
    }

    override fun onStart() {
        super.onStart()
        CameraHub.addListener(hubListener)
    }

    override fun onStop() {
        CameraHub.removeListener(hubListener)
        super.onStop()
    }

    // The shooting settings now (a dozen properties), on the camera thread.
    private fun load() {
        status.text = "正在读取相机…"
        CameraHub.run {
            val shown = CameraHub.device?.properties?.filter { it in SHOWN } ?: emptyList()
            val read = CameraHub.readProps(shown)
            val all = if (showAll) CameraHub.readProps() else null
            runOnUiThread {
                props = read
                if (all != null) allProps = all
                status.text = "读取于 " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                render()
            }
        }
    }

    // ---- the page ------------------------------------------------------------------------------

    private class Section(val title: String, val rows: List<Pair<String, String>>, val extra: (LinearLayout) -> Unit = {})

    private fun sections(): List<Section> {
        val d = CameraHub.device
        val p = CameraHub.profile
        val out = mutableListOf<Section>()
        if (d != null) out += Section("机身", listOfNotNull(
            "品牌" to (p?.brand?.label ?: "—"),
            "厂商" to d.manufacturer,
            "型号" to d.model,
            "固件" to d.version,
            "序列号" to d.serial.ifBlank { "—" },
            "PTP 版本" to "%d.%02d".format(d.standardVersion / 100, d.standardVersion % 100),
            "厂商扩展" to "0x%X%s".format(d.vendorExtensionId, if (d.vendorExtension.isNotBlank()) " · ${d.vendorExtension}" else ""),
        ))

        val s = CameraHub.settings
        val shooting = mutableListOf<Pair<String, String>>()
        s.battery?.let { shooting += "电量" to "$it%" }
        for (code in SHOWN) props[code]?.let { shooting += (NAMES[code] ?: "0x%04X".format(code)) to format(code, it) }
        out += Section("电量与拍摄设置", shooting.ifEmpty { listOf("—" to "相机没有给出") })

        for ((i, st) in CameraHub.storages.sortedBy { it.id }.withIndex()) {
            val used = (st.maxCapacity - st.freeBytes).coerceAtLeast(0)
            val onCard = CameraHub.items.filter { it.storageId == st.id }
            out += Section("卡槽 ${i + 1}", listOf(
                "名称" to st.description.ifBlank { st.label }.ifBlank { "—" },
                "类型" to when (st.type) { 1 -> "固定 ROM"; 2 -> "可移动 ROM"; 3 -> "固定 RAM"; 4 -> "可移动 RAM"; else -> "未知" },
                "读写" to when (st.access) { 0 -> "读写"; 1 -> "只读（不可删除）"; 2 -> "只读（可删除）"; else -> "未知" },
                "容量" to formatBytes(st.maxCapacity),
                "已用" to "${formatBytes(used)}（%.0f%%）".format(if (st.maxCapacity > 0) used * 100.0 / st.maxCapacity else 0.0),
                "剩余" to formatBytes(st.freeBytes) + if (st.freeImages in 1..999999) " · 约 ${st.freeImages} 张" else "",
                "照片" to "${onCard.size} 张 · ${onCard.sumOf { it.files.size }} 个文件 · ${formatBytes(onCard.sumOf { it.size })}",
                "ID" to "0x%08X".format(st.id),
            )) { box ->
                box.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000
                    progress = if (st.maxCapacity > 0) (used * 1000 / st.maxCapacity).toInt() else 0
                    progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
                    progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ui.LINE)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(8) })
            }
        }

        val items = CameraHub.items
        if (items.isNotEmpty()) {
            val imported = Library.importedKeys()
            val times = items.map { it.time }.filter { it > 0 }
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            out += Section("照片", listOfNotNull(
                "总数" to "${items.size} 张 · ${items.sumOf { it.files.size }} 个文件 · ${formatBytes(items.sumOf { it.size })}",
                "RAW / JPEG" to "${items.count { it.raw != null }} / ${items.count { it.jpeg != null }}（RAW+JPEG ${items.count { it.raw != null && it.jpeg != null }}）",
                items.count { it.isVideo }.takeIf { it > 0 }?.let { "视频" to "$it 个" },
                "受保护" to "${items.count { it.isProtected }} 张",
                "已存到手机" to "${items.count { it.key in imported }} 张",
                if (times.isNotEmpty()) "时间范围" to "${day.format(Date(times.min()))} 至 ${day.format(Date(times.max()))}" else null,
                "RAW 格式" to items.mapNotNull { it.raw?.extension }.distinct().joinToString(" ").ifEmpty { "—" },
            ))
        }

        val since = CameraHub.connectedAt
        out += Section("本次连接", listOfNotNull(
            if (since > 0) "已连接" to duration(System.currentTimeMillis() - since) else null,
            "新拍收到" to "${synchronized(CameraHub.arrivals) { CameraHub.arrivals.size }} 个文件",
            "本次冲洗" to "${DevelopQueue.developedThisSession} 张",
            "新照片" to listOfNotNull(
                if (p?.pollsEvents == true) "轮询相机事件" else null,
                if (p?.interruptEvents == true) "USB 事件" else null,
                if (p?.listsForNew == true) "定时查找" else null,
                if (p?.sonyRemote == true) "相机内存（PC 遥控）" else null,
            ).joinToString("、").ifEmpty { "—" },
            "自动冲洗" to if (Prefs.tether(this)) "开" else "关",
        )) { box ->
            CameraHub.usbStats()?.let { box.addView(label(it, 12f, Ui.SUB).apply { setPadding(0, dp(8), 0, 0); setLineSpacing(0f, 1.3f) }) }
            p?.hint?.let { box.addView(label(it, 12f, Ui.ACCENT).apply { setPadding(0, dp(8), 0, 0); setLineSpacing(0f, 1.3f) }) }
        }

        if (d != null) out += Section("相机支持", listOf(
            "操作" to "${d.operations.size} 种",
            "事件" to "${d.events.size} 种",
            "设备属性" to "${d.properties.size} 种",
            "分段读取" to if (d.supports(Ptp.GET_PARTIAL_OBJECT)) "支持" else "不支持",
        )) { box ->
            box.addView(label(if (showOps) "收起操作列表 ▴" else "展开操作列表 ▾", 13f, Ui.ACCENT).apply {
                setPadding(0, dp(10), 0, dp(4))
                setOnClickListener { showOps = !showOps; render() }
            })
            if (showOps) box.addView(label(d.operations.sorted().joinToString("\n") { "0x%04X  %s".format(it, opName(it)) }, 11f, Ui.SUB).apply {
                typeface = Ui.mono
                setTextIsSelectable(true)
            })
            box.addView(label(if (showAll) "收起全部属性 ▴" else "读取全部 ${d.properties.size} 个属性 ▾", 13f, Ui.ACCENT).apply {
                setPadding(0, dp(10), 0, dp(4))
                setOnClickListener {
                    showAll = !showAll
                    if (showAll && allProps == null) load() else render()
                }
            })
            if (showAll) box.addView(label(allProps?.entries?.joinToString("\n") { (code, v) ->
                "0x%04X  %-10s %s".format(code, NAMES[code] ?: "", format(code, v))
            } ?: "读取中…", 11f, Ui.SUB).apply {
                typeface = Ui.mono
                setTextIsSelectable(true)
            })
        }
        return out
    }

    private fun render() {
        columns.forEach { it.removeAllViews() }
        if (CameraHub.state != CameraHub.State.CONNECTED) {
            columns[0].addView(label("相机未连接", 15f, Ui.SUB).apply { setPadding(dp(4), dp(20), 0, 0) })
            return
        }
        // two columns in landscape: each section to the shorter one
        val heights = IntArray(columns.size)
        for (sec in sections()) {
            val col = heights.indices.minBy { heights[it] }
            columns[col].addView(section(sec.title))
            val card = card()
            for ((k, v) in sec.rows) card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(5), 0, dp(5))
                addView(label(k, 13f, Ui.SUB), LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(label(v, 14f).apply { setTextIsSelectable(true) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, matchWrap())
            sec.extra(card)
            columns[col].addView(card, matchWrap())
            heights[col] += sec.rows.size + 3
        }
    }

    private fun asText(): String = buildString {
        for (sec in sections()) {
            appendLine("【${sec.title}】")
            sec.rows.forEach { (k, v) -> appendLine("$k：$v") }
        }
        CameraHub.usbStats()?.let { appendLine("【USB】").appendLine(it) }
    }

    private fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d 小时 %d 分".format(s / 3600, s % 3600 / 60) else if (s >= 60) "%d 分 %d 秒".format(s / 60, s % 60) else "$s 秒"
    }

    private fun opName(code: Int) = Ptp.operationName(code).takeIf { !it.startsWith("0x") } ?: OP_NAMES[code] ?: ""

    companion object {
        fun open(context: Context) = context.startActivity(Intent(context, CameraInfoActivity::class.java))

        /** The standard properties shown under the shooting settings, in this order. */
        private val SHOWN = listOf(0x500E, 0x5007, 0x500D, 0x500F, 0x5010, 0x5005, 0x500B, 0x500A, 0x500C,
            0x5008, 0x5013, 0x5004, 0x5003, 0x5011, 0x5017, 0x5018, 0x501C)

        private val NAMES = mapOf(
            0x5001 to "电量", 0x5003 to "图像尺寸", 0x5004 to "画质", 0x5005 to "白平衡", 0x5007 to "光圈",
            0x5008 to "焦距", 0x5009 to "对焦距离", 0x500A to "对焦模式", 0x500B to "测光", 0x500C to "闪光",
            0x500D to "快门", 0x500E to "曝光模式", 0x500F to "ISO", 0x5010 to "曝光补偿", 0x5011 to "相机时间",
            0x5012 to "自拍延时", 0x5013 to "释放模式", 0x5017 to "对比度", 0x5018 to "锐度", 0x5019 to "数码变焦",
            0x501A to "效果模式", 0x501B to "连拍张数", 0x501C to "包围间隔", 0x501D to "间隔拍摄", 0x501E to "间隔时长",
        )

        private val OP_NAMES = mapOf(
            0x1012 to "SetObjectProtection", 0x1014 to "GetDevicePropDesc", 0x1016 to "SetDevicePropValue",
            0x100C to "SendObjectInfo", 0x100D to "SendObject", 0x100F to "FormatStore", 0x1013 to "PowerDown",
            0x9201 to "厂商：实时取景 / SDIO", 0x9202 to "厂商：结束实时取景 / SDIO", 0x9203 to "厂商：取景图像",
        )

        private fun intOf(b: ByteArray): Long? = when (b.size) {
            1 -> (b[0].toLong() and 0xFF)
            2 -> PtpReader(b).u16().toLong()
            4 -> PtpReader(b).u32()
            8 -> PtpReader(b).u64()
            else -> null
        }

        private fun stringOf(b: ByteArray): String? {
            if (b.isEmpty()) return null
            val n = b[0].toInt() and 0xFF
            return if (n > 0 && b.size == 1 + n * 2) PtpReader(b).string() else null
        }

        /** A property value in words where the code is known, else the number. */
        fun format(code: Int, b: ByteArray): String {
            stringOf(b)?.let { s ->
                if (code == 0x5011 && s.length >= 15) return "${s.substring(0, 4)}-${s.substring(4, 6)}-${s.substring(6, 8)} ${s.substring(9, 11)}:${s.substring(11, 13)}:${s.substring(13, 15)}"
                return s
            }
            val v = intOf(b) ?: return b.joinToString(" ") { "%02X".format(it) }.take(60)
            return when (code) {
                0x5001 -> "$v%"
                0x5004 -> when (v.toInt()) { 0 -> "JPEG 基本"; 1 -> "JPEG 标准"; 2 -> "JPEG 精细"; 3 -> "JPEG 最佳"; 4 -> "RAW"; 5 -> "RAW + JPEG 基本"
                    6 -> "RAW + JPEG 标准"; 7 -> "RAW + JPEG 精细"; else -> "$v" }
                0x5005 -> when (v.toInt()) { 1 -> "手动"; 2 -> "自动"; 3 -> "单键自动"; 4 -> "日光"; 5 -> "荧光灯"; 6 -> "白炽灯"; 7 -> "闪光灯"
                    0x8010 -> "阴天"; 0x8011 -> "阴影"; 0x8012 -> "色温"; 0x8013 -> "手动预设"; 0x8016 -> "自然光自动"; else -> "0x%X".format(v) }
                0x5007 -> "f/%.1f".format(v / 100.0)
                0x5008 -> "%.0f mm".format(v / 100.0)
                0x500A -> when (v.toInt()) { 1 -> "手动对焦"; 2 -> "自动对焦"; 3 -> "微距"; 0x8010 -> "AF-S"; 0x8011 -> "AF-C"; 0x8012 -> "AF-A"
                    0x8013 -> "AF-F"; else -> "0x%X".format(v) }
                0x500B -> when (v.toInt()) { 1 -> "平均"; 2 -> "中央重点"; 3 -> "矩阵 / 多区"; 4 -> "点测光"; 0x8010 -> "高光重点"; else -> "0x%X".format(v) }
                0x500C -> when (v.toInt()) { 1 -> "自动"; 2 -> "关闭"; 3 -> "补光"; 4 -> "防红眼"; 5 -> "防红眼补光"; 6 -> "外接"; else -> "0x%X".format(v) }
                0x500D -> when {
                    v == 0xFFFFFFFFL || v == 0x7FFFFFFFL -> "B 门"
                    v >= 3000 -> "%.1f″".format(v / 10000.0)
                    v > 0 -> "1/${Math.round(10000.0 / v)}"
                    else -> "$v"
                }
                0x500E -> when (v.toInt()) { 1 -> "M 手动"; 2 -> "P 程序"; 3 -> "A 光圈优先"; 4 -> "S 快门优先"; 5 -> "创意"; 6 -> "动作"; 7 -> "人像"
                    0x8010 -> "自动"; 0x8011 -> "人像（场景）"; 0x8016 -> "U1"; 0x8017 -> "U2"; 0x8018 -> "U3"; else -> "0x%X".format(v) }
                0x500F -> "ISO $v"
                0x5010 -> "%+.1f EV".format((if (b.size == 2) PtpReader(b).s16() else v.toInt()) / 1000.0)
                0x5013 -> when (v.toInt()) { 1 -> "单张"; 2 -> "连拍"; 3 -> "延时"; 0x8010 -> "连拍（高速）"; 0x8011 -> "自拍"; 0x8012 -> "反光镜预升"
                    0x8013 -> "快速"; 0x8014 -> "遥控"; 0x8015 -> "静音"; else -> "0x%X".format(v) }
                else -> if (v > 0xFFFF) "0x%X".format(v) else "$v"
            }
        }
    }
}
