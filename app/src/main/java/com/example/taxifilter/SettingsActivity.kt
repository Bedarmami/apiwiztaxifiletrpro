package com.example.taxifilter

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.taxifilter.ui.theme.TaxiFilterTheme

class SettingsActivity : ComponentActivity() {

    private val scanAreaLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val intent = Intent(this, ScanAreaActivity::class.java).apply {
                setData(it)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } ?: Toast.makeText(this, "Выбор отменён", Toast.LENGTH_SHORT).show()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TaxiFilterTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = { Text("Настройки", color = Color.White) },
                                navigationIcon = {
                                    IconButton(onClick = { finish() }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Color.White)
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E1E1E))
                            )
                        }
                    ) { padding ->
                        SettingsScreen(
                            modifier = Modifier.padding(padding),
                            onPickScanAreaImage = { scanAreaLauncher.launch("image/*") }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(modifier: Modifier = Modifier, onPickScanAreaImage: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("taxi_prefs", Context.MODE_PRIVATE) }

    var minHourlyRate by remember { mutableStateOf(prefs.getFloat("min_hourly_rate", 60f).toInt().toString()) }
    var maxTime       by remember { mutableStateOf(prefs.getInt("max_time", 40).toString()) }
    var maxDistance   by remember { mutableStateOf(prefs.getFloat("max_distance", 30f).toString()) }
    var costPerKm     by remember { mutableStateOf(prefs.getFloat("cost_per_km", 0.30f).toString()) }
    var loadingTime   by remember { mutableStateOf(prefs.getInt("loading_time", 2).toString()) }
    var overlayDuration by remember { mutableStateOf(prefs.getInt("overlay_duration", 10).toString()) }
    var soundType by remember { mutableStateOf(prefs.getString("notification_sound", "system") ?: "system") }
    var mapStyle by remember { mutableStateOf(prefs.getString("map_style", "midnight") ?: "midnight") }
    var tgEnabled by remember { mutableStateOf(prefs.getBoolean("tg_enabled", true)) }
    var tgLogIgnored by remember { mutableStateOf(prefs.getBoolean("tg_log_ignored", false)) }
    var tgChatId by remember { mutableStateOf(prefs.getString("tg_chat_id", "") ?: "") }

    // --- НАСТРОЙКИ ПЛАШКИ ---
    var overlayScale by remember { mutableStateOf(prefs.getFloat("overlay_scale", 1.0f)) }
    var showOverlayMap by remember { mutableStateOf(prefs.getBoolean("show_overlay_map", true)) }
    var showAiVerdict by remember { mutableStateOf(prefs.getBoolean("show_ai_verdict", true)) }
    var showOverlayStats by remember { mutableStateOf(prefs.getBoolean("show_overlay_stats", true)) }
    var showOverlayDetails by remember { mutableStateOf(prefs.getBoolean("show_overlay_details", true)) }
    var showOverlayAddress by remember { mutableStateOf(prefs.getBoolean("show_overlay_address", true)) }
    var showOverlayRoute by remember { mutableStateOf(prefs.getBoolean("show_overlay_route", true)) }
    var showFloatingStats by remember { mutableStateOf(prefs.getBoolean("show_floating_stats", true)) }
    
    // --- ПОЛНАЯ КАСТОМИЗАЦИЯ ---
    var overlayWidth by remember { mutableStateOf(prefs.getFloat("overlay_width", 320f)) }
    var overlayMapHeight by remember { mutableStateOf(prefs.getFloat("overlay_map_height", 140f)) }
    var overlayCornerRadius by remember { mutableStateOf(prefs.getFloat("overlay_corner_radius", 20f)) }
    var overlayOpacity by remember { mutableStateOf(prefs.getFloat("overlay_opacity", 1.0f)) }
    var enableOverlay by remember { mutableStateOf(prefs.getBoolean("enable_overlay", true)) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- ЖИВОЙ ПРЕДПРОСМОТР ПЛАШКИ ---
        Text("ПРЕДПРОСМОТР ПЛАШКИ (ЖИВОЙ РЕЖИМ)", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(Color(0xFF000000), RoundedCornerShape(12.dp))
                .padding(10.dp),
            contentAlignment = Alignment.Center
        ) {
            // Фон имитирующий приложение такси
            Text("ИМИТАЦИЯ ЭКРАНА ЗАКАЗА", color = Color(0xFF222222), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            
            // САМА ПЛАШКА (ПРЕВЬЮ)
            Card(
                modifier = Modifier
                    .width(overlayWidth.dp)
                    .graphicsLayer(scaleX = overlayScale, scaleY = overlayScale)
                    .alpha(overlayOpacity),
                shape = RoundedCornerShape(overlayCornerRadius.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                elevation = CardDefaults.elevatedCardElevation(6.dp)
            ) {
                Column {
                    // Имитация карты
                    if (showOverlayMap) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(overlayMapHeight.dp)
                                .background(Color(0xFF2C2C2C))
                        ) {
                            Text("📍 КАРТА", modifier = Modifier.align(Alignment.Center), color = Color.DarkGray, fontSize = 10.sp)
                            // Кнопка закрытия
                            Box(modifier = Modifier.padding(6.dp).size(20.dp).background(Color(0xFF333333), CircleShape).align(Alignment.TopEnd)) {
                                Text("×", color = Color.White, modifier = Modifier.align(Alignment.Center), fontSize = 12.sp)
                            }
                        }
                    }
                    
                    Column(modifier = Modifier.padding(10.dp)) {
                        if (showOverlayStats) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("185", color = Color(0xFF00FF7F), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Text(" zł/ч", color = Color.Gray, fontSize = 10.sp)
                                Spacer(Modifier.width(8.dp))
                                Box(modifier = Modifier.width(1.dp).height(14.dp).background(Color(0xFF333333)))
                                Spacer(Modifier.width(8.dp))
                                Text("🔥 ЖИР", color = Color(0xFF00BFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        
                        if (showOverlayRoute) {
                            Row(modifier = Modifier.padding(top = 4.dp)) {
                                Text("4.2 km", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(" • ", color = Color.DarkGray)
                                Text("12 min", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        
                        if (showOverlayAddress) {
                            Text("A: Fort Wola 22  ➡  B: Centrum", color = Color.Gray, fontSize = 9.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                        }
                        
                        if (showAiVerdict) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .background(Color(0xFF00FF7F).copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                    .padding(4.dp)
                            ) {
                                Text("✅ ХОРОШИЙ БАЛАНС", color = Color(0xFF00FF7F), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
                            }
                        }

                        if (showOverlayDetails) {
                            Row(
                                modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("👥 12", color = Color.Gray, fontSize = 8.sp)
                                Text("☁️ SYNC", color = Color(0xFF00FF7F), fontSize = 8.sp)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        
        // --- НАСТРОЙКИ СТРАТЕГИИ ---
        var currentStrategy by remember { mutableStateOf(prefs.getInt("current_strategy", 2)) }
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = Color(0xFFFFA000))
                    Spacer(Modifier.width(8.dp))
                    Text("Стратегия Советника", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                }
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { currentStrategy = 1; prefs.edit().putInt("current_strategy", 1).apply() },
                        modifier = Modifier.weight(1f).height(46.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (currentStrategy == 1) Color(0xFF00C853) else Color(0xFF333333)),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("МАКС. ПРИБЫЛЬ", fontSize = 9.sp, color = if (currentStrategy == 1) Color.Black else Color.White, fontWeight = FontWeight.Bold) }
                    
                    Button(
                        onClick = { currentStrategy = 2; prefs.edit().putInt("current_strategy", 2).apply() },
                        modifier = Modifier.weight(1f).height(46.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (currentStrategy == 2) Color(0xFF29B6F6) else Color(0xFF333333)),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("БАЛАНС", fontSize = 10.sp, color = if (currentStrategy == 2) Color.Black else Color.White, fontWeight = FontWeight.Bold) }
                    
                    Button(
                        onClick = { currentStrategy = 3; prefs.edit().putInt("current_strategy", 3).apply() },
                        modifier = Modifier.weight(1f).height(46.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (currentStrategy == 3) Color(0xFFFFA000) else Color(0xFF333333)),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("СПАСТИ %", fontSize = 10.sp, color = if (currentStrategy == 3) Color.Black else Color.White, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { currentStrategy = 4; prefs.edit().putInt("current_strategy", 4).apply() },
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (currentStrategy == 4) Color(0xFF9C27B0) else Color(0xFF333333))
                ) { Text("СУПЕР-ОПТИМИЗАЦИЯ (РУЧНЫЕ ФИЛЬТРЫ)", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = Color(0xFF00FF7F))
                    Spacer(Modifier.width(8.dp))
                    Text("Критерии фильтрации", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                }

                Spacer(Modifier.height(12.dp))

                CriteriaField("💰 Мин. доход/час (zł/ч)", minHourlyRate) { minHourlyRate = it }
                Spacer(Modifier.height(4.dp))
                CriteriaField("⛽ Себестоимость км (zł)", costPerKm) { costPerKm = it }
                Spacer(Modifier.height(4.dp))
                CriteriaField("⏱ Макс. время заказа (мин)", maxTime) { maxTime = it }
                Spacer(Modifier.height(4.dp))
                CriteriaField("📍 Макс. расстояние (km)", maxDistance) { maxDistance = it }
                Spacer(Modifier.height(4.dp))
                CriteriaField("🚶 Доп. время загрузки (мин)", loadingTime) { loadingTime = it }
                Spacer(Modifier.height(4.dp))
                CriteriaField("🕒 Время плашки (сек)", overlayDuration) { overlayDuration = it }

                Spacer(Modifier.height(14.dp))
                
                Text(text = "🔊 Звук при заказе", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                
                Column(Modifier.selectableGroup()) {
                    SoundOption("Системный (По умолчанию)", "system", soundType) { soundType = it }
                    SoundOption("🪙 Монеты (Super Mario)", "coin", soundType) { soundType = it }
                    SoundOption("🐸 Лягушка", "frog", soundType) { soundType = it }
                    SoundOption("✨ Магия", "magic", soundType) { soundType = it }
                    SoundOption("📳 Только вибрация", "vibrate", soundType) { soundType = it }
                    SoundOption("🔇 Ничего", "none", soundType) { soundType = it }
                }

                Spacer(Modifier.height(14.dp))

                Text(text = "🗺️ Стиль карты", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                
                Column(Modifier.selectableGroup()) {
                    SoundOption("Тёмная полночь (Midnight Navy)", "midnight", mapStyle) { mapStyle = it }
                    SoundOption("Неоновый киберпанк (Black+Neon)", "cyberpunk", mapStyle) { mapStyle = it }
                    SoundOption("Матрица (Matrix Green)", "matrix", mapStyle) { mapStyle = it }
                    SoundOption("Стелс (Прозрачная)", "stealth", mapStyle) { mapStyle = it }
                    SoundOption("Стандартная (Светлая)", "standard", mapStyle) { mapStyle = it }
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = { DriverNetworkManager.syncData(context); Toast.makeText(context, "☁️ Данные синхронизированы!", Toast.LENGTH_SHORT).show() },
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF673AB7))
                ) {
                    Text("☁️ ОБЛАЧНАЯ СИНХРОНИЗАЦИЯ (SHARED)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(14.dp))

                Button(
                    onClick = {
                        prefs.edit().apply {
                            putFloat("min_hourly_rate", minHourlyRate.toFloatOrNull() ?: 60f)
                            putFloat("cost_per_km", costPerKm.toFloatOrNull() ?: 0.30f)
                            putInt("max_time", maxTime.toIntOrNull() ?: 40)
                            putFloat("max_distance", maxDistance.toFloatOrNull() ?: 30f)
                            putInt("loading_time", loadingTime.toIntOrNull() ?: 2)
                            putInt("overlay_duration", overlayDuration.toIntOrNull() ?: 10)
                            putString("notification_sound", soundType)
                            putString("map_style", mapStyle)
                            putBoolean("tg_enabled", tgEnabled)
                            putBoolean("tg_log_ignored", tgLogIgnored)
                            putString("tg_chat_id", tgChatId)
                            
                            // Новые настройки плашки
                            putFloat("overlay_scale", overlayScale)
                            putBoolean("show_overlay_map", showOverlayMap)
                            putBoolean("show_ai_verdict", showAiVerdict)
                            putBoolean("show_overlay_stats", showOverlayStats)
                            putBoolean("show_overlay_details", showOverlayDetails)
                            putBoolean("show_overlay_address", showOverlayAddress)
                            putBoolean("show_overlay_route", showOverlayRoute)
                            putBoolean("show_floating_stats", showFloatingStats)
                            
                            putFloat("overlay_width", overlayWidth)
                            putFloat("overlay_map_height", overlayMapHeight)
                            putFloat("overlay_corner_radius", overlayCornerRadius)
                            putFloat("overlay_opacity", overlayOpacity)
                            
                            putBoolean("enable_overlay", enableOverlay)
                            apply()
                        }
                        Toast.makeText(context, "✅ Сохранено!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF7F))
                ) {
                    Text("СОХРАНИТЬ", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // --- НОВАЯ КАРТОЧКА: ВНЕШНИЙ ВИД ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = Color(0xFFE91E63))
                    Spacer(Modifier.width(8.dp))
                    Text("Внешний вид плашки", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                }
                
                Spacer(Modifier.height(12.dp))
                
                Text("Размер интерфейса (Масштаб)", color = Color.Gray, fontSize = 12.sp)
                Slider(
                    value = overlayScale,
                    onValueChange = { overlayScale = it },
                    valueRange = 0.5f..1.3f,
                    steps = 8,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFE91E63), activeTrackColor = Color(0xFFE91E63))
                )
                Text("Пример: ${(overlayScale * 100).toInt()}%", color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.End))

                Spacer(Modifier.height(8.dp))
                
                Text("Ширина окна (dp)", color = Color.Gray, fontSize = 12.sp)
                Slider(
                    value = overlayWidth,
                    onValueChange = { overlayWidth = it },
                    valueRange = 200f..400f,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF00BFFF), activeTrackColor = Color(0xFF00BFFF))
                )
                Text("${overlayWidth.toInt()} dp", color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.End))
                
                Spacer(Modifier.height(8.dp))
                
                Text("Высота карты (dp)", color = Color.Gray, fontSize = 12.sp)
                Slider(
                    value = overlayMapHeight,
                    onValueChange = { overlayMapHeight = it },
                    valueRange = 50f..300f,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF00BFFF), activeTrackColor = Color(0xFF00BFFF))
                )
                Text("${overlayMapHeight.toInt()} dp", color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.End))

                Spacer(Modifier.height(8.dp))
                
                Text("Скругление углов (dp)", color = Color.Gray, fontSize = 12.sp)
                Slider(
                    value = overlayCornerRadius,
                    onValueChange = { overlayCornerRadius = it },
                    valueRange = 0f..50f,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF00BFFF), activeTrackColor = Color(0xFF00BFFF))
                )
                Text("${overlayCornerRadius.toInt()} dp", color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.End))

                Spacer(Modifier.height(8.dp))
                
                Text("Прозрачность плашки", color = Color.Gray, fontSize = 12.sp)
                Slider(
                    value = overlayOpacity,
                    onValueChange = { overlayOpacity = it },
                    valueRange = 0.2f..1.0f,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF00BFFF), activeTrackColor = Color(0xFF00BFFF))
                )
                Text("${(overlayOpacity * 100).toInt()}%", color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.End))

                Spacer(Modifier.height(8.dp))
                
                Button(
                    onClick = {
                        overlayWidth = 300f
                        overlayMapHeight = 130f
                        overlayScale = 1.0f
                        overlayCornerRadius = 16f
                        overlayOpacity = 1.0f
                    },
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF444444)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("СБРОСИТЬ РАЗМЕРЫ К СТАНДАРТУ", fontSize = 10.sp, color = Color.LightGray)
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color(0xFF333333))
                Spacer(Modifier.height(8.dp))

                OverlayToggle("🗺️ Показывать карту", showOverlayMap) { showOverlayMap = it }
                OverlayToggle("🤖 Вердикт ИИ", showAiVerdict) { showAiVerdict = it }
                OverlayToggle("📈 Статистика (доход/ч)", showOverlayStats) { showOverlayStats = it }
                OverlayToggle("📋 Детали (Рейтинг, Сурж)", showOverlayDetails) { showOverlayDetails = it }
                OverlayToggle("🏠 Адреса подачи/финиша", showOverlayAddress) { showOverlayAddress = it }
                OverlayToggle("🛤️ Расстояние и время", showOverlayRoute) { showOverlayRoute = it }
                OverlayToggle("📉 Статистика в ожидании (Мини)", showFloatingStats) { showFloatingStats = it }
                
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color(0xFF333333))
                Spacer(Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth().height(48.dp).clickable { enableOverlay = !enableOverlay },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = enableOverlay,
                        onCheckedChange = { enableOverlay = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00FF7F))
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("ВКЛЮЧИТЬ ПЛАШКУ ВООБЩЕ", color = Color(0xFF00FF7F), fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = Color(0xFF00BFFF))
                    Spacer(Modifier.width(8.dp))
                    Text("Telegram Аналитика", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                }
                
                Spacer(Modifier.height(12.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = tgEnabled,
                        onCheckedChange = { tgEnabled = it },
                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00BFFF))
                    )
                    Text("Включить отправку отчетов", color = Color.White)
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = tgLogIgnored,
                        onCheckedChange = { tgLogIgnored = it },
                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00BFFF), uncheckedColor = Color.Gray)
                    )
                    Text("Спам Режим (Слать IGNORED заказы)", color = Color.Gray, fontSize = 12.sp)
                }

                Spacer(Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = tgChatId,
                    onValueChange = { tgChatId = it },
                    label = { Text("Твой Telegram Chat ID", color = Color.Gray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00BFFF)
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                Spacer(Modifier.height(14.dp))
                
                Button(
                    onClick = {
                        prefs.edit().apply {
                            putBoolean("tg_enabled", tgEnabled)
                            putBoolean("tg_log_ignored", tgLogIgnored)
                            putString("tg_chat_id", tgChatId)
                            apply()
                        }
                        Toast.makeText(context, "🚀 Тест отправлен!", Toast.LENGTH_SHORT).show()
                        TelegramSender.sendTestMessage(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BFFF))
                ) {
                    Text("ПРОВЕРЬТЕ СВЯЗЬ (ТЕСТ)", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { onPickScanAreaImage() },
            modifier = Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(12.dp)),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A237E))
        ) {
            Text("📐 НАСТРОИТЬ ОБЛАСТЬ СКАНИРОВАНИЯ", color = Color.White, fontSize = 13.sp)
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = {
                prefs.edit().apply {
                    putInt("stats_good_orders", 0)
                    putFloat("stats_potential_profit", 0f)
                    apply()
                }
                Toast.makeText(context, "📊 Статистика сброшена", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray)
        ) {
            Text("СБРОСИТЬ СТАТИСТИКУ", color = Color.Gray)
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                // ПЕРЕД ТЕСТОМ СОХРАНЯЕМ ТЕКУЩИЕ НАСТРОЙКИ (Live Preview)
                prefs.edit().apply {
                    putFloat("min_hourly_rate", minHourlyRate.toFloatOrNull() ?: 60f)
                    putFloat("cost_per_km", costPerKm.toFloatOrNull() ?: 0.30f)
                    putInt("max_time", maxTime.toIntOrNull() ?: 40)
                    putFloat("max_distance", maxDistance.toFloatOrNull() ?: 30f)
                    putInt("loading_time", loadingTime.toIntOrNull() ?: 2)
                    putInt("overlay_duration", overlayDuration.toIntOrNull() ?: 10)
                    putString("notification_sound", soundType)
                    putString("map_style", mapStyle)
                    putBoolean("tg_enabled", tgEnabled)
                    putBoolean("tg_log_ignored", tgLogIgnored)
                    putString("tg_chat_id", tgChatId)
                    
                    putFloat("overlay_scale", overlayScale)
                    putBoolean("show_overlay_map", showOverlayMap)
                    putBoolean("show_ai_verdict", showAiVerdict)
                    putBoolean("show_overlay_stats", showOverlayStats)
                    putBoolean("show_overlay_details", showOverlayDetails)
                    putBoolean("show_overlay_address", showOverlayAddress)
                    putBoolean("show_overlay_route", showOverlayRoute)
                    putBoolean("show_floating_stats", showFloatingStats)

                    putFloat("overlay_width", overlayWidth)
                    putFloat("overlay_map_height", overlayMapHeight)
                    putFloat("overlay_corner_radius", overlayCornerRadius)
                    putFloat("overlay_opacity", overlayOpacity)
                    
                    apply()
                }

                val intent = Intent("com.example.taxifilter.TEST_ORDER").apply {
                    val randomPrice = (50..300).random()
                    val apps = listOf("Bolt", "Uber")
                    val app = apps.random()
                    putExtra("text", "$app Simulator ⭐ 4.95\nx1.5 SURGE!\n$randomPrice,50 zł\n4 min • 1,2 km (ul. Marszałкowska 1)\n30 min • 18,5 km (Lotnisko Chopina Terminal A)")
                    putExtra("force", true)
                    setPackage(context.packageName)
                }
                context.sendBroadcast(intent)
                Toast.makeText(context, "Тест запущен (настройки применены)", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(12.dp)),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100))
        ) {
            Text("🧪 ТЕСТ ПЛАШКИ (С СИМУЛЯЦИЕЙ)", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SoundOption(label: String, value: String, selectedValue: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = { onSelect(value) }),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = (value == selectedValue),
            onClick = { onSelect(value) },
            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF00FF7F), unselectedColor = Color.Gray)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White)
    }
}

@Composable
fun CriteriaField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = Color.Gray, fontSize = 13.sp) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = Color(0xFF00FF7F),
            unfocusedBorderColor = Color(0xFF444444)
        ),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
fun OverlayToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onCheckedChange(it) },
            colors = CheckboxDefaults.colors(checkedColor = Color(0xFFE91E63))
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, fontSize = 14.sp)
    }
}
