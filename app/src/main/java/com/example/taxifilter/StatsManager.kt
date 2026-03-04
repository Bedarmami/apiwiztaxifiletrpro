package com.example.taxifilter

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class DailyStats(
    val date: String,
    var totalIncome: Double = 0.0,
    var totalExpenses: Double = 0.0,
    var orderCount: Int = 0,
    var totalDistance: Double = 0.0
) {
    val netProfit: Double get() = totalIncome - totalExpenses
}

object StatsManager {
    private const val FILE_NAME = "trip_stats.json"
    private val allStats = mutableMapOf<String, DailyStats>()

    fun init(context: Context) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) {
                val json = JSONObject(file.readText())
                json.keys().forEach { date ->
                    val obj = json.getJSONObject(date)
                    allStats[date] = DailyStats(
                        date = date,
                        totalIncome = obj.optDouble("income", 0.0),
                        totalExpenses = obj.optDouble("expenses", 0.0),
                        orderCount = obj.optInt("count", 0),
                        totalDistance = obj.optDouble("distance", 0.0)
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getTodayDate(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    fun addOrder(income: Double, distance: Double, costPerKm: Float) {
        val today = getTodayDate()
        val stats = allStats.getOrPut(today) { DailyStats(today) }
        
        stats.totalIncome += income
        stats.totalDistance += distance
        stats.totalExpenses += (distance * costPerKm)
        stats.orderCount++
    }

    fun getTodayStats(): DailyStats {
        return allStats[getTodayDate()] ?: DailyStats(getTodayDate())
    }

    fun getAllStats(): List<DailyStats> = allStats.values.sortedByDescending { it.date }

    fun save(context: Context) {
        try {
            val json = JSONObject()
            allStats.forEach { (date, stats) ->
                val obj = JSONObject()
                obj.put("income", stats.totalIncome)
                obj.put("expenses", stats.totalExpenses)
                obj.put("count", stats.orderCount)
                obj.put("distance", stats.totalDistance)
                json.put(date, obj)
            }
            val file = File(context.filesDir, FILE_NAME)
            file.writeText(json.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
