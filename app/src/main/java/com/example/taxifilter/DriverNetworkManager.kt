package com.example.taxifilter

import android.content.Context
import android.provider.Settings
import android.util.Log
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Работает в реальной сети водителей через Taxi Filter Cloud.
 */
object DriverNetworkManager {
    private var nearbyColleaguesCount = 0
    
    // РЕАЛЬНЫЙ ОБЛАЧНЫЙ СЕРВЕР (Railway)
    private const val BASE_URL = "https://railway-volume-dump-production-ead4.up.railway.app" 

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val api = retrofit.create(ApiService::class.java)

    var isLicenseActive = false
    var subscriptionExpiry = 0L
    var updateRequired = false
    var statusMessage = "Проверка..."
    var botRegistrationLink = ""
    var apkDownloadLink = ""

    fun checkLicense(context: Context, onResult: (Boolean) -> Unit) {
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val currentVersion = "1.2.5" // Должно совпадать с сервером
        
        Thread {
            try {
                val response = api.checkStatus(deviceId, currentVersion).execute()
                if (response.isSuccessful) {
                    val body = response.body()
                    isLicenseActive = body?.isActive ?: false
                    subscriptionExpiry = body?.expiry ?: 0
                    updateRequired = body?.updateRequired ?: false
                    statusMessage = body?.message ?: ""
                    botRegistrationLink = body?.botLink ?: "https://t.me/your_bot"
                    apkDownloadLink = body?.apkDownloadLink ?: "https://your-site.com/app.apk"
                    onResult(isLicenseActive)
                } else {
                    onResult(false)
                }
            } catch (e: Exception) {
                Log.e("Network", "License check failed")
                onResult(false)
            }
        }.start()
    }

    fun activate(context: Context, key: String, onResult: (Boolean, String) -> Unit) {
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val request = ActivateRequest(deviceId, key)
        
        Thread {
            try {
                val response = api.activateKey(request).execute()
                if (response.isSuccessful) {
                    isLicenseActive = true
                    subscriptionExpiry = response.body()?.expiry ?: 0
                    onResult(true, "Активировано!")
                } else {
                    onResult(false, "Неверный ключ")
                }
            } catch (e: Exception) {
                onResult(false, "Ошибка сети")
            }
        }.start()
    }

    fun getNearbyColleagues(): Int {
        return nearbyColleaguesCount
    }

    fun updateRealLocation(context: Context, lat: Double, lon: Double) {
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val request = LocationRequest(deviceId, lat, lon, "Driver_${deviceId.takeLast(4)}")
        
        Thread {
            try {
                val response = api.updateLocation(request).execute()
                if (response.isSuccessful) {
                    nearbyColleaguesCount = response.body()?.nearby ?: 0
                }
            } catch (e: Exception) {
                Log.e("Network", "Location update failed: ${e.message}")
            }
        }.start()
    }

    fun syncData(context: Context) {
        Thread {
            try {
                val response = api.getIntel().execute()
                if (response.isSuccessful) {
                    val intel = response.body()
                    if (intel != null) {
                        SmartLearningManager.importCloudData(intel.whitelist, intel.blacklist)
                        Log.d("Network", "Облачная база знаний синхронизирована: W=${intel.whitelist.size}, B=${intel.blacklist.size}")
                    }
                }
            } catch (e: Exception) {
                Log.e("Network", "Sync failed: ${e.message}")
            }
        }.start()
    }

    fun logOrderToCloud(
        context: Context, 
        price: Double, 
        km: Double, 
        dest: String, 
        pickup: String? = null,
        lat: Double? = null,
        lon: Double? = null,
        app: String? = null,
        status: String? = "NEW",
        rawText: String? = null,
        screenshot: String? = null
    ) {
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val request = OrderLogRequest(price, km, dest, pickup, lat, lon, app, status, deviceId, rawText, screenshot)
        
        Thread {
            try {
                Log.d("Network", "Отправка заказа: $price zł (${app ?: "???"}) на сервер...")
                val response = api.logOrder(request).execute()
                if (response.isSuccessful) {
                    Log.d("Network", "Заказ успешно сохранен в облако!")
                } else {
                    Log.e("Network", "Ошибка сервера при сохранении заказа: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e("Network", "Ошибка сети при отправке заказа: ${e.message}")
            }
        }.start()
    }
}
