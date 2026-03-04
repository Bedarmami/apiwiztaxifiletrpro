package com.example.taxifilter

import android.os.SystemClock

/**
 * Отслеживает время простоя между заказами.
 * Считает потенциально потерянную прибыль.
 */
object IdleTracker {
    private var lastOrderTime: Long = SystemClock.elapsedRealtime()
    private var totalIdleMinutes: Int = 0
    
    // Средний доход водителя в минуту для оценки потерь (например, 1 zł/мин)
    private const val EARNING_PER_MINUTE = 1.0

    fun reset() {
        lastOrderTime = SystemClock.elapsedRealtime()
    }

    fun getIdleMinutes(): Int {
        val diff = SystemClock.elapsedRealtime() - lastOrderTime
        return (diff / 60000).toInt()
    }

    fun getPotentialLoss(): Double {
        return getIdleMinutes() * EARNING_PER_MINUTE
    }
    
    fun onOrderAccepted() {
        totalIdleMinutes += getIdleMinutes()
        reset()
    }
}
