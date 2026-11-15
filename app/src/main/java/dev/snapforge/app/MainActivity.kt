@file:OptIn(ExperimentalMaterial3Api::class)

package dev.snapforge.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val CAccent = Color(0xFF39D98A)
internal val CBg = Color(0xFF0E1116)
internal val CCard = Color(0xFF171B22)
internal val CDim = Color(0xFF8B95A5)
internal val CHot = Color(0xFFFF6B5E)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = CAccent,
                    onPrimary = Color.Black,
                    background = CBg,
                    surface = CCard,
                    surfaceVariant = Color(0xFF222833),
                    secondaryContainer = Color(0xFF1F3B2E)
                )
            ) {
                AppRoot()
            }
        }
    }
}

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rooted by remember { mutableStateOf<Boolean?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var mode by remember { mutableStateOf(Prefs.mode(ctx)) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { rooted = Root.isRooted() }
    // mantém em sincronia com o bloco das Configurações Rápidas
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            if (!busy) mode = Prefs.mode(ctx)
        }
    }

    val setMode: (PerfMode) -> Unit = { m ->
        scope.launch {
            busy = true
            if (Perf.apply(m)) {
                Prefs.setMode(ctx, m)
                mode = m
            } else {
                Toast.makeText(ctx, "Falha ao aplicar o modo (root?)", Toast.LENGTH_SHORT).show()
            }
            busy = false
        }
    }

    val launchGame: (AppEntry, PerfMode) -> Unit = { app, m ->
        scope.launch {
            busy = true
            if (Perf.apply(m)) {
                Prefs.setMode(ctx, m)
                mode = m
            }
            busy = false
            if (!Apps.launch(ctx, app.pkg)) {
                Toast.makeText(ctx, "Não foi possível abrir ${app.label}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        containerColor = CBg,
        bottomBar = {
            NavigationBar(containerColor = CCard) {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("Painel") }
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    label = { Text("Jogos") }
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Build, contentDescription = null) },
                    label = { Text("Controle") }
                )
                NavigationBarItem(
                    selected = tab == 3, onClick = { tab = 3 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Ajustes") }
                )
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (rooted) {
                null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                false -> NoRoot { scope.launch { rooted = null; rooted = Root.isRooted() } }
                else -> when (tab) {
                    0 -> Dashboard(mode, busy, setMode)
                    1 -> GamesTab(launchGame)
                    2 -> TuningTab()
                    else -> ToolsTab(setMode)
                }
            }
        }
    }
}

@Composable
private fun NoRoot(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Acesso root não concedido", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Abra o Magisk/KernelSU, permita o SnapForge e tente de novo.",
            color = CDim
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Tentar novamente") }
    }
}

@Composable
internal fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CCard)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
internal fun StatRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = CDim)
        Text(value, color = valueColor, fontWeight = FontWeight.Medium)
    }
}

private fun tempText(t: Float?): String = if (t == null) "—" else String.format("%.1f °C", t)
private fun tempColor(t: Float?): Color =
    if (t != null && t >= 70f) CHot else Color.Unspecified

