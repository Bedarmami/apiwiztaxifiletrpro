package com.example.taxifilter

import android.content.Intent
import android.os.Bundle
import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.taxifilter.ui.theme.TaxiFilterTheme
import android.content.Context.MODE_PRIVATE
import android.media.projection.MediaProjectionManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.util.Log

fun openUrl(context: Context, url: String) {
    if (url == null || url.isEmpty()) return
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.e("UI", "Error opening URL: $url")
    }
}

class MainActivity : ComponentActivity() {

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("RESULT_DATA", result.data)
            }
            startForegroundService(intent)
        }
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { analyzeImage(it) } ?: Toast.makeText(this, "Выбор отменён", Toast.LENGTH_SHORT).show()
    }

    private val locationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Доступ к GPS отклонен. Некоторые функции будут ограничены.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun analyzeImage(uri: Uri) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val image = InputImage.fromFilePath(this, uri)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val text = visionText.text
                    if (text.isNotEmpty()) {
                        val orderInfo = OrderParser.parse(text)
                        
                        val prefs = getSharedPreferences("taxi_prefs", Context.MODE_PRIVATE)
                        val costPerKm = prefs.getFloat("cost_per_km", 0.30f)
                        val loadingTime = prefs.getInt("loading_time", 2)
                        val overlayDuration = prefs.getInt("overlay_duration", 10)

                        val totalMinutes = (if (orderInfo.timeToClient > 0) orderInfo.timeToClient else 30) + loadingTime
                        val runningCost = orderInfo.estimatedDistance * costPerKm
                        val netEarning = orderInfo.price - runningCost
                        val hourlyRate = (netEarning / (totalMinutes / 60.0)).toInt()

                        val testOverlay = OverlayController(this)
                        testOverlay.showOrderInfo(
                            hourlyRate = hourlyRate,
                            km = orderInfo.estimatedDistance,
                            minutes = orderInfo.timeToClient,
                            pickup = orderInfo.pickupAddress,
                            destination = orderInfo.destinationAddress,
                            durationSec = overlayDuration
                        )
                    } else {
                        Toast.makeText(this, "Текст не найден на фото", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Ошибка OCR: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка при открытии фото", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TaxiFilterTheme(darkTheme = true) {
                val gradient = androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364))
                )
                Surface(modifier = Modifier.fillMaxSize().background(gradient), color = Color.Transparent) {
                    TaxiFilterScreen(
                        onStartOCR = {
                            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                val config = android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
                                projectionLauncher.launch(manager.createScreenCaptureIntent(config))
                            } else {
                                projectionLauncher.launch(manager.createScreenCaptureIntent())
                            }
                        },
                        onStopOCR = { stopService(Intent(this, ScreenCaptureService::class.java)) },
                        onPickGallery = { galleryLauncher.launch("image/*") },
                        onLocationPermissionRequest = { locationPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION) }
                    )
                }
            }
        }
    }
}

