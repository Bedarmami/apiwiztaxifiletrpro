package com.example.taxifilter

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import android.util.Log

/**
 * Адаптированный код из 1H PROFIT (EA.java)
 * Позволяет проверять адреса через внешнее API и геокодировать их для точности.
 */
object AddressVerifier {
    private val client = OkHttpClient()
    private val MAPTILER_KEY = "Nn5g5Ngwb4LswKlatPg1" // Ключ из 1H PROFIT
    private val TAG = "AddressVerifier"

    data class GeoResult(
        val lat: Double,
        val lon: Double,
        val cityName: String?,
        val confidence: Double,
        val placeName: String?
    )

    fun verify(address: String): GeoResult? {
        try {
            val encoded = URLEncoder.encode(address, "UTF-8")
            val url = "https://api.maptiler.com/geocoding/$encoded.json?key=$MAPTILER_KEY&limit=1&language=pl,en"
            
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val features = json.optJSONArray("features") ?: return null
                
                if (features.length() > 0) {
                    val feature = features.getJSONObject(0)
                    val center = feature.optJSONArray("center")
                    val lon = center?.optDouble(0) ?: 0.0
                    val lat = center?.optDouble(1) ?: 0.0
                    val placeName = feature.optString("place_name")
                    
                    // В 1H PROFIT тут еще идет расчет Levenshtein distance (метод EA.d)
                    // Но для начала просто вернем результат
                    return GeoResult(lat, lon, null, 1.0, placeName)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying address: ${e.message}")
        }
        return null
    }

    /**
     * Расчет редакционного расстояния (Levenshtein) - перенесено из EA.d() 1H PROFIT
     */
    fun calculateSimilarity(str1: String, str2: String): Double {
        val s1 = str1.lowercase(Locale.ROOT).trim()
        val s2 = str2.lowercase(Locale.ROOT).trim()
        
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        
        val dp = IntArray(s2.length + 1) { it }
        for (i in 1..s1.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..s2.length) {
                val temp = dp[j]
                if (s1[i - 1] == s2[j - 1]) {
                    dp[j] = prev
                } else {
                    dp[j] = 1 + minOf(dp[j], dp[j - 1], prev)
                }
                prev = temp
            }
        }
        val maxLen = maxOf(s1.length, s2.length)
        return (maxLen - dp[s2.length]).toDouble() / maxLen
    }
}
