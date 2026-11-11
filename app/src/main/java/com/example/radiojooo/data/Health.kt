package com.example.radiojooo.data

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Device-side memory of every server it has tried. This is what makes the app smarter and faster over time:
 * servers that start fast and never fail move to the front, failing ones are put on a cool-down, and
 * everything is ordered cheapest-bitrate-first for maximum data saving.
 */
class Health(context: Context) {
    private class Stat(var ok: Int = 0, var fail: Int = 0, var consecutiveFails: Int = 0,
                       var startupMs: Long = 0, var lastFailAt: Long = 0, var kbps: Int = 0)

    private val prefs = context.applicationContext.getSharedPreferences("health_v1", Context.MODE_PRIVATE)
    private val stats = ConcurrentHashMap<String, Stat>()
    private val pending = ArrayList<Report>()

    init {
        try {
            val o = JSONObject(prefs.getString("stats", "{}") ?: "{}")
            for (k in o.keys()) {
                val s = o.getJSONObject(k)
                stats[k] = Stat(s.optInt("o"), s.optInt("f"), s.optInt("c"), s.optLong("m"), s.optLong("l"), s.optInt("k"))
            }
        } catch (e: Exception) { /* start fresh */ }
    }

    private fun bucket(kbps: Int) = when { kbps <= 0 -> 3; kbps <= 40 -> 0; kbps <= 72 -> 1; kbps <= 112 -> 2; else -> 3 }

    /** Order servers: not-cooling first, then cheapest bitrate tier, then fastest/most reliable. */
    fun rank(servers: List<Server>): List<Server> {
        val now = System.currentTimeMillis()
        return servers.sortedWith(compareBy<Server>(
            { cooling(it.url, now) },
            { bucket(if (it.kbps > 0) it.kbps else (stats[it.url]?.kbps ?: 0)) },
            { reliabilityPenalty(it.url) },
            { stats[it.url]?.startupMs?.takeIf { ms -> ms > 0 } ?: 5000L }
        ))
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

    @Synchronized fun success(url: String, startupMs: Long) {
        val s = stats.getOrPut(url) { Stat() }
        s.ok++; s.consecutiveFails = 0
        s.startupMs = if (s.startupMs == 0L) startupMs else (s.startupMs * 7 + startupMs * 3) / 10
        pending.add(Report(url, true, startupMs, s.kbps))
        save()
    }

    @Synchronized fun failure(url: String) {
        val s = stats.getOrPut(url) { Stat() }
        s.fail++; s.consecutiveFails++; s.lastFailAt = System.currentTimeMillis()
        pending.add(Report(url, false, 0, 0))
        save()
    }

    @Synchronized fun measured(url: String, kbps: Int) {
        if (kbps in 8..400) stats.getOrPut(url) { Stat() }.kbps = kbps
    }

    /** Learning events waiting to be sent to the cloud (called by the sync worker / when playback stops). */
    @Synchronized fun drainReports(): List<Report> { val c = ArrayList(pending); pending.clear(); return c }
    @Synchronized fun requeue(items: List<Report>) { pending.addAll(0, items); while (pending.size > 200) pending.removeAt(0) }

    private fun save() {
        try {
            val o = JSONObject()
            for ((k, s) in stats) o.put(k, JSONObject().put("o", s.ok).put("f", s.fail).put("c", s.consecutiveFails)
                .put("m", s.startupMs).put("l", s.lastFailAt).put("k", s.kbps))
            prefs.edit().putString("stats", o.toString()).apply()
        } catch (e: Exception) { /* non fatal */ }
    }
}
