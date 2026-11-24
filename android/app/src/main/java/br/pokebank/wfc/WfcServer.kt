package br.pokebank.wfc

import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Servidor WFC proprio, rodando no celular. Fase A: DNS + conntest + NAS + GPCM + GPSP.
 * Fase B (proxima): QR2 (27900), Server Browser (28910) e NATNEG (27901), que o Wi-Fi Club usa
 * para os dois jogos se acharem e se conectarem direto.
 *
 * As portas 53 e 80 sao fixas no DS. Como app comum nao pode abrir portas < 1024, o servidor usa
 * portas altas e o [RootRedirect] (com root) desvia 53 -> dnsPort e 80 -> httpPort.
 */
class WfcServer(
    dataDir: File?,
    private val log: (String) -> Unit,
    val dnsPort: Int = 5353,
    val httpPort: Int = 8080,
    val gpcmPort: Int = 29900,
    val gpspPort: Int = 29901,
) {
    val db = WfcDb(dataDir?.let { File(it, "wfc.db") })
    private val dns = DnsServer(dnsPort, log)
    private val http = NasHttpServer(httpPort, db, log)
    val gpcm = GpcmServer(gpcmPort, db, log, publicIpFor = ::publicIp)
    private val gpsp = GpspServer(gpspPort, db, log)
    @Volatile var running = false; private set

    fun start() {
        if (running) return
        val started = mutableListOf<() -> Unit>()
        try {
            dns.start(); started += dns::stop
            http.start(); started += http::stop
            gpcm.start(); started += gpcm::stop
            gpsp.start(); started += gpsp::stop
            running = true
        } catch (e: Exception) {
            started.forEach { runCatching { it() } }
            throw e
        }
    }

    fun stop() {
        runCatching { dns.stop() }; runCatching { http.stop() }; runCatching { gpcm.stop() }; runCatching { gpsp.stop() }
        running = false
    }

    data class OnlinePlayer(val name: String, val profileId: Long, val game: String, val friendCode: String, val ip: String, val status: String)

    fun online(): List<OnlinePlayer> = gpcm.sessions.values.map {
        OnlinePlayer(it.name, it.profileId, it.gameId, WfcCrypto.formatFriendCode(WfcCrypto.friendCode(it.profileId, it.gameId)), it.remoteIp, it.statstring)
    }

    companion object {
        fun localIps(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())

        /** Cliente no proprio celular (melonDS) aparece como 127.0.0.1: anuncia o IP do celular na rede. */
        fun publicIp(seen: String): String =
            if (seen.startsWith("127.")) localIps().firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") } ?: seen
            else seen
    }
}

/**
 * Regras de iptables (precisa de root) que levam as portas fixas do DS para o servidor.
 * Usa uma cadeia propria (POKEBANK_WFC) para poder remover tudo de uma vez.
 */
object RootRedirect {
    const val CHAIN = "POKEBANK_WFC"

    fun enableCommands(dnsPort: Int, httpPort: Int): List<String> = listOf(
        "iptables -t nat -N $CHAIN 2>/dev/null || iptables -t nat -F $CHAIN",
        // so trafego destinado a este aparelho (nao mexe no resto da rede do roteador)
        "iptables -t nat -A $CHAIN -p udp --dport 53 -m addrtype --dst-type LOCAL -j REDIRECT --to-ports $dnsPort",
        "iptables -t nat -A $CHAIN -p tcp --dport 80 -m addrtype --dst-type LOCAL -j REDIRECT --to-ports $httpPort",
        // PREROUTING: o DS vindo pelo roteador do celular / Wi-Fi; OUTPUT: o melonDS no proprio celular
        "iptables -t nat -C PREROUTING -j $CHAIN 2>/dev/null || iptables -t nat -I PREROUTING -j $CHAIN",
        "iptables -t nat -C OUTPUT -j $CHAIN 2>/dev/null || iptables -t nat -I OUTPUT -j $CHAIN",
    )

    fun disableCommands(): List<String> = listOf(
        "iptables -t nat -D PREROUTING -j $CHAIN 2>/dev/null",
        "iptables -t nat -D OUTPUT -j $CHAIN 2>/dev/null",
        "iptables -t nat -F $CHAIN 2>/dev/null",
        "iptables -t nat -X $CHAIN 2>/dev/null",
    )

    /** Executa como root. Retorna (sucesso, saida). */
    fun runAsRoot(commands: List<String>, listAfter: Boolean = true): Pair<Boolean, String> = try {
        val script = commands.joinToString(" ; ") + if (listAfter) " ; iptables -t nat -L $CHAIN -n 2>&1" else " ; true"
        val p = ProcessBuilder("su", "-c", script)
            .redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        (code == 0) to text
    } catch (e: Exception) {
        false to (e.message ?: e.toString())
    }

    fun hasRoot(): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val t = p.inputStream.bufferedReader().readText()
        p.waitFor() == 0 && t.contains("uid=0")
    }.getOrDefault(false)
}
