package org.photoall.tether.camera

import org.photoall.tether.ptp.DeviceInfo
import org.photoall.tether.ptp.Ptp
import org.photoall.tether.ptp.PtpConnection
import org.photoall.tether.ptp.PtpReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What differs between camera makers over PTP (from libgphoto2's camlibs/ptp2, the most
 * complete open record of each maker's extensions):
 *
 *  - Nikon: events by polling GetEvent (0x90C7); GetLargeThumb stalls the Z 6II's pipe.
 *  - Canon EOS: SetRemoteMode + SetEventMode first, then events by polling GetEvent (0x9116),
 *    a stream of (size, code, data) records; a new file is ObjectAddedEx (0xC181 / 0xC1A7).
 *  - Sony in "PC Remote": the SDIO handshake (0x9201 phases 1-3 and 0x9202) first; a new shot
 *    waits in the camera's memory at handle 0xFFFFC001 and may only be read once property
 *    0xD215 (objects in memory) is above 0x8000, or the camera crashes. In MTP mode the card
 *    can be browsed but there are no new shots.
 *  - Fujifilm: no ObjectAdded events in tether mode: new files are found by listing all handles;
 *    PriorityMode (0xD207) set to 1 keeps the camera talking.
 *  - Panasonic: says more (DeleteObject, ...) once the session initiator is named as Windows MTP.
 *  - OM System / Olympus, Leica, Pentax / Ricoh, Sigma, Hasselblad: standard PTP events on the
 *    interrupt pipe, and the file-listing fallback.
 */
enum class Brand(val label: String) {
    NIKON("尼康"), CANON("佳能"), SONY("索尼"), FUJIFILM("富士"), PANASONIC("松下"),
    OLYMPUS("OM System / 奥林巴斯"), PENTAX("宾得 / 理光"), LEICA("徕卡"), SIGMA("适马"),
    HASSELBLAD("哈苏"), OTHER("其他品牌");
}

object Vendors {
    // USB vendor ids
    private val byUsb = mapOf(
        0x04B0 to Brand.NIKON, 0x04A9 to Brand.CANON, 0x054C to Brand.SONY, 0x04CB to Brand.FUJIFILM,
        0x04DA to Brand.PANASONIC, 0x07B4 to Brand.OLYMPUS, 0x33A2 to Brand.OLYMPUS, 0x0A17 to Brand.PENTAX,
        0x25FB to Brand.PENTAX, 0x1A98 to Brand.LEICA, 0x1003 to Brand.SIGMA, 0x0FE4 to Brand.HASSELBLAD,
    )

    fun brand(usbVendor: Int, info: DeviceInfo?): Brand {
        byUsb[usbVendor]?.let { return it }
        val m = (info?.manufacturer ?: "").lowercase()
        return when {
            "nikon" in m -> Brand.NIKON
            "canon" in m -> Brand.CANON
            "sony" in m -> Brand.SONY
            "fuji" in m -> Brand.FUJIFILM
            "panasonic" in m || "lumix" in m -> Brand.PANASONIC
            "olympus" in m || "om digital" in m || "omsystem" in m -> Brand.OLYMPUS
            "pentax" in m || "ricoh" in m -> Brand.PENTAX
            "leica" in m -> Brand.LEICA
            "sigma" in m -> Brand.SIGMA
            "hasselblad" in m -> Brand.HASSELBLAD
            else -> Brand.OTHER
        }
    }

    // ---- operation and property codes ----------------------------------------------------------
    private const val CANON_SET_REMOTE_MODE = 0x9114
    private const val CANON_SET_EVENT_MODE = 0x9115
    private const val CANON_GET_EVENT = 0x9116
    private const val CANON_KEEP_DEVICE_ON = 0x911D
    private const val CANON_OBJECT_ADDED = 0xC181
    private const val CANON_OBJECT_ADDED_64 = 0xC1A7
    private const val CANON_OBJECT_ADDED_64_LFN = 0xC1B6
    private const val CANON_OBJECT_REMOVED = 0xC182

    private const val SONY_SDIO_CONNECT = 0x9201
    private const val SONY_SDIO_EXT_DEVICE_INFO = 0x9202
    private const val SONY_ALL_EXT_PROP_INFO = 0x9209
    private const val SONY_OBJECT_IN_MEMORY = 0xD215
    const val SONY_MEMORY_HANDLE = 0xFFFFC001.toInt()

    private const val FUJI_PRIORITY_MODE = 0xD207
    private const val MTP_SESSION_INITIATOR = 0xD406

    /** How a connected camera tells of new shots, and what it lets us do. */
    class Profile(
        val brand: Brand,
        /** Poll [poll] for events (Nikon / Canon). */
        val pollsEvents: Boolean,
        /** Read the standard interrupt pipe for events. */
        val interruptEvents: Boolean,
        /** No new-file events at all: list the handles to find new files (Fujifilm, fallback). */
        val listsForNew: Boolean,
        /** Sony PC Remote: shots arrive in camera memory, not on the card. */
        val sonyRemote: Boolean,
        /** Shown on the camera page: what the user should know or set for this camera. */
        val hint: String?,
        val avoidLargeThumb: Boolean = true,
    )

    /**
     * Sets the camera up after OpenSession; returns its profile (and maybe a new DeviceInfo,
     * when the camera tells more after the set-up).
     */
    fun prepare(c: PtpConnection, info: DeviceInfo, brand: Brand, note: (String) -> Unit): Pair<Profile, DeviceInfo> {
        var di = info
        when (brand) {
            Brand.CANON -> if (info.supports(CANON_SET_REMOTE_MODE)) {
                val model = info.model
                // EOS M and PowerShot bodies want mode 0x15 (as libgphoto2 sends)
                val mode = if (model.contains("EOS M", ignoreCase = true) || model.contains("PowerShot", ignoreCase = true)) 0x15 else 1
                runCatching { c.command(CANON_SET_REMOTE_MODE, mode) }.onFailure { note("佳能 SetRemoteMode：${it.message}") }
                if (info.supports(CANON_SET_EVENT_MODE))
                    runCatching { c.command(CANON_SET_EVENT_MODE, 1) }.onFailure { note("佳能 SetEventMode：${it.message}") }
                // the first GetEvent returns the whole state: read it away
                if (info.supports(CANON_GET_EVENT)) runCatching { c.request(CANON_GET_EVENT) }
                note("佳能 EOS：已进入联机模式（remote $mode）")
            }
            Brand.SONY -> if (info.supports(SONY_SDIO_EXT_DEVICE_INFO)) {
                try {
                    c.request(SONY_SDIO_CONNECT, 1, 0, 0)
                    c.request(SONY_SDIO_CONNECT, 2, 0, 0)
                    var size = 0
                    repeat(20) { if (size == 0) size = runCatching { c.request(SONY_SDIO_EXT_DEVICE_INFO, 0x12C, 1).size }.getOrDefault(0) }
                    c.request(SONY_SDIO_CONNECT, 3, 0, 0)
                    note("索尼 PC 遥控：握手完成（扩展信息 $size 字节）")
                } catch (e: Exception) {
                    note("索尼 PC 遥控握手失败：${e.message}")
                }
            }
            Brand.FUJIFILM -> if (FUJI_PRIORITY_MODE in info.properties) {
                runCatching { c.command(Ptp.SET_DEVICE_PROP_VALUE, FUJI_PRIORITY_MODE, dataOut = u16(1)) }
                    .onFailure { note("富士 PriorityMode：${it.message}") }
            }
            Brand.PANASONIC -> if (MTP_SESSION_INITIATOR in info.properties) {
                runCatching {
                    c.command(Ptp.SET_DEVICE_PROP_VALUE, MTP_SESSION_INITIATOR,
                        dataOut = ptpString("Windows/6.2.9200 MTPClassDriver/6.2.9200.16384"))
                    di = DeviceInfo.parse(c.request(Ptp.GET_DEVICE_INFO))
                    note("松下：声明为 Windows MTP 后支持 ${di.operations.size} 种操作")
                }.onFailure { note("松下 MTP 声明：${it.message}") }
            }
            else -> {}
        }
        val nikonPoll = brand == Brand.NIKON && di.supports(Ptp.NIKON_GET_EVENT)
        val canonPoll = brand == Brand.CANON && di.supports(CANON_GET_EVENT)
        val sonyRemote = brand == Brand.SONY && di.supports(SONY_SDIO_EXT_DEVICE_INFO)
        val hint = when {
            brand == Brand.SONY && !sonyRemote ->
                "索尼在 MTP 模式下只能浏览和导入存储卡。要边拍边传，把相机菜单「USB 连接」设为「PC 遥控」（拍摄的照片保存到「PC+相机」）。"
            brand == Brand.SONY -> "PC 遥控模式：新拍的照片从相机内存直接传到手机。"
            brand == Brand.FUJIFILM ->
                "富士：要边拍边传，在「连接设置 → PC 连接模式」选「USB 联机拍摄 自动」。X-Trans RAW 由 LibRaw 去马赛克，冲洗比拜耳慢。"
            brand == Brand.CANON && !canonPoll -> "这台佳能不支持 EOS 联机事件：只能浏览和导入，新照片靠定时查找。"
            brand == Brand.OLYMPUS -> "OM System / 奥林巴斯：USB 模式选「存储」或「MTP」可以浏览和导入；联机拍摄的新照片靠事件或定时查找。"
            brand == Brand.PANASONIC -> "松下：USB 模式选「PC(Tether)」时可以边拍边传；选「PC(Storage)」时只能浏览导入。"
            brand == Brand.OTHER -> "未适配的品牌：按标准 PTP 浏览、导入；新照片靠事件或定时查找。"
            else -> null
        }
        val profile = Profile(
            brand = brand,
            pollsEvents = nikonPoll || canonPoll,
            interruptEvents = !nikonPoll && !canonPoll,
            listsForNew = brand == Brand.FUJIFILM,
            sonyRemote = sonyRemote,
            hint = hint,
        )
        return profile to di
    }

    /** One poll of the camera's own event queue (Nikon, Canon): (event code, object handle). */
    fun poll(c: PtpConnection, profile: Profile): List<Pair<Int, Int>> = when (profile.brand) {
        Brand.NIKON -> {
            val r = PtpReader(c.request(Ptp.NIKON_GET_EVENT))
            val count = if (r.remaining >= 2) r.u16() else 0
            buildList { repeat(count) { if (r.remaining >= 6) add(r.u16() to r.u32().toInt()) } }
        }
        Brand.CANON -> canonEvents(c.request(CANON_GET_EVENT))
        else -> emptyList()
    }

    // Canon EOS GetEvent: records of (u32 size, u32 code, data), ended by a size 8 / code 0 record.
    private fun canonEvents(data: ByteArray): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var at = 0
        while (at + 8 <= data.size) {
            val size = bb.getInt(at)
            val code = bb.getInt(at + 4)
            if (size < 8 || at + size > data.size || (size == 8 && code == 0)) break
            when (code) {
                // the handle is at offset 8 in all three layouts
                CANON_OBJECT_ADDED, CANON_OBJECT_ADDED_64, CANON_OBJECT_ADDED_64_LFN ->
                    if (size >= 12) out += Ptp.EVENT_OBJECT_ADDED to bb.getInt(at + 8)
                CANON_OBJECT_REMOVED -> if (size >= 12) out += Ptp.EVENT_OBJECT_REMOVED to bb.getInt(at + 8)
            }
            at += size
        }
        return out
    }

    /** Sony PC Remote: is a shot waiting in the camera's memory? (property 0xD215 above 0x8000) */
    fun sonyShotWaiting(c: PtpConnection): Boolean {
        val blob = c.request(SONY_ALL_EXT_PROP_INFO)
        // Each property: u16 code, u16 type, u8 get/set, u8 enabled, default, current, form. Rather
        // than parse every type (and Sony's two enum-list layouts), find 0xD215 as a UINT16 (type 4).
        for (i in 8 until blob.size - 10) {
            if (blob[i] == 0x15.toByte() && blob[i + 1] == 0xD2.toByte() && blob[i + 2] == 0x04.toByte() && blob[i + 3] == 0x00.toByte()) {
                val current = (blob[i + 8].toInt() and 0xFF) or ((blob[i + 9].toInt() and 0xFF) shl 8)
                return current > 0x8000
            }
        }
        return false
    }

    /** Canon: keeps the camera from sleeping while tethered (call now and then). */
    fun keepAlive(c: PtpConnection, profile: Profile, info: DeviceInfo) {
        if (profile.brand == Brand.CANON && info.supports(CANON_KEEP_DEVICE_ON)) runCatching { c.command(CANON_KEEP_DEVICE_ON) }
    }

    /** Leaves the camera as it was (Canon: out of remote mode). */
    fun release(c: PtpConnection, profile: Profile?, info: DeviceInfo?) {
        if (profile?.brand == Brand.CANON && info != null) {
            if (info.supports(CANON_SET_EVENT_MODE)) runCatching { c.command(CANON_SET_EVENT_MODE, 0) }
            if (info.supports(CANON_SET_REMOTE_MODE)) runCatching { c.command(CANON_SET_REMOTE_MODE, 0) }
        }
    }

    private fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    // PTP string: u8 count of UTF-16 units including the terminating zero, then the units.
    private fun ptpString(s: String): ByteArray {
        val bb = ByteBuffer.allocate(1 + (s.length + 1) * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.put((s.length + 1).toByte())
        s.forEach { bb.putShort(it.code.toShort()) }
        bb.putShort(0)
        return bb.array()
    }
}
