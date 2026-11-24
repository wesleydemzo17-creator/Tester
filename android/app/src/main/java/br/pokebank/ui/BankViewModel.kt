package br.pokebank.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import br.pokebank.core.BankSession
import br.pokebank.core.BankStore
import br.pokebank.core.Gen4Save
import br.pokebank.core.Pk4
import br.pokebank.core.SaveContainer
import br.pokebank.core.SaveFormatException
import br.pokebank.net.DsServer
import br.pokebank.net.Protocol
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** De onde veio o save que esta aberto. */
sealed class SaveOrigin {
    /** Um Nintendo DS conectado pela rede (cartucho ou .sav do R4/DSi). */
    data class Ds(val info: String) : SaveOrigin()
    /** Um arquivo no celular (emulador: melonDS, DraStic...). */
    data class LocalFile(val uri: Uri, val name: String, val container: SaveContainer) : SaveOrigin()
}

/** Estado da tela + ponte entre o servidor (threads de rede) e a interface (thread principal). */
class BankViewModel(app: Application) : AndroidViewModel(app), DsServer.Listener {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val work = Executors.newSingleThreadExecutor()
    private val root = app.getExternalFilesDir(null) ?: app.filesDir
    val bank = BankStore(java.io.File(root, "bank"))
    val sprites = SpriteRepository(app)
    private val server = DsServer(Build.MODEL ?: "Android", this)
    private var multicastLock: WifiManager.MulticastLock? = null

    // ---- estado observado pela interface
    var serverRunning by mutableStateOf(false); private set
    var dsInfo by mutableStateOf<String?>(null); private set
    var session by mutableStateOf<BankSession?>(null); private set
    var origin by mutableStateOf<SaveOrigin?>(null); private set
    var currentBox by mutableIntStateOf(0)
    var version by mutableIntStateOf(0); private set        // incrementa a cada mudanca (forca recomposicao)
    var busy by mutableStateOf<String?>(null); private set  // "Gravando no DS..." etc.
    var pendingRecovery by mutableIntStateOf(bank.pendingCount()); private set
    var spriteProgress by mutableStateOf<String?>(null); private set
    var toast by mutableStateOf<String?>(null)
    val log = mutableStateListOf<String>()

    val storagePath: String get() = root.absolutePath
    val save: Gen4Save? get() = session?.save

    init { startServer() }

    fun startServer() {
        if (serverRunning) return
        try {
            val wifi = getApplication<Application>().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("pokebank").apply { setReferenceCounted(false); acquire() }
            server.start()
            serverRunning = true
        } catch (e: Exception) {
            addLog("Nao consegui iniciar o servidor: ${e.message}")
        }
    }

    override fun onCleared() {
        server.stop()
        multicastLock?.release()
        io.shutdownNow()
        work.shutdownNow()
    }

    fun localIps(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress ?: "" }
            .filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    private fun addLog(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val run = { log.add(0, "$t  $msg"); if (log.size > 300) log.removeAt(log.size - 1) }
        if (Looper.myLooper() == Looper.getMainLooper()) run() else main.post(run)
    }

    private fun changed() { version++ }

    // ---- sprites

    fun sprite(species: Int, shiny: Boolean = false, female: Boolean = false): Bitmap? = sprites.get(species, shiny, female)

    fun extractSprites(uri: Uri) {
        if (spriteProgress != null) return
        spriteProgress = "Abrindo a ROM..."
        work.execute {
            try {
                val label = sprites.extract(uri) { n, total -> main.post { spriteProgress = "Extraindo sprites... $n/$total" } }
                main.post { spriteProgress = null; toast = "Sprites extraidos de $label"; addLog("Sprites extraidos de $label"); changed() }
            } catch (e: Exception) {
                main.post { spriteProgress = null; toast = "Falhou: ${e.message}"; addLog("Erro extraindo sprites: ${e.message}") }
            }
        }
    }

    // ---- callbacks do servidor (threads de rede)

    override fun onLog(msg: String) = addLog(msg)

    override fun onConnected(hello: Protocol.Hello, address: String) {
        main.post {
            val src = if (hello.gameCode == "FILE") "arquivo ${hello.title}" else "${hello.title} (${hello.gameCode})"
            dsInfo = "$src - $address" + if (hello.infrared) " - cartao IR" else ""
            addLog("DS conectado: $dsInfo")
        }
    }

    override fun onSaveReceived(save: ByteArray) {
        // Backup ANTES de qualquer coisa.
        val label = runCatching { Gen4Save(save).let { "${it.game.name}_${it.trainerName}" } }.getOrDefault("desconhecido")
        val file = runCatching { bank.backup(save, label) }.getOrNull()
        main.post {
            try {
                val s = BankSession(save, bank)
                session = s
                origin = SaveOrigin.Ds(dsInfo ?: "DS")
                currentBox = 0
                addLog("Save recebido: ${s.save.game.label}, treinador ${s.save.trainerName} (ID ${s.save.trainerId})")
                if (file != null) addLog("Backup: ${file.name}")
                if (!s.save.storageChecksumValid) addLog("ATENCAO: checksum das caixas invalido no save recebido")
                io.execute { server.sendMessage("Save recebido! Treinador ${s.save.trainerName}.") }
            } catch (e: SaveFormatException) {
                addLog("Save nao reconhecido: ${e.message}")
                io.execute { server.sendMessage("Save nao reconhecido (so DPPt/HGSS).") }
            }
            changed()
        }
    }

    private var inFlight: ByteArray? = null

