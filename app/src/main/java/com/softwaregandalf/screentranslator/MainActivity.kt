package com.softwaregandalf.screentranslator

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    // Ekran yakalama iznini ekrana getirecek olan modern fırlatıcı
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            // Kullanıcı ekran yakalamaya onay verdi! Bileti servise yolluyoruz.
            startFloatingService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Ekran okuma izni verilmedi, uygulama çalışamaz usta.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // 1. Aşama: Üstte görünme izni kontrolü
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Önce diğer uygulamaların üzerinde görünme iznini ver usta!", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, 1001)
        } else {
            // İzin zaten varsa doğrudan 2. Aşamaya (Ekran Yakalama iznine) geç
            askForScreenCapturePermission()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            if (Settings.canDrawOverlays(this)) {
                // Üstte görünme izni alındı, şimdi ekran yakalama iznini iste
                askForScreenCapturePermission()
            } else {
                Toast.makeText(this, "Üstte görünme izni şart!", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    // 2. Aşama: Ekrana o meşhur "Kayıt başlasın mı?" uyarısını çıkaran fonksiyon
    private fun askForScreenCapturePermission() {
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(captureIntent)
    }

    // 3. Aşama: Her iki izin de tamamsa bileti (data) servise ver ve motoru çalıştır
    private fun startFloatingService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, FloatingService::class.java).apply {
            putExtra("RESULT_CODE", resultCode)
            putExtra("DATA", data)
        }
        startService(serviceIntent)
        finish() // Ana ekranı kapat, buton sahnede kalsın
    }
}