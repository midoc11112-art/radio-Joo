package com.example.radiojooo.data

import android.graphics.Color

/** One place that defines how each category looks (emoji, color, Arabic name) for the list, player and notification. */
object CategoryStyle {
    fun emoji(c: String) = when (c) {
        "EGYPT" -> "\uD83C\uDDEA\uD83C\uDDEC"
        "QURAN" -> "\uD83D\uDCD6"
        "NEWS" -> "\uD83D\uDCF0"
        "TARAB" -> "\uD83C\uDFB6"
        "SONGS", "MUSIC" -> "\uD83C\uDFB5"
        "SPORTS" -> "\u26BD"
        "TALK" -> "\uD83C\uDF99\uFE0F"
        "GULF" -> "\uD83C\uDDF8\uD83C\uDDE6"
        "LEVANT_MAGHREB" -> "\uD83C\uDF0D"
        "GENERAL" -> "\uD83D\uDCFB"
        else -> "\uD83D\uDCFB"
    }

    fun color(c: String): Int = Color.parseColor(
        when (c) {
            "EGYPT" -> "#B91C1C"
            "QURAN" -> "#047857"
            "NEWS" -> "#1D4ED8"
            "TARAB" -> "#9D174D"
            "SONGS", "MUSIC" -> "#7C3AED"
            "SPORTS" -> "#C2410C"
            "TALK" -> "#0F766E"
            "GULF" -> "#0369A1"
            "LEVANT_MAGHREB" -> "#4338CA"
            else -> "#334155"
        }
    )

    fun arabicName(c: String) = when (c) {
        "EGYPT" -> "\u0625\u0630\u0627\u0639\u0627\u062A \u0645\u0635\u0631"
        "QURAN" -> "\u0627\u0644\u0642\u0631\u0622\u0646 \u0627\u0644\u0643\u0631\u064A\u0645"
        "NEWS" -> "\u0627\u0644\u0623\u062E\u0628\u0627\u0631"
        "TARAB" -> "\u0637\u0631\u0628 \u0623\u0635\u064A\u0644"
        "SONGS", "MUSIC" -> "\u0623\u063A\u0627\u0646\u064A \u0648\u0645\u0648\u0633\u064A\u0642\u0649"
        "SPORTS" -> "\u0627\u0644\u0631\u064A\u0627\u0636\u0629"
        "TALK" -> "\u0628\u0631\u0627\u0645\u062C \u0648\u062D\u0648\u0627\u0631"
        "GULF" -> "\u0625\u0630\u0627\u0639\u0627\u062A \u0627\u0644\u062E\u0644\u064A\u062C"
        "LEVANT_MAGHREB" -> "\u0627\u0644\u0634\u0627\u0645 \u0648\u0627\u0644\u0645\u063A\u0631\u0628"
        "GENERAL" -> "\u0625\u0630\u0627\u0639\u0627\u062A \u0639\u0627\u0645\u0629"
        else -> c
    }
}