@Composable
fun TaxiFilterScreen(
    onStartOCR: () -> Unit, 
    onStopOCR: () -> Unit, 
    onPickGallery: () -> Unit, 
    onLocationPermissionRequest: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { context.getSharedPreferences("taxi_prefs", MODE_PRIVATE) }

    // Новые статы для Дашборда
    var goodOrdersSeen by remember { mutableStateOf(prefs.getInt("stats_good_orders", 0)) }
    var potentialProfit by remember { mutableStateOf(prefs.getFloat("stats_potential_profit", 0f)) }

    var isServiceEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var isOverlayAllowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var isOCRRunning by remember { mutableStateOf(false) }
    var isLocationEnabled by remember { mutableStateOf(context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    var showInstruction by remember { mutableStateOf(false) }
    var updateUrl by remember { mutableStateOf("") }

    // Проверяем актуальную версию при запуске
    LaunchedEffect(Unit) {
        StatsManager.init(context)
        VersionChecker.checkUpdate { newUrl ->
            updateUrl = newUrl
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isServiceEnabled = isAccessibilityServiceEnabled(context)
                isOverlayAllowed = Settings.canDrawOverlays(context)
                isLocationEnabled = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                
                // Обновляем статы из преференсов (которые будут писать сервисы)
                goodOrdersSeen = prefs.getInt("stats_good_orders", 0)
                potentialProfit = prefs.getFloat("stats_potential_profit", 0f)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // --- Система лицензий ---
    var licenseDataFetched by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf(DriverNetworkManager.statusMessage) }

    LaunchedEffect(Unit) {
        DriverNetworkManager.checkLicense(context) { 
            licenseDataFetched = true 
            statusMessage = DriverNetworkManager.statusMessage
        }
    }

    if (!licenseDataFetched) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color(0xFF00ff7f))
                Spacer(modifier = Modifier.height(16.dp))
                Text("Проверка лицензии...", color = Color.White)
            }
        }
        return
    }

    if (DriverNetworkManager.updateRequired) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Card(
                modifier = Modifier.padding(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xCC203A43))
            ) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ВНИМАНИЕ!", color = Color.Red, style = MaterialTheme.typography.headlineMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(DriverNetworkManager.statusMessage, color = Color.White, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { openUrl(context, DriverNetworkManager.apkDownloadLink) }, 
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00d2ff))
                    ) {
                        Text("СКАЧАТЬ ОБНОВЛЕНИЕ")
                    }
                }
            }
        }
        return
    }

    if (!DriverNetworkManager.isLicenseActive) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Card(
                modifier = Modifier.padding(24.dp).border(1.dp, Color(0xFF00ff7f), RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xEE050505))
            ) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("АКТИВАЦИЯ", color = Color(0xFF00ff7f), style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                    Text("Ваше устройство: ${deviceId}", color = Color.Gray, fontSize = 10.sp)
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        label = { Text("Введите ключ активации") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00ff7f),
                            unfocusedBorderColor = Color.Gray,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    
                    if (statusMessage.isNotEmpty()) {
                        Text(statusMessage, color = if (statusMessage.contains("!")) Color.Red else Color.Gray, modifier = Modifier.padding(top = 8.dp))
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Button(
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        onClick = {
                            DriverNetworkManager.activate(context, keyInput) { success, msg ->
                                statusMessage = msg
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00ff7f))
                    ) {
                        Text("АКТИВИРОВАТЬ", color = Color.Black, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    TextButton(onClick = { openUrl(context, DriverNetworkManager.botRegistrationLink) }) {
                        Text("ПОЛУЧИТЬ ДОСТУП (TELEGRAM)", color = Color(0xFF00d2ff))
                    }
                }
            }
        }
        return
    }
    // --- Конец системы лицензий ---

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = " TAXI FILTER PRO",
            fontSize = 32.sp, fontWeight = FontWeight.Black,
            color = Color(0xFF00FF7F),
            letterSpacing = 2.sp,
            modifier = Modifier.padding(top = 32.dp, bottom = 8.dp)
        )
        Text(
            text = "PREMIUM EDITION — VARSAVIA",
            fontSize = 12.sp, color = Color.Gray,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        if (!isServiceEnabled || !isOverlayAllowed) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isServiceEnabled) {
                    StatusCard("СЕРВИС", isServiceEnabled, Modifier.weight(1f)) {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                }
                if (!isOverlayAllowed) {
                    StatusCard("ПЛАШКА", isOverlayAllowed, Modifier.weight(1f)) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        if (!isLocationEnabled) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusCard("ЛОКАЦИЯ", isLocationEnabled, Modifier.weight(1f)) {
                    if (!isLocationEnabled) onLocationPermissionRequest()
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // --- ДАШБОРД (PREMIUM DASHBOARD) ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0x22FFFFFF)),
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x4400FF7F))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("DASHBOARD STATS", fontSize = 11.sp, fontWeight = FontWeight.Black, color = Color.Gray, letterSpacing = 2.sp)
                Spacer(Modifier.height(16.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = goodOrdersSeen.toString(), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(text = "ЗАКАЗОВ СЕГОДНЯ", fontSize = 10.sp, color = Color.Gray)
                    }
                    VerticalDivider(modifier = Modifier.width(1.dp).height(40.dp), color = Color.DarkGray)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "${potentialProfit.toInt()} zł", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00FF7F))
                        Text(text = "ОЖИДАЕМЫЙ ДОХОД", fontSize = 10.sp, color = Color.Gray)
                    }
                }
                
                
                Spacer(Modifier.height(24.dp))
                
                Button(
                    onClick = { context.startActivity(Intent(context, AnalyticsActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BFA5)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("📊 ПОДРОБНАЯ СТАТИСТИКА", fontWeight = FontWeight.Bold)
                }
                
                LinearProgressIndicator(
                    progress = if (goodOrdersSeen > 0) 0.8f else 0.1f,
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = Color(0xFF00FF7F),
                    trackColor = Color.DarkGray
                )
                
                Spacer(Modifier.height(8.dp))
                Text("🔥 АКТИВНОСТЬ: ${if (goodOrdersSeen > 5) "HIGH" else "NORMAL"}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00FF7F))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = { if (isOCRRunning) onStopOCR() else onStartOCR(); isOCRRunning = !isOCRRunning },
            modifier = Modifier.fillMaxWidth().height(60.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (isOCRRunning) Color(0xFFB71C1C) else Color(0xFF1B5E20))
        ) { 
            Text(if (isOCRRunning) "ЗАПУСК СИСТЕМЫ: ОСТАНОВИТЬ" else "ЗАПУСК СИСТЕМЫ В ФОНЕ", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White) 
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) },
            modifier = Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(12.dp)),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Icon(Icons.Default.Settings, contentDescription = null, tint = Color(0xFF00FF7F))
            Spacer(Modifier.width(8.dp))
            Text("⚙️ НАСТРОЙКИ СИСТЕМЫ", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = { showInstruction = true },
            modifier = Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(12.dp)),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D47A1))
        ) {
            Text("📖 ИНСТРУКЦИЯ (КАК ЭТО РАБОТАЕТ)", color = Color.White, fontWeight = FontWeight.Bold)
        }



        if (showInstruction) {
            InstructionDialog(onDismiss = { showInstruction = false })
        }

        // --- Блокировщик версии (АБСОЛЮТНО ПЕРЕКРЫВАЕТ ЭКРАН) ---
        if (updateUrl.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { /* Нельзя закрыть! */ },
                title = { Text("🚨 ДОСТУП ЗАКРЫТ", color = Color.Red, fontWeight = FontWeight.Bold) },
                text = { Text("Ваша версия приложения устарела и больше не поддерживается.\nОна была отключена администратором.\n\nПожалуйста, скачайте новое обновление по ссылке.", color = Color.White) },
                confirmButton = { 
                    Button(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl))) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF7F))
                    ) { 
                        Text("СКАЧАТЬ ОБНОВЛЕНИЕ", color = Color.Black, fontWeight = FontWeight.Bold) 
                    } 
                },
                containerColor = Color(0xFF222222),
                properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun InstructionDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📘 ИНСТРУКЦИЯ ПО ИИ", color = Color(0xFF00FF7F), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Добро пожаловать в Taxi Filter PRO! Это ваш умный ИИ-советник, который на лету анализирует каждый заказ и помогает вам зарабатывать больше.", color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                
                Text("🔥 4 СТРАТЕГИИ:", color = Color(0xFF00C853), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("1. МАКС. ПРИБЫЛЬ: ИИ ищет самые 'жирные' заказы с максимальной стоимостью за минуту. Отбрасывает дешевые поездки.", color = Color.LightGray, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text("2. БАЛАНС: Стандартный режим. Берет хорошие заказы, но не отказывается от средних, чтобы держать процент принятия в норме.", color = Color.LightGray, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text("3. СПАСТИ %: Если у вас падает рейтинг, ИИ будет высвечивать только короткие заказы (до 15 минут), чтобы вы быстро набрали количество и подняли свой процент.", color = Color.LightGray, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text("4. СУПЕР-ОПТИМИЗАЦИЯ: Ваш личный фильтр. ИИ переходит в ручной режим и смотрит только на те цифры (Мин/Км/Цена), которые вы ввели в настройках.", color = Color.LightGray, fontSize = 12.sp)
                
                Spacer(Modifier.height(12.dp))
                Text("💎 ЗОЛОТОЙ ТРАНЗИТ:", color = Color(0xFF29B6F6), fontWeight = FontWeight.Bold)
                Text("Помимо цен, ИИ читает адреса. Если маршрут пролегает к аэропорту, вокзалу или торговому центру (Места, где почти всегда есть обратный пассажир), ИИ подсветит его неоновым зеленым.", color = Color.LightGray, fontSize = 12.sp)
                
                Spacer(Modifier.height(12.dp))
                Spacer(Modifier.height(12.dp))
                Text("🗑 МЕРТВЫЕ ЗОНЫ:", color = Color(0xFFFF4D4D), fontWeight = FontWeight.Bold)
                Text("Если адресом назначения является отдаленный закрытый спальный район (например, глубокий Wawer), то скорее всего вам придется возвращаться пустым. ИИ вас предупредит: 'Мертвая зона'.", color = Color.LightGray, fontSize = 12.sp)

                Divider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 12.dp))

                Text("🧮 КАК ИИ СЧИТАЕТ ВЫГОДУ?", color = Color(0xFFFFCC00), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Давайте разберем пример:", color = Color.LightGray, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text("💰 Заказ: 25.00 zł\n📍 Расстояние: 10 км\n⏳ Время: 20 мин\n⛽ Расход (в настройках): 0.50 zł/км\n🕒 Ожидание клиента: 2 мин", color = Color(0xFFE0E0E0), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("1. Сначала вычитаем расходы на топливо и амортизацию:\n10 км × 0.50 zł = 5.00 zł расходов.\nВ кармане остается: 25.00 - 5.00 = 20.00 zł чистыми.", color = Color.LightGray, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Text("2. Дальше считаем реальное время:\n20 мин поездки + 2 мин ожидания клиента = 22 минуты ушло на заказ.", color = Color.LightGray, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Text("3. Наконец, приводим это к доходу В ЧАС:\nЕсли за 22 минуты заработано 20 zł, значит за 60 минут (полный час) будет заработано 54 zł/час.", color = Color.LightGray, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Text("✨ Вердикт:\nЕсли у вас в фильтре стоит 'Минимум 60 zł/час', приложение этот заказ Заблокирует (доход маловат). ИИ всегда защищает ваш карман!", color = Color(0xFF00FF7F), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = { Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E))) { Text("ПОНЯТНО", color = Color.White) } },
        containerColor = Color(0xFF222222)
    )
}



@Composable
fun StatusCard(label: String, isEnabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.height(56.dp),
        colors = CardDefaults.cardColors(containerColor = if (isEnabled) Color(0xFF1B5E20) else Color(0xFFB71C1C)),
        shape = RoundedCornerShape(12.dp),
        onClick = onClick
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = "$label: ${if (isEnabled) "ВКЛ" else "ВЫКЛ"}",
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp
            )
        }
    }
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val service = "${context.packageName}/${TaxiAccessibilityService::class.java.canonicalName}"
    val settingValue = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    return settingValue?.contains(service) == true
}