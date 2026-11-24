package br.pokebank.core

/**
 * Um Pokemon da 4a geracao (Diamond/Pearl/Platinum/HeartGold/SoulSilver) no formato
 * "stored" de 136 bytes, ja DESCRIPTOGRAFADO. E o mesmo formato dos arquivos .pk4 do PKHeX.
 *
 * Estrutura: 8 bytes de cabecalho (PID, flags, checksum) + 4 blocos de 32 bytes (A, B, C, D)
 * que no save ficam embaralhados (pela PID) e criptografados (LCRNG semeado pelo checksum).
 */
class Pk4(data: ByteArray) {
    val data: ByteArray = data.copyOf()

    init {
        require(data.size == SIZE || data.size == PARTY_SIZE) { "PK4 precisa ter $SIZE bytes (recebido ${data.size})" }
    }

    /** Somente os 136 bytes de armazenamento (descarta estatisticas de party). */
    private val stored: ByteArray get() = data.copyOf(SIZE)

    val pid: Long get() = data.u32(0x00)
    val checksum: Int get() = data.u16(0x06)
    val species: Int get() = data.u16(0x08)
    val heldItem: Int get() = data.u16(0x0A)
    val tid: Int get() = data.u16(0x0C)
    val sid: Int get() = data.u16(0x0E)
    val exp: Long get() = data.u32(0x10)
    val language: Int get() = data.u8(0x17)
    val iv32: Long get() = data.u32(0x38)
    val isEgg: Boolean get() = (iv32 ushr 30) and 1L == 1L
    val isNicknamed: Boolean get() = (iv32 ushr 31) and 1L == 1L
    val version: Int get() = data.u8(0x5F)
    /** Forma alternativa (Giratina Origem, Shaymin Ceu, Rotom...). */
    val form: Int get() = data.u8(0x40) ushr 3
    val nickname: String get() = Gen4Text.decode(data, 0x48, 22)
    val otName: String get() = Gen4Text.decode(data, 0x68, 16)

    val isEmpty: Boolean get() = species == 0

    val speciesName: String get() = SpeciesInfo.name(species)
    val level: Int get() = SpeciesInfo.levelFor(species, exp)

    val isShiny: Boolean
        get() {
            val p = pid
            val x = tid xor sid xor (p and 0xFFFF).toInt() xor ((p ushr 16) and 0xFFFF).toInt()
            return x < 8
        }

    /** Texto curto para listas. */
    val displayName: String
        get() = when {
            isEmpty -> "(vazio)"
            isEgg -> "Ovo"
            isNicknamed && nickname.isNotBlank() -> "$nickname ($speciesName)"
            else -> speciesName
        }

    fun calcChecksum(): Int {
        var sum = 0
        var i = 8
        while (i < SIZE) { sum += data.u16(i); i += 2 }
        return sum and 0xFFFF
    }

    val isChecksumValid: Boolean get() = calcChecksum() == checksum

    fun refreshChecksum() = data.put16(0x06, calcChecksum())

    /** Faz um sanity check: especie valida e checksum correto. */
    val isValid: Boolean get() = species in 1..SpeciesInfo.MAX_SPECIES && isChecksumValid

    /**
     * Motivo pelo qual este Pokemon nao pode ir para [game], ou null se pode.
     * Itens e formas que so existem em Platinum/HGSS travariam ou corromperiam Diamond/Pearl.
     */
    fun incompatibilityWith(game: Gen4Game): String? {
        val maxItem = when (game) { Gen4Game.DP -> 464; Gen4Game.PT -> 467; Gen4Game.HGSS -> 536 }
        if (heldItem > maxItem) return "segura um item que nao existe em ${game.label}"
        if (game == Gen4Game.DP && form != 0 && species in setOf(479, 487, 492))
            return "esta numa forma que nao existe em Diamond/Pearl"
        return null
    }

    /** Prepara para gravar num jogo especifico (HGSS zera o "humor de passeio", como o PKHeX). */
    fun preparedFor(game: Gen4Game): Pk4 {
        val copy = Pk4(stored)
        if (game == Gen4Game.HGSS) copy.data[0x87] = 0
        copy.refreshChecksum()
        return copy
    }

    /** Identidade estavel para evitar duplicatas no banco. */
    val uid: String get() = "%08X-%04X%04X-%04X".format(pid, tid, sid, checksum)

    /** Retorna 136 bytes embaralhados + criptografados, prontos para o save. */
    fun encrypt(): ByteArray {
        val out = stored
        val sv = ((pid ushr 13) and 31).toInt()
        shuffle(out, BLOCK_POSITION_INVERT[sv])
        crypt(out, checksum.toLong())
        return out
    }

