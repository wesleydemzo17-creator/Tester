package br.pokebank.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.pokebank.core.BankSession
import br.pokebank.core.Gen4Save
import br.pokebank.core.Pk4

class MainActivity : ComponentActivity() {
    private val vm: BankViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App(vm) }
            }
        }
    }
}

@Composable
fun App(vm: BankViewModel) {
    val ctx = LocalContext.current
    val msg = vm.toast
    LaunchedEffect(msg) {
        if (msg != null) { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show(); vm.toast = null }
    }
    var tab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        StatusCard(vm)
        if (vm.pendingRecovery > 0) RecoveryBanner(vm)
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Cartucho") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Banco (${vm.version.let { vm.bankEntries().size }})") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Registro") })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> CartridgeTab(vm)
                1 -> BankTab(vm)
                else -> LogTab(vm)
            }
        }
        PendingBar(vm)
    }
}

@Composable
private fun StatusCard(vm: BankViewModel) {
    vm.version // le o contador de mudancas: recompoe quando o save/banco muda
    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("PokeBank", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(if (vm.serverRunning) "Servidor ativo - IP: ${vm.localIps().joinToString(", ").ifEmpty { "sem rede" }}" else "Servidor parado")
            Text(vm.dsInfo?.let { "DS: $it" } ?: "Aguardando o DS... (abra o PokeBank DS na mesma rede)")
            vm.session?.save?.let { s ->
                Text("Jogo: ${s.game.label} - ${s.trainerName} (ID %05d)".format(s.trainerId))
            }
            vm.busy?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun RecoveryBanner(vm: BankViewModel) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Gravacao interrompida", fontWeight = FontWeight.Bold)
            Text("Ha ${vm.pendingRecovery} Pokemon de um deposito que nao foi confirmado. " +
                "Releia o cartucho no DS (botao X): se eles NAO estiverem mais la, recupere para o banco.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.recoverPending() }) { Text("Recuperar") }
                OutlinedButton(onClick = { vm.dropPending() }) { Text("Descartar") }
            }
        }
    }
}

@Composable
private fun CartridgeTab(vm: BankViewModel) {
    vm.version // le o contador de mudancas: recompoe quando o save/banco muda
    val session = vm.session
    if (session == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Nenhum save carregado.", fontWeight = FontWeight.Bold)
            Text("1. Ligue o PokeBank DS com o cartucho Pokemon.\n2. Deixe o DS e este celular na mesma rede Wi-Fi.\n3. O DS acha o celular sozinho e envia o save.")
            Spacer(Modifier.height(12.dp))
            Text("Arquivos: ${vm.storagePath}", fontSize = 12.sp)
        }
        return
    }
    var selected by remember { mutableStateOf<Int?>(null) }
    val box = vm.currentBox
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.currentBox = (box + Gen4Save.BOXES - 1) % Gen4Save.BOXES }) { Text("<") }
            Text(session.save.boxName(box), Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
            TextButton(onClick = { vm.currentBox = (box + 1) % Gen4Save.BOXES }) { Text(">") }
        }
        for (row in 0 until 5) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 6) {
                    val slot = row * 6 + col
                    SlotCell(vm.slot(box, slot), vm.isPendingSlot(box, slot), Modifier.weight(1f)) { selected = slot }
                }
            }
        }
        Text("Toque num Pokemon para deposita-lo no banco.", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    }
    selected?.let { slot ->
        val pk = vm.slot(box, slot)
        if (pk == null) { selected = null; return@let }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(pk.displayName) },
            text = { PokemonDetails(pk) },
            confirmButton = { Button(onClick = { vm.deposit(box, slot); selected = null }) { Text("Depositar no banco") } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Fechar") } },
        )
    }
}

@Composable
private fun SlotCell(pk: Pk4?, pending: Boolean, modifier: Modifier, onClick: () -> Unit) {
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
                Text(if (pk.isEgg) "Ovo" else pk.speciesName, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!pk.isEgg) Text("${pk.level}" + if (pk.isShiny) "*" else "", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PokemonDetails(pk: Pk4) {
    Column {
        Text("Especie: ${pk.speciesName} (#${pk.species})")
        if (!pk.isEgg) Text("Nivel: ${pk.level}")
        Text("Treinador original: ${pk.otName} (ID %05d)".format(pk.tid))
        if (pk.isShiny) Text("Shiny!")
        if (!pk.isChecksumValid) Text("ATENCAO: checksum invalido", color = MaterialTheme.colorScheme.error)
        Text("PID: %08X".format(pk.pid), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

@Composable
private fun BankTab(vm: BankViewModel) {
    vm.version // le o contador de mudancas: recompoe quando o save/banco muda
    val entries = vm.bankEntries()
    var selected by remember { mutableStateOf<String?>(null) }
    if (entries.isEmpty()) {
        Text("O banco esta vazio. Deposite Pokemon pela aba Cartucho.", Modifier.padding(24.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(entries, key = { it.uid }) { e ->
            Row(
                Modifier.fillMaxWidth().clickable { selected = e.uid }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(e.pk.displayName + if (e.pk.isShiny) "  *" else "", fontWeight = FontWeight.Bold)
                    Text("Nv. ${e.pk.level} - TO: ${e.pk.otName}", fontSize = 12.sp)
                }
            }
        }
    }
    selected?.let { uid ->
        val e = entries.firstOrNull { it.uid == uid }
        if (e == null) { selected = null; return@let }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(e.pk.displayName) },
            text = { PokemonDetails(e.pk) },
            confirmButton = {
                Button(onClick = { vm.withdraw(uid); selected = null }, enabled = vm.session != null) { Text("Retirar para o cartucho") }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Fechar") } },
        )
    }
}

@Composable
private fun LogTab(vm: BankViewModel) {
    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        items(vm.log) { line -> Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    }
}

@Composable
private fun PendingBar(vm: BankViewModel) {
    vm.version // le o contador de mudancas: recompoe quando o save/banco muda
    val s = vm.session ?: return
    val ops = s.pending
    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp)) {
            val dep = ops.count { it is BankSession.Op.Deposit }
            val wit = ops.count { it is BankSession.Op.Withdraw }
            Text(if (ops.isEmpty()) "Nenhuma mudanca pendente" else "Pendente: $dep deposito(s), $wit retirada(s)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(onClick = { vm.writeToDs() }, enabled = ops.isNotEmpty() && vm.busy == null && vm.dsInfo != null) { Text("Gravar no DS") }
                OutlinedButton(onClick = { vm.discard() }, enabled = ops.isNotEmpty() && vm.busy == null) { Text("Desfazer") }
                OutlinedButton(onClick = { vm.requestSave() }, enabled = vm.dsInfo != null && vm.busy == null) { Text("Reler") }
            }
        }
    }
}
