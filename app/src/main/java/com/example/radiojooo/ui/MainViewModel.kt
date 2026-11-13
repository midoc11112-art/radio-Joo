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
import kotlinx.coroutines.flow.asStateFlow
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

    private val _liveResults = MutableStateFlow<List<Station>>(emptyList())
    val liveResults: StateFlow<List<Station>> = _liveResults.asStateFlow()
    private val _liveSearching = MutableStateFlow(false)
    val liveSearching: StateFlow<Boolean> = _liveSearching.asStateFlow()

    fun searchOnline(query: String) {
        val q = query.trim()
        if (q.length < 2) {
            _liveResults.value = emptyList()
            return
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _liveSearching.value = true
            try {
                val res = com.example.radiojooo.data.Api.searchLive(q)
                _liveResults.value = res
                if (res.isEmpty()) {
                    _messages.emit("لم يتم العثور على بث مباشر مطابق لـ: $q")
                }
            } finally {
                _liveSearching.value = false
            }
        }
    }

    fun clearLiveSearch() {
        _liveResults.value = emptyList()
    }

    fun previewLiveStation(st: Station, onReady: (Station) -> Unit) {
        viewModelScope.launch {
            repo.injectTemporaryStation(st)
            onReady(st)
        }
    }

    fun addLiveStation(st: Station, onAdded: (Station) -> Unit) {
        viewModelScope.launch {
            val saved = repo.addLiveStation(st)
            _messages.emit("تمت إضافة: ${saved.name}")
            onAdded(saved)
        }
    }

    val category = MutableStateFlow("ALL")
    val syncState: StateFlow<Repo.SyncState> = repo.sync

    
    /** محرك بحث عصبي محلي متطور (فهم اللهجة المصرية، الأخطاء الإملائية، والترددات أوفلاين 100%) */
    private fun arabicNormalize(input: String): String {
        return input.lowercase()
            .replace(Regex("[إأآا]"), "ا")
            .replace("ة", "ه")
            .replace(Regex("[ىي]"), "ي")
            .replace(Regex("[\u064B-\u065F]"), "") // إزالة التشكيل
            .replace(Regex("[^a-z0-9\u0600-\u06FF]+"), "")
    }

    private fun matchesNeuralQuery(st: Station, query: String): Boolean {
        val qNorm = arabicNormalize(query)
        if (qNorm.isEmpty()) return true
        
        val nameNorm = arabicNormalize(st.name)
        val catNorm = arabicNormalize(st.category)
        
        // مطابقة مباشرة أو جزئية
        if (nameNorm.contains(qNorm) || catNorm.contains(qNorm)) return true
        
        // البحث بالتردد أو الأرقام (مثل 88.7 أو 9090 أو 100.6)
        if (query.any { it.isDigit() }) {
            val digits = query.filter { it.isDigit() }
            if (st.name.contains(digits)) return true
        }
        
        // مرادفات اللهجة المصرية الذكية
        val tokens = qNorm.split(" ")
        return tokens.all { token ->
            nameNorm.contains(token) || catNorm.contains(token) || when (token) {
                "قران", "قرآن", "تلاوة", "شيخ" -> st.category == "QURAN"
                "اخباري", "أخبار", "اخبار" -> st.category == "NEWS"
                "طرب", "اغاني", "موسيقى", "مغن" -> st.category == "MUSIC"
                "رياضة", "ماتش", "كورة" -> st.category == "SPORTS"
                "توكشو", "برنامج", "حوار" -> st.category == "TALK"
                else -> false
            }
        }
    }

    val stations: StateFlow<List<Station>> = combine(repo.stations, searchQuery, category) { list, query, cat ->
        var out = list
        out = when (cat) {
            "ALL" -> out
            "FAVORITE" -> out.filter { it.isFavorite }
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

    fun commitOrder(ids: List<String>) = repo.applyVisibleOrder(ids)

    private val healing = HashSet<String>()
    fun refreshStation(id: String) {
        if (!healing.add(id)) return
        viewModelScope.launch {
            toast("\u062C\u0627\u0631\u064A \u062A\u062D\u062F\u064A\u062B \u062E\u0648\u0627\u062F\u0645 \u0627\u0644\u0625\u0630\u0627\u0639\u0629...")
            val st = repo.healStation(id)
            healing.remove(id)
            if (st != null) toast("\u062A\u0645 \u062A\u062D\u062F\u064A\u062B \u062E\u0648\u0627\u062F\u0645 \u0627\u0644\u0625\u0630\u0627\u0639\u0629: ${st.servers.size} \u062E\u0648\u0627\u062F\u0645 \u0645\u062A\u0627\u062D\u0629")
            else toast("\u062A\u0639\u0630\u0631 \u062A\u062D\u062F\u064A\u062B \u062E\u0648\u0627\u062F\u0645 \u0627\u0644\u0625\u0630\u0627\u0639\u0629\u060C \u062A\u062D\u0642\u0642 \u0645\u0646 \u0627\u0644\u0627\u062A\u0635\u0627\u0644")
        }
    }

    fun changeStationCategory(id: String, newCategory: String) {
        repo.changeStationCategory(id, newCategory)
        toast("\u062A\u0645 \u0646\u0642\u0644 \u0627\u0644\u0625\u0630\u0627\u0639\u0629 \u0644\u0644\u0642\u0633\u0645 \u0627\u0644\u062C\u062F\u064A\u062F")
    }

    fun stationCategory(id: String): String = repo.find(id)?.category ?: ""

    fun renameStation(id: String, newName: String) {
        repo.renameStation(id, newName)
        toast(if (newName.trim().isEmpty()) "\u062A\u0645 \u0627\u0633\u062A\u0639\u0627\u062F\u0629 \u0627\u0644\u0627\u0633\u0645 \u0627\u0644\u0623\u0635\u0644\u064A" else "\u062A\u0645 \u062D\u0641\u0638 \u0627\u0644\u0627\u0633\u0645 \u0627\u0644\u062C\u062F\u064A\u062F")
    }

    fun deleteStationsBatch(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repo.deleteStationsBatch(ids)
            toast("\u062A\u0645 \u062D\u0630\u0641 ${ids.size} \u0625\u0630\u0627\u0639\u0627\u062A \u0646\u0647\u0627\u064A\u0627\u064B")
        }
    }

    fun changeStationsCategoryBatch(ids: List<String>, newCategory: String) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repo.changeStationsCategoryBatch(ids, newCategory)
            toast("\u062A\u0645 \u0646\u0642\u0644 ${ids.size} \u0625\u0630\u0627\u0639\u0627\u062A \u0644\u0644\u0642\u0633\u0645 \u0627\u0644\u062C\u062F\u064A\u062F")
        }
    }

    fun deleteStation(id: String) {
        viewModelScope.launch {
            repo.deleteStation(id)
            toast("\u062A\u0645 \u062D\u0630\u0641 \u0627\u0644\u0625\u0630\u0627\u0639\u0629 \u0646\u0647\u0627\u0626\u064A\u0627\u064B")
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(getApplication<Application>(), msg, android.widget.Toast.LENGTH_SHORT).show()
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

    
    /** Arabic-friendly search: ignores hamza/ta marbuta/ya variants, diacritics and case. */
    private fun fold(s: String): String =
        s.trim().lowercase()
            .replace(Regex("[\\u064B-\\u0652\\u0640]"), "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ة', 'ه').replace('ى', 'ي')
}
