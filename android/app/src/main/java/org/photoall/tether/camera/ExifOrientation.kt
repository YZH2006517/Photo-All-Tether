package org.photoall.tether.camera

/**
 * The EXIF / TIFF orientation tag (0x0112) from the first bytes of a file: a JPEG (its APP1
 * "Exif" segment) or a TIFF-based raw (NEF, CR2, ARW, DNG, ORF, PEF ...: IFD0). 1 when absent.
 */
object ExifOrientation {
    fun read(b: ByteArray): Int {
        val tiff = when {
            b.size > 4 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xD8 -> app1(b)
            b.size > 8 && ((b[0] == 'I'.code.toByte() && b[1] == 'I'.code.toByte()) ||
                (b[0] == 'M'.code.toByte() && b[1] == 'M'.code.toByte())) -> 0
            else -> -1
        }
        if (tiff < 0 || tiff + 8 > b.size) return 1
        val little = b[tiff] == 'I'.code.toByte()
        fun u16(at: Int): Int = if (at + 2 > b.size) -1 else if (little)
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
        else ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
        fun u32(at: Int): Long = if (at + 4 > b.size) -1 else if (little)
            (u16(at).toLong()) or (u16(at + 2).toLong() shl 16)
        else (u16(at).toLong() shl 16) or u16(at + 2).toLong()
        val ifd = u32(tiff + 4)
        if (ifd < 8) return 1
        val start = tiff + ifd.toInt()
        val count = u16(start)
        if (count <= 0 || count > 500) return 1
        for (i in 0 until count) {
            val e = start + 2 + i * 12
            if (e + 12 > b.size) break
            if (u16(e) == 0x0112) {
                val v = u16(e + 8)
                return if (v in 1..8) v else 1
            }
        }
        return 1
    }

    // the start of the TIFF block inside a JPEG's APP1 "Exif\0\0" segment, or -1
    private fun app1(b: ByteArray): Int {
        var i = 2
        while (i + 4 < b.size) {
            if ((b[i].toInt() and 0xFF) != 0xFF) return -1
            val marker = b[i + 1].toInt() and 0xFF
            val length = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (marker == 0xE1 && i + 10 < b.size && String(b, i + 4, 4, Charsets.US_ASCII) == "Exif") return i + 10
            if (marker == 0xDA || length < 2) return -1 // image data starts: no EXIF before it
            i += 2 + length
        }
        return -1
    }
}
