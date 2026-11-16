package org.photoall.tether.engine

import android.content.Context
import org.json.JSONObject

/**
 * How a photo is developed: the film, the paper and the creative LUT, and the few controls a
 * camera user reaches for (exposure, white balance, grain, halation, noise). Parameters the
 * engine has beyond these keep filmsim's defaults.
 */
data class Recipe(
    val film: Int = 4,              // index into FilmEngine.filmStocks (4: Portra 400)
    val paper: Int = 3,             // index into FilmEngine.papers (3: Portra Endura)
    val filmOn: Boolean = true,     // off: the raw under a neutral tone curve
    val printOn: Boolean = true,    // off: the negative scanned (slides as they are)
    val lut: String? = null,        // LutCatalog id
    val lutOn: Boolean = true,
    val lutStrength: Float = 0.8f,
    val ev: Float = 0f,
    val matchCamera: Boolean = true, // start from the brightness of the camera's own JPEG
    val white: Int = WHITE_CAMERA,
    val kelvin: Float = 5500f,
    val tint: Float = 0f,
    val balance: Int = BALANCE_NEUTRAL,
    val grain: Boolean = true,
    val grainSize: Float = 1f,
    val halation: Boolean = true,
    val halationAmount: Float = 1f,
    val couplers: Boolean = true,
    val rawDenoise: Float = 0.3f,
    val chromaDenoise: Float = 0.5f,
    /** The advanced filmsim parameters set away from their defaults (FilmParamsCatalog keys). */
    val extra: Map<String, Float> = emptyMap(),
) {
    /** filmsim parameters (vkdt names) and the engine's stage switches. */
    fun params(): Map<String, Float> {
        val p = linkedMapOf(
            "film" to film.toFloat(),
            "paper" to paper.toFloat(),
            "grain" to if (grain) 1f else 0f,
            "size" to grainSize,
            "halation" to if (halation) 1f else 0f,
            "hal amt" to halationAmount,
            "couplers" to if (couplers) 1f else 0f,
            "stage film" to if (filmOn) 1f else 0f,
            "stage print" to if (printOn) 1f else 0f,
        )
        for ((k, v) in extra) if (FilmParamsCatalog.byKey.containsKey(k)) p[k] = v
        // the enlarger: the engine's fitted packs (-2 reference film, -1 neutral), or the user's
        when (balance) {
            BALANCE_MANUAL -> {
                p["filter c"] = extra["filter c"] ?: 0f
                p["filter m"] = extra["filter m"] ?: 0f
                p["filter y"] = extra["filter y"] ?: 0f
            }
            else -> {
                p["filter c"] = if (balance == BALANCE_FILM) -2f else -1f
                p.remove("filter m")
                p.remove("filter y")
            }
        }
        // filmsim reads illuminants below its slider range as "off"
        for (k in listOf("scne ill", "film ill")) if ((p[k] ?: 0f) < 2000f) p.remove(k)
        return p
    }

    /** How many advanced parameters of [group] differ from their defaults. */
    fun changed(group: FilmParamsCatalog.Group): Int =
        group.params.count { prm -> extra[prm.key]?.let { kotlin.math.abs(it - prm.default) > 1e-4f } == true }

    fun value(key: String): Float = extra[key] ?: FilmParamsCatalog.byKey[key]?.default ?: 0f

    fun withParam(key: String, v: Float): Recipe {
        val d = FilmParamsCatalog.byKey[key]?.default
        return copy(extra = if (d != null && kotlin.math.abs(v - d) < 1e-4f) extra - key else extra + (key to v))
    }

    fun kelvinFor(): Float = when (white) {
        WHITE_DAYLIGHT -> 5500f
        WHITE_TUNGSTEN -> 3200f
        WHITE_FILM -> if (Films.isTungsten(film)) 3200f else 5500f
        else -> kelvin
    }

    /** "Portra 400 · Endura · LUT 名" */
    fun summary(): String = buildString {
        append(if (filmOn) Films.short(film) else "无胶片")
        if (filmOn && printOn) append(" · ").append(Films.paperShort(paper))
        if (lut != null && lutOn) append(" · ").append(LutCatalog.label(lut))
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("film", film); put("paper", paper); put("filmOn", filmOn); put("printOn", printOn)
        put("lut", lut ?: ""); put("lutOn", lutOn); put("lutStrength", lutStrength.toDouble())
        put("ev", ev.toDouble()); put("matchCamera", matchCamera)
        put("white", white); put("kelvin", kelvin.toDouble()); put("tint", tint.toDouble())
        put("balance", balance); put("grain", grain); put("grainSize", grainSize.toDouble())
        put("halation", halation); put("halationAmount", halationAmount.toDouble()); put("couplers", couplers)
        put("rawDenoise", rawDenoise.toDouble()); put("chromaDenoise", chromaDenoise.toDouble())
        put("extra", JSONObject().apply { extra.forEach { (k, v) -> put(k, v.toDouble()) } })
    }

    companion object {
        const val WHITE_CAMERA = 0
        const val WHITE_FILM = 1
        const val WHITE_DAYLIGHT = 2
        const val WHITE_TUNGSTEN = 3
        const val WHITE_KELVIN = 4
        val whiteNames = listOf("相机", "按胶片", "日光", "钨丝灯", "色温")

        const val BALANCE_FILM = 0
        const val BALANCE_NEUTRAL = 1
        const val BALANCE_MANUAL = 2
        val balanceNames = listOf("保留胶片本色", "自动中性", "手动")

        fun fromJson(o: JSONObject): Recipe {
            val d = Recipe()
            return Recipe(
                film = o.optInt("film", d.film), paper = o.optInt("paper", d.paper),
                filmOn = o.optBoolean("filmOn", d.filmOn), printOn = o.optBoolean("printOn", d.printOn),
                lut = o.optString("lut", "").ifEmpty { null }, lutOn = o.optBoolean("lutOn", d.lutOn),
                lutStrength = o.optDouble("lutStrength", d.lutStrength.toDouble()).toFloat(),
                ev = o.optDouble("ev", 0.0).toFloat(), matchCamera = o.optBoolean("matchCamera", d.matchCamera),
                white = o.optInt("white", d.white), kelvin = o.optDouble("kelvin", 5500.0).toFloat(),
                tint = o.optDouble("tint", 0.0).toFloat(), balance = o.optInt("balance", d.balance),
                grain = o.optBoolean("grain", d.grain), grainSize = o.optDouble("grainSize", 1.0).toFloat(),
                halation = o.optBoolean("halation", d.halation),
                halationAmount = o.optDouble("halationAmount", 1.0).toFloat(),
                couplers = o.optBoolean("couplers", d.couplers),
                rawDenoise = o.optDouble("rawDenoise", d.rawDenoise.toDouble()).toFloat(),
                chromaDenoise = o.optDouble("chromaDenoise", d.chromaDenoise.toDouble()).toFloat(),
                extra = o.optJSONObject("extra")?.let { e ->
                    e.keys().asSequence().associateWith { e.getDouble(it).toFloat() }
                } ?: emptyMap(),
            )
        }

        private fun prefs(context: Context) = context.getSharedPreferences("recipe", Context.MODE_PRIVATE)

        fun load(context: Context): Recipe = runCatching {
            prefs(context).getString("current", null)?.let { fromJson(JSONObject(it)) }
        }.getOrNull() ?: Recipe()

        fun save(context: Context, recipe: Recipe) {
            prefs(context).edit().putString("current", recipe.toJson().toString()).apply()
        }
    }
}

