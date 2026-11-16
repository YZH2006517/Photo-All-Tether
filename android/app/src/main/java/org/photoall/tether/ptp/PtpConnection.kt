package org.photoall.tether.ptp

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A USB step that failed, with what is known about it (for the error log). */
class UsbException(message: String, val stage: String, val result: Int, val elapsedMs: Long) : IOException(message)

/**
 * PTP over USB (the Still Image class bulk-only transport) on Android's USB host API.
 *
 * One transaction at a time under a fair lock, so a long download (read in chunks with
 * GetPartialObject) lets thumbnails and event polls in between its chunks. Bulk-in reads
 * go through one buffer that keeps what a transfer brought beyond the current container:
 * a data phase that ends exactly on a packet boundary may arrive together with the
 * response, or be followed by a zero-length packet.
 *
 * Transfers are 64 KB, and 16 KB from the first one that fails at once: the kernel copies
 * each transfer through one contiguous buffer, which a busy phone may not have for more.
 * Before the first command the camera is brought back to idle (see [prepare]): a camera that
 * another program or an earlier failed attempt left mid-transaction refuses new commands.
 */
class PtpConnection private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
    private val interruptIn: UsbEndpoint?,
    private val log: (String) -> Unit,
) : Closeable {
    class Response(val code: Int, val params: IntArray)
    class Event(val code: Int, val params: IntArray)

    private val lock = ReentrantLock(true)
    private var transactionId = 0
    private var sessionOpen = false
    private var recovering = false
    private val packet = bulkIn.maxPacketSize.coerceAtLeast(64)
    private val buffer = ByteArray(MAX_CHUNK)
    @Volatile private var chunk = MAX_CHUNK
    /** Transactions so far, and the bytes they brought (for the camera's info page). */
    @Volatile var transactions = 0
        private set
    @Volatile var bytesIn = 0L
        private set

    /** Bytes per USB transfer now (64 KB, or 16 KB once the phone refused larger ones). */
    val chunkSize: Int get() = chunk
    val packetSize: Int get() = packet
    private var start = 0
    private var end = 0
    private var stage = ""
    @Volatile var broken: String? = null
        private set
    @Volatile private var closed = false
    private val trace = ArrayDeque<String>()

    val hasInterruptEndpoint: Boolean get() = interruptIn != null

    // ---- transactions --------------------------------------------------------------------------

    /**
     * Runs one operation. Data from the camera goes to [sink] (bytes as they arrive, the
     * total first); [dataOut] is sent to the camera. Returns the response, OK or not.
     */
    fun transact(
        operation: Int,
        params: IntArray = IntArray(0),
        dataOut: ByteArray? = null,
        sink: ((total: Long, bytes: ByteArray, offset: Int, length: Int) -> Unit)? = null,
        timeout: Int = 6000,
    ): Response = lock.withLock {
        broken?.let { throw IOException("连接已中断：$it") }
        if (closed) throw IOException("连接已关闭")
        val name = Ptp.operationName(operation)
        val began = SystemClock.elapsedRealtime()
        try {
            val tid = if (operation == Ptp.OPEN_SESSION) 0 else ++transactionId
            start = 0; end = 0
            stage = "发送命令 $name"
            send(container(Ptp.COMMAND, operation, tid, params, 0), timeout)
            if (dataOut != null) {
                stage = "发送数据 $name"
                val all = container(Ptp.DATA, operation, tid, IntArray(0), dataOut.size) + dataOut
                send(all, timeout)
                if (all.size % bulkOut.maxPacketSize.coerceAtLeast(64) == 0) connection.bulkTransfer(bulkOut, all, 0, 0, timeout)
            }
            stage = "等待 $name 的回应"
            var head = readHeader(timeout)
            var received = 0L
            if (head.type == Ptp.DATA) {
                var remaining = head.length - 12L
                val total = remaining
                stage = "接收 $name 的数据"
                while (remaining > 0) {
                    if (start == end) fill(if (remaining >= chunk) chunk else roundUp(remaining.toInt()), timeout)
                    val take = minOf((end - start).toLong(), remaining).toInt()
                    sink?.invoke(total, buffer, start, take)
                    start += take
                    remaining -= take
                    received += take
                }
                stage = "等待 $name 的结果"
                head = readHeader(timeout)
            }
            if (head.type != Ptp.RESPONSE) throw UsbException("$name：应答类型错误 ${head.type}（代码 0x%04X）".format(head.code), stage, head.type, 0)
            val count = ((head.length - 12) / 4).toInt().coerceIn(0, 5)
            val raw = ByteArray(count * 4)
            readExact(raw, raw.size, timeout)
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            transactions++
            bytesIn += received
            if (operation == Ptp.OPEN_SESSION && (head.code == Ptp.OK || head.code == Ptp.SESSION_ALREADY_OPEN)) sessionOpen = true
            note("#$tid $name → ${Ptp.responseName(head.code)}${if (received > 0) "，$received 字节" else ""}，${SystemClock.elapsedRealtime() - began} ms")
            Response(head.code, IntArray(count) { bb.int })
        } catch (e: IOException) {
            note("$name 失败：${e.message}")
            // One refused operation must not end the connection: back to idle and go on. A
            // container that makes no sense means the stream is out of step: only reopening
            // the device (the hub reconnects) puts it right.
            val desync = e is UsbException && e.stage.startsWith("等待") && e.result >= 0
            if (closed || recovering || desync || !recover(name)) broken = e.message ?: "USB 错误"
            throw e
        }
    }

    // After a failed transaction: back to idle, or false when the camera no longer answers.
    private fun recover(name: String): Boolean {
        recovering = true
        try {
            return recoverOnce(name)
        } finally {
            recovering = false
        }
    }

    private fun recoverOnce(name: String): Boolean {
        // Drain what the camera still sends; endpoints only cleared when the camera reports them
        // stalled (clearing a working pipe loses a packet); reset when it is not ready.
        var ok = runCatching { prepare(forceReset = (deviceStatus()?.first ?: 0) != Ptp.OK) }.isSuccess &&
            deviceStatus()?.first == Ptp.OK
        // and prove it: one harmless transaction must come back in order
        if (ok) ok = runCatching { transact(Ptp.GET_STORAGE_IDS).code == Ptp.OK }.getOrDefault(false)
        note(if (ok) "$name 失败后已恢复，连接继续可用" else "$name 失败后无法恢复")
        log(if (ok) "${name} 被相机拒绝，已恢复连接" else "${name} 失败，连接无法恢复")
        recovered++
        return ok
    }

    /** How many times a failed transaction was recovered from (for the log). */
    @Volatile var recovered = 0
        private set

    /**
     * [transact], collecting the data phase; throws [PtpException] unless OK. Reads change
     * nothing on the camera, so one that failed on the wire is asked once more after recovery.
     */
    fun request(operation: Int, vararg params: Int, timeout: Int = 6000): ByteArray {
        val out = ByteArrayOutputStream()
        fun once(): Response = busyRetry(operation) {
            out.reset()
            transact(operation, params, sink = { _, b, o, n -> out.write(b, o, n) }, timeout = timeout)
        }
        val response = try {
            once()
        } catch (e: UsbException) {
            if (closed || broken != null) throw e
            once()
        }
        if (response.code != Ptp.OK) throw PtpException(operation, response.code)
        return out.toByteArray()
    }

    /** An operation without data; throws [PtpException] unless OK. */
    fun command(operation: Int, vararg params: Int, dataOut: ByteArray? = null): Response {
        val response = busyRetry(operation) { transact(operation, params, dataOut) }
        if (response.code != Ptp.OK) throw PtpException(operation, response.code)
        return response
    }

    /** Streams an object's data phase into [out]. */
    fun stream(operation: Int, params: IntArray, out: OutputStream, progress: (Long, Long) -> Unit, timeout: Int = 15000): Long {
        var got = 0L
        val response = transact(operation, params, sink = { total, b, o, n ->
            out.write(b, o, n)
            got += n
            progress(got, total)
        }, timeout = timeout)
        if (response.code != Ptp.OK) throw PtpException(operation, response.code)
        return got
    }

    // The camera answers "busy" for a while after a shot (writing the card): wait and ask again.
    private inline fun busyRetry(operation: Int, block: () -> Response): Response {
        var response = block()
        var waited = 0
        while (response.code == Ptp.DEVICE_BUSY && waited < 4000) {
            Thread.sleep(100)
            waited += 100
            response = block()
        }
        if (waited > 0) log("${Ptp.operationName(operation)} 等待相机 $waited ms")
        return response
    }

    /** One event from the interrupt endpoint, or null when none came within [timeout] ms. */
    fun readEvent(timeout: Int): Event? {
        val endpoint = interruptIn ?: return null
        if (closed || broken != null) return null
        val bytes = ByteArray(64)
        val n = connection.bulkTransfer(endpoint, bytes, bytes.size, timeout)
        if (n < 12) return null
        val bb = ByteBuffer.wrap(bytes, 0, n).order(ByteOrder.LITTLE_ENDIAN)
        val length = bb.int
        val type = bb.short.toInt() and 0xFFFF
        val code = bb.short.toInt() and 0xFFFF
        bb.int // transaction id
        if (type != Ptp.EVENT) return null
        val count = ((minOf(length, n) - 12) / 4).coerceIn(0, 3)
        return Event(code, IntArray(count) { bb.int })
    }

    // ---- getting the camera back to idle ---------------------------------------------------------

    /**
     * PTP class request Get Device Status: the camera's state (0x2001 ready, 0x2019 busy) and the
     * endpoints it has stalled. Null when the camera does not answer it.
     */
    fun deviceStatus(): Pair<Int, List<Int>>? {
        val b = ByteArray(32)
        val n = connection.controlTransfer(0xA1, 0x67, 0, usbInterface.id, b, b.size, 1000)
        if (n < 4) {
            note("Get Device Status：$n")
            return null
        }
        val bb = ByteBuffer.wrap(b, 0, n).order(ByteOrder.LITTLE_ENDIAN)
        val length = (bb.short.toInt() and 0xFFFF).coerceAtMost(n)
        val code = bb.short.toInt() and 0xFFFF
        val stalled = mutableListOf<Int>()
        while (bb.position() + 4 <= length) stalled += bb.int
        return code to stalled
    }

    /**
     * Brings the camera to idle before the first command: reads away anything it still had to
     * send, clears stalled endpoints, and resets the PTP channel when it is not ready. Returns
     * what was done, for the log.
     */
    fun prepare(forceReset: Boolean = false, clearPipes: Boolean = false): String = lock.withLock {
        val steps = mutableListOf<String>()
        val status = deviceStatus()
        steps += "状态 " + (status?.let { "0x%04X%s".format(it.first, if (it.second.isEmpty()) "" else "，停滞端点 " + it.second.joinToString { e -> "0x%02X".format(e) }) } ?: "无回应")
        status?.second?.forEach { ep -> steps += "清除端点 0x%02X 停滞：%d".format(ep, clearHalt(ep)) }
        if (clearPipes) steps += "清除端点：in ${clearHalt(bulkIn.address)}，out ${clearHalt(bulkOut.address)}"
        val drained = drain()
        if (drained > 0) steps += "读掉残留数据 $drained 字节"
        if (forceReset || status == null || status.first != Ptp.OK) {
            val r = connection.controlTransfer(0x21, 0x66, 0, usbInterface.id, null, 0, 2000)
            steps += "复位 PTP 通道：$r"
            var waited = 0
            while (waited < 3000) {
                val s = deviceStatus()
                if (s != null && s.first == Ptp.OK) break
                SystemClock.sleep(100)
                waited += 100
            }
            steps += "复位后状态 " + (deviceStatus()?.let { "0x%04X".format(it.first) } ?: "无回应") + "（等待 $waited ms）"
            val more = drain()
            if (more > 0) steps += "再读掉 $more 字节"
            transactionId = 0
            // a reset closes the session: open it again for the transactions that follow
            if (sessionOpen) {
                sessionOpen = false
                broken = null
                val r = runCatching { transact(Ptp.OPEN_SESSION, intArrayOf(1)).code }
                steps += "重新打开会话：" + (r.getOrNull()?.let { Ptp.responseName(it) } ?: r.exceptionOrNull()?.message)
            }
        }
        broken = null
        start = 0
        end = 0
        val text = steps.joinToString("；")
        note("准备：$text")
        text
    }

    private fun clearHalt(endpoint: Int): Int =
        connection.controlTransfer(0x02, 0x01 /* CLEAR_FEATURE */, 0 /* ENDPOINT_HALT */, endpoint, null, 0, 1000)

    private fun drain(): Int {
        var total = 0
        repeat(64) {
            val n = connection.bulkTransfer(bulkIn, buffer, 0, MIN_CHUNK, 60)
            if (n <= 0) return total
            total += n
        }
        return total
    }

    // ---- closing -------------------------------------------------------------------------------

    override fun close() {
        if (closed) return
        lock.withLock {
            closed = true
            runCatching { connection.releaseInterface(usbInterface) }
            runCatching { connection.close() }
        }
    }

    /** Closes without waiting for a transaction in progress (the cable was pulled). */
    fun abort() {
        closed = true
        runCatching { connection.close() }
    }

    /** The last USB steps (for the error log). */
    fun traceText(): String = synchronized(trace) { trace.joinToString("\n") }

    private fun note(text: String) {
        synchronized(trace) {
            trace.addLast(text)
            while (trace.size > 40) trace.removeFirst()
        }
    }

    // ---- bulk transport ------------------------------------------------------------------------

    private class Header(val length: Long, val type: Int, val code: Int)

    private fun container(type: Int, code: Int, tid: Int, params: IntArray, dataLength: Int): ByteArray {
        val length = 12 + params.size * 4
        val bb = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(if (type == Ptp.DATA) 12 + dataLength else length)
        bb.putShort(type.toShort())
        bb.putShort(code.toShort())
        bb.putInt(tid)
        params.forEach { bb.putInt(it) }
        return bb.array()
    }

    private fun send(bytes: ByteArray, timeout: Int) {
        var at = 0
        var writeErrors = 0
        while (at < bytes.size) {
            val len = minOf(bytes.size - at, chunk)
            val began = SystemClock.elapsedRealtime()
            val n = connection.bulkTransfer(bulkOut, bytes, at, len, timeout)
            val ms = SystemClock.elapsedRealtime() - began
            if (n <= 0) {
                if (closed) throw IOException("连接已关闭")
                if (ms < 30 && chunk > MIN_CHUNK && len > MIN_CHUNK) { smaller(); continue }
                // a command (12-32 bytes) is one packet: a wire error before it went is sent again
                if (len <= packet && ms < timeout - 50 && ++writeErrors <= 3) {
                    transferErrors++
                    SystemClock.sleep(30L * writeErrors)
                    continue
                }
                throw UsbException("$stage：USB 写入失败（返回 $n，${ms} ms，${if (ms >= timeout - 50) "超时：相机不收命令" else "立即失败"}）", stage, n, ms)
            }
            at += n
        }
    }

    private fun readHeader(timeout: Int): Header {
        val raw = ByteArray(12)
        readExact(raw, 12, timeout)
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val length = bb.int.toLong() and 0xFFFFFFFFL
        val type = bb.short.toInt() and 0xFFFF
        val code = bb.short.toInt() and 0xFFFF
        if (length < 12) throw UsbException("$stage：容器长度错误 $length", stage, length.toInt(), 0)
        return Header(length, type, code)
    }

    private fun readExact(dst: ByteArray, length: Int, timeout: Int) {
        var at = 0
        while (at < length) {
            if (start == end) fill(chunk, timeout)
            val take = minOf(end - start, length - at)
            System.arraycopy(buffer, start, dst, at, take)
            start += take
            at += take
        }
    }

    // Reads one bulk transfer of at most [max] bytes (a whole number of packets, so a full
    // packet never overflows it); zero-length packets are skipped.
    private fun fill(max: Int, timeout: Int) {
        start = 0
        end = 0
        var zero = 0
        var wireErrors = 0
        while (true) {
            val len = max.coerceAtMost(chunk)
            val began = SystemClock.elapsedRealtime()
            val n = connection.bulkTransfer(bulkIn, buffer, 0, len, timeout)
            val ms = SystemClock.elapsedRealtime() - began
            if (n > 0) {
                end = n
                return
            }
            if (closed) throw IOException("连接已关闭")
            if (n == 0 && ++zero < 4) continue
            // A transfer error on the wire (not a timeout): the host gave up on this read, but the
            // camera's data is still to come. Reading again keeps the stream in step; resetting
            // the pipe here would lose a packet and put every later container out of place.
            if (n < 0 && ms < timeout - 50 && ++wireErrors <= 6) {
                transferErrors++
                if (wireErrors == 1) note("$stage：USB 传输错误（${ms} ms），重读")
                SystemClock.sleep(20L * wireErrors)
                continue
            }
            if (n < 0 && ms < 30 && chunk > MIN_CHUNK) { smaller(); wireErrors = 0; continue }
            throw UsbException("$stage：USB 读取失败（返回 $n，${ms} ms，${if (ms >= timeout - 50) "超时：相机没有回应" else "立即失败"}，每次 ${len / 1024} KB）", stage, n, ms)
        }
    }

    /** Reads that failed on the wire and were read again (for the log). */
    @Volatile var transferErrors = 0
        private set

    private fun smaller() {
        chunk = MIN_CHUNK
        log("USB 大块传输被系统拒绝，改为每次 ${MIN_CHUNK / 1024} KB")
        note("改为 ${MIN_CHUNK / 1024} KB 传输")
    }

    private fun roundUp(n: Int): Int = ((n + packet - 1) / packet) * packet

    companion object {
        private const val MAX_CHUNK = 64 * 1024
        private const val MIN_CHUNK = 16 * 1024

        /** The PTP interface of a USB device, or null. */
        fun findInterface(device: UsbDevice): UsbInterface? {
            val all = (0 until device.interfaceCount).map { device.getInterface(it) }
            return all.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }
                ?: all.firstOrNull { i ->
                    val eps = (0 until i.endpointCount).map { i.getEndpoint(it) }
                    eps.count { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK } >= 2 &&
                        eps.any { it.type == UsbConstants.USB_ENDPOINT_XFER_INT }
                }
        }

        /** The USB device as the phone sees it (for the error log). */
        fun describe(device: UsbDevice): String = buildString {
            append("%s %s（VID 0x%04X PID 0x%04X，%s）\n".format(device.manufacturerName ?: "?", device.productName ?: "?",
                device.vendorId, device.productId, device.deviceName))
            for (i in 0 until device.interfaceCount) {
                val f = device.getInterface(i)
                append("  接口 ${f.id}：类 ${f.interfaceClass}/${f.interfaceSubclass}/${f.interfaceProtocol}，${f.endpointCount} 个端点\n")
                for (k in 0 until f.endpointCount) {
                    val e = f.getEndpoint(k)
                    val type = when (e.type) {
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
                        else -> "type ${e.type}"
                    }
                    append("    0x%02X %s %s，包 %d\n".format(e.address, type, if (e.direction == UsbConstants.USB_DIR_IN) "in" else "out", e.maxPacketSize))
                }
            }
        }

        fun open(manager: UsbManager, device: UsbDevice, log: (String) -> Unit): PtpConnection {
            val usbInterface = findInterface(device) ?: throw IOException("这个 USB 设备不是 PTP 相机")
            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            var interrupt: UsbEndpoint? = null
            for (i in 0 until usbInterface.endpointCount) {
                val e = usbInterface.getEndpoint(i)
                when {
                    e.type == UsbConstants.USB_ENDPOINT_XFER_BULK && e.direction == UsbConstants.USB_DIR_IN -> bulkIn = e
                    e.type == UsbConstants.USB_ENDPOINT_XFER_BULK && e.direction == UsbConstants.USB_DIR_OUT -> bulkOut = e
                    e.type == UsbConstants.USB_ENDPOINT_XFER_INT && e.direction == UsbConstants.USB_DIR_IN -> interrupt = e
                }
            }
            if (bulkIn == null || bulkOut == null) throw IOException("相机的 USB 接口缺少数据端点")
            val connection = manager.openDevice(device) ?: throw IOException("无法打开 USB 设备（没有权限？）")
            if (!connection.claimInterface(usbInterface, true)) {
                connection.close()
                throw IOException("USB 接口被占用（关闭手机的文件管理或其他相机应用后重试）")
            }
            log("USB：包大小 ${bulkIn.maxPacketSize}，事件端点 ${if (interrupt != null) "有" else "无"}")
            return PtpConnection(connection, usbInterface, bulkIn, bulkOut, interrupt, log)
        }
    }
}
