package com.example.radiojooo.player

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlin.math.max

class ThrottledDataSource(
    private val upstream: DataSource,
    private val maxBytesPerSecond: Long = 16 * 1024L // 16 KB/s = 128 kbps
) : DataSource {

    private var bytesReadInWindow = 0L
    private var windowStartTimeMs = 0L

    override fun open(dataSpec: DataSpec): Long {
        val length = upstream.open(dataSpec)
        bytesReadInWindow = 0
        windowStartTimeMs = System.currentTimeMillis()
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val now = System.currentTimeMillis()
        val elapsed = now - windowStartTimeMs

        if (elapsed >= 1000) {
            bytesReadInWindow = 0
            windowStartTimeMs = now
        }

        if (bytesReadInWindow >= maxBytesPerSecond) {
            val sleepTime = 1000 - elapsed
            if (sleepTime > 0) {
                try {
                    Thread.sleep(sleepTime)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            bytesReadInWindow = 0
            windowStartTimeMs = System.currentTimeMillis()
        }

        val remaining = maxBytesPerSecond - bytesReadInWindow
        val toRead = max(1, minOf(length.toLong(), remaining).toInt())

        // ✅ قراءة مباشرة من المصدر الأساسي
        val read = upstream.read(buffer, offset, toRead)
        if (read > 0) {
            bytesReadInWindow += read
        }
        return read
    }

    override fun getUri(): Uri? = upstream.uri

    override fun close() {
        upstream.close()
    }

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }
}
