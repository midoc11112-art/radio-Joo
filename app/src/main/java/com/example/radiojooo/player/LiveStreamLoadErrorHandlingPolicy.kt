package com.example.radiojooo.player

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

class LiveStreamLoadErrorHandlingPolicy : LoadErrorHandlingPolicy {

    private val extraRetries = 5
    private val retryDelayMs = 1500L

    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
    ): LoadErrorHandlingPolicy.FallbackSelection? {
        return null
    }

    override fun getRetryDelayMsFor(
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
    ): Long {
        if (loadErrorInfo.errorCount > extraRetries) {
            return C.TIME_UNSET // إيقاف المحاولات
        }
        return retryDelayMs
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int {
        return extraRetries
    }
}
