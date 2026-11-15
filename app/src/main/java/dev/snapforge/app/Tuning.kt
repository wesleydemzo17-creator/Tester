package dev.snapforge.app

data class ClusterInfo(
    val id: Int,
    val path: String,
    val cpus: String,
    val minKhz: Long,
    val maxKhz: Long,
    val governor: String,
    val freqs: List<Long>,
    val governors: List<String>
)

/** freqsHz em ordem decrescente: o índice é o "power level" do kgsl (0 = mais rápido). */
data class GpuInfo(
    val freqsHz: List<Long>,
    val minLevel: Int,
    val maxLevel: Int,
    val governor: String,
    val governors: List<String>
)

data class CoreInfo(val id: Int, val online: Boolean)

data class TuningState(
    val clusters: List<ClusterInfo>,
    val gpu: GpuInfo?,
    val cores: List<CoreInfo>
)

/** Controle manual: núcleos, frequências/governor por cluster e níveis da GPU Adreno. */
object Tuning {
    private const val KGSL = "/sys/class/kgsl/kgsl-3d0"

    private val READ = """
for p in /sys/devices/system/cpu/cpufreq/policy*; do
a=; b=; c=; d=; e=; f=; g=; h=
read a < @p/related_cpus
read b < @p/scaling_min_freq
read c < @p/scaling_max_freq
read d < @p/scaling_governor
read e < @p/scaling_available_frequencies
read f < @p/scaling_available_governors
read g < @p/cpuinfo_min_freq
read h < @p/cpuinfo_max_freq
echo "P @p|@a|@b|@c|@d|@e|@f|@g|@h"
done 2>/dev/null
k=/sys/class/kgsl/kgsl-3d0
if [ -d @k ]; then
a=; b=; c=; d=; e=
read a 2>/dev/null < @k/gpu_available_frequencies
read b 2>/dev/null < @k/min_pwrlevel
read c 2>/dev/null < @k/max_pwrlevel
read d 2>/dev/null < @k/devfreq/governor
read e 2>/dev/null < @k/devfreq/available_governors
echo "G @a|@b|@c|@d|@e"
fi
for c in /sys/devices/system/cpu/cpu[0-9]*; do
o=1
read o 2>/dev/null < @c/online
echo "C @{c##*cpu} @o"
done
""".trimIndent().replace('@', '$')

    private val WFUNC = """
w() { [ -e "@1" ] && { chmod u+w "@1" 2>/dev/null; echo "@2" > "@1" 2>/dev/null; }; }
""".trimIndent().replace('@', '$')

    private fun words(s: String): List<String> =
        s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    suspend fun read(): TuningState {
        val clusters = ArrayList<ClusterInfo>()
        val cores = ArrayList<CoreInfo>()
        var gpu: GpuInfo? = null

        for (line in Root.run(READ)) {
            if (line.startsWith("P ")) {
                val p = line.removePrefix("P ").split("|")
                if (p.size < 9) continue
                val path = p[0].trim()
                val id = path.substringAfterLast("policy").toIntOrNull() ?: continue
                val min = p[2].trim().toLongOrNull() ?: continue
                val max = p[3].trim().toLongOrNull() ?: continue
                var freqs = words(p[5]).mapNotNull { it.toLongOrNull() }.distinct().sorted()
                if (freqs.size < 2) {
                    // kernel sem tabela: gera 8 degraus entre o mínimo e o máximo do hardware
                    val lo = p[7].trim().toLongOrNull() ?: min
                    val hi = p[8].trim().toLongOrNull() ?: max
                    freqs = (0..7).map { lo + (hi - lo) * it / 7 }.distinct()
                }
                clusters.add(
                    ClusterInfo(id, path, p[1].trim(), min, max, p[4].trim(), freqs, words(p[6]))
                )
            } else if (line.startsWith("G ")) {
                val p = line.removePrefix("G ").split("|")
                if (p.size < 5) continue
                val freqs = words(p[0]).mapNotNull { it.toLongOrNull() }.sortedDescending()
                if (freqs.isEmpty()) continue
                gpu = GpuInfo(
                    freqs,
                    p[1].trim().toIntOrNull() ?: (freqs.size - 1),
                    p[2].trim().toIntOrNull() ?: 0,
                    p[3].trim(),
                    words(p[4])
                )
            } else if (line.startsWith("C ")) {
                val t = words(line)
                if (t.size < 3) continue
                val id = t[1].toIntOrNull() ?: continue
                cores.add(CoreInfo(id, t[2] == "1"))
            }
        }
        return TuningState(clusters.sortedBy { it.id }, gpu, cores.sortedBy { it.id })
    }

    suspend fun setCore(id: Int, online: Boolean) {
        val v = if (online) 1 else 0
        Root.run("$WFUNC\nw /sys/devices/system/cpu/cpu$id/online $v")
    }

    suspend fun setClusterMin(c: ClusterInfo, khz: Long) {
        Root.run("$WFUNC\nw ${c.path}/scaling_min_freq $khz")
    }

    suspend fun setClusterMax(c: ClusterInfo, khz: Long) {
        Root.run("$WFUNC\nw ${c.path}/scaling_max_freq $khz")
    }

    suspend fun setClusterGovernor(c: ClusterInfo, gov: String) {
        Root.run("$WFUNC\nw ${c.path}/scaling_governor $gov")
    }

    /** Teto da GPU: level menor = frequência maior. */
    suspend fun setGpuMax(g: GpuInfo, level: Int) {
        val hz = g.freqsHz.getOrNull(level) ?: return
        Root.run("$WFUNC\nw $KGSL/max_pwrlevel $level\nw $KGSL/devfreq/max_freq $hz\nw $KGSL/max_gpuclk $hz")
    }

    /** Piso da GPU. */
    suspend fun setGpuMin(g: GpuInfo, level: Int) {
        val hz = g.freqsHz.getOrNull(level) ?: return
        Root.run("$WFUNC\nw $KGSL/min_pwrlevel $level\nw $KGSL/devfreq/min_freq $hz")
    }

    suspend fun setGpuGovernor(gov: String) {
        Root.run("$WFUNC\nw $KGSL/devfreq/governor $gov")
    }
}
