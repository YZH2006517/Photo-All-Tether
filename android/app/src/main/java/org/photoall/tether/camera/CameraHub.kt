package org.photoall.tether.camera

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import org.photoall.tether.ErrorLog
import org.photoall.tether.develop.DevelopQueue
import org.photoall.tether.develop.Prefs
import org.photoall.tether.ptp.DeviceInfo
import org.photoall.tether.ptp.ObjectInfo
import org.photoall.tether.ptp.Ptp
import org.photoall.tether.ptp.PtpConnection
import org.photoall.tether.ptp.PtpException
import org.photoall.tether.ptp.PtpReader
import org.photoall.tether.ptp.StorageInfo
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The camera on the USB port: connection, the files on its cards, its events and its
 * settings. One per process; screens listen with [addListener] (called on the main thread).
 */
object CameraHub {
    enum class State { DISCONNECTED, WAITING_PERMISSION, CONNECTING, CONNECTED, ERROR }

    /** What the camera is set to (from its device properties), null when it does not say. */
    class Settings(
        val battery: Int? = null,
        val aperture: Float? = null,
        val shutter: String? = null,
        val iso: Int? = null,
        val bias: Float? = null,
        val program: String? = null,
    )

    @Volatile var state = State.DISCONNECTED
        private set
    @Volatile var message: String = "用数据线连接相机"
        private set
    @Volatile var device: DeviceInfo? = null
        private set
    /** When this connection opened (ms), 0 when not connected. */
    @Volatile var connectedAt = 0L
        private set
    /** The last transfer off the camera: MB/s, 0 before the first. */
    @Volatile var lastSpeed = 0.0

    /** The open connection's USB statistics for the info page, or null. */
    fun usbStats(): String? {
        val c = connection ?: return null
        val d = usbDevice
        return buildString {
            d?.let { appendLine("%s（VID 0x%04X · PID 0x%04X）".format(it.productName ?: it.deviceName, it.vendorId, it.productId)) }
            appendLine("包大小 ${c.packetSize} 字节（${if (c.packetSize >= 1024) "USB 3 SuperSpeed" else "USB 2 High Speed"}），每次传输 ${c.chunkSize / 1024} KB")
            append("事务 ${c.transactions} 次，收到 %.1f MB".format(c.bytesIn / 1048576.0))
            if (lastSpeed > 0) append("，最近一次传输 %.1f MB/s".format(lastSpeed))
            appendLine()
            append("传输错误重读 ${c.transferErrors} 次，出错后恢复 ${c.recovered} 次")
        }
    }

    /**
     * Reads device properties (blocking, on the camera thread's turn): code to raw value bytes.
     * [codes] defaults to every property the camera lists.
     */
    fun readProps(codes: Collection<Int>? = null): Map<Int, ByteArray> {
        val c = connection ?: return emptyMap()
        val list = codes ?: device?.properties?.sorted() ?: return emptyMap()
        val out = LinkedHashMap<Int, ByteArray>()
        for (code in list) {
            if (c.broken != null) break
            runCatching { c.request(Ptp.GET_DEVICE_PROP_VALUE, code) }.getOrNull()?.let { out[code] = it }
        }
        return out
    }

    /** How this maker's camera is driven (events, set-up, hints). */
    @Volatile var profile: Vendors.Profile? = null
        private set
    @Volatile var storages: List<StorageInfo> = emptyList()
        private set
    @Volatile var settings = Settings()
        private set
    @Volatile var items: List<CameraItem> = emptyList()
        private set
    /** Listing the cards: files read so far, of [listTotal]. */
    @Volatile var listing = false
        private set
    @Volatile var listDone = 0
        private set
    @Volatile var listTotal = 0
        private set
    /** Shots that arrived while connected, newest last (the tethered roll). */
    val arrivals = mutableListOf<String>()

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()
    private val io = Executors.newSingleThreadExecutor { Thread(it, "camera-io") }
    private val thumbPool = Executors.newFixedThreadPool(2) { Thread(it, "camera-thumb").apply { priority = Thread.MIN_PRIORITY } }
    private val files = ConcurrentHashMap<Int, ObjectInfo>()
    private val thumbs = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val thumbsLoading = ConcurrentHashMap.newKeySet<String>()
    private val noThumb = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var connection: PtpConnection? = null
    @Volatile private var usbDevice: UsbDevice? = null
    @Volatile private var generation = 0
    @Volatile private var eventsSeen = false
    private val log = ArrayDeque<String>()

    // ---- set-up ------------------------------------------------------------------------------

