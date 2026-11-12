package com.example.radiojooo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.radiojooo.Graph
import com.example.radiojooo.data.Repo
import com.example.radiojooo.data.Station
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Graph.repo(app)
    private val health = Graph.health(app)

    /** True while the manual "update servers" is running (the button shows progress and ignores extra taps). */
    val refreshing = MutableStateFlow(false)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** One-shot messages for the user: result of the manual refresh. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val searchQuery = MutableStateFlow("")
    val category = MutableStateFlow("ALL")
    val syncState: StateFlow<Repo.SyncState> = repo.sync

    val stations: StateFlow<List<Station>> = combine(repo.stations, searchQuery, category) { list, query, cat ->
        var out = list
        out = when (cat) {
            "ALL" -> out
            "FAVORITE" -> out.filter { it.isFavorite }
            "EGYPT" -> out.filter { isEgypt(it) }
            else -> out.filter { it.category == cat }
        }
        val q = fold(query)
        if (q.isNotEmpty()) out = out.filter { fold(it.name).contains(q) || fold(it.countryCode).contains(q) }
        out
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Chip counters: ALL, FAVORITE, EGYPT, QURAN, NEWS, MUSIC, SPORTS, TALK. */
    val counts: StateFlow<Map<String, Int>> = repo.stations.map { list ->
        val m = HashMap<String, Int>()
        m["ALL"] = list.size
        m["FAVORITE"] = list.count { it.isFavorite }
        m["EGYPT"] = list.count { isEgypt(it) }
        for (c in listOf("QURAN", "NEWS", "MUSIC", "SPORTS", "TALK")) m[c] = list.count { it.category == c }
        m
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    init {
        viewModelScope.launch {
            repo.loadCache()                                  // instant list, works offline
            syncWithRetry(force = false)
        }
    }

    /** Button "تحديث الخوادم": the cloud re-checks servers now, then the real list is downloaded and compared. */
    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            val r = repo.forceRefresh(health)
            refreshing.value = false
            val msg = when {
                r.ok && (r.added > 0 || r.removed > 0) ->
                    "✅ تم التحديث: ${r.total} إذاعة (+${r.added} جديدة، −${r.removed} محذوفة) • ${r.servers} خادم"
                r.ok && r.throttled ->
                    "✅ القائمة محدّثة قبل لحظات: ${r.total} إذاعة • ${r.servers} خادم"
                r.ok ->
                    "✅ تم الفحص والتحديث: ${r.total} إذاعة • ${r.servers} خادم (${r.verified} مؤكَّد)"
                r.warming -> "⏳ السحابة لسه بتجهّز القائمة، جرّب بعد دقيقة"
                else -> "❌ تعذر التحديث، تأكد من الاتصال بالإنترنت"
            }
            _messages.emit(msg)
        }
    }

    fun toggleFavorite(id: String) = repo.toggleFavorite(id)

    private suspend fun syncWithRetry(force: Boolean) {
        val fresh = System.currentTimeMillis() - repo.lastSyncAt() < 30 * 60_000L
        if (!force && fresh && repo.stations.value.isNotEmpty()) return      // data saver: no need to ask again so soon
        var tries = 0
        while (tries < 14) {
            val wait = repo.sync()
            if (wait > 0) { delay(wait * 1000L); tries++; continue }          // cloud is still preparing the first list
            if (repo.sync.value == Repo.SyncState.FAILED && repo.stations.value.isEmpty() && tries < 3) { delay(8_000); tries++; continue }
            break
        }
    }

    private fun isEgypt(s: Station) = s.countryCode.equals("EG", true) || s.category == "EGYPT"

    /** Arabic-friendly search: ignores hamza/ta marbuta/ya variants, diacritics and case. */
    private fun fold(s: String): String =
        s.trim().lowercase()
            .replace(Regex("[\\u064B-\\u0652\\u0640]"), "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ة', 'ه').replace('ى', 'ي')
}
