package com.example.radiojooo.data

import android.content.Context
import android.os.Environment
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
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
    private val file = File(app.filesDir, "catalog_v5.json")
    private val prefs = app.getSharedPreferences("repo_v3", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    @Suppress("DEPRECATION")
    private val extBackupDir = File(Environment.getExternalStorageDirectory(), "RadioJoo")
    private val extBackupFile = File(extBackupDir, "radiojoo_user_prefs.json")
    private val lock = Any()
    @Volatile private var externalRestored = false
    private val orderPrefs = app.getSharedPreferences("custom_order_v1", Context.MODE_PRIVATE)
    private val delPrefs = app.getSharedPreferences("deleted_stations_v1", Context.MODE_PRIVATE)
    private val ranks: MutableMap<String, Int> = HashMap<String, Int>().also { m -> for ((k, v) in orderPrefs.all) if (v is Int) m[k] = v }
    private val deleted: MutableSet<String> = HashSet(delPrefs.getStringSet("ids", emptySet()) ?: emptySet())
    private val deletedNames: MutableMap<String, String> = HashMap()
    private val namePrefs = app.getSharedPreferences("custom_names_v1", Context.MODE_PRIVATE)
    private val catPrefs = app.getSharedPreferences("custom_categories_v1", Context.MODE_PRIVATE)
    private val customCats: MutableMap<String, String> = HashMap<String, String>().also { m -> for ((k, v) in catPrefs.all) if (v is String && v.isNotBlank()) m[k] = v }
    private val customNames: MutableMap<String, String> = HashMap<String, String>().also { m -> for ((k, v) in namePrefs.all) if (v is String && v.isNotBlank()) m[k] = v }

    init {
        if (!prefs.getBoolean("migrated_v5_clean", false)) {
            deleted.clear()
            deletedNames.clear()
            delPrefs.edit().clear().commit()
            prefs.edit().remove("etag").putBoolean("migrated_v5_clean", true).commit()
            try {
                if (extBackupFile.exists() && extBackupFile.canRead()) {
                    val obj = JSONObject(extBackupFile.readText(Charsets.UTF_8))
                    obj.put("deleted", JSONArray())
                    extBackupFile.writeText(obj.toString(2), Charsets.UTF_8)
                }
            } catch (_: Exception) { }
        }
        restoreFromExternalBackup()
    }

    fun restoreFromExternalBackup(): Boolean = synchronized(lock) {
        try {
            if (!extBackupFile.exists() || !extBackupFile.canRead()) return false
            val text = extBackupFile.readText(Charsets.UTF_8)
            if (text.isBlank()) return false
            val obj = JSONObject(text)
            var anyRestored = false

            var delChanged = false
            val delArr = obj.optJSONArray("deleted")
            if (delArr != null) {
                val ed = delPrefs.edit()
                for (i in 0 until delArr.length()) {
                    val item = delArr.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    val name = item.optString("name")
                    if (id.isNotEmpty() && deleted.add(id)) delChanged = true
                    if (id.isNotEmpty() && name.isNotEmpty()) deletedNames[id] = name
                }
                if (delChanged) {
                    ed.putStringSet("ids", HashSet(deleted)).commit()
                    anyRestored = true
                }
            }

            val ordArr = obj.optJSONArray("order")
            if (ordArr != null && ranks.isEmpty() && ordArr.length() > 0) {
                val ed = orderPrefs.edit()
                for (i in 0 until ordArr.length()) {
                    val id = ordArr.optString(i)
                    if (id.isNotEmpty() && id !in deleted) {
                        ranks[id] = i
                        ed.putInt(id, i)
                    }
                }
                ed.commit()
                if (ranks.isNotEmpty()) anyRestored = true
            }

            val catObj = obj.optJSONObject("categories")
            if (catObj != null) {
                val ed = catPrefs.edit()
                val keys = catObj.keys()
                var catChanged = false
                while (keys.hasNext()) {
                    val id = keys.next()
                    val ct = catObj.optString(id).trim()
                    if (id.isNotEmpty() && ct.isNotEmpty() && !customCats.containsKey(id)) {
                        customCats[id] = ct
                        ed.putString(id, ct)
                        catChanged = true
                    }
                }
                if (catChanged) {
                    ed.commit()
                    anyRestored = true
                }
            }

            val renObj = obj.optJSONObject("renamed")
            if (renObj != null) {
                val ed = namePrefs.edit()
                val keys = renObj.keys()
                var renChanged = false
                while (keys.hasNext()) {
                    val id = keys.next()
                    val nm = renObj.optString(id).trim()
                    if (id.isNotEmpty() && nm.isNotEmpty() && !customNames.containsKey(id)) {
                        customNames[id] = nm
                        ed.putString(id, nm)
                        renChanged = true
                    }
                }
                if (renChanged) {
                    ed.commit()
                    anyRestored = true
                }
            }

            val favArr = obj.optJSONArray("favorites")
            if (favArr != null && favArr.length() > 0) {
                var favChanged = false
                for (i in 0 until favArr.length()) {
                    val id = favArr.optString(i).trim()
                    if (id.isNotEmpty() && id !in deleted && favorites.add(id)) {
                        favChanged = true
                    }
                }
                if (favChanged) {
                    prefs.edit().putStringSet("fav", HashSet(favorites)).commit()
                    anyRestored = true
                }
            }

            externalRestored = true
            if (anyRestored && raw.isNotEmpty()) {
                publish(raw)
            }
            anyRestored
        } catch (_: Exception) {
            false
        }
    }

    private fun syncToExternalBackup() {
        try {
            if (!externalRestored && extBackupFile.exists()) {
                restoreFromExternalBackup()
            }
            if (!extBackupDir.exists()) extBackupDir.mkdirs()
            val delArr = JSONArray()
            for (id in deleted) {
                delArr.put(JSONObject().put("id", id).put("name", deletedNames[id] ?: ""))
            }
            val ordArr = JSONArray()
            ranks.entries.sortedBy { it.value }.forEach { (id, _) ->
                if (id !in deleted) ordArr.put(id)
            }
            val favArr = JSONArray()
            for (id in favorites) {
                if (id !in deleted) favArr.put(id)
            }
            val root = JSONObject()
                .put("deleted", delArr)
                .put("order", ordArr)
                .put("renamed", JSONObject(customNames as Map<*, *>))
                .put("categories", JSONObject(customCats as Map<*, *>))
                .put("favorites", favArr)
            val tmp = File(extBackupDir, "radiojoo_user_prefs.tmp")
            tmp.writeText(root.toString(2), Charsets.UTF_8)
            if (!tmp.renameTo(extBackupFile)) {
                extBackupFile.delete()
                tmp.renameTo(extBackupFile)
            }
            externalRestored = true
        } catch (_: Exception) { }
    }
    private fun filterDeleted(f: Api.Fetch): Api.Fetch =
        if (f is Api.Fetch.Fresh) f.copy(stations = f.stations.filter { it.id !in deleted }) else f

    private var favorites: MutableSet<String> = (prefs.getStringSet("fav", emptySet()) ?: emptySet()).toMutableSet()
    private var raw: List<Station> = emptyList()
    private val _stations = MutableStateFlow<List<Station>>(emptyList())
    val stations: StateFlow<List<Station>> = _stations.asStateFlow()

    /** Outcome of the manual "update servers" button, shown to the user as a clear message. */
    data class RefreshResult(
        val ok: Boolean, val warming: Boolean, val total: Int, val added: Int, val removed: Int,
        val servers: Int, val verified: Int, val throttled: Boolean
    )

    enum class SyncState { IDLE, SYNCING, WARMING, UPDATED, UNCHANGED, FAILED }
    private val _sync = MutableStateFlow(SyncState.IDLE)
    val sync: StateFlow<SyncState> = _sync.asStateFlow()

    /** Shows the cached list instantly (works offline), before any network call. */
    
    

    
    private fun loadGoldenAssetFallback(): List<Station> {
        return try {
            val assetManager = app.assets
            assetManager.open("golden_catalog.json").use { stream ->
                val jsonStr = stream.bufferedReader().use { it.readText() }
                val root = org.json.JSONObject(jsonStr)
                val arr = root.optJSONArray("stations") ?: return emptyList()
                val list = ArrayList<Station>(arr.length())
                for (i in 0 until arr.length()) {
                    Api.parseStation(arr.getJSONObject(i))?.let { list.add(it) }
                }
                list
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    
    

    suspend fun addLiveStation(st: Station): Station = mutex.withLock {
        val clean = st.copy(isFavorite = true)
        deleted.remove(clean.id)
        delPrefs.edit().putStringSet("ids", deleted).commit()
        customCats[clean.id] = clean.category
        catPrefs.edit().putString(clean.id, clean.category).commit()
        ranks[clean.id] = -System.currentTimeMillis().toInt()
        orderPrefs.edit().putInt(clean.id, ranks[clean.id]!!).commit()
        val cur = _stations.value.filterNot { it.id == clean.id }.toMutableList()
        cur.add(0, clean)
        _stations.value = cur
        favorites.add(clean.id); prefs.edit().putStringSet("fav", HashSet(favorites)).apply(); save(cur); publish(cur); syncToExternalBackup()
        clean
    }

    suspend fun loadCache() = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) { val assetList = loadGoldenAssetFallback(); if (assetList.isNotEmpty()) { save(assetList); publish(assetList) }; return@withContext }
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
            _sync.value = SyncState.SYNCING; flushPendingDeletes()
            val etag = prefs.getString("etag", null).takeIf { raw.isNotEmpty() }
            when (val r = filterDeleted(Api.fetchStations(etag))) {
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

    /**
     * Real refresh: the cloud re-checks its servers first (so the answer is fresh), then the full list is downloaded
     * WITHOUT the ETag shortcut, compared with what we had, and every server gets a clean slate locally.
     */
    suspend fun forceRefresh(health: Health): RefreshResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            _sync.value = SyncState.SYNCING; flushPendingDeletes()
            val before = raw.map { it.id }.toSet()
            val info = Api.refresh()
            when (val r = filterDeleted(Api.fetchStations(null))) {
                is Api.Fetch.Fresh -> {
                    save(r.stations)
                    prefs.edit().putString("etag", r.etag).putLong("last", System.currentTimeMillis()).apply()
                    publish(r.stations)
                    health.resetCooldowns()
                    _sync.value = SyncState.UPDATED
                    val ids = r.stations.map { it.id }.toSet()
                    RefreshResult(true, false, r.stations.size, (ids - before).size, (before - ids).size,
                        r.stations.sumOf { it.servers.size }, info?.verified ?: 0, info?.throttled ?: false)
                }
                is Api.Fetch.Warming -> { _sync.value = SyncState.WARMING; RefreshResult(false, true, raw.size, 0, 0, 0, 0, false) }
                else -> { _sync.value = SyncState.FAILED; RefreshResult(false, false, raw.size, 0, 0, 0, 0, false) }
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
        synchronized(lock) {
            if (!favorites.add(id)) favorites.remove(id)
            prefs.edit().putStringSet("fav", HashSet(favorites)).apply()
            syncToExternalBackup()
            publish(raw)
        }
    }

    private fun arrange(list: List<Station>): List<Station> {
        if (ranks.isEmpty()) return list
        val ranked = ArrayList<Station>(); val rest = ArrayList<Station>()
        for (s in list) if (ranks.containsKey(s.id)) ranked.add(s) else rest.add(s)
        ranked.sortBy { ranks[it.id] ?: Int.MAX_VALUE }
        ranked.addAll(rest)
        return ranked
    }

    fun applyVisibleOrder(visibleIds: List<String>) {
        synchronized(lock) {
            val cur = arrange(raw).map { it.id }.toMutableList()
            val curSet = cur.toHashSet()
            val ordered = visibleIds.filter { it in curSet }.distinct()
            val orderedSet = ordered.toHashSet()
            val slots = cur.indices.filter { cur[it] in orderedSet }
            if (slots.size != ordered.size) { publish(raw); return }
            slots.forEachIndexed { k, pos -> cur[pos] = ordered[k] }
            val ed = orderPrefs.edit()
            cur.forEachIndexed { i, id -> ranks[id] = i; ed.putInt(id, i) }
            ed.commit()
            syncToExternalBackup()
            publish(raw)
        }
    }

    suspend fun healStation(id: String): Station? = withContext(Dispatchers.IO) {
        val fresh = Api.heal(id) ?: return@withContext null
        val ranked = fresh.servers.sortedWith(compareBy<Server>({ if (it.kbps > 0) 0 else 1 }, { it.kbps }))
        val st = fresh.copy(servers = ranked)
        updateStation(st)
        st
    }

    suspend fun deleteStationsBatch(ids: List<String>) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val pend = HashSet(delPrefs.getStringSet("pending", emptySet()) ?: emptySet())
                for (id in ids) {
                    find(id)?.name?.let { deletedNames[id] = it }
                    deleted.add(id)
                    ranks.remove(id)
                    pend.add(id)
                }
                delPrefs.edit().putStringSet("ids", HashSet(deleted)).putStringSet("pending", pend).commit()
                val oEd = orderPrefs.edit()
                for (id in ids) oEd.remove(id)
                oEd.commit()
                syncToExternalBackup()
                publish(raw)
            }
            try { save(raw) } catch (_: Exception) { }
            if (Api.deleteStationsBatch(ids)) {
                synchronized(lock) {
                    val cur = HashSet(delPrefs.getStringSet("pending", emptySet()) ?: emptySet())
                    cur.removeAll(ids.toSet())
                    delPrefs.edit().putStringSet("pending", cur).commit()
                }
            } else {
                flushPendingDeletes()
            }
        }
    }

    suspend fun changeStationsCategoryBatch(ids: List<String>, newCategory: String) {
        val clean = newCategory.trim()
        if (ids.isEmpty() || clean.isEmpty()) return
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val ed = catPrefs.edit()
                for (id in ids) {
                    customCats[id] = clean
                    ed.putString(id, clean)
                }
                ed.commit()
                syncToExternalBackup()
                publish(raw)
            }
            Api.categorizeStationsBatch(ids, clean)
        }
    }

    suspend fun deleteStation(id: String) {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                find(id)?.name?.let { deletedNames[id] = it }
                deleted.add(id)
                ranks.remove(id)
                val pend = HashSet(delPrefs.getStringSet("pending", emptySet()) ?: emptySet())
                pend.add(id)
                delPrefs.edit().putStringSet("ids", HashSet(deleted)).putStringSet("pending", pend).commit()
                orderPrefs.edit().remove(id).commit()
                syncToExternalBackup()
                publish(raw)
            }
            try { save(raw) } catch (e: Exception) { }
            flushPendingDeletes()
        }
    }

    private fun flushPendingDeletes() {
        val pend = delPrefs.getStringSet("pending", null)?.toList() ?: return
        if (pend.isEmpty()) return
        val done = ArrayList<String>()
        for (id in pend) if (Api.deleteStation(id)) done.add(id)
        if (done.isEmpty()) return
        synchronized(lock) {
            val cur = HashSet(delPrefs.getStringSet("pending", emptySet()) ?: emptySet())
            cur.removeAll(done.toSet())
            delPrefs.edit().putStringSet("pending", cur).commit()
        }
    }

    fun renameStation(id: String, newName: String) {
        val clean = newName.trim()
        synchronized(lock) {
            if (clean.isEmpty()) {
                customNames.remove(id)
                namePrefs.edit().remove(id).commit()
            } else {
                customNames[id] = clean
                namePrefs.edit().putString(id, clean).commit()
            }
            syncToExternalBackup()
            publish(raw)
        }
    }

    fun changeStationCategory(id: String, newCategory: String) {
        val clean = newCategory.trim()
        synchronized(lock) {
            if (clean.isEmpty()) {
                customCats.remove(id)
                catPrefs.edit().remove(id).commit()
            } else {
                customCats[id] = clean
                catPrefs.edit().putString(id, clean).commit()
            }
            syncToExternalBackup()
            publish(raw)
        }
    }

    fun find(id: String): Station? = raw.firstOrNull { it.id == id }?.let { it.copy(name = customNames[it.id] ?: it.name, category = customCats[it.id] ?: it.category) }

    private fun publish(list: List<Station>) = synchronized(lock) {
        raw = list.filter { it.id !in deleted }
        _stations.value = arrange(raw).map { it.copy(name = customNames[it.id] ?: it.name, category = customCats[it.id] ?: it.category, isFavorite = it.id in favorites) }
    }

    private fun save(list: List<Station>) {
        val arr = JSONArray()
        for (s in list) {
            val sv = JSONArray()
            for (x in s.servers) sv.put(JSONObject().put("u", x.url).put("b", x.kbps).put("h", if (x.hls) 1 else 0))
            arr.put(JSONObject().put("i", s.id).put("n", s.name).put("c", s.category).put("k", s.countryCode).put("p", s.popularity).put("v", sv))
        }
        val tmp = File(app.filesDir, "catalog_v5.tmp")
        tmp.writeText(JSONObject().put("s", arr).toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}

private val deleteHttpClient by lazy {
    OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
}

fun Api.deleteStationsBatch(stationIds: List<String>): Boolean {
    return try {
        val arr = JSONArray()
        for (id in stationIds) arr.put(id)
        val body = JSONObject().put("ids", arr).toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$BASE/v2/delete-batch").post(body).build()
        deleteHttpClient.newCall(req).execute().use { it.isSuccessful }
    } catch (e: Exception) { false }
}

fun Api.categorizeStationsBatch(stationIds: List<String>, category: String): Boolean {
    return try {
        val arr = JSONArray()
        for (id in stationIds) arr.put(id)
        val body = JSONObject().put("ids", arr).put("category", category).toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$BASE/v2/categorize-batch").post(body).build()
        deleteHttpClient.newCall(req).execute().use { it.isSuccessful }
    } catch (e: Exception) { false }
}

fun Api.deleteStation(stationId: String): Boolean {
    return try {
        val body = JSONObject().put("id", stationId).toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$BASE/v2/delete").post(body).build()
        deleteHttpClient.newCall(req).execute().use { it.isSuccessful }
    } catch (e: Exception) { false }
}