    fun init(context: Context) {
        app = context.applicationContext
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_PERMISSION)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val dev = deviceOf(intent)
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (dev != null && state != State.CONNECTED) connect(dev)
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> if (dev != null && dev.deviceName == usbDevice?.deviceName) lost("相机已断开")
                    ACTION_PERMISSION -> {
                        if (dev != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect(dev)
                        else setState(State.ERROR, "没有得到 USB 权限：重新插线，或点「连接相机」后允许")
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(receiver, filter)
    }

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    private fun changed() = main.post { listeners.toList().forEach { it() } }

    private fun setState(s: State, text: String) {
        state = s
        message = text
        note(text)
        changed()
        TetherService.update(app)
    }

    fun deviceOf(intent: Intent?): UsbDevice? = intent?.let {
        if (Build.VERSION.SDK_INT >= 33) it.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") it.getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    /** A camera already on the port (the app opened by hand), or null. */
    fun findCamera(): UsbDevice? {
        val manager = app.getSystemService(UsbManager::class.java) ?: return null
        return manager.deviceList.values.firstOrNull { PtpConnection.findInterface(it) != null }
    }

    /** Connects to [dev], or to the first camera on the port; asks for USB permission first. */
    fun connect(dev: UsbDevice? = null) {
        val target = dev ?: findCamera()
        if (target == null) {
            setState(State.DISCONNECTED, "没有找到相机：用数据线连接相机并开机（USB 设为 MTP/PTP）")
            return
        }
        if (state == State.CONNECTED && target.deviceName == usbDevice?.deviceName) return
        // the attach broadcast, the permission answer and the activity can all ask at once
        if (state == State.CONNECTING && target.deviceName == connectingName) return
        val manager = app.getSystemService(UsbManager::class.java)
        if (!manager.hasPermission(target)) {
            setState(State.WAITING_PERMISSION, "请允许访问 ${target.productName ?: "相机"}")
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(app, 0, Intent(ACTION_PERMISSION).setPackage(app.packageName), flags)
            manager.requestPermission(target, pi)
            return
        }
        val gen = ++generation
        connectingName = target.deviceName
        setState(State.CONNECTING, "正在连接 ${target.productName ?: "相机"}…")
        io.execute { open(target, gen) }
    }

    fun disconnect() {
        generation++
        val p = profile
        val d = device
        connection?.let { c -> io.execute { Vendors.release(c, p, d); runCatching { c.transact(Ptp.CLOSE_SESSION) }; c.close() } }
        connection = null
        usbDevice = null
        clearCamera()
        setState(State.DISCONNECTED, "已断开")
    }

    private var lastReconnect = 0L
    @Volatile private var connectingName: String? = null

    // The connection could not be recovered in place: open it again (once in 15 s; then it
    // stays disconnected and the user reconnects).
    private fun reconnect(text: String) {
        val target = usbDevice
        lost(text)
        val now = System.currentTimeMillis()
        if (target != null && now - lastReconnect > 15000) {
            lastReconnect = now
            note("自动重新连接…")
            connect(target)
        }
    }

    private fun lost(text: String) {
        generation++
        connection?.abort()
        connection = null
        usbDevice = null
        clearCamera()
        setState(State.DISCONNECTED, text)
    }

    private fun clearCamera() {
        connectedAt = 0L
        device = null
        profile = null
        storages = emptyList()
        items = emptyList()
        files.clear()
        settings = Settings()
        listing = false
        synchronized(arrivals) { arrivals.clear() }
    }

    private fun open(target: UsbDevice, gen: Int) {
        try {
            connection?.abort()
            val manager = app.getSystemService(UsbManager::class.java)
            note("设备：" + PtpConnection.describe(target).trim().replace("\n", " | "))
            val c = PtpConnection.open(manager, target, ::note)
            connection = c
            usbDevice = target
            c.prepare()
            // A camera left mid-transaction (by another program or a failed attempt) answers
            // nothing: reset its PTP channel and ask once more.
            val info = try {
                DeviceInfo.parse(c.request(Ptp.GET_DEVICE_INFO))
            } catch (first: java.io.IOException) {
                ErrorLog.record("连接：第一次 GetDeviceInfo 失败，复位后重试", first, usbReport(target, c))
                c.prepare(forceReset = true)
                DeviceInfo.parse(c.request(Ptp.GET_DEVICE_INFO))
            }
            device = info
            note("相机：${info.manufacturer} ${info.model}，固件 ${info.version}，厂商扩展 ${info.vendorExtension.ifEmpty { "-" }}")
            note("支持：${info.operations.size} 种操作，${info.events.size} 种事件；Nikon GetEvent ${yes(info.supports(Ptp.NIKON_GET_EVENT))}，大缩略图 ${yes(info.supports(Ptp.NIKON_GET_LARGE_THUMB))}，分段读取 ${yes(info.supports(Ptp.GET_PARTIAL_OBJECT))}")
            val opened = c.transact(Ptp.OPEN_SESSION, intArrayOf(1))
            if (opened.code != Ptp.OK && opened.code != Ptp.SESSION_ALREADY_OPEN) throw PtpException(Ptp.OPEN_SESSION, opened.code)
            if (gen != generation) return
            eventsSeen = false
            // Just switched on, the Z 6II answers with a short operation list and refuses its
            // cards for a few seconds: wait for it, then read what it can really do.
            var waited = 0
            while (true) {
                try {
                    refreshStorages()
                    break
                } catch (e: PtpException) {
                    if (gen != generation) return
                    if (e.code != Ptp.ACCESS_DENIED && e.code != Ptp.DEVICE_BUSY || waited >= 15000) {
                        // Sony PC Remote and some tether modes keep the cards to themselves:
                        // carry on without them, new shots still come
                        note("读不到存储卡（${e.message}），只接收新拍的照片")
                        storages = emptyList()
                        break
                    }
                    if (waited == 0) setState(State.CONNECTING, "相机正在启动，等它准备好…")
                    Thread.sleep(500)
                    waited += 500
                }
            }
            if (waited > 0) {
                device = DeviceInfo.parse(c.request(Ptp.GET_DEVICE_INFO))
                note("相机准备好了（等待 $waited ms）：${device?.operations?.size} 种操作")
            }
            // the maker's own set-up (Canon remote mode, Sony PC Remote handshake, ...)
            val brand = Vendors.brand(target.vendorId, device)
            val (p, di) = Vendors.prepare(c, device ?: info, brand, ::note)
            profile = p
            device = di
            note("品牌：${brand.label}；新照片：" + listOfNotNull(
                if (p.pollsEvents) "轮询相机事件" else null,
                if (p.interruptEvents) "USB 事件" else null,
                if (p.listsForNew) "定时查找新文件" else null,
                if (p.sonyRemote) "相机内存（PC 遥控）" else null,
            ).joinToString("、"))
            refreshSettings()
            connectedAt = System.currentTimeMillis()
            setState(State.CONNECTED, "${device?.model ?: info.model} 已连接")
            startEvents(c, gen)
            listAll(gen)
        } catch (e: Exception) {
            ErrorLog.record("连接相机失败", e, usbReport(target, connection))
            if (gen == generation) {
                connection?.abort()
                connection = null
                setState(State.ERROR, "连接失败：${e.message ?: e}")
            }
        }
    }

    private fun yes(b: Boolean) = if (b) "有" else "无"

    /** The USB device and the last USB steps, for the error log. */
    private fun usbReport(target: UsbDevice?, c: PtpConnection?): String = buildString {
        target?.let { append(PtpConnection.describe(it)) }
        val trace = c?.traceText().orEmpty()
        if (trace.isNotEmpty()) append("-- USB 步骤\n").append(trace).append('\n')
        device?.let { append("相机：${it.manufacturer} ${it.model} 固件 ${it.version}\n") }
    }

    /** For other screens' error reports: the USB steps so far. */
    fun usbTrace(): String = usbReport(usbDevice, connection)

    // ---- cards and files -----------------------------------------------------------------------

    private fun refreshStorages() {
        val c = connection ?: return
        val ids = PtpReader(c.request(Ptp.GET_STORAGE_IDS)).u32Array()
        storages = ids.filter { (it and 0xFFFF) != 0 }.mapNotNull { id ->
            runCatching { StorageInfo.parse(id, c.request(Ptp.GET_STORAGE_INFO, id)) }
                .onFailure { note("读取存储卡 0x%08X 失败：%s".format(id, it.message)) }.getOrNull()
        }
        changed()
    }

    /** Reads every file on every card (folders walked from the root). */
    fun refresh() {
        val gen = generation
        if (state == State.CONNECTED) io.execute { runCatching { refreshStorages() }; listAll(gen) }
    }

    private fun listAll(gen: Int) {
        val c = connection ?: return
        listing = true
        listDone = 0
        listTotal = 0
        changed()
        val found = HashMap<Int, ObjectInfo>()
        val started = System.currentTimeMillis()
        try {
            for (storage in storages) {
                val queue = ArrayDeque<Pair<Int, Int>>() // parent, depth
                queue += -1 to 0 // 0xFFFFFFFF: the root
                while (queue.isNotEmpty() && gen == generation) {
                    val (parent, depth) = queue.removeFirst()
                    val handles = PtpReader(c.request(Ptp.GET_OBJECT_HANDLES, storage.id, 0, parent)).u32Array()
                    listTotal += handles.size
                    for (h in handles) {
                        if (gen != generation) return
                        val info = files[h] ?: ObjectInfo.parse(h, c.request(Ptp.GET_OBJECT_INFO, h))
                        listDone++
                        if (info.isFolder) {
                            if (depth < 4) queue += h to depth + 1
                        } else if (info.isRaw || info.isJpeg || info.isHeif || info.isVideo) {
                            found[h] = info
                        }
                        if (listDone % 60 == 0) publish(found)
                    }
                }
            }
            if (gen != generation) return
            files.clear()
            files.putAll(found)
            publish(found)
            note("读取完成：${found.size} 个文件，${items.size} 张，%.1f 秒".format((System.currentTimeMillis() - started) / 1000.0))
        } catch (e: Exception) {
            note("读取文件列表失败：${e.message}")
            ErrorLog.record("读取存储卡文件列表", e, usbReport(usbDevice, connection))
            if (connection?.broken != null) main.post { if (gen == generation) reconnect("连接中断：${e.message}") }
        } finally {
            listing = false
            changed()
        }
    }

    private fun publish(found: Map<Int, ObjectInfo>) {
        items = CameraItem.group(found.values)
        changed()
    }

    // ---- events --------------------------------------------------------------------------------

    private fun startEvents(c: PtpConnection, gen: Int) {
        val p = profile ?: return
        // One channel for events where the maker has its own (Nikon / Canon GetEvent): the Z 6II
        // also sends every event on the interrupt pipe, reading both doubles the USB traffic.
        if (p.interruptEvents && c.hasInterruptEndpoint) Thread({
            while (gen == generation && c.broken == null) {
                val e = c.readEvent(1000) ?: continue
                onEvent(e.code, e.params.firstOrNull() ?: 0, "USB 事件")
            }
        }, "camera-events").start()
        Thread({
            var lastSettings = 0L
            var lastScan = 0L
            var lastAlive = 0L
            while (gen == generation && c.broken == null) {
                try {
                    if (p.pollsEvents) Vendors.poll(c, p).forEach { (code, param) -> onEvent(code, param, "${p.brand.label}事件") }
                    // Sony PC Remote: a shot waits in the camera's memory until it is read
                    if (p.sonyRemote && Vendors.sonyShotWaiting(c)) memoryShots(c)
                    val now = System.currentTimeMillis()
                    if (now - lastSettings > 30000) {
                        lastSettings = now
                        refreshSettings()
                    }
                    if (now - lastAlive > 60000) {
                        lastAlive = now
                        device?.let { Vendors.keepAlive(c, p, it) }
                    }
                    // Cameras that tell of no new files (Fujifilm), or none yet this session:
                    // list the handles and take whatever is new.
                    val scan = p.listsForNew || (!eventsSeen && !p.sonyRemote)
                    if (scan && Prefs.tether(app) && !listing && now - lastScan > (if (p.listsForNew) 1000 else 3000)) {
                        lastScan = now
                        scanForNew()
                    }
                    Thread.sleep(if (p.pollsEvents || p.sonyRemote) 400 else 1000)
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (e: Exception) {
                    note("事件轮询：${e.message}")
                    ErrorLog.record("事件轮询", e, usbReport(usbDevice, c))
                    if (c.broken != null) {
                        main.post { if (gen == generation) reconnect("连接中断：${c.broken}") }
                        return@Thread
                    }
                    Thread.sleep(1000)
                }
            }
        }, "camera-poll").start()
    }

    private var memoryCount = 0

    // Sony PC Remote: read every shot waiting in the camera's memory (RAW+JPEG is two) into the
    // phone at once (the next shot takes the same handle), list it under its own handle.
    private fun memoryShots(c: PtpConnection) {
        var guard = 0
        do {
            val info = ObjectInfo.parse(Vendors.SONY_MEMORY_HANDLE, c.request(Ptp.GET_OBJECT_INFO, Vendors.SONY_MEMORY_HANDLE))
            val file = runCatching { fetchToCache(info) }.onFailure {
                note("读取相机内存里的照片失败：${it.message}")
                ErrorLog.record("索尼：读取内存中的照片", it, usbReport(usbDevice, c))
            }.getOrNull() ?: return
            val local = ObjectInfo(MEMORY_BASE + memoryCount++, 0, info.format, 0, info.size, info.thumbFormat,
                info.imageWidth, info.imageHeight, 0, 0, info.filename, info.captureDate.ifEmpty {
                    java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).format(Date())
                })
            // the thumbnail from the file itself (no card object to ask)
            thumbFromFile(local, file)
            files[local.handle] = local
            publish(HashMap(files))
            note("新拍（相机内存）：${info.filename}（${info.size / 1024} KB）")
            newShot(local)
        } while (++guard < 4 && Vendors.sonyShotWaiting(c))
    }

    private fun thumbFromFile(info: ObjectInfo, file: File) {
        runCatching {
            val bytes = if (info.isRaw) org.photoall.tether.engine.RawFile.embeddedJpeg(file.path) else file.readBytes()
            bytes ?: return
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 16 }) ?: return
            val bmp = upright(raw, runCatching {
                android.media.ExifInterface(file.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
            }.getOrDefault(1))
            diskThumb(info.key).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            thumbs.put(info.key, bmp)
        }
    }

    /** Files this app keeps for shots that were never on a card (Sony PC Remote). */
    private fun isLocal(handle: Int) = handle in MEMORY_BASE until MEMORY_BASE + 0x100000

    private val recentEvents = LinkedHashMap<Long, Long>()
    @Volatile private var settingsQueued = false
    private val shownProps = setOf(Ptp.PROP_BATTERY_LEVEL, Ptp.PROP_F_NUMBER, Ptp.PROP_EXPOSURE_TIME,
        Ptp.PROP_EXPOSURE_PROGRAM, Ptp.PROP_EXPOSURE_INDEX, Ptp.PROP_EXPOSURE_BIAS)

    private fun onEvent(code: Int, param: Int, source: String) {
        // The Z 6II tells each event twice (USB interrupt and Nikon GetEvent): once is enough.
        val id = (code.toLong() shl 32) or (param.toLong() and 0xFFFFFFFFL)
        val now = System.currentTimeMillis()
        synchronized(recentEvents) {
            recentEvents.entries.removeAll { now - it.value > 2000 }
            if (recentEvents.containsKey(id)) return
            recentEvents[id] = now
        }
        if (code == Ptp.EVENT_DEVICE_PROP_CHANGED) {
            // the camera reports some setting changing every second: read ours, at most every 2 s
            if (param in shownProps && !settingsQueued) {
                settingsQueued = true
                main.postDelayed({
                    settingsQueued = false
                    io.execute { runCatching { refreshSettings() } }
                }, 2000)
            }
            return
        }
        note("$source：${Ptp.eventName(code)} 0x%08X".format(param))
        when (code) {
            Ptp.EVENT_OBJECT_ADDED, Ptp.NIKON_EVENT_OBJECT_ADDED_IN_SDRAM -> {
                eventsSeen = true
                val handle = if (code == Ptp.NIKON_EVENT_OBJECT_ADDED_IN_SDRAM && param == 0) Ptp.NIKON_SDRAM_HANDLE else param
                io.execute { added(handle) }
            }
            Ptp.EVENT_OBJECT_REMOVED -> {
                if (files.remove(param) != null) publish(HashMap(files))
            }
            Ptp.EVENT_STORE_ADDED, Ptp.EVENT_STORE_REMOVED -> refresh()
            Ptp.EVENT_STORAGE_INFO_CHANGED, Ptp.EVENT_STORE_FULL -> io.execute { runCatching { refreshStorages() } }
            Ptp.EVENT_OBJECT_INFO_CHANGED -> io.execute {
                runCatching { files[param] = ObjectInfo.parse(param, connection!!.request(Ptp.GET_OBJECT_INFO, param)) }
                publish(HashMap(files))
            }
        }
    }

    private val skipped = java.util.Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    private fun added(handle: Int) {
        val c = connection ?: return
        if (files.containsKey(handle)) return
        val info = runCatching { ObjectInfo.parse(handle, c.request(Ptp.GET_OBJECT_INFO, handle)) }
            .onFailure {
                note("新文件 0x%08X 读取失败：%s".format(handle, it.message))
                ErrorLog.record("读取新拍的照片信息", it, usbReport(usbDevice, c))
            }.getOrNull() ?: return
        if (info.isFolder || !(info.isRaw || info.isJpeg || info.isHeif || info.isVideo)) return
        files[handle] = info
        publish(HashMap(files))
        note("新拍：${info.filename}（${info.size / 1024} KB）")
        newShot(info)
    }

    // Lists the folders that hold pictures and takes whatever is new (cameras that send no events).
    private fun scanForNew() {
        val c = connection ?: return
        // every handle on every card in one go (what libgphoto2 does for Fujifilm)
        val all = runCatching { PtpReader(c.request(Ptp.GET_OBJECT_HANDLES, -1, 0, 0)).u32Array() }.getOrNull()
        if (all != null) {
            for (h in all) if (!files.containsKey(h) && h !in skipped) {
                val before = files.size
                added(h)
                if (files.size == before) skipped += h // a folder, or a file we do not list
            }
            return
        }
        val folders = files.values.map { it.storageId to it.parent }.distinct()
        for ((storage, parent) in folders) {
            val handles = PtpReader(c.request(Ptp.GET_OBJECT_HANDLES, storage, 0, parent)).u32Array()
            for (h in handles) if (!files.containsKey(h)) added(h)
        }
    }

    private fun newShot(info: ObjectInfo) {
        synchronized(arrivals) { arrivals += info.key }
        // the roll's frame counter: one frame per shot (a RAW+JPEG pair counts once, by its raw)
        if (info.isRaw) org.photoall.tether.develop.Roll.shot(app)
        else if (info.isJpeg) main.postDelayed({
            val now = items.firstOrNull { it.files.any { f -> f.handle == info.handle } }
            if (now == null || now.raw == null) org.photoall.tether.develop.Roll.shot(app)
        }, 2500)
        if (!Prefs.tether(app)) return
        val item = items.firstOrNull { it.files.any { f -> f.handle == info.handle } } ?: CameraItem(listOf(info))
        // RAW+JPEG: develop once, from the raw (the JPEG of the pair arrives first or after it)
        if (info.isRaw) DevelopQueue.addCamera(app, item, develop = true, auto = true)
        else if (info.isJpeg && Prefs.developJpeg(app)) main.postDelayed({
            val now = items.firstOrNull { it.files.any { f -> f.handle == info.handle } }
            if (now != null && now.raw == null) DevelopQueue.addCamera(app, now, develop = true, auto = true)
        }, 2500)
    }

    // ---- camera settings -----------------------------------------------------------------------

    private fun refreshSettings() {
        val c = connection ?: return
        val props = device?.properties ?: return
        fun raw(code: Int): ByteArray? = if (code in props) runCatching { c.request(Ptp.GET_DEVICE_PROP_VALUE, code) }.getOrNull() else null
        fun uint(code: Int): Long? = raw(code)?.let { b ->
            when (b.size) {
                1 -> (b[0].toLong() and 0xFF)
                2 -> PtpReader(b).u16().toLong()
                4 -> PtpReader(b).u32()
                else -> null
            }
        }
        val battery = uint(Ptp.PROP_BATTERY_LEVEL)?.toInt()
        val f = uint(Ptp.PROP_F_NUMBER)?.let { it / 100f }
        val t = uint(Ptp.PROP_EXPOSURE_TIME)
        val iso = uint(Ptp.PROP_EXPOSURE_INDEX)?.toInt()
        val bias = raw(Ptp.PROP_EXPOSURE_BIAS)?.takeIf { it.size == 2 }?.let { PtpReader(it).s16() / 1000f }
        val program = uint(Ptp.PROP_EXPOSURE_PROGRAM)?.let {
            when (it.toInt()) { 1 -> "M"; 2 -> "P"; 3 -> "A"; 4 -> "S"; else -> null }
        }
        settings = Settings(battery, f?.takeIf { it > 0 }, t?.let(::shutterText), iso?.takeIf { it in 25..409600 }, bias, program)
        changed()
    }

    private fun shutterText(t: Long): String? = when {
        t == 0xFFFFFFFFL || t == 0x7FFFFFFFL -> "B"
        t <= 0 -> null
        t >= 3000 -> if (t % 10000 == 0L) "${t / 10000}″" else "%.1f″".format(t / 10000.0)
        else -> "1/${Math.round(10000.0 / t)}"
    }

    // ---- files ---------------------------------------------------------------------------------

    /** The cached thumbnail of a file, or null (then [loadThumb] fetches it). */
    fun cachedThumb(info: ObjectInfo): Bitmap? = thumbs.get(info.key)

    /**
     * Fetches a thumbnail, upright, and keeps it on the phone. A shot the camera is still writing
     * has no thumbnail yet ("no thumbnail" right after ObjectAdded): asked again a few seconds
     * later, and taken from the original when that is on the phone already.
     */
    fun loadThumb(info: ObjectInfo, attempt: Int = 0, done: (Bitmap?) -> Unit) {
        val key = info.key
        thumbs.get(key)?.let { done(it); return }
        if (key in noThumb || !thumbsLoading.add(key)) return
        thumbPool.execute {
            var failed = false
            val bitmap = try {
                val disk = diskThumb(key)
                if (disk.exists()) BitmapFactory.decodeFile(disk.path)
                else (fromCamera(info) ?: fromOriginal(info))?.also { bmp ->
                    runCatching { disk.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) } }
                }
            } catch (e: Exception) {
                failed = true
                null
            } finally {
                thumbsLoading -= key
            }
            if (bitmap != null) thumbs.put(key, bitmap)
            else if (failed || attempt < 4) {
                if (attempt < 4) main.postDelayed({ loadThumb(info, attempt + 1, done) }, 2500L * (attempt + 1))
                else noThumb += key
            }
            main.post { done(bitmap) }
        }
    }

    // The camera's own thumbnail, turned upright by the orientation in the file's EXIF.
    private fun fromCamera(info: ObjectInfo): Bitmap? {
        val c = connection ?: return null
        if (isLocal(info.handle)) return null
        val bytes = runCatching { c.request(Ptp.GET_THUMB, info.handle) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return upright(bmp, orientationOf(info))
    }

    // A thumbnail from the original already on the phone (cache, or imported to the library).
    private fun fromOriginal(info: ObjectInfo): Bitmap? {
        val file = cachedOriginal(info) ?: return null
        val bytes = if (info.isRaw) org.photoall.tether.engine.RawFile.embeddedJpeg(file.path) else file.readBytes()
        bytes ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 320) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val o = runCatching { android.media.ExifInterface(file.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) }
            .getOrDefault(1)
        return upright(bmp, o)
    }

    private val orientations = ConcurrentHashMap<String, Int>()

    /**
     * The EXIF orientation of a camera file (1 when unknown), read from its first 32 KB: a JPEG's
     * APP1 or a raw's TIFF header carry it near the start. Thumbnails from the camera have none.
     */
    fun orientationOf(info: ObjectInfo): Int {
        orientations[info.key]?.let { return it }
        val c = connection ?: return 1
        if (device?.supports(Ptp.GET_PARTIAL_OBJECT) != true || isLocal(info.handle)) return 1
        val head = runCatching { c.request(Ptp.GET_PARTIAL_OBJECT, info.handle, 0, 32 * 1024) }.getOrNull() ?: return 1
        return ExifOrientation.read(head).also { orientations[info.key] = it }
    }

    private fun upright(bmp: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> return bmp }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(degrees) }, true)
    }

    /** A thumbnail kept on the phone for a camera file key (also used by the library), upright. */
    fun diskThumb(key: String): File =
        File(File(app.cacheDir, "thumbs2").apply { mkdirs() }, "%08x_%08x.jpg".format(key.hashCode(), key.reversed().hashCode()))

    /**
     * A large preview of a shot (blocking, [maxEdge] pixels at most): the camera's JPEG read
     * whole, or the full-size JPEG inside the NEF (the NEF is copied to the phone for it and
     * kept, so importing or developing it next needs no second transfer). Nikon's own
     * GetLargeThumb is not used: the Z 6II stalls the USB pipe on it.
     */
    fun preview(item: CameraItem, maxEdge: Int): Bitmap? {
        val c = connection ?: return null
        try {
            val jpeg = item.jpeg
            if (jpeg != null) {
                val file = cachedOriginal(jpeg) ?: fetchToCache(jpeg)
                return decodeUpright(file, null, maxEdge)
            }
            val raw = item.raw
            if (raw != null) {
                val file = cachedOriginal(raw) ?: fetchToCache(raw)
                val bytes = org.photoall.tether.engine.RawFile.embeddedJpeg(file.path)
                if (bytes != null) return decodeUpright(file, bytes, maxEdge)
            }
        } catch (e: Exception) {
            note("大图预览：${e.message}")
            ErrorLog.record("读取大图预览 ${item.name}", e, usbReport(usbDevice, connection))
        }
        return runCatching { c.request(Ptp.GET_THUMB, item.primary.handle) }.getOrNull()
            ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    /** A camera file already copied to the phone this session (whole), or null. */
    fun cachedOriginal(info: ObjectInfo): File? =
        originalCacheFile(info).takeIf { it.exists() && it.length() == info.size }

    private fun originalCacheFile(info: ObjectInfo): File {
        val dir = File(app.cacheDir, "camera").apply { mkdirs() }
        return File(dir, "%08x_%s".format(info.key.hashCode(), info.filename.replace(Regex("[^A-Za-z0-9._-]"), "_")))
    }

    // Copies a camera file into the cache (keeping the newest few) and returns it.
    private fun fetchToCache(info: ObjectInfo): File {
        val file = originalCacheFile(info)
        val part = File(file.path + ".part")
        part.outputStream().buffered(1 shl 20).use { out -> download(info, out) { _, _ -> } }
        if (part.length() != info.size) throw IllegalStateException("${info.filename} 只收到 ${part.length()} / ${info.size} 字节")
        part.renameTo(file)
        file.parentFile?.listFiles()?.filter { !it.name.endsWith(".part") }?.sortedByDescending { it.lastModified() }
            ?.drop(6)?.forEach { it.delete() }
        return file
    }

    // Decodes a JPEG (the file itself, or [bytes] taken from it) at most [maxEdge] wide,
    // turned upright from the file's EXIF orientation.
    private fun decodeUpright(file: File, bytes: ByteArray?, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) else BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = (if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) else BitmapFactory.decodeFile(file.path, opts))
            ?: return null
        val degrees = runCatching {
            when (android.media.ExifInterface(file.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
            }
        }.getOrDefault(0f)
        if (degrees == 0f) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, android.graphics.Matrix().apply { postRotate(degrees) }, true)
    }

    /** Copies a file from the camera into [out] (blocking); [progress] gets bytes so far and the total. */
    fun download(info: ObjectInfo, out: OutputStream, progress: (Long, Long) -> Unit) {
        val c = connection ?: throw IllegalStateException("相机未连接")
        cachedOriginal(info)?.let { cached ->
            cached.inputStream().use { it.copyTo(out, 1 shl 20) }
            progress(info.size, info.size)
            return
        }
        if (isLocal(info.handle)) throw IllegalStateException("${info.filename} 已不在相机内存里")
        // pictures held in camera memory (Nikon SDRAM, Sony PC Remote) are read whole
        val memory = (info.handle.toLong() and 0xFFFF0000L) == 0xFFFF0000L
        val partial = device?.supports(Ptp.GET_PARTIAL_OBJECT) == true && !memory && info.size > 0
        if (!partial) {
            c.stream(Ptp.GET_OBJECT, intArrayOf(info.handle), out, progress)
            return
        }
        // In pieces: thumbnails and events get the camera in between.
        var offset = 0L
        while (offset < info.size) {
            val want = minOf(CHUNK.toLong(), info.size - offset).toInt()
            val before = offset
            val got = c.stream(Ptp.GET_PARTIAL_OBJECT, intArrayOf(info.handle, offset.toInt(), want), out,
                { n, _ -> progress(before + n, info.size) })
            if (got <= 0) throw IllegalStateException("相机没有发来数据")
            offset += got
        }
    }

    /** Deletes the files of [items] on the camera (blocking). Returns the errors, empty when all went. */
    fun delete(items: List<CameraItem>): List<String> {
        val c = connection ?: return listOf("相机未连接")
        val errors = mutableListOf<String>()
        for (item in items) for (f in item.files) {
            try {
                if (!isLocal(f.handle)) c.command(Ptp.DELETE_OBJECT, f.handle, 0)
                files.remove(f.handle)
                note("已删除 ${f.filename}")
            } catch (e: Exception) {
                errors += "${f.filename}：${e.message}"
                ErrorLog.record("删除 ${f.filename}", e)
            }
        }
        publish(HashMap(files))
        runCatching { refreshStorages() }
        return errors
    }

    /** Protects (or unprotects) [items] on the card, as the camera's own protect button. */
    fun protect(items: List<CameraItem>, on: Boolean): List<String> {
        val c = connection ?: return listOf("相机未连接")
        val errors = mutableListOf<String>()
        for (item in items) for (f in item.files) {
            try {
                if (isLocal(f.handle)) continue
                c.command(Ptp.SET_OBJECT_PROTECTION, f.handle, if (on) 1 else 0)
                files[f.handle] = ObjectInfo.parse(f.handle, c.request(Ptp.GET_OBJECT_INFO, f.handle))
            } catch (e: Exception) {
                errors += "${f.filename}：${e.message}"
                ErrorLog.record("保护 ${f.filename}", e)
            }
        }
        publish(HashMap(files))
        return errors
    }

    /** Runs [block] on the camera thread (connection work, in order with the rest). */
    fun run(block: () -> Unit) = io.execute(block)

    fun storageName(id: Int): String {
        if (id == 0) return "相机内存"
        val s = storages.firstOrNull { it.id == id } ?: return "卡槽"
        val index = storages.sortedBy { it.id }.indexOf(s) + 1
        return "卡槽 $index"
    }

    // ---- log -------------------------------------------------------------------------------------

    fun note(text: String) {
        val line = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()) + "  " + text
        synchronized(log) {
            log.addLast(line)
            while (log.size > 600) log.removeFirst()
        }
        android.util.Log.i("CameraHub", text)
    }

    fun logText(): String = synchronized(log) { log.joinToString("\n") }

    fun logTail(lines: Int): String = synchronized(log) { log.toList().takeLast(lines).joinToString("\n") }

    private const val ACTION_PERMISSION = "org.photoall.tether.USB_PERMISSION"
    private const val CHUNK = 4 * 1024 * 1024
    private const val MEMORY_BASE = 0x7F000000
}
