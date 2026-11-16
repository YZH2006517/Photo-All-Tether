package org.photoall.tether.develop

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * The roll in the camera: which stock, and how many of its 36 frames are exposed. Each shot
 * the camera sends advances it; the 36th finishes the roll (and a fresh roll of the same stock
 * is loaded). Choosing another stock in the recipe loads a new roll of it.
 */
object Roll {
    const val FRAMES = 36

    sealed class Event {
        class Advanced(val frame: Int) : Event()
        class Finished(val film: Int) : Event()
        class Loaded(val film: Int) : Event()
    }

    private val listeners = mutableListOf<(Event) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: (Event) -> Unit) { listeners += l }
    fun removeListener(l: (Event) -> Unit) { listeners -= l }
    private fun emit(e: Event) = main.post { listeners.toList().forEach { it(e) } }

    private fun p(c: Context) = c.getSharedPreferences("roll", Context.MODE_PRIVATE)

    fun film(c: Context) = p(c).getInt("film", -1)
    fun frames(c: Context) = p(c).getInt("frames", 0).coerceIn(0, FRAMES)
    /** Rolls finished since the app was installed. */
    fun finished(c: Context) = p(c).getInt("finished", 0)

    /** A new roll of [film] in the camera, at frame 0. */
    fun load(c: Context, film: Int) {
        p(c).edit().putInt("film", film).putInt("frames", 0).apply()
        emit(Event.Loaded(film))
    }

    /** The recipe's stock changed: a roll of it goes in (unless it is the one loaded). */
    fun follow(c: Context, film: Int) {
        if (film(c) != film) load(c, film)
    }

    /** One frame exposed. */
    fun shot(c: Context) {
        val n = frames(c) + 1
        if (n >= FRAMES) {
            val film = film(c)
            p(c).edit().putInt("frames", FRAMES).putInt("finished", finished(c) + 1).apply()
            emit(Event.Advanced(FRAMES))
            emit(Event.Finished(film))
            // the next roll of the same stock, once the rewind has played
            main.postDelayed({ p(c).edit().putInt("frames", 0).apply(); emit(Event.Loaded(film)) }, 2600)
        } else {
            p(c).edit().putInt("frames", n).apply()
            emit(Event.Advanced(n))
        }
    }
}
