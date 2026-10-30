package com.example.radiojooo.player

import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

class LiveStreamLoadErrorHandlingPolicy : LoadErrorHandlingPolicy {

    // عدد المحاولات الإضافية لإعادة الاتصال
    private val extraRetries = 5

    // تأخير قصير بين المحاولات (1.5 ثانية)
    private val retryDelayMs = 1500L

    /**
     * هذه الطريقة تحدد سياسة التبديل بين المصادر عند حدوث خطأ.
     * نعيد null لأننا لا نريد أي تبديل تلقائي (سنقوم بالتبديل يدوياً في RadioService).
     */
    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
    ): LoadErrorHandlingPolicy.FallbackSelection? {
        return null
    }

    /**
     * هذه الطريقة تحدد وقت الانتظار قبل إعادة المحاولة.
     */
    override fun getRetryDelayMsFor(
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
    ): Long {
        // إذا تجاوزنا الحد الأقصى للمحاولات، ننتظر وقتاً أطول (أو نتوقف)
        if (loadErrorInfo.errorCount > extraRetries) {
            return LoadErrorHandlingPolicy.DEFAULT_MIN_LOADABLE_RETRY_COUNT.toLong()
        }
        // غير ذلك، ننتظر 1.5 ثانية ونحاول مرة أخرى
        return retryDelayMs
    }

    /**
     * هذه الطريقة تحدد الحد الأدنى لعدد المحاولات قبل التخلي عن المصدر.
     */
    override fun getMinimumLoadableRetryCount(dataType: Int): Int {
        return extraRetries
    }
}
