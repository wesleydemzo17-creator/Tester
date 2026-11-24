package br.pokebank.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.pokebank.core.Gen4Save
import br.pokebank.core.Pk4

@Composable
fun NoSaveHint(vm: BankViewModel) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Nenhum save aberto.", fontWeight = FontWeight.Bold)
        Text(
            "Opcao 1 - Nintendo DS:\n  Abra o PokeBank DS (R4, DSi ou cartucho) na mesma rede Wi-Fi. " +
                "Ele acha este celular sozinho e envia o save.\n\n" +
                "Opcao 2 - Emulador no celular:\n  Toque em \"Abrir save\" e escolha o .sav (melonDS) ou .dsv (DraStic).",
        )
        Spacer(Modifier.height(12.dp))
        Text("Arquivos do app: ${vm.storagePath}", fontSize = 12.sp)
    }
}

@Composable
fun BoxesTab(vm: BankViewModel) {
    vm.version
    val save = vm.save ?: return NoSaveHint(vm)
    var selected by remember { mutableStateOf<Int?>(null) }
    val box = vm.currentBox
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.currentBox = (box + Gen4Save.BOXES - 1) % Gen4Save.BOXES }) { Text("<") }
            Text(save.boxName(box), Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
            TextButton(onClick = { vm.currentBox = (box + 1) % Gen4Save.BOXES }) { Text(">") }
        }
        for (row in 0 until 5) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 6) {
                    val slot = row * 6 + col
                    SlotCell(vm, vm.slot(box, slot), vm.isPendingSlot(box, slot), Modifier.weight(1f)) { selected = slot }
                }
            }
        }
        Text("Toque num Pokemon para ver a ficha completa ou deposita-lo.", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    }
    selected?.let { slot ->
        val pk = vm.slot(box, slot)
        if (pk == null) { selected = null; return@let }
        PokemonDetailDialog(vm, pk, actionLabel = "Depositar no banco", onAction = { vm.deposit(box, slot); selected = null }) { selected = null }
    }
}

@Composable
private fun SlotCell(vm: BankViewModel, pk: Pk4?, pending: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val border = if (pending) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .border(if (pending) 2.dp else 1.dp, border, RoundedCornerShape(6.dp))
            .background(if (pk == null) Color.Transparent else MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp))
            .clickable(enabled = pk != null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (pk != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (pk.isEgg) Text("Ovo", fontSize = 10.sp) else SpriteImage(vm, pk.species, size = 40.dp, shiny = pk.isShiny)
                if (!pk.isEgg) Text("Nv${pk.level}" + if (pk.isShiny) "★" else "", fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Clip)
            }
        }
    }
}

@Composable
fun PartyTab(vm: BankViewModel) {
    vm.version
    val save = vm.save ?: return NoSaveHint(vm)
    val party = remember(vm.version) { save.party }
    var selected by remember { mutableStateOf<Int?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text("Equipe (${party.size}/6) - somente leitura nesta versao", Modifier.padding(12.dp), fontSize = 12.sp) }
        items(party.indices.toList()) { i ->
            val (pk, st) = party[i]
            MonRow(vm, pk, "Nv ${st.level} - HP ${st.hpCurrent}/${st.stats[0]}") { selected = i }
        }
    }
    selected?.let { i -> party.getOrNull(i)?.let { (pk, st) -> PokemonDetailDialog(vm, pk, st) { selected = null } } }
}

@Composable
fun MonRow(vm: BankViewModel, pk: Pk4, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpriteImage(vm, if (pk.isEgg) 0 else pk.species, size = 48.dp, shiny = pk.isShiny)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(pk.displayName + if (pk.isShiny) "  ★" else "", fontWeight = FontWeight.Bold)
            Text(subtitle, fontSize = 12.sp)
        }
    }
}

@Composable
fun BankTab(vm: BankViewModel) {
    vm.version
    val entries = vm.bankEntries()
    var selected by remember { mutableStateOf<String?>(null) }
    if (entries.isEmpty()) {
        Text("O banco esta vazio. Deposite Pokemon pela aba Caixas.", Modifier.padding(24.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text("${entries.size} Pokemon guardados no celular (arquivos .pk4, abrem no PKHeX)", Modifier.padding(12.dp), fontSize = 12.sp) }
        items(entries, key = { it.uid }) { e ->
            MonRow(vm, e.pk, "Nv ${e.pk.level} - TO: ${e.pk.otName}") { selected = e.uid }
        }
    }
    selected?.let { uid ->
        val e = entries.firstOrNull { it.uid == uid }
        if (e == null) { selected = null; return@let }
        PokemonDetailDialog(
            vm, e.pk,
            actionLabel = if (vm.session != null) "Retirar para o save" else null,
            onAction = { vm.withdraw(uid); selected = null },
        ) { selected = null }
    }
}
