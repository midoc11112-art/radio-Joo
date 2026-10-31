package com.example.radiojooo.data.util

import java.util.Locale

object StationNormalizer {

    private val quranKeywords = listOf("quran", "koran", "islamic", "قرآن", "قرأن", "تلاوة", "إسلامي")
    private val newsKeywords = listOf("news", "talk", "أخبار", "اخبار", "حوار", "الجزيرة", "العربية", "الحدث", "روسيا اليوم", "مونت كارلو")
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

    // ✅ خوارزمية الفلترة الجديدة: تعتمد على اللغة أولاً، وليس على اسم العلامة التجارية
    fun isArabicStation(name: String, language: String, countryCode: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        val l = language.lowercase(Locale.ROOT)
        val c = countryCode.uppercase(Locale.ROOT)

        // 1. إذا كان الاسم يحتوي على أحرف عربية، اقبل فوراً
        if (name.any { it in '\u0600'..'\u06FF' }) return true

        // 2. إذا كانت اللغة عربية، اقبل فوراً
        if (l.contains("arabic") || l == "ar" || l.contains(",ar") || l.contains("ar,")) return true

        // 3. إذا كان الاسم يحتوي على كلمة "arabic" أو "عربي" أو "عربية"، اقبل فوراً
        if (n.contains("arabic") || n.contains("عربي") || n.contains("عربية")) return true

        // 4. أسماء النسخ العربية المعروفة (يجب أن تكون صريحة)
        val arabicBrands = listOf(
            "aljazeera", "alarabiya", "alhadath", "sky news arabia",
            "rt arabic", "france 24 arabic", "dw arabic", "monte carlo", "mcd"
        )
        if (arabicBrands.any { n.contains(it) }) return true

        // 5. إذا كانت اللغة أجنبية صريحة، ارفض (ما لم تستوفِ الشروط أعلاه)
        val foreignLangs = listOf(
            "english", "french", "german", "spanish", "italian",
            "russian", "chinese", "japanese", "korean", "portuguese",
            "dutch", "polish", "turkish", "greek"
        )
        if (foreignLangs.any { l.contains(it) }) return false

        // 6. إذا كانت الدولة عربية، اقبل (حتى لو كانت اللغة غير معروفة)
        val arabCountries = listOf(
            "EG", "SA", "AE", "QA", "KW", "BH", "OM", "JO", "LB",
            "SY", "IQ", "PS", "YE", "SD", "LY", "TN", "DZ", "MA",
            "MR", "SO", "DJ", "KM"
        )
        if (c in arabCountries) return true

        // 7. القبول الافتراضي (لتجنب الحجب الخاطئ لمحطة عربية غير معروفة)
        return true
    }
}
