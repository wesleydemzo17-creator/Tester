package br.pokebank.wfc

import java.security.MessageDigest
import java.util.Base64

/**
 * Mensagens no formato GameSpy: \comando\valor\chave\valor...\final\
 * Porte fiel do gs_query.py do AltWFC (dwc_network_server_emulator, GPLv3/AGPL).
 */
class GsMessage(val cmd: String, val cmdVal: String, val fields: LinkedHashMap<String, String>) {
    operator fun get(k: String): String? = fields[k]

    companion object {
        private const val FINAL = "\\final\\"

        /** Retorna as mensagens completas e o que sobrou (mensagem cortada no meio). */
        fun parse(input: String): Pair<List<GsMessage>, String> {
            var msg = input
            val out = mutableListOf<GsMessage>()
            while (msg.isNotEmpty() && msg[0] == '\\' && msg.contains(FINAL)) {
                var cmd: String? = null
                var cmdVal = ""
                val fields = LinkedHashMap<String, String>()
                while (msg.isNotEmpty() && msg[0] == '\\') {
                    val keyEnd = msg.indexOf('\\', 1)
                    if (keyEnd < 0) break
                    val key = msg.substring(1, keyEnd)
                    msg = msg.substring(keyEnd + 1)
                    if (key == "final") break
                    val value: String
                    if (msg.contains('\\')) {
                        if (msg[0] == '\\') value = "" else {
                            val ve = msg.indexOf('\\')
                            value = msg.substring(0, ve)
                            msg = msg.substring(ve)
                        }
                    } else {
                        value = msg
                        msg = ""
                    }
                    if (cmd == null) { cmd = key; cmdVal = value }
                    fields[key] = value
                }
                out.add(GsMessage(cmd ?: "", cmdVal, fields))
            }
            return out to msg
        }

        /** Monta uma mensagem: o 1o par e o comando. */
        fun build(cmd: String, cmdVal: String, vararg pairs: Pair<String, Any?>): String {
            val sb = StringBuilder("\\").append(cmd).append('\\').append(cmdVal)
            for ((k, v) in pairs) sb.append('\\').append(k).append('\\').append(v?.toString() ?: "")
            return sb.append(FINAL).toString()
        }
    }
}

object WfcCrypto {
    fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.ISO_8859_1)).joinToString("") { "%02x".format(it) }

    /** Resposta que o cliente (DS) manda no login GPCM. */
    fun response(serverChallenge: String, nasChallenge: String, clientChallenge: String, authtoken: String): String {
        val h = md5Hex(nasChallenge)
        return md5Hex(h + " ".repeat(0x30) + authtoken + clientChallenge + serverChallenge + h)
    }

    /** Prova que o servidor devolve (igual a resposta, com os desafios trocados). */
    fun proof(serverChallenge: String, nasChallenge: String, clientChallenge: String, authtoken: String): String {
        val h = md5Hex(nasChallenge)
        return md5Hex(h + " ".repeat(0x30) + authtoken + serverChallenge + clientChallenge + h)
    }

    fun base32(num: Long): String {
        val alpha = "0123456789abcdefghijklmnopqrstuv"
        var n = num
        val sb = StringBuilder()
        while (n > 0) { sb.append(alpha[(n and 0x1F).toInt()]); n = n ushr 5 }
        return sb.reverse().toString()
    }

    /** Campos do NAS: base64 com '*' no lugar de '=' (e variantes para '+' e '/'). */
    fun nasDecode(v: String): String = try {
        val s = java.net.URLDecoder.decode(v, "ISO-8859-1")
            .replace('*', '=').replace('?', '/').replace('>', '+').replace('-', '/')
        String(Base64.getDecoder().decode(s), Charsets.ISO_8859_1)
    } catch (e: IllegalArgumentException) { v }

    fun nasEncode(v: String): String = Base64.getEncoder().encodeToString(v.toByteArray(Charsets.ISO_8859_1)).replace('=', '*')

    private val CRC8 = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var c = i
            repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
            t[i] = c
        }
    }

    /** Codigo de amigo DWC (12 digitos) a partir do profile id e do codigo do jogo (ex.: "CPUE"). */
    fun friendCode(profileId: Long, gameId: String): Long {
        var crc = 0
        val buf = ByteArray(8)
        for (i in 0..3) buf[i] = (profileId ushr (8 * i)).toByte()
        gameId.take(4).forEachIndexed { i, c -> buf[4 + i] = c.code.toByte() }
        for (b in buf) crc = CRC8[(crc xor (b.toInt() and 0xFF)) and 0xFF]
        return ((crc and 0x7F).toLong() shl 32) or (profileId and 0xFFFFFFFFL)
    }

    fun formatFriendCode(fc: Long): String = "%012d".format(fc).chunked(4).joinToString("-")

    fun randomStr(n: Int, chars: String = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"): String {
        val r = java.security.SecureRandom()
        return (1..n).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    fun ipToInt(ip: String): Long = ip.split('.').fold(0L) { acc, p -> (acc shl 8) or (p.toLong() and 0xFF) }
}
