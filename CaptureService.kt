package com.candle.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var root: LinearLayout? = null
    private var tv: TextView? = null
    private var worker: HandlerThread? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastRun = 0L
    private var bmp: Bitmap? = null
    private var px = IntArray(0)
    private var current: Output? = null
    private var screenW = 1080
    @Volatile private var alive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (alive) return START_NOT_STICKY
        startForegroundCompat()
        val code = intent.getIntExtra("code", 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra("data")
        }
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        try {
            alive = true
            beginCapture(code, data)
        } catch (e: Exception) {
            alive = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("cap", "التحليل", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(
            this, 0, Intent(this, CaptureService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAct = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "إيقاف", stopPi
        ).build()
        val n: Notification = Notification.Builder(this, "cap")
            .setContentTitle("محلل الشموع يعمل")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(stopAct)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            Pair(m.widthPixels, m.heightPixels)
        }
    }

    private fun beginCapture(code: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { if (alive) stopSelf() }
        }, main)

        val (w, h) = screenSize()
        screenW = w
        val dpi = resources.displayMetrics.densityDpi
        val t = HandlerThread("analyze").also { it.start() }
        worker = t
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        display = proj.createVirtualDisplay(
            "cap", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )
        r.setOnImageAvailableListener({ rd -> onImage(rd) }, Handler(t.looper))
        showOverlay()
    }

    private fun onImage(rd: ImageReader) {
        val img = try { rd.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            if (!alive) return
            val cfg = getSharedPreferences("cfg", Context.MODE_PRIVATE)
            val sens = cfg.getInt("sens", 3).coerceIn(1, 5)
            val interval = (2600 - sens * 400).toLong()
            val now = System.currentTimeMillis()
            if (now - lastRun < interval) return
            lastRun = now
            val topPct = cfg.getInt("top", 12).coerceIn(0, 95)
            val botPct = cfg.getInt("bottom", 60).coerceIn(topPct + 3, 100)
            val tf = cfg.getInt("tf", 60).coerceAtLeast(1)
            val minConf = cfg.getInt("conf", 60).coerceIn(50, 95)

            val plane = img.planes[0]
            val rowPixels = plane.rowStride / plane.pixelStride
            var b = bmp
            if (b == null || b.width != rowPixels || b.height != img.height) {
                b?.recycle()
                b = Bitmap.createBitmap(rowPixels, img.height, Bitmap.Config.ARGB_8888)
                bmp = b
            }
            plane.buffer.rewind()
            b!!.copyPixelsFromBuffer(plane.buffer)
            val w = img.width
            val top = img.height * topPct / 100
            val bandH = img.height * botPct / 100 - top
            if (px.size != w * bandH) px = IntArray(w * bandH)
            b.getPixels(px, 0, w, 0, top, w, bandH)

            val read = CandleReader.read(px, w, bandH)
            // لا نحلل آخر شمعة لأنها قد تكون قيد التكوين.
            val closedCandles = if (read.candles.size > 1) read.candles.dropLast(1) else emptyList()
            val out = if (closedCandles.size < 40 || read.quality < 2.0) {
                Output(
                    "قراءة غير كافية/غير موثوقة: ${closedCandles.size}/40 شمعة\n" +
                        "${read.message}\nعدّل حدود الشارت، أخفِ المؤشرات المتداخلة، وتأكد من ألوان الشموع",
                    Color.DKGRAY, 0, DoubleArray(Learner.N), 0.5
                )
            } else {
                Signal.compute(
                    closedCandles, tf, minConf, sens,
                    Learner.weights(this), Learner.bias(this)
                )
            }
            current = out
            main.post {
                tv?.text = out.text + "\nقراءة بصرية تقريبية | جودة التقسيم: ${read.quality.toInt()}%"
                tv?.setBackgroundColor(out.color)
            }
        } catch (e: Exception) {
            main.post { tv?.text = "خطأ في التحليل" }
        } finally {
            img.close()
        }
    }

    private fun showOverlay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 25, 25, 25))
            setPadding(8, 8, 8, 8)
            minimumWidth = (screenW * 0.86).toInt()
        }
        val label = TextView(this).apply {
            text = "جاري التحليل..."
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.DKGRAY)
            textSize = 13f
            setPadding(24, 14, 24, 14)
        }
        box.addView(label)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, bg: Int, f: () -> Unit) {
            val b = TextView(this).apply {
                text = t
                setTextColor(Color.WHITE)
                setBackgroundColor(bg)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(10, 16, 10, 16)
                setOnClickListener { f() }
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(4, 8, 4, 0)
            row.addView(b, lp)
        }
        btn("دخلت", Color.rgb(30, 90, 180)) {
            val o = current
            if (o != null && o.side != 0) {
                Learner.setPending(this, o)
                Toast.makeText(this, "سُجّلت الصفقة، سجّل نتيجتها بعد انتهائها", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "لا توجد إشارة حالياً", Toast.LENGTH_SHORT).show()
            }
        }
        btn("ربحت", Color.rgb(0, 140, 70)) {
            val learned = Learner.record(this, true)
            Toast.makeText(this, if (learned) "سُجّل الربح وتعلّم النظام" else "سُجّل الربح", Toast.LENGTH_SHORT).show()
        }
        btn("خسرت", Color.rgb(200, 40, 40)) {
            val learned = Learner.record(this, false)
            Toast.makeText(this, if (learned) "سُجّلت الخسارة وتعلّم النظام" else "سُجّلت الخسارة", Toast.LENGTH_SHORT).show()
        }
        btn("إيقاف", Color.GRAY) { stopSelf() }
        box.addView(row)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = 120
        wm.addView(box, lp)
        root = box
        tv = label
    }

    override fun onDestroy() {
        alive = false
        try { reader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        worker?.quitSafely()
        root?.let {
            try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {}
        }
        root = null; tv = null
        try { bmp?.recycle() } catch (_: Exception) {}
        bmp = null
        @Suppress("DEPRECATION") stopForeground(true)
        super.onDestroy()
    }
}
