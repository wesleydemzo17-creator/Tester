package dev.snapforge.app

import kotlin.math.abs

data class Policy(
    val id: Int,
    val curKhz: Long,
    val maxKhz: Long,
    val hwMaxKhz: Long,
    val governor: String
)

data class Snapshot(
    val policies: List<Policy>,
    val cpuUsage: Int?,
    val gpuBusy: Int?,
    val gpuClkHz: Long?,
    val cpuTemp: Float?,
    val gpuTemp: Float?,
    val batTemp: Float?,
    val batCurrentMa: Int?,
    val batPct: Int?,
    val batStatus: String?,
    val memTotalKb: Long,
    val memAvailKb: Long,
    val zones: List<Pair<String, Float>>
)

/** Lê CPU, GPU Adreno (kgsl), temperaturas, bateria e RAM em uma única chamada root. */
object Monitor {
    // '@' vira '$' — evita conflito com templates de string do Kotlin.
    private val SCRIPT = """
l=; read l < /proc/stat; echo "S @l"
for p in /sys/devices/system/cpu/cpufreq/policy*; do
c=; m=; h=; g=
read c < @p/scaling_cur_freq
read m < @p/scaling_max_freq
read h < @p/cpuinfo_max_freq
read g < @p/scaling_governor
echo "P @{p##*policy} @c @m @h @g"
done 2>/dev/null
k=/sys/class/kgsl/kgsl-3d0
a=; read a x 2>/dev/null < @k/gpu_busy_percentage; echo "GB @a"
a=; read a 2>/dev/null < @k/gpuclk; echo "GC @a"
for z in /sys/class/thermal/thermal_zone*; do
t=; v=
read t < @z/type
read v < @z/temp
echo "T @t @v"
done 2>/dev/null
b=/sys/class/power_supply/battery
a=; read a 2>/dev/null < @b/temp; echo "BT @a"
a=; read a 2>/dev/null < @b/current_now; echo "BC @a"
a=; read a 2>/dev/null < @b/capacity; echo "BP @a"
a=; read a 2>/dev/null < @b/status; echo "BS @a"
while read n v u; do
case "@n" in MemTotal:|MemAvailable:) echo "M @n @v";; esac
done < /proc/meminfo
""".trimIndent().replace('@', '$')

    private val statLock = Any()
    private var prevTotal = 0L
    private var prevIdle = 0L

    private fun normTemp(raw: Long): Float? {
        var v = raw.toFloat()
        if (abs(v) > 1000f) v /= 1000f else if (abs(v) > 200f) v /= 10f
        return if (v > 0f && v < 150f) v else null
    }

    /** Uso total da CPU a partir da linha "cpu" de /proc/stat (diferença desde a última leitura). */
    private fun usage(t: List<String>): Int? {
        // t = [S, cpu, user, nice, system, idle, iowait, irq, softirq, steal...]
        if (t.size < 7) return null
        val nums = t.drop(2).mapNotNull { it.toLongOrNull() }
        if (nums.size < 5) return null
        val total = nums.take(8).sum()
        val idle = nums[3] + nums[4]
        synchronized(statLock) {
            val dt = total - prevTotal
            val di = idle - prevIdle
            val first = prevTotal == 0L
            prevTotal = total
            prevIdle = idle
            if (first || dt <= 0) return null
            return (100 - (di * 100 / dt)).toInt().coerceIn(0, 100)
        }
    }

    suspend fun read(): Snapshot? {
        val lines = Root.run(SCRIPT)
        if (lines.isEmpty()) return null

        val policies = ArrayList<Policy>()
        val zones = ArrayList<Pair<String, Float>>()
        var cpuUsage: Int? = null
        var gpuBusy: Int? = null
        var gpuClk: Long? = null
        var cpuTemp: Float? = null
        var gpuTemp: Float? = null
        var batTemp: Float? = null
        var batCur: Int? = null
        var batPct: Int? = null
        var batStatus: String? = null
        var memTotal = 0L
        var memAvail = 0L

        for (line in lines) {
            val t = line.trim().split(Regex("\\s+"))
            if (t.isEmpty()) continue
            when (t[0]) {
                "S" -> cpuUsage = usage(t)
                "P" -> if (t.size == 6) {
                    val id = t[1].toIntOrNull()
                    val c = t[2].toLongOrNull()
                    val m = t[3].toLongOrNull()
                    val h = t[4].toLongOrNull()
                    if (id != null && c != null && m != null && h != null) {
                        policies.add(Policy(id, c, m, h, t[5]))
                    }
                }
                "GB" -> gpuBusy = t.getOrNull(1)?.toIntOrNull()
                "GC" -> gpuClk = t.getOrNull(1)?.toLongOrNull()
                "T" -> if (t.size == 3) {
                    val type = t[1].lowercase()
                    val raw = t[2].toLongOrNull()
                    val v = if (raw != null) normTemp(raw) else null
                    if (v != null) {
                        zones.add(Pair(t[1], v))
                        if (type.contains("gpu")) {
                            val cur = gpuTemp
                            if (cur == null || v > cur) gpuTemp = v
                        } else if (type.contains("cpu")) {
                            val cur = cpuTemp
                            if (cur == null || v > cur) cpuTemp = v
                        }
                    }
                }
                "BT" -> {
                    val raw = t.getOrNull(1)?.toLongOrNull()
                    batTemp = if (raw != null) normTemp(raw) else null
                }
                "BC" -> {
                    val raw = t.getOrNull(1)?.toLongOrNull()
                    if (raw != null) {
                        val a = abs(raw)
                        batCur = (if (a > 10000) a / 1000 else a).toInt()
                    }
                }
                "BP" -> batPct = t.getOrNull(1)?.toIntOrNull()
                "BS" -> batStatus = t.getOrNull(1)
                "M" -> if (t.size == 3) {
                    val v = t[2].toLongOrNull() ?: 0L
                    if (t[1].startsWith("MemTotal")) memTotal = v else memAvail = v
                }
            }
        }

        return Snapshot(
            policies.sortedBy { it.id }, cpuUsage, gpuBusy, gpuClk, cpuTemp, gpuTemp,
            batTemp, batCur, batPct, batStatus, memTotal, memAvail, zones
        )
    }
}
