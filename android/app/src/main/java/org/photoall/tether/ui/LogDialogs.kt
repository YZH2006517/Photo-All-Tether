package org.photoall.tether.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.photoall.tether.ErrorLog
import org.photoall.tether.camera.CameraHub

/** Long text in a scrolling dialog, selectable, with a copy button. */
fun Context.showText(title: String, text: String, scrollToEnd: Boolean = false, extra: Pair<String, () -> Unit>? = null) {
    val view = TextView(this).apply {
        this.text = text
        textSize = 11f
        typeface = Typeface.MONOSPACE
        // the dialogs are always dark (AppDialog, #2C2C2E), whatever the app's theme: Ui.TEXT
        // would be black in the light theme there
        setTextColor(0xFFF2F2F7.toInt())
        setTextIsSelectable(true)
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    val scroll = ScrollView(this).apply { addView(view) }
    val builder = AlertDialog.Builder(this).setTitle(title).setView(scroll)
        .setPositiveButton("复制") { _, _ -> copy(title, text) }
        .setNegativeButton("关闭", null)
    extra?.let { (label, action) -> builder.setNeutralButton(label) { _, _ -> action() } }
    builder.show()
    if (scrollToEnd) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
}

fun Context.copy(label: String, text: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(this, "已复制，可以直接粘贴发送", Toast.LENGTH_SHORT).show()
}

/** The error log, newest last; it can be cleared from here. */
fun Context.showErrorLog() {
    val text = ErrorLog.text()
    if (text.isBlank()) {
        Toast.makeText(this, "没有错误记录", Toast.LENGTH_SHORT).show()
        return
    }
    showText("错误日志（${ErrorLog.count} 条）", text, scrollToEnd = true, extra = "清空" to {
        AlertDialog.Builder(this).setMessage("清空错误日志？").setPositiveButton("清空") { _, _ -> ErrorLog.clear() }
            .setNegativeButton("取消", null).show()
    })
}

/** Everything for a report in one text: the errors and the connection log. */
fun diagnosticReport(): String = buildString {
    append("【错误日志】\n").append(ErrorLog.text().ifBlank { "（无）\n" })
    append("\n【连接日志】\n").append(CameraHub.logText().ifBlank { "（无）" })
}

fun Context.showConnectionLog() =
    showText("连接日志", CameraHub.logText().ifEmpty { "（还没有记录）" }, scrollToEnd = true)
