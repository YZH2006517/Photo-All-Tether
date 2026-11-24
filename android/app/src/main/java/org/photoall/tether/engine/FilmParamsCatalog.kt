package org.photoall.tether.engine

/**
 * Every engine parameter the recipe page offers beyond its main controls, by group, with the
 * ranges and defaults of the film engine (engine/, Photo All 0.3.0's, as its phone app lists
 * them). Keys are the engine's names: filmsim parameters (film_params.cpp set_param) and
 * stages ("stage …", set_stage).
 */
object FilmParamsCatalog {
    class Param(
        val key: String,
        val label: String,
        val min: Float,
        val max: Float,
        val default: Float,
        val step: Float = 0.01f,
        val toggle: Boolean = false,
        /** A choice of fixed values (shown as segments) instead of a slider: label to value. */
        val choices: List<Pair<String, Float>>? = null,
        val format: (Float) -> String = { "%.2f".format(it) },
        /**
         * Set when the app's [default] is not the engine's own (this value): the parameter is then
         * always sent. Null: [default] is the engine's, and an unset parameter is left to it.
         */
        val engineDefault: Float? = null,
    )

    class Group(val id: String, val title: String, val note: String?, val params: List<Param>)

    private val ev: (Float) -> String = { "%+.2f EV".format(it) }
    private val um: (Float) -> String = { "%.0f µm".format(it) }
    private val px: (Float) -> String = { "%.1f px".format(it) }
    private val kelvin: (Float) -> String = { if (it < 2000f) "关" else "%.0f K".format(it) }
    private val off: (Float) -> String = { if (it == 0f) "关" else "%.2f".format(it) }

    /** The negative's long edge in mm, as the engine scales it ("frame mm", 35 = a 135 frame; 0 = its default, 135). */
    private fun frame(longEdgeMm: Float) = 35f * longEdgeMm / 36f

