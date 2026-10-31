// ... (نفس الكود السابق، فقط تأكد من هذا السطر في onCreate)
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    requestAllPermissions()
    scheduleStationSync() 

    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)

    // ✅ إخفاء مؤشر التحميل تماماً عند بداية فتح التطبيق
    binding.swipeRefreshLayout.isRefreshing = false

    setupRecyclerView()
    setupSearch()
    setupChipGroup()
    setupSwipeRefresh()
    setupHeaderButtons()
    setupMiniPlayer()
    observeViewModel()
}
