package com.example.taxifilter

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.util.Locale

/**
 * CityManager is responsible for learning city names and garbage words automatically.
 * Ported from the original 1H PROFIT APK logic.
 */
class CityManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("city_verification_data", Context.MODE_PRIVATE)

    /**
     * Attempts to find a verified city name for a given raw string.
     */
    fun getVerifiedCity(rawCity: String): String? {
        val trimmed = rawCity.trim()
        if (trimmed.isEmpty()) return null
        
        // Check direct map
        val mapped = prefs.getString("map_$trimmed", null)
        if (mapped != null) return mapped

        // Search for partial matches (if raw string contains a known city name)
        val all = prefs.all
        var bestMatch: String? = null
        var maxLength = 0

        for ((key, value) in all) {
            if (key.startsWith("map_") && value is String) {
                val cityKey = key.substring(4)
                if (trimmed.contains(cityKey, ignoreCase = true) && cityKey.length > maxLength) {
                    maxLength = cityKey.length
                    bestMatch = value
                }
            }
        }
        return bestMatch
    }

    /**
     * Learns from a raw/detected city pair. 
     * If a pair is seen 20 times with >50% consistency, it becomes a verified map.
     */
    fun learnCity(rawCity: String, verifiedCity: String) {
        val raw = rawCity.trim()
        val verified = verifiedCity.trim()
        
        if (raw.length < 3 || raw.length > 50 || raw == verified) return
        if (getVerifiedCity(raw) != null) return

        val editor = prefs.edit()
        val totalKey = "total_$raw"
        val count = prefs.getInt(totalKey, 0) + 1
        editor.putInt(totalKey, count)

        val resultsKey = "results_$raw"
        val resultsJson = prefs.getString(resultsKey, "{}")
        val json = try { JSONObject(resultsJson) } catch (e: Exception) { JSONObject() }
        
        json.put(verified, json.optInt(verified, 0) + 1)
        editor.putString(resultsKey, json.toString())

        // Consistent threshold (Ported from 1H PROFIT: 20 hits)
        if (count >= 20) {
            var mostFrequent: String? = null
            var maxFreq = 0
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = json.getInt(k)
                if (v > maxFreq) {
                    maxFreq = v
                    mostFrequent = k
                }
            }

            if (mostFrequent != null && (maxFreq.toDouble() / count) >= 0.5) {
                Log.d("CityManager", "Learned city: $raw -> $mostFrequent")
                editor.putString("map_$raw", mostFrequent)
                editor.remove(totalKey)
                editor.remove(resultsKey)
                
                // Also learn garbage words by diffing
                learnGarbage(raw, mostFrequent)
            }
        }
        editor.apply()
    }

    private fun learnGarbage(raw: String, verified: String) {
        val rawWords = raw.lowercase().split(Regex("[ ,]")).filter { it.length > 2 }
        val verifiedWords = verified.lowercase().split(Regex("[ ,]"))
        
        val editor = prefs.edit()
        for (word in rawWords) {
            if (!verifiedWords.contains(word)) {
                editor.putBoolean("garbage_$word", true)
                Log.d("CityManager", "Learned garbage word: $word")
            }
        }
        editor.apply()
    }

    fun isGarbage(word: String): Boolean {
        val low = word.lowercase().trim()
        if (low.length < 3) return true
        return prefs.getBoolean("garbage_$low", false)
    }
}
