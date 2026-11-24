package br.pokebank.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * O banco no celular: cada Pokemon e um arquivo .pk4 (136 bytes, descriptografado),
 * compativel com o PKHeX. Backups dos saves recebidos ficam em backups/.
 */
class BankStore(root: File) {
    val pokemonDir = File(root, "pokemon").apply { mkdirs() }
    val backupDir = File(root, "backups").apply { mkdirs() }
    /** Diario: depositos enviados ao DS mas ainda nao confirmados. Protege contra o app fechar no meio. */
    val pendingDir = File(root, "pending").apply { mkdirs() }

    fun writePending(pks: List<Pk4>) {
        clearPending()
        pks.forEachIndexed { i, pk -> File(pendingDir, "${i}_${pk.uid}.pk4").writeBytes(pk.data.copyOf(Pk4.SIZE)) }
    }

    fun clearPending() { pendingDir.listFiles()?.forEach { it.delete() } }

    fun pendingCount(): Int = pendingDir.listFiles { f -> f.name.endsWith(".pk4") }?.size ?: 0

    /** Move para o banco os depositos de uma gravacao interrompida (use se o DS chegou a gravar). */
    fun recoverPending(): Int {
        var n = 0
        pendingDir.listFiles { f -> f.name.endsWith(".pk4") }?.forEach { f ->
            runCatching { Pk4(f.readBytes().copyOf(Pk4.SIZE)) }.getOrNull()?.takeIf { it.isValid }?.let { add(it); n++ }
            f.delete()
        }
        return n
    }

    data class Entry(val uid: String, val pk: Pk4, val file: File)

    fun list(): List<Entry> =
        (pokemonDir.listFiles { f -> f.name.endsWith(".pk4") } ?: emptyArray())
            .sortedBy { it.lastModified() }
            .mapNotNull { f ->
                runCatching { Pk4(f.readBytes().copyOf(Pk4.SIZE)) }.getOrNull()
                    ?.takeIf { it.isValid }
                    ?.let { Entry(f.nameWithoutExtension, it, f) }
            }

    fun contains(uid: String) = File(pokemonDir, "$uid.pk4").exists()

    fun add(pk: Pk4): String {
        var uid = pk.uid
        var n = 1
        while (contains(uid)) uid = "${pk.uid}_${n++}"
        val tmp = File(pokemonDir, "$uid.tmp")
        tmp.writeBytes(pk.data.copyOf(Pk4.SIZE))
        if (!tmp.renameTo(File(pokemonDir, "$uid.pk4"))) throw IllegalStateException("falha ao salvar $uid")
        return uid
    }

    fun get(uid: String): Pk4? = File(pokemonDir, "$uid.pk4").takeIf { it.exists() }?.let { Pk4(it.readBytes().copyOf(Pk4.SIZE)) }

    fun remove(uid: String): Boolean = File(pokemonDir, "$uid.pk4").delete()

    fun backup(save: ByteArray, label: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safe = label.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(backupDir, "${stamp}_$safe.sav").apply { writeBytes(save) }
    }
}

/**
 * Uma sessao de edicao: o save recebido do DS + as movimentacoes pendentes.
 * Nada muda no banco ate o DS confirmar que gravou e verificou o cartucho ([commit]).
 */
class BankSession(original: ByteArray, private val bank: BankStore) {
    var originalBytes: ByteArray = original.copyOf()
        private set
    var save = Gen4Save(original)
        private set

    sealed class Op {
        data class Deposit(val box: Int, val slot: Int, val pk: Pk4) : Op()
        data class Withdraw(val uid: String, val box: Int, val slot: Int, val pk: Pk4) : Op()
    }

    private val ops = mutableListOf<Op>()
    val pending: List<Op> get() = ops.toList()
    val hasChanges: Boolean get() = ops.isNotEmpty()

    /** Pokemon do banco que ainda estao disponiveis (descontando retiradas pendentes). */
    fun bankView(): List<BankStore.Entry> {
        val withdrawn = ops.filterIsInstance<Op.Withdraw>().map { it.uid }.toSet()
        return bank.list().filter { it.uid !in withdrawn }
    }

    fun deposit(box: Int, slot: Int): Pk4 {
        val pk = save.getBoxSlot(box, slot) ?: throw IllegalArgumentException("Slot vazio")
        if (!pk.isValid) throw IllegalArgumentException("Pokemon invalido/corrompido; nao vou mexer nele")
        // Se este slot recebeu uma retirada pendente, desfazer e o mesmo que cancelar a retirada.
        val w = ops.filterIsInstance<Op.Withdraw>().firstOrNull { it.box == box && it.slot == slot }
        if (w != null) ops.remove(w) else ops.add(Op.Deposit(box, slot, pk))
        save.setBoxSlot(box, slot, null)
        return pk
    }

    fun withdraw(uid: String, target: Pair<Int, Int>? = null): Pair<Int, Int> {
        if (ops.any { it is Op.Withdraw && it.uid == uid }) throw IllegalArgumentException("Ja esta sendo retirado")
        val pk = bank.get(uid) ?: throw IllegalArgumentException("Nao esta no banco")
        pk.incompatibilityWith(save.game)?.let { throw IllegalArgumentException("${pk.speciesName} $it") }
        val (box, slot) = target ?: save.firstEmptySlot() ?: throw IllegalStateException("Todas as caixas estao cheias")
        if (save.getBoxSlot(box, slot) != null) throw IllegalStateException("Slot ocupado")
        save.setBoxSlot(box, slot, pk)
        ops.add(Op.Withdraw(uid, box, slot, pk))
        return box to slot
    }

    fun discardChanges() {
        ops.clear()
        save = Gen4Save(originalBytes)
    }

    /** Save final para mandar ao DS + resumo em ASCII (a fonte do DS nao tem acentos). */
    fun buildWrite(): Pair<ByteArray, String> {
        val bytes = save.finish()
        val check = Gen4Save(bytes)
        require(check.storageChecksumValid) { "checksum do Storage invalido apos edicao" }
        val dep = ops.filterIsInstance<Op.Deposit>()
        bank.writePending(dep.map { it.pk })
        val wit = ops.filterIsInstance<Op.Withdraw>()
        val sb = StringBuilder()
        sb.append("Depositar no banco: ${dep.size}\n")
        dep.take(6).forEach { sb.append(" - ${ascii(it.pk.displayName)} Nv${it.pk.level}\n") }
        sb.append("Retirar do banco: ${wit.size}\n")
        wit.take(6).forEach { sb.append(" + ${ascii(it.pk.displayName)} -> Cx${it.box + 1}\n") }
        return bytes to sb.toString()
    }

    /** Chamado apenas depois do DS confirmar gravacao verificada. */
    fun commit(writtenBytes: ByteArray) {
        for (op in ops) when (op) {
            is Op.Deposit -> bank.add(op.pk)
            is Op.Withdraw -> bank.remove(op.uid)
        }
        bank.clearPending()
        ops.clear()
        originalBytes = writtenBytes.copyOf()
        save = Gen4Save(writtenBytes)
    }

    /** O DS recusou ou falhou: o cartucho nao mudou, so limpamos o diario. */
    fun abortWrite() = bank.clearPending()

    companion object {
        fun ascii(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .replace('♂', 'M').replace('♀', 'F')
            .map { if (it.code in 32..126 || it == '\n') it else '?' }.joinToString("")
    }
}
