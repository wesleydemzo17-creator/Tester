package br.pokebank.core

/**
 * Textos e dados dos jogos (extraidos do PKHeX, em ingles como nos cartuchos ocidentais).
 * Ficam em src/main/resources/br/pokebank/data e sao carregados sob demanda.
 */
object GameData {
    private fun load(name: String): List<String> =
        (GameData::class.java.getResourceAsStream("/br/pokebank/data/$name")
            ?: error("recurso $name ausente")).bufferedReader(Charsets.UTF_8).readLines()

    val moves by lazy { load("moves.txt") }
    val abilities by lazy { load("abilities.txt") }
    val items by lazy { load("items.txt") }
    val natures by lazy { load("natures.txt") }
    val types by lazy { load("types.txt") }
    private val loc0 by lazy { load("locations_00000.txt") }
    private val loc2000 by lazy { load("locations_02000.txt") }
    private val loc3000 by lazy { load("locations_03000.txt") }

    private val personal: ByteArray by lazy {
        GameData::class.java.getResourceAsStream("/br/pokebank/data/personal.bin")!!.readBytes()
    }

    fun move(id: Int) = moves.getOrNull(id)?.takeIf { id > 0 } ?: if (id == 0) "-" else "#$id"
    fun ability(id: Int) = abilities.getOrNull(id) ?: "#$id"
    fun item(id: Int) = if (id == 0) "nenhum" else items.getOrNull(id) ?: "#$id"
    fun nature(id: Int) = natures.getOrNull(id) ?: "?"
    fun type(id: Int) = types.getOrNull(id) ?: "?"

    fun location(id: Int): String = when {
        id >= 3000 -> loc3000.getOrNull(id - 3000)
        id >= 2000 -> loc2000.getOrNull(id - 2000)
        else -> loc0.getOrNull(id)
    }?.ifBlank { null } ?: "Local #$id"

    val BALLS = listOf(
        "", "Master Ball", "Ultra Ball", "Great Ball", "Poke Ball", "Safari Ball", "Net Ball", "Dive Ball",
        "Nest Ball", "Repeat Ball", "Timer Ball", "Luxury Ball", "Premier Ball", "Dusk Ball", "Heal Ball",
        "Quick Ball", "Cherish Ball", "Fast Ball", "Level Ball", "Lure Ball", "Heavy Ball", "Love Ball",
        "Friend Ball", "Moon Ball", "Sport Ball", "Park Ball",
    )

    val GAMES = mapOf(
        1 to "Sapphire", 2 to "Ruby", 3 to "Emerald", 4 to "FireRed", 5 to "LeafGreen", 7 to "HeartGold",
        8 to "SoulSilver", 10 to "Diamond", 11 to "Pearl", 12 to "Platinum", 15 to "Colosseum/XD",
    )

    val LANGUAGES = mapOf(1 to "Japones", 2 to "Ingles", 3 to "Frances", 4 to "Italiano", 5 to "Alemao", 7 to "Espanhol", 8 to "Coreano")

    /** Dados base da especie (tabela "personal" do jogo). */
    class Species(val id: Int, private val p: ByteArray, private val o: Int) {
        val name: String get() = SpeciesInfo.name(id)
        /** HP, Atk, Def, Vel, AtkEsp, DefEsp (mesma ordem da tabela do jogo). */
        val baseStats: IntArray get() = intArrayOf(b(0), b(1), b(2), b(3), b(4), b(5))
        val type1: Int get() = b(6)
        val type2: Int get() = b(7)
        val catchRate: Int get() = b(8)
        val genderRatio: Int get() = b(0x10) // 0 = so macho, 254 = so femea, 255 = sem genero
        val ability1: Int get() = b(0x16)
        val ability2: Int get() = b(0x17)
        val growth: Int get() = b(0x13)
        val eggGroup1: Int get() = b(0x14)
        val eggGroup2: Int get() = b(0x15)
        val baseTotal: Int get() = baseStats.sum()
        val typesText: String get() = if (type1 == type2) GameData.type(type1) else "${GameData.type(type1)} / ${GameData.type(type2)}"
        private fun b(i: Int) = p[o + i].toInt() and 0xFF
    }

    fun species(id: Int): Species? = if (id in 1..SpeciesInfo.MAX_SPECIES) Species(id, personal, id * 0x2C) else null

    /** Natureza: +10% e -10% (indices em Atk, Def, Vel, AtkEsp, DefEsp). */
    fun natureEffect(nature: Int): Pair<Int, Int> = (nature / 5) to (nature % 5)
}
