package br.pokebank.wfc

import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * gpcm.gs.nintendowifi.net (porta 29900): login, perfil, status e lista de amigos (Pal Pad).
 * Porte do gamespy_profile_server.py do AltWFC.
 */
class GpcmServer(
    private val port: Int,
    private val db: WfcDb,
    private val log: (String) -> Unit,
    /** IP a anunciar quando o cliente esta no proprio celular (melonDS): o IP do celular na rede. */
    private val publicIpFor: (String) -> String = { it },
) {
    @Volatile private var running = false
    private var server: ServerSocket? = null
    val sessions = ConcurrentHashMap<Long, Session>()

    fun start() {
        val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
        server = ss
        running = true
        thread(name = "wfc-gpcm", isDaemon = true) {
            while (running) {
                val s = try { ss.accept() } catch (e: Exception) { if (!running) return@thread else continue }
                thread(isDaemon = true) { Session(s).run() }
            }
        }
        log("GPCM (perfis/amigos) ouvindo na porta $port")
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        sessions.values.forEach { runCatching { it.socket.close() } }
    }

    inner class Session(val socket: Socket) {
        private val out: OutputStream = socket.getOutputStream()
        val challenge = WfcCrypto.randomStr(10, "ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        var profileId = 0L; private set
        var gameId = ""; private set
        var status = "0"; private set
        var statstring = "Offline"; private set
        var locstring = ""; private set
        var name = ""; private set
        private var sesskey = ""
        val remoteIp: String = publicIpFor(socket.inetAddress.hostAddress ?: "0.0.0.0")

        fun send(msg: String) {
            synchronized(out) { runCatching { out.write(msg.toByteArray(Charsets.ISO_8859_1)); out.flush() } }
        }

        fun run() {
            log("GPCM: conexao de ${socket.inetAddress.hostAddress}")
            try {
                socket.keepAlive = true
                send(GsMessage.build("lc", "1", "challenge" to challenge, "id" to 1))
                val input = socket.getInputStream()
                val buf = ByteArray(4096)
                var pending = ""
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    var data = pending + String(buf, 0, n, Charsets.ISO_8859_1)
                    if (data.isNotEmpty() && data[0] != '\\') {
                        val f = data.indexOf("\\final\\")
                        data = if (f >= 0) data.substring(f + 7) else ""
                    }
                    val (msgs, rest) = GsMessage.parse(data)
                    pending = rest
                    for (m in msgs) dispatch(m)
                }
            } catch (_: Exception) {
            } finally {
                status = "0"; statstring = "Offline"; locstring = ""
                if (profileId != 0L) {
                    sendStatusToFriends()
                    if (sessions[profileId] === this) sessions.remove(profileId)
                    log("GPCM: $name ($profileId) saiu")
                }
                runCatching { socket.close() }
            }
        }

        private fun dispatch(m: GsMessage) {
            when (m.cmd) {
                "login" -> login(m)
                "logout" -> runCatching { socket.close() }
                "getprofile" -> getProfile(m)
                "updatepro" -> m.fields.filterKeys { it !in setOf("updatepro", "partnerid", "sesskey") }
                    .forEach { (k, v) -> db.updateProfile(profileId, k, v) }
                "ka" -> send(GsMessage.build("ka", ""))
                "status" -> statusCmd(m)
                "bm" -> bm(m)
                "addbuddy" -> addBuddy(m)
                "delbuddy" -> m["delprofileid"]?.toLongOrNull()?.let { db.deleteBuddy(profileId, it) }
                "authadd" -> authAdd(m)
                else -> log("GPCM: comando desconhecido '${m.cmd}'")
            }
        }

        private fun login(m: GsMessage) {
            val token = m["authtoken"] ?: ""
            val nas = db.nasLogin(token)
            if (nas == null) {
                log("GPCM: authtoken invalido (o NAS foi pulado? jogo sem patch NoSSL?)")
                send(GsMessage.build("error", "", "err" to 266, "fatal" to "", "errmsg" to "There was an error validating the pre-authentication.", "id" to m["id"]))
                return
            }
            val clientChallenge = m["challenge"] ?: ""
            val expected = WfcCrypto.response(challenge, nas["challenge"] ?: "", clientChallenge, token)
            if (m["response"] != expected) log("GPCM: resposta de login diferente da esperada (seguindo mesmo assim, como o AltWFC)")
            val proof = WfcCrypto.proof(challenge, nas["challenge"] ?: "", clientChallenge, token)
            val p = db.loginOrCreate(nas)
            if (p == null) {
                send(GsMessage.build("error", "", "err" to 256, "fatal" to "", "errmsg" to "Login failed.", "id" to m["id"]))
                return
            }
            profileId = p.profileId
            gameId = p.gsbrcd.take(4)
            name = p.ingamesn.ifBlank { p.devname }
            sesskey = (100_000_000 + (Math.random() * 800_000_000).toLong()).toString()
            sessions[profileId]?.takeIf { it !== this }?.let { runCatching { it.socket.close() } }
            sessions[profileId] = this
            val lt = java.util.Base64.getEncoder().encodeToString(WfcCrypto.randomStr(16).toByteArray())
            send(GsMessage.build(
                "lc", "2", "sesskey" to sesskey, "proof" to proof, "userid" to p.userId, "profileid" to p.profileId,
                "uniquenick" to p.uniquenick, "lt" to lt, "id" to m["id"],
            ))
            log("GPCM: login de '$name' jogo ${p.gsbrcd} profile ${p.profileId} codigo de amigo ${WfcCrypto.formatFriendCode(WfcCrypto.friendCode(p.profileId, gameId))}")
            db.takePending(profileId).forEach { send(it.msg) }
            getStatusFromFriends()
            sendStatusToFriends()
        }

        private fun getProfile(m: GsMessage) {
            val p = m["profileid"]?.toLongOrNull()?.let { db.profile(it) } ?: return
            send(GsMessage.build(
                "pi", "", "profileid" to p.profileId, "nick" to p.uniquenick, "userid" to p.userId, "email" to p.email,
                "sig" to WfcCrypto.randomStr(32, "0123456789abcdef"), "uniquenick" to p.uniquenick, "pid" to "11",
                "lon" to "0.000000", "lat" to "0.000000", "loc" to "", "id" to m["id"],
            ))
        }

        private fun statusCmd(m: GsMessage) {
            m["sesskey"]?.let { sesskey = it }
            status = m.cmdVal
            statstring = m["statstring"] ?: ""
            locstring = m["locstring"] ?: ""
            log("GPCM: status de '$name': s=$status ss=$statstring ls=$locstring")
            // pedidos de amizade pendentes e avisos de autorizacao
            db.pendingRequestsTo(profileId).forEach { sendBuddyRequest(this, it.user, it.time) }
            db.needAuthMessage(profileId).forEach {
                send(GsMessage.build("bm", "1", "f" to it.user, "msg" to "I have authorized your request to add me to your list"))
                db.markAuthNotified(it.user, it.buddy)
            }
            sendStatusToFriends()
        }

        private fun bm(m: GsMessage) {
            if (m.cmdVal !in setOf("1", "5", "102", "103")) return
            val dest = m["t"]?.toLongOrNull() ?: return
            val msg = GsMessage.build("bm", "1", "f" to profileId, "msg" to (m["msg"] ?: ""))
            val target = sessions[dest]
            if (target != null) {
                log("GPCM: mensagem de $profileId para $dest (tipo ${m.cmdVal})")
                target.send(msg)
                sendStatusToFriends(dest)
                getStatusFromFriends(dest)
            } else if (m.cmdVal == "1") {
                db.savePending(profileId, dest, msg)
            } else {
                send(GsMessage.build("error", "", "err" to 2307, "errmsg" to "The buddy to send a message to is offline.", "id" to 1))
            }
        }

        private fun addBuddy(m: GsMessage) {
            val other = m["newprofileid"]?.toLongOrNull() ?: return
            if (other == profileId) return
            val exists = db.buddies(profileId).any { it.buddy == other }
            if (!exists) {
                db.addBuddy(profileId, other)
                log("GPCM: $profileId adicionou $other no Pal Pad")
                val target = sessions[other]
                if (target != null) {
                    val mutual = db.buddies(other).any { it.buddy == profileId && !it.blocked }
                    if (mutual) {
                        sendBuddyRequest(target, profileId)
                        sendBuddyRequest(this, other)
                        sendBm4(other)
                        db.authBuddy(other, profileId)
                        db.authBuddy(profileId, other)
                        log("GPCM: $profileId e $other agora sao amigos")
                        sendStatusToFriends(other)
                        getStatusFromFriends(other)
                    } else {
                        sendBuddyRequest(target, profileId)
                    }
                }
            } else {
                sendStatusToFriends(other)
                getStatusFromFriends(other)
            }
        }

        private fun authAdd(m: GsMessage) {
            val from = m["fromprofileid"]?.toLongOrNull() ?: return
            db.authBuddy(from, profileId)
            db.needAuthMessage(profileId).forEach {
                send(GsMessage.build("bm", "1", "f" to it.user, "msg" to "I have authorized your request to add me to your list"))
                db.markAuthNotified(it.user, it.buddy)
            }
            sendBm4(from)
            sendStatusToFriends(from)
            getStatusFromFriends(from)
        }

        private fun sendBm4(player: Long) =
            send(GsMessage.build("bm", "4", "f" to player, "date" to System.currentTimeMillis() / 1000, "msg" to ""))

        private fun sendBuddyRequest(to: Session, from: Long, time: Long = System.currentTimeMillis() / 1000) {
            val sig = WfcCrypto.randomStr(32, "0123456789abcdef")
            to.send(GsMessage.build("bm", "2", "f" to from, "date" to time, "msg" to "\r\n\r\n|signed|$sig"))
        }

        fun statusMessage(): String =
            if (status == "0" && statstring == "Offline") "|s|$status|ss|$statstring"
            else "|s|$status|ss|$statstring|ls|$locstring|ip|${WfcCrypto.ipToInt(remoteIp)}|p|0|qm|0"

        fun sendStatusToFriends(only: Long? = null) {
            val msg = GsMessage.build("bm", "100", "f" to profileId, "msg" to statusMessage())
            val targets = only?.let { listOf(it) } ?: db.buddies(profileId).map { it.buddy }
            targets.forEach { sessions[it]?.send(msg) }
        }

        fun getStatusFromFriends(@Suppress("UNUSED_PARAMETER") only: Long? = null) {
            for (b in db.buddies(profileId)) {
                if (b.status != 1) continue
                val other = sessions[b.buddy]
                val st = if (other != null && other.gameId == gameId) other.statusMessage() else "|s|0|ss|Offline"
                send(GsMessage.build("bm", "100", "f" to b.buddy, "msg" to st))
            }
        }
    }
}

