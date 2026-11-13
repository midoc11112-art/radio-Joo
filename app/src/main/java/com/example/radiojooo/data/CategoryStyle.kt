package com.example.radiojooo.data

import android.graphics.Color

/** One place that defines how each category looks (emoji, color, Arabic name) for the list, player and notification. */
object CategoryStyle {
    fun emoji(c: String) = when (c) {
        "QURAN" -> "\uD83D\uDCD6"; "NEWS" -> "\uD83D\uDCF0"; "MUSIC" -> "\uD83C\uDFB5"; "SPORTS" -> "\u26BD"; "TALK" -> "\uD83C\uDF99\uFE0F"; else -> "\uD83D\uDCFB"
    }

    fun color(c: String): Int = Color.parseColor(
        when (c) {
            "QURAN" -> "#047857"; "NEWS" -> "#1D4ED8"; "MUSIC" -> "#7C3AED"; "SPORTS" -> "#C2410C"
            "TALK" -> "#0F766E"; else -> "#334155"
        }
    )

    fun arabicName(c: String) = when (c) {
        "QURAN" -> "\u0627\u0644\u0642\u0631\u0622\u0646 \u0627\u0644\u0643\u0631\u064A\u0645"; "NEWS" -> "\u0627\u0644\u0623\u062E\u0628\u0627\u0631"; "MUSIC" -> "\u0627\u0644\u0645\u0648\u0633\u064A\u0642\u0649"; "SPORTS" -> "\u0627\u0644\u0631\u064A\u0627\u0636\u0629"
        "TALK" -> "\u062A\u0648\u0643 \u0634\u0648"; else -> c
    }
}