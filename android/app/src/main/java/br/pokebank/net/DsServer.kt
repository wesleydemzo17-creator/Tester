package br.pokebank.net

import br.pokebank.core.Checksums
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * Servidor local que o Nintendo DS procura na rede.
 * Puro Java/Kotlin (sem Android), para poder ser testado no PC.
 * Atende um DS por vez.
 */
class DsServer(
    private val serverName: String,
    private val listener: Listener,
    private val tcpPort: Int = Protocol.TCP_PORT,
    private val udpPort: Int = Protocol.DISCOVERY_PORT,
) {
    interface Listener {
        fun onLog(msg: String)
        fun onConnected(hello: Protocol.Hello, address: String)
        fun onSaveReceived(save: ByteArray)
        fun onWriteResult(status: Int, crcReadBack: Long, sectors: Int)
        fun onDisconnected()
    }

    @Volatile private var running = false
    @Volatile private var client: Socket? = null
    private var server: ServerSocket? = null
    private var udp: DatagramSocket? = null

    val isClientConnected: Boolean get() = client?.isConnected == true && client?.isClosed == false

    fun start() {
        if (running) return
        running = true
        server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(tcpPort)) }
        udp = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(udpPort)) }
        thread(name = "pb-udp", isDaemon = true) { udpLoop() }
        thread(name = "pb-tcp", isDaemon = true) { acceptLoop() }
        listener.onLog("Servidor ouvindo: UDP $udpPort (descoberta), TCP $tcpPort")
    }

    fun stop() {
        running = false
        runCatching { client?.close() }
        runCatching { server?.close() }
        runCatching { udp?.close() }
    }

    private fun udpLoop() {
        val buf = ByteArray(64)
        while (running) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                udp!!.receive(pkt)
                val req = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
                if (req.size >= 8 && req.copyOf(8).contentEquals(Protocol.DISCOVER_REQ)) {
                    val name = Protocol.ascii(serverName).let { it.copyOf(minOf(it.size, 32)) }
                    val rsp = ByteArray(10 + name.size)
                    System.arraycopy(Protocol.DISCOVER_RSP, 0, rsp, 0, 8)
                    Protocol.putU16(rsp, 8, tcpPort)
                    System.arraycopy(name, 0, rsp, 10, name.size)
                    udp!!.send(DatagramPacket(rsp, rsp.size, pkt.socketAddress))
                    listener.onLog("DS procurando servidor: ${pkt.address.hostAddress}")
                }
            } catch (e: SocketException) {
                if (!running) return
            } catch (e: IOException) {
                listener.onLog("UDP: ${e.message}")
            }
        }
    }

    private fun acceptLoop() {
        while (running) {
            val s = try { server!!.accept() } catch (e: IOException) { if (!running) return; continue }
            if (isClientConnected) {
                runCatching { Protocol.write(s.getOutputStream(), Protocol.MESSAGE, Protocol.ascii("Outro DS ja esta conectado.")); s.close() }
                continue
            }
            client = s
            thread(name = "pb-client", isDaemon = true) { clientLoop(s) }
        }
    }

    private fun clientLoop(s: Socket) {
        val addr = s.inetAddress.hostAddress ?: "?"
        try {
            s.tcpNoDelay = true
            val input = s.getInputStream().buffered(64 * 1024)
            while (running) {
                val f = Protocol.read(input)
                when (f.type) {
                    Protocol.HELLO -> {
                        val hello = Protocol.Hello.parse(f.payload)
                        Protocol.write(s.getOutputStream(), Protocol.HELLO_ACK,
                            byteArrayOf(Protocol.VERSION.toByte()) + Protocol.ascii(serverName))
                        listener.onConnected(hello, addr)
                    }
                    Protocol.SAVE_DATA -> {
                        if (f.payload.size < 4) throw IOException("SAVE_DATA curto")
                        val crc = Protocol.u32(f.payload, 0)
                        val save = f.payload.copyOfRange(4, f.payload.size)
                        if (Checksums.crc32(save) != crc) {
                            listener.onLog("CRC do save nao confere; pedindo de novo")
                            sendMessage("Erro de transmissao. Reenviando...")
                            requestSave()
                        } else {
                            listener.onSaveReceived(save)
                        }
                    }
                    Protocol.WRITE_RESULT -> {
                        val p = f.payload
                        listener.onWriteResult(p[0].toInt() and 0xFF, Protocol.u32(p, 1), Protocol.u16(p, 5))
                    }
                    Protocol.BYE -> break
                    else -> listener.onLog("Mensagem desconhecida 0x%02X".format(f.type))
                }
            }
        } catch (e: java.io.EOFException) {
            if (running) listener.onLog("O DS fechou a conexao")
        } catch (e: IOException) {
            if (running) listener.onLog("Conexao com o DS caiu: ${e.message}")
        } finally {
            runCatching { s.close() }
            if (client === s) client = null
            listener.onDisconnected()
        }
    }

    private fun send(type: Int, payload: ByteArray = ByteArray(0)): Boolean {
        val c = client ?: return false
        return try { Protocol.write(c.getOutputStream(), type, payload); true } catch (e: IOException) {
            listener.onLog("Falha ao enviar: ${e.message}"); false
        }
    }

    fun sendMessage(text: String) = send(Protocol.MESSAGE, Protocol.ascii(text))
    fun requestSave() = send(Protocol.REQUEST_SAVE)

    fun sendWrite(save: ByteArray, summary: String): Boolean {
        val sum = Protocol.ascii(summary).let { if (it.size > 1024) it.copyOf(1024) else it }
        val p = ByteArray(8 + sum.size + save.size)
        Protocol.putU32(p, 0, Checksums.crc32(save))
        Protocol.putU32(p, 4, sum.size.toLong())
        System.arraycopy(sum, 0, p, 8, sum.size)
        System.arraycopy(save, 0, p, 8 + sum.size, save.size)
        return send(Protocol.WRITE_REQUEST, p)
    }
}
