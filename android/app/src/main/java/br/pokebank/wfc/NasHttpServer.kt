package br.pokebank.wfc

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

/**
 * HTTP do WFC: teste de conexao (conntest) e autenticacao da Nintendo (nas.nintendowifi.net/ac).
 * Porte do nas_server.py do AltWFC. Funciona com jogos com o patch "NoSSL" (http:// em vez de https://).
 */
class NasHttpServer(private val port: Int, private val db: WfcDb, private val log: (String) -> Unit) {
    @Volatile private var running = false
    private var server: ServerSocket? = null

    fun start() {
        val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
        server = ss
        running = true
        thread(name = "wfc-http", isDaemon = true) {
            while (running) {
                val s = try { ss.accept() } catch (e: Exception) { if (!running) return@thread else continue }
                thread(isDaemon = true) { runCatching { handle(s) }.onFailure { log("HTTP: ${it.message}") }; runCatching { s.close() } }
            }
        }
        log("HTTP (conntest/NAS) ouvindo na porta $port")
    }

    fun stop() { running = false; runCatching { server?.close() } }

    private fun readLine(i: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = i.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("ISO-8859-1")
            if (c == '\n'.code) return b.toString("ISO-8859-1").trimEnd('\r')
            b.write(c)
        }
    }

    private fun handle(s: Socket) {
        s.soTimeout = 15000
        val input = BufferedInputStream(s.getInputStream())
        val reqLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val l = readLine(input) ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val parts = reqLine.split(' ')
        val method = parts.getOrNull(0) ?: return
        val path = parts.getOrNull(1) ?: "/"
        val host = headers["host"] ?: ""
        val body = headers["content-length"]?.toIntOrNull()?.let { n ->
            val b = ByteArray(n); var r = 0
            while (r < n) { val k = input.read(b, r, n - r); if (k < 0) break; r += k }
            String(b, 0, r, Charsets.ISO_8859_1)
        } ?: ""
        val client = s.inetAddress.hostAddress ?: "?"
        log("HTTP $client $method $host$path")

        val out = s.getOutputStream()
        fun reply(status: String, contentType: String, text: String, extra: Map<String, String> = emptyMap()) {
            val bytes = text.toByteArray(Charsets.ISO_8859_1)
            val sb = StringBuilder("HTTP/1.1 $status\r\n")
            sb.append("Content-Type: $contentType\r\n")
            extra.forEach { (k, v) -> sb.append("$k: $v\r\n") }
            sb.append("Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1)); out.write(bytes); out.flush()
        }

        if (method == "GET") {
            // conntest.nintendowifi.net: o "Testar conexao" do DS so quer um 200 com "ok"
            reply("200 OK", "text/html", "ok", mapOf("X-Organization" to "Nintendo", "Server" to "BigIP"))
            return
        }
        if (method != "POST") { reply("405 Method Not Allowed", "text/plain", ""); return }
        val post = parseForm(body).toMutableMap()
        post["ipaddr"] = client
        when (path.substringBefore('?')) {
            "/ac" -> {
                val ret = handleAc(post)
                ret["datetime"] = nowStamp()
                reply("200 OK", "text/plain", toQs(ret), mapOf("NODE" to "wifiappe1"))
            }
            "/pr" -> {
                val words = (post["words"] ?: "").split('\t').size
                val zeros = "0".repeat(words)
                val ret = linkedMapOf("prwords" to zeros, "returncd" to "000", "datetime" to nowStamp())
                for (l in "ACEJKP") ret["prwords$l"] = zeros
                reply("200 OK", "text/plain", toQs(ret), mapOf("NODE" to "wifiappe1"))
            }
            else -> reply("404 Not Found", "text/plain", "")
        }
    }

    fun handleAc(post: Map<String, String>): LinkedHashMap<String, String> {
        val action = (post["action"] ?: "").lowercase()
        log("NAS action=$action gamecd=${post["gamecd"]} gsbrcd=${post["gsbrcd"]} userid=${post["userid"]} nome=${post["ingamesn"] ?: post["devname"]}")
        return when (action) {
            "acctcreate" -> linkedMapOf("retry" to "0", "returncd" to "002", "userid" to db.nextUserId())
            "login" -> {
                val challenge = WfcCrypto.randomStr(8)
                val token = "NDS" + WfcCrypto.randomStr(80)
                db.saveNasLogin(token, post + ("challenge" to challenge))
                linkedMapOf("retry" to "0", "returncd" to "001", "locator" to "gamespy.com", "challenge" to challenge, "token" to token)
            }
            "svcloc" -> {
                val token = "NDS" + WfcCrypto.randomStr(80)
                db.saveNasLogin(token, post)
                val ret = linkedMapOf("retry" to "0", "returncd" to "007", "statusdata" to "Y")
                when (post["svc"]) {
                    "9000" -> { ret["svchost"] = "dls1.nintendowifi.net"; ret["token"] = token }
                    "9001" -> { ret["svchost"] = "dls1.nintendowifi.net"; ret["servicetoken"] = token }
                    else -> { ret["svchost"] = "n/a"; ret["servicetoken"] = token } // Pokemon pede svc=0000
                }
                ret
            }
            else -> { log("NAS: acao desconhecida '$action'"); LinkedHashMap() }
        }
    }

    companion object {
        fun parseForm(body: String): Map<String, String> =
            body.trim().split('&').filter { it.contains('=') }.associate {
                val k = it.substringBefore('=')
                k to WfcCrypto.nasDecode(it.substringAfter('='))
            }

        fun toQs(d: Map<String, String>): String =
            d.entries.joinToString("&") { "${it.key}=${WfcCrypto.nasEncode(it.value)}" } + "\r\n"

        fun nowStamp(): String = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date())
    }
}
