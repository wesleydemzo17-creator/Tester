package br.pokebank

import br.pokebank.wfc.DnsServer
import br.pokebank.wfc.GsMessage
import br.pokebank.wfc.NasHttpServer
import br.pokebank.wfc.RootRedirect
import br.pokebank.wfc.WfcCrypto
import br.pokebank.wfc.WfcServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class WfcTest {
    private fun freePort(): Int = 40000 + java.util.Random().nextInt(20000)

    @Test fun messageParseAndBuild() {
        val m = GsMessage.build("login", "", "challenge" to "abc", "authtoken" to "NDSxyz", "id" to 1)
        assertEquals("\\login\\\\challenge\\abc\\authtoken\\NDSxyz\\id\\1\\final\\", m)
        val (msgs, rest) = GsMessage.parse(m + m + "\\ka\\\\fin")
        assertEquals(2, msgs.size)
        assertEquals("login", msgs[0].cmd)
        assertEquals("NDSxyz", msgs[1]["authtoken"])
        assertEquals("\\ka\\\\fin", rest) // mensagem cortada fica para depois
        val st = GsMessage.parse("\\status\\3\\sesskey\\1\\statstring\\Wi-Fi Club\\locstring\\x\\final\\").first[0]
        assertEquals("3", st.cmdVal); assertEquals("Wi-Fi Club", st["statstring"])
    }

    @Test fun cryptoMatchesAltWfc() {
        // valores calculados com as funcoes do proprio AltWFC (gs_utility.py / utils.py)
        assertEquals("54dc229148fbb6b96628a4e7c07cb9e2", WfcCrypto.response("ABCDEFGHIJ", "nasch123", "clientchallenge", "NDStoken"))
        assertEquals("ad3afc070dc333f26fc8398ed81fd763", WfcCrypto.proof("ABCDEFGHIJ", "nasch123", "clientchallenge", "NDStoken"))
        assertEquals(30064771073L, WfcCrypto.friendCode(1, "CPUE"))
        assertEquals(184683717184L, WfcCrypto.friendCode(123456, "IPKE"))
        assertEquals("13tovm16b", WfcCrypto.base32(1234567890123L))
        assertEquals("0300-6477-1073", WfcCrypto.formatFriendCode(30064771073L))
        assertEquals("Wii", WfcCrypto.nasDecode(WfcCrypto.nasEncode("Wii")))
        assertEquals("ok", WfcCrypto.nasDecode("b2s*")) // '*' no lugar de '='
    }

    private fun dnsQuery(name: String, type: Int = 1): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
        name.split('.').forEach { o.write(it.length); o.write(it.toByteArray()) }
        o.write(0); o.write(0); o.write(type); o.write(0); o.write(1)
        return o.toByteArray()
    }

    @Test fun dnsRedirectsNintendoDomains() {
        assertTrue(DnsServer.shouldRedirect("nas.nintendowifi.net"))
        assertTrue(DnsServer.shouldRedirect("pokemondpds.available.gs.nintendowifi.net."))
        assertTrue(DnsServer.shouldRedirect("gpcm.gs.nintendowifi.net"))
        assertFalse(DnsServer.shouldRedirect("google.com"))
        assertFalse(DnsServer.shouldRedirect("evilnintendowifi.net"))
        val port = freePort()
        val dns = DnsServer(port, {}, answerFor = { byteArrayOf(192.toByte(), 168.toByte(), 43, 1) })
        dns.start()
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 3000
                val q = dnsQuery("conntest.nintendowifi.net")
                s.send(DatagramPacket(q, q.size, InetAddress.getLoopbackAddress(), port))
                val b = ByteArray(512); val p = DatagramPacket(b, b.size); s.receive(p)
                val r = b.copyOf(p.length)
                assertEquals(0x12, r[0].toInt()); assertEquals(0x34, r[1].toInt())
                assertEquals(1, r[7].toInt()) // 1 resposta
                assertEquals(listOf(192, 168, 43, 1), r.copyOfRange(r.size - 4, r.size).map { it.toInt() and 0xFF })
                // AAAA (IPv6) para dominio desviado: resposta vazia, sem erro
                val q6 = dnsQuery("nas.nintendowifi.net", 28)
                s.send(DatagramPacket(q6, q6.size, InetAddress.getLoopbackAddress(), port))
                val p6 = DatagramPacket(b, b.size); s.receive(p6)
                assertEquals(0, b[7].toInt())
            }
        } finally { dns.stop() }
    }

    private fun http(port: Int, method: String, path: String, host: String, body: String = ""): Pair<String, String> {
        Socket("127.0.0.1", port).use { s ->
            val req = "$method $path HTTP/1.1\r\nHost: $host\r\nContent-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: ${body.length}\r\n\r\n$body"
            s.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
            val resp = s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            return resp.substringBefore("\r\n\r\n") to resp.substringAfter("\r\n\r\n")
        }
    }

    private fun form(vararg kv: Pair<String, String>) = kv.joinToString("&") { "${it.first}=${WfcCrypto.nasEncode(it.second)}" }

    /** Simula o que o DS faz: cria conta, faz login no NAS e devolve (authtoken, challenge). */
    private fun nasLogin(port: Int, name: String, gsbrcd: String): Triple<String, String, String> {
        val (_, b1) = http(port, "POST", "/ac", "nas.nintendowifi.net", form("action" to "acctcreate", "gsbrcd" to gsbrcd, "ingamesn" to name))
        val r1 = NasHttpServer.parseForm(b1)
        assertEquals("002", r1["returncd"])
        val userid = r1["userid"]!!
        val (_, b2) = http(port, "POST", "/ac", "nas.nintendowifi.net",
            form("action" to "login", "userid" to userid, "gsbrcd" to gsbrcd, "gamecd" to gsbrcd.take(4), "ingamesn" to name, "macadr" to "0009bf112233"))
        val r2 = NasHttpServer.parseForm(b2)
        assertEquals("001", r2["returncd"])
        assertTrue(r2["token"]!!.startsWith("NDS"))
        return Triple(r2["token"]!!, r2["challenge"]!!, userid)
    }

    /** Cliente GPCM falso (como o DS): le mensagens numa fila. */
    private class FakeDs(port: Int) {
        val s = Socket("127.0.0.1", port)
        val q = LinkedBlockingQueue<GsMessage>()
        init {
            thread(isDaemon = true) {
                val buf = ByteArray(4096); var pending = ""
                try {
                    while (true) {
                        val n = s.getInputStream().read(buf); if (n < 0) break
                        val (m, rest) = GsMessage.parse(pending + String(buf, 0, n, Charsets.ISO_8859_1)); pending = rest
                        m.forEach { q.add(it) }
                    }
                } catch (_: Exception) {}
            }
        }
        fun send(m: String) = s.getOutputStream().write(m.toByteArray(Charsets.ISO_8859_1))
        fun next(cmd: String, timeoutS: Long = 5): GsMessage {
            val end = System.currentTimeMillis() + timeoutS * 1000
            while (System.currentTimeMillis() < end) {
                val m = q.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (m.cmd == cmd) return m
            }
            throw AssertionError("nao recebi '$cmd'")
        }
        fun nextBm(type: String, from: Long, contains: String = ""): GsMessage {
            val end = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < end) {
                val m = q.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (m.cmd == "bm" && m.cmdVal == type && m["f"] == from.toString() && (m["msg"] ?: "").contains(contains)) return m
            }
            throw AssertionError("nao recebi bm $type de $from")
        }
    }

    private fun gpcmLogin(port: Int, nas: Triple<String, String, String>): Pair<FakeDs, Long> {
        val ds = FakeDs(port)
        val lc1 = ds.next("lc")
        assertEquals("1", lc1.cmdVal)
        val serverChallenge = lc1["challenge"]!!
        val clientChallenge = WfcCrypto.randomStr(32)
        val response = WfcCrypto.response(serverChallenge, nas.second, clientChallenge, nas.first)
        ds.send(GsMessage.build("login", "", "challenge" to clientChallenge, "authtoken" to nas.first, "partnerid" to 11,
            "response" to response, "port" to 0, "productid" to 11059, "gamename" to "pokemondpds", "namespaceid" to 16,
            "sdkrevision" to 3, "quiet" to 0, "id" to 1))
        val lc2 = ds.next("lc")
        assertEquals("2", lc2.cmdVal)
        assertEquals(WfcCrypto.proof(serverChallenge, nas.second, clientChallenge, nas.first), lc2["proof"])
        return ds to lc2["profileid"]!!.toLong()
    }

    @Test fun fullServerLoginFriendsAndStatus() {
        val dir = Files.createTempDirectory("wfc").toFile()
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val srv = WfcServer(dir, { logs.add(it) }, dnsPort = freePort(), httpPort = freePort(), gpcmPort = freePort(), gpspPort = freePort())
        srv.start()
        try {
            // conntest (o "Testar conexao" do DS)
            val (h, b) = http(srv.httpPort, "GET", "/", "conntest.nintendowifi.net")
            assertTrue(h.startsWith("HTTP/1.1 200"))
            assertTrue(h.contains("X-Organization: Nintendo"))
            assertEquals("ok", b)

            val nasA = nasLogin(srv.httpPort, "LUCAS", "CPUE0000")   // DS com Platinum
            val nasB = nasLogin(srv.httpPort, "DAWN", "CPUE0000")    // melonDS com Platinum
            val (a, pidA) = gpcmLogin(srv.gpcmPort, nasA)
            val (bb, pidB) = gpcmLogin(srv.gpcmPort, nasB)
            assertTrue(pidA > 0 && pidB > 0 && pidA != pidB)
            assertEquals(2, srv.online().size)

            // Pal Pad: A registra B (B recebe pedido), B registra A -> amigos mutuos
            a.send(GsMessage.build("addbuddy", "", "sesskey" to 1, "newprofileid" to pidB, "reason" to ""))
            bb.nextBm("2", pidA)
            bb.send(GsMessage.build("addbuddy", "", "sesskey" to 1, "newprofileid" to pidA, "reason" to ""))
            a.nextBm("2", pidB)
            bb.nextBm("4", pidA)

            // A entra no Wi-Fi Club: B recebe o status com o IP de A
            a.send(GsMessage.build("status", "3", "sesskey" to 1, "statstring" to "WiFi Club", "locstring" to "pokemon"))
            val st = bb.nextBm("100", pidA, contains = "WiFi Club")
            assertTrue(st["msg"]!!.contains("|s|3|ss|WiFi Club|ls|pokemon"))
            assertTrue(st["msg"]!!.contains("|ip|${WfcCrypto.ipToInt(WfcServer.publicIp("127.0.0.1"))}|"))

            // mensagem direta (os jogos usam para combinar a partida)
            bb.send(GsMessage.build("bm", "1", "sesskey" to 1, "t" to pidA, "msg" to "GPCM3vMAT03"))
            assertEquals("GPCM3vMAT03", a.nextBm("1", pidB, contains = "GPCM3vMAT03")["msg"])

            // relogar mantem o mesmo perfil (mesmo userid + jogo)
            a.s.close()
            Thread.sleep(300)
            val nasA2 = run {
                val (_, b2) = http(srv.httpPort, "POST", "/ac", "nas.nintendowifi.net",
                    form("action" to "login", "userid" to nasA.third, "gsbrcd" to "CPUE0000", "ingamesn" to "LUCAS"))
                val r = NasHttpServer.parseForm(b2); Triple(r["token"]!!, r["challenge"]!!, nasA.third)
            }
            val (_, pidA2) = gpcmLogin(srv.gpcmPort, nasA2)
            assertEquals(pidA, pidA2)

            // GPSP
            Socket("127.0.0.1", srv.gpspPort).use { s ->
                s.getOutputStream().write(GsMessage.build("otherslist", "", "sesskey" to 1, "numopids" to 1, "opids" to pidB, "gamename" to "pokemondpds").toByteArray())
                val buf = ByteArray(1024); val n = s.getInputStream().read(buf)
                val m = GsMessage.parse(String(buf, 0, n, Charsets.ISO_8859_1)).first[0]
                assertEquals("otherslist", m.cmd)
                assertNotNull(m["uniquenick"]); assertTrue(m["uniquenick"]!!.endsWith("CPUE0000"))
            }
        } finally { srv.stop(); dir.deleteRecursively() }
        assertTrue(logs.any { it.contains("agora sao amigos") })
    }

    @Test fun rootCommandsAreScoped() {
        val on = RootRedirect.enableCommands(5353, 8080)
        assertTrue(on.all { !it.contains("-F PREROUTING") && !it.contains("-F OUTPUT") }) // nunca limpa cadeias do sistema
        assertTrue(on.any { it.contains("--dport 53") && it.contains("--to-ports 5353") && it.contains("dst-type LOCAL") })
        assertTrue(on.any { it.contains("--dport 80") && it.contains("--to-ports 8080") })
        assertTrue(RootRedirect.disableCommands().any { it.contains("-X ${RootRedirect.CHAIN}") })
    }
}
