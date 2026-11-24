// Teste ponta a ponta: servidor Kotlin real (o mesmo do app Android) <-> simulador do DS (pokebank_host).
// Uso: kotlinc ... && java ... E2EKt <caminho do pokebank_host> <cenario: ok|cancel|fail>
import br.pokebank.TestSaves
import br.pokebank.core.BankSession
import br.pokebank.core.BankStore
import br.pokebank.core.Gen4Game
import br.pokebank.core.Gen4Save
import br.pokebank.core.Pk4
import br.pokebank.net.DsServer
import br.pokebank.net.Protocol
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

fun main(args: Array<String>) {
    val hostBin = args[0]
    val scenario = args.getOrElse(1) { "ok" }
    val work = Files.createTempDirectory("e2e").toFile()
    val leafeon = Pk4(TestSaves.res("leafeon.pk4"))
    val g = Gen4Game.PT
    val original = TestSaves.build(g) { b, s -> System.arraycopy(leafeon.encrypt(), 0, b, s + g.boxOffset(0), 136) }
    val sav = File(work, "cart.sav").apply { writeBytes(original) }
    val bank = BankStore(File(work, "bank"))
    var session: BankSession? = null
    var pendingWrite: ByteArray? = null
    var result = -1
    val done = CountDownLatch(1)
    lateinit var server: DsServer

    server = DsServer("PC-Teste", object : DsServer.Listener {
        override fun onLog(msg: String) = println("[Android] $msg")
        override fun onConnected(hello: Protocol.Hello, address: String) =
            println("[Android] DS conectado: ${hello.title} ${hello.gameCode} ${hello.saveSize} bytes de $address")
        override fun onSaveReceived(save: ByteArray) {
            val s = BankSession(save, bank).also { session = it }
            println("[Android] Save recebido: ${s.save.game.label}, slot 1 = ${s.save.getBoxSlot(0, 0)}")
            s.deposit(0, 0)
            val (bytes, summary) = s.buildWrite()
            pendingWrite = bytes
            server.sendMessage("Ola do teste! Depositando 1 Pokemon.")
            server.sendWrite(bytes, summary)
        }
        override fun onWriteResult(status: Int, crcReadBack: Long, sectors: Int) {
            println("[Android] Resultado: status=$status setores=$sectors crc=%08X".format(crcReadBack))
            result = status
            if (status == Protocol.WR_OK) session!!.commit(pendingWrite!!) else if (status != Protocol.WR_FAILED_UNKNOWN) session!!.abortWrite()
            done.countDown()
        }
        override fun onDisconnected() = println("[Android] DS desconectou")
    }, tcpPort = 47101, udpPort = 47100)
    server.start()

    val keys = when (scenario) { "cancel" -> "A,B"; else -> "A,RA" }
    val pb = ProcessBuilder(hostBin, sav.absolutePath).inheritIO()
    pb.environment().putAll(mapOf("PB_SERVER" to "127.0.0.1", "PB_KEYS" to keys, "PB_EXIT_AFTER" to "1"))
    if (scenario == "fail") pb.environment()["PB_FAIL_SECTOR"] = "0x40000"
    val proc = pb.start()
    val ok = done.await(60, TimeUnit.SECONDS)
    proc.waitFor(20, TimeUnit.SECONDS)
    server.stop()

    val cart = Gen4Save(sav.readBytes())
    val slot = cart.getBoxSlot(0, 0)
    val inBank = bank.list().map { it.pk }
    println("---- verificacao ($scenario) ----")
    println("resposta recebida: $ok, status: $result, codigo de saida do DS: ${proc.exitValue()}")
    println("cartucho: slot1=${slot?.displayName ?: "vazio"}, checksums ok=${cart.generalChecksumValid && cart.storageChecksumValid}")
    println("banco: ${inBank.map { it.displayName }}  pendentes=${bank.pendingCount()}")
    val pass = when (scenario) {
        "ok" -> result == Protocol.WR_OK && slot == null && inBank == listOf(leafeon) && cart.storageChecksumValid && bank.pendingCount() == 0
        "cancel" -> result == Protocol.WR_CANCELLED && slot == leafeon && inBank.isEmpty() && sav.readBytes().contentEquals(original)
        "fail" -> result == Protocol.WR_FAILED_RESTORED && slot == leafeon && inBank.isEmpty() && sav.readBytes().contentEquals(original)
        else -> false
    }
    println(if (pass) "PASSOU: $scenario" else "FALHOU: $scenario")
    work.deleteRecursively()
    if (!pass) System.exit(1)
}
