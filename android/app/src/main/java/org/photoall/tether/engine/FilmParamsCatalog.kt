package org.photoall.tether.engine

/**
 * Every filmsim parameter the recipe page offers beyond its main controls, by group, with the
 * ranges and defaults of filmsim's params.ui (as the Photo All phone app lists them). Keys are
 * the engine's parameter names (film_params.cpp).
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
        val format: (Float) -> String = { "%.2f".format(it) },
    )

    class Group(val id: String, val title: String, val note: String?, val params: List<Param>)

    private val ev: (Float) -> String = { "%+.2f EV".format(it) }
    private val um: (Float) -> String = { "%.0f µm".format(it) }
    private val kelvin: (Float) -> String = { if (it < 2000f) "关" else "%.0f K".format(it) }

    val groups: List<Group> = listOf(
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
            Param("ev paper", "印放曝光", -5f, 5f, 0f, 0.05f, format = ev),
            Param("g paper", "相纸反差", 0.5f, 1.5f, 1f),
            Param("p base", "相纸片基", 0f, 1.5f, 1f),
            Param("g fast p", "相纸快层反差", 0.5f, 1.5f, 1f),
            Param("g slow p", "相纸慢层反差", 0.5f, 1.5f, 1f),
            Param("p exh", "相纸药液衰竭", 0f, 1f, 0f),
            Param("glare", "眩光", 0f, 0.5f, 0f),
        )),
        Group("filter", "放大机滤色", "青、品、黄滤色只在「手动」滤色方式下起作用；微调在任何方式下都起作用", listOf(
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
        Group("couplers", "DIR 耦合剂", "层间抑制：边缘更锐、色彩更分离（开关在「质感」里）", listOf(
            Param("cp amt", "耦合剂强度", 0f, 1f, 0.5f),
            Param("lang r", "红层抑制", 0.01f, 2f, 1f),
            Param("lang g", "绿层抑制", 0.01f, 2f, 1f),
            Param("lang b", "蓝层抑制", 0.01f, 2f, 1f),
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
        Group("grain", "颗粒", "开关和大小在「质感」里", listOf(
            Param("uniform", "颗粒均匀度", 0f, 1f, 1f),
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
