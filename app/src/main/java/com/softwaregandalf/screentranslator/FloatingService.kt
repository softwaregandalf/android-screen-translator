package com.softwaregandalf.screentranslator

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View

    override fun onBind(intent: Intent?): IBinder? {
        return null // Arka plan servisi olduğu için bind işlemi kullanmıyoruz
    }

    override fun onCreate() {
        super.onCreate()

        // Ekrana basma motorunu (WindowManager) çağırıyoruz
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Az önce çizdiğimiz o XML buton tasarımını koda çevirme
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_widget, null)

        // Ekranın neresinde, nasıl duracağının profesyonel ayarları
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, // Diğer tüm uygulamaların üstünde durması için şart!
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, // Altındaki uygulamaya (Instagram vs) dokunabilmen için
            PixelFormat.TRANSLUCENT
        )

        // Başlangıç pozisyonu: Ekranın sol üst köşesine yakın bir yer
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100

        // Ve butonu ekrana çakıyoruz
        windowManager.addView(floatingView, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Servis kapanırsa butonu ekrandan temizle
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }
}