/** Display names for the engine's stocks and papers. */
object Films {
    private fun title(s: String) = s.split(' ').joinToString(" ") { w ->
        when {
            w.isEmpty() -> w
            w.any { it.isDigit() } -> w.uppercase()
            else -> w.replaceFirstChar { it.uppercase() }
        }
    }

    fun name(index: Int): String {
        val raw = FilmEngine.filmStocks.getOrNull(index) ?: return "胶片 $index"
        val base = raw.substringBefore(" (").trim()
        val kind = when {
            "b/w" in raw -> "黑白"
            "positive" in raw -> "反转片"
            else -> "彩色负片"
        }
        return "${title(base)} · $kind"
    }

    /** Without the maker: "Portra 400". */
    fun short(index: Int): String {
        val raw = FilmEngine.filmStocks.getOrNull(index) ?: return "胶片 $index"
        return title(raw.substringBefore(" (").removePrefix("kodak ").removePrefix("fujifilm ").trim())
    }

    fun maker(index: Int): String {
        val raw = FilmEngine.filmStocks.getOrNull(index) ?: return ""
        return if (raw.startsWith("fujifilm")) "Fujifilm" else "Kodak"
    }

    fun kind(index: Int): String {
        val raw = FilmEngine.filmStocks.getOrNull(index) ?: return ""
        return when {
            "b/w" in raw -> "黑白"
            "positive" in raw -> "反转片"
            "vision3" in raw || "verita" in raw -> "电影负片"
            else -> "彩色负片"
        }
    }

    fun isTungsten(index: Int): Boolean =
        Regex("\\d+t\\b").containsMatchIn(FilmEngine.filmStocks.getOrNull(index) ?: "")

    /** Box speed from the name ("portra 800 push 1" -> 1600). */
    fun iso(index: Int): Int {
        val film = FilmEngine.filmStocks.getOrNull(index) ?: return 400
        val speed = Regex("(\\d{2,4})(?:[dt]\\b)?").findAll(film).map { it.groupValues[1].toInt() }
            .firstOrNull { it in 25..3200 } ?: 400
        val push = Regex("push (\\d)").find(film)?.groupValues?.get(1)?.toInt() ?: 0
        return speed shl push
    }

    fun paper(index: Int): String = title(FilmEngine.papers.getOrNull(index) ?: "相纸 $index")
        .replace("(b/w)", "黑白").replace("(B/w)", "黑白")

    fun paperShort(index: Int): String = paper(index).removePrefix("Kodak ").removePrefix("Fujifilm ")
}
