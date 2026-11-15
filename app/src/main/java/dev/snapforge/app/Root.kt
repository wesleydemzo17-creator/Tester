package dev.snapforge.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter

/**
 * Shell root persistente: abre um único processo `su` e reutiliza,
 * assim o gerenciador de root (Magisk/KernelSU) só pergunta uma vez.
 */
object Root {
    private val lock = Any()
    private var proc: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    private fun ensure(): Boolean {
        val p = proc
        if (p != null && p.isAlive) return true
        return try {
            val np = ProcessBuilder("su").redirectErrorStream(true).start()
            proc = np
            writer = np.outputStream.bufferedWriter()
            reader = np.inputStream.bufferedReader()
            true
        } catch (e: Exception) {
            proc = null
            false
        }
    }

    /** Executa o comando e devolve as linhas de saída. Lista vazia em caso de falha. */
    fun exec(cmd: String): List<String> {
        synchronized(lock) {
            if (!ensure()) return emptyList()
            val marker = "__SF_END_" + System.nanoTime() + "__"
            val lines = ArrayList<String>()
            try {
                val w = writer ?: return emptyList()
                val r = reader ?: return emptyList()
                w.write(cmd)
                w.write("\necho " + marker + "\n")
                w.flush()
                while (true) {
                    val l = r.readLine() ?: break
                    if (l.endsWith(marker)) {
                        val rest = l.removeSuffix(marker)
                        if (rest.isNotEmpty()) lines.add(rest)
                        break
                    }
                    lines.add(l)
                }
            } catch (e: Exception) {
                try {
                    proc?.destroy()
                } catch (ignored: Exception) {
                }
                proc = null
                return emptyList()
            }
            return lines
        }
    }

    suspend fun run(cmd: String): List<String> = withContext(Dispatchers.IO) { exec(cmd) }

    suspend fun isRooted(): Boolean = run("id -u").firstOrNull()?.trim() == "0"
}
