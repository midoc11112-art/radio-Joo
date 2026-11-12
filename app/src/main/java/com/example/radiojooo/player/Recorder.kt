package com.example.radiojooo.player

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records exactly the bytes the player already downloads (tee of the stream), so recording costs ZERO extra data.
 * Works for direct MP3 / AAC / OGG streams (HLS is not supported because segments are not a single file).
 *
 * Recordings live in a visible folder in the main storage: /sdcard/RadioJoo (reachable from any file manager).
 * If Android refuses that folder (all-files access not granted yet) it falls back to the app folder, and the
 * old recordings are moved into /sdcard/RadioJoo automatically as soon as it becomes available.
 */
object Recorder {
    data class State(val active: Boolean = false, val seconds: Long = 0, val bytes: Long = 0, val fileName: String = "")

    private const val MAX_BYTES = 600L * 1024 * 1024     // safety: stop at 600 MB
    private const val PUBLIC_FOLDER = "RadioJoo"
    val state = MutableStateFlow(State())

    @Volatile var contentType: String = ""
    @Volatile var hlsActive: Boolean = false

    private var out: FileOutputStream? = null
    private var file: File? = null
    private var startedAt = 0L
    private var bytes = 0L
    @Volatile private var writing = false
    @Volatile private var appCtx: Context? = null
    @Volatile private var legacyMigrated = false

    /** /sdcard/RadioJoo when writable, otherwise the app-private "recordings" folder. */
    @Suppress("DEPRECATION")
    fun dir(context: Context): File {
        val app = context.applicationContext
        appCtx = app
        val pub = File(Environment.getExternalStorageDirectory(), PUBLIC_FOLDER)
        if (usable(pub)) {
            migrateLegacy(app, pub)
            return pub
        }
        return legacyDir(app)
    }

    private fun legacyDir(app: Context): File {
        val base = app.getExternalFilesDir(null) ?: app.filesDir
        val d = File(base, "recordings")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun usable(d: File): Boolean {
        return try {
            if (!d.exists()) d.mkdirs()
            if (!d.isDirectory) return false
            val probe = File(d, ".probe")
            val ok = probe.createNewFile() || probe.exists()
            probe.delete()
            ok
        } catch (e: Exception) { false }
    }

    /** One-time move of recordings made before the public folder existed. */
    @Synchronized private fun migrateLegacy(app: Context, target: File) {
        if (legacyMigrated) return
        legacyMigrated = true
        try {
            val active = file?.absolutePath
            legacyDir(app).listFiles { f -> f.isFile && f.length() > 0 }?.forEach { f ->
                if (f.absolutePath == active) return@forEach
                val dest = File(target, f.name)
                if (dest.exists()) return@forEach
                if (!f.renameTo(dest)) {
                    f.copyTo(dest, overwrite = false)
                    f.delete()
                }
            }
        } catch (e: Exception) { /* non fatal */ }
    }

    @Synchronized fun start(context: Context, stationName: String): Boolean {
        if (writing) return true
        if (hlsActive) return false
        val ct = contentType.lowercase()
        val ext = when {
            ct.contains("aac") || ct.contains("mp4") -> "aac"
            ct.contains("ogg") || ct.contains("opus") -> "ogg"
            else -> "mp3"
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val safe = stationName.replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(40).ifEmpty { "radio" }
        val f = File(dir(context), "${safe}__$stamp.$ext")
        return try {
            out = FileOutputStream(f)
            file = f; bytes = 0; startedAt = SystemClock.elapsedRealtime(); writing = true
            state.value = State(true, 0, 0, f.name)
            true
        } catch (e: Exception) { false }
    }

    @Synchronized fun stop() {
        writing = false
        val finished = file
        try { out?.flush(); out?.close() } catch (e: Exception) { }
        out = null; file = null
        state.value = State()
        scan(finished)
    }

    /** Lets gallery / music apps and file managers notice the new recording right away. */
    private fun scan(f: File?) {
        val c = appCtx ?: return
        if (f == null || !f.exists() || f.length() == 0L) return
        try { MediaScannerConnection.scanFile(c, arrayOf(f.absolutePath), null, null) } catch (e: Exception) { }
    }

    /** Called from the loader thread with the raw stream bytes. */
    @Synchronized fun write(buf: ByteArray, off: Int, len: Int) {
        if (!writing || hlsActive) return
        try {
            out?.write(buf, off, len)
            bytes += len
            if (bytes > MAX_BYTES) stop()
        } catch (e: Exception) { stop() }
    }

    /** 1 Hz publish from the service. */
    @Synchronized fun tick() {
        if (writing) state.value = State(true, (SystemClock.elapsedRealtime() - startedAt) / 1000, bytes, file?.name ?: "")
    }

    val isActive: Boolean get() = writing
}