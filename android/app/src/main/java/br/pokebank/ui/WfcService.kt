package br.pokebank.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import br.pokebank.wfc.RootRedirect
import br.pokebank.wfc.WfcServer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Estado do servidor WFC compartilhado entre o servico e a interface. */
object WfcRuntime {
    @Volatile var server: WfcServer? = null
    @Volatile var redirectActive = false
    @Volatile var lastError: String? = null
    val log = CopyOnWriteArrayList<String>()
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun add(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        log.add(0, "$t  $msg")
        while (log.size > 500) log.removeAt(log.size - 1)
        listeners.forEach { runCatching { it() } }
    }

    val running: Boolean get() = server?.running == true
}

/**
 * Servico em primeiro plano: mantem o servidor WFC vivo enquanto voce joga no melonDS
 * (o Android congela apps em segundo plano). Ao parar, remove as regras de iptables.
 */
class WfcService : Service() {
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        goForeground()
        if (WfcRuntime.server == null) {
            Thread {
                try {
                    val srv = WfcServer(filesDir, WfcRuntime::add)
                    srv.start()
                    WfcRuntime.server = srv
                    WfcRuntime.lastError = null
                    val (ok, out) = RootRedirect.runAsRoot(RootRedirect.enableCommands(srv.dnsPort, srv.httpPort))
                    WfcRuntime.redirectActive = ok
                    WfcRuntime.add(if (ok) "Root: portas 53 e 80 desviadas para o servidor" else "Root FALHOU: $out")
                    if (!ok) WfcRuntime.lastError = "Sem root ou iptables recusou: o DS nao vai achar o servidor nas portas 53/80."
                } catch (e: Exception) {
                    WfcRuntime.lastError = "Nao consegui iniciar: ${e.message}"
                    WfcRuntime.add(WfcRuntime.lastError!!)
                    stopSelf()
                }
                WfcRuntime.add("Servidor WFC " + if (WfcRuntime.running) "ATIVO" else "parado")
            }.start()
        }
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder: Notification.Builder = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Servidor WFC", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        val n: Notification = builder.setContentTitle("PokeBank: servidor WFC ativo")
            .setContentText("DS e emulador podem conectar. Toque no app para parar.")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pokebank:wfc").apply { setReferenceCounted(false); acquire() }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pokebank:wfc").apply { setReferenceCounted(false); acquire() }
    }

    override fun onDestroy() {
        val srv = WfcRuntime.server
        WfcRuntime.server = null
        Thread {
            runCatching { srv?.stop() }
            if (WfcRuntime.redirectActive) {
                RootRedirect.runAsRoot(RootRedirect.disableCommands(), listAfter = false)
                WfcRuntime.redirectActive = false
                WfcRuntime.add("Root: regras de iptables removidas")
            }
            WfcRuntime.add("Servidor WFC parado")
        }.start()
        runCatching { wifiLock?.release() }
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "wfc"
        const val ACTION_STOP = "br.pokebank.WFC_STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, WfcService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, WfcService::class.java)) }
    }
}
