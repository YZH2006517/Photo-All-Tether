package org.photoall.tether.engine

import android.content.Context
import org.json.JSONObject

/**
 * How a photo is developed: the film, the paper and the creative LUT, and the few controls a
 * camera user reaches for (exposure, white balance, grain, halation, diffusion, noise).
 * Parameters the engine has beyond these keep the engine's defaults unless set in [extra].
 *
 * Schema 2 is the recalibrated engine of Photo All 0.3.0 (grain, couplers at the reference
 * spektrafilm's level); [fromJson] brings schema 1 recipes (App 0.1.x) over, see [migrate].
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
    val meter: Int = METER_CAMERA,   // where the develop's brightness starts from
    val white: Int = WHITE_CAMERA,
    val kelvin: Float = 5500f,
    val tint: Float = 0f,
    val balance: Int = BALANCE_NEUTRAL,
    val grain: Boolean = true,
    // 1 / 1 = the reference spektrafilm's grain; the phone app's defaults are half of each
    val grainSize: Float = 0.5f,
    val grainAmount: Float = 0.5f,
    val halation: Boolean = true,
    val halationAmount: Float = 1f,
    val couplers: Boolean = true,
    val diffusion: Int = 0,          // a diffusion filter on the lens, see diffusionNames
    val diffusionAmount: Float = 0.5f,
    // exposure fusion before the film, 0..1: shadows lifted, highlights held, local contrast kept
    // (single-frame HDR tone; the engine's local_tone)
    val localTone: Float = 0f,
    val rawDenoise: Float = 0.3f,
    val chromaDenoise: Float = 0.5f,
    /** The advanced filmsim parameters set away from their defaults (FilmParamsCatalog keys). */
    val extra: Map<String, Float> = emptyMap(),
) {
    /**
     * filmsim parameters (vkdt names) and the engine's stage switches. [jpegSource]: the picture
     * is a camera JPEG (with the film off it then stays as the camera rendered it).
     */
    fun params(jpegSource: Boolean = false): Map<String, Float> {
        val p = linkedMapOf(
            "film" to film.toFloat(),
            "paper" to paper.toFloat(),
            "grain" to if (grain) 1f else 0f,
            "size" to grainSize,
            "grain amt" to grainAmount,
            "halation" to if (halation) 1f else 0f,
            "hal amt" to halationAmount,
            "couplers" to if (couplers) 1f else 0f,
            "stage diffusion" to diffusion.toFloat(),
            "stage diff amt" to diffusionAmount,
            "stage film" to if (filmOn) 1f else 0f,
            "stage print" to if (printOn) 1f else 0f,
        )
        // every catalogued parameter whose app default is not the engine's own goes in always
        for (prm in FilmParamsCatalog.byKey.values) if (prm.engineDefault != null) p[prm.key] = prm.default
        for ((k, v) in extra) if (FilmParamsCatalog.byKey.containsKey(k)) p[k] = v
        if (jpegSource && !filmOn) p["stage display"] = 1f
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

    /**
     * The engine's match key for a raw: the camera JPEG's [cameraKey] (0 when the raw had none:
     * the reference meter then), [REFERENCE_EXPOSURE], or 0 (the raw's own exposure).
     */
    fun matchKey(cameraKey: Float): Float = when (meter) {
        METER_CAMERA -> if (cameraKey > 0f) cameraKey else REFERENCE_EXPOSURE
        METER_FILM -> REFERENCE_EXPOSURE
        else -> 0f
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
        if (filmOn && diffusion > 0) append(" · ").append(diffusionNames.getOrElse(diffusion) { "" })
        if (lut != null && lutOn) append(" · ").append(LutCatalog.label(lut))
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("film", film); put("paper", paper); put("filmOn", filmOn); put("printOn", printOn)
        put("lut", lut ?: ""); put("lutOn", lutOn); put("lutStrength", lutStrength.toDouble())
        put("ev", ev.toDouble()); put("meter", meter)
        put("matchCamera", meter == METER_CAMERA) // what App 0.1.x reads
        put("white", white); put("kelvin", kelvin.toDouble()); put("tint", tint.toDouble())
        put("balance", balance); put("grain", grain); put("grainSize", grainSize.toDouble())
        put("grainAmount", grainAmount.toDouble())
        put("diffusion", diffusion); put("diffusionAmount", diffusionAmount.toDouble())
        put("localTone", localTone.toDouble())
        put("halation", halation); put("halationAmount", halationAmount.toDouble()); put("couplers", couplers)
        put("rawDenoise", rawDenoise.toDouble()); put("chromaDenoise", chromaDenoise.toDouble())
        put("extra", JSONObject().apply { extra.forEach { (k, v) -> put(k, v.toDouble()) } })
    }

    companion object {
        /** 1: App 0.1.x (the engine of Photo All 0.2.2). 2: the recalibrated engine (0.3.0). */
        const val SCHEMA = 2

        /** The engine's "match_key < 0": the reference spektrafilm's own centre-weighted meter. */
        const val REFERENCE_EXPOSURE = -1f

        const val METER_CAMERA = 0 // the camera JPEG's brightness (the reference meter without one)
        const val METER_FILM = 1   // the reference spektrafilm's meter
        const val METER_NONE = 2   // the raw as exposed
        val meterNames = listOf("相机 JPEG", "胶片测光", "按原曝光")

        val diffusionNames = listOf("无", "柔焦", "黑柔", "微光", "泛光")

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
            val schema = o.optInt("schema", 1)
            val recipe = Recipe(
                film = o.optInt("film", d.film), paper = o.optInt("paper", d.paper),
                filmOn = o.optBoolean("filmOn", d.filmOn), printOn = o.optBoolean("printOn", d.printOn),
                lut = o.optString("lut", "").ifEmpty { null }, lutOn = o.optBoolean("lutOn", d.lutOn),
                lutStrength = o.optDouble("lutStrength", d.lutStrength.toDouble()).toFloat(),
                ev = o.optDouble("ev", 0.0).toFloat(),
                meter = if (o.has("meter")) o.optInt("meter", d.meter).coerceIn(0, meterNames.lastIndex)
                    else if (o.optBoolean("matchCamera", true)) METER_CAMERA else METER_NONE,
                white = o.optInt("white", d.white), kelvin = o.optDouble("kelvin", 5500.0).toFloat(),
                tint = o.optDouble("tint", 0.0).toFloat(), balance = o.optInt("balance", d.balance),
                grain = o.optBoolean("grain", d.grain),
                grainSize = o.optDouble("grainSize", if (schema < 2) 1.0 else d.grainSize.toDouble()).toFloat(),
                grainAmount = o.optDouble("grainAmount", d.grainAmount.toDouble()).toFloat(),
                halation = o.optBoolean("halation", d.halation),
                halationAmount = o.optDouble("halationAmount", 1.0).toFloat(),
                couplers = o.optBoolean("couplers", d.couplers),
                diffusion = o.optInt("diffusion", d.diffusion).coerceIn(0, diffusionNames.lastIndex),
                diffusionAmount = o.optDouble("diffusionAmount", d.diffusionAmount.toDouble()).toFloat(),
                localTone = o.optDouble("localTone", d.localTone.toDouble()).toFloat().coerceIn(0f, 1f),
                rawDenoise = o.optDouble("rawDenoise", d.rawDenoise.toDouble()).toFloat(),
                chromaDenoise = o.optDouble("chromaDenoise", d.chromaDenoise.toDouble()).toFloat(),
                extra = o.optJSONObject("extra")?.let { e ->
                    e.keys().asSequence().associateWith { e.getDouble(it).toFloat() }
                } ?: emptyMap(),
            )
            return if (schema < 2) migrate(recipe) else recipe
        }

        /**
         * A schema 1 recipe on the recalibrated engine. Its grain size was filmsim's own (1 the
         * default, coarser and a third as strong as the reference): kept relative to the default
         * (1 -> the new 0.5), at the new default strength. The couplers' strength and
         * inhibitors had defaults 0.5 and 1, now 1 and 1.5 (fitted to the reference's
         * saturation): unset ones take the new defaults, set ones keep their ratio to the
         * default. Everything else means the same as before.
         */
        private fun migrate(r: Recipe): Recipe {
            val scaled = mapOf("cp amt" to 1f / 0.5f, "lang r" to 1.5f, "lang g" to 1.5f, "lang b" to 1.5f)
            val extra = r.extra.mapValues { (k, v) ->
                val f = scaled[k] ?: return@mapValues v
                val p = FilmParamsCatalog.byKey.getValue(k)
                (v * f).coerceIn(p.min, p.max)
            }.filter { (k, v) ->
                val p = FilmParamsCatalog.byKey[k]
                p == null || kotlin.math.abs(v - p.default) > 1e-4f
            }
            return r.copy(grainSize = (r.grainSize * 0.5f).coerceIn(0.1f, 2.5f), extra = extra)
        }

        private fun prefs(context: Context) = context.getSharedPreferences("recipe", Context.MODE_PRIVATE)

        fun load(context: Context): Recipe = runCatching {
            prefs(context).getString("current", null)?.let { json ->
                val o = JSONObject(json)
                fromJson(o).also { if (o.optInt("schema", 1) < SCHEMA) save(context, it) } // migrated once
            }
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
