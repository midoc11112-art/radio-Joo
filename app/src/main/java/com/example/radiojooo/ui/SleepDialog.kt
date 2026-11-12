package com.example.radiojooo.ui

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.util.UnstableApi
import com.example.radiojooo.R
import com.example.radiojooo.player.RadioService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/** Sleep timer dialog: two boxes (hours / minutes) typed by hand. When time is up the radio AND the app close completely. */
@UnstableApi
object SleepDialog {
    fun show(activity: Activity, remainingSec: Long) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_sleep_timer, null)
        val etH = view.findViewById<EditText>(R.id.etSleepHours)
        val etM = view.findViewById<EditText>(R.id.etSleepMinutes)
        val tvRem = view.findViewById<TextView>(R.id.tvSleepRemaining)
        if (remainingSec > 0) {
            tvRem.visibility = View.VISIBLE
            tvRem.text = String.format(Locale.US, "المؤقت الحالي: متبقي %d:%02d:%02d", remainingSec / 3600, (remainingSec % 3600) / 60, remainingSec % 60)
        }
        val dialog = MaterialAlertDialogBuilder(activity, R.style.RadioDialog)
            .setView(view)
            .setPositiveButton("تشغيل المؤقت", null)
            .setNeutralButton("إلغاء المؤقت") { _, _ ->
                RadioService.sleepTimer(activity, 0)
                Toast.makeText(activity, "تم إلغاء المؤقت", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("رجوع", null)
            .create()
        dialog.setOnShowListener {
            // validate before closing: keep the dialog open when the input is wrong
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val h = etH.text.toString().trim().toIntOrNull() ?: 0
                val m = etM.text.toString().trim().toIntOrNull() ?: 0
                val total = h * 60 + m
                if (total < 1 || total > 24 * 60) {
                    Toast.makeText(activity, "اكتب مدة صحيحة (من دقيقة إلى 24 ساعة)", Toast.LENGTH_SHORT).show()
                } else {
                    RadioService.sleepTimer(activity, total)
                    Toast.makeText(activity, "سيُغلق التطبيق بالكامل بعد ${h} ساعة و ${m} دقيقة", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
}