    override fun onWriteResult(status: Int, crcReadBack: Long, sectors: Int) {
        main.post {
            val s = session
            val bytes = inFlight
            inFlight = null
            busy = null
            when (status) {
                Protocol.WR_OK -> {
                    if (s != null && bytes != null) s.commit(bytes)
                    addLog("Gravado e conferido no DS ($sectors setores).")
                    toast = "Pronto! Save atualizado no DS."
                }
                Protocol.WR_CANCELLED -> { s?.abortWrite(); addLog("Gravacao cancelada no DS. Nada mudou."); toast = "Cancelado no DS" }
                Protocol.WR_FAILED_RESTORED -> { s?.abortWrite(); addLog("Falha ao gravar, mas o DS manteve o save original."); toast = "Falhou; nada mudou" }
                else -> {
                    addLog("FALHA GRAVE: estado do save incerto. Use o backup em $storagePath/bank/backups")
                    toast = "Falha grave! Veja o registro."
                    pendingRecovery = bank.pendingCount()
                }
            }
            changed()
        }
    }

    override fun onDisconnected() {
        main.post {
            addLog("DS desconectado")
            dsInfo = null
            if (busy != null && origin is SaveOrigin.Ds) {
                busy = null
                inFlight = null
                pendingRecovery = bank.pendingCount()
                addLog("A conexao caiu durante a gravacao. Releia o save no DS para saber o estado.")
            }
        }
    }

    // ---- saves de emulador (arquivo no celular)

    fun openLocalSave(uri: Uri, name: String) {
        work.execute {
            try {
                val bytes = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val c = SaveContainer(bytes)
                val s = BankSession(c.raw, bank)
                runCatching { bank.backup(c.raw, "${s.save.game.name}_${s.save.trainerName}_emulador") }
                main.post {
                    session = s
                    origin = SaveOrigin.LocalFile(uri, name, c)
                    currentBox = 0
                    addLog("Arquivo aberto: $name (${c.kind}) - ${s.save.game.label}, ${s.save.trainerName}")
                    changed()
                }
            } catch (e: Exception) {
                main.post { toast = "Nao abri o arquivo: ${e.message}"; addLog("Erro abrindo $name: ${e.message}") }
            }
        }
    }

    private fun writeLocal(o: SaveOrigin.LocalFile, s: BankSession) {
        val (bytes, _) = s.buildWrite()
        busy = "Gravando o arquivo..."
        work.execute {
            try {
                val out = o.container.rebuild(bytes)
                getApplication<Application>().contentResolver.openOutputStream(o.uri, "wt")!!.use { it.write(out) }
                // confere lendo de volta
                val back = getApplication<Application>().contentResolver.openInputStream(o.uri)!!.use { it.readBytes() }
                if (!back.contentEquals(out)) throw java.io.IOException("conferencia falhou")
                main.post { s.commit(bytes); busy = null; toast = "Arquivo salvo!"; addLog("Gravado em ${o.name}"); changed() }
            } catch (e: Exception) {
                main.post {
                    busy = null
                    pendingRecovery = bank.pendingCount()
                    toast = "Falhou: ${e.message}"
                    addLog("Erro gravando ${o.name}: ${e.message}. Backup em $storagePath/bank/backups")
                }
            }
        }
    }

    // ---- acoes da interface

    fun slot(box: Int, slot: Int): Pk4? = session?.save?.getBoxSlot(box, slot)

    fun isPendingSlot(box: Int, slot: Int): Boolean = session?.pending?.any {
        when (it) {
            is BankSession.Op.Deposit -> it.box == box && it.slot == slot
            is BankSession.Op.Withdraw -> it.box == box && it.slot == slot
        }
    } == true

    fun bankEntries(): List<BankStore.Entry> = session?.bankView() ?: bank.list()

    fun deposit(box: Int, slot: Int) = act { session!!.deposit(box, slot).also { addLog("Depositar: ${it.displayName}") } }

    fun withdraw(uid: String) = act {
        val s = session ?: throw IllegalStateException("Abra um save primeiro (DS ou arquivo)")
        val target = (0 until Gen4Save.SLOTS).firstOrNull { s.save.getBoxSlot(currentBox, it) == null }?.let { currentBox to it }
        val (b, sl) = s.withdraw(uid, target)
        addLog("Retirar para Caixa ${b + 1}, posicao ${sl + 1}")
    }

    fun discard() = act { session?.discardChanges() }

    /** Grava as mudancas: no DS (pela rede) ou direto no arquivo do emulador. */
    fun writeChanges() {
        val s = session ?: return
        when (val o = origin) {
            is SaveOrigin.LocalFile -> try { writeLocal(o, s) } catch (e: Exception) { toast = e.message }
            is SaveOrigin.Ds, null -> {
                if (!server.isClientConnected) { toast = "O DS nao esta conectado"; return }
                try {
                    val (bytes, summary) = s.buildWrite()
                    inFlight = bytes
                    busy = "Confirme no DS (A, depois R+A)..."
                    io.execute { if (!server.sendWrite(bytes, summary)) main.post { busy = null; inFlight = null; s.abortWrite() } }
                } catch (e: Exception) {
                    toast = e.message
                }
            }
        }
    }

    fun closeSave() { session = null; origin = null; changed() }

    fun requestSave() { io.execute { server.requestSave() } }

    fun recoverPending() { val n = bank.recoverPending(); pendingRecovery = 0; addLog("Recuperados $n Pokemon para o banco"); changed() }
    fun dropPending() { bank.clearPending(); pendingRecovery = 0; changed() }

    fun notify(msg: String) { toast = msg }

    private fun act(block: () -> Unit) {
        try { block() } catch (e: Exception) { toast = e.message ?: e.toString() }
        changed()
    }
}
