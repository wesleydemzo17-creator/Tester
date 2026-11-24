package br.pokebank.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.pokebank.core.BankSession

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

private val TABS = listOf("Caixas", "Equipe", "Banco", "Pokedex", "Treinador", "Registro")

private fun displayName(uri: Uri): String = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "save"

@Composable
fun App(vm: BankViewModel) {
    val ctx = LocalContext.current
    val msg = vm.toast
    LaunchedEffect(msg) {
        if (msg != null) { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show(); vm.toast = null }
    }
    val openSave = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            vm.openLocalSave(uri, displayName(uri))
        }
    }
    val openRom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.extractSprites(uri)
    }
    var tab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        StatusCard(vm, onOpenSave = { openSave.launch(arrayOf("*/*")) }, onOpenRom = { openRom.launch(arrayOf("*/*")) })
        if (vm.pendingRecovery > 0) RecoveryBanner(vm)
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            TABS.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> BoxesTab(vm)
                1 -> PartyTab(vm)
                2 -> BankTab(vm)
                3 -> PokedexTab(vm)
                4 -> TrainerTab(vm)
                else -> LogTab(vm)
            }
        }
        PendingBar(vm)
    }
}

@Composable
private fun StatusCard(vm: BankViewModel, onOpenSave: () -> Unit, onOpenRom: () -> Unit) {
    vm.version
    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("PokeBank", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(
                if (vm.serverRunning) "Servidor ativo - IP: ${vm.localIps().joinToString(", ").ifEmpty { "sem rede" }}" else "Servidor parado",
                fontSize = 12.sp,
            )
            Text(vm.dsInfo?.let { "DS: $it" } ?: "DS: aguardando (abra o PokeBank DS na mesma rede)", fontSize = 12.sp)
            val o = vm.origin
            vm.save?.let { s ->
                val from = when (o) { is SaveOrigin.LocalFile -> "arquivo ${o.name}"; is SaveOrigin.Ds -> "DS"; null -> "" }
                Text("Aberto: ${s.game.label} - ${s.trainerName} ($from)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            if (!vm.sprites.hasSprites && vm.spriteProgress == null) {
                Text("Sem imagens: toque em \"Sprites da ROM\" e escolha o .nds do seu jogo.", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            }
            vm.spriteProgress?.let { Text(it, fontSize = 12.sp); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            vm.busy?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = onOpenSave, enabled = vm.busy == null) { Text("Abrir save", fontSize = 12.sp) }
                OutlinedButton(onClick = onOpenRom, enabled = vm.spriteProgress == null) { Text("Sprites da ROM", fontSize = 12.sp) }
                if (vm.save != null) OutlinedButton(onClick = { vm.closeSave() }, enabled = vm.busy == null) { Text("Fechar", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun RecoveryBanner(vm: BankViewModel) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Gravacao interrompida", fontWeight = FontWeight.Bold)
            Text(
                "Ha ${vm.pendingRecovery} Pokemon de um deposito que nao foi confirmado. " +
                    "Releia o save (no DS: botao X): se eles NAO estiverem mais la, recupere para o banco.",
                fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.recoverPending() }) { Text("Recuperar") }
                OutlinedButton(onClick = { vm.dropPending() }) { Text("Descartar") }
            }
        }
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
    vm.version
    val s = vm.session ?: return
    val ops = s.pending
    val local = vm.origin is SaveOrigin.LocalFile
    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp)) {
            val dep = ops.count { it is BankSession.Op.Deposit }
            val wit = ops.count { it is BankSession.Op.Withdraw }
            Text(if (ops.isEmpty()) "Nenhuma mudanca pendente" else "Pendente: $dep deposito(s), $wit retirada(s)", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(
                    onClick = { vm.writeChanges() },
                    enabled = ops.isNotEmpty() && vm.busy == null && (local || vm.dsInfo != null),
                ) { Text(if (local) "Salvar no arquivo" else "Gravar no DS") }
                OutlinedButton(onClick = { vm.discard() }, enabled = ops.isNotEmpty() && vm.busy == null) { Text("Desfazer") }
                if (!local) OutlinedButton(onClick = { vm.requestSave() }, enabled = vm.dsInfo != null && vm.busy == null) { Text("Reler") }
            }
        }
    }
}
