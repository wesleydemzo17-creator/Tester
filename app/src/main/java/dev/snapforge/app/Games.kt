package dev.snapforge.app

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

data class AppEntry(
    val pkg: String,
    val label: String,
    val icon: ImageBitmap?,
    val userAdded: Boolean
)

/** Biblioteca de jogos: apps marcados como jogo pelo sistema + os que o usuário adicionar. */
class GameStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("snapforge_games", Context.MODE_PRIVATE)

    fun added(): Set<String> = sp.getStringSet("added", emptySet())?.toSet() ?: emptySet()

    fun add(pkg: String) {
        sp.edit().putStringSet("added", added() + pkg).apply()
    }

    fun remove(pkg: String) {
        sp.edit().putStringSet("added", added() - pkg).apply()
    }

    fun modeFor(pkg: String): PerfMode {
        val name = sp.getString("m_$pkg", null)
        return PerfMode.values().firstOrNull { it.name == name } ?: PerfMode.PERFORMANCE
    }

    fun setMode(pkg: String, m: PerfMode) {
        sp.edit().putString("m_$pkg", m.name).apply()
    }
}

object Apps {
    /** Devolve (todos os apps abríveis, sem ícone) e (jogos, com ícone). */
    @Suppress("DEPRECATION")
    fun load(ctx: Context, added: Set<String>): Pair<List<AppEntry>, List<AppEntry>> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val all = ArrayList<AppEntry>()
        val games = ArrayList<AppEntry>()
        val seen = HashSet<String>()

        for (ri in pm.queryIntentActivities(intent, 0)) {
            val ai = ri.activityInfo?.applicationInfo ?: continue
            val pkg = ai.packageName
            if (pkg == ctx.packageName || !seen.add(pkg)) continue
            val label = ai.loadLabel(pm).toString()
            all.add(AppEntry(pkg, label, null, false))

            val isGame = ai.category == ApplicationInfo.CATEGORY_GAME ||
                (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            val isAdded = pkg in added
            if (isGame || isAdded) {
                val icon = try {
                    ai.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
                } catch (e: Exception) {
                    null
                }
                games.add(AppEntry(pkg, label, icon, isAdded))
            }
        }
        return Pair(all.sortedBy { it.label.lowercase() }, games.sortedBy { it.label.lowercase() })
    }

    fun launch(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }
}