@Composable
private fun ModeSelector(mode: PerfMode, busy: Boolean, onMode: (PerfMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PerfMode.values().forEach { m ->
            val selected = m == mode
            Card(
                modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { onMode(m) },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) Color(0xFF1F3B2E) else Color(0xFF222833)
                ),
                border = if (selected) BorderStroke(1.dp, CAccent) else null
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        m.label,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) CAccent else Color.Unspecified
                    )
                    Text(m.desc, color = CDim, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun Dashboard(mode: PerfMode, busy: Boolean, onMode: (PerfMode) -> Unit) {
    var snap by remember { mutableStateOf<Snapshot?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            snap = Monitor.read()
            delay(2000)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionCard("Modo de desempenho") { ModeSelector(mode, busy, onMode) }

        val s = snap
        if (s == null) {
            Text("Lendo sensores…", color = CDim)
        } else {
            SectionCard("Temperaturas") {
                StatRow("CPU", tempText(s.cpuTemp), tempColor(s.cpuTemp))
                StatRow("GPU", tempText(s.gpuTemp), tempColor(s.gpuTemp))
                StatRow("Bateria", tempText(s.batTemp), if ((s.batTemp ?: 0f) >= 45f) CHot else Color.Unspecified)
            }

            SectionCard("CPU") {
                StatRow("Uso total", s.cpuUsage?.let { "$it %" } ?: "—")
                if (s.policies.isEmpty()) Text("Sem dados de cpufreq", color = CDim)
                s.policies.forEach { p ->
                    val frac = if (p.hwMaxKhz > 0) (p.curKhz.toFloat() / p.hwMaxKhz).coerceIn(0f, 1f) else 0f
                    StatRow(
                        "Cluster ${p.id} · ${p.governor}",
                        "${p.curKhz / 1000} / ${p.maxKhz / 1000} MHz"
                    )
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            SectionCard("GPU (Adreno)") {
                StatRow("Uso", s.gpuBusy?.let { "$it %" } ?: "—")
                StatRow("Frequência", s.gpuClkHz?.let { "${it / 1_000_000} MHz" } ?: "—")
            }

            SectionCard("Memória e bateria") {
                if (s.memTotalKb > 0) {
                    val used = s.memTotalKb - s.memAvailKb
                    StatRow("RAM em uso", "${used / 1024} / ${s.memTotalKb / 1024} MB")
                    LinearProgressIndicator(
                        progress = { (used.toFloat() / s.memTotalKb).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                StatRow("Bateria", s.batPct?.let { "$it %" } ?: "—")
                StatRow("Estado", s.batStatus ?: "—")
                StatRow("Corrente", s.batCurrentMa?.let { "$it mA" } ?: "—")
            }

            ZonesCard(s.zones)
        }
    }
}

@Composable
private fun GamesTab(onLaunch: (AppEntry, PerfMode) -> Unit) {
    val ctx = LocalContext.current
    val store = remember { GameStore(ctx) }
    var games by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var all by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var version by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var picker by remember { mutableStateOf(false) }

    LaunchedEffect(version) {
        val r = withContext(Dispatchers.IO) { Apps.load(ctx, store.added()) }
        all = r.first
        games = r.second
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Biblioteca", style = MaterialTheme.typography.titleLarge)
            FilledTonalButton(onClick = { picker = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Adicionar")
            }
        }
        Spacer(Modifier.height(12.dp))

        if (loading) {
            CircularProgressIndicator()
        } else if (games.isEmpty()) {
            Text("Nenhum jogo detectado. Toque em Adicionar para escolher apps.", color = CDim)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(games, key = { it.pkg }) { g ->
                    GameRow(
                        g, store, onLaunch,
                        onRemove = {
                            store.remove(g.pkg)
                            version++
                        }
                    )
                }
            }
        }
    }

    if (picker) {
        AlertDialog(
            onDismissRequest = { picker = false },
            confirmButton = { TextButton(onClick = { picker = false }) { Text("Fechar") } },
            title = { Text("Adicionar app") },
            text = {
                LazyColumn(Modifier.height(380.dp)) {
                    items(all, key = { it.pkg }) { a ->
                        Text(
                            a.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    store.add(a.pkg)
                                    version++
                                    picker = false
                                }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun GameRow(
    g: AppEntry,
    store: GameStore,
    onLaunch: (AppEntry, PerfMode) -> Unit,
    onRemove: () -> Unit
) {
    var gm by remember(g.pkg) { mutableStateOf(store.modeFor(g.pkg)) }

    Card(colors = CardDefaults.cardColors(containerColor = CCard)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (g.icon != null) {
                Image(bitmap = g.icon, contentDescription = null, modifier = Modifier.size(44.dp))
            } else {
                Spacer(Modifier.size(44.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(g.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                // toque para alternar o modo deste jogo
                Text(
                    "Modo: ${gm.label}",
                    color = CAccent,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable {
                        val modes = PerfMode.values()
                        val next = modes[(gm.ordinal + 1) % modes.size]
                        store.setMode(g.pkg, next)
                        gm = next
                    }.padding(vertical = 6.dp)
                )
            }
            if (g.userAdded) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remover", tint = CDim)
                }
            }
            FilledIconButton(onClick = { onLaunch(g, gm) }) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Jogar")
            }
        }
    }
}

@Composable
private fun ToolsTab(onMode: (PerfMode) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rates = remember { DisplayCtl.rates(ctx) }
    var curRate by remember { mutableStateOf<Int?>(null) }
    var node by remember { mutableStateOf<Charging.Node?>(null) }
    var nodeChecked by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var cleaning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        curRate = DisplayCtl.current()
        val n = Charging.detect()
        node = n
        if (n != null) paused = Charging.isPaused(n)
        nodeChecked = true
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OverlayCard()

        SectionCard("Taxa de atualização") {
            if (rates.size <= 1) {
                Text("Esta tela só oferece uma taxa.", color = CDim)
            } else {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rates.forEach { r ->
                        FilterChip(
                            selected = curRate == r,
                            onClick = {
                                scope.launch {
                                    DisplayCtl.set(r)
                                    curRate = r
                                }
                            },
                            label = { Text("$r Hz") }
                        )
                    }
                }
            }
        }

        SectionCard("Carregamento") {
            val n = node
            if (!nodeChecked) {
                Text("Verificando…", color = CDim)
            } else if (n == null) {
                Text("Nenhum controle de carga conhecido neste kernel.", color = CDim)
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Pausar carregamento")
                        Text(
                            "Evita aquecer a bateria enquanto joga na tomada.",
                            color = CDim,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = paused,
                        onCheckedChange = { v ->
                            scope.launch {
                                Charging.set(n, v)
                                paused = Charging.isPaused(n)
                            }
                        }
                    )
                }
            }
        }

        SectionCard("Memória") {
            Button(
                enabled = !cleaning,
                onClick = {
                    scope.launch {
                        cleaning = true
                        val mb = Memory.clean()
                        cleaning = false
                        Toast.makeText(ctx, "Liberados ~$mb MB", Toast.LENGTH_SHORT).show()
                    }
                }
            ) { Text(if (cleaning) "Limpando…" else "Limpar RAM") }
        }

        SectionCard("Restaurar") {
            Text(
                "Volta ao modo Equilibrado e retoma o carregamento. Reiniciar o aparelho também desfaz tudo.",
                color = CDim,
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = {
                onMode(PerfMode.BALANCED)
                val n = node
                if (n != null) scope.launch {
                    Charging.set(n, false)
                    paused = Charging.isPaused(n)
                }
            }) { Text("Restaurar padrões") }
        }
    }
}
