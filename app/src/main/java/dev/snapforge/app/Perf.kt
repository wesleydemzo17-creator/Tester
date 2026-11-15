package dev.snapforge.app

import android.content.Context

enum class PerfMode(val label: String, val desc: String) {
    ECO("Economia", "Limita a CPU a ~60%. Menos calor e mais bateria."),
    BALANCED("Equilibrado", "Padrão do sistema."),
    PERFORMANCE("Desempenho", "Frequência mínima elevada, resposta mais rápida."),
    EXTREME("Extremo", "CPU e GPU no máximo o tempo todo. Esquenta bastante.")
}

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("snapforge", Context.MODE_PRIVATE)

    fun mode(ctx: Context): PerfMode {
        val name = sp(ctx).getString("mode", null)
        return PerfMode.values().firstOrNull { it.name == name } ?: PerfMode.BALANCED
    }

    fun setMode(ctx: Context, m: PerfMode) {
        sp(ctx).edit().putString("mode", m.name).apply()
    }
}

/**
 * Perfis de desempenho para Snapdragon: cpufreq (por cluster/policy) e GPU Adreno (kgsl).
 * Nada é reaplicado no boot de propósito: se algo der errado, reiniciar desfaz tudo.
 */
object Perf {
    private val INFO = """
for p in /sys/devices/system/cpu/cpufreq/policy*; do
a=; b=; g=
read a < @p/cpuinfo_min_freq
read b < @p/cpuinfo_max_freq
read g < @p/scaling_available_governors
echo "I @p|@a|@b|@g"
done 2>/dev/null
""".trimIndent().replace('@', '$')

    private val WFUNC = """
w() { [ -e "@1" ] && { chmod u+w "@1" 2>/dev/null; echo "@2" > "@1" 2>/dev/null; }; }
""".trimIndent().replace('@', '$')

    suspend fun apply(mode: PerfMode): Boolean {
        val info = Root.run(INFO)
        val sb = StringBuilder()
        sb.append(WFUNC).append('\n')

        for (line in info) {
            if (!line.startsWith("I ")) continue
            val parts = line.removePrefix("I ").split("|")
            if (parts.size < 4) continue
            val path = parts[0].trim()
            val hwMin = parts[1].trim().toLongOrNull() ?: continue
            val hwMax = parts[2].trim().toLongOrNull() ?: continue
            val govs = parts[3].trim().split(Regex("\\s+"))

            val defGov = listOf("walt", "schedutil", "interactive").firstOrNull { it in govs }
            val gov: String?
            val min: Long
            val max: Long
            when (mode) {
                PerfMode.ECO -> {
                    gov = defGov; min = hwMin; max = hwMax * 60 / 100
                }
                PerfMode.BALANCED -> {
                    gov = defGov; min = hwMin; max = hwMax
                }
                PerfMode.PERFORMANCE -> {
                    gov = defGov; min = hwMax / 2; max = hwMax
                }
                PerfMode.EXTREME -> {
                    gov = if ("performance" in govs) "performance" else defGov
                    min = hwMax; max = hwMax
                }
            }
            if (gov != null) sb.append("w ").append(path).append("/scaling_governor ").append(gov).append('\n')
            // ordem: baixa o mínimo, ajusta o máximo, depois sobe o mínimo
            sb.append("w ").append(path).append("/scaling_min_freq ").append(hwMin).append('\n')
            sb.append("w ").append(path).append("/scaling_max_freq ").append(max).append('\n')
            sb.append("w ").append(path).append("/scaling_min_freq ").append(min).append('\n')
        }

        val k = "/sys/class/kgsl/kgsl-3d0"
        if (mode == PerfMode.EXTREME) {
            sb.append("w $k/devfreq/governor performance\n")
            sb.append("w $k/force_clk_on 1\n")
            sb.append("w $k/force_bus_on 1\n")
            sb.append("w $k/force_rail_on 1\n")
        } else {
            sb.append("w $k/force_clk_on 0\n")
            sb.append("w $k/force_bus_on 0\n")
            sb.append("w $k/force_rail_on 0\n")
            sb.append("w $k/devfreq/governor msm-adreno-tz\n")
        }
        sb.append("echo SF_OK\n")

        val out = Root.run(sb.toString())
        return out.any { it.trim() == "SF_OK" }
    }
}
