package com.example.taxifilter

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import android.media.RingtoneManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.location.Geocoder
import com.google.android.gms.maps.model.LatLng
import java.util.Locale
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.atan
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper

class TaxiAccessibilityService : AccessibilityService() {

    override fun onCreate() {
        super.onCreate()
        SmartLearningManager.init(this)
        StatsManager.init(this)
        overlayController = OverlayController(this)
        registerReceiver(testReceiver, IntentFilter("com.example.taxifilter.TEST_ORDER"), Context.RECEIVER_NOT_EXPORTED)
        
        // Синхронизация при старте
        DriverNetworkManager.syncData(this)
        startPeriodicSync()
    }

    private var syncHandler = Handler(Looper.getMainLooper())
    private var syncRunnable: Runnable? = null

    private fun startPeriodicSync() {
        syncRunnable = object : Runnable {
            override fun run() {
                DriverNetworkManager.syncData(this@TaxiAccessibilityService)
                syncHandler.postDelayed(this, 300000) // 5 минут
            }
        }
        syncHandler.postDelayed(syncRunnable!!, 300000)
    }



    companion object {
        private const val TAG = "TaxiFilter"
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

    private val testReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra("text") ?: return
            val force = intent.getBooleanExtra("force", false)
            Log.d(TAG, "Входящий заброс (force=$force): $text")
            
            // Для теста всегда прогоняем через processText, чтобы сработал ML логгер
            processText(text)
            
            if (force) {
                val orderInfo = OrderParser.parse(text)
                forceAnalyzeOrder(orderInfo)
            }
        }
    }

    private var overlayController: OverlayController? = null


    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Сервис подключен")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        // НЕ СКАНИРУЕМ САМИ СЕБЯ (избегаем зацикливания и мусора из админки)
        val packageName = event.packageName?.toString() ?: ""
        if (packageName.contains("com.example.myapplications") || packageName.contains("taxifilter")) return

        val rootNode = rootInActiveWindow ?: return
        
        val textBuilder = StringBuilder()
        extractTextFromNode(rootNode, textBuilder)
        
        val rawText = textBuilder.toString()
        if (rawText.isNotEmpty() && OrderParser.containsTaxiKeywords(rawText)) {
            processText(rawText)
        } else if (rawText.isNotEmpty() && OrderParser.isTripActive(rawText)) {
            // МИКСЕР МУЗЫКИ: Снижаем громкость при посадке пассажира
            adjustMusicVolume(true)

            // ML Аналитика: Если поездка стала активна = заказ ВЗЯТ
            mlReportRunnable?.let {
                mlHandler.removeCallbacks(it)
                mlReportRunnable = null
                mlPendingOrder?.let { order -> 
                    TelegramSender.sendMlDataRow(this@TaxiAccessibilityService, order, true)
                    
                    // Логируем УСПЕХ (TAKEN) в облако
                    val loc3 = getLastLocation()
                    DriverNetworkManager.logOrderToCloud(
                        this@TaxiAccessibilityService,
                        order.price,
                        order.estimatedDistance,
                        order.destinationAddress ?: "Unknown",
                        pickup = order.pickupAddress,
                        lat = loc3?.latitude,
                        lon = loc3?.longitude,
                        app = order.appName,
                        status = "TAKEN"
                    )

                    SmartLearningManager.learnFromSuccess(order.pickupAddress)
                    SmartLearningManager.learnFromSuccess(order.destinationAddress)
                    mlPendingOrder = null
                }
            }

            val pending = pendingGoodOrder
            if (pending != null && (System.currentTimeMillis() - pendingOrderTime < 15000)) {
                currentSessionTaken++
                currentSessionPrice += pending.price
                
                val prefs = getSharedPreferences("taxi_prefs", Context.MODE_PRIVATE)
                val netEarning = pending.price - (pending.estimatedDistance * prefs.getFloat("cost_per_km", 0.30f))
                val totalMinutes = (if (pending.timeToClient > 0) pending.timeToClient else 30) + prefs.getInt("loading_time", 2)
                val rate = (netEarning / (totalMinutes / 60.0)).toInt()
                
                // === СТАТИСТИКА ===
                IdleTracker.onOrderAccepted()
                StatsManager.addOrder(pending.price, pending.estimatedDistance, prefs.getFloat("cost_per_km", 0.30f))
                StatsManager.save(this@TaxiAccessibilityService)
                
                // Переводим в Stealth режим
                overlayController?.applyTheme("stealth")
                
                val statsMsg = "✅ ПРИНЯТО В РАБОТУ!\n" +
                               "💰 Цена: ${pending.price}zł\n" +
                               "📈 Поток: ${rate}zł/час\n" +
                               "📊 Итого за смену: $currentSessionTaken заказов"
                
                TelegramSender.sendMessage(this@TaxiAccessibilityService, statsMsg)
                pendingGoodOrder = null
            }
        } else {
            // Если текст пустой или нет ключевых слов - показываем Floating Stats
            val prefs = getSharedPreferences("taxi_prefs", Context.MODE_PRIVATE)
            
            // Если мы не в поездке - возвращаем громкость
            adjustMusicVolume(false)

            val showFloating = prefs.getBoolean("show_floating_stats", true)
            if (currentSessionTaken > 0 && showFloating) {
                overlayController?.showOrderInfo(
                    hourlyRate = (currentSessionPrice / max(1, currentSessionTaken)).toInt(),
                    km = 0.0, minutes = 0, durationSec = 5, isMinimized = true
                )
            }
        }
    }

    private fun adjustMusicVolume(dim: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (dim) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
        } else {
            // am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
        }
    }

    private fun extractTextFromNode(node: AccessibilityNodeInfo, builder: StringBuilder) {
        if (node.text != null) {
            builder.append(node.text).append("\n")
        }
        if (node.contentDescription != null) {
            builder.append(node.contentDescription).append("\n")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                extractTextFromNode(child, builder)
                child.recycle()
            }
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

    private var lastProcessedText = ""
    private var lastProcessTime = 0L
    private var lastFingerprint = ""

    private fun processText(text: String) {
        val orderInfo = OrderParser.parse(text)
        val fingerprint = "${orderInfo.price}:${orderInfo.timeToClient}:${orderInfo.estimatedDistance}"
        
        // Анти-спам: Заказ с теми же данными не обрабатываем повторно в течение 60 сек
        if (fingerprint == lastFingerprint && System.currentTimeMillis() - lastProcessTime < 60000) return
        
        // Фильтр мусора: если цены нет и адреса нет - не спамим
        if (orderInfo.price <= 0.0 && orderInfo.pickupAddress == null) return

        Log.i("TaxiFilter", "Детектирован заказ: ${orderInfo.price} zł, ${orderInfo.pickupAddress} -> ${orderInfo.destinationAddress}")
        
        lastFingerprint = fingerprint
        lastProcessTime = System.currentTimeMillis()

        // СНАЧАЛА ОТПРАВЛЯЕМ В ОБЛАКО (Безусловно, если это похоже на заказ)
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
            status = "NEW"
        )

        // Анализируем для показа плашки и звука (только если есть полные данные)
        if (orderInfo.price > 1.0 && orderInfo.timeToClient > 0 && orderInfo.estimatedDistance > 0.0) {
            
            // ============== ML TRACKER START ==============
            mlReportRunnable?.let {
                mlHandler.removeCallbacks(it)
                mlPendingOrder?.let { oldOrder ->
                    TelegramSender.sendMlDataRow(this@TaxiAccessibilityService, oldOrder, false)
                    SmartLearningManager.learnFromIgnored(oldOrder.pickupAddress)
                    SmartLearningManager.learnFromIgnored(oldOrder.destinationAddress)
                }
            }
            
            mlPendingOrder = orderInfo
            val orderToReport = orderInfo
            mlReportRunnable = Runnable {
                TelegramSender.sendMlDataRow(this@TaxiAccessibilityService, orderToReport, false)
                
                // Также логируем ИГНОР в облако для ML
                val loc2 = getLastLocation()
                DriverNetworkManager.logOrderToCloud(
                    this@TaxiAccessibilityService,
                    orderToReport.price,
                    orderToReport.estimatedDistance,
                    orderToReport.destinationAddress ?: "Unknown",
                    pickup = orderToReport.pickupAddress,
                    lat = loc2?.latitude,
                    lon = loc2?.longitude,
                    app = orderToReport.appName,
                    status = "IGNORED"
                )

                SmartLearningManager.learnFromIgnored(orderToReport.pickupAddress)
                SmartLearningManager.learnFromIgnored(orderToReport.destinationAddress)
                mlPendingOrder = null
            }
            mlHandler.postDelayed(mlReportRunnable!!, 20000) 
            // ============== ML TRACKER END ==============

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

        val currentStrategy = prefs.getInt("current_strategy", 2)

        val isGood = when (currentStrategy) {
            1 -> hourlyRate >= (minHourlyRate * 1.3)
            2 -> hourlyRate >= minHourlyRate
            3 -> orderInfo.timeToClient <= 15 && hourlyRate >= (minHourlyRate * 0.7)
            4 -> (hourlyRate >= minHourlyRate && orderInfo.timeToClient <= maxTime && orderInfo.estimatedDistance <= maxDist) || orderInfo.confidence >= 90
            else -> hourlyRate >= minHourlyRate
        } || orderInfo.confidence >= 95 // Если мы на 95% уверены в адресе (обучен), то это почти всегда "Good"


        // ПОКАЗЫВАЕМ ПЛАШКУ! (если включена в настройках)
        val showOverlay = prefs.getBoolean("enable_overlay", true)
        if (showOverlay) {
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
        }

        // Звук и вибрация ТОЛЬКО если заказ подходит под фильтры
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
            // Вибрация (для всех звуков и для 'vibrate')
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            
            when (soundType) {
                "vibrate" -> {
                    // Звук не нужен
                }
                "system" -> {
                    val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    val ringtone = RingtoneManager.getRingtone(applicationContext, notificationUri)
                    ringtone.play()
                }
                else -> playSyntheticAudio(soundType)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
                val audioTrack = AudioTrack(
                    AudioManager.STREAM_NOTIFICATION, 
                    sampleRate, 
                    AudioFormat.CHANNEL_OUT_MONO, 
                    AudioFormat.ENCODING_PCM_16BIT, 
                    sample.size, 
                    AudioTrack.MODE_STATIC
                )
                audioTrack.write(sample, 0, sample.size)
                audioTrack.play()
                Thread.sleep((duration * 1000).toLong() + 100)
                audioTrack.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    override fun onDestroy() {
        SmartLearningManager.save(this)
        unregisterReceiver(testReceiver)
        
        if (currentSessionTaken > 0) {
            val summary = "🔚 СМЕНА ОКОНЧЕНА\n" +
                          "📦 Всего взято: $currentSessionTaken заказов\n" +
                          "💰 Общая сумма: ${"%.2f".format(currentSessionPrice)}zł"
            TelegramSender.sendMessage(this, summary)
        }
        
        super.onDestroy()
    }

    override fun onInterrupt() {}
}