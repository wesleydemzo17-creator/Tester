package br.pokebank.wfc

import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

/**
 * Banco de dados do servidor WFC (contas, perfis, amigos, mensagens pendentes).
 * Mesmas regras do gs_database.py do AltWFC, guardado num arquivo do app.
 */
class WfcDb(private val file: File?) {
    data class Profile(
        val profileId: Long, val userId: String, val gsbrcd: String, val uniquenick: String,
        val email: String, val gameId: String, val devname: String = "", val ingamesn: String = "",
        val extra: HashMap<String, String> = HashMap(),
    ) : Serializable

    data class Buddy(
        val user: Long, val buddy: Long, val time: Long,
        var status: Int = 0,        // 0 = pedido, 1 = autorizado
        var notified: Boolean = false,
        var blocked: Boolean = false,
    ) : Serializable

    data class Pending(val from: Long, val to: Long, val msg: String) : Serializable

    private class State : Serializable {
        val profiles = ArrayList<Profile>()
        val buddies = ArrayList<Buddy>()
        val pending = ArrayList<Pending>()
        var lastUserId = 1L
        companion object { private const val serialVersionUID = 1L }
    }

    private var s: State = load()
    /** Logins do NAS (authtoken -> campos): so na memoria, valem ate o servidor reiniciar. */
    private val nasLogins = HashMap<String, Map<String, String>>()

    @Suppress("UNCHECKED_CAST")
    private fun load(): State = try {
        if (file != null && file.exists()) ObjectInputStream(file.inputStream()).use { it.readObject() as State } else State()
    } catch (e: Exception) { State() }

    private fun save() {
        val f = file ?: return
        val tmp = File(f.path + ".tmp")
        ObjectOutputStream(tmp.outputStream()).use { it.writeObject(s) }
        tmp.renameTo(f)
    }

    @Synchronized fun nextUserId(): String {
        s.lastUserId++
        save()
        return s.lastUserId.toString().padStart(13, '0')
    }

    @Synchronized fun saveNasLogin(authtoken: String, data: Map<String, String>) { nasLogins[authtoken] = data }
    @Synchronized fun nasLogin(authtoken: String): Map<String, String>? = nasLogins[authtoken]

    /** Encontra ou cria o perfil deste console + jogo. */
    @Synchronized fun loginOrCreate(login: Map<String, String>): Profile? {
        val userId = login["userid"] ?: return null
        val gsbrcd = login["gsbrcd"] ?: return null
        s.profiles.firstOrNull { it.userId == userId && it.gsbrcd == gsbrcd }?.let { return it }
        val pid = (s.profiles.maxOfOrNull { it.profileId } ?: 0L) + 1 // nunca 0 (trava o jogo)
        val nick = WfcCrypto.base32(userId.toLong()) + gsbrcd
        val p = Profile(
            pid, userId, gsbrcd, nick, "$nick@nds", gsbrcd.take(4),
            devname = login["devname"] ?: "", ingamesn = login["ingamesn"] ?: "",
        )
        s.profiles.add(p)
        save()
        return p
    }

    @Synchronized fun profile(id: Long): Profile? = s.profiles.firstOrNull { it.profileId == id }
    @Synchronized fun profiles(): List<Profile> = s.profiles.toList()

    @Synchronized fun updateProfile(id: Long, key: String, value: String) {
        profile(id)?.extra?.put(key, value); save()
    }

    @Synchronized fun buddies(user: Long): List<Buddy> = s.buddies.filter { it.user == user && !it.blocked }.map { it.copy() }
    @Synchronized fun blocked(user: Long): List<Buddy> = s.buddies.filter { it.user == user && it.blocked }.map { it.copy() }

    @Synchronized fun addBuddy(user: Long, buddy: Long) {
        if (s.buddies.none { it.user == user && it.buddy == buddy }) {
            s.buddies.add(Buddy(user, buddy, System.currentTimeMillis() / 1000)); save()
        }
    }

    @Synchronized fun authBuddy(user: Long, buddy: Long) {
        s.buddies.filter { it.user == user && it.buddy == buddy }.forEach { it.status = 1 }; save()
    }

    @Synchronized fun deleteBuddy(user: Long, buddy: Long) {
        s.buddies.removeAll { it.user == user && it.buddy == buddy }; save()
    }

    /** Pedidos de amizade feitos A MIM e ainda nao aceitos. */
    @Synchronized fun pendingRequestsTo(me: Long): List<Buddy> = s.buddies.filter { it.buddy == me && it.status == 0 }.map { it.copy() }

    @Synchronized fun needAuthMessage(me: Long): List<Buddy> = s.buddies.filter { it.buddy == me && it.status == 1 && !it.notified }.map { it.copy() }

    @Synchronized fun markAuthNotified(user: Long, buddy: Long) {
        s.buddies.filter { it.user == user && it.buddy == buddy }.forEach { it.notified = true }; save()
    }

    @Synchronized fun savePending(from: Long, to: Long, msg: String) { s.pending.add(Pending(from, to, msg)); save() }

    @Synchronized fun takePending(to: Long): List<Pending> {
        val l = s.pending.filter { it.to == to }
        if (l.isNotEmpty()) { s.pending.removeAll { it.to == to }; save() }
        return l
    }
}
