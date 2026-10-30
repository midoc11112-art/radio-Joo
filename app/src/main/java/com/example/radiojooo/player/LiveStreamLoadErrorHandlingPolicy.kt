package com.example.radiojooo.player

import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaSource

class LiveStreamLoadErrorHandlingPolicy : LoadErrorHandlingPolicy {

    // عدد المحاولات الإضافية لإعادة الاتصال
    private val extraRetries = 5

    // تأخير قصير بين المحاولات (1.5 ثانية)
    private val retryDelayMs = 1500L

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        // إذا تجاوزنا الحد الأقصى للمحاولات، توقف عن المحاولة
        if (loadErrorInfo.errorCount > extraRetries) {
            return LoadErrorHandlingPolicy.DEFAULT_MIN_LOADABLE_RETRY_COUNT_PROGRESSIVE_LIVE.toLong()
        }
        // غير ذلك، انتظر 1.5 ثانية وحاول مرة أخرى
        return retryDelayMs
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int {
        // زيادة عدد المحاولات للبث المباشر
        return extraRetries
    }
}
