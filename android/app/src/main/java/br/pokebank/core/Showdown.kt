package br.pokebank.core

/**
 * Exporta Pokemon no formato de texto do Pokemon Showdown (simulador de batalhas de codigo aberto).
 * E a ponte para batalhas: o time do seu cartucho pode ser colado no Showdown hoje,
 * e o modulo de batalha local (veja BATTLES.md) usa o mesmo formato.
 */
object Showdown {
    fun export(d: Pk4Details): String {
        val pk = d.pk
        val sb = StringBuilder()
        val species = pk.speciesName
        val nick = pk.nickname
        sb.append(if (pk.isNicknamed && nick.isNotBlank() && nick != species) "$nick ($species)" else species)
        when (d.gender) { 0 -> sb.append(" (M)"); 1 -> sb.append(" (F)") }
        if (pk.heldItem != 0) sb.append(" @ ").append(GameData.item(pk.heldItem))
        sb.append('\n')
        sb.append("Ability: ").append(d.abilityName).append('\n')
        if (pk.level != 100) sb.append("Level: ").append(pk.level).append('\n')
        if (pk.isShiny) sb.append("Shiny: Yes\n")
        if (d.friendship != 255) sb.append("Happiness: ").append(d.friendship).append('\n')
        val labels = listOf("HP", "Atk", "Def", "Spe", "SpA", "SpD")
        val showOrder = listOf(0, 1, 2, 4, 5, 3) // ordem do Showdown: HP Atk Def SpA SpD Spe
        val evs = showOrder.filter { d.evs[it] > 0 }.joinToString(" / ") { "${d.evs[it]} ${labels[it]}" }
        if (evs.isNotEmpty()) sb.append("EVs: ").append(evs).append('\n')
        sb.append(d.natureName).append(" Nature\n")
        val ivs = showOrder.filter { d.ivs[it] < 31 }.joinToString(" / ") { "${d.ivs[it]} ${labels[it]}" }
        if (ivs.isNotEmpty()) sb.append("IVs: ").append(ivs).append('\n')
        d.moves.filter { it != 0 }.forEach { sb.append("- ").append(GameData.move(it)).append('\n') }
        return sb.toString()
    }

    fun exportTeam(team: List<Pk4Details>): String = team.joinToString("\n") { export(it) }
}
