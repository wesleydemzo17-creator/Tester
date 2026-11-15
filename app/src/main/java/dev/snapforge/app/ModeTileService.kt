package dev.snapforge.app

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Bloco das Configurações Rápidas: cada toque passa para o próximo modo. */
class ModeTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val modes = PerfMode.values()
        val next = modes[(Prefs.mode(this).ordinal + 1) % modes.size]
        scope.launch {
            if (Perf.apply(next)) Prefs.setMode(this@ModeTileService, next)
            refresh()
        }
    }

    private fun refresh() {
        val t = qsTile ?: return
        val m = Prefs.mode(this)
        t.label = m.label
        t.state = if (m == PerfMode.BALANCED) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        t.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