    val groups: List<Group> = listOf(
        Group("meter", "测光", "起始亮度和局部影调在「曝光与白平衡」里", listOf(
            Param("stage match lift", "对齐相机 JPEG 时只提亮不压暗（闪光照片）", 0f, 1f, 0f, toggle = true),
        )),
        Group("film", "胶片", "曝光、反差与显影", listOf(
            Param("ev film", "胶片曝光", -5f, 5f, 0f, 0.05f, format = ev),
            Param("g film", "胶片反差", 0.5f, 1.5f, 1f),
            Param("f base", "片基密度", 0f, 1.5f, 1f),
            Param("g fast", "快感光层反差", 0.5f, 1.5f, 1f),
            Param("g slow", "慢感光层反差", 0.5f, 1.5f, 1f),
            Param("exhaust", "显影液衰竭", 0f, 1f, 0f),
            Param("hl boost", "高光增强", 0f, 10f, 0f, 0.1f, format = ev),
        )),
        Group("print", "冲印", "印放到相纸（关闭「印放到相纸」时不起作用）", listOf(
            Param("process", "印放方式", 0f, 2f, 0f, choices = listOf("光学印放" to 0f, "扫描底片后数码印放" to 2f)),
            Param("ev paper", "印放曝光", -5f, 5f, 0f, 0.05f, format = ev),
            Param("g paper", "相纸反差", 0.5f, 1.5f, 1f),
            Param("p base", "相纸片基", 0f, 1.5f, 1f),
            Param("g fast p", "相纸快层反差", 0.5f, 1.5f, 1f),
            Param("g slow p", "相纸慢层反差", 0.5f, 1.5f, 1f),
            Param("p gamma r", "相纸红层反差", 0.5f, 1.5f, 1f),
            Param("p gamma g", "相纸绿层反差", 0.5f, 1.5f, 1f),
            Param("p gamma b", "相纸蓝层反差", 0.5f, 1.5f, 1f),
            Param("p exh", "相纸药液衰竭", 0f, 1f, 0f),
            Param("glare", "眩光", 0f, 0.5f, 0f),
        )),
        Group("filter", "放大机滤色", "青、品、黄滤色只在「手动」滤色方式下起作用；微调在任何方式下都起作用。反转片直接扫描，不加滤色", listOf(
            Param("filter c", "青滤色", 0f, 1f, 0f),
            Param("filter m", "品滤色", 0f, 1f, 0f),
            Param("filter y", "黄滤色", 0f, 1f, 0f),
            Param("tune m", "品红微调", -1f, 1f, 0f),
            Param("tune y", "黄微调", -1f, 1f, 0f),
        )),
        Group("preflash", "预闪", "印放前给相纸一次均匀的弱曝光，压低反差、托起暗部", listOf(
            Param("preflash", "预闪光", 0f, 1f, 0f, toggle = true),
            Param("pf ev", "预闪曝光", -10f, -3f, -10f, 0.1f, format = ev),
            Param("pf m", "预闪品红", -1f, 1f, 0f),
            Param("pf y", "预闪黄", -1f, 1f, 0f),
        )),
        Group("couplers", "DIR 耦合剂", "层间抑制：边缘更锐、色彩更分离；默认值按原版 spektrafilm 的饱和度标定（开关在「质感」里）", listOf(
            Param("cp amt", "耦合剂强度", 0f, 2f, 1f),
            Param("lang r", "红层抑制", 0.01f, 3f, 1.5f),
            Param("lang g", "绿层抑制", 0.01f, 3f, 1.5f),
            Param("lang b", "蓝层抑制", 0.01f, 3f, 1.5f),
            Param("cp rad", "扩散半径", 0f, 200f, 20f, 1f, format = um),
        )),
        Group("halation", "光晕与散射", "高光透过片基、在片背反射回来形成的红晕（开关和强度在「质感」里）", listOf(
            Param("radius", "光晕半径", 0f, 200f, 65f, 1f, format = um),
            Param("hal mids", "中间调保护", 0f, 1f, 0f),
            Param("hal bnc", "反射次数", 1f, 6f, 3f, 1f, format = { "%.0f".format(it) }),
            Param("hal dec", "反射衰减", 0f, 1f, 0.5f),
            Param("scat amt", "乳剂散射", 0f, 1f, 0.2f),
            Param("strength r", "红通道", 0f, 2f, 1f),
            Param("strength g", "绿通道", 0f, 2f, 1f),
            Param("strength b", "蓝通道", 0f, 2f, 1f),
        )),
        Group("grain", "颗粒与画幅", "开关、大小和强度在「质感」里；画幅越大，颗粒、光晕和耦合剂扩散在画面上越细", listOf(
            Param("uniform", "颗粒均匀度", 0f, 1f, 1f),
            Param("stage grain blur", "显影扩散", 0f, 3f, 0.65f, 0.05f, format = px),
            Param("frame mm", "画幅", 0f, 100f, 0f, choices = listOf(
                "135" to 0f, "半格" to frame(24f), "645" to frame(56f), "6×7" to frame(70f), "6×9" to frame(84f),
            )),
        )),
        Group("optics", "镜头滤镜", "扩散滤镜的种类和强度在「质感」里", listOf(
            Param("stage diff size", "扩散范围", 0.3f, 3f, 1f, 0.05f),
            Param("stage diff warm", "扩散冷暖（− 冷 / + 暖）", -1f, 1f, 0f),
            Param("uv cut", "UV 截止滤镜", 0f, 1f, 0f, format = off),
            Param("ir cut", "IR 截止滤镜", 0f, 1f, 0f, format = off),
        )),
        Group("scan", "扫描", "成片扫描仪：柔化、锐化、黑白点与色域", listOf(
            Param("stage scan blur", "扫描柔化", 0f, 3f, 0f, 0.1f, format = px),
            Param("stage scan sharp", "扫描锐化", 0f, 2f, 0.3f),
            Param("stage scan radius", "锐化半径", 0.5f, 5f, 0.7f, 0.1f, format = px),
            Param("stage black", "黑点", 0f, 0.1f, 0f, 0.002f, format = { "%.3f".format(it) }),
            Param("stage white", "白点", 0.7f, 1.3f, 1f),
            Param("stage gamut", "柔和压入色域（关 = 硬裁切）", 0f, 1f, 1f, toggle = true),
            Param("stage negative", "直接看负片（不反相，带橙色片基）", 0f, 1f, 0f, toggle = true),
        )),
        Group("jpeg", "相机 JPEG 来源", "只在没有 RAW、用相机 JPEG 冲洗时起作用", listOf(
            Param("stage restore", "还原场景（去掉相机自带的饱和、反差和肩部）", 0f, 1f, 1f, engineDefault = 0f),
            Param("stage meter", "自动测光", 0f, 1f, 1f, toggle = true),
            Param("stage temp", "色温（− 冷 / + 暖）", -1f, 1f, 0f),
            Param("stage tint", "色调（− 品 / + 绿）", -1f, 1f, 0f),
        )),
        Group("light", "光源", "扫描与印放用的光、场景光的校正", listOf(
            Param("scan ill", "扫描光源", 2000f, 10000f, 5000f, 50f, format = { "%.0f K".format(it) }),
            Param("scne ill", "场景光源（关 = 不校正）", 0f, 10000f, 0f, 50f, format = kelvin),
            Param("tint", "场景色调", -1f, 1f, 0f),
            Param("film ill", "胶片平衡光源（关 = 默认）", 0f, 10000f, 0f, 50f, format = kelvin),
        )),
    )

    val byKey: Map<String, Param> = groups.flatMap { it.params }.associateBy { it.key }
}
