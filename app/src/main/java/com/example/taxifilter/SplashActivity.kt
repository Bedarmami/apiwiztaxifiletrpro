package com.example.taxifilter

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContent {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF0F2027)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("👑", fontSize = 64.sp)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "TAXI FILTER PRO",
                            color = Color(0xFF00FF7F),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "PREMIUM EDITION",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Если Compose упадет — хотя бы откроем MainActivity
            e.printStackTrace()
        }

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, 1500)
    }
}
