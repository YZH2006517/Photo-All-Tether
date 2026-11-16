package org.photoall.tether.develop

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the app put on the phone: prints (developed JPEGs) and originals (NEFs and camera
 * JPEGs copied from the card), in Pictures/PhotoAll Tether, with each one's record.
 */
object Library {
    const val PRINT = "print"
    const val ORIGINAL = "original"
    const val FOLDER = "Pictures/PhotoAll Tether"

    class Entry(
        val id: Long,
        val kind: String,
        val uri: String,
        val name: String,
        val mime: String,
        val cameraKey: String?,
        val original: String?,      // prints: the original they were developed from
        val time: Long,             // when it was added
        val shotTime: Long,         // when it was taken (camera clock), 0 unknown
        val width: Int,
        val height: Int,
        val recipe: JSONObject?,
        val meta: Map<String, String>,
        val note: String,
    ) {
        val isRaw: Boolean get() = !mime.startsWith("image/jpeg")

        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id); put("kind", kind); put("uri", uri); put("name", name); put("mime", mime)
            put("cameraKey", cameraKey ?: ""); put("original", original ?: ""); put("time", time)
            put("shotTime", shotTime); put("width", width); put("height", height)
            recipe?.let { put("recipe", it) }
            put("meta", JSONObject(meta)); put("note", note)
        }

        companion object {
            fun fromJson(o: JSONObject): Entry {
                val metaJson = o.optJSONObject("meta")
                val meta = metaJson?.keys()?.asSequence()?.associateWith { metaJson.getString(it) } ?: emptyMap()
                return Entry(
                    o.getLong("id"), o.getString("kind"), o.getString("uri"), o.getString("name"),
                    o.optString("mime", "image/jpeg"), o.optString("cameraKey").ifEmpty { null },
                    o.optString("original").ifEmpty { null }, o.optLong("time"), o.optLong("shotTime"),
                    o.optInt("width"), o.optInt("height"), o.optJSONObject("recipe"), meta, o.optString("note"),
                )
            }
        }
    }

    private val entries = mutableListOf<Entry>()
    private var loaded = false
    private val listeners = mutableListOf<() -> Unit>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = main.post { listeners.toList().forEach { it() } }

    private fun file(context: Context) = File(context.filesDir, "library.json")

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val a = JSONArray(file(context).readText())
            for (i in 0 until a.length()) entries += Entry.fromJson(a.getJSONObject(i))
        }
    }

    @Synchronized
    private fun persist(context: Context) {
        val a = JSONArray()
        entries.forEach { a.put(it.toJson()) }
        val tmp = File(context.filesDir, "library.json.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file(context))
    }

    @Synchronized fun prints(): List<Entry> = entries.filter { it.kind == PRINT }.sortedByDescending { it.time }
    @Synchronized fun originals(): List<Entry> = entries.filter { it.kind == ORIGINAL }.sortedByDescending { it.shotTime.takeIf { t -> t > 0 } ?: it.time }
    @Synchronized fun find(id: Long): Entry? = entries.firstOrNull { it.id == id }
    @Synchronized fun originalOf(cameraKey: String): Entry? = entries.firstOrNull { it.kind == ORIGINAL && it.cameraKey == cameraKey }
    @Synchronized fun printsOf(cameraKey: String): Int = entries.count { it.kind == PRINT && it.cameraKey == cameraKey }
    @Synchronized fun byUri(uri: String): Entry? = entries.firstOrNull { it.uri == uri }

    /** Keys of camera files that are on the phone (as an original or a print). */
    @Synchronized fun importedKeys(): Set<String> = entries.mapNotNull { it.cameraKey }.toSet()

    fun add(context: Context, entry: Entry) {
        synchronized(this) { entries += entry }
        persist(context)
        changed()
    }

    fun nextId(): Long = System.currentTimeMillis() * 1000 + (Math.random() * 1000).toLong()

    // ---- files ---------------------------------------------------------------------------------

    /** Saves a developed picture as a JPEG with the shot's EXIF; returns its content uri. */
    fun saveJpeg(context: Context, bitmap: Bitmap, name: String, quality: Int, exif: Map<String, String>): Uri {
        val tmp = File(context.cacheDir, "print.jpg")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        runCatching {
            val e = ExifInterface(tmp.path)
            exif.forEach { (k, v) -> if (v.isNotEmpty()) e.setAttribute(k, v) }
            e.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            e.saveAttributes()
        }
        val uri = insert(context, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            name, "image/jpeg", FOLDER)
        write(context, uri, tmp)
        tmp.delete()
        return uri
    }

    /** Copies an original (NEF, camera JPEG) into the phone's pictures; returns its uri. */
    fun saveOriginal(context: Context, file: File, name: String): Uri {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mime = mimeOf(ext)
        val uri = runCatching {
            insert(context, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), name, mime, "$FOLDER/原片")
        }.getOrElse {
            // a raw type the media store does not take as an image: the Downloads collection takes anything
            insert(context, MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), name, mime,
                "Download/PhotoAll Tether")
        }
        write(context, uri, file)
        return uri
    }

    fun mimeOf(ext: String): String = when (ext.lowercase(Locale.ROOT)) {
        "nef", "nrw" -> "image/x-nikon-nef"
        "cr2" -> "image/x-canon-cr2"
        "cr3" -> "image/x-canon-cr3"
        "crw" -> "image/x-canon-crw"
        "arw", "srf", "sr2" -> "image/x-sony-arw"
        "raf" -> "image/x-fuji-raf"
        "orf", "ori" -> "image/x-olympus-orf"
        "rw2", "rwl" -> "image/x-panasonic-rw2"
        "pef" -> "image/x-pentax-pef"
        "srw" -> "image/x-samsung-srw"
        "x3f" -> "image/x-sigma-x3f"
        "dng" -> "image/x-adobe-dng"
        "jpg", "jpeg" -> "image/jpeg"
        "hif", "heic", "heif" -> "image/heif"
        "mov" -> "video/quicktime"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    private fun insert(context: Context, collection: Uri, name: String, mime: String, folder: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return context.contentResolver.insert(collection, values) ?: throw IllegalStateException("无法写入手机相册")
    }

    private fun write(context: Context, uri: Uri, file: File) {
        val resolver = context.contentResolver
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 20) } }
                ?: throw IllegalStateException("无法写入 $uri")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /** Copies a library file to a local path (for LibRaw). */
    fun copyToCache(context: Context, uri: Uri, name: String): File {
        val out = File(File(context.cacheDir, "work").apply { mkdirs() }, name)
        context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it, 1 shl 20) } }
            ?: throw IllegalStateException("无法读取 $name")
        return out
    }

    /**
     * Deletes [list] from the phone. Returns an IntentSender when Android asks the user first
     * (files of an earlier install); call [forget] for them once the user agreed.
     */
    fun delete(context: Context, list: List<Entry>): IntentSender? {
        val needConsent = mutableListOf<Uri>()
        val gone = mutableListOf<Entry>()
        for (e in list) {
            val uri = Uri.parse(e.uri)
            try {
                context.contentResolver.delete(uri, null, null)
                gone += e
            } catch (s: SecurityException) {
                if (Build.VERSION.SDK_INT >= 30 || s is RecoverableSecurityException) needConsent += uri else gone += e
            }
        }
        forget(context, gone)
        if (needConsent.isEmpty()) return null
        return if (Build.VERSION.SDK_INT >= 30) MediaStore.createDeleteRequest(context.contentResolver, needConsent).intentSender
        else null
    }

    fun forget(context: Context, list: List<Entry>) {
        if (list.isEmpty()) return
        val ids = list.map { it.id }.toSet()
        synchronized(this) { entries.removeAll { it.id in ids } }
        persist(context)
        changed()
    }

    /** EXIF for a print from the raw's metadata and the recipe. */
    fun exifFor(meta: Map<String, String>, recipe: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        meta["make"]?.let { out[ExifInterface.TAG_MAKE] = it }
        meta["model"]?.let { out[ExifInterface.TAG_MODEL] = it }
        meta["lens"]?.takeIf { it.isNotBlank() }?.let { out["LensModel"] = it }
        meta["iso"]?.toFloatOrNull()?.takeIf { it > 0 }?.let { out[ExifInterface.TAG_ISO_SPEED_RATINGS] = it.toInt().toString() }
        meta["shutter"]?.toDoubleOrNull()?.takeIf { it > 0 }?.let { out[ExifInterface.TAG_EXPOSURE_TIME] = it.toString() }
        meta["aperture"]?.toDoubleOrNull()?.takeIf { it > 0 }?.let { out[ExifInterface.TAG_F_NUMBER] = it.toString() }
        meta["focal"]?.toDoubleOrNull()?.takeIf { it > 0 }?.let { out[ExifInterface.TAG_FOCAL_LENGTH] = "${(it * 10).toInt()}/10" }
        meta["time"]?.toLongOrNull()?.takeIf { it > 0 }?.let {
            val text = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(it * 1000))
            out[ExifInterface.TAG_DATETIME_ORIGINAL] = text
            out[ExifInterface.TAG_DATETIME] = text
        }
        out[ExifInterface.TAG_SOFTWARE] = "Photo All 联机"
        out[ExifInterface.TAG_IMAGE_DESCRIPTION] = recipe
        return out
    }
}
