package com.example.radiojooo.data.util

import com.example.radiojooo.data.model.StationEntity
import java.util.Locale

object StationNormalizer {

    private val quranKeywords = listOf("quran", "koran", "islamic", "قرآن", "قرأن", "تلاوة", "إسلامي")
    private val newsKeywords = listOf("news", "talk", "أخبار", "اخبار", "حوار", "الجزيرة", "العربية", "bbc", "sky news", "sputnik", "الحدث", "روسيا اليوم", "مونت كارلو", "mcd")
    private val sportsKeywords = listOf("sport", "sports", "رياضة", "سبورت", "أهلي", "زمالك")
    private val musicKeywords = listOf("music", "hits", "pop", "موسيقى", "أغاني", "طرب", "نجوم", "شعبي", "نغم")

    fun classify(uuid: String, name: String, tags: String): String {
        val n = name.lowercase(Locale.ROOT)
        val t = tags.lowercase(Locale.ROOT)

        val isQuran = quranKeywords.any { n.contains(it) || t.contains(it) }
        val isNews = newsKeywords.any { n.contains(it) || t.contains(it) }
        val isSports = sportsKeywords.any { n.contains(it) || t.contains(it) }
        val isMusic = musicKeywords.any { n.contains(it) || t.contains(it) }

        return when {
            isQuran -> "QURAN"
            isNews -> "NEWS"
            isSports -> "SPORTS"
            isMusic -> "MUSIC"
            uuid.startsWith("eg_") -> "EGYPT"
            else -> "GENERAL"
        }
    }

    // ✅ خوارزمية الفلترة الذكية: لا نستبعد إلا إذا تأكدنا 100% أنها أجنبية
    fun isArabicStation(name: String, language: String, countryCode: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        val l = language.lowercase(Locale.ROOT)
        val c = countryCode.uppercase(Locale.ROOT)

        // 1. قبول فوري إذا كان الاسم يحتوي على حروف عربية
        if (name.any { it in '\u0600'..'\u06FF' }) return true

        // 2. قبول فوري إذا كانت اللغة عربية
        if (l.contains("arabic") || l == "ar" || l.contains(",ar") || l.contains("ar,")) return true

        // 3. قبول فوري إذا كانت الدولة عربية
        val arabCountries = listOf(
            "EG", "SA", "AE", "QA", "KW", "BH", "OM", "JO", "LB", 
            "SY", "IQ", "PS", "YE", "SD", "LY", "TN", "DZ", "MA", 
            "MR", "SO", "DJ", "KM"
        )
        if (c in arabCountries) return true

        // 4. قبول فوري إذا كان اسم المحطة معروفاً كعلامة إخبارية عربية (حتى لو كانت اللغة إنجليزية)
        val arabicBrands = listOf(
            "arab", "jazeera", "alarabiya", "bbc arabic", "sputnik", 
            "monte carlo", "mcd", "sky news arabia", "alhadath", 
            "rt arabic", "france 24 arabic", "dw arabic"
        )
        if (arabicBrands.any { n.contains(it) }) return true

        // 5. الاستبعاد فقط إذا كانت اللغة أجنبية صريحة والدولة غير عربية
        val foreignLangs = listOf(
            "french", "german", "spanish", "italian", "russian", 
            "chinese", "japanese", "korean", "portuguese", "dutch", 
            "polish", "turkish", "greek"
        )
        if (foreignLangs.any { l.contains(it) }) return false

        // 6. إذا لم نستطع التأكد، نقبلها (لضمان عدم خسارة أي محطة عربية)
        return true
    }
}
