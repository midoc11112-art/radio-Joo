// ... داخل MainActivity
    private fun playStation(station: StationEntity) {
        viewModel.setCurrentStation(station)

        val serviceIntent = Intent(this, RadioService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent) // تأكد من استخدام هذا السطر

        radioService?.playStation(
            title = station.name,
            uuid = station.uuid,
            pUrl = station.primaryUrl,
            fUrl = station.fallbackUrl
        )

        updateMiniPlayer()
    }
