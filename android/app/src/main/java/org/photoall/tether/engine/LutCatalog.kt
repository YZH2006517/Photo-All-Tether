package org.photoall.tether.engine

import android.content.Context
import android.util.LruCache
import org.json.JSONObject

/** The creative LUTs bundled with the phone app (assets/luts): 33^3 RGB8, sRGB in and out. */
object LutCatalog {
    class Lut(val id: String, val label: String, val group: String)

    @Volatile var all: List<Lut> = emptyList()
        private set
    var size = 33
        private set
    private val cache = LruCache<String, ByteArray>(6)

    fun load(context: Context) {
        if (all.isNotEmpty()) return
        runCatching {
            val json = JSONObject(context.assets.open("luts/manifest.json").bufferedReader().use { it.readText() })
            size = json.optInt("size", 33)
            val array = json.getJSONArray("luts")
            all = List(array.length()) { i ->
                val o = array.getJSONObject(i)
                Lut(o.getString("id"), o.getString("label"), o.getString("group"))
            }
        }
    }

    fun groups(): List<String> = all.map { it.group }.distinct()

    fun label(id: String?): String = all.firstOrNull { it.id == id }?.label ?: (id ?: "无 LUT")

    fun table(context: Context, id: String): ByteArray? {
        cache.get(id)?.let { return it }
        return runCatching { context.assets.open("luts/$id.rgb").use { it.readBytes() } }.getOrNull()
            ?.takeIf { it.size == size * size * size * 3 }
            ?.also { cache.put(id, it) }
    }
}
