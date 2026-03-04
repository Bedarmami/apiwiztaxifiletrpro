package com.example.taxifilter

import retrofit2.Call
import retrofit2.http.*

interface ApiService {
    @GET("/api/status")
    fun checkStatus(@Query("deviceId") deviceId: String, @Query("version") version: String): Call<StatusResponse>

    @POST("/api/activate")
    fun activateKey(@Body data: ActivateRequest): Call<ActivateResponse>

    @POST("/api/location")
    fun updateLocation(@Body data: LocationRequest): Call<SimpleResponse>

    @GET("/api/nearby")
    fun getNearby(): Call<List<DriverLocation>>

    @GET("/api/intel")
    fun getIntel(): Call<IntelResponse>

    @POST("/api/orders")
    fun logOrder(@Body order: OrderLogRequest): Call<SimpleResponse>
}

data class LocationRequest(
    val deviceId: String,
    val lat: Double,
    val lon: Double,
    val name: String
)

data class DriverLocation(
    val lat: Double,
    val lon: Double,
    val name: String,
    val lastUpdate: Long
)

data class IntelResponse(
    val blacklist: List<String>,
    val whitelist: List<String>,
    val garbage: List<String> = emptyList()
)

data class OrderLogRequest(
    val price: Double,
    val km: Double,
    val destination: String,
    val pickup: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val app: String? = null,
    val status: String? = null,
    val device_id: String,
    val raw_text: String? = null,
    val screenshot: String? = null // Base64
)

data class StatusResponse(
    val isActive: Boolean,
    val expiry: Long,
    val updateRequired: Boolean,
    val serverVersion: String,
    val message: String,
    val botLink: String? = null,
    val apkDownloadLink: String? = null
)

data class ActivateRequest(
    val deviceId: String,
    val key: String
)

data class ActivateResponse(
    val status: String,
    val expiry: Long,
    val message: String? = null
)

data class SimpleResponse(
    val status: String,
    val nearby: Int? = null
)
