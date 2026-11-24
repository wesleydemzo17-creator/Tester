package br.pokebank.core

/** Textos da 4a geracao: cada caractere e um u16 numa tabela propria; 0xFFFF termina. */
object Gen4Text {
    private const val INVALID = '￿'

    fun decode(data: ByteArray, offset: Int, length: Int): String {
        val sb = StringBuilder()
        var i = offset
        while (i + 1 < offset + length) {
            val v = data.u16(i)
            if (v == 0xFFFF || v == 0) break
            val c = if (v < Gen4Tables.CHARS.length) Gen4Tables.CHARS[v] else INVALID
            if (c != INVALID) sb.append(c) else sb.append('?')
            i += 2
        }
        return sb.toString()
    }
}

object SpeciesInfo {
    const val MAX_SPECIES = 493

    fun name(species: Int): String =
        if (species in 0..MAX_SPECIES) Gen4Tables.SPECIES[species] else "#$species"

    fun levelFor(species: Int, exp: Long): Int {
        if (species !in 1..MAX_SPECIES) return 0
        val table = Gen4Tables.EXP[Gen4Tables.GROWTH[species].toInt().coerceIn(0, 5)]
        var lv = 1
        while (lv < 100 && exp >= table[lv]) lv++
        return lv
    }
}
