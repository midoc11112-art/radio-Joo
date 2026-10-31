package com.example.radiojooo.ui

import android.os.Bundle
import androidx.activity.viewModels // ✅ التعديل هنا
import androidx.appcompat.app.AppCompatActivity
import com.example.radiojooo.databinding.ActivityPlayerBinding

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private val viewModel: RadioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // TODO: كود واجهة المشغل
    }
}
