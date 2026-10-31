package com.example.radiojooo.player

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

class ThrottledDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    private val maxBytesPerSecond: Long = 16 * 1024L
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        return ThrottledDataSource(upstreamFactory.createDataSource(), maxBytesPerSecond)
    }
}
