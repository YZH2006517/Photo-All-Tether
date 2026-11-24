package org.photoall.tether.ptp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Locale

/** Little-endian reader for PTP datasets. */
class PtpReader(bytes: ByteArray) {
    private val buffer: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    val remaining: Int get() = buffer.remaining()
    fun u8(): Int = buffer.get().toInt() and 0xFF
    fun u16(): Int = buffer.short.toInt() and 0xFFFF
    fun s16(): Int = buffer.short.toInt()
    fun u32(): Long = buffer.int.toLong() and 0xFFFFFFFFL
    fun s32(): Int = buffer.int
    fun u64(): Long = buffer.long

    /** u8 count of UTF-16 code units (with the terminating zero), then the units. */
    fun string(): String {
        if (buffer.remaining() < 1) return ""
        val count = u8()
        if (count == 0) return ""
        val chars = CharArray(count) { buffer.short.toInt().toChar() }
        return String(chars).trimEnd('\u0000')
    }

    fun u16Array(): IntArray = IntArray(s32().coerceIn(0, buffer.remaining() / 2)) { u16() }
    fun u32Array(): IntArray = IntArray(s32().coerceIn(0, buffer.remaining() / 4)) { buffer.int }
}

class DeviceInfo(
    val standardVersion: Int,
    val vendorExtensionId: Long,
    val vendorExtension: String,
    val operations: Set<Int>,
    val events: Set<Int>,
    val properties: Set<Int>,
    val manufacturer: String,
    val model: String,
    val version: String,
    val serial: String,
) {
    fun supports(operation: Int) = operation in operations

    companion object {
        fun parse(bytes: ByteArray): DeviceInfo {
            val r = PtpReader(bytes)
            val standard = r.u16()
            val vendorId = r.u32()
            r.u16() // vendor extension version
            val vendorDesc = r.string()
            r.u16() // functional mode
            val ops = r.u16Array().toSet()
            val events = r.u16Array().toSet()
            val props = r.u16Array().toSet()
            r.u16Array() // capture formats
            r.u16Array() // image formats
            return DeviceInfo(standard, vendorId, vendorDesc, ops, events, props, r.string(), r.string(), r.string(), r.string())
        }
    }
}

class StorageInfo(
    val id: Int,
    val type: Int,
    val access: Int,
    val maxCapacity: Long,
    val freeBytes: Long,
    val freeImages: Long,
    val description: String,
    val label: String,
) {
    /** Bit 16+ of a storage id is the physical slot; 0x0001 in the low half means a card is in. */
    val slot: Int get() = (id ushr 16) and 0xFFFF
    val present: Boolean get() = (id and 0xFFFF) != 0
    val readOnly: Boolean get() = access == 1 || access == 2

    companion object {
        fun parse(id: Int, bytes: ByteArray): StorageInfo {
            val r = PtpReader(bytes)
            val type = r.u16()
            r.u16() // filesystem type
            val access = r.u16()
            val max = r.u64()
            val free = r.u64()
            val freeImages = r.u32()
            return StorageInfo(id, type, access, max, free, freeImages, r.string(), r.string())
        }
    }
}

class ObjectInfo(
    val handle: Int,
    val storageId: Int,
    val format: Int,
    val protection: Int,
    val size: Long,
    val thumbFormat: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val parent: Int,
    val associationType: Int,
    val filename: String,
    val captureDate: String,
) {
    val isFolder: Boolean get() = format == Ptp.FORMAT_ASSOCIATION
    val extension: String get() = filename.substringAfterLast('.', "").uppercase(Locale.ROOT)
    val isRaw: Boolean get() = extension in RAW_EXTENSIONS
    val isJpeg: Boolean get() = extension == "JPG" || extension == "JPEG" || format == Ptp.FORMAT_EXIF_JPEG
    val isHeif: Boolean get() = extension == "HIF" || extension == "HEIC" || extension == "HEIF"
    val isVideo: Boolean get() = extension in VIDEO_EXTENSIONS
    val isProtected: Boolean get() = protection != 0
    val baseName: String get() = filename.substringBeforeLast('.')

    /** Capture time in ms (camera clock, read as local time), 0 when unknown. */
    val captureTime: Long by lazy { parseDate(captureDate) }

    /** Stays the same across connections (handles need not): file name, size and time. */
    val key: String get() = "$filename|$size|$captureDate"

    companion object {
        /** Raw files LibRaw reads, by maker: Nikon, Canon, Sony, Fujifilm, OM / Olympus, Panasonic / Leica,
         *  Pentax, Samsung, Sigma, Hasselblad, Phase One, Minolta, Kodak, Epson, Mamiya, GoPro, DNG. */
        val RAW_EXTENSIONS = setOf(
            "NEF", "NRW", "CR2", "CR3", "CRW", "ARW", "SRF", "SR2", "RAF", "ORF", "ORI", "RW2", "RWL", "PEF",
            "SRW", "X3F", "3FR", "FFF", "IIQ", "MRW", "DCR", "KDC", "ERF", "MEF", "MOS", "GPR", "DNG",
            // less common ones LibRaw also reads: Panasonic / Leica RAW, Sony pixel shift, Phase One,
            // Mamiya, Kodak, Sinar, Casio
            "RAW", "RWZ", "ARQ", "CAP", "EIP", "MFW", "K25", "KC2", "DC2", "DCS", "STI", "BAY", "MDC",
        )
        val VIDEO_EXTENSIONS = setOf("MOV", "MP4", "NEV", "AVI")

        fun parse(handle: Int, bytes: ByteArray): ObjectInfo {
            val r = PtpReader(bytes)
            val storage = r.s32()
            val format = r.u16()
            val protection = r.u16()
            val size = r.u32()
            val thumbFormat = r.u16()
            r.u32() // thumb compressed size
            r.u32(); r.u32() // thumb width, height
            val width = r.u32().toInt()
            val height = r.u32().toInt()
            r.u32() // bit depth
            val parent = r.s32()
            val association = r.u16()
            r.u32() // association desc
            r.u32() // sequence number
            val name = r.string()
            val captured = r.string()
            return ObjectInfo(handle, storage, format, protection, size, thumbFormat, width, height, parent, association, name, captured)
        }

        fun parseDate(s: String): Long {
            if (s.length < 15) return 0
            return runCatching {
                SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).parse(s.substring(0, 15))?.time ?: 0L
            }.getOrDefault(0L)
        }
    }
}
