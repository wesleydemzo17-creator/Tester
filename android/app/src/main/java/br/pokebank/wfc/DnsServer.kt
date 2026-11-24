package br.pokebank.wfc

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * DNS minimo: responde os dominios da Nintendo/GameSpy com o IP deste celular e repassa
 * todo o resto para um DNS publico. Sem root, roda numa porta alta (5353);
 * com root, o iptables desvia a porta 53 para ca (veja RootRedirect).
 */
class DnsServer(
    private val port: Int,
    private val log: (String) -> Unit,
    private val upstream: String = "8.8.8.8",
    /** IP a responder para um cliente; o padrao escolhe o IP local da mesma rede do cliente. */
    private val answerFor: (InetAddress) -> ByteArray? = ::localIpFor,
) {
    @Volatile private var running = false
    private var sock: DatagramSocket? = null

    fun start() {
        val s = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(this@DnsServer.port)) }
        sock = s
        running = true
        thread(name = "wfc-dns", isDaemon = true) {
            val buf = ByteArray(1500)
            while (running) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val q = p.data.copyOfRange(p.offset, p.offset + p.length)
                    val from = p.address
                    val fromPort = p.port
                    thread(isDaemon = true) { handle(q, from, fromPort) }
                } catch (e: SocketException) {
                    if (!running) return@thread
                } catch (e: Exception) {
                    log("DNS: ${e.message}")
                }
            }
        }
        log("DNS ouvindo na porta $port")
    }

    fun stop() { running = false; sock?.close() }

    private fun handle(q: ByteArray, from: InetAddress, fromPort: Int) {
        val parsed = parseQuestion(q) ?: return
        val (name, qtype) = parsed
        val reply = if (shouldRedirect(name)) {
            val ip = answerFor(from)
            log("DNS ${from.hostAddress}: $name -> ${ip?.let { InetAddress.getByAddress(it).hostAddress } ?: "?"}")
            buildAnswer(q, if (qtype == 1) ip else null)
        } else {
            forward(q)
        } ?: return
        try { sock?.send(DatagramPacket(reply, reply.size, from, fromPort)) } catch (_: Exception) {}
    }

    private fun forward(q: ByteArray): ByteArray? = try {
        DatagramSocket().use { u ->
            u.soTimeout = 3000
            u.send(DatagramPacket(q, q.size, InetAddress.getByName(upstream), 53))
            val b = ByteArray(1500)
            val p = DatagramPacket(b, b.size)
            u.receive(p)
            b.copyOf(p.length)
        }
    } catch (e: SocketTimeoutException) { null } catch (e: Exception) { null }

    companion object {
        val REDIRECT_SUFFIXES = listOf("nintendowifi.net", "nintendo.net", "gamespy.com", "gs.nintendowifi.net")

        fun shouldRedirect(name: String): Boolean {
            val n = name.lowercase().trimEnd('.')
            return REDIRECT_SUFFIXES.any { n == it || n.endsWith(".$it") }
        }

        /** (nome, tipo) da primeira pergunta, ou null se o pacote for invalido. */
        fun parseQuestion(q: ByteArray): Pair<String, Int>? {
            if (q.size < 17) return null
            if ((q[2].toInt() and 0x80) != 0) return null // e resposta, nao pergunta
            val qd = ((q[4].toInt() and 0xFF) shl 8) or (q[5].toInt() and 0xFF)
            if (qd < 1) return null
            var p = 12
            val labels = mutableListOf<String>()
            while (p < q.size) {
                val len = q[p].toInt() and 0xFF
                if (len == 0) { p++; break }
                if (len > 63 || p + 1 + len > q.size) return null
                labels.add(String(q, p + 1, len, Charsets.US_ASCII))
                p += 1 + len
            }
            if (p + 4 > q.size) return null
            val qtype = ((q[p].toInt() and 0xFF) shl 8) or (q[p + 1].toInt() and 0xFF)
            return labels.joinToString(".") to qtype
        }

        /** Resposta com 1 registro A (ou nenhum, se ip == null), copiando a pergunta. */
        fun buildAnswer(q: ByteArray, ip: ByteArray?): ByteArray {
            var p = 12
            while (q[p].toInt() != 0) p += 1 + (q[p].toInt() and 0xFF)
            val qEnd = p + 5
            val out = java.io.ByteArrayOutputStream()
            out.write(q, 0, 2)                         // id
            out.write(0x81); out.write(0x80)           // resposta, recursao disponivel, sem erro
            out.write(0); out.write(1)                 // 1 pergunta
            out.write(0); out.write(if (ip != null) 1 else 0)
            out.write(0); out.write(0); out.write(0); out.write(0)
            out.write(q, 12, qEnd - 12)                // pergunta original
            if (ip != null) {
                out.write(0xC0); out.write(0x0C)       // ponteiro para o nome
                out.write(0); out.write(1)             // tipo A
                out.write(0); out.write(1)             // classe IN
                out.write(0); out.write(0); out.write(0); out.write(60) // TTL 60 s
                out.write(0); out.write(4)
                out.write(ip)
            }
            return out.toByteArray()
        }

        /** IPv4 local na mesma rede /24 do cliente (o celular pode ter Wi-Fi e roteador ao mesmo tempo). */
        fun localIpFor(client: InetAddress): ByteArray? {
            val all = runCatching {
                NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            }.getOrDefault(emptyList())
            val c = client.address
            if (client.isLoopbackAddress) return all.firstOrNull()?.address ?: client.address
            return (all.firstOrNull { a -> a.address.take(3) == c.take(3) } ?: all.firstOrNull())?.address
        }
    }
}
