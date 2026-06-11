package com.softwaregandalf.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
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
    private var mediaProjection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        // Android'in bizi öldürmemesi için zorunlu olan Ön Plan (Foreground) bildirimini başlatıyoruz
        createNotificationChannel()
        startForeground(1, createNotification())

        // Ekrana basma motorunu çağırıyoruz ve arayüzü koda döküyoruz
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

        // Buton tıklama olayını ve animasyonunu ayarlıyoruz
        val btnTranslate = floatingView.findViewById<Button>(R.id.btn_translate)
        btnTranslate.setOnClickListener {
            // 1. Yazıyı kısa tutuyoruz ki kutuya sığsın
            btnTranslate.text = "Aldım!"

            // 2. Material kurallarına uygun şekilde (TintList ile) rengi yeşile çeviriyoruz
            btnTranslate.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#4CAF50"))

            // Logcat'e tıklanma kaydı düşüyoruz
            println("BUTONA BASILDI - EKRAN YAKALAMA BAŞLAYACAK")

            // 3. Butonun kilitli kalmaması için 1.5 saniye sonra eski haline döndürüyoruz
            Handler(Looper.getMainLooper()).postDelayed({
                btnTranslate.text = "Çevir"
                btnTranslate.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF3B30"))
            }, 1500)
        }
    }

    // MainActivity'den fırlatılan bileti (Intent) burada yakalıyoruz
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val resultCode = intent.getIntExtra("RESULT_CODE", -1)
            val data: Intent? = intent.getParcelableExtra("DATA")

            if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                // Bileti MediaProjection'a çevirip ekran okuma yetkisini resmen elimize alıyoruz
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpm.getMediaProjection(resultCode, data)
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "SCREEN_TRANSLATOR_CHANNEL",
                "Ekran Çeviri Servisi",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "SCREEN_TRANSLATOR_CHANNEL")
            .setContentTitle("Ekran Çevirici Aktif")
            .setContentText("Buton arka planda çeviri için hazır bekliyor...")
            .setSmallIcon(R.mipmap.ic_launcher) // Varsayılan Android ikonunu kullandık
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Servis kapanırken sistemi temiz bırak
        mediaProjection?.stop()
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }
}