    override fun equals(other: Any?) = other is Pk4 && other.stored.contentEquals(stored)
    override fun hashCode() = stored.contentHashCode()
    override fun toString() = "Pk4($displayName, Nv.$level, $uid)"

    companion object {
        const val SIZE = 136
        const val PARTY_SIZE = 236
        private const val BLOCK = 32

        /** Ordem dos blocos para cada valor de embaralhamento (copiado do PKHeX). */
        private val BLOCK_POSITION = intArrayOf(
            0, 1, 2, 3, 0, 1, 3, 2, 0, 2, 1, 3, 0, 3, 1, 2,
            0, 2, 3, 1, 0, 3, 2, 1, 1, 0, 2, 3, 1, 0, 3, 2,
            2, 0, 1, 3, 3, 0, 1, 2, 2, 0, 3, 1, 3, 0, 2, 1,
            1, 2, 0, 3, 1, 3, 0, 2, 2, 1, 0, 3, 3, 1, 0, 2,
            2, 3, 0, 1, 3, 2, 0, 1, 1, 2, 3, 0, 1, 3, 2, 0,
            2, 1, 3, 0, 3, 1, 2, 0, 2, 3, 1, 0, 3, 2, 1, 0,
            0, 1, 2, 3, 0, 1, 3, 2, 0, 2, 1, 3, 0, 3, 1, 2,
            0, 2, 3, 1, 0, 3, 2, 1, 1, 0, 2, 3, 1, 0, 3, 2,
        )
        private val BLOCK_POSITION_INVERT = intArrayOf(
            0, 1, 2, 4, 3, 5, 6, 7, 12, 18, 13, 19, 8, 10, 14, 20,
            16, 22, 9, 11, 15, 21, 17, 23, 0, 1, 2, 4, 3, 5, 6, 7,
        )

        /** XOR com o fluxo do LCRNG da Nintendo, em palavras de 16 bits, a partir do byte 8. */
        private fun crypt(buf: ByteArray, seed0: Long) {
            var seed = seed0 and 0xFFFFFFFFL
            var i = 8
            while (i < SIZE) {
                seed = (0x41C64E6DL * seed + 0x6073L) and 0xFFFFFFFFL
                val x = (seed ushr 16).toInt() and 0xFFFF
                buf.put16(i, buf.u16(i) xor x)
                i += 2
            }
        }

        /** Depois do embaralhamento, o bloco na posicao i e o bloco original BLOCK_POSITION[sv*4+i]. */
        private fun shuffle(buf: ByteArray, sv: Int) {
            if (sv == 0) return
            val src = buf.copyOfRange(8, SIZE)
            for (i in 0 until 4) {
                val from = BLOCK_POSITION[sv * 4 + i]
                System.arraycopy(src, from * BLOCK, buf, 8 + i * BLOCK, BLOCK)
            }
        }

        /** Descriptografa 136 bytes vindos do save. */
        fun decrypt(encrypted: ByteArray, offset: Int = 0): Pk4 {
            val buf = encrypted.copyOfRange(offset, offset + SIZE)
            val pid = buf.u32(0)
            crypt(buf, buf.u16(6).toLong())
            shuffle(buf, ((pid ushr 13) and 31).toInt())
            return Pk4(buf)
        }

        /** Descriptografa um Pokemon da party (236 bytes): 136 de dados + 100 de status em batalha. */
        fun decryptParty(encrypted: ByteArray, offset: Int): Pair<Pk4, PartyStats> {
            val pk = decrypt(encrypted, offset)
            val ext = encrypted.copyOfRange(offset, offset + PARTY_SIZE)
            // os 100 bytes extras sao criptografados com a PID como semente
            var seed = pk.pid
            var i = SIZE
            while (i < PARTY_SIZE) {
                seed = (0x41C64E6DL * seed + 0x6073L) and 0xFFFFFFFFL
                ext.put16(i, ext.u16(i) xor ((seed ushr 16).toInt() and 0xFFFF))
                i += 2
            }
            val stats = PartyStats(
                level = ext.u8(0x8C), hpCurrent = ext.u16(0x8E),
                stats = IntArray(6) { ext.u16(0x90 + it * 2) }, status = ext.u32(0x88),
            )
            return pk to stats
        }

        /** Slot vazio, exatamente como o PKHeX grava (blank criptografado). */
        fun emptyEncrypted(): ByteArray = Pk4(ByteArray(SIZE)).encrypt()
    }
}
