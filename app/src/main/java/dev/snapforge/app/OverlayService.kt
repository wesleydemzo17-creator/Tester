package dev.snapforge.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Preferências do overlay, editadas no app e lidas pelo serviço a cada segundo. */
object OverlayPrefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("snapforge_overlay", Context.MODE_PRIVATE)

    fun alpha(ctx: Context) = sp(ctx).getFloat("alpha", 0.85f)
    fun scale(ctx: Context) = sp(ctx).getFloat("scale", 1.0f)
    fun graph(ctx: Context) = sp(ctx).getBoolean("graph", true)
    fun buttons(ctx: Context) = sp(ctx).getBoolean("buttons", true)
    fun x(ctx: Context) = sp(ctx).getInt("x", 40)
    fun y(ctx: Context) = sp(ctx).getInt("y", 200)

    fun setAlpha(ctx: Context, v: Float) = sp(ctx).edit().putFloat("alpha", v).apply()
    fun setScale(ctx: Context, v: Float) = sp(ctx).edit().putFloat("scale", v).apply()
    fun setGraph(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("graph", v).apply()
    fun setButtons(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("buttons", v).apply()
    fun setPos(ctx: Context, x: Int, y: Int) = sp(ctx).edit().putInt("x", x).putInt("y", y).apply()
}

/** Mini gráfico de linha: CPU (verde) e GPU (azul), últimos 60 segundos. */
class GraphView(ctx: Context) : View(ctx) {
    private val cpu = ArrayDeque<Float>()
    private val gpu = ArrayDeque<Float>()
    private val path = Path()
    private val pCpu = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF39D98A.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val pGpu = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5EA8FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val pGrid = Paint().apply {
        color = 0x33FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    fun push(c: Float, g: Float) {
        cpu.addLast(c)
        gpu.addLast(g)
        while (cpu.size > MAX) cpu.removeFirst()
        while (gpu.size > MAX) gpu.removeFirst()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val w = width.toFloat()
        canvas.drawLine(0f, h / 2f, w, h / 2f, pGrid)
        canvas.drawRect(0f, 0f, w - 1f, h - 1f, pGrid)
        drawSeries(canvas, gpu, pGpu)
        drawSeries(canvas, cpu, pCpu)
    }

    private fun drawSeries(canvas: Canvas, data: ArrayDeque<Float>, p: Paint) {
        if (data.size < 2) return
        val w = width.toFloat()
        val h = height.toFloat()
        val step = w / (MAX - 1).toFloat()
        path.reset()
        var started = false
        for (i in data.indices) {
            val v = data[i]
            if (v < 0f) continue
            val x = w - (data.size - 1 - i) * step
            val y = h - 2f - (v.coerceIn(0f, 100f) / 100f) * (h - 4f)
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }
        if (started) canvas.drawPath(path, p)
    }

    companion object {
        const val MAX = 60
    }
}

/**
 * Overlay flutuante: uso/temperatura de CPU e GPU, mini gráfico,
 * botões de print (screencap) e gravação (screenrecord) via root.
 */
class OverlayService : Service() {
    companion object {
        @Volatile
        var running = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var text: TextView
    private lateinit var graph: GraphView
    private lateinit var buttons: LinearLayout
    private lateinit var recBtn: TextView
    private var root: LinearLayout? = null
    private var appliedScale = -1f
    private var recording = false
    private var recFile = ""
    private var recJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startFg()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (!Settings.canDrawOverlays(this)) {
            toast("Permissão de sobreposição negada")
            stopSelf()
            return
        }
        val r = build()
        try {
            wm.addView(r, lp)
        } catch (e: Exception) {
            toast("Não foi possível criar o overlay")
            stopSelf()
            return
        }
        root = r
        running = true
        scope.launch {
            while (isActive) {
                tick()
                delay(1000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        if (recording) Root.exec("pkill -INT screenrecord")
        scope.cancel()
        val r = root
        if (r != null) {
            try {
                wm.removeView(r)
            } catch (ignored: Exception) {
            }
        }
        root = null
        running = false
        super.onDestroy()
    }

    private fun startFg() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("overlay", "Overlay", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "overlay")
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("SnapForge")
            .setContentText("Overlay ativo")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun makeButton(label: String, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = label
        b.setTextColor(Color.WHITE)
        b.typeface = Typeface.DEFAULT_BOLD
        b.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.setColor(0x55FFFFFF)
        bg.cornerRadius = dp(6f).toFloat()
        b.background = bg
        b.setOnClickListener { onClick() }
        return b
    }

    private fun build(): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(0xE60E1116.toInt())
        bg.cornerRadius = dp(10f).toFloat()
        r.background = bg

        text = TextView(this)
        text.setTextColor(Color.WHITE)
        text.typeface = Typeface.MONOSPACE
        text.text = "…"

        graph = GraphView(this)

        buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        recBtn = makeButton("REC") { toggleRecord() }
        buttons.addView(makeButton("FOTO") { screenshot() })
        buttons.addView(recBtn)
        buttons.addView(makeButton("X") { stopSelf() })

        r.addView(text)
        r.addView(graph)
        r.addView(buttons)

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = OverlayPrefs.x(this)
        lp.y = OverlayPrefs.y(this)

        // arrastar para mover
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        r.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = e.rawX
                    touchY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - touchX).toInt()
                    lp.y = startY + (e.rawY - touchY).toInt()
                    try {
                        wm.updateViewLayout(v, lp)
                    } catch (ignored: Exception) {
                    }
                }
                MotionEvent.ACTION_UP -> OverlayPrefs.setPos(this, lp.x, lp.y)
            }
            true
        }

        applyStyle(r)
        return r
    }

    /** Aplica transparência, tamanho e visibilidade conforme as preferências. */
    private fun applyStyle(r: LinearLayout) {
        r.alpha = OverlayPrefs.alpha(this).coerceIn(0.15f, 1f)
        val s = OverlayPrefs.scale(this).coerceIn(0.5f, 2.5f)
        if (s != appliedScale) {
            appliedScale = s
            val pad = dp(6f * s)
            r.setPadding(pad, pad, pad, pad)
            text.textSize = 11f * s
            val glp = LinearLayout.LayoutParams(dp(150f * s), dp(46f * s))
            glp.topMargin = dp(4f * s)
            graph.layoutParams = glp
            for (i in 0 until buttons.childCount) {
                val b = buttons.getChildAt(i) as TextView
                b.textSize = 10f * s
                val blp = LinearLayout.LayoutParams(0, dp(26f * s), 1f)
                blp.topMargin = dp(4f * s)
                if (i > 0) blp.leftMargin = dp(4f * s)
                b.layoutParams = blp
            }
            buttons.layoutParams = LinearLayout.LayoutParams(
                dp(150f * s), LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        graph.visibility = if (OverlayPrefs.graph(this)) View.VISIBLE else View.GONE
        buttons.visibility = if (OverlayPrefs.buttons(this)) View.VISIBLE else View.GONE
    }

    private fun fmtTemp(t: Float?): String = if (t == null) "--" else t.toInt().toString() + "°"

    private suspend fun tick() {
        val r = root ?: return
        applyStyle(r)
        val s = Monitor.read() ?: return
        val cpu = s.cpuUsage
        val gpu = s.gpuBusy
        val cpuTxt = if (cpu == null) "--" else cpu.toString()
        val gpuTxt = if (gpu == null) "--" else gpu.toString()
        text.text = "CPU " + cpuTxt + "% " + fmtTemp(s.cpuTemp) +
            "\nGPU " + gpuTxt + "% " + fmtTemp(s.gpuTemp) +
            (if (recording) "\n● gravando" else "")
        graph.push(cpu?.toFloat() ?: -1f, gpu?.toFloat() ?: -1f)
    }

    private fun screenshot() {
        scope.launch {
            val r = root ?: return@launch
            val f = "/sdcard/Pictures/SnapForge/shot_" + System.currentTimeMillis() + ".png"
            r.visibility = View.INVISIBLE
            delay(250)
            Root.run(
                "mkdir -p /sdcard/Pictures/SnapForge\n" +
                    "screencap -p " + f + "\n" +
                    "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://" + f +
                    " >/dev/null 2>&1"
            )
            r.visibility = View.VISIBLE
            toast("Print salvo em Pictures/SnapForge")
        }
    }

    private fun toggleRecord() {
        scope.launch {
            if (recording) {
                stopRecord()
            } else {
                recFile = "/sdcard/Movies/SnapForge/rec_" + System.currentTimeMillis() + ".mp4"
                Root.run(
                    "mkdir -p /sdcard/Movies/SnapForge\n" +
                        "(screenrecord --bit-rate 12000000 " + recFile + " >/dev/null 2>&1 &)"
                )
                recording = true
                recBtn.text = "PARAR"
                // o screenrecord do Android para sozinho em 3 minutos
                recJob?.cancel()
                recJob = scope.launch {
                    delay(181_000)
                    if (recording) stopRecord()
                }
            }
        }
    }

    private suspend fun stopRecord() {
        recording = false
        recBtn.text = "REC"
        Root.run(
            "pkill -INT screenrecord\nsleep 1\n" +
                "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://" + recFile +
                " >/dev/null 2>&1"
        )
        toast("Vídeo salvo em Movies/SnapForge")
    }
}
