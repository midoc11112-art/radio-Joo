package com.example.radiojooo.player

import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

class LiveStreamLoadErrorHandlingPolicy : LoadErrorHandlingPolicy {
    private val extraRetries = 5
    private val retryDelayMs = 1500L

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (loadErrorInfo.errorCount > extraRetries) {
            return LoadErrorHandlingPolicy.DEFAULT_MIN_LOADABLE_RETRY_COUNT_PROGRESSIVE_LIVE.toLong()
        }
        return retryDelayMs
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int {
        return extraRetries
    }
}
