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
    
    fun getWhitelistCount(): Int = whiteList.size
    fun getBlacklistCount(): Int = blackList.size
    fun getGarbageCount(): Int = garbageList.size

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
        if (address == null || address.length < 5) return 0
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
               low.contains("samsung") || low.contains("xiaomi") || low.contains("charge")
    }

    fun isLikelyGarbage(line: String): Boolean {
        val lower = line.lowercase()
        return blackList.any { lower.contains(it) } || garbageList.any { lower.contains(it) }
    }

    fun save(context: Context) {
        try {
            val json = JSONObject()
            json.put("blacklist", JSONArray(blackList.toList()))
            json.put("whitelist", JSONArray(whiteList.toList()))
            json.put("garbage", JSONArray(garbageList.toList()))
            val file = File(context.filesDir, FILE_NAME)
            file.writeText(json.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
