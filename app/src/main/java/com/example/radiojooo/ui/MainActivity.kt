private fun playStation(station: StationEntity) {
    viewModel.setCurrentStation(station)
    RadioService.requestPlayStation(
        this,
        title = station.name,
        uuid = station.uuid,
        pUrl = station.primaryUrl,
        fUrl = station.fallbackUrl,
        bitrate = station.bitrate,
        category = station.category,
        country = station.countryCode
    )
    updateMiniPlayer()
}
