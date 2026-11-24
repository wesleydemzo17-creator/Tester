package br.pokebank.core

/** Todos os dados de um Pokemon Gen 4, no formato que a interface mostra. */
class Pk4Details(val pk: Pk4, val party: PartyStats? = null) {
    private val d = pk.data

    val species = pk.species
    val info: GameData.Species? = GameData.species(species)
    val nature: Int get() = (pk.pid % 25).toInt()
    val natureName: String get() = GameData.nature(nature)
    val abilityId: Int get() = d.u8(0x15)
    val abilityName: String get() = GameData.ability(abilityId)
    val friendship: Int get() = d.u8(0x14)
    val gender: Int get() = (d.u8(0x40) shr 1) and 3 // 0 macho, 1 femea, 2 sem genero
    val genderSymbol: String get() = when (gender) { 0 -> "♂"; 1 -> "♀"; else -> "" }
    val form: Int get() = pk.form
    val fateful: Boolean get() = d.u8(0x40) and 1 == 1
    val heldItemName: String get() = GameData.item(pk.heldItem)

    val moves: List<Int> get() = (0 until 4).map { d.u16(0x28 + it * 2) }
    val pp: List<Int> get() = (0 until 4).map { d.u8(0x30 + it) }
    val ppUps: List<Int> get() = (0 until 4).map { d.u8(0x34 + it) }

    /** IVs na ordem HP, Atk, Def, Vel, AtkEsp, DefEsp. */
    val ivs: IntArray get() {
        val v = pk.iv32
        return IntArray(6) { ((v ushr (5 * it)) and 31).toInt() }
    }
    /** EVs na ordem HP, Atk, Def, Vel, AtkEsp, DefEsp. */
    val evs: IntArray get() = IntArray(6) { d.u8(0x18 + it) }

    val ball: Int get() = maxOf(d.u8(0x83), d.u8(0x86))
    val ballName: String get() = GameData.BALLS.getOrNull(ball) ?: "Ball #$ball"
    val metLocation: Int get() = d.u16(0x46).takeIf { it != 0 } ?: d.u16(0x80)
    val eggLocation: Int get() = d.u16(0x44).takeIf { it != 0 } ?: d.u16(0x7E)
    val metLevel: Int get() = d.u8(0x84) and 0x7F
    val otGender: Int get() = d.u8(0x84) shr 7
    val metDate: String get() = "%02d/%02d/20%02d".format(d.u8(0x7D), d.u8(0x7C), d.u8(0x7B))
    val eggDate: String? get() = if (d.u8(0x78) == 0) null else "%02d/%02d/20%02d".format(d.u8(0x7A), d.u8(0x79), d.u8(0x78))
    val originGame: String get() = GameData.GAMES[pk.version] ?: "Jogo #${pk.version}"
    val language: String get() = GameData.LANGUAGES[pk.language] ?: "?"
    val pokerus: String get() {
        val v = d.u8(0x82)
        return when { v == 0 -> "nao"; v and 0xF == 0 -> "curado"; else -> "infectado" }
    }
    val contest: IntArray get() = IntArray(6) { d.u8(0x1E + it) }
    val ribbonCount: Int get() =
        Integer.bitCount((d.u32(0x24) and 0x0FFFFFFFL).toInt()) + java.lang.Long.bitCount(d.u32(0x3C)) +
            Integer.bitCount((d.u32(0x60) and 0x000FFFFFL).toInt())
    val hiddenPowerType: Int get() {
        val iv = ivs // HP, Atk, Def, Vel, AtkEsp, DefEsp
        val order = intArrayOf(iv[0], iv[1], iv[2], iv[3], iv[4], iv[5])
        var t = 0
        order.forEachIndexed { i, v -> t = t or ((v and 1) shl i) }
        return t * 15 / 63 + 1 // pula Normal
    }

    /** Status calculados (HP, Atk, Def, Vel, AtkEsp, DefEsp). Se veio da party, usa os do jogo. */
    val stats: IntArray get() {
        party?.let { return it.stats }
        val base = info?.baseStats ?: return IntArray(6)
        val lv = pk.level
        val iv = ivs
        val ev = evs
        val (up, down) = GameData.natureEffect(nature)
        // ordem interna da natureza: Atk(0) Def(1) Vel(2) AtkEsp(3) DefEsp(4) -> nossos indices 1..5
        return IntArray(6) { i ->
            val core = (2 * base[i] + iv[i] + ev[i] / 4) * lv / 100
            if (i == 0) {
                if (species == 292) 1 else core + lv + 10 // Shedinja
            } else {
                var s = core + 5
                if (up != down) {
                    if (up == i - 1) s = s * 110 / 100
                    if (down == i - 1) s = s * 90 / 100
                }
                s
            }
        }
    }

    companion object {
        val STAT_NAMES = listOf("HP", "Ataque", "Defesa", "Velocidade", "Atq. Esp.", "Def. Esp.")
    }
}

/** Estatisticas que so existem para Pokemon na party (calculadas pelo proprio jogo). */
class PartyStats(val level: Int, val hpCurrent: Int, val stats: IntArray, val status: Long)
