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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationCompat

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var dismissView: View // Ekranın altındaki X bölgesi

    private lateinit var cardTranslateBtn: androidx.cardview.widget.CardView
    private lateinit var tvBtnText: TextView

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var resultView: View? = null

    private var isCaptureRequested = false

    // Sürükle Bırak (Fizik Motoru) Değişkenleri
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var touchStartTime = 0L

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

        // 1. Kapatma Bölgesini (Dismiss Target) Gizli Olarak Ekliyoruz
        dismissView = LayoutInflater.from(this).inflate(R.layout.layout_dismiss_target, null)
        val dismissParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        dismissParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        dismissView.visibility = View.GONE
        windowManager.addView(dismissView, dismissParams)

        // 2. Ana Yüzen Butonu Ekliyoruz
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

        cardTranslateBtn = floatingView.findViewById(R.id.card_translate_btn)
        tvBtnText = floatingView.findViewById(R.id.tv_btn_text)

        // --- UX DEHASI: SÜRÜKLE BIRAK (DRAG AND DROP) FİZİK MOTORU ---
        cardTranslateBtn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Ekrana ilk dokunulduğu anki pozisyonları kaydet
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()

                    // Alttaki kapatma bölgesini görünür yap
                    dismissView.visibility = View.VISIBLE
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Parmağı hareket ettirdikçe butonu peşinden sürükle
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, params)

                    // Eğer buton ekranın alt %75'lik kısmına (çöpe yaklaştıysa) kırmızı bölgeyi parlat
                    if (params.y > Resources.getSystem().displayMetrics.heightPixels * 0.75) {
                        dismissView.findViewById<View>(R.id.card_dismiss).alpha = 1.0f
                    } else {
                        dismissView.findViewById<View>(R.id.card_dismiss).alpha = 0.5f
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Parmağı ekrandan çektiğimiz an X bölgesini sakla
                    dismissView.visibility = View.GONE

                    // Bu bir tıklama mı yoksa sürükleme mi? (200 milisaniyeden kısa ve yerinden oynamamışsa TIKLAMADIR)
                    val clickDuration = System.currentTimeMillis() - touchStartTime
                    val isClick = clickDuration < 200 && Math.abs(event.rawX - initialTouchX) < 15 && Math.abs(event.rawY - initialTouchY) < 15

                    if (isClick) {
                        // Tıkladı: Çeviriyi Başlat!
                        updateButtonUI("Okuyor..", "#4CAF50")
                        isCaptureRequested = true
                    } else {
                        // Sürükledi ve bıraktı: Eğer ekranın alt çöp bölgesine bıraktıysa SİSTEMİ KAPAT!
                        if (params.y > Resources.getSystem().displayMetrics.heightPixels * 0.75) {
                            stopSelf()
                        }
                    }
                    true
                }
                else -> false
            }
        }
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

                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        super.onStop()
                        cleanupResources()
                        mediaProjection = null
                    }
                }, Handler(Looper.getMainLooper()))

                setupContinuousVirtualDisplay()
            }
        }
        return START_NOT_STICKY
    }

    private fun setupContinuousVirtualDisplay() {
        try {
            val metrics = Resources.getSystem().displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )

            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    if (isCaptureRequested) {
                        isCaptureRequested = false

                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * width

                        val bitmapWidth = width + rowPadding / pixelStride
                        val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
                        bitmap.copyPixelsFromBuffer(buffer)

                        val finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)

                        processImageWithAI(finalBitmap)
                    }
                    image.close()
                }
            }, Handler(Looper.getMainLooper()))

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun processImageWithAI(bitmap: Bitmap) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->

                val smartTextBuilder = StringBuilder()
                val blocks = visionText.textBlocks

                for (block in blocks) {
                    val cleanBlock = block.text.replace("\n", " ").trim()
                    val wordCount = cleanBlock.split("\\s+".toRegex()).size

                    val isSystemUI = cleanBlock.matches(".*\\d{1,2}:\\d{2}.*".toRegex()) ||
                            cleanBlock.matches(".*%\\d+.*".toRegex()) ||
                            cleanBlock.contains("5G") || cleanBlock.contains("LTE")

                    val socialGarbageWords = listOf("paylaş", "beğen", "yorum", "kaydet", "share", "like", "comment", "save", "reels", "gönder", "takip", "follow", "müzik", "ses", "orijinal", "abone")
                    val isSocialButton = socialGarbageWords.any { cleanBlock.lowercase().contains(it) }

                    val isTooShort = wordCount < 3

                    if (!isSystemUI && !isSocialButton && !isTooShort) {
                        smartTextBuilder.append(cleanBlock).append(" ")
                    }
                }

                val finalSmartText = smartTextBuilder.toString().trim()

                if (finalSmartText.isNotBlank()) {
                    detectLanguageAndTranslate(finalSmartText)
                } else {
                    updateButtonUI("Metin Yok", "#FF9800")
                    resetButtonDelayed()
                }
            }
            .addOnFailureListener {
                updateButtonUI("OCR Hata", "#B00020")
                resetButtonDelayed()
            }
    }

    private fun detectLanguageAndTranslate(text: String) {
        val languageIdentifier = LanguageIdentification.getClient()
        languageIdentifier.identifyLanguage(text)
            .addOnSuccessListener { languageCode ->
                if (languageCode == "und") {
                    updateButtonUI("Dil Bulunamadı", "#FF9800")
                    resetButtonDelayed()
                } else if (languageCode == "tr") {
                    updateButtonUI("Zaten TR", "#4CAF50")
                    showTranslationResult(text, "tr")
                    resetButtonDelayed()
                } else {
                    translateDynamic(text, languageCode)
                }
            }
            .addOnFailureListener {
                updateButtonUI("Dil Hata", "#B00020")
                resetButtonDelayed()
            }
    }

    private fun translateDynamic(text: String, sourceLangCode: String) {
        updateButtonUI("Çevriliyor..", "#8AB4F8")

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLangCode)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
        val translator = Translation.getClient(options)

        val conditions = DownloadConditions.Builder().build()

        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                translator.translate(text)
                    .addOnSuccessListener { translatedText ->
                        updateButtonUI("Başarılı!", "#4CAF50")
                        showTranslationResult(translatedText, sourceLangCode)
                        resetButtonDelayed()
                    }
                    .addOnFailureListener {
                        updateButtonUI("Çeviri Hata", "#B00020")
                        resetButtonDelayed()
                    }
            }
            .addOnFailureListener {
                updateButtonUI("İndirme Hata", "#B00020")
                resetButtonDelayed()
            }
    }

    private fun showTranslationResult(text: String, sourceLang: String) {
        Handler(Looper.getMainLooper()).post {
            if (resultView != null) {
                windowManager.removeView(resultView)
                resultView = null
            }

            resultView = LayoutInflater.from(this).inflate(R.layout.layout_translation_result, null)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.CENTER

            val tvLanguageInfo = resultView!!.findViewById<TextView>(R.id.tv_language_info)
            val tvTranslatedText = resultView!!.findViewById<TextView>(R.id.tv_translated_text)
            val btnClose = resultView!!.findViewById<Button>(R.id.btn_close_result)

            tvLanguageInfo.text = "ÇEVİRİ [${sourceLang.uppercase()} -> TR]"
            tvTranslatedText.text = text

            btnClose.setOnClickListener {
                windowManager.removeView(resultView)
                resultView = null
            }

            windowManager.addView(resultView, params)
        }
    }

    private fun updateButtonUI(text: String, colorHex: String) {
        Handler(Looper.getMainLooper()).post {
            tvBtnText.text = text
            cardTranslateBtn.setCardBackgroundColor(android.graphics.Color.parseColor(colorHex))
        }
    }

    private fun resetButtonDelayed() {
        Handler(Looper.getMainLooper()).postDelayed({
            tvBtnText.text = "Çevir"
            cardTranslateBtn.setCardBackgroundColor(android.graphics.Color.parseColor("#1A73E8"))
        }, 1500)
    }

    private fun cleanupResources() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("SCREEN_TRANSLATOR_CHANNEL", "Ekran Çevirici", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "SCREEN_TRANSLATOR_CHANNEL")
            .setContentTitle("Çeviri Motoru Aktif")
            .setContentText("Herhangi bir ekranda yazıları çevirmek için butona basın.")
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
        if (::dismissView.isInitialized) {
            windowManager.removeView(dismissView)
        }
        if (resultView != null) {
            windowManager.removeView(resultView)
        }
    }
}