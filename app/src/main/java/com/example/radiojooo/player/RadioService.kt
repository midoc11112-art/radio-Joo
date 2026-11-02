    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // زر الإغلاق (X) - أيقونة حمراء دائرية
        val stopIntent = Intent(RadioActionReceiver.ACTION_STOP).apply {
            setPackage(packageName)
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // زر التشغيل/الإيقاف - أيقونة زرقاء دائرية
        val playPauseIntent = Intent(RadioActionReceiver.ACTION_PLAY_PAUSE).apply {
            setPackage(packageName)
        }
        val playPausePendingIntent = PendingIntent.getBroadcast(
            this, 0, playPauseIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // استخدام الأيقونات الدائرية الجديدة
        val playPauseIcon = R.drawable.ic_notification_play_pause
        val playPauseText = if (player.isPlaying) "⏸️ إيقاف" else "▶️ تشغيل"
        
        val largeIcon = getLargeIcon()

        // بناء النص الفرعي من المعلومات الفعلية
        val subTextParts = mutableListOf<String>()
        
        if (currentBitrate > 0) {
            subTextParts.add(currentBitrate.toString() + " Kbps")
        }
        
        if (currentCategory.isNotBlank()) {
            subTextParts.add(getCategoryArabicName(currentCategory))
        }
        
        if (currentCountry.isNotBlank()) {
            subTextParts.add(currentCountry)
        }
        
        val subText = if (subTextParts.isNotEmpty()) {
            subTextParts.joinToString(" • ")
        } else {
            "راديو جو"
        }

        // تصميم الودجت بشكل فخم ومتناسق
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(subText)
            .setSmallIcon(R.drawable.ic_notification_play_pause)
            .setLargeIcon(largeIcon)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setColor(0xFF1976D2.toInt()) // لون أزرق موحد
            .setColorized(true)
            // زر الإغلاق (أيقونة حمراء دائرية)
            .addAction(R.drawable.ic_notification_close, "❌ خروج", stopPendingIntent)
            // زر التشغيل/الإيقاف (أيقونة زرقاء دائرية)
            .addAction(playPauseIcon, playPauseText, playPausePendingIntent)
            .setStyle(
                MediaStyle()
                    .setShowActionsInCompactView(0, 1)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(stopPendingIntent)
            )
            .build()
    }
