// ... (باقي الاستيرادات كما هي)

class MainActivity : AppCompatActivity() {

    // ... (باقي المتغيرات كما هي)

    // ✅ Runnable لإخفاء الرسالة بعد ثانيتين
    private val hideSuccessRunnable = Runnable {
        binding.tvUpdateSuccess.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                binding.tvUpdateSuccess.visibility = View.GONE
            }
            .start()
    }

    // ... (دالة onCreate كما هي)

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.stations.collectLatest { list ->
                stationAdapter.submitList(list)
                binding.tvEmptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        // ... (باقي المراقبات كما هي)

        lifecycleScope.launch {
            viewModel.refreshResult.collectLatest { count ->
                // ✅ إظهار الرسالة الأنيقة بدلاً من Toast
                showSuccessMessage("✅ تم تحديث $count محطة بنجاح")
            }
        }

        // ... (باقي المراقبات كما هي)
    }

    // ✅ دالة إظهار الرسالة
    private fun showSuccessMessage(message: String) {
        binding.tvUpdateSuccess.text = message
        binding.tvUpdateSuccess.visibility = View.VISIBLE
        
        // تأثير الظهور (Fade In)
        binding.tvUpdateSuccess.animate()
            .alpha(1f)
            .setDuration(300)
            .start()

        // إلغاء أي مؤقت سابق وإعادة تعيين مؤقت الإخفاء (ثانيتين)
        updateHandler.removeCallbacks(hideSuccessRunnable)
        updateHandler.postDelayed(hideSuccessRunnable, 2000)
    }

    // ... (باقي الدوال مثل updateSleepTimerButton, updateMiniPlayer كما هي)
}
