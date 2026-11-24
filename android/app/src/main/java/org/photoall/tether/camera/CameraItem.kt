package org.photoall.tether.camera

import org.photoall.tether.ptp.ObjectInfo

/**
 * One shot on the camera's card: the files sharing a name in a folder (DSC_0001.NEF and
 * DSC_0001.JPG of a RAW+JPEG shot are one item).
 */
class CameraItem(val files: List<ObjectInfo>) {
    val raw: ObjectInfo? get() = files.firstOrNull { it.isRaw }
    /** The camera's own picture of the shot: its JPEG, or its HEIF (Sony, Canon, Fujifilm HIF). */
    val jpeg: ObjectInfo? get() = files.firstOrNull { it.isJpeg } ?: files.firstOrNull { it.isHeif }
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
        raw != null && jpeg != null -> "${raw!!.extension}+${if (jpeg!!.isHeif) "HEIF" else "JPG"}"
        raw != null -> raw!!.extension
        jpeg != null -> if (jpeg!!.isHeif) "HEIF" else "JPG"
        else -> primary.extension
    }

    /** Stable across connections: the primary file's name, size and time. */
    val key: String get() = primary.key

    fun handles(): List<Int> = files.map { it.handle }

    companion object {
        /** Groups files into shots, newest first. */
        fun group(files: Collection<ObjectInfo>): List<CameraItem> =
            files.filter { !it.isFolder }
                .groupBy { Triple(it.storageId, it.parent, it.baseName.uppercase()) }
                .values.map { CameraItem(it.sortedBy { f -> f.filename }) }
                .sortedWith(compareByDescending<CameraItem> { it.time }.thenByDescending { it.name })
    }
}
