package com.example.radiojooo.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Device-side memory of every server it has tried, saved permanently in SharedPreferences.
 *
 *  PIN (check mark): a server that proved itself (3 clean starts in a row, or pinned by hand) always goes first.
 *  BAN (cross): a server that keeps failing (3 separate failing sessions with no success in between, or banned
 *  by hand) is removed from the ranking completely until the user un-bans it.
 *
 * Both lists survive closing the app, rebooting the phone, and weeks of not opening the app.
 * Everything else is ordered cheapest-bitrate-first for maximum data saving.
 */
class Health(context: Context) {
    private class Stat(var ok: Int = 0, var fail: Int = 0, var consecutiveFails: Int = 0,
                       var startupMs: Long = 0, var lastFailAt: Long = 0, var kbps: Int = 0,
                       var streak: Int = 0, var strikes: Int = 0, var strikeAt: Long = 0)

    private companion object {
        const val PIN_AFTER_CLEAN_STARTS = 3
        const val BAN_AFTER_STRIKES = 3
        const val STRIKE_GAP_MS = 3 * 60_000L      // failures inside one listening burst count as ONE strike
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("health_v1", Context.MODE_PRIVATE)
    private val stats = ConcurrentHashMap<String, Stat>()
    private val pinned: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val banned: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pending = ArrayList<Report>()

    init {
        try {
            val o = JSONObject(prefs.getString("stats", "{}") ?: "{}")
            for (k in o.keys()) {
                val s = o.getJSONObject(k)
                stats[k] = Stat(s.optInt("o"), s.optInt("f"), s.optInt("c"), s.optLong("m"), s.optLong("l"), s.optInt("k"),
                    s.optInt("p"), s.optInt("x"), s.optLong("t"))
            }
        } catch (e: Exception) { /* start fresh */ }
        try {
            val a = JSONArray(prefs.getString("pinned", "[]") ?: "[]")
            for (i in 0 until a.length()) pinned.add(a.getString(i))
        } catch (e: Exception) { /* start fresh */ }
        try {
            val a = JSONArray(prefs.getString("banned", "[]") ?: "[]")
            for (i in 0 until a.length()) banned.add(a.getString(i))
        } catch (e: Exception) { /* start fresh */ }
        banned.removeAll(pinned)
    }

    private fun bucket(kbps: Int) = when { kbps <= 0 -> 3; kbps <= 40 -> 0; kbps <= 72 -> 1; kbps <= 112 -> 2; else -> 3 }

    /**
     * Order servers: banned ones are dropped completely; pinned (and not cooling) servers come first with absolute
     * priority; then everything else not cooling; cooling servers last. Inside a group: cheapest bitrate tier,
     * then reliability, then fastest start.
     */
    fun rank(servers: List<Server>): List<Server> {
        val now = System.currentTimeMillis()
        return servers.filter { it.url !in banned }.sortedWith(compareBy<Server>(
            { tier(it.url, now) },
            { bucket(if (it.kbps > 0) it.kbps else (stats[it.url]?.kbps ?: 0)) },
            { reliabilityPenalty(it.url) },
            { stats[it.url]?.startupMs?.takeIf { ms -> ms > 0 } ?: 5000L }
        ))
    }

    private fun tier(url: String, now: Long): Int {
        val cool = cooling(url, now) == 1
        return when {
            !cool && url in pinned -> 0
            !cool -> 1
            else -> 2
        }
    }

    private fun cooling(url: String, now: Long): Int {
        val s = stats[url] ?: return 0
        if (s.consecutiveFails == 0) return 0
        val coolMs = minOf(30 * 60_000L, 15_000L shl minOf(s.consecutiveFails, 7))   // 30s, 60s, 2m ... capped at 30 min
        return if (now - s.lastFailAt < coolMs) 1 else 0
    }

    private fun reliabilityPenalty(url: String): Int {
        val s = stats[url] ?: return 1
        val total = s.ok + s.fail
        if (total < 3) return 1
        return if (s.fail * 2 > total) 2 else 0
    }

    // ---------------------------------------------------------------- pin / ban state
    fun isPinned(url: String): Boolean = url in pinned
    fun isBanned(url: String): Boolean = url in banned

    @Synchronized fun setPinned(url: String, on: Boolean) {
        if (on) {
            pinned.add(url); banned.remove(url)
            stats[url]?.let { it.strikes = 0; it.consecutiveFails = 0 }
            save()
        } else pinned.remove(url)
        saveFlags()
    }

    @Synchronized fun setBanned(url: String, on: Boolean) {
        if (on) {
            banned.add(url); pinned.remove(url)
        } else {
            banned.remove(url)
            stats[url]?.let { it.strikes = 0; it.consecutiveFails = 0; it.streak = 0 }
            save()
        }
        saveFlags()
    }

    // ---------------------------------------------------------------- learning
    @Synchronized fun success(url: String, startupMs: Long) {
        val s = stats.getOrPut(url) { Stat() }
        s.ok++; s.consecutiveFails = 0; s.strikes = 0; s.streak++
        s.startupMs = if (s.startupMs == 0L) startupMs else (s.startupMs * 7 + startupMs * 3) / 10
        pending.add(Report(url, true, startupMs, s.kbps))
        save()
        if (s.streak >= PIN_AFTER_CLEAN_STARTS && url !in banned && pinned.add(url)) saveFlags()
    }

    @Synchronized fun failure(url: String) {
        if (!online()) return                      // no internet = not the server's fault, never punish it
        val now = System.currentTimeMillis()
        val s = stats.getOrPut(url) { Stat() }
        s.fail++; s.consecutiveFails++; s.lastFailAt = now; s.streak = 0
        if (now - s.strikeAt > STRIKE_GAP_MS) { s.strikes++; s.strikeAt = now }
        pending.add(Report(url, false, 0, 0))
        save()
        if (s.strikes >= BAN_AFTER_STRIKES && banned.add(url)) {
            pinned.remove(url)
            saveFlags()
        }
    }

    /** After a manual "update servers": give every server a fresh chance (they may have been fixed). Bans stay. */
    @Synchronized fun resetCooldowns() {
        for (s in stats.values) s.consecutiveFails = 0
        save()
    }

    @Synchronized fun measured(url: String, kbps: Int) {
        if (kbps in 8..400) stats.getOrPut(url) { Stat() }.kbps = kbps
    }

    /** Learning events waiting to be sent to the cloud (called by the sync worker / when playback stops). */
    @Synchronized fun drainReports(): List<Report> { val c = ArrayList(pending); pending.clear(); return c }
    @Synchronized fun requeue(items: List<Report>) { pending.addAll(0, items); while (pending.size > 200) pending.removeAt(0) }

    // ---------------------------------------------------------------- persistence
    private fun online(): Boolean {
        return try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else true
        } catch (e: Exception) { true }
    }

    private fun save() {
        try {
            val o = JSONObject()
            for ((k, s) in stats) o.put(k, JSONObject().put("o", s.ok).put("f", s.fail).put("c", s.consecutiveFails)
                .put("m", s.startupMs).put("l", s.lastFailAt).put("k", s.kbps)
                .put("p", s.streak).put("x", s.strikes).put("t", s.strikeAt))
            prefs.edit().putString("stats", o.toString()).apply()
        } catch (e: Exception) { /* non fatal */ }
    }

    /** Pin/ban lists are written synchronously (commit) so they survive even if the process is killed right after. */
    private fun saveFlags() {
        try {
            prefs.edit()
                .putString("pinned", JSONArray(pinned.toList()).toString())
                .putString("banned", JSONArray(banned.toList()).toString())
                .commit()
        } catch (e: Exception) { /* non fatal */ }
    }
}