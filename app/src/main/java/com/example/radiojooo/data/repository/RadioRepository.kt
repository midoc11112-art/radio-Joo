// ... (باقي الكود كما هو)

    suspend fun refreshStations(): Int = withContext(Dispatchers.IO) {
        supervisorScope {
            val jobs = listOf(
                async { withTimeoutOrNull(15_000L) { fetchFromRadioBrowserEG() } },
                async { withTimeoutOrNull(15_000L) { fetchFromRadioBrowserArabic() } },
                async { withTimeoutOrNull(15_000L) { fetchFromRadioBrowserNews() } },
                async { withTimeoutOrNull(15_000L) { fetchFromIptvOrgArabic() } },
                async { withTimeoutOrNull(15_000L) { fetchFromEgyptM3U() } }
            )
            
            // ✅ الحفظ التدريجي: كل مصدر ينتهي، يتم حفظه فوراً في Room، والواجهة تتحدث تلقائياً
            jobs.forEach { job ->
                val list = job.await()
                if (list != null && list.isNotEmpty()) {
                    val distinctList = list.distinctBy { it.uuid }
                    val currentFavs = stationDao.getStationsListDirect().filter { it.isFavorite }.map { it.uuid }.toSet()
                    val updated = distinctList.map { it.copy(isFavorite = currentFavs.contains(it.uuid)) }
                    stationDao.insertStations(updated)
                }
            }
        }
        // إرجاع العدد الإجمالي النهائي للمحطات
        stationDao.getStationsCount()
    }

// ... (باقي الكود كما هو)
