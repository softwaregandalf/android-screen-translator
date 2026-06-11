package com.softwaregandalf.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import androidx.core.app.NotificationCompat

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var btnTranslate: Button
    private var mediaProjection: MediaProjection? = null

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, createNotification())
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_widget, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100

        windowManager.addView(floatingView, params)

        btnTranslate = floatingView.findViewById(R.id.btn_translate)
        btnTranslate.setOnClickListener {
            updateButtonUI("Okuyor...", "#4CAF50")
            captureScreen()
        }
    }

    private fun captureScreen() {
        if (mediaProjection == null) {
            updateButtonUI("Ehliyet Yok", "#B00020")
            println("GELİŞTİRİCİ UYARISI: MediaProjection (Ehliyet) hala null!")
            resetButtonDelayed()
            return
        }

        try {
            val metrics = Resources.getSystem().displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            virtualDisplay?.release()
            imageReader?.close()

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val image = imageReader?.acquireLatestImage()
                    if (image != null) {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * width

                        val bitmapWidth = width + rowPadding / pixelStride
                        val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
                        bitmap.copyPixelsFromBuffer(buffer)

                        updateButtonUI("RAM OK!", "#4CAF50")
                        println("GELİŞTİRİCİ ZAFERİ: Ekran başarıyla RAM'e düştü -> ${bitmap.width}x${bitmap.height}")

                        image.close()
                    } else {
                        updateButtonUI("Çevir", "#FF9800")
                        println("GELİŞTİRİCİ UYARISI: Kamera boş çekti!")
                    }
                } catch (e: Exception) {
                    updateButtonUI("Hata", "#B00020")
                    println("--- GELİŞTİRİCİ ACİL DURUM: Bitmap İşleme Hatası ---")
                    e.printStackTrace()
                } finally {
                    cleanupResources()
                    resetButtonDelayed()
                }
            }, 1000)

        } catch (e: SecurityException) {
            updateButtonUI("Hata", "#B00020")
            println("--- GELİŞTİRİCİ ACİL DURUM: Güvenlik İzni Koptu! ---")
            e.printStackTrace()
            cleanupResources()
            resetButtonDelayed()
        } catch (e: Exception) {
            updateButtonUI("Hata", "#B00020")
            println("--- GELİŞTİRİCİ ACİL DURUM: Sistem Çöktü! ---")
            e.printStackTrace()
            cleanupResources()
            resetButtonDelayed()
        }
    }

    private fun updateButtonUI(text: String, colorHex: String) {
        Handler(Looper.getMainLooper()).post {
            btnTranslate.text = text
            btnTranslate.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(colorHex))
        }
    }

    private fun resetButtonDelayed() {
        Handler(Looper.getMainLooper()).postDelayed({
            btnTranslate.text = "Çevir"
            btnTranslate.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF3B30"))
        }, 1500)
    }

    private fun cleanupResources() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val resultCode = intent.getIntExtra("RESULT_CODE", -1)

            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("DATA", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("DATA")
            }

            if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpm.getMediaProjection(resultCode, data)

                // İŞTE ÇÖZÜM BURADA: Android 14'ün zorunlu kıldığı Callback Zırhı!
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        super.onStop()
                        cleanupResources()
                        mediaProjection = null
                        println("SİSTEM BİLGİSİ: Ekran okuma izni sonlandırıldı.")
                    }
                }, Handler(Looper.getMainLooper()))
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("SCREEN_TRANSLATOR_CHANNEL", "Ekran Çeviri Servisi", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "SCREEN_TRANSLATOR_CHANNEL")
            .setContentTitle("Ekran Çevirici Aktif")
            .setContentText("Buton arka planda çeviri için hazır bekliyor...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupResources()
        mediaProjection?.stop()
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }
}