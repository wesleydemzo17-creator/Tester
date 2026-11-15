@file:OptIn(ExperimentalMaterial3Api::class)

package dev.snapforge.app

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Linha "rótulo — botão com menu suspenso". */
@Composable
internal fun Picker(label: String, current: String, options: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = CDim)
        Box {
            OutlinedButton(onClick = { open = true }) { Text(current) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, o ->
                    DropdownMenuItem(
                        text = { Text(o) },
                        onClick = {
                            open = false
                            onPick(i)
                        }
                    )
                }
            }
        }
    }
}

private fun mhz(khz: Long): String = (khz / 1000).toString() + " MHz"
private fun gpuMhz(hz: Long): String = (hz / 1_000_000).toString() + " MHz"

@Composable
internal fun TuningTab() {
    val scope = rememberCoroutineScope()
    var st by remember { mutableStateOf<TuningState?>(null) }

    LaunchedEffect(Unit) { st = Tuning.read() }

    // executa a alteração e relê o estado real do kernel
    val act: (suspend () -> Unit) -> Unit = { block ->
        scope.launch {
            block()
            st = Tuning.read()
        }
    }

    val s = st
    if (s == null) {
        Box(Modifier.fillMaxSize().padding(16.dp)) { Text("Lendo o kernel…", color = CDim) }
        return
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Os valores mostrados são sempre os que o kernel aceitou. Reiniciar desfaz tudo.",
            color = CDim,
            style = MaterialTheme.typography.bodySmall
        )

        SectionCard("Núcleos") {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                s.cores.forEach { c ->
                    FilterChip(
                        selected = c.online,
                        enabled = c.id != 0,
                        onClick = { act { Tuning.setCore(c.id, !c.online) } },
                        label = { Text("CPU" + c.id) }
                    )
                }
            }
            Text(
                "CPU0 não pode ser desligado. Com um cluster inteiro desligado, ele some da lista abaixo.",
                color = CDim,
                style = MaterialTheme.typography.bodySmall
            )
        }

        s.clusters.forEach { c ->
            SectionCard("Cluster " + c.id + " · CPUs " + c.cpus) {
                val labels = c.freqs.map { mhz(it) }
                Picker("Frequência mínima", mhz(c.minKhz), labels) { i ->
                    act { Tuning.setClusterMin(c, c.freqs[i]) }
                }
                Picker("Frequência máxima", mhz(c.maxKhz), labels) { i ->
                    act { Tuning.setClusterMax(c, c.freqs[i]) }
                }
                if (c.governors.isNotEmpty()) {
                    Picker("Governor", c.governor, c.governors) { i ->
                        act { Tuning.setClusterGovernor(c, c.governors[i]) }
                    }
                }
            }
        }

        val g = s.gpu
        SectionCard("GPU Adreno") {
            if (g == null) {
                Text("GPU kgsl não encontrada neste kernel.", color = CDim)
            } else {
                val labels = g.freqsHz.mapIndexed { i, hz ->
                    gpuMhz(hz) + when (i) {
                        0 -> " (turbo)"
                        g.freqsHz.size - 1 -> " (mín.)"
                        else -> ""
                    }
                }
                val maxIdx = g.maxLevel.coerceIn(0, g.freqsHz.size - 1)
                val minIdx = g.minLevel.coerceIn(0, g.freqsHz.size - 1)
                Picker("Frequência máxima", gpuMhz(g.freqsHz[maxIdx]), labels) { i ->
                    act { Tuning.setGpuMax(g, i) }
                }
                Picker("Frequência mínima", gpuMhz(g.freqsHz[minIdx]), labels) { i ->
                    act { Tuning.setGpuMin(g, i) }
                }
                if (g.governors.isNotEmpty()) {
                    Picker("Governor", g.governor, g.governors) { i ->
                        act { Tuning.setGpuGovernor(g.governors[i]) }
                    }
                }
            }
        }

        OutlinedButton(onClick = { act { } }) { Text("Atualizar leitura") }
    }
}

/** Liga/desliga o overlay e edita transparência, tamanho, gráfico e botões. */
@Composable
internal fun OverlayCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(OverlayService.running) }
    var alpha by remember { mutableFloatStateOf(OverlayPrefs.alpha(ctx)) }
    var scale by remember { mutableFloatStateOf(OverlayPrefs.scale(ctx)) }
    var graph by remember { mutableStateOf(OverlayPrefs.graph(ctx)) }
    var buttons by remember { mutableStateOf(OverlayPrefs.buttons(ctx)) }

    // o overlay pode ser fechado pelo próprio botão X
    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            on = OverlayService.running
        }
    }

    SectionCard("Overlay flutuante") {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Mostrar overlay")
                Text(
                    "Uso e temperatura de CPU/GPU, gráfico, print e gravação. Arraste para mover.",
                    color = CDim,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = on,
                onCheckedChange = { v ->
                    if (v) {
                        scope.launch {
                            val pkg = ctx.packageName
                            Root.run(
                                "appops set " + pkg + " SYSTEM_ALERT_WINDOW allow\n" +
                                    "pm grant " + pkg + " android.permission.POST_NOTIFICATIONS 2>/dev/null"
                            )
                            ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
                            delay(800)
                            on = OverlayService.running
                        }
                    } else {
                        ctx.stopService(Intent(ctx, OverlayService::class.java))
                        on = false
                    }
                }
            )
        }

        Text("Transparência: " + (alpha * 100).toInt() + "% opaco", color = CDim)
        Slider(
            value = alpha,
            onValueChange = {
                alpha = it
                OverlayPrefs.setAlpha(ctx, it)
            },
            valueRange = 0.2f..1f
        )

        Text("Tamanho: " + (scale * 100).toInt() + "%", color = CDim)
        Slider(
            value = scale,
            onValueChange = {
                scale = it
                OverlayPrefs.setScale(ctx, it)
            },
            valueRange = 0.6f..2f
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Mini gráfico")
            Switch(checked = graph, onCheckedChange = {
                graph = it
                OverlayPrefs.setGraph(ctx, it)
            })
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Botões de print e gravação")
            Switch(checked = buttons, onCheckedChange = {
                buttons = it
                OverlayPrefs.setButtons(ctx, it)
            })
        }
    }
}

/** Lista todas as zonas térmicas, da mais quente para a mais fria. */
@Composable
internal fun ZonesCard(zones: List<Pair<String, Float>>) {
    var open by remember { mutableStateOf(false) }
    SectionCard("Todos os sensores (" + zones.size + ")") {
        TextButton(onClick = { open = !open }) {
            Text(if (open) "Ocultar" else "Mostrar")
        }
        if (open) {
            zones.sortedByDescending { it.second }.forEach { z ->
                StatRow(z.first, String.format("%.1f °C", z.second))
            }
        }
    }
}
