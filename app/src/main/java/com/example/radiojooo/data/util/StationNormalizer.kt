package com.example.radiojooo.data.util

import com.example.radiojooo.data.model.Station
import com.example.radiojooo.data.model.StationEntity
import java.util.Locale

object StationNormalizer {

    private val quranKeywords = listOf("quran", "koran", "islamic", "قرآن", "قرأن", "تلاوة", "إسلامي")
    private val newsKeywords = listOf("news", "talk", "أخبار", "اخبار", "حوار", "الجزيرة", "العربية", "bbc", "sky news", "sputnik", "الحدث", "روسيا اليوم")
    private val sportsKeywords = listOf("sport", "sports", "رياضة", "سبورت", "أهلي", "زمالك")
    private val musicKeywords = listOf("music", "hits", "pop", "موسيقى", "أغاني", "طرب", "نجوم", "شعبي", "نغم")

    fun classify(uuid: String, name: String, tags: String): String {
        val n = name.lowercase(Locale.ROOT)
        val t = tags.lowercase(Locale.ROOT)

        val isQuran = quranKeywords.any { n.contains(it) || t.contains(it) }
        val isNews = newsKeywords.any { n.contains(it) || t.contains(it) }
        val isSports = sportsKeywords.any { n.contains(it) || t.contains(it) }
        val isMusic = musicKeywords.any { n.contains(it) || t.contains(it) }

        // الأولوية الصارمة: القرآن أولاً، ثم الأخبار، ثم الرياضة، ثم الموسيقى
        return when {
            isQuran -> "QURAN"
            isNews -> "NEWS"
            isSports -> "SPORTS"
            isMusic -> "MUSIC"
            uuid.startsWith("eg_") -> "EGYPT"
            else -> "GENERAL"
        }
    }

    // دالة الفلترة اللغوية: نستبعد المحطات الأجنبية ونبقي المحطات العربية
    fun isArabicStation(name: String, language: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        val l = language.lowercase(Locale.ROOT)

        // إذا كانت اللغة عربية، اقبلها فوراً
        if (l.contains("arabic") || l.contains("ar")) return true

        // إذا كان الاسم يحتوي على كلمات عربية أو محطات إخبارية معروفة بالعربية، اقبلها
        val arabicKeywords = listOf("عربي", "عربية", "arab", "arabic", "الجزيرة", "العربية", "الحدث", "سبوتنيك", "bbc", "sky")
        if (arabicKeywords.any { n.contains(it) }) return true

        // استبعاد المحطات الأجنبية الصريحة
        val foreignKeywords = listOf("french", "german", "italian", "spanish", "english", "deutsch", "français", "italiano", "español")
        if (foreignKeywords.any { n.contains(it) || l.contains(it) }) return false

        // القبول كخيار أخير إذا كان المصدر عربي (مثل EG)
        return true 
    }
}
