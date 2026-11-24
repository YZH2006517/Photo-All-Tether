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
    /** The immersive view's entrance frames: about its screen's size, light enough for eight of them. */
    private const val REVEAL_EDGE = 1440

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
        // for the immersive view's live readout: the file, its transfer, the develop
        @Volatile var fileName: String? = null
        @Volatile var bytes = 0L
        @Volatile var totalBytes = 0L
        @Volatile var transferStarted = 0L
        @Volatile var transferEnded = 0L
        /** How the file got here when it was not copied off the camera just now. */
        @Volatile var transferNote: String? = null
        @Volatile var developStarted = 0L
        @Volatile var developEnded = 0L
        @Volatile var created = System.currentTimeMillis()
        /** The steps this shot goes through (the live readout's), and which one it is at. */
        @Volatile var plan: List<String> = listOf("传输", "解码 RAW", "冲洗", "存储")
        @Volatile var step = 0
        @Volatile var stepStarted = System.currentTimeMillis()
        fun at(name: String) {
            val i = plan.indexOf(name)
            if (i >= 0) { step = i; stepStarted = System.currentTimeMillis() }
        }
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
            job.plan = listOf("传输") + (if (info.isRaw) listOf("解码 RAW") else emptyList()) + listOf("冲洗", "存储")
            job.at("传输")
            job.fileName = info.filename
            job.totalBytes = info.size
            job.transferStarted = System.currentTimeMillis()
            changed()
            // already on the phone: develop from that copy
            val existing = Library.originalOf(item.key)
            val file = File(File(app.cacheDir, "work").apply { mkdirs() }, safe(info.filename))
            val cached = CameraHub.cachedOriginal(info)
            if (existing != null && runCatching { Library.copyToCache(app, Uri.parse(existing.uri), file.name) }.isSuccess) {
                job.detail = "原片已在手机上"
                job.transferNote = job.detail
            } else if (cached != null && runCatching { cached.copyTo(file, overwrite = true) }.isSuccess) {
                // copied over already when its large preview was opened
                job.detail = "原片已传到手机"
                job.transferNote = job.detail
                if (Prefs.keepOriginal(app) || !job.develop) saveOriginal(app, job, file, info)
            } else {
                val started = System.currentTimeMillis()
                file.outputStream().buffered(1 shl 20).use { out ->
                    CameraHub.download(info, out) { got, total ->
                        job.bytes = got
                        if (total > 0) job.totalBytes = total
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
            job.bytes = job.totalBytes
            job.transferEnded = System.currentTimeMillis()
            if (!job.develop) {
                file.delete()
                job.status = Status.DONE
                job.detail = "原片已存到手机"
                changed()
                return
            }
            developer.execute { develop(app, job, file, item.key, Library.originalOf(item.key)) { pairedJpeg(app, item) } }
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

    // RAW+JPEG from the camera: the JPEG of the pair (looked up again, it may have come after the
    // raw), copied to the work folder; null when the shot has none.
    private fun pairedJpeg(app: Context, item: CameraItem): File? {
        val jpeg = item.jpeg ?: CameraHub.items.firstOrNull { it.key == item.key }?.jpeg ?: return null
        val file = File(File(app.cacheDir, "work").apply { mkdirs() }, safe(jpeg.filename))
        val cached = CameraHub.cachedOriginal(jpeg)
        if (cached != null) cached.copyTo(file, overwrite = true)
        else file.outputStream().buffered(1 shl 20).use { out -> CameraHub.download(jpeg, out) { _, _ -> } }
        return file
    }

    /**
     * Develops [file]. A raw this app cannot decode (Nikon High Efficiency, a body or compression
     * LibRaw does not know yet) is developed from [jpegFallback] instead when that gives a file
     * (the JPEG of a RAW+JPEG shot), else from the full-size JPEG the raw carries inside, so the
     * shot is not lost; the reason is kept in the job and the log.
     */
    private fun develop(app: Context, job: Job, file: File, cameraKey: String?, original: Library.Entry?,
                        jpegFallback: (() -> File?)? = null) {
        var fallbackFile: File? = null
        try {
            job.status = Status.DEVELOPING
            job.progress = 0f
            job.developStarted = System.currentTimeMillis()
            if (job.fileName == null) job.fileName = file.name
            job.detail = "启动 GPU"
            changed()
            val engine = FilmEngine.get(app)
            val recipe = job.recipe
            val longEdge = Prefs.longEdge(app)
            val seed = Random.nextInt(1, Int.MAX_VALUE)
            val ext = file.extension.uppercase(Locale.ROOT)
            val started = System.currentTimeMillis()
            var done: org.photoall.tether.engine.Developed? = null
            var meta: Map<String, String> = emptyMap()
            var source = file
            var rawProblem: String? = null
            var fallbackWhat = "JPEG"          // what was developed instead of the raw
            var orientationFrom: File? = null  // an embedded JPEG: the raw's own EXIF turns it upright
            var reveal: List<Reveal.Frame> = emptyList() // the immersive view's entrance, rendered by the engine
            if (ext in ObjectInfo.RAW_EXTENSIONS) {
                job.detail = "解码 RAW"
                job.at("解码 RAW")
                changed()
                val key = RawFile.embeddedJpeg(file.path)?.let { Brightness.key(it) } ?: 0f
                val raw = try {
                    RawFile.open(file.path, longEdge)
                } catch (e: Exception) {
                    // RAW+JPEG: develop the JPEG of the pair rather than fail the shot
                    var jpeg: File? = null
                    if (jpegFallback != null) {
                        job.detail = "RAW 解不了，取同一张的 JPEG"
                        changed()
                        jpeg = runCatching { jpegFallback() }
                            .onFailure { CameraHub.note("${job.title}：取同一张的 JPEG 失败：${it.message}") }
                            .getOrNull()
                    }
                    // otherwise the full-size JPEG inside the raw (a NEF, CR3, ARW ... carries one)
                    if (jpeg == null) {
                        jpeg = embeddedFallback(app, file)
                        if (jpeg != null) {
                            orientationFrom = file
                            fallbackWhat = "RAW 内嵌 JPEG"
                        }
                    }
                    if (jpeg == null) throw e
                    rawProblem = e.message ?: e.toString()
                    CameraHub.note("${job.title}：$rawProblem；改用 ${if (orientationFrom != null) "RAW 内嵌的 JPEG" else jpeg.name} 冲洗")
                    fallbackFile = jpeg
                    source = jpeg
                    null
                }
                if (raw != null) {
                    raw.use {
                        meta = it.meta
                        // the immersive view is open: the engine's steps first, each shown there as it
                        // is rendered (the live darkroom), then the print at full size
                        if (Reveal.wanted) {
                            val steps = Reveal.plan(recipe)
                            job.plan = job.plan.takeWhile { s -> s != "冲洗" } + steps + listOf("全尺寸冲洗", "存储")
                            reveal = runCatching {
                                Reveal.render(engine, it, recipe, key, seed, app, REVEAL_EDGE, Prefs.displayP3(app), job.id) { step ->
                                    job.detail = step
                                    job.at(step)
                                    changed(force = false)
                                }
                            }.onFailure { e -> CameraHub.note("${job.title}：显影动画未生成：${e.message}") }.getOrDefault(emptyList())
                        } else job.plan = job.plan.map { s -> if (s == "冲洗") "全尺寸冲洗" else s }
                        job.detail = "全尺寸冲洗"
                        job.at("全尺寸冲洗")
                        changed()
                        done = engine.developRaw(it, recipe, longEdge, key, seed, app, Prefs.displayP3(app), tiffFor(app, job, recipe, meta))
                    }
                    Preview.offer(app, file)
                }
            }
            if (done == null) {
                job.detail = "冲洗 JPEG"
                job.at("冲洗")
                changed()
                val bitmap = decodeJpeg(source, longEdge, orientationFrom) ?: throw IllegalStateException("无法读取 ${source.name}")
                meta = jpegMeta(orientationFrom ?: source)
                done = engine.developBitmap(bitmap, recipe, longEdge, seed, app, Prefs.displayP3(app), tiffFor(app, job, recipe, meta))
                bitmap.recycle()
            }
            val developed = done!!
            job.detail = "存储"
            job.at("存储")
            changed()
            val name = "${job.title}_${safe(Films.short(recipe.film)).replace(" ", "")}.jpg".takeIf { recipe.filmOn }
                ?: "${job.title}_print.jpg"
            val summary = recipe.summary()
            val uri = Library.saveJpeg(app, developed.bitmap, name, Prefs.jpegQuality(app), Library.exifFor(meta, summary))
            // the lossless master beside it (16-bit TIFF), when asked for
            val tiff = tiffFile(app, job)
            if (Prefs.lossless(app) && !tiff.exists()) CameraHub.note("${job.title}：无损 TIFF 没生成")
            if (tiff.exists()) {
                job.detail = "存储无损 TIFF"
                changed()
                runCatching { Library.saveFile(app, tiff, name.substringBeforeLast('.') + ".tif", "image/tiff") }
                    .onSuccess { meta = meta + ("lossless" to it.toString()) }
                    .onFailure { CameraHub.note("${job.title}：无损 TIFF 没存上：${it.message}") }
                tiff.delete()
            }
            val entry = Library.Entry(
                Library.nextId(), Library.PRINT, uri.toString(), name, "image/jpeg", cameraKey, original?.uri,
                System.currentTimeMillis(), meta["time"]?.toLongOrNull()?.times(1000) ?: 0L,
                developed.bitmap.width, developed.bitmap.height, recipe.toJson(), meta,
                developed.note + (rawProblem?.let { "冲洗结果\t来源\t$fallbackWhat（RAW 解不了：$it）\n" } ?: "") +
                    "冲洗结果\t总用时\t%.1f 秒\n".format((System.currentTimeMillis() - started) / 1000.0),
            )
            Reveal.offer(uri.toString(), reveal) // before the library tells the immersive view
            // the raw just handed to the recipe preview is this print's (the tuning bench uses it)
            if (source == file && ext in ObjectInfo.RAW_EXTENSIONS) Preview.latestPrint = uri.toString()
            Library.add(app, entry)
            job.thumb = Bitmap.createScaledBitmap(developed.bitmap, 240, 240 * developed.bitmap.height / developed.bitmap.width, true)
            developed.bitmap.recycle()
            job.result = entry
            job.developEnded = System.currentTimeMillis()
            job.status = Status.DONE
            job.progress = 1f
            job.detail = "%.1f 秒 · %s".format((System.currentTimeMillis() - started) / 1000.0, summary) +
                (if (rawProblem != null) " · 用 $fallbackWhat 冲洗（RAW 不支持）" else "")
            developedThisSession++
            CameraHub.note("冲洗 ${job.title}：${job.detail}")
            changed()
            TetherService.update(app)
        } catch (e: Throwable) {
            fail(job, e)
        } finally {
            if (file.exists() && file.parentFile?.name == "work") file.delete()
            fallbackFile?.takeIf { it.exists() && it.parentFile?.name == "work" }?.delete()
        }
    }

    /**
     * The largest JPEG inside [raw], written to the work folder, when it is large enough to make a
     * print of (1500 px on the long edge); null otherwise.
     */
    private fun embeddedFallback(app: Context, raw: File): File? {
        val bytes = RawFile.embeddedJpeg(raw.path) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (maxOf(bounds.outWidth, bounds.outHeight) < 1500) return null
        return File(File(app.cacheDir, "work").apply { mkdirs() }, raw.nameWithoutExtension + "_embedded.jpg")
            .apply { writeBytes(bytes) }
    }

    private fun tiffFile(app: Context, job: Job) = File(File(app.cacheDir, "work").apply { mkdirs() }, "lossless_${job.id}.tif")

    /** The 16-bit TIFF to write with this develop (the lossless master), when the setting asks for one. */
    private fun tiffFor(app: Context, job: Job, recipe: Recipe, meta: Map<String, String>): org.photoall.tether.engine.TiffRequest? {
        if (!Prefs.lossless(app)) return null
        val time = meta["time"]?.toLongOrNull()?.takeIf { it > 0 }?.let {
            java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(java.util.Date(it * 1000))
        } ?: ""
        return org.photoall.tether.engine.TiffRequest(tiffFile(app, job).path, meta["make"] ?: "", meta["model"] ?: "",
            "Photo All 联机 · " + recipe.summary(), time)
    }

    /** [orientationFrom]: the file whose EXIF orientation applies when the JPEG has none of its own (a raw's preview). */
    private fun decodeJpeg(file: File, longEdge: Int, orientationFrom: File? = null): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longEdge) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        // upright from the EXIF orientation
        fun orientation(f: File) = runCatching {
            android.media.ExifInterface(f.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
        }.getOrDefault(1)
        val own = orientation(file)
        val degrees = when (if (own == 1 && orientationFrom != null) orientation(orientationFrom) else own) {
            6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
        }
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
