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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
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
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var dismissView: View

    private lateinit var cardTranslateBtn: androidx.cardview.widget.CardView
    private lateinit var tvBtnText: TextView

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var resultView: View? = null

    private var isCaptureRequested = false
    private var cropOverlayView: ScreenCropView? = null
    private var selectedCropRect: Rect? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var touchStartTime = 0L

    // HAFIZADAN HEDEF DİLİ ÇEKME FONKSİYONU
    private fun getTargetLanguageCode(): String {
        val prefs = getSharedPreferences("ST_PREFS", Context.MODE_PRIVATE)
        return prefs.getString("TARGET_LANG_CODE", "tr") ?: "tr"
    }

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

        cardTranslateBtn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    dismissView.visibility = View.VISIBLE
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, params)

                    if (params.y > Resources.getSystem().displayMetrics.heightPixels * 0.75) {
                        dismissView.findViewById<View>(R.id.card_dismiss).alpha = 1.0f
                    } else {
                        dismissView.findViewById<View>(R.id.card_dismiss).alpha = 0.5f
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    dismissView.visibility = View.GONE

                    val clickDuration = System.currentTimeMillis() - touchStartTime
                    val isClick = clickDuration < 200 && Math.abs(event.rawX - initialTouchX) < 15 && Math.abs(event.rawY - initialTouchY) < 15

                    if (isClick) {
                        startCropSelection()
                    } else {
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

    private fun startCropSelection() {
        floatingView.visibility = View.INVISIBLE

        cropOverlayView = ScreenCropView(this) { rect ->
            windowManager.removeView(cropOverlayView)
            cropOverlayView = null
            floatingView.visibility = View.VISIBLE

            if (rect.width() > 50 && rect.height() > 50) {
                selectedCropRect = rect
                updateButtonUI("İşleniyor", "#4CAF50")
                isCaptureRequested = true
            } else {
                resetButtonDelayed()
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(cropOverlayView, params)
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
                    if (isCaptureRequested && selectedCropRect != null) {
                        isCaptureRequested = false

                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * width

                        val bitmapWidth = width + rowPadding / pixelStride
                        val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
                        bitmap.copyPixelsFromBuffer(buffer)

                        val fullBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)

                        val safeLeft = Math.max(0, selectedCropRect!!.left)
                        val safeTop = Math.max(0, selectedCropRect!!.top)
                        val safeWidth = Math.min(fullBitmap.width - safeLeft, selectedCropRect!!.width())
                        val safeHeight = Math.min(fullBitmap.height - safeTop, selectedCropRect!!.height())

                        val croppedBitmap = Bitmap.createBitmap(fullBitmap, safeLeft, safeTop, safeWidth, safeHeight)

                        processImageWithAI(croppedBitmap)
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
                    if (cleanBlock.isNotBlank()) {
                        smartTextBuilder.append(cleanBlock).append("\n\n")
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
                val targetLang = getTargetLanguageCode() // Hedef dili aldık

                if (languageCode == "und") {
                    updateButtonUI("Dil Bulunamadı", "#FF9800")
                    resetButtonDelayed()
                } else if (languageCode == targetLang) {
                    updateButtonUI("Zaten $targetLang", "#4CAF50")
                    showTranslationResult(text, languageCode, targetLang)
                    resetButtonDelayed()
                } else {
                    translateDynamic(text, languageCode, targetLang)
                }
            }
            .addOnFailureListener {
                updateButtonUI("Dil Hata", "#B00020")
                resetButtonDelayed()
            }
    }

    // --- DİNAMİK ÇEVİRİ MOTORU GÜNCELLEMESİ ---
    private fun translateDynamic(text: String, sourceLangCode: String, targetLangCode: String) {
        updateButtonUI("Çevriliyor..", "#8AB4F8")

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLangCode)
            .setTargetLanguage(targetLangCode) // Artık dinamik!
            .build()
        val translator = Translation.getClient(options)

        val conditions = DownloadConditions.Builder().build()

        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                translator.translate(text)
                    .addOnSuccessListener { translatedText ->
                        updateButtonUI("Başarılı!", "#4CAF50")
                        showTranslationResult(translatedText, sourceLangCode, targetLangCode)
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

    // Sonuç ekranına HEDEF DİLİ de ekledik
    private fun showTranslationResult(text: String, sourceLang: String, targetLang: String) {
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

            // Ekranın tepesine havalı bir şekilde [TR -> EN] formatında yazıyoruz
            tvLanguageInfo.text = "ÇEVİRİ [${sourceLang.uppercase()} -> ${targetLang.uppercase()}]"
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
            cardTranslateBtn.setCardBackgroundColor(Color.parseColor(colorHex))
        }
    }

    private fun resetButtonDelayed() {
        Handler(Looper.getMainLooper()).postDelayed({
            tvBtnText.text = "Çevir"
            cardTranslateBtn.setCardBackgroundColor(Color.parseColor("#1A73E8"))
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
        if (cropOverlayView != null) {
            windowManager.removeView(cropOverlayView)
        }
    }
}

class ScreenCropView(context: Context, private val onCropFinish: (Rect) -> Unit) : View(context) {
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var isDrawing = false

    private val borderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
        pathEffect = DashPathEffect(floatArrayOf(15f, 15f), 0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isDrawing) {
            val rect = RectF(
                Math.min(startX, endX),
                Math.min(startY, endY),
                Math.max(startX, endX),
                Math.max(startY, endY)
            )
            canvas.save()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                canvas.clipOutRect(rect)
            } else {
                @Suppress("DEPRECATION")
                canvas.clipRect(rect, android.graphics.Region.Op.DIFFERENCE)
            }
            canvas.drawColor(Color.parseColor("#B3000000"))
            canvas.restore()

            canvas.drawRect(rect, borderPaint)
        } else {
            canvas.drawColor(Color.parseColor("#B3000000"))
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                endX = event.x
                endY = event.y
                isDrawing = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                endX = event.x
                endY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                isDrawing = false
                val rect = Rect(
                    Math.min(startX, endX).toInt(),
                    Math.min(startY, endY).toInt(),
                    Math.max(startX, endX).toInt(),
                    Math.max(startY, endY).toInt()
                )
                onCropFinish(rect)
            }
        }
        return true
    }
}