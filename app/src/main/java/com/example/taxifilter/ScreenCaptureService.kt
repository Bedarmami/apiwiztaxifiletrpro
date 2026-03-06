package com.example.taxifilter

import android.app.*
import android.content.*
import android.graphics.*
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.media.RingtoneManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.location.LocationManager
import java.util.Timer
import java.util.TimerTask
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.atan
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
    private var wakeLock: PowerManager.WakeLock? = null
    private var overlayController: OverlayController? = null

    override fun onCreate() {
        super.onCreate()
        instance = this  // 🐞 регистрируемся
        SmartLearningManager.init(this)
        StatsManager.init(this)
        overlayController = OverlayController(this)
        val filter = IntentFilter("com.example.taxifilter.TEST_ORDER")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(testReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(testReceiver, filter)
        }
        // 🐞 Bug report receiver (fallback на broadcast)
        val bugFilter = IntentFilter("com.example.taxifilter.SAVE_BUG_REPORT")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bugReportReceiver, bugFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bugReportReceiver, bugFilter)
        }
    }

    // Overlay Logic Text Processor
    private var overlayLastFingerprint = ""
    private var overlayLastProcessTime = 0L

    // 🐞 Приёмник для сохранения отчёта из overlay-кнопки
    private val bugReportReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            saveBugReport(false)
        }
    }

    private val testReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra("text") ?: return
            val force = intent.getBooleanExtra("force", false)
            Log.d(TAG, "Входящий заброс (force=$force): $text")
            
            // Прогоняем через полный цикл обработки для ML лога
            processText(text)
            
            if (force) {
                val prefs = getSharedPreferences("taxi_prefs", Context.MODE_PRIVATE)
                val commission = prefs.getFloat("app_commission", 25f)
                val fuelCost = prefs.getFloat("fuel_cost_per_km", 0.40f)
                val orderInfo = OrderParser.parse(text, commission, fuelCost)
                forceAnalyzeOrder(orderInfo)
            }
        }
    }
    
    // Telegram Anti-Spam & Stats
    private var lastOrderFingerprint = ""
    private var lastSentTime = 0L
    private var pendingGoodOrder: OrderInfo? = null
    private var pendingOrderTime = 0L
    private var currentSessionTaken = 0
    private var currentSessionPrice = 0.0

    // ML Tracker Data
    private var mlPendingOrder: OrderInfo? = null
    private val mlHandler = Handler(Looper.getMainLooper())
    private var mlReportRunnable: Runnable? = null
    
    // == Чёрный ящик (по аналогии accesreed) ==
    private val rollingLog = java.util.LinkedList<String>()
    private val MAX_LOG_ENTRIES = 50
    private var lastRawOcrText = ""
    
    // 1H PROFIT: Idle & WakeLock management
    private var lastOrderTime = System.currentTimeMillis()
    private val WAKELOCK_RENEW_INTERVAL = 3600000L // 1 Hour
    private var wakeLockTimer: Timer? = null

    companion object {
        private const val TAG = "ScreenCapture"
        private const val CHANNEL_ID = "ScreenCaptureChannel"
        private const val NOTIFICATION_ID = 101
        
        // 🐞 Статическая ссылка на сервис — OverlayController держит прямой доступ
        var instance: ScreenCaptureService? = null
    }

    private var locationTimer: Timer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val resultData = intent?.getParcelableExtra<Intent>("RESULT_DATA")

        if (resultData != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
            
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection Stopped")
                    stopSelf()
                }
            }, handler)

            setupVirtualDisplay()
            startCaptureLoop()
            startLocationUpdates()
            // Авто-синхронизация базы знаний при старте
            DriverNetworkManager.syncData(this)
            
            // 1H Profit logic: start automated renewal instead of manual acquire here
            startWakeLockRenewal()
            startIdleTracking()
        }
        
        return START_NOT_STICKY
    }

    private fun startLocationUpdates() {
        locationTimer = Timer()
        locationTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                updateCloudStatus()
                DriverNetworkManager.syncData(this@ScreenCaptureService)
            }
        }, 0, 300000) // Раз в 5 минут
    }

    private fun updateCloudStatus() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) 
                      ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            
            loc?.let {
                DriverNetworkManager.updateRealLocation(this, it.latitude, it.longitude)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "No location permission for cloud sync")
        }
    }

    private fun startWakeLockRenewal() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TaxiFilter::OCR_WakeLock")
        
        wakeLockTimer = Timer()
        wakeLockTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                Log.d(TAG, "Renewing WakeLock for 1 hour...")
                if (wakeLock?.isHeld == true) wakeLock?.release()
                wakeLock?.acquire(WAKELOCK_RENEW_INTERVAL)
            }
        }, 0, WAKELOCK_RENEW_INTERVAL - 60000) // Renew 1 min before expiry
    }

    private fun startIdleTracking() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val idleTime = System.currentTimeMillis() - lastOrderTime
                if (idleTime > 900000) { // 15 mins of silence
                    Log.d(TAG, "Idle for 15 mins. Playing reminder sound.")
                    playSoundAndVibrate() // 1H PROFIT: Notification for driver
                    lastOrderTime = System.currentTimeMillis() // Reset to avoid spam
                }
                handler.postDelayed(this, 60000) // Check every minute
            }
        }, 60000)
    }

    private fun setupVirtualDisplay() {
        val metrics = resources.displayMetrics
        imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            0, imageReader?.surface, null, null
        )
    }

    private val isProcessing = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun startCaptureLoop() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!isProcessing.get()) {
                    captureAndProcess()
                }
                handler.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private var lastLuminance = 0.0
    private val LUMINANCE_THRESHOLD = 0.001 

    private fun captureAndProcess() {
        var lastImage: android.media.Image? = null
        while (true) {
            val img = imageReader?.acquireNextImage() ?: break
            lastImage?.close()
            lastImage = img
        }

        val image = lastImage ?: return
        
        try {
            isProcessing.set(true)
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val width = image.width
            val height = image.height

            val prefs = getSharedPreferences("taxi_prefs", Context.MODE_PRIVATE)
            val topPct = prefs.getFloat("roi_top_pct", 0.35f)
            val bottomPct = prefs.getFloat("roi_bottom_pct", 0.90f)

            // --- 1H PROFIT OPTIMIZATION: Luminance Variance Check ONLY on ROI ---
            // We focus detection on the area where the order actually appears.
            val variance = calculateLuminanceVariance(buffer, width, height, rowStride, topPct, bottomPct)
            if (kotlin.math.abs(variance - lastLuminance) < LUMINANCE_THRESHOLD) {
                isProcessing.set(false)
                image.close()
                return
            }
            lastLuminance = variance
            Log.d(TAG, "Screen ROI changed, running OCR... (Var: $variance)")

            var bitmap = Bitmap.createBitmap(width + (rowStride - 4 * width) / 4, height, Bitmap.Config.ARGB_8888)
            buffer.position(0)
            bitmap.copyPixelsFromBuffer(buffer)
            image.close() 
            lastImage = null // Mark as closed

            // --- 1H PROFIT: ROI Cropping Logic (Tuned for better results) ---
            val cropX = (width * 0.05).toInt() // Wide capture to ensure price is not cut
            val cropY = (height * 0.33).toInt() // Start above the card
            val cropWidth = (width * 0.90).toInt()
            val cropHeight = (height * 0.55).toInt() // Focus on the bottom half where cards appear

            val safeX = cropX.coerceIn(0, bitmap.width - 1)
            val safeY = cropY.coerceIn(0, bitmap.height - 1)
            val safeW = cropWidth.coerceIn(1, bitmap.width - safeX)
            val safeH = cropHeight.coerceIn(1, bitmap.height - safeY)

            val cropped = Bitmap.createBitmap(bitmap, safeX, safeY, safeW, safeH)
            bitmap.recycle()
            bitmap = cropped
            
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val currentBitmap = bitmap
            recognizer.process(inputImage)
                .addOnCompleteListener {
                    isProcessing.set(false) // Всегда сбрасываем флаг завершения
                }
                .addOnSuccessListener { visionText ->
                    val rawText = visionText.text
                    if (rawText.isNotEmpty() && OrderParser.containsTaxiKeywords(rawText)) {
                        lastOrderTime = System.currentTimeMillis() // Reset idle timer
                        processText(rawText, currentBitmap)
                        
                        val commission = prefs.getFloat("app_commission", 25f)
                        val fuelCost = prefs.getFloat("fuel_cost_per_km", 0.40f)
                        val orderInfo = OrderParser.parse(rawText, commission, fuelCost)
                        
                        val minRate = prefs.getFloat("min_hourly_rate", 60f)
                        
                        // Use calculated rate from orderInfo
                        val hourlyRate = orderInfo.hourlyRate.toInt()
                        
                        val fingerprint = "${orderInfo.price}:${orderInfo.timeToClient}:${orderInfo.estimatedDistance}"
                        val isNewOrder = fingerprint != lastOrderFingerprint || (System.currentTimeMillis() - lastSentTime > 60000)

                        // 1H PROFIT: If rate is 15% above min, it's "Highly Profitable"
                        val isHighlyProfitable = hourlyRate >= (minRate * 1.15f)

                        if (hourlyRate >= minRate && orderInfo.price > 1.0 && isNewOrder) {
                            lastOrderFingerprint = fingerprint
                            lastSentTime = System.currentTimeMillis()
                            pendingGoodOrder = orderInfo
                            pendingOrderTime = System.currentTimeMillis()
                            
                            val currentCount = prefs.getInt("stats_good_orders", 0)
                            val currentProfit = prefs.getFloat("stats_potential_profit", 0f)
                            prefs.edit().apply {
                                putInt("stats_good_orders", currentCount + 1)
                                putFloat("stats_potential_profit", currentProfit + orderInfo.price.toFloat())
                                apply()
                            }
                            Log.d(TAG, "Good order detected, waiting for trip start...")
                        }
                    } else if (OrderParser.isTripActive(rawText)) {
                        mlReportRunnable?.let {
                            mlHandler.removeCallbacks(it)
                            mlReportRunnable = null
                            mlPendingOrder?.let { order -> 
                                TelegramSender.sendMlDataRow(this@ScreenCaptureService, order, true)
                                val loc3 = getLastLocation()
                                DriverNetworkManager.logOrderToCloud(
                                    this@ScreenCaptureService,
                                    order.price,
                                    order.estimatedDistance,
                                    order.destinationAddress ?: "Unknown",
                                    pickup = order.pickupAddress,
                                    lat = loc3?.latitude,
                                    lon = loc3?.longitude,
                                    app = order.appName,
                                    status = "TAKEN"
                                )
                                mlPendingOrder = null
                            }
                        }

                        val pending = pendingGoodOrder
                        if (pending != null && (System.currentTimeMillis() - pendingOrderTime < 15000)) {
                            currentSessionTaken++
                            currentSessionPrice += pending.price
                            
                            val netEarning = pending.price - (pending.estimatedDistance * prefs.getFloat("cost_per_km", 0.30f))
                            val totalMinutes = (if (pending.timeToClient > 0) pending.timeToClient else 30) + prefs.getInt("loading_time", 2)
                            val rate = (netEarning / (totalMinutes / 60.0)).toInt()
                            
                            IdleTracker.onOrderAccepted()
                            StatsManager.addOrder(pending.price, pending.estimatedDistance, prefs.getFloat("cost_per_km", 0.30f))
                            StatsManager.save(this@ScreenCaptureService)
                            overlayController?.applyTheme("stealth")
                            
                            val statsMsg = "✅ ПРИНЯТО В РАБОТУ!\n💰 Цена: ${pending.price}zł\n📈 Поток: ${rate}zł/час\n📊 Итого за смену: $currentSessionTaken заказов"
                            TelegramSender.sendMessage(this@ScreenCaptureService, statsMsg)
                            
                            SmartLearningManager.learnFromSuccess(pending.pickupAddress)
                            SmartLearningManager.learnFromSuccess(pending.destinationAddress)
                            SmartLearningManager.save(this@ScreenCaptureService)
                            pendingGoodOrder = null
                        }
                    } else {
                        val showFloating = prefs.getBoolean("show_floating_stats", true)
                        if (showFloating) {
                            if (currentSessionTaken > 0) {
                                overlayController?.showOrderInfo(
                                    hourlyRate = (currentSessionPrice / max(1, currentSessionTaken)).toInt(),
                                    km = 0.0, minutes = 0, durationSec = 5, isMinimized = true
                                )
                            } else if (prefs.getInt("stats_good_orders", 0) > 0) {
                                overlayController?.showOrderInfo(
                                    hourlyRate = 0, km = 0.0, minutes = 0, durationSec = 5, isMinimized = true
                                )
                            }
                        }
                    }
                    isProcessing.set(false)
                }
                .addOnFailureListener { e -> 
                    Log.e(TAG, "OCR Error", e)
                    isProcessing.set(false)
                }

        } catch (e: Exception) {
            Log.e(TAG, "Capture failed", e)
            isProcessing.set(false)
        } finally {
            try { lastImage?.close() } catch (e: Exception) {}
        }
    }

    private fun forceAnalyzeOrder(orderInfo: OrderInfo) {
        val prefs = getSharedPreferences("taxi_prefs", Context.MODE_PRIVATE)
        val overlayDuration = prefs.getInt("overlay_duration", 10)

        overlayController?.showOrderInfo(
            hourlyRate = orderInfo.hourlyRate.toInt(),
            km = orderInfo.estimatedDistance,
            minutes = orderInfo.timeToClient,
            pickup = orderInfo.pickupAddress,
            destination = orderInfo.destinationAddress,
            durationSec = overlayDuration,
            appName = orderInfo.appName,
            confidence = orderInfo.confidence,
            surge = orderInfo.surgeMultiplier,
            rating = orderInfo.passengerRating,
            isHighlyProfitable = orderInfo.isHighlyProfitable
        )
        playSoundAndVibrate()
    }

    private fun processText(text: String, bitmap: Bitmap? = null) {
        val prefs = getSharedPreferences("taxi_prefs", Context.MODE_PRIVATE)
        val commission = prefs.getFloat("app_commission", 25f)
        val fuelCost = prefs.getFloat("fuel_cost_per_km", 0.40f)
        val orderInfo = OrderParser.parse(text, commission, fuelCost)
        
        val fingerprint = "${orderInfo.price}:${orderInfo.timeToClient}:${orderInfo.estimatedDistance}"
        
        // Анти-спам для OCR: 2 секунды, чтобы ловить быстрые изменения
        if (fingerprint == overlayLastFingerprint && System.currentTimeMillis() - overlayLastProcessTime < 2000) return
        // Исключаем текст нашего собственного оверлея
        val lower = text.lowercase()
        // Исключаем текст нашего собственного оверлея максимально жестко
        // Исключаем текст нашего собственного оверлея максимально жестко
        if (lower.contains("zł/ч") || lower.contains("zł/ч") || lower.contains("ai:") ||
            lower.contains("profit:") || lower.contains(" (мин)") || lower.contains(" (min)") ||
            lower.contains("👤") || lower.contains("sync") || lower.contains("a (") || lower.contains("b (") ||
            // 🔴 НОВОЕ: фильтр popup нашего приложения ("0.50 zł/ KM" = объяснение расходов)
            lower.contains("zł/") ||   // "zł/ km", "zł/ км" — только в нашем popup
            lower.contains("nohatho") || lower.contains("ponятно") ||  // кнопка "ПОНЯТНО"
            lower.contains("kapмaHe") || lower.contains("karman") ||   // "кармане" из объяснения
            // Украинский интерфейс приложения
            lower.contains("3aMoBneHb") || lower.contains("3akoblth") ||
            lower.contains("вплине на показник") || lower.contains("bnnhhe")) return

        // Показываем плашку даже если парсер не нашел все данные (как в 1 Hour)
        if (true) { 
            var base64Img: String? = null
            bitmap?.let {
                try {
                    val out = java.io.ByteArrayOutputStream()
                    it.compress(Bitmap.CompressFormat.JPEG, 70, out)
                    base64Img = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
                } catch (e: Exception) {}
            }

            val loc = getLastLocation()
            DriverNetworkManager.logOrderToCloud(
                this,
                orderInfo.price,
                orderInfo.estimatedDistance,
                orderInfo.destinationAddress ?: "Unknown",
                pickup = orderInfo.pickupAddress,
                lat = loc?.latitude,
                lon = loc?.longitude,
                app = orderInfo.appName,
                status = "NEW",
                rawText = text,
                screenshot = base64Img
            )

            mlReportRunnable?.let {
                mlHandler.removeCallbacks(it)
                mlPendingOrder?.let { oldOrder ->
                    TelegramSender.sendMlDataRow(this@ScreenCaptureService, oldOrder, false)
                    SmartLearningManager.learnFromIgnored(oldOrder.pickupAddress)
                    SmartLearningManager.learnFromIgnored(oldOrder.destinationAddress)
                }
            }
            
            mlPendingOrder = orderInfo
            val orderToReport = orderInfo
            mlReportRunnable = Runnable {
                TelegramSender.sendMlDataRow(this@ScreenCaptureService, orderToReport, false)
                val loc2 = getLastLocation()
                DriverNetworkManager.logOrderToCloud(
                    this@ScreenCaptureService,
                    orderToReport.price,
                    orderToReport.estimatedDistance,
                    orderToReport.destinationAddress ?: "Unknown",
                    pickup = orderToReport.pickupAddress,
                    lat = loc2?.latitude,
                    lon = loc2?.longitude,
                    app = orderToReport.appName,
                    status = "IGNORED",
                    rawText = text,
                    screenshot = base64Img
                )
                SmartLearningManager.learnFromIgnored(orderToReport.pickupAddress)
                SmartLearningManager.learnFromIgnored(orderToReport.destinationAddress)
                mlPendingOrder = null
            }
            mlHandler.postDelayed(mlReportRunnable!!, 20000)

            overlayLastFingerprint = fingerprint
            overlayLastProcessTime = System.currentTimeMillis()

            // Чёрный ящик
            addToRollingLog(text, orderInfo)
            lastRawOcrText = text

            analyzeOrder(orderInfo)
        }
    }

    private fun analyzeOrder(orderInfo: OrderInfo) {
        val prefs = getSharedPreferences("taxi_prefs", Context.MODE_PRIVATE)
        val minHourlyRate = prefs.getFloat("min_hourly_rate", 60f)
        val maxTime = prefs.getInt("max_time", 40)
        val maxDist = prefs.getFloat("max_distance", 30f)
        val overlayDuration = prefs.getInt("overlay_duration", 10)

        val isGood = (orderInfo.hourlyRate >= minHourlyRate &&
                      orderInfo.timeToClient <= maxTime &&
                      orderInfo.estimatedDistance <= maxDist) || orderInfo.confidence >= 95

        overlayController?.showOrderInfo(
            hourlyRate = orderInfo.hourlyRate.toInt(),
            km = orderInfo.estimatedDistance,
            minutes = orderInfo.timeToClient,
            pickup = orderInfo.pickupAddress,
            destination = orderInfo.destinationAddress,
            durationSec = overlayDuration,
            appName = orderInfo.appName,
            confidence = orderInfo.confidence,
            surge = orderInfo.surgeMultiplier,
            rating = orderInfo.passengerRating,
            isHighlyProfitable = orderInfo.isHighlyProfitable,
            pickupTime = orderInfo.pickupTime,
            destinationTime = orderInfo.destinationTime
        )
        if (isGood) {
            playSoundAndVibrate()
            // 🔴 Исправлено: ChainPredictor теперь знает пункт назначения
            ChainPredictor.setLastDestination(orderInfo.destinationAddress)
        }
    }

    private fun getLastLocation(): android.location.Location? {
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            null
        }
    }

    // ==================== DEBUG / BLACK BOX (like accesreed) ====================

    private fun addToRollingLog(rawText: String, order: OrderInfo) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
        val entry = buildString {
            appendLine("[$ts] ЦЕНА: ${order.price} | РАССТ: ${order.estimatedDistance} км | ВРЕМЯ: ${order.timeToClient} мин")
            appendLine("Адрес А: ${order.pickupAddress ?: "-"}")
            appendLine("Адрес Б: ${order.destinationAddress ?: "-"}")
            appendLine("Час_ставка: ${order.hourlyRate.toInt()} zł/ч | App: ${order.appName}")
            appendLine("Сырой OCR (сжат):\n${compressText(rawText)}")
            appendLine("-".repeat(50))
        }
        if (rollingLog.size >= MAX_LOG_ENTRIES) rollingLog.removeFirst()
        rollingLog.addLast(entry)
    }

    private fun compressText(text: String): String {
        // Аналог accesreed: подсчитываем повторы в OCR тексте
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val counts = LinkedHashMap<String, Int>()
        for (line in lines) counts[line] = (counts[line] ?: 0) + 1
        return counts.entries.joinToString("\n") { (line, cnt) ->
            if (cnt > 1) "$line ×$cnt" else line
        }
    }

    fun saveBugReport(isGood: Boolean) {
        try {
            val dir = java.io.File(getExternalFilesDir(null), "TaxiFilter_Reports")
            dir.mkdirs()
            val ts = java.text.SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", java.util.Locale.getDefault()).format(java.util.Date())
            val prefix = if (isGood) "good_" else "bad_"
            val file = java.io.File(dir, "${prefix}report_$ts.txt")

            val sb = StringBuilder()
            sb.appendLine("=== ОТЧЁТ TAXIFILTER (${if (isGood) "УСПЕХ" else "ОШИБКА"}) ===")
            sb.appendLine("Время: $ts")
            sb.appendLine()
            sb.appendLine("=== ЧЁРНЫЙ ЯЩИК (${rollingLog.size} записей) ===")
            rollingLog.forEach { sb.append(it) }
            sb.appendLine()
            sb.appendLine("=== ПОСЛЕДНИЙ СЫРОЙ OCR ===")
            sb.appendLine(lastRawOcrText)
            sb.appendLine()
            sb.appendLine("=== СЖАТЫЙ OCR ===")
            sb.appendLine(compressText(lastRawOcrText))

            file.writeText(sb.toString())
            
            // 📤 Автоотправка отчёта в Telegram
            sendReportToTelegram(file, isGood, ts)
            
            android.widget.Toast.makeText(this,
                "🐞 Отчёт сохранён и отправлен в TG",
                android.widget.Toast.LENGTH_LONG).show()
            Log.d(TAG, "Отчёт сохранён: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения отчёта", e)
        }
    }

    private fun sendReportToTelegram(file: java.io.File, isGood: Boolean, ts: String) {
        val prefs = getSharedPreferences("taxi_prefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)) ?: "unknown"
        val serverUrl = prefs.getString("server_url", "https://railway-volume-dump-production-ead4.up.railway.app")
            ?.takeIf { it.isNotEmpty() } ?: "https://railway-volume-dump-production-ead4.up.railway.app"
        val logText = file.readText()

        Thread {
            // 1. Отправка в Telegram
            try {
                val chatId = TelegramSender.ADMIN_CHAT_ID
                if (chatId.isNotEmpty()) {
                    val botToken = "8724275601:AAGoPoIGG8tLpSooeHJb5yFVSSFiHuxi6ow"
                    val emoji = if (isGood) "✅" else "🐞"
                    val caption = "$emoji TaxiFilter Debug Report\n📅 $ts\n📱 ${android.os.Build.MODEL}\n📊 ${rollingLog.size} записей"
                    val tgBody = okhttp3.MultipartBody.Builder()
                        .setType(okhttp3.MultipartBody.FORM)
                        .addFormDataPart("chat_id", chatId)
                        .addFormDataPart("caption", caption)
                        .addFormDataPart("document", file.name, logText.toByteArray().toRequestBody("text/plain".toMediaType()))
                        .build()
                    val tgRequest = okhttp3.Request.Builder()
                        .url("https://api.telegram.org/bot$botToken/sendDocument")
                        .post(tgBody)
                        .build()
                    okhttp3.OkHttpClient().newCall(tgRequest).execute().use { r ->
                        Log.d(TAG, "TG report: ${r.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TG send failed", e)
            }

            // 2. Отправка на сервер
            try {
                val json = org.json.JSONObject().apply {
                    put("device_id", deviceId)
                    put("device_model", android.os.Build.MODEL)
                    put("is_good", isGood)
                    put("log_text", logText)
                }
                val serverBody = json.toString().toByteArray().toRequestBody("application/json".toMediaType())
                val serverRequest = okhttp3.Request.Builder()
                    .url("$serverUrl/api/taxifilter/report")
                    .post(serverBody)
                    .build()
                okhttp3.OkHttpClient().newCall(serverRequest).execute().use { r ->
                    Log.d(TAG, "Server report: ${r.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server send failed", e)
            }
        }.start()
    }


    private fun playSoundAndVibrate() {
        val prefs = applicationContext.getSharedPreferences("taxi_prefs", Context.MODE_PRIVATE)
        val soundType = prefs.getString("notification_sound", "system") ?: "system"
        if (soundType == "none") return
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            when (soundType) {
                "system" -> {
                    val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    val ringtone = RingtoneManager.getRingtone(applicationContext, notificationUri)
                    ringtone.play()
                }
                else -> if (soundType != "vibrate") playSyntheticAudio(soundType)
            }
        } catch (e: Exception) {}
    }

    private fun playSyntheticAudio(type: String) {
        val sampleRate = 44100
        val duration = when(type) {
            "coin" -> 0.3
            "frog" -> 0.4
            "magic" -> 0.6
            else -> 0.3
        }
        val numSamples = (duration * sampleRate).toInt()
        val sample = ByteArray(numSamples * 2)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            var valShort = 0.0
            when (type) {
                "coin" -> {
                    val freq = if (t < 0.1) 988.0 else 1318.0
                    val envelope = if (t < 0.1) 1.0 - (t / 0.1) else 1.0 - ((t - 0.1) / 0.2)
                    valShort = sin(2 * PI * freq * t) * 16000 * max(0.0, envelope)
                }
                "frog" -> {
                    val freq = 120.0 + sin(2 * PI * 15.0 * t) * 40.0
                    val envelope = if (t < 0.2) t / 0.2 else 1.0 - ((t - 0.2) / 0.2)
                    val rawWave = atan(sin(2 * PI * freq * t) * 5) / 1.5
                    valShort = rawWave * 16000 * max(0.0, envelope)
                }
                "magic" -> {
                    val freq = when {
                        t < 0.1 -> 523.25
                        t < 0.2 -> 659.25
                        t < 0.3 -> 783.99
                        else -> 1046.50
                    }
                    val envelope = 1.0 - (t / 0.6)
                    valShort = sin(2 * PI * freq * t) * 16000 * max(0.0, envelope)
                }
            }
            val shortVal = valShort.toInt().toShort()
            sample[i * 2] = (shortVal.toInt() and 0xff).toByte()
            sample[i * 2 + 1] = ((shortVal.toInt() ushr 8) and 0xff).toByte()
        }
        Thread {
            try {
                val audioTrack = AudioTrack(AudioManager.STREAM_NOTIFICATION, sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, sample.size, AudioTrack.MODE_STATIC)
                audioTrack.write(sample, 0, sample.size)
                audioTrack.play()
                Thread.sleep((duration * 1000).toLong() + 100)
                audioTrack.release()
            } catch (e: Exception) {}
        }.start()
    }

    private fun createNotification(): Notification {
        val channel = NotificationChannel(CHANNEL_ID, "Screen Analysis", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Taxi Filter: OCR Активен")
            .setContentText("Анализирую экран в поисках заказов...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null  // 🐞 чистимся
        SmartLearningManager.save(this)
        unregisterReceiver(testReceiver)
        try { unregisterReceiver(bugReportReceiver) } catch (e: Exception) {}
        if (currentSessionTaken > 0) {
            val summary = "🔚 СМЕНА ОКОНЧЕНА\n📦 Всего взято: $currentSessionTaken заказов\n💰 Общая сумма: ${"%.2f".format(currentSessionPrice)}zł\n⭐ Молодец!"
            TelegramSender.sendMessage(this, summary)
        }
        handler.removeCallbacksAndMessages(null)
        virtualDisplay?.release()
        mediaProjection?.stop()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLockTimer?.cancel()
        locationTimer?.cancel()
        try { recognizer.close() } catch (e: Exception) {}
        overlayController?.onActivityDestroyed()
        super.onDestroy()
    }

    private fun calculateLuminanceVariance(buffer: java.nio.ByteBuffer, width: Int, height: Int, rowStride: Int, topPct: Float, bottomPct: Float): Double {
        // Fast luminance calculation (sampling) ONLY within ROI
        // 100% matched with 1H PROFIT (com.onehour.profit.ScreenCaptureService.a)
        var sum = 0.0
        var sumSq = 0.0
        val sampleStep = 6 
        var count = 0

        val startX = (width * 0.20).toInt()
        val endX = (width * 0.80).toInt()
        val startY = (height * topPct).toInt()
        val endY = (height * bottomPct).toInt()

        buffer.position(0)
        for (y in startY until endY step sampleStep) {
            for (x in startX until endX step sampleStep) {
                val offset = y * rowStride + x * 4
                if (offset + 2 < buffer.limit()) {
                    val r = buffer.get(offset).toInt() and 0xFF
                    val g = buffer.get(offset + 1).toInt() and 0xFF
                    val b = buffer.get(offset + 2).toInt() and 0xFF
                    
                    val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
                    sum += lum
                    sumSq += lum * lum
                    count++
                }
            }
        }
        
        if (count == 0) return 0.0
        val mean = sum / count
        return (sumSq / count) - (mean * mean)
    }
}
