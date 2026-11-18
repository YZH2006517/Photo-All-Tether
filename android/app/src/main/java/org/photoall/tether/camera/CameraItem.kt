package org.photoall.tether.camera

import org.photoall.tether.ptp.ObjectInfo
import kotlin.math.abs

/**
 * One shot on the camera's card: the files sharing a name in a folder (DSC_0001.NEF and
 * DSC_0001.JPG of a RAW+JPEG shot are one item).
 */
class CameraItem(val files: List<ObjectInfo>) {
    val raw: ObjectInfo? get() = files.firstOrNull { it.isRaw }
    val jpeg: ObjectInfo? get() = files.firstOrNull { it.isJpeg }
    val primary: ObjectInfo get() = raw ?: jpeg ?: files.first()

    val name: String get() = primary.baseName
    val storageId: Int get() = primary.storageId
    val time: Long get() = files.maxOf { it.captureTime }
    val size: Long get() = files.sumOf { it.size }
    val isProtected: Boolean get() = files.any { it.isProtected }
    val isVideo: Boolean get() = files.all { it.isVideo }
    val canDevelop: Boolean get() = raw != null || jpeg != null

    /** What the item holds: "RAW+JPG", "RAW", "JPG", "HEIF", "MOV". */
    val badge: String get() = when {
        raw != null && jpeg != null -> "${raw!!.extension}+JPG"
        raw != null -> raw!!.extension
        jpeg != null -> "JPG"
        else -> primary.extension
    }

    /** Stable across connections: the primary file's name, size and time. */
    val key: String get() = primary.key

    fun handles(): List<Int> = files.map { it.handle }

    companion object {
        /** Groups files into shots, newest first. */
        fun group(files: Collection<ObjectInfo>, fuji: Boolean = false): List<CameraItem> {
            val all = files.filter { !it.isFolder }
            if (!fuji) return all
                .groupBy { Triple(it.storageId, it.parent, it.baseName.uppercase()) }
                .values.map { CameraItem(it.sortedBy { f -> f.filename }) }
                .sortedWith(compareByDescending<CameraItem> { it.time }.thenByDescending { it.name })

            // Fujifilm numbers RAW+JPEG as two consecutive files (DSCF0013.RAF / DSCF0014.JPG)
            // instead of using the same base name. Pair a raw with a jpeg one number away in
            // the same folder and from the same burst, so it counts and develops once.
            val out = mutableListOf<CameraItem>()
            for (folder in all.groupBy { Pair(it.storageId, it.parent) }.values) {
                val used = mutableSetOf<Int>()
                val ordered = folder.sortedBy { it.filename }
                for (f in ordered) {
                    if (f.handle in used) continue
                    val n = sequence(f.baseName)
                    val partner = if (n != null && (f.isRaw || f.isJpeg))
                        ordered.firstOrNull { o ->
                            o.handle != f.handle && o.handle !in used && (o.isRaw || o.isJpeg) &&
                                o.isRaw != f.isRaw &&
                                sequence(o.baseName)?.let { m -> (m == n + 1 || m == n - 1) && sameBurst(f, o) } == true
                        }
                    else null
                    if (partner != null) {
                        used += f.handle
                        used += partner.handle
                        out += CameraItem(listOf(f, partner))
                    } else {
                        used += f.handle
                        out += CameraItem(listOf(f))
                    }
                }
            }
            return out.sortedWith(compareByDescending<CameraItem> { it.time }.thenByDescending { it.name })
        }

        private fun sequence(baseName: String): Int? =
            Regex("""(\d+)$""").find(baseName)?.value?.toIntOrNull()

        private fun sameBurst(a: ObjectInfo, b: ObjectInfo): Boolean {
            val ta = a.captureTime
            val tb = b.captureTime
            return ta <= 0 || tb <= 0 || abs(ta - tb) <= 10_000
        }
    }
}
