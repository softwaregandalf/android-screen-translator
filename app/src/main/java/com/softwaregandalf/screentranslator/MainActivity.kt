package com.softwaregandalf.screentranslator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private lateinit var spinnerTargetLang: Spinner

    // Global Kullanıcı Kitlesi İçin Temel Dil Havuzu
    private val languageNames = arrayOf("Türkçe", "English (İngilizce)", "Español (İspanyolca)", "Deutsch (Almanca)", "Français (Fransızca)", "日本語 (Japonca)", "Русский (Rusça)", "العربية (Arapça)")
    // Google ML Kit'in anlayacağı BCP-47 dil kodları (Sıralama üsttekiyle aynı olmak ZORUNDA)
    private val languageCodes = arrayOf("tr", "en", "es", "de", "fr", "ja", "ru", "ar")

    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startFloatingService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Çeviri için ekran okuma izni zorunludur!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinnerTargetLang = findViewById(R.id.spinner_target_language)
        val btnStartSetup = findViewById<Button>(R.id.btn_start_setup)

        // Spinner (Açılır menü) Kurulumu
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languageNames)
        spinnerTargetLang.adapter = adapter

        // Önceden seçilmiş bir dil varsa (hafızadan) onu getir, yoksa varsayılan 0 (Türkçe) olsun
        val prefs = getSharedPreferences("ST_PREFS", Context.MODE_PRIVATE)
        val savedIndex = prefs.getInt("TARGET_LANG_INDEX", 0)
        spinnerTargetLang.setSelection(savedIndex)

        btnStartSetup.setOnClickListener {
            saveSelectedLanguage()
            checkAndRequestPermissions()
        }
    }

    // --- İŞTE GLOBALLEŞME MÜHRÜ: SEÇİLEN DİLİ HAFIZAYA YAZ ---
    private fun saveSelectedLanguage() {
        val selectedIndex = spinnerTargetLang.selectedItemPosition
        val selectedCode = languageCodes[selectedIndex]

        val prefs = getSharedPreferences("ST_PREFS", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("TARGET_LANG_INDEX", selectedIndex)
            .putString("TARGET_LANG_CODE", selectedCode) // FloatingService bu kodu okuyacak!
            .apply()
    }

    private fun checkAndRequestPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Lütfen listeden uygulamamızı bulup izni açın.", Toast.LENGTH_LONG).show()
            return
        }

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        finish()
    }
}