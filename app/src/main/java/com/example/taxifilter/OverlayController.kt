package com.example.taxifilter

import android.content.Context
import com.example.taxifilter.R
import android.util.Log
import android.graphics.PixelFormat
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.ImageButton
import android.content.Intent
import android.net.Uri
import android.view.View.GONE
import android.view.View.VISIBLE
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import android.location.Geocoder
import android.animation.ObjectAnimator
import android.animation.ArgbEvaluator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.graphics.drawable.GradientDrawable
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class OverlayController(private val context: Context) {
    private val geocoder = Geocoder(context.applicationContext, Locale.getDefault())
    private var mlModel: Interpreter? = null
    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null
    private var countdownRunnable: Runnable? = null
    private var geocodeThread: Thread? = null  // 🔴 трек потоков: храним ссылку для отмены
    
    companion object {
        private var appContext: Context? = null
        private var windowManager: WindowManager? = null
        private var sharedOverlayView: View? = null
        private var lastFullInfoShownAt = 0L
        private var currentParams: WindowManager.LayoutParams? = null
        
        // Переменные для перемещения
        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f

        fun getWindowManager(context: Context): WindowManager {
            if (windowManager == null) {
                appContext = context.applicationContext
                windowManager = appContext!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            }
            return windowManager!!
        }
    }

    private val gestureDetector = GestureDetector(context.applicationContext, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            hide()
            return true
        }
        override fun onDown(e: MotionEvent): Boolean = true
    })

    init {
        val ctx = context.applicationContext
        Configuration.getInstance().userAgentValue = ctx.packageName
        try {
            val afd = ctx.assets.openFd("taxi_advisor.tflite")
            val fis = FileInputStream(afd.fileDescriptor)
            val channel = fis.channel
            val map = channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            mlModel = Interpreter(map)
        } catch (e: Exception) {
            android.util.Log.e("TAXI_ML", "Не удалось загрузить TF модель", e)
        }
    }
    
    fun onActivityPaused() {
        handler.removeCallbacksAndMessages(null)
    }
    
    fun onActivityResumed() { }
    
    fun onActivityDestroyed() {
        hide()
        try { mlModel?.close() } catch (e: Exception) {}
        mlModel = null
    }

    fun showOrderInfo(
        hourlyRate: Int, km: Double, minutes: Int, 
        pickup: String? = null, destination: String? = null, 
        durationSec: Int = 10, appName: String? = null, confidence: Int = 100,
        surge: Double = 1.0, rating: Double? = null,
        isMinimized: Boolean = false,
        isHighlyProfitable: Boolean = false,
        pickupTime: Int? = null,
        destinationTime: Int? = null
    ) {
        val safeDuration = if (durationSec < 5) 5 else durationSec
        Log.d("Overlay", "showOrderInfo: rate=$hourlyRate, app=$appName, dur=$safeDuration")

        handler.post {
            // ПРИОРИТЕТ: Не показываем "минимизированную статистику", если только что (12 сек) был реальный заказ
            if (isMinimized && System.currentTimeMillis() - lastFullInfoShownAt < 12000) {
                Log.d("Overlay", "Skipping minimized update (cooldown)")
                return@post
            }
            if (!isMinimized) lastFullInfoShownAt = System.currentTimeMillis()

            val currentCtx = appContext ?: this@OverlayController.context.applicationContext
            val ctx = currentCtx
            getWindowManager(ctx)

            if (sharedOverlayView == null) {
                createOverlayView(ctx)
            }
            
            if (sharedOverlayView == null) return@post
            
            val ctx2 = appContext ?: ctx
            val v = sharedOverlayView!!
            val mapView = v.findViewById<MapView>(R.id.mapView)
            
            val prefs = ctx2.getSharedPreferences("taxi_prefs", Context.MODE_PRIVATE)

            // Читаем настройки отображения
            val showMapPref = prefs.getBoolean("show_overlay_map", true)
            val showAiPref = prefs.getBoolean("show_ai_verdict", true)
            val showStatsPref = prefs.getBoolean("show_overlay_stats", true)
            val showRoutePref = prefs.getBoolean("show_overlay_route", true)
            val showAddressPref = prefs.getBoolean("show_overlay_address", true)
            
            // Новые детальные настройки
            val overlayWidth = prefs.getFloat("overlay_width", 320f).toInt()
            val overlayMapHeight = prefs.getFloat("overlay_map_height", 140f).toInt()
            val overlayCornerRadius = prefs.getFloat("overlay_corner_radius", 20f)
            val overlayOpacity = prefs.getFloat("overlay_opacity", 1.0f)
            
            val currentStrategy = prefs.getInt("current_strategy", 2)
            val mapStyle = prefs.getString("map_style", "midnight") ?: "midnight"

            // Применяем настройки видимости и размеров
            val cardContainer = v.findViewById<androidx.cardview.widget.CardView>(R.id.cardContainer)
            val mapContainer = v.findViewById<View>(R.id.mapContainer)
            
            cardContainer?.let { card ->
                val lp = card.layoutParams
                lp.width = overlayWidth.toPx(ctx2)
                card.layoutParams = lp
                
                card.radius = overlayCornerRadius.toPx(ctx2).toFloat()
                card.alpha = overlayOpacity
                
                val scale = prefs.getFloat("overlay_scale", 1.0f)
                card.scaleX = scale
                card.scaleY = scale
            }
            
            mapContainer?.apply {
                visibility = if (showMapPref) VISIBLE else GONE
                val lp = layoutParams
                lp.height = overlayMapHeight.toPx(ctx2)
                layoutParams = lp
            }

            v.findViewById<View>(R.id.statsRow)?.visibility = if (showStatsPref) VISIBLE else GONE
            v.findViewById<View>(R.id.routeRow)?.visibility = if (showRoutePref) VISIBLE else GONE
            
            mapView?.alpha = 0.35f 
            
            v.findViewById<TextView>(R.id.tvHourlyRate)?.text = hourlyRate.toString()
            v.findViewById<TextView>(R.id.tvKm)?.text = String.format("%.1f", km)
            v.findViewById<TextView>(R.id.tvMinutes)?.text = minutes.toString()
            v.findViewById<TextView>(R.id.tvAppName)?.text = appName ?: ""
            v.findViewById<TextView>(R.id.tvDuration)?.text = "${safeDuration}s"
            
            val tvAddress = v.findViewById<TextView>(R.id.tvAddress)
            if (tvAddress != null) {
                if (showAddressPref && (pickup != null || destination != null)) {
                    tvAddress.visibility = VISIBLE
                    val aTimeStr = if (pickupTime != null) " ($pickupTime мин)" else ""
                    val bTimeStr = if (destinationTime != null) " ($destinationTime мин)" else ""
                    
                    if (pickup != null && destination != null) {
                        tvAddress.text = "A$aTimeStr: $pickup\nB$bTimeStr: $destination"
                    } else if (destination != null) {
                        tvAddress.text = "B$bTimeStr: $destination"
                    } else if (pickup != null) {
                        tvAddress.text = "A$aTimeStr: $pickup"
                    }
                } else {
                    tvAddress.visibility = GONE
                }
            }

            v.findViewById<TextView>(R.id.tvChainWarning)?.apply {
                val warning = ChainPredictor.getChainWarning(pickup)
                if (warning != null) {
                    text = warning
                    visibility = VISIBLE
                } else {
                    visibility = GONE
                }
            }
            
            v.findViewById<TextView>(R.id.tvColleagues)?.text = "👥 ${DriverNetworkManager.getNearbyColleagues()}"
            v.findViewById<TextView>(R.id.tvSyncStatus)?.apply {
                alpha = if (System.currentTimeMillis() % 10000 > 8000) 1.0f else 0.4f
            }
            
            val vVerdictGlow = v.findViewById<View>(R.id.vVerdictGlow)
            val tvVerdict = v.findViewById<TextView>(R.id.tvVerdict)
            
            if (!showAiPref) {
                tvVerdict?.visibility = GONE
                vVerdictGlow?.visibility = GONE
            } else if (tvVerdict != null) {
                var verdictText = "ОЦЕНКА ЗАКАЗА"
                var verdictColor = 0xFFFFFFFF.toInt()
                var glowColor = 0x00000000.toInt()
                var animatePulse = false
                
                var mlPredictedStrategy = -1
                var mlConfidenceText = "[AI: $confidence% БАЛ]"
                if (mlModel != null) {
                    try {
                        val approxPrice = hourlyRate * (minutes / 60f)
                        val inputs = arrayOf(floatArrayOf(
                            ((approxPrice - 80f) / 50f).toFloat(), 
                            (surge.toFloat() - 1.0f) * 2f, 
                            (rating?.toFloat() ?: 4.8f) - 4.5f, 
                            ((minutes - 30f) / 15f).toFloat(),
                            ((km - 15f) / 10f).toFloat()
                        ))
                        val outputs = arrayOf(floatArrayOf(0f, 0f, 0f, 0f))
                        mlModel?.run(inputs, outputs)
                        
                        var maxIdx = 0
                        var maxVal = outputs[0][0]
                        for (i in 1..3) {
                            if (outputs[0][i] > maxVal) {
                                maxVal = outputs[0][i]
                                maxIdx = i
                            }
                        }
                        mlPredictedStrategy = maxIdx + 1
                        mlConfidenceText = " [AI: ${(maxVal * 100).toInt()}% " + when(mlPredictedStrategy) {
                            1 -> "ЖИР]"
                            2 -> "БАЛ]"
                            3 -> "РАДИ%]"
                            else -> "ДАЛЬН]"
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }

                val lowerPickup = pickup?.lowercase() ?: ""
                val lowerDest = destination?.lowercase() ?: ""
                val isGolden = lowerDest.contains("lotnisko") || lowerDest.contains("centralny") || 
                              lowerDest.contains("tarasy") || lowerDest.contains("chmielna") || 
                              lowerDest.contains("centrum") || lowerDest.contains("złote")
                val isDeadZone = lowerDest.contains("wawer") || lowerDest.contains("białołęka") || 
                                lowerDest.contains("targówek") || lowerDest.contains("ursus") || 
                                lowerDest.contains("rembertów") || lowerDest.contains("wesoła") || 
                                lowerDest.contains("wilanów") || lowerDest.contains("kabaty")
                
                val minHourlyRate = prefs.getFloat("min_hourly_rate", 60f)
                val maxTimeSetting = prefs.getInt("max_time", 40)
                val maxDistSetting = prefs.getFloat("max_distance", 30f)

                val isGoodOrder = when (currentStrategy) {
                    1 -> hourlyRate >= (minHourlyRate * 1.3)
                    2 -> hourlyRate >= minHourlyRate
                    3 -> minutes <= 15 && hourlyRate >= (minHourlyRate * 0.7)
                    4 -> hourlyRate >= minHourlyRate && minutes <= maxTimeSetting && km <= maxDistSetting
                    else -> hourlyRate >= minHourlyRate
                }

                val tvRate = v.findViewById<TextView>(R.id.tvHourlyRate)
                val tvK = v.findViewById<TextView>(R.id.tvKm)
                val tvM = v.findViewById<TextView>(R.id.tvMinutes)
                val isTrusted = confidence >= 85
                val trustedPrefix = if (isTrusted) "🛡️ [TRUSTED] " else ""

                if (isGolden) {
                    verdictText = "$trustedPrefix💎 ЗОЛОТОЙ ТРАНЗИТ (БРАТЬ!)" + mlConfidenceText
                    verdictColor = 0xFF00FF7F.toInt()
                    glowColor = 0x6600FF7F
                    tvRate?.setTextColor(0xFF00FF7F.toInt())
                } else if (isDeadZone) {
                    verdictText = "${trustedPrefix}🗑 МЕРТВАЯ ЗОНА (ИГНОР)" + mlConfidenceText
                    verdictColor = 0xFFAAAAAA.toInt()
                    glowColor = 0x88333333.toInt()
                    tvRate?.setTextColor(0xFFAAAAAA.toInt())
                } else if (!isGoodOrder) {
                    verdictText = trustedPrefix + when (currentStrategy) {
                        1 -> "🚨 НЕ ВЫГОДНО (НИЗКИЙ ЧЕК)"
                        3 -> "⏳ СЛИШКОМ ДОЛГО (ДЛЯ %)"
                        4 -> "❌ ФИЛЬТР: НЕ СООТВЕТСТВУЕТ"
                        else -> "🚨 ИГНОР: Меньше $minHourlyRate zł/ч"
                    } + mlConfidenceText
                    verdictColor = 0xFFFF4D4D.toInt()
                    glowColor = 0x88FF0000.toInt()
                    animatePulse = true
                    tvRate?.setTextColor(0xFFFF4D4D.toInt())
                } else {
                    verdictText = if (isHighlyProfitable) {
                        trustedPrefix + "⭐ ШИКАРНЫЙ ЗАКАЗ (Margin +15%)"
                    } else {
                        trustedPrefix + when(currentStrategy) {
                            1 -> "🔥 ЖИРНЫЙ ЗАКАЗ!"
                            2 -> "⚖️ ХОРОШИЙ БАЛАНС"
                            3 -> "🛡️ ПОДХОДИТ ДЛЯ %"
                            else -> "⚙️ ПРОШЕЛ ПО ФИЛЬТРАМ"
                        }
                    } + mlConfidenceText
                    verdictColor = if (isHighlyProfitable) 0xFFFFD700.toInt() else 0xFF00FF7F.toInt()
                    glowColor = if (isHighlyProfitable) 0xAAFFD700.toInt() else 0x6600FF7F.toInt()
                    animatePulse = isHighlyProfitable
                    tvRate?.setTextColor(verdictColor)
                    tvK?.setTextColor(verdictColor)
                    tvM?.setTextColor(verdictColor)
                }
                
                tvVerdict.text = verdictText
                tvVerdict.setTextColor(verdictColor)
                
                if (vVerdictGlow != null && glowColor != 0x00000000) {
                    val gradient = GradientDrawable(
                        GradientDrawable.Orientation.BOTTOM_TOP,
                        intArrayOf(glowColor, 0x00000000)
                    )
                    vVerdictGlow.background = gradient
                    if (animatePulse) {
                        val fade = AlphaAnimation(0.2f, 0.6f)
                        fade.duration = 800
                        fade.repeatMode = Animation.REVERSE
                        fade.repeatCount = Animation.INFINITE
                        vVerdictGlow.startAnimation(fade)
                    } else {
                        vVerdictGlow.clearAnimation()
                        vVerdictGlow.alpha = 0.4f
                    }
                }
            }

            v.findViewById<View>(R.id.btnClose)?.setOnClickListener { hide() }
            val btnNav = v.findViewById<View>(R.id.btnNavigate)
            if (destination != null) {
                btnNav?.visibility = VISIBLE
                btnNav?.setOnClickListener {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}"))
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        ctx2.startActivity(intent)
                    } catch (e: Exception) {}
                }
            }

            // 🐞 Кнопка отчёта об ошибке (чёрный ящик)
            val btnBug = v.findViewById<android.widget.TextView>(R.id.btnBugReport)
            if (btnBug != null) {
                btnBug.visibility = VISIBLE
                btnBug.setOnClickListener {
                    val svc = ScreenCaptureService.instance
                    if (svc != null) {
                        svc.saveBugReport(false)
                    } else {
                        android.widget.Toast.makeText(ctx2,
                            "⚠️ Сервис не запущен — запусти сканирование",
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }


            if (mapView != null && destination != null) {
                mapView.overlays.clear()
                mapView.setMultiTouchControls(false)
                mapView.isEnabled = false
                mapView.zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)

                // 🔴 Исправлено: отменяем предыдущий поток геокодинга перед созданием нового
                geocodeThread?.interrupt()

                val city = prefs.getString("default_city", "Warsaw") ?: "Warsaw"

                geocodeThread = Thread {
                    try {
                        val list = geocoder.getFromLocationName("$destination, $city", 1)
                        if (list?.isNotEmpty() == true && !Thread.interrupted()) {
                            val point = GeoPoint(list[0].latitude, list[0].longitude)
                            handler.post {
                                val currentSharedView = sharedOverlayView ?: return@post
                                val currentMapView = mapView ?: return@post
                                if (!currentMapView.isAttachedToWindow) return@post
                                if (currentSharedView.findViewById<View>(R.id.mapView) != currentMapView) return@post
                                
                                try {
                                    val marker = Marker(currentMapView)
                                    marker.position = point
                                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                    currentMapView.overlays.add(marker)
                                    currentMapView.controller.setZoom(16.0)
                                    currentMapView.controller.setCenter(point)
                                    handler.postDelayed({
                                        if (currentMapView.isAttachedToWindow) {
                                            currentMapView.controller.animateTo(point, 12.5, 1800L)
                                        }
                                    }, 500)
                                    currentMapView.invalidate()
                                } catch (e: Exception) {
                                    Log.e("Overlay", "Error adding marker", e)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e !is InterruptedException) Log.e("Overlay", "Geocode error", e)
                    }
                }.also { it.start() }
            }

            // Цветовой фильтр карты
            if (mapView != null) {
                val matrix = when (mapStyle) {
                    "midnight" -> ColorMatrix(floatArrayOf(
                        -0.8f, 0f, 0f, 0f, 60f,
                        0f, -0.8f, 0f, 0f, 70f,
                        0.2f, 0.2f, -0.8f, 0f, 110f,
                        0f, 0f, 0f, 1f, 0f
                    ))
                    "cyberpunk" -> ColorMatrix(floatArrayOf(
                        -0.75f, 0f, 0f, 0f, 255f,
                        0f, -0.75f, 0f, 0f, 255f,
                        0f, 0f, -0.75f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    ))
                    else -> null
                }
                if (matrix != null) {
                    mapView.overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(matrix))
                } else {
                    mapView.overlayManager.tilesOverlay.setColorFilter(null)
                }
            }

            // Таймеры
            countdownRunnable?.let { handler.removeCallbacks(it) }
            startCountdown(safeDuration)
            hideRunnable?.let { handler.removeCallbacks(it) }
            hideRunnable = Runnable { hide() }
            handler.postDelayed(hideRunnable!!, (safeDuration * 1000).toLong())
        }
    }
    
    private fun createOverlayView(ctx: Context) {
        sharedOverlayView = LayoutInflater.from(ctx).inflate(R.layout.overlay_layout, null)
        currentParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            else 
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val prefs = ctx.getSharedPreferences("taxi_prefs", Context.MODE_PRIVATE)
            x = prefs.getInt("overlay_pos_x", 100)
            y = prefs.getInt("overlay_pos_y", 150)
        }
        
        try {
            val wm = getWindowManager(appContext!!)
            if (sharedOverlayView?.isAttachedToWindow == false) {
                wm.addView(sharedOverlayView, currentParams)
            }
            
            // ПРИВЯЗЫВАЕМ ЖЕСТЫ И ПЕРЕМЕЩЕНИЕ К КОРПУСУ ПЛАШКИ
            val card = sharedOverlayView?.findViewById<View>(R.id.cardContainer)
            card?.setOnTouchListener { v, event -> 
                gestureDetector.onTouchEvent(event)
                val params = currentParams ?: return@setOnTouchListener false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        getWindowManager(ctx).updateViewLayout(sharedOverlayView, params)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val prefs = ctx.getSharedPreferences("taxi_prefs", Context.MODE_PRIVATE)
                        prefs.edit()
                            .putInt("overlay_pos_x", params.x)
                            .putInt("overlay_pos_y", params.y)
                            .apply()
                        v.performClick()
                        true
                    }
                    else -> false
                }
            }
            val mapContainer = sharedOverlayView?.findViewById<View>(R.id.mapContainer)
            mapContainer?.setOnTouchListener { v, event ->
                val ctxLocal = appContext ?: context
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialY = v.height
                        initialTouchY = event.rawY
                        v.parent.requestDisallowInterceptTouchEvent(true)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaY = (event.rawY - initialTouchY).toInt()
                        val minH = (50 * ctxLocal.resources.displayMetrics.density).toInt()
                        val maxH = (400 * ctxLocal.resources.displayMetrics.density).toInt()
                        val newHeight = (initialY + deltaY).coerceIn(minH, maxH)
                        
                        val lp = v.layoutParams
                        lp.height = newHeight
                        v.layoutParams = lp
                        
                        val prefs = ctxLocal.getSharedPreferences("taxi_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putFloat("overlay_map_height", newHeight / ctxLocal.resources.displayMetrics.density).apply()
                        true
                    }
                    else -> false
                }
            }

            val mapView = sharedOverlayView?.findViewById<View>(R.id.mapView)
            mapView?.setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                false
            }
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (!msg.contains("already been added")) {
                Log.e("Overlay", "Failed to add view: $msg")
                sharedOverlayView = null
            }
        }
    }

    private fun startCountdown(seconds: Int) {
        var remaining = seconds
        val tv = sharedOverlayView?.findViewById<TextView>(R.id.tvDuration)
        countdownRunnable = object : Runnable {
            override fun run() {
                remaining--
                if (remaining >= 0) {
                    tv?.text = "${remaining}s"
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.postDelayed(countdownRunnable!!, 1000)
    }

    fun applyTheme(themeName: String) {
        handler.post {
            if (sharedOverlayView == null) return@post
            sharedOverlayView?.let { v ->
                val card = v.findViewById<androidx.cardview.widget.CardView>(R.id.cardContainer) ?: return@let
                when (themeName) {
                    "matrix" -> {
                        card.setCardBackgroundColor(0xFF000000.toInt())
                        v.findViewById<TextView>(R.id.tvHourlyRate)?.setTextColor(0xFF00FF00.toInt())
                    }
                    "cyberpunk" -> {
                        card.setCardBackgroundColor(0xFF1A1A1A.toInt())
                        v.findViewById<TextView>(R.id.tvHourlyRate)?.setTextColor(0xFFFF00FF.toInt())
                    }
                    "stealth" -> {
                        card.alpha = 0.5f
                        v.findViewById<MapView>(R.id.mapView)?.visibility = GONE
                    }
                    else -> {
                        card.setCardBackgroundColor(0xFF000000.toInt())
                        card.alpha = 1.0f
                    }
                }
            }
        }
    }

    private fun hide() {
        Log.d("Overlay", "hide() called")
        handler.post {
            if (sharedOverlayView == null) return@post
            sharedOverlayView?.let { v ->
                try {
                    val mapView = v.findViewById<MapView>(R.id.mapView)
                    if (mapView != null && mapView.isAttachedToWindow) {
                        mapView.onDetach()
                    }
                } catch (e: Exception) {
                    Log.e("Overlay", "Error detaching map", e)
                }
                try { 
                    val wm = getWindowManager(appContext!!)
                    if (v.isAttachedToWindow) {
                        wm.removeView(v)
                    }
                } catch (e: Exception) {
                    Log.e("Overlay", "Error removing view", e)
                }
                sharedOverlayView = null
                currentParams = null
            }
            handler.removeCallbacksAndMessages(null)
            hideRunnable = null
            countdownRunnable = null
        }
    }

    private fun Int.toPx(context: android.content.Context): Int = 
        (this * context.resources.displayMetrics.density).toInt()

    private fun Float.toPx(context: android.content.Context): Int = 
        (this * context.resources.displayMetrics.density).toInt()
}