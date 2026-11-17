package com.example.autoclickimage

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.WindowManager
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    @Volatile private var running = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i == null || i.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        startFg()
        if (!OpenCVLoader.initLocal()) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        val data = i.getParcelableExtra<Intent>("data")!!
        projection = mpm.getMediaProjection(i.getIntExtra("code", 0), data)
        handlerThread = HandlerThread("cap").also { it.start() }
        val h = Handler(handlerThread!!.looper)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, h)

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        val w = dm.widthPixels; val hgt = dm.heightPixels
        reader = ImageReader.newInstance(w, hgt, PixelFormat.RGBA_8888, 2)
        display = projection!!.createVirtualDisplay("cap", w, hgt, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, h)

        running = true
        Thread { loop(w, hgt) }.start()
        return START_NOT_STICKY
    }

    private fun loop(w: Int, h: Int) {
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val thr = prefs.getInt("thr", 85) / 100.0
        val interval = prefs.getLong("interval", 700)
        val maxClicks = prefs.getInt("max", 0)
        val scale = 0.5
        val files = File(filesDir, "templates").listFiles()?.sorted() ?: emptyList()
        val templates = files.mapNotNull {
            val m = Imgcodecs.imread(it.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
            if (m.empty()) null else {
                Imgproc.resize(m, m, Size(), scale, scale, Imgproc.INTER_AREA); m
            }
        }
        var clicks = 0
        while (running) {
            val img = reader?.acquireLatestImage()
            if (img == null) { Thread.sleep(100); continue }
            val mat = Mat()
            try {
                val pl = img.planes[0]
                val rowPx = pl.rowStride / pl.pixelStride
                val bmp = Bitmap.createBitmap(rowPx, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(pl.buffer)
                val crop = Bitmap.createBitmap(bmp, 0, 0, w, h)
                Utils.bitmapToMat(crop, mat)
                bmp.recycle(); crop.recycle()
            } finally { img.close() }
            Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.resize(mat, mat, Size(), scale, scale, Imgproc.INTER_AREA)

            for (t in templates) {
                if (t.cols() > mat.cols() || t.rows() > mat.rows()) continue
                val res = Mat()
                Imgproc.matchTemplate(mat, t, res, Imgproc.TM_CCOEFF_NORMED)
                val mm = Core.minMaxLoc(res)
                res.release()
                if (mm.maxVal >= thr) {
                    val x = ((mm.maxLoc.x + t.cols() / 2.0) / scale).toFloat()
                    val y = ((mm.maxLoc.y + t.rows() / 2.0) / scale).toFloat()
                    ClickService.instance?.click(x, y)
                    clicks++
                    break
                }
            }
            mat.release()
            if (maxClicks in 1..clicks) { stopSelf(); break }
            try { Thread.sleep(interval) } catch (_: InterruptedException) {}
        }
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ac", "AutoClick", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0,
            Intent(this, CaptureService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "ac")
            .setContentTitle("AutoClick Image đang chạy")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(Notification.Action.Builder(null, "Dừng", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    override fun onDestroy() {
        running = false
        display?.release(); reader?.close(); projection?.stop(); handlerThread?.quitSafely()
        super.onDestroy()
    }
}
