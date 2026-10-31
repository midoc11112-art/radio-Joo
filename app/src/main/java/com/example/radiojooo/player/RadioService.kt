// داخل RadioService
private var actualBitrateMeasured = false
private var playbackStartTime = 0L

// داخل calculateDataUsage() أو دالة منفصلة
private fun measureActualBitrate() {
    if (!isPlaying()) return
    if (playbackStartTime == 0L) {
        playbackStartTime = System.currentTimeMillis()
        return
    }
    
    val elapsedSeconds = (System.currentTimeMillis() - playbackStartTime) / 1000
    
    // ننتظر 15 ثانية لتجاوز البافر الأولي
    if (elapsedSeconds in 15..20 && !actualBitrateMeasured) {
        actualBitrateMeasured = true
        
        // حساب البتريت الفعلي بناءً على إجمالي البيانات المسحوبة خلال هذه الفترة
        // totalSessionBytes تم حسابها بالفعل في calculateDataUsage
        val actualKbps = (totalSessionBytes * 8) / (elapsedSeconds * 1000)
        
        // إرسال هذه القيمة إلى الـ ViewModel أو الـ Repository
        // يمكنك استخدام Callback أو SharedFlow
        onActualBitrateMeasured?.invoke(actualKbps.toInt())
    }
}
