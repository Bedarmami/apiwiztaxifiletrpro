package com.example.taxifilter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Система "Самообучения". 
 * Собирает слова из ошибочных и успешных заказов, чтобы в будущем лучше фильтровать мусор.
 */
object SmartLearningManager {
    private const val FILE_NAME = "smart_learning.json"
    
    private val blackList = mutableSetOf<String>()
    private val whiteList = mutableSetOf<String>()
    private val garbageList = mutableSetOf<String>()
    private val ignoreCounter = mutableMapOf<String, Int>()
    private val cityMap = mutableMapOf<String, String>() // OCR Raw -> Verified City
    
    fun getWhitelistCount(): Int = whiteList.size
    fun getBlacklistCount(): Int = blackList.size
    fun getGarbageCount(): Int = garbageList.size
    fun getCityMappingCount(): Int = cityMap.size

    fun verifyCity(rawCity: String): String? {
        val low = rawCity.lowercase().trim()
        if (low.length < 3) return null
        return cityMap[low] ?: cityMap.entries.find { low.contains(it.key) }?.value
    }

    fun addCityMapping(raw: String, verified: String) {
        if (raw.length in 3..50 && verified.isNotEmpty()) {
            cityMap[raw.lowercase().trim()] = verified
        }
    }

    fun importCloudData(newWhite: List<String>, newBlack: List<String>, newGarbage: List<String> = emptyList()) {
        newWhite.forEach { learnFromSuccess(it) }
        newBlack.forEach { word ->
            val w = word.lowercase()
            if (!whiteList.contains(w)) {
                blackList.add(w)
            }
        }
        newGarbage.forEach { word ->
            garbageList.add(word.lowercase())
        }
    }

    fun init(context: Context) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) {
                val json = JSONObject(file.readText())
                val bArray = json.optJSONArray("blacklist")
                bArray?.let { 
                    for (i in 0 until it.length()) blackList.add(it.getString(i))
                }
                val wArray = json.optJSONArray("whitelist")
                wArray?.let {
                    for (i in 0 until it.length()) whiteList.add(it.getString(i))
                }
                val gArray = json.optJSONArray("garbage")
                gArray?.let {
                    for (i in 0 until it.length()) garbageList.add(it.getString(i))
                }
                
                val cMap = json.optJSONObject("city_map")
                cMap?.let { obj ->
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        cityMap[key] = obj.getString(key)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Когда заказ взят, мы подтверждаем эти слова
     */
    fun learnFromSuccess(address: String?) {
        if (address == null) return
        val words = address.split(" ", ",", ".", "(", ")", "-")
            .filter { it.length > 3 }
            .map { it.lowercase() }
            .filter { !isHardcodedGarbage(it) }

        words.forEach { w ->
            whiteList.add(w)
            blackList.remove(w) // Если оно было в бане, значит бан ошибочный
            ignoreCounter.remove(w)
        }
    }

    /**
     * Когда заказ проигнорирован (или исчез), копим "подозрительные" слова
     */
    fun learnFromIgnored(address: String?) {
        if (address == null) return
        val words = address.split(" ", ",", ".", "(", ")", "-").filter { it.length > 3 }
        words.forEach {
            val w = it.lowercase()
            if (!whiteList.contains(w)) {
                val count = ignoreCounter.getOrDefault(w, 0) + 1
                ignoreCounter[w] = count
                // Если слово встретилось в 10 подозрительных заказах и ни одного разу в успешном - в бан
                if (count >= 10) {
                    blackList.add(w)
                }
            }
        }
    }

    /**
     * Возвращает "Оценку уверенности" в адресе
     */
    fun getConfidence(address: String?): Int {
        if (address == null || address.length < 5 || address.equals("Unknown", ignoreCase = true)) return 0
        val words = address.lowercase().split(" ", ",", ".", "-").filter { it.length > 2 }
        
        // Базовая уверенность ниже, если слов мало
        var score = if (words.size > 2) 40 else 20
        
        words.forEach {
            if (whiteList.contains(it)) score += 15
            if (blackList.contains(it)) score -= 30
        }
        return score.coerceIn(0, 100)
    }

    private fun isHardcodedGarbage(word: String): Boolean {
        val low = word.lowercase()
        return low.contains("miui") || low.contains("android") || low.contains("безопасн") || 
               low.contains("доступ") || low.contains("система") || low.contains("huawei") ||
               low.contains("samsung") || low.contains("xiaomi") || low.contains("charge") ||
               low.contains("mock") || low.contains("location") || low.contains("music") || 
               low.contains("активн") || low.contains("прилож") || low.contains("телефон") ||
               low.contains("уведомл") || low.contains("настройк") || low.contains("экрана")
    }

    fun isLikelyGarbage(line: String): Boolean {
        val lower = line.lowercase()
        val words = lower.split(" ", ",", ".", "(", ")", "-").filter { it.length > 2 }
        
        return blackList.any { lower.contains(it) } || 
               garbageList.any { lower.contains(it) } ||
               words.any { isHardcodedGarbage(it) }
    }

    fun save(context: Context) {
        try {
            val json = JSONObject()
            json.put("blacklist", JSONArray(blackList.toList()))
            json.put("whitelist", JSONArray(whiteList.toList()))
            json.put("garbage", JSONArray(garbageList.toList()))
            
            val cMap = JSONObject()
            cityMap.forEach { (k, v) -> cMap.put(k, v) }
            json.put("city_map", cMap)
            
            val file = File(context.filesDir, FILE_NAME)
            file.writeText(json.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
