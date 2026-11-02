package com.example.radiojooo.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.radiojooo.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // تسجيل عقد طلب الإذن بشكل احترافي باستخدام Activity Result API
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // تم منح الإذن بنجاح - ستظهر عناصر تحكم الإشعار وشاشة القفل
        } else {
            // تم رفض الإذن - تنبيه المستخدم بأهمية الإذن لتشغيل الراديو في الخلفية
            Toast.makeText(
                this,
                "إذن الإشعارات مطلوب للتحكم بالراديو من شريط الإشعارات وشاشة القفل",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // التحقق من الإذن وطلبه فور فتح الشاشة الرئيسية
        checkAndRequestNotificationPermission()
    }

    private fun checkAndRequestNotificationPermission() {
        // الإذن مطلوب فقط للأنظمة بدءاً من Android 13 (Tiramisu - API 33) وما فوق
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // الإذن ممنوح بالفعل، لا داعي للطلب
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    // إذا كان المستخدم قد رفض الإذن سابقاً، نعرض الطلب مجدداً مع توضيح الفائدة
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    // طلب الإذن لأول مرة
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
}
