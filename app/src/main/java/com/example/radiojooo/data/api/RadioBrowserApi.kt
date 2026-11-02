package com.example.radiojooo.data.api

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class RadioBrowserApi {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    
    private val baseUrls = listOf(
        "https://de1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
        "https://at1.api.radio-browser.info"
    )
    
    private var currentBaseUrlIndex = 0
    
    data class RadioStation(
        @SerializedName("stationuuid") val uuid: String,
        @SerializedName("name") val name: String,
        @SerializedName("url") val url: String,
        @SerializedName("url_resolved") val urlResolved: String,
        @SerializedName("homepage") val homepage: String?,
        @SerializedName("favicon") val favicon: String?,
        @SerializedName("tags") val tags: String?,
        @SerializedName("country") val country: String?,
        @SerializedName("countrycode") val countryCode: String?,
        @SerializedName("state") val state: String?,
        @SerializedName("language") val language: String?,
        @SerializedName("bitrate") val bitrate: Int,
        @SerializedName("codec") val codec: String?,
        @SerializedName("clickcount") val clickCount: Int,
        @SerializedName("votes") val votes: Int
    )
    
    private fun getBaseUrl(): String = baseUrls[currentBaseUrlIndex]
    
    suspend fun searchByCountry(countryCode: String, limit: Int = 100): List<RadioStation> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "${getBaseUrl()}/json/stations/bycountrycodeexact/$countryCode?limit=$limit&order=clickcount&reverse=true"
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                
                if (response.isSuccessful && body != null) {
                    gson.fromJson(body, Array<RadioStation>::class.java).toList()
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }
    
    suspend fun searchByName(name: String, limit: Int = 50): List<RadioStation> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "${getBaseUrl()}/json/stations/byname/$name?limit=$limit&order=clickcount&reverse=true"
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                
                if (response.isSuccessful && body != null) {
                    gson.fromJson(body, Array<RadioStation>::class.java).toList()
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }
    
    suspend fun searchByTag(tag: String, limit: Int = 50): List<RadioStation> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "${getBaseUrl()}/json/stations/bytag/$tag?limit=$limit&order=clickcount&reverse=true"
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                
                if (response.isSuccessful && body != null) {
                    gson.fromJson(body, Array<RadioStation>::class.java).toList()
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }
    
    suspend fun getTopStations(limit: Int = 100): List<RadioStation> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "${getBaseUrl()}/json/stations/topclick/$limit"
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                
                if (response.isSuccessful && body != null) {
                    gson.fromJson(body, Array<RadioStation>::class.java).toList()
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }
    
    fun filterLowBitrate(stations: List<RadioStation>, maxBitrate: Int = 64): List<RadioStation> {
        return stations.filter { it.bitrate in 1..maxBitrate }
    }
    
    fun filterArabic(stations: List<RadioStation>): List<RadioStation> {
        val arabicCountries = listOf("EG", "SA", "AE", "KW", "QA", "BH", "OM", "JO", "LB", "SY", "IQ", "YE", "LY", "TN", "DZ", "MA", "SD", "PS")
        return stations.filter { it.countryCode in arabicCountries }
    }
    
    suspend fun clickStation(stationUuid: String) {
        withContext(Dispatchers.IO) {
            try {
                val url = "${getBaseUrl()}/json/url/$stationUuid"
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
