package com.example.taxifilter

import android.location.Location

/**
 * Анализирует цепочку заказов. 
 * Предсказывает, насколько удобно брать следующий заказ после текущего.
 */
object ChainPredictor {
    private var lastDestination: String? = null
    
    fun setLastDestination(dest: String?) {
        lastDestination = dest
    }

    fun isGoodChain(currentPickup: String?): Boolean {
        if (lastDestination == null || currentPickup == null) return true
        
        // В идеале тут должен быть расчет расстояния через Google Maps или Haversine
        // Для начала - простое сравнение строк или ключевых слов района
        val d = lastDestination?.lowercase() ?: ""
        val p = currentPickup?.lowercase() ?: ""
        
        // Если в адресах есть одинаковые слова (например, название улицы или района)
        val dWords = d.split(" ").filter { it.length > 3 }.toSet()
        val pWords = p.split(" ").filter { it.length > 3 }.toSet()
        
        return dWords.intersect(pWords).isNotEmpty() || d.contains(p) || p.contains(d)
    }

    fun getChainWarning(currentPickup: String?): String? {
        return if (lastDestination != null && !isGoodChain(currentPickup)) {
            "⚠️ РАЗРЫВ ЦЕПИ: Новая точка далеко!"
        } else null
    }
}