/** gpsp.gs.nintendowifi.net (porta 29901): busca de jogadores. Porte do gamespy_player_search_server.py. */
class GpspServer(private val port: Int, private val db: WfcDb, private val log: (String) -> Unit) {
    @Volatile private var running = false
    private var server: ServerSocket? = null

    fun start() {
        val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
        server = ss
        running = true
        thread(name = "wfc-gpsp", isDaemon = true) {
            while (running) {
                val s = try { ss.accept() } catch (e: Exception) { if (!running) return@thread else continue }
                thread(isDaemon = true) { serve(s) }
            }
        }
        log("GPSP (busca de jogadores) ouvindo na porta $port")
    }

    fun stop() { running = false; runCatching { server?.close() } }

    private fun serve(s: Socket) {
        try {
            val input = s.getInputStream()
            val buf = ByteArray(4096)
            var pending = ""
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                val (msgs, rest) = GsMessage.parse(pending + String(buf, 0, n, Charsets.ISO_8859_1))
                pending = rest
                for (m in msgs) {
                    if (m.cmd != "otherslist") { log("GPSP: comando '${m.cmd}' ignorado"); continue }
                    val pairs = mutableListOf<Pair<String, Any?>>()
                    (m["opids"] ?: "").split('|').filter { it.isNotBlank() }.forEach { op ->
                        pairs += "o" to op
                        pairs += "uniquenick" to (op.toLongOrNull()?.let { db.profile(it)?.uniquenick } ?: "")
                    }
                    pairs += "oldone" to ""
                    s.getOutputStream().write(GsMessage.build("otherslist", "", *pairs.toTypedArray()).toByteArray(Charsets.ISO_8859_1))
                }
            }
        } catch (_: Exception) {
        } finally { runCatching { s.close() } }
    }
}
