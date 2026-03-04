package com.example.taxifilter

import android.os.Handler
import android.os.Looper
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

object VersionChecker {
    // ==========================================
    // ССЫЛКА НА ГЛОБАЛЬНЫЙ РУБИЛЬНИК ВЕРСИЙ
    // ==========================================
    // Создай текстовый файл на GitHub Gist (или любом своем сервере) с текстом:
    // {"latest_version": 2, "update_url": "https://t.me/твой_канал"}
    // Вставь прямую (RAW) ссылку на этот файл сюда:
    private const val VERSION_URL = "https://raw.githubusercontent.com/vlad-taxi/taxi-config/main/version.json"
    
    // Твоя ТЕКУЩАЯ версия. Для удобства сравнения используем целые числа (1.1 = 11, 2.0 = 20)
    const val CURRENT_APP_VERSION = 11
    
    fun checkUpdate(onUpdateRequired: (String) -> Unit) {
        val client = OkHttpClient()
        val request = Request.Builder().url(VERSION_URL).build()
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Если нет интернета - просто пускаем в приложение
            }
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    try {
                        val json = JSONObject(response.body?.string() ?: "")
                        val latestVersion = json.optInt("latest_version", CURRENT_APP_VERSION)
                        val updateUrl = json.optString("update_url", "https://t.me/")
                        
                        if (latestVersion > CURRENT_APP_VERSION) {
                            Handler(Looper.getMainLooper()).post {
                                onUpdateRequired(updateUrl)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                response.close()
            }
        })
    }
}
