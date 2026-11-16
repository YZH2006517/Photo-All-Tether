package org.photoall.tether.engine

import android.content.Context
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The creative LUTs: those bundled with the phone app (assets/luts, 33^3 RGB8, sRGB in and out)
 * and those the user imported (files/luts/user, brought to the same form by [LutImport]), with
 * the user's favourites and the ones used lately.
 */
object LutCatalog {
    class Lut(val id: String, val label: String, val group: String, val user: Boolean = false,
              val format: String = "", val sourceSize: Int = 0, val added: Long = 0L)

    /** The group an import goes to unless told otherwise. */
    const val MY_GROUP = "我的 LUT"

    @Volatile var all: List<Lut> = emptyList()
        private set
    @Volatile private var bundled: List<Lut> = emptyList()
    var size = 33
        private set
    private val cache = LruCache<String, ByteArray>(12)
    private var loaded = false
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = listeners.toList().forEach { it() }

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val json = JSONObject(context.assets.open("luts/manifest.json").bufferedReader().use { it.readText() })
            size = json.optInt("size", 33)
            val array = json.getJSONArray("luts")
            bundled = List(array.length()) { i ->
                val o = array.getJSONObject(i)
                Lut(o.getString("id"), o.getString("label"), o.getString("group"))
            }
        }
        all = users(context) + bundled
    }

    /** The groups in order: the user's own first, then the bundled ones. */
    fun groups(): List<String> = all.map { it.group }.distinct()
    fun userGroups(): List<String> = all.filter { it.user }.map { it.group }.distinct()

    fun find(id: String?): Lut? = all.firstOrNull { it.id == id }
    fun label(id: String?): String = find(id)?.label ?: (id?.substringAfterLast('/') ?: "无 LUT")

    fun table(context: Context, id: String): ByteArray? {
        cache.get(id)?.let { return it }
        val bytes = runCatching {
            if (id.startsWith("user/")) file(context, id).readBytes()
            else context.assets.open("luts/$id.rgb").use { it.readBytes() }
        }.getOrNull()
        return bytes?.takeIf { it.size == size * size * size * 3 }?.also { cache.put(id, it) }
    }

    // ---- the user's own -------------------------------------------------------------------------

    private fun dir(context: Context) = File(context.filesDir, "luts/user").apply { mkdirs() }
    private fun file(context: Context, id: String) = File(dir(context), id.removePrefix("user/") + ".rgb")
    private fun index(context: Context) = File(context.filesDir, "luts/user.json")

    private fun users(context: Context): List<Lut> = runCatching {
        val a = JSONArray(index(context).readText())
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Lut(o.getString("id"), o.getString("label"), o.optString("group", MY_GROUP), true,
                o.optString("format"), o.optInt("sourceSize"), o.optLong("added"))
        }.filter { file(context, it.id).exists() }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun saveUsers(context: Context) {
        val a = JSONArray()
        all.filter { it.user }.forEach { l ->
            a.put(JSONObject().put("id", l.id).put("label", l.label).put("group", l.group)
                .put("format", l.format).put("sourceSize", l.sourceSize).put("added", l.added))
        }
        val tmp = File(index(context).path + ".tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(index(context))
    }

    /** Keeps an imported LUT (already 33^3) in [group]; returns it. */
    @Synchronized
    fun add(context: Context, parsed: LutImport.Parsed, group: String): Lut {
        load(context)
        val id = "user/" + UUID.randomUUID().toString().replace("-", "").take(16)
        file(context, id).writeBytes(parsed.rgb)
        // a name already taken in the group gets a number
        var label = parsed.name.trim().ifEmpty { "LUT" }
        val taken = all.filter { it.group == group }.map { it.label }.toSet()
        if (label in taken) { var k = 2; while ("$label $k" in taken) k++; label = "$label $k" }
        val lut = Lut(id, label, group, true, parsed.format, parsed.sourceSize, System.currentTimeMillis())
        all = listOf(lut) + all
        saveUsers(context)
        return lut
    }

    /** After a batch of [add]s: tell the pages once. */
    fun announce() = changed()

    @Synchronized
    fun rename(context: Context, id: String, label: String) {
        all = all.map { if (it.id == id && it.user) Lut(it.id, label.trim().ifEmpty { it.label }, it.group, true, it.format, it.sourceSize, it.added) else it }
        saveUsers(context); changed()
    }

    @Synchronized
    fun move(context: Context, id: String, group: String) {
        all = all.map { if (it.id == id && it.user) Lut(it.id, it.label, group.trim().ifEmpty { MY_GROUP }, true, it.format, it.sourceSize, it.added) else it }
        // the user's groups stay together at the front
        all = all.filter { it.user }.sortedBy { userGroups().indexOf(it.group) } + all.filter { !it.user }
        saveUsers(context); changed()
    }

    @Synchronized
    fun delete(context: Context, ids: Collection<String>) {
        ids.filter { it.startsWith("user/") }.forEach { file(context, it).delete(); cache.remove(it) }
        all = all.filter { !(it.user && it.id in ids) }
        val p = prefs(context)
        p.edit().putStringSet("favourites", favourites(context) - ids.toSet())
            .putString("recent", JSONArray(recent(context).filter { it !in ids }).toString()).apply()
        saveUsers(context); changed()
    }

    // ---- favourites, lately used -------------------------------------------------------------------

    private fun prefs(context: Context) = context.getSharedPreferences("luts", Context.MODE_PRIVATE)

    fun favourites(context: Context): Set<String> = prefs(context).getStringSet("favourites", emptySet()) ?: emptySet()
    fun isFavourite(context: Context, id: String) = id in favourites(context)
    fun toggleFavourite(context: Context, id: String): Boolean {
        val now = favourites(context).toMutableSet()
        val on = !now.remove(id)
        if (on) now += id
        prefs(context).edit().putStringSet("favourites", now).apply()
        changed()
        return on
    }

    fun recent(context: Context): List<String> = runCatching {
        val a = JSONArray(prefs(context).getString("recent", "[]"))
        List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())

    fun used(context: Context, id: String) {
        val list = (listOf(id) + recent(context).filter { it != id }).take(24)
        prefs(context).edit().putString("recent", JSONArray(list).toString()).apply()
    }
}
