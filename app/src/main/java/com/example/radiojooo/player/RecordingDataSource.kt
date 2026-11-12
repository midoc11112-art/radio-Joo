package com.example.radiojooo.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Wraps the network data source: (1) removes the ICY metadata request header so the stream is clean audio
 * (also a few bytes less), (2) hands every byte to the [Recorder] when recording is on.
 */
@UnstableApi
class RecordingDataSource(private val upstream: DataSource) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val headers = dataSpec.httpRequestHeaders.filterKeys { !it.equals("Icy-MetaData", ignoreCase = true) }
        val clean = dataSpec.buildUpon().setHttpRequestHeaders(headers).build()
        val length = upstream.open(clean)
        Recorder.contentType = upstream.responseHeaders["Content-Type"]?.firstOrNull() ?: ""
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = upstream.read(buffer, offset, length)
        if (n > 0) Recorder.write(buffer, offset, n)
        return n
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        upstream.close()
    }
}