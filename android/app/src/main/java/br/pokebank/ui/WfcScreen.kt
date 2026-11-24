package br.pokebank.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.pokebank.wfc.RootRedirect
import br.pokebank.wfc.WfcCrypto
import br.pokebank.wfc.WfcServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Aba do servidor WFC proprio (Wi-Fi Club, Pal Pad, batalhas e trocas do jogo original). */
@Composable
fun WfcTab(vm: BankViewModel) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var tick by remember { mutableIntStateOf(0) }
    var root by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        root = withContext(Dispatchers.IO) { RootRedirect.hasRoot() }
        while (true) { delay(1000); tick++ }
    }
    tick
    // Android 13+: pede permissao para a notificacao do servico (sem ela o servico roda, mas escondido)
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { WfcService.start(ctx) }
    val srv = WfcRuntime.server
    val running = WfcRuntime.running
    val ips = WfcServer.localIps()

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Servidor WFC proprio", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Root: " + when (root) { null -> "verificando..."; true -> "sim"; false -> "NAO (necessario)" },
                        color = if (root == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Text("Servidor: " + if (running) "ATIVO" else "parado", fontWeight = FontWeight.Bold,
                        color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    if (running) Text("Portas 53/80: " + if (WfcRuntime.redirectActive) "desviadas (ok)" else "NAO desviadas", fontSize = 12.sp)
                    WfcRuntime.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) else WfcService.start(ctx)
                        }, enabled = !running) { Text("Iniciar") }
                        OutlinedButton(onClick = { WfcService.stop(ctx) }, enabled = running) { Text("Parar") }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("DNS para o DS e o melonDS", fontWeight = FontWeight.Bold)
                    if (ips.isEmpty()) Text("Sem rede. Ligue o roteador do celular ou um Wi-Fi.")
                    ips.forEach { ip ->
                        Row {
                            Text(ip, fontFamily = FontFamily.Monospace, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(ip)); vm.notify("DNS copiado: $ip") }) { Text("Copiar") }
                        }
                    }
                    Text(
                        "No jogo: Nintendo WFC Settings > conexao > DNS automatico NAO > Primario = o IP acima " +
                            "(o da rede onde o DS esta; no roteador do celular costuma ser 192.168.43.1 ou parecido), Secundario = o mesmo.\n" +
                            "No melonDS: o mesmo DNS dentro do jogo.\n\n" +
                            "IMPORTANTE: a ROM precisa do patch NoSSL (WfcPatcher ou codigos de Action Replay). " +
                            "O \"Testar conexao\" funciona mesmo sem patch.",
                        fontSize = 12.sp,
                    )
                }
            }
        }
        item {
            Text("Online agora", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            val online = srv?.online() ?: emptyList()
            if (online.isEmpty()) Text("Ninguem conectado.", fontSize = 12.sp)
            online.forEach { p ->
                Text("${p.name.ifBlank { "?" }} - ${p.game} - codigo ${p.friendCode} - ${p.status} (${p.ip})", fontSize = 13.sp)
            }
        }
        item {
            Text("Perfis registrados (use no Pal Pad)", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            val profiles = srv?.db?.profiles() ?: emptyList()
            if (profiles.isEmpty()) Text("Nenhum ainda. Conecte um jogo uma vez.", fontSize = 12.sp)
            profiles.forEach { p ->
                Text("${p.ingamesn.ifBlank { p.devname }} (${p.gsbrcd.take(4)}): " +
                    WfcCrypto.formatFriendCode(WfcCrypto.friendCode(p.profileId, p.gsbrcd.take(4))),
                    fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Registro do servidor", fontWeight = FontWeight.Bold)
        }
        items(WfcRuntime.log.take(200)) { line -> Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
    }
}
