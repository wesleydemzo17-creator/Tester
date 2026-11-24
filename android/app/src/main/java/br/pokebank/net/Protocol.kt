package br.pokebank.net

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Protocolo PokeBank DS <-> Android (versao 1). Tudo little-endian.
 *
 * Quadro TCP: 'P' 'B' | tipo (u8) | 0 (u8) | tamanho (u32) | payload
 * Descoberta UDP (porta [DISCOVERY_PORT]): DS manda "PKBANK?1" em broadcast,
 * o Android responde "PKBANK!1" + porta TCP (u16) + nome (ASCII).
 *
 * Veja PROTOCOL.md para a descricao completa de cada mensagem.
 */
object Protocol {
    const val VERSION = 1
    const val DISCOVERY_PORT = 47100
    const val TCP_PORT = 47101
    const val MAX_PAYLOAD = 0x80000 + 4096
    val DISCOVER_REQ = "PKBANK?1".toByteArray(Charsets.US_ASCII)
    val DISCOVER_RSP = "PKBANK!1".toByteArray(Charsets.US_ASCII)

    // Tipos de mensagem
    const val HELLO = 0x01          // DS -> A
    const val HELLO_ACK = 0x02      // A -> DS
    const val SAVE_DATA = 0x03      // DS -> A : crc32 + save
    const val MESSAGE = 0x04        // A -> DS : texto ASCII para mostrar
    const val WRITE_REQUEST = 0x05  // A -> DS : crc32 + tamResumo + resumo + save
    const val WRITE_RESULT = 0x06   // DS -> A : status + crc32 lido + setores gravados
    const val REQUEST_SAVE = 0x07   // A -> DS : "leia o cartucho de novo e mande"
    const val BYE = 0x09            // ambos

    // Status de WRITE_RESULT
    const val WR_OK = 0
    const val WR_CANCELLED = 1        // usuario recusou; nada foi gravado
    const val WR_FAILED_RESTORED = 2  // falhou, mas o save original foi regravado e conferido
    const val WR_FAILED_UNKNOWN = 3   // falhou e o estado do cartucho e incerto: use o backup!

    class Frame(val type: Int, val payload: ByteArray)

    fun write(out: OutputStream, type: Int, payload: ByteArray = ByteArray(0)) {
        val h = ByteArray(8)
        h[0] = 'P'.code.toByte(); h[1] = 'B'.code.toByte(); h[2] = type.toByte(); h[3] = 0
        putU32(h, 4, payload.size.toLong())
        synchronized(out) {
            out.write(h); out.write(payload); out.flush()
        }
    }

    fun read(input: InputStream): Frame {
        val din = DataInputStream(input)
        val h = ByteArray(8)
        din.readFully(h)
        if (h[0] != 'P'.code.toByte() || h[1] != 'B'.code.toByte()) throw IOException("Quadro invalido")
        val len = u32(h, 4)
        if (len < 0 || len > MAX_PAYLOAD) throw IOException("Tamanho invalido: $len")
        val p = ByteArray(len.toInt())
        din.readFully(p)
        return Frame(h[2].toInt() and 0xFF, p)
    }

    fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    fun u32(b: ByteArray, o: Int): Long = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
    fun putU16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte() }
    fun putU32(b: ByteArray, o: Int, v: Long) { putU16(b, o, (v and 0xFFFF).toInt()); putU16(b, o + 2, ((v ushr 16) and 0xFFFF).toInt()) }

    /** HELLO: versao u8, tipoChip u8, reservado u16, tamanhoSave u32, codigoJogo[4], titulo[12], irCard u8 */
    data class Hello(val version: Int, val chipType: Int, val saveSize: Long, val gameCode: String, val title: String, val infrared: Boolean) {
        companion object {
            fun parse(p: ByteArray): Hello {
                if (p.size < 24) throw IOException("HELLO curto")
                fun str(o: Int, n: Int) = String(p, o, n, Charsets.US_ASCII).trimEnd('\u0000', ' ')
                return Hello(p[0].toInt() and 0xFF, p[1].toInt() and 0xFF, u32(p, 4), str(8, 4), str(12, 12),
                    p.size > 24 && p[24].toInt() != 0)
            }
        }
    }

    fun ascii(s: String): ByteArray = br.pokebank.core.BankSession.ascii(s).toByteArray(Charsets.US_ASCII)
}
