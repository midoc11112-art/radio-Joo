package com.example.radiojooo.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.example.radiojooo.data.CategoryStyle

/** Draws the colorful rounded artwork (gradient + category emoji) shown in the notification. Cached per category. */
object NotificationArt {
    private val cache = HashMap<String, Bitmap>()

    @Synchronized fun largeIcon(category: String): Bitmap {
        cache[category]?.let { return it }
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val base = CategoryStyle.color(category)
        val light = Color.rgb(
            minOf(255, Color.red(base) + 70), minOf(255, Color.green(base) + 70), minOf(255, Color.blue(base) + 70)
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), light, base, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 56f, 56f, paint)
        paint.shader = null
        paint.color = Color.WHITE
        paint.textSize = 128f
        paint.textAlign = Paint.Align.CENTER
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(CategoryStyle.emoji(category), size / 2f, y, paint)
        cache[category] = bmp
        return bmp
    }
}
