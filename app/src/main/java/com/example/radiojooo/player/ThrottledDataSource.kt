package com.example.radiojooo.player

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InputStream
import kotlin.math.max

class ThrottledDataSource(
    private val upstream: DataSource,
    private val maxBytesPerSecond: Long = 16 * 1024L // 16 KB/s = 128 kbps
) : DataSource {

    private var bytesReadInWindow = 0L
    private var windowStartTimeMs = 0L
    private var inputStream: InputStream? = null

    override fun open(dataSpec: DataSpec): Long {
        val length = upstream.open(dataSpec)
        inputStream = upstream.inputStream() // ✅ التعديل هنا: إضافة الأقواس
        bytesReadInWindow = 0
        windowStartTimeMs = System.currentTimeMillis()
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val stream = inputStream ?: return -1

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

        val read = stream.read(buffer, offset, toRead)
        if (read > 0) {
            bytesReadInWindow += read
        }
        return read
    }

    override fun getUri(): Uri? = upstream.uri

    override fun close() {
        try {
            upstream.close()
        } finally {
            inputStream = null
        }
    }

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }
}
