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

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var wakeLock: PowerManager.WakeLock? = null
    private var overlayController: OverlayController? = null

    override fun onCreate() {
        super.onCreate()
        SmartLearningManager.init(this)
        StatsManager.init(this)
        overlayController = OverlayController(this)
        registerReceiver(testReceiver, IntentFilter("com.example.taxifilter.TEST_ORDER"), Context.RECEIVER_NOT_EXPORTED)
    }

    // Overlay Logic Text Processor
    private var overlayLastFingerprint = ""
    private var overlayLastProcessTime = 0L

    private val testReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra("text") ?: return
            val force = intent.getBooleanExtra("force", false)
            Log.d(TAG, "Входящий заброс (force=$force): $text")
            
            // Прогоняем через полный цикл обработки для ML лога
            processText(text)
            
            if (force) {
                val orderInfo = OrderParser.parse(text)
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

    companion object {
        private const val TAG = "ScreenCapture"
        private const val CHANNEL_ID = "ScreenCaptureChannel"
        private const val NOTIFICATION_ID = 101
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
            
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TaxiFilter::OCR_WakeLock")
            wakeLock?.acquire()
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

            var bitmap = Bitmap.createBitmap(width + (rowStride - pixelStride * width) / pixelStride, height, Bitmap.Config.ARGB_8888)
            buffer.position(0)
            bitmap.copyPixelsFromBuffer(buffer)

            val prefs = getSharedPreferences("taxi_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("scan_area_set", false)) {
                val x = prefs.getInt("scan_x", 0)
                val y = prefs.getInt("scan_y", 0)
                val w = prefs.getInt("scan_w", bitmap.width)
                val h = prefs.getInt("scan_h", bitmap.height)

                val safeX = x.coerceIn(0, bitmap.width - 1)
                val safeY = y.coerceIn(0, bitmap.height - 1)
                val safeW = w.coerceIn(1, bitmap.width - safeX)
                val safeH = h.coerceIn(1, bitmap.height - safeY)

                val cropped = Bitmap.createBitmap(bitmap, safeX, safeY, safeW, safeH)
                bitmap.recycle()
                bitmap = cropped
            }
            
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val currentBitmap = bitmap
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val rawText = visionText.text
                    if (rawText.isNotEmpty() && OrderParser.containsTaxiKeywords(rawText)) {
                        processText(rawText, currentBitmap)
                        
                        val orderInfo = OrderParser.parse(rawText)
                        val minRate = prefs.getFloat("min_hourly_rate", 60f)
                        val costPerKm = prefs.getFloat("cost_per_km", 0.30f)
                        val loadingTime = prefs.getInt("loading_time", 2)
                        
                        val totalMinutes = (if (orderInfo.timeToClient > 0) orderInfo.timeToClient else 30) + loadingTime
                        val runningCost = orderInfo.estimatedDistance * costPerKm
                        val netEarning = orderInfo.price - runningCost
                        val hourlyRate = (netEarning / (totalMinutes / 60.0)).toInt()
                        
                        val fingerprint = "${orderInfo.price}:${orderInfo.timeToClient}:${orderInfo.estimatedDistance}"
                        val isNewOrder = fingerprint != lastOrderFingerprint || (System.currentTimeMillis() - lastSentTime > 60000)

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
            image.close()
        }
    }

    private fun forceAnalyzeOrder(orderInfo: OrderInfo) {
        val prefs = getSharedPreferences("taxi_prefs", Context.MODE_PRIVATE)
        val costPerKm = prefs.getFloat("cost_per_km", 0.30f)
        val loadingTime = prefs.getInt("loading_time", 2)
        val overlayDuration = prefs.getInt("overlay_duration", 10)

        val totalMinutes = (if (orderInfo.timeToClient > 0) orderInfo.timeToClient else 30) + loadingTime
        val runningCost = orderInfo.estimatedDistance * costPerKm
        val netEarning = orderInfo.price - runningCost
        val hourlyRate = (netEarning / (totalMinutes / 60.0)).toInt()

        overlayController?.showOrderInfo(
            hourlyRate = hourlyRate,
            km = orderInfo.estimatedDistance,
            minutes = orderInfo.timeToClient,
            pickup = orderInfo.pickupAddress,
            destination = orderInfo.destinationAddress,
            durationSec = overlayDuration,
            appName = orderInfo.appName,
            confidence = orderInfo.confidence,
            surge = orderInfo.surgeMultiplier,
            rating = orderInfo.passengerRating
        )
        playSoundAndVibrate()
    }

    private fun processText(text: String, bitmap: Bitmap? = null) {
        val orderInfo = OrderParser.parse(text)
        val fingerprint = "${orderInfo.price}:${orderInfo.timeToClient}:${orderInfo.estimatedDistance}"
        
        if (fingerprint == overlayLastFingerprint && System.currentTimeMillis() - overlayLastProcessTime < 60000) return
        
        if (orderInfo.price > 1.0 && orderInfo.timeToClient > 0 && orderInfo.estimatedDistance > 0.0) {
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
            analyzeOrder(orderInfo)
        }
    }

    private fun analyzeOrder(orderInfo: OrderInfo) {
        val prefs = getSharedPreferences("taxi_prefs", Context.MODE_PRIVATE)
        val minHourlyRate = prefs.getFloat("min_hourly_rate", 60f)
        val maxTime = prefs.getInt("max_time", 40)
        val maxDist = prefs.getFloat("max_distance", 30f)
        val costPerKm = prefs.getFloat("cost_per_km", 0.30f)
        val loadingTime = prefs.getInt("loading_time", 2)
        val overlayDuration = prefs.getInt("overlay_duration", 10)

        val totalMinutes = (if (orderInfo.timeToClient > 0) orderInfo.timeToClient else 30) + loadingTime
        val runningCost = orderInfo.estimatedDistance * costPerKm
        val netEarning = orderInfo.price - runningCost
        val hourlyRate = (netEarning / (totalMinutes / 60.0)).toInt()

        val isGood = (hourlyRate >= minHourlyRate &&
                      orderInfo.timeToClient <= maxTime &&
                      orderInfo.estimatedDistance <= maxDist) || orderInfo.confidence >= 95

        overlayController?.showOrderInfo(
            hourlyRate = hourlyRate,
            km = orderInfo.estimatedDistance,
            minutes = orderInfo.timeToClient,
            pickup = orderInfo.pickupAddress,
            destination = orderInfo.destinationAddress,
            durationSec = overlayDuration,
            appName = orderInfo.appName,
            confidence = orderInfo.confidence,
            surge = orderInfo.surgeMultiplier,
            rating = orderInfo.passengerRating
        )
        if (isGood) {
            playSoundAndVibrate()
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
        SmartLearningManager.save(this)
        unregisterReceiver(testReceiver)
        if (currentSessionTaken > 0) {
            val summary = "🔚 СМЕНА ОКОНЧЕНА\n📦 Всего взято: $currentSessionTaken заказов\n💰 Общая сумма: ${"%.2f".format(currentSessionPrice)}zł\n⭐ Молодец!"
            TelegramSender.sendMessage(this, summary)
        }
        handler.removeCallbacksAndMessages(null)
        virtualDisplay?.release()
        mediaProjection?.stop()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }
}
