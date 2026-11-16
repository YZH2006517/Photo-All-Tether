package org.photoall.tether.develop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.photoall.tether.camera.CameraHub
import org.photoall.tether.camera.CameraItem
import org.photoall.tether.camera.TetherService
import org.photoall.tether.engine.Brightness
import org.photoall.tether.engine.Films
import org.photoall.tether.engine.FilmEngine
import org.photoall.tether.engine.RawFile
import org.photoall.tether.engine.Recipe
import org.photoall.tether.ptp.ObjectInfo
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Photos on their way: copied off the camera (one at a time, on the transfer thread), then
 * developed on the GPU (one at a time, on the develop thread), so the next shot downloads
 * while the last one develops.
 */
object DevelopQueue {
    enum class Status(val text: String) {
        WAITING("排队"), DOWNLOADING("传输"), DEVELOPING("冲洗"), DONE("完成"), FAILED("失败")
    }

    sealed class Source {
        class Camera(val item: CameraItem) : Source()
        class Local(val uri: Uri, val name: String) : Source()
        class Original(val entry: Library.Entry) : Source()
    }

    class Job(val id: Long, val title: String, val source: Source, val develop: Boolean, val auto: Boolean, val recipe: Recipe) {
        @Volatile var status = Status.WAITING
        @Volatile var progress = 0f
        @Volatile var detail = ""
        @Volatile var error: String? = null
        @Volatile var result: Library.Entry? = null
        @Volatile var thumb: Bitmap? = null
        val finished: Boolean get() = status == Status.DONE || status == Status.FAILED
    }

