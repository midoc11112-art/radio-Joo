package com.example.radiojooo.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Catalog cache (file) + favorites (prefs) + sync with the cloud. The UI only ever reads [stations]. */
class Repo(context: Context) {
    private val app = context.applicationContext
    private val file = File(app.filesDir, "catalog_v3.json")
    private val prefs = app.getSharedPreferences("repo_v3", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private var favorites: MutableSet<String> = (prefs.getStringSet("fav", emptySet()) ?: emptySet()).toMutableSet()
    private var raw: List<Station> = emptyList()

    private val _stations = MutableStateFlow<List<Station>>(emptyList())
    val stations: StateFlow<List<Station>> = _stations.asStateFlow()

    enum class SyncState { IDLE, SYNCING, WARMING, UPDATED, UNCHANGED, FAILED }
    private val _sync = MutableStateFlow(SyncState.IDLE)
    val sync: StateFlow<SyncState> = _sync.asStateFlow()

    /** Shows the cached list instantly (works offline), before any network call. */
    suspend fun loadCache() = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext
            val arr = JSONObject(file.readText()).getJSONArray("s")
            val list = ArrayList<Station>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val sv = o.getJSONArray("v"); val servers = ArrayList<Server>()
                for (j in 0 until sv.length()) { val s = sv.getJSONObject(j); servers.add(Server(s.getString("u"), s.optInt("b"), s.optInt("h") == 1)) }
                list.add(Station(o.getString("i"), o.getString("n"), o.getString("c"), o.optString("k"), o.optInt("p"), servers))
            }
            publish(list)
        } catch (e: Exception) { /* corrupt cache is ignored, next sync rebuilds it */ }
    }

    /** One sync with the cloud. ETag makes "nothing changed" nearly free. Returns seconds to wait if the cloud is still warming up. */
    suspend fun sync(): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            _sync.value = SyncState.SYNCING
            val etag = prefs.getString("etag", null).takeIf { raw.isNotEmpty() }
            when (val r = Api.fetchStations(etag)) {
                is Api.Fetch.Fresh -> {
                    save(r.stations); prefs.edit().putString("etag", r.etag).putLong("last", System.currentTimeMillis()).apply()
                    publish(r.stations); _sync.value = SyncState.UPDATED; 0
                }
                is Api.Fetch.NotModified -> { prefs.edit().putLong("last", System.currentTimeMillis()).apply(); _sync.value = SyncState.UNCHANGED; 0 }
                is Api.Fetch.Warming -> { _sync.value = SyncState.WARMING; r.retrySeconds }
                is Api.Fetch.Failed -> { _sync.value = SyncState.FAILED; 0 }
            }
        }
    }

    fun lastSyncAt(): Long = prefs.getLong("last", 0L)

    /** Replace one station's servers with freshly healed ones. */
    fun updateStation(updated: Station) {
        publish(raw.map { if (it.id == updated.id) updated else it })
        try { save(raw) } catch (e: Exception) { }
    }

    fun toggleFavorite(id: String) {
        if (!favorites.add(id)) favorites.remove(id)
        prefs.edit().putStringSet("fav", HashSet(favorites)).apply()
        publish(raw)
    }

    fun find(id: String): Station? = raw.firstOrNull { it.id == id }

    private fun publish(list: List<Station>) {
        raw = list
        _stations.value = list.map { it.copy(isFavorite = it.id in favorites) }
    }

    private fun save(list: List<Station>) {
        val arr = JSONArray()
        for (s in list) {
            val sv = JSONArray()
            for (x in s.servers) sv.put(JSONObject().put("u", x.url).put("b", x.kbps).put("h", if (x.hls) 1 else 0))
            arr.put(JSONObject().put("i", s.id).put("n", s.name).put("c", s.category).put("k", s.countryCode).put("p", s.popularity).put("v", sv))
        }
        val tmp = File(app.filesDir, "catalog_v3.tmp")
        tmp.writeText(JSONObject().put("s", arr).toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
