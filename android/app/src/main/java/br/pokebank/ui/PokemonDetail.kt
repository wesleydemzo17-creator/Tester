package br.pokebank.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import br.pokebank.core.GameData
import br.pokebank.core.PartyStats
import br.pokebank.core.Pk4
import br.pokebank.core.Pk4Details
import br.pokebank.core.Showdown

/** Sprite do Pokemon (extraido da ROM) ou um marcador com o numero. */
@Composable
fun SpriteImage(vm: BankViewModel, species: Int, modifier: Modifier = Modifier, size: Dp = 48.dp, shiny: Boolean = false, female: Boolean = false) {
    val v = vm.version
    val bmp = remember(species, shiny, female, v) { vm.sprite(species, shiny, female)?.asImageBitmap() }
    if (bmp != null) {
        Image(bitmap = bmp, contentDescription = null, modifier = modifier.size(size), filterQuality = FilterQuality.None)
    } else {
        Box(modifier.size(size), contentAlignment = Alignment.Center) {
            Text(if (species > 0) "#$species" else "?", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
        }
    }
}

private val TYPE_COLORS = mapOf(
    0 to 0xFFA8A878, 1 to 0xFFC03028, 2 to 0xFFA890F0, 3 to 0xFFA040A0, 4 to 0xFFE0C068, 5 to 0xFFB8A038,
    6 to 0xFFA8B820, 7 to 0xFF705898, 8 to 0xFFB8B8D0, 9 to 0xFFF08030, 10 to 0xFF6890F0, 11 to 0xFF78C850,
    12 to 0xFFF8D030, 13 to 0xFFF85888, 14 to 0xFF98D8D8, 15 to 0xFF7038F8, 16 to 0xFF705848,
)

@Composable
fun TypeChip(type: Int) {
    Box(
        Modifier.padding(end = 4.dp)
            .background(Color(TYPE_COLORS[type] ?: 0xFF888888), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) { Text(GameData.type(type), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun StatBar(label: String, value: Int, max: Int, extra: String = "") {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(80.dp), fontSize = 12.sp)
        Text("$value", Modifier.width(36.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.weight(1f).height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
            Box(
                Modifier.fillMaxWidth((value.toFloat() / max).coerceIn(0.02f, 1f)).height(8.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
            )
        }
        if (extra.isNotEmpty()) Text(extra, Modifier.padding(start = 6.dp), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, Modifier.width(130.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
        Text(value, fontSize = 13.sp)
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(12.dp))
    Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
}

/** Ficha completa de um Pokemon, com acoes opcionais (depositar/retirar). */
@Composable
fun PokemonDetailDialog(
    vm: BankViewModel,
    pk: Pk4,
    party: PartyStats? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val d = remember(pk, party) { Pk4Details(pk, party) }
    val clipboard = LocalClipboardManager.current
    var shiny by remember { mutableStateOf(pk.isShiny) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SpriteImage(vm, pk.species, size = 96.dp, shiny = shiny, female = d.gender == 1)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(pk.displayName + " " + d.genderSymbol, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("#%03d %s".format(pk.species, pk.speciesName) + if (pk.isShiny) "  ★ Shiny" else "", fontSize = 13.sp)
                        if (!pk.isEgg) Text("Nivel ${party?.level ?: pk.level}", fontSize = 13.sp)
                        Row(Modifier.padding(top = 4.dp)) {
                            d.info?.let { TypeChip(it.type1); if (it.type2 != it.type1) TypeChip(it.type2) }
                        }
                    }
                }
                TextButton(onClick = { shiny = !shiny }) { Text(if (shiny) "Ver cor normal" else "Ver cor shiny") }
                if (!pk.isChecksumValid) Text("ATENCAO: checksum invalido (dados corrompidos)", color = MaterialTheme.colorScheme.error)

                Section("Geral")
                InfoLine("Natureza", d.natureName)
                InfoLine("Habilidade", d.abilityName)
                InfoLine("Item", d.heldItemName)
                InfoLine("Amizade", "${d.friendship} / 255")
                InfoLine("EXP", "${pk.exp}")
                InfoLine("Poder Oculto", GameData.type(d.hiddenPowerType))
                InfoLine("Pokerus", d.pokerus)
                InfoLine("Fitas", "${d.ribbonCount}")
                if (d.form != 0) InfoLine("Forma", "${d.form}")
                if (party != null) InfoLine("HP atual", "${party.hpCurrent} / ${party.stats[0]}")

                Section("Status (IV / EV)")
                val stats = d.stats
                Pk4Details.STAT_NAMES.forEachIndexed { i, n ->
                    StatBar(n, stats[i], if (i == 0) 500 else 400, "%2d/%3d".format(d.ivs[i], d.evs[i]))
                }
                Text("Total de EVs: ${d.evs.sum()} / 510   Soma dos IVs: ${d.ivs.sum()} / 186", fontSize = 11.sp)

                Section("Golpes")
                d.moves.forEachIndexed { i, m ->
                    if (m != 0) InfoLine(GameData.move(m), "PP ${d.pp[i]}" + if (d.ppUps[i] > 0) " (+${d.ppUps[i]})" else "")
                }

                Section("Origem")
                InfoLine("Treinador", "${pk.otName} ${if (d.otGender == 1) "♀" else "♂"}")
                InfoLine("ID / ID secreto", "%05d / %05d".format(pk.tid, pk.sid))
                InfoLine("Jogo de origem", d.originGame)
                InfoLine("Idioma", d.language)
                InfoLine("Pokebola", d.ballName)
                InfoLine("Encontrado em", GameData.location(d.metLocation))
                InfoLine("Nivel / data", "${d.metLevel}  em  ${d.metDate}")
                d.eggDate?.let { InfoLine("Ovo", "${GameData.location(d.eggLocation)} em $it") }
                if (d.fateful) InfoLine("Evento", "Encontro do destino")
                InfoLine("PID", "%08X".format(pk.pid))

                d.info?.let { sp ->
                    Section("Especie (base)")
                    Pk4Details.STAT_NAMES.forEachIndexed { i, n -> StatBar(n, sp.baseStats[i], 255) }
                    InfoLine("Total base", "${sp.baseTotal}")
                    InfoLine("Habilidades", if (sp.ability1 == sp.ability2 || sp.ability2 == 0) GameData.ability(sp.ability1) else "${GameData.ability(sp.ability1)} / ${GameData.ability(sp.ability2)}")
                    InfoLine("Taxa de captura", "${sp.catchRate}")
                }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(Showdown.export(d)))
                        vm.notify("Copiado no formato do Showdown")
                    }) { Text("Copiar p/ Showdown") }
                    if (actionLabel != null && onAction != null) Button(onClick = onAction) { Text(actionLabel) }
                }
                TextButton(onClick = onDismiss) { Text("Fechar") }
            }
        }
    }
}
