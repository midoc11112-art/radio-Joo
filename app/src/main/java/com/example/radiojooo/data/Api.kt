package com.example.radiojooo.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Talks to the Cloudflare brain. Every call is tiny: the list uses ETag so an unchanged catalog costs ~0 bytes. */
object Api {
    const val BASE = "https://radio-joo-proxy.midoc11112.workers.dev"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    sealed class Fetch {
        data class Fresh(val etag: String?, val stations: List<Station>) : Fetch()
        object NotModified : Fetch()
        data class Warming(val retrySeconds: Int) : Fetch()
        object Failed : Fetch()
    }

    fun fetchStations(etag: String?): Fetch {
        return try {
            val b = Request.Builder().url("$BASE/v2/stations")
            if (!etag.isNullOrEmpty()) b.header("If-None-Match", etag)
            client.newCall(b.build()).execute().use { r ->
                if (r.code == 304) return Fetch.NotModified
                if (!r.isSuccessful) return Fetch.Failed
                val json = JSONObject(r.body?.string() ?: return Fetch.Failed)
                val list = parseStations(json.optJSONArray("stations") ?: JSONArray())
                if (list.isEmpty()) Fetch.Warming(json.optInt("retry", 25))
                else Fetch.Fresh(r.header("ETag"), list)
            }
        } catch (e: Exception) { Fetch.Failed }
    }

    /** Asks the cloud to find fresh servers for a station whose servers all failed. */
    fun heal(stationId: String): Station? {
        return try {
            val req = Request.Builder().url("$BASE/v2/heal?id=" + URLEncoder.encode(stationId, "UTF-8")).build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val st = JSONObject(r.body?.string() ?: return null).optJSONObject("station") ?: return null
                parseStation(st)
            }
        } catch (e: Exception) { null }
    }

    fun report(items: List<Report>): Boolean {
        if (items.isEmpty()) return true
        return try {
            val arr = JSONArray()
            for (i in items) arr.put(JSONObject().put("u", i.url).put("ok", if (i.ok) 1 else 0).put("ms", i.startupMs).put("b", i.kbps))
            val body = JSONObject().put("r", arr).toString().toRequestBody("application/json".toMediaType())
            client.newCall(Request.Builder().url("$BASE/v2/report").post(body).build()).execute().use { it.isSuccessful }
        } catch (e: Exception) { false }
    }

    private fun parseStations(arr: JSONArray): List<Station> {
        val out = ArrayList<Station>(arr.length())
        for (i in 0 until arr.length()) parseStation(arr.optJSONObject(i))?.let { out.add(it) }
        return out
    }

    private fun parseStation(o: JSONObject?): Station? {
        if (o == null) return null
        val id = o.optString("id"); val name = o.optString("n")
        val sv = o.optJSONArray("s") ?: return null
        val servers = ArrayList<Server>()
        for (i in 0 until sv.length()) {
            val s = sv.optJSONObject(i) ?: continue
            val u = s.optString("u")
            if (u.startsWith("http")) servers.add(Server(u, s.optInt("b", 0), s.optInt("h", 0) == 1))
        }
        if (id.isEmpty() || name.isEmpty() || servers.isEmpty()) return null
        return Station(id, name, o.optString("c", "MUSIC"), o.optString("cc", ""), o.optInt("p", 0), servers)
    }
}
