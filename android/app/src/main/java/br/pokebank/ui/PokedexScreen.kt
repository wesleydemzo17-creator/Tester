package br.pokebank.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import br.pokebank.core.GameData
import br.pokebank.core.Pk4Details
import br.pokebank.core.Showdown
import br.pokebank.core.SpeciesInfo

/** Pokedex nacional completa (1-493), cruzada com o save aberto: capturado / visto / faltando. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PokedexTab(vm: BankViewModel) {
    vm.version
    val save = vm.save
    var filter by remember { mutableIntStateOf(0) } // 0 todos, 1 capturados, 2 so vistos, 3 faltando
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Int?>(null) }
    // quantos de cada especie voce tem (caixas + equipe + banco)
    val owned = remember(vm.version) {
        val m = HashMap<Int, Int>()
        save?.allBoxMons()?.forEach { (_, _, pk) -> if (!pk.isEgg) m[pk.species] = (m[pk.species] ?: 0) + 1 }
        save?.party?.forEach { (pk, _) -> if (!pk.isEgg) m[pk.species] = (m[pk.species] ?: 0) + 1 }
        vm.bankEntries().forEach { m[it.pk.species] = (m[it.pk.species] ?: 0) + 1 }
        m
    }
    val list = (1..SpeciesInfo.MAX_SPECIES).filter { sp ->
        val caught = save?.dexCaught(sp) == true
        val seen = save?.dexSeen(sp) == true
        val f = when (filter) { 1 -> caught; 2 -> seen && !caught; 3 -> !caught; else -> true }
        f && (query.isBlank() || SpeciesInfo.name(sp).contains(query, ignoreCase = true) || sp.toString() == query.trim())
    }
    Column(Modifier.fillMaxSize()) {
        if (save != null) {
            Text(
                "Pokedex de ${save.trainerName}: ${save.dexCaughtCount} capturados, ${save.dexSeenCount} vistos" +
                    if (save.nationalDex) " (Nacional)" else "",
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold,
            )
        } else {
            Text("Abra um save para ver o que voce ja capturou.", Modifier.padding(12.dp), fontSize = 12.sp)
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            label = { Text("Buscar por nome ou numero") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Todos", "Capturados", "Vistos", "Faltando").forEachIndexed { i, t ->
                FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(t, fontSize = 12.sp) })
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it }) { sp ->
                val info = GameData.species(sp)!!
                val caught = save?.dexCaught(sp) == true
                val seen = save?.dexSeen(sp) == true
                Row(
                    Modifier.fillMaxWidth().clickable { selected = sp }.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SpriteImage(vm, sp, size = 48.dp)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text("#%03d %s".format(sp, info.name), fontWeight = FontWeight.Bold)
                        Row { TypeChip(info.type1); if (info.type2 != info.type1) TypeChip(info.type2) }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            when { caught -> "Capturado"; seen -> "Visto"; save != null -> "-"; else -> "" },
                            fontSize = 12.sp,
                            color = if (caught) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        )
                        owned[sp]?.let { Text("voce tem $it", fontSize = 11.sp) }
                    }
                }
            }
        }
    }
    selected?.let { sp -> SpeciesDialog(vm, sp, owned[sp] ?: 0) { selected = null } }
}

@Composable
private fun SpeciesDialog(vm: BankViewModel, sp: Int, owned: Int, onDismiss: () -> Unit) {
    val info = GameData.species(sp) ?: return onDismiss()
    var shiny by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SpriteImage(vm, sp, size = 96.dp, shiny = shiny)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("#%03d %s".format(sp, info.name), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Row { TypeChip(info.type1); if (info.type2 != info.type1) TypeChip(info.type2) }
                        if (owned > 0) Text("Voce tem $owned", fontSize = 12.sp)
                    }
                }
                TextButton(onClick = { shiny = !shiny }) { Text(if (shiny) "Cor normal" else "Cor shiny") }
                Pk4Details.STAT_NAMES.forEachIndexed { i, n -> StatBar(n, info.baseStats[i], 255) }
                Text("Total: ${info.baseTotal}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                val ab = if (info.ability2 == 0 || info.ability2 == info.ability1) GameData.ability(info.ability1)
                else "${GameData.ability(info.ability1)} / ${GameData.ability(info.ability2)}"
                Text("Habilidades: $ab", fontSize = 13.sp)
                Text("Genero: " + when (info.genderRatio) {
                    255 -> "sem genero"; 254 -> "so femea"; 0 -> "so macho"
                    else -> "%.1f%% femea".format(info.genderRatio * 100.0 / 256)
                }, fontSize = 13.sp)
                Text("Taxa de captura: ${info.catchRate}", fontSize = 13.sp)
                TextButton(onClick = onDismiss) { Text("Fechar") }
            }
        }
    }
}

/** Dados do treinador do save aberto. */
@Composable
fun TrainerTab(vm: BankViewModel) {
    vm.version
    val save = vm.save ?: return NoSaveHint(vm)
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(save.trainerName, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text(save.game.label, fontSize = 13.sp)
                Text("ID %05d   ID secreto %05d".format(save.trainerId, save.secretId))
                Text("Genero: " + if (save.trainerGender == 1) "feminino" else "masculino")
                Text("Dinheiro: $${save.money}")
                Text("Insignias: ${save.badgeCount}")
                Text("Tempo de jogo: ${save.playTime}")
                Text("Fichas (Game Corner): ${save.coins}")
                Text("Pokedex: ${save.dexCaughtCount} capturados / ${save.dexSeenCount} vistos")
                Text("Pokemon nas caixas: ${save.allBoxMons().size}   Equipe: ${save.party.size}")
                Text(
                    "Checksums: " + if (save.generalChecksumValid && save.storageChecksumValid) "OK" else "INVALIDOS",
                    color = if (save.generalChecksumValid && save.storageChecksumValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }
        TextButton(onClick = {
            val team = save.party.map { (pk, st) -> Pk4Details(pk, st) }.filter { !it.pk.isEgg }
            clipboard.setText(AnnotatedString(Showdown.exportTeam(team)))
            vm.notify("Equipe copiada no formato do Pokemon Showdown")
        }) { Text("Copiar equipe para o Pokemon Showdown") }
        Text(
            "Cole no Teambuilder do Pokemon Showdown (\"Import from text\") para batalhar online com o seu time real. " +
                "As batalhas DS <-> Android estao planejadas em BATTLES.md.",
            fontSize = 12.sp,
        )
    }
}
