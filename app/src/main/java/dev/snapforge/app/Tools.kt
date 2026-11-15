package dev.snapforge.app

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import kotlin.math.roundToInt

/** Taxa de atualização da tela via `settings` (chaves AOSP). */
object DisplayCtl {
    fun rates(ctx: Context): List<Int> {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptyList()
        return d.supportedModes.map { it.refreshRate.roundToInt() }.distinct().sorted()
    }

    suspend fun current(): Int? =
        Root.run("settings get system peak_refresh_rate").firstOrNull()
            ?.trim()?.toFloatOrNull()?.roundToInt()

    suspend fun set(hz: Int) {
        Root.run(
            "settings put system peak_refresh_rate $hz.0\n" +
                "settings put system min_refresh_rate $hz.0"
        )
    }
}

/** Pausa do carregamento. O nó varia por fabricante; usamos o primeiro que existir. */
object Charging {
    data class Node(val path: String, val pauseValue: String, val resumeValue: String)

    private val candidates = listOf(
        Node("/sys/class/power_supply/battery/input_suspend", "1", "0"),
        Node("/sys/class/qcom-battery/input_suspend", "1", "0"),
        Node("/sys/class/power_supply/battery/charging_enabled", "0", "1"),
        Node("/sys/class/power_supply/battery/battery_charging_enabled", "0", "1")
    )

    suspend fun detect(): Node? {
        val cmd = candidates.joinToString("\n") { "[ -e ${it.path} ] && echo ${it.path}" }
        val found = Root.run(cmd).map { it.trim() }
        return candidates.firstOrNull { it.path in found }
    }

    suspend fun isPaused(n: Node): Boolean =
        Root.run("cat ${n.path}").firstOrNull()?.trim() == n.pauseValue

    suspend fun set(n: Node, paused: Boolean) {
        val v = if (paused) n.pauseValue else n.resumeValue
        Root.run("chmod u+w ${n.path} 2>/dev/null; echo $v > ${n.path}")
    }
}

object Memory {
    /** Encerra apps em segundo plano e limpa caches. Devolve MB liberados (aprox.). */
    suspend fun clean(): Long {
        val before = Monitor.read()?.memAvailKb ?: 0L
        Root.run("am kill-all\nsync\necho 3 > /proc/sys/vm/drop_caches")
        val after = Monitor.read()?.memAvailKb ?: 0L
        return ((after - before) / 1024).coerceAtLeast(0)
    }
}
