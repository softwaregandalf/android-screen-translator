package com.softwaregandalf.screentranslator

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Uygulama açıldığında ilk iş: Üstte gösterme izni var mı diye kontrol et
        if (!Settings.canDrawOverlays(this)) {
            // İzin yoksa, kullanıcıyı telefonun ayarlar sayfasına yolla
            Toast.makeText(this, "Lütfen uygulamanın diğer uygulamaların üzerinde görünmesine izin ver!", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, 1001)
        } else {
            // İzin zaten varsa, direkt motoru çalıştır
            startFloatingService()
        }
    }

    // Ayarlardan (izin ekranından) geri dönünce izni verip vermediğini kontrol eden mekanizma
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            if (Settings.canDrawOverlays(this)) {
                startFloatingService()
            } else {
                Toast.makeText(this, "İzin vermeden butonu ekrana basamayız", Toast.LENGTH_SHORT).show()
                finish() // İzin vermezse uygulamayı kapat
            }
        }
    }

    private fun startFloatingService() {
        val serviceIntent = Intent(this, FloatingService::class.java)
        startService(serviceIntent)
        // Servisi başlattıktan sonra ana uygulamayı kapatıp ekranı temiz bırakıyoruz, buton kendi başına yaşayacak
        finish()
    }
}