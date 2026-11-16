package org.photoall.tether.develop

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.photoall.tether.engine.Brightness
import org.photoall.tether.engine.FilmEngine
import org.photoall.tether.engine.RawFile
import org.photoall.tether.engine.Recipe
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * The recipe screen's live picture: the latest raw (kept decoded) developed small with the
 * recipe being edited. Only the newest request is rendered.
 */
object Preview {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "preview") }
    private val main = Handler(Looper.getMainLooper())
    private val pending = AtomicReference<Pair<Recipe, (Bitmap?, String?) -> Unit>?>(null)
    private var raw: RawFile? = null
    private var rawPath: String? = null
    private var key = 0f
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun dir(context: Context) = File(context.cacheDir, "preview").apply { mkdirs() }

    /** The raw the preview shows, or null. */
    fun source(context: Context): File? = dir(context).listFiles()?.firstOrNull { it.name.startsWith("latest.") }

    /** Takes [file] (moved, not copied) as the preview's picture. */
    fun offer(context: Context, file: File) {
        // moved now (the caller deletes its work file afterwards), swapped in on the worker
        val staged = File(dir(context), "incoming_${System.nanoTime()}.${file.extension}")
        if (!file.renameTo(staged)) return
        worker.execute {
            closeRaw()
            dir(context).listFiles()?.filter { it.name.startsWith("latest.") }?.forEach { it.delete() }
            staged.renameTo(File(dir(context), "latest.${staged.extension}"))
            main.post { listeners.toList().forEach { it() } }
        }
    }

    /** Uses a library original as the preview's picture. */
    fun useOriginal(context: Context, entry: Library.Entry) {
        val app = context.applicationContext
        worker.execute {
            runCatching {
                val file = Library.copyToCache(app, Uri.parse(entry.uri), "pick.${entry.name.substringAfterLast('.')}")
                offer(app, file)
            }
        }
    }

    private fun closeRaw() {
        raw?.close()
        raw = null
        rawPath = null
    }

    /** Renders [recipe] (replacing any request not started yet); [done] gets the picture or an error. */
    fun render(context: Context, recipe: Recipe, done: (Bitmap?, String?) -> Unit) {
        val app = context.applicationContext
        pending.set(recipe to done)
        worker.execute {
            val (r, callback) = pending.getAndSet(null) ?: return@execute
            try {
                val file = source(app) ?: run {
                    main.post { callback(null, null) }
                    return@execute
                }
                if (rawPath != file.path) {
                    closeRaw()
                    key = RawFile.embeddedJpeg(file.path)?.let { Brightness.key(it) } ?: 0f
                    raw = RawFile.open(file.path, 1280)
                    rawPath = file.path
                }
                val developed = FilmEngine.get(app).developRaw(raw!!, r, 1280, key, 7, app)
                main.post { callback(developed.bitmap, null) }
            } catch (e: Throwable) {
                main.post { callback(null, e.message ?: e.toString()) }
            }
        }
    }
}
