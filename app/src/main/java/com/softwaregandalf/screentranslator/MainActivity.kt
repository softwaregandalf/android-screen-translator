package com.softwaregandalf.screentranslator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() { // İŞTE ÇÖZÜM BURADA: Artık temalardan bağımsızız!

    // Ekran Kaydı İzni İçin Modern Fırlatıcı (Launcher)
    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Kullanıcı ekran kaydı iznini verdi, motoru çalıştır!
            startFloatingService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Çeviri için ekran okuma izni zorunludur!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnStartSetup = findViewById<Button>(R.id.btn_start_setup)

        btnStartSetup.setOnClickListener {
            checkAndRequestPermissions()
        }
    }

    private fun checkAndRequestPermissions() {
        // 1. Adım: "Diğer Uygulamaların Üzerinde Gösterim" (Overlay) izni kontrolü
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Lütfen listeden uygulamamızı bulup izni açın.", Toast.LENGTH_LONG).show()
            return
        }

        // 2. Adım: Overlay izni tamamsa, Ekran Kaydı (MediaProjection) iznini iste
        requestScreenCapturePermission()
    }

    private fun requestScreenCapturePermission() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startFloatingService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, FloatingService::class.java).apply {
            putExtra("RESULT_CODE", resultCode)
            putExtra("DATA", data)
        }

        // Android 8 (Oreo) ve üzeri için Foreground Service olarak başlatmak zorunludur
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Servis başarıyla başladı, bu kurulum ekranını kapatıp kullanıcıyı rahat bırakıyoruz
        finish()
    }
}