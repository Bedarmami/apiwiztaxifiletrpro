package com.example.taxifilter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.example.taxifilter.ui.theme.TaxiFilterTheme

class AnalyticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StatsManager.init(this)
        setContent {
            TaxiFilterTheme {
                AnalyticsScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
fun AnalyticsScreen(onBack: () -> Unit) {
    var isLoading by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf(0f) }
    var loadingText by remember { mutableStateOf("Загрузка данных...") }

    // Анимация загрузки
    LaunchedEffect(Unit) {
        val steps = listOf(
            "INITIALIZING ANALYTICS ENGINE..." to 0.1f,
            "CONNECTING TO TRIP DATABASE..." to 0.25f,
            "PARSING ORDER ARCHIVES..." to 0.4f,
            "CALCULATING FUEL CONSUMPTION..." to 0.55f,
            "MAPPING KILOMETERS TO PROFIT..." to 0.75f,
            "FINALIZING DASHBOARD..." to 0.9f,
            "READY!" to 1.0f
        )
        for (step in steps) {
            loadingText = step.first
            val target = step.second
            while (progress < target) {
                progress += 0.05f
                delay(50)
            }
            delay(300)
        }
        delay(500)
        isLoading = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F0F))
    ) {
        if (isLoading) {
            LoadingView(progress, loadingText)
        } else {
            StatsDashboard(onBack)
        }
    }
}

@Composable
fun LoadingView(progress: Float, text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "RESOURCES LOADING",
            color = Color.Cyan,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Box(
            modifier = Modifier
                .width(250.dp)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color.DarkGray)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF00BFA5), Color(0xFF00E5FF))
                        )
                    )
            )
        }
        
        Text(
            text = text,
            color = Color.LightGray,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}

@Composable
fun StatsDashboard(onBack: () -> Unit) {
    val stats = StatsManager.getAllStats()
    val today = StatsManager.getTodayStats()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = Color.White)
            }
            Text(
                "СТАТИСТИКА ВОДИТЕЛЯ",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Главная карточка (Сегодня)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("СЕГОДНЯ", color = Color.Gray, fontSize = 12.sp)
                Text(
                    "Чистая прибыль: ${"%.2f".format(today.netProfit)} zł",
                    color = Color(0xFF00E676),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.DarkGray)
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatItem("Грязными", "${"%.2f".format(today.totalIncome)} zł")
                    StatItem("Расход", "${"%.2f".format(today.totalExpenses)} zł")
                    StatItem("Заказов", "${today.orderCount}")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text("ИСТОРИЯ ПО ДНЯМ", color = Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(stats) { day ->
                HistoryRow(day)
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column {
        Text(label, color = Color.Gray, fontSize = 10.sp)
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun HistoryRow(stats: DailyStats) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1A1A1A))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(stats.date, color = Color.White, fontSize = 14.sp)
            Text("${stats.orderCount} поезда(ок)", color = Color.Gray, fontSize = 11.sp)
        }
        
        Column(horizontalAlignment = Alignment.End) {
            Text("+${"%.2f".format(stats.netProfit)} zł", color = Color(0xFF00E676), fontWeight = FontWeight.Bold)
            Text("${"%.1f".format(stats.totalDistance)} км", color = Color.Gray, fontSize = 11.sp)
        }
    }
}
