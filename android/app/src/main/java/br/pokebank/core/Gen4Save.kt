package br.pokebank.core

/**
 * Layout dos saves da 4a geracao (offsets conferidos com o PKHeX: SAV4, SAV4Sinnoh, SAV4HGSS).
 *
 * O save tem 512 KB = duas particoes de 0x40000. Cada particao tem um bloco "General"
 * (treinador, party, mochila...) e um bloco "Storage" (as 18 caixas do PC). O jogo alterna
 * entre as particoes; o bloco valido e o que tem o contador de gravacao mais alto no rodape.
 */
enum class Gen4Game(
    val label: String,
    val generalSize: Int,
    val storageSize: Int,
    val storageStart: Int,
    val footerSize: Int,
    val trainerOffset: Int,
) {
    DP("Diamond/Pearl", 0xC100, 0x121E0, 0xC100, 0x14, 0x64),
    PT("Platinum", 0xCF2C, 0x121E4, 0xCF2C, 0x14, 0x68),
    HGSS("HeartGold/SoulSilver", 0xF628, 0x12310, 0xF700, 0x10, 0x64);

    fun boxOffset(box: Int): Int = if (this == HGSS) box * 0x1000 else 4 + box * 0xFF0
    fun boxNameOffset(box: Int): Int = if (this == HGSS) 0x12008 + box * 40 else 4 + 18 * 0xFF0 + box * 40
}

class SaveFormatException(msg: String) : Exception(msg)

class Gen4Save(raw: ByteArray) {
    val data: ByteArray = raw.copyOf()
    val game: Gen4Game
    private val generalOffset: Int
    private val storageOffset: Int

    init {
        if (data.size != SAVE_SIZE) throw SaveFormatException("Tamanho inesperado: ${data.size} bytes (esperado 512 KB)")
        game = detect(data) ?: throw SaveFormatException("Nao parece um save de Pokemon DPPt/HGSS")
        generalOffset = if (activePartition(0, game.generalSize) == 0) 0 else PARTITION
        storageOffset = (if (activePartition(game.storageStart, game.storageSize) == 0) 0 else PARTITION) + game.storageStart
    }

    val trainerName: String get() = Gen4Text.decode(data, generalOffset + game.trainerOffset, 16)
    val trainerId: Int get() = data.u16(generalOffset + game.trainerOffset + 0x10)
    val secretId: Int get() = data.u16(generalOffset + game.trainerOffset + 0x12)

    val generalChecksumValid: Boolean get() = blockValid(generalOffset, game.generalSize, game.footerSize)
    val storageChecksumValid: Boolean get() = blockValid(storageOffset, game.storageSize, game.footerSize)

    fun boxName(box: Int): String {
        checkBox(box)
        return Gen4Text.decode(data, storageOffset + game.boxNameOffset(box), 40).ifBlank { "Caixa ${box + 1}" }
    }

    private fun slotOffset(box: Int, slot: Int): Int {
        checkBox(box); require(slot in 0 until SLOTS) { "slot $slot" }
        return storageOffset + game.boxOffset(box) + slot * Pk4.SIZE
    }

    /** Retorna o Pokemon do slot, ou null se o slot estiver vazio. */
    fun getBoxSlot(box: Int, slot: Int): Pk4? {
        val pk = Pk4.decrypt(data, slotOffset(box, slot))
        return if (pk.isEmpty) null else pk
    }

    /** Grava (ou esvazia, com null) um slot. Chame [finish] antes de exportar. */
    fun setBoxSlot(box: Int, slot: Int, pk: Pk4?) {
        val enc = pk?.preparedFor(game)?.encrypt() ?: Pk4.emptyEncrypted()
        System.arraycopy(enc, 0, data, slotOffset(box, slot), Pk4.SIZE)
        dirty = true
    }

    fun firstEmptySlot(): Pair<Int, Int>? {
        for (b in 0 until BOXES) for (s in 0 until SLOTS) if (getBoxSlot(b, s) == null) return b to s
        return null
    }

    private var dirty = false

    /** Recalcula o CRC do bloco Storage (o unico que alteramos) e devolve o save pronto. */
    fun finish(): ByteArray {
        if (dirty && game == Gen4Game.HGSS) {
            // Bits "caixa alterada": forca o jogo a regravar todas as caixas no proximo save.
            data.put32(storageOffset + 18 * 0x1000 + 4, 0x3FFFFL)
        }
        val end = storageOffset + game.storageSize
        data.put16(end - 2, Checksums.crc16Ccitt(data, storageOffset, end - game.footerSize))
        dirty = false
        return data.copyOf()
    }

    private fun blockValid(offset: Int, size: Int, footer: Int): Boolean =
        Checksums.crc16Ccitt(data, offset, offset + size - footer) == data.u16(offset + size - 2)

    private fun activePartition(begin: Int, length: Int): Int {
        val o1 = begin + length - 0x14
        val o2 = o1 + PARTITION
        val r = compareCounters(data.u32(o1), data.u32(o2))
        if (r != SAME) return r
        return if (compareCounters(data.u32(o1 + 4), data.u32(o2 + 4)) == SECOND) SECOND else FIRST
    }

    private fun checkBox(box: Int) = require(box in 0 until BOXES) { "caixa $box" }

    companion object {
        const val SAVE_SIZE = 0x80000
        const val PARTITION = 0x40000
        const val BOXES = 18
        const val SLOTS = 30
        private const val FIRST = 0
        private const val SECOND = 1
        private const val SAME = 2
        private const val MAGIC_INTL = 0x20060623L
        private const val MAGIC_KOR = 0x20070903L

        private fun compareCounters(c1: Long, c2: Long): Int {
            val max = 0xFFFFFFFFL
            if (c1 == max && c2 != max - 1) return SECOND
            if (c2 == max && c1 != max - 1) return FIRST
            return when { c1 > c2 -> FIRST; c1 < c2 -> SECOND; else -> SAME }
        }

        /** Mesmo criterio do PKHeX: rodape do bloco General da 2a particao com tamanho e "magic" corretos. */
        fun detect(data: ByteArray): Gen4Game? {
            if (data.size != SAVE_SIZE) return null
            for (g in Gen4Game.values()) {
                for (base in intArrayOf(PARTITION, 0)) {
                    val end = base + g.generalSize
                    val size = data.u32(end - 0xC)
                    val magic = data.u32(end - 0x8)
                    if (size == g.generalSize.toLong() && (magic == MAGIC_INTL || magic == MAGIC_KOR)) return g
                }
            }
            return null
        }
    }
}