    val jobs = CopyOnWriteArrayList<Job>()
    @Volatile var developedThisSession = 0
        private set
    private val listeners = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val transfer = Executors.newSingleThreadExecutor { Thread(it, "transfer") }
    private val developer = Executors.newSingleThreadExecutor { Thread(it, "develop") }
    private var lastPost = 0L

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed(force: Boolean = true) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPost < 120) return
        lastPost = now
        main.post { listeners.toList().forEach { it() } }
    }

    val active: Int get() = jobs.count { !it.finished }

    // ---- adding --------------------------------------------------------------------------------

    /** A shot on the camera: copied to the phone, and developed when [develop]. */
    fun addCamera(context: Context, item: CameraItem, develop: Boolean, auto: Boolean = false) {
        val app = context.applicationContext
        // the same shot already on its way (a RAW+JPEG pair, or a tap twice)
        if (jobs.any { !it.finished && (it.source as? Source.Camera)?.item?.key == item.key && it.develop == develop }) return
        val job = Job(Library.nextId(), item.name, Source.Camera(item), develop, auto, Recipe.load(app))
        CameraHub.cachedThumb(item.primary)?.let { job.thumb = it }
        jobs += job
        changed()
        TetherService.update(app)
        transfer.execute { fetch(app, job) }
    }

    /** A raw or JPEG file chosen on the phone. */
    fun addLocal(context: Context, uri: Uri, name: String) {
        val app = context.applicationContext
        val job = Job(Library.nextId(), name.substringBeforeLast('.'), Source.Local(uri, name), true, false, Recipe.load(app))
        jobs += job
        changed()
        transfer.execute {
            try {
                job.status = Status.DOWNLOADING
                job.detail = "读取文件"
                changed()
                val file = Library.copyToCache(app, uri, safe(name))
                developer.execute { develop(app, job, file, null, null) }
            } catch (e: Throwable) {
                fail(job, e)
            }
        }
    }

    /** Develops an original on the phone again with the current recipe. */
    fun redevelop(context: Context, original: Library.Entry) {
        val app = context.applicationContext
        val job = Job(Library.nextId(), original.name.substringBeforeLast('.'), Source.Original(original), true, false, Recipe.load(app))
        jobs += job
        changed()
        transfer.execute {
            try {
                job.status = Status.DOWNLOADING
                job.detail = "读取原片"
                changed()
                val file = Library.copyToCache(app, Uri.parse(original.uri), safe(original.name))
                developer.execute { develop(app, job, file, original.cameraKey, original) }
            } catch (e: Throwable) {
                fail(job, e)
            }
        }
    }

    fun clearFinished() {
        jobs.removeAll { it.finished }
        changed()
    }

    // ---- transfer ------------------------------------------------------------------------------

    private fun fetch(app: Context, job: Job) {
        val item = (job.source as Source.Camera).item
        try {
            val info: ObjectInfo = item.raw ?: item.jpeg ?: throw IllegalStateException("没有可冲洗的照片文件（${item.badge}）")
            job.status = Status.DOWNLOADING
            changed()
            // already on the phone: develop from that copy
            val existing = Library.originalOf(item.key)
            val file = File(File(app.cacheDir, "work").apply { mkdirs() }, safe(info.filename))
            val cached = CameraHub.cachedOriginal(info)
            if (existing != null && runCatching { Library.copyToCache(app, Uri.parse(existing.uri), file.name) }.isSuccess) {
                job.detail = "原片已在手机上"
            } else if (cached != null && runCatching { cached.copyTo(file, overwrite = true) }.isSuccess) {
                // copied over already when its large preview was opened
                job.detail = "原片已传到手机"
                if (Prefs.keepOriginal(app) || !job.develop) saveOriginal(app, job, file, info)
            } else {
                val started = System.currentTimeMillis()
                file.outputStream().buffered(1 shl 20).use { out ->
                    CameraHub.download(info, out) { got, total ->
                        job.progress = if (total > 0) got.toFloat() / total else 0f
                        job.detail = "%.1f / %.1f MB".format(got / 1048576.0, total / 1048576.0)
                        changed(force = false)
                    }
                }
                val seconds = (System.currentTimeMillis() - started) / 1000.0
                CameraHub.lastSpeed = file.length() / 1048576.0 / seconds.coerceAtLeast(0.01)
                CameraHub.note("传输 ${info.filename}：%.1f MB，%.1f 秒（%.1f MB/s）".format(
                    file.length() / 1048576.0, seconds, file.length() / 1048576.0 / seconds.coerceAtLeast(0.01)))
                if (Prefs.keepOriginal(app) || !job.develop) saveOriginal(app, job, file, info)
            }
            job.progress = 1f
            if (!job.develop) {
                file.delete()
                job.status = Status.DONE
                job.detail = "原片已存到手机"
                changed()
                return
            }
            developer.execute { develop(app, job, file, item.key, Library.originalOf(item.key)) }
        } catch (e: Throwable) {
            fail(job, e)
        }
    }

    private fun saveOriginal(app: Context, job: Job, file: File, info: ObjectInfo) {
        if (Library.originalOf(info.key) != null) return
        job.detail = "存原片"
        changed()
        val uri = Library.saveOriginal(app, file, info.filename)
        val entry = Library.Entry(
            Library.nextId(), Library.ORIGINAL, uri.toString(), info.filename, Library.mimeOf(info.extension),
            info.key, null, System.currentTimeMillis(), info.captureTime, info.imageWidth, info.imageHeight,
            null, emptyMap(), "",
        )
        Library.add(app, entry)
        job.result = entry
        // the camera's thumbnail stands for the original in the library
        CameraHub.cachedThumb(info)?.let { bmp ->
            runCatching { CameraHub.diskThumb(info.key).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
        }
    }

    // ---- develop -------------------------------------------------------------------------------

    private fun develop(app: Context, job: Job, file: File, cameraKey: String?, original: Library.Entry?) {
        try {
            job.status = Status.DEVELOPING
            job.progress = 0f
            job.detail = "启动 GPU"
            changed()
            val engine = FilmEngine.get(app)
            val recipe = job.recipe
            val longEdge = Prefs.longEdge(app)
            val seed = Random.nextInt(1, Int.MAX_VALUE)
            val ext = file.extension.uppercase(Locale.ROOT)
            val started = System.currentTimeMillis()
            val developed: org.photoall.tether.engine.Developed
            var meta: Map<String, String> = emptyMap()
            if (ext in ObjectInfo.RAW_EXTENSIONS) {
                job.detail = "解码 RAW"
                changed()
                val key = RawFile.embeddedJpeg(file.path)?.let { Brightness.key(it) } ?: 0f
                RawFile.open(file.path, longEdge).use { raw ->
                    meta = raw.meta
                    job.detail = "冲洗"
                    changed()
                    developed = engine.developRaw(raw, recipe, longEdge, key, seed, app)
                }
                Preview.offer(app, file)
            } else {
                job.detail = "冲洗 JPEG"
                changed()
                val bitmap = decodeJpeg(file, longEdge) ?: throw IllegalStateException("无法读取 ${file.name}")
                meta = jpegMeta(file)
                developed = engine.developBitmap(bitmap, recipe, longEdge, seed, app)
                bitmap.recycle()
            }
            job.detail = "存储"
            changed()
            val name = "${job.title}_${safe(Films.short(recipe.film)).replace(" ", "")}.jpg".takeIf { recipe.filmOn }
                ?: "${job.title}_print.jpg"
            val summary = recipe.summary()
            val uri = Library.saveJpeg(app, developed.bitmap, name, Prefs.jpegQuality(app), Library.exifFor(meta, summary))
            val entry = Library.Entry(
                Library.nextId(), Library.PRINT, uri.toString(), name, "image/jpeg", cameraKey, original?.uri,
                System.currentTimeMillis(), meta["time"]?.toLongOrNull()?.times(1000) ?: 0L,
                developed.bitmap.width, developed.bitmap.height, recipe.toJson(), meta,
                developed.note + "冲洗结果\t总用时\t%.1f 秒\n".format((System.currentTimeMillis() - started) / 1000.0),
            )
            Library.add(app, entry)
            job.thumb = Bitmap.createScaledBitmap(developed.bitmap, 240, 240 * developed.bitmap.height / developed.bitmap.width, true)
            developed.bitmap.recycle()
            job.result = entry
            job.status = Status.DONE
            job.progress = 1f
            job.detail = "%.1f 秒 · %s".format((System.currentTimeMillis() - started) / 1000.0, summary)
            developedThisSession++
            CameraHub.note("冲洗 ${job.title}：${job.detail}")
            changed()
            TetherService.update(app)
        } catch (e: Throwable) {
            fail(job, e)
        } finally {
            if (file.exists() && file.parentFile?.name == "work") file.delete()
        }
    }

    private fun decodeJpeg(file: File, longEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longEdge) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        // upright from the EXIF orientation
        val degrees = runCatching {
            when (android.media.ExifInterface(file.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
            }
        }.getOrDefault(0f)
        if (degrees == 0f) return bitmap
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
            android.graphics.Matrix().apply { postRotate(degrees) }, true)
        bitmap.recycle()
        return turned
    }

    private fun jpegMeta(file: File): Map<String, String> = runCatching {
        val e = android.media.ExifInterface(file.path)
        buildMap {
            e.getAttribute(android.media.ExifInterface.TAG_MAKE)?.let { put("make", it) }
            e.getAttribute(android.media.ExifInterface.TAG_MODEL)?.let { put("model", it) }
            e.getAttribute("LensModel")?.let { put("lens", it) }
            e.getAttribute(android.media.ExifInterface.TAG_ISO_SPEED_RATINGS)?.let { put("iso", it) }
            e.getAttribute(android.media.ExifInterface.TAG_EXPOSURE_TIME)?.let { put("shutter", it) }
            e.getAttribute(android.media.ExifInterface.TAG_F_NUMBER)?.let { put("aperture", it) }
            e.getAttributeDouble(android.media.ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.let { put("focal", it.toString()) }
            e.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL)?.let { text ->
                runCatching { java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(text)?.time }
                    .getOrNull()?.let { put("time", (it / 1000).toString()) }
            }
        }
    }.getOrDefault(emptyMap())

    private fun fail(job: Job, e: Throwable) {
        job.status = Status.FAILED
        job.error = e.message ?: e.toString()
        job.detail = job.error ?: ""
        CameraHub.note("${job.title} 失败：${job.error}")
        val what = when (val src = job.source) {
            is Source.Camera -> "相机文件 " + src.item.files.joinToString { "${it.filename}（${it.size} 字节，句柄 0x%08X）".format(it.handle) }
            is Source.Local -> "手机文件 ${src.name}"
            is Source.Original -> "原片 ${src.entry.name}"
        }
        org.photoall.tether.ErrorLog.record("${if (job.develop) "冲洗" else "导入"} ${job.title} 失败", e,
            "${what}\n配方：${job.recipe.summary()}\n" + (if (job.source is Source.Camera) CameraHub.usbTrace() else ""))
        changed()
    }

    private fun safe(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
}
