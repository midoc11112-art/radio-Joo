package com.example.radiojooo.data.local

import com.example.radiojooo.data.model.StationEntity

object TrustedStations {
    fun getInitialStations(): List<StationEntity> {
        val now = System.currentTimeMillis()
        return listOf(
            // --- المحطات المصرية الوطنية والخاصة ---
            StationEntity(
                uuid = "eg_quran_cairo",
                name = "إذاعة القرآن الكريم من القاهرة",
                primaryUrl = "https://stream.radiojar.com/8s5u5tpdtwzuv",
                fallbackUrl = "http://www.quran-radio.org:8002/",
                category = "QURAN",
                countryCode = "EG",
                bitrate = 128,
                popularity = 1000,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "eg_nogoum_fm",
                name = "نجوم إف إم 100.6 Nogoum FM",
                primaryUrl = "https://stream.radiojar.com/4s2n11eydn8uv",
                fallbackUrl = "https://stream-159.zeno.fm/qb1zvsykm98uv",
                category = "EGYPT",
                countryCode = "EG",
                bitrate = 128,
                popularity = 999,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "eg_nagham_fm",
                name = "نغم إف إم 105.3 Nagham FM",
                primaryUrl = "https://stream.radiojar.com/8s5u5tpdtwzuv",
                fallbackUrl = "https://stream.zeno.fm/na3vpvn10qruv",
                category = "EGYPT",
                countryCode = "EG",
                bitrate = 128,
                popularity = 998,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "eg_radio_9090",
                name = "راديو 9090 Radio 9090",
                primaryUrl = "https://9090live.radioca.st/stream",
                fallbackUrl = "http://9090streaming.mobtada.com/9090FMEGYPT",
                category = "EGYPT",
                countryCode = "EG",
                bitrate = 128,
                popularity = 997,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "eg_nrj_egypt",
                name = "إنيرجي إيجيبت 92.1 NRJ Egypt",
                primaryUrl = "https://nrjegypt.ice.infomaniak.ch/nrjegypt-128.mp3",
                fallbackUrl = "",
                category = "EGYPT",
                countryCode = "EG",
                bitrate = 128,
                popularity = 996,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "eg_mega_fm",
                name = "ميجا إف إم 92.7 Mega FM",
                primaryUrl = "https://stream.radiojar.com/8s5u5tpdtwzuv",
                fallbackUrl = "https://eu1.fastcast4u.com/proxy/prontofm?mp=/1",
                category = "EGYPT",
                countryCode = "EG",
                bitrate = 128,
                popularity = 995,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "eg_radio_masr",
                name = "راديو مصر 88.7 Radio Masr",
                primaryUrl = "https://stream.zeno.fm/0b97ef7rxchvv",
                fallbackUrl = "http://82.201.132.237:8000/;",
                category = "EGYPT",
                countryCode = "EG",
                bitrate = 128,
                popularity = 994,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "eg_sha3by_fm",
                name = "شعبي إف إم 95.0 Sha3by FM",
                primaryUrl = "https://radio95.radioca.st/;",
                fallbackUrl = "",
                category = "EGYPT",
                countryCode = "EG",
                bitrate = 128,
                popularity = 993,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "eg_radio_hits",
                name = "راديو هيتس 88.2 Radio Hits",
                primaryUrl = "https://radiohits882.radioca.st/;",
                fallbackUrl = "",
                category = "EGYPT",
                countryCode = "EG",
                bitrate = 128,
                popularity = 992,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "eg_onsport_fm",
                name = "أون سبورت إف إم 93.7 On Sport FM",
                primaryUrl = "https://carina.streamerr.co:2020/stream/OnSportFM",
                fallbackUrl = "",
                category = "SPORTS",
                countryCode = "EG",
                bitrate = 128,
                popularity = 991,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "eg_program_general",
                name = "إذاعة البرنامج العام 107.4 القاهرة",
                primaryUrl = "https://stream.zeno.fm/f3wvbb7524zuv",
                fallbackUrl = "",
                category = "EGYPT",
                countryCode = "EG",
                bitrate = 128,
                popularity = 990,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "eg_sawt_el_arab",
                name = "إذاعة صوت العرب 106.3 القاهرة",
                primaryUrl = "https://stream.zeno.fm/6598822v04zuv",
                fallbackUrl = "",
                category = "EGYPT",
                countryCode = "EG",
                bitrate = 128,
                popularity = 989,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "eg_shabab_ryada",
                name = "إذاعة الشباب والرياضة 108.0",
                primaryUrl = "https://stream.zeno.fm/0787d89z1heuv",
                fallbackUrl = "",
                category = "SPORTS",
                countryCode = "EG",
                bitrate = 128,
                popularity = 988,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "eg_radio_agani",
                name = "إذاعة الأغاني المصرية 105.8",
                primaryUrl = "https://stream.zeno.fm/zsgrfxg71s8uv",
                fallbackUrl = "",
                category = "EGYPT",
                countryCode = "EG",
                bitrate = 128,
                popularity = 987,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "eg_mix_fm",
                name = "ميكس إف إم 87.8 Mix FM مصر",
                primaryUrl = "https://stream-29.zeno.fm/na3vpvn10qruv",
                fallbackUrl = "",
                category = "EGYPT",
                countryCode = "EG",
                bitrate = 128,
                popularity = 986,
                lastUpdated = now
            ),

            // --- الإذاعات الإخبارية والتوك شو الدولية والعربية ---
            StationEntity(
                uuid = "news_aljazeera_voice",
                name = "صوت الجزيرة الإخبارية Al Jazeera",
                primaryUrl = "https://live-hls-audio-web-aja.getaj.net/VOICE-AJA/01.m3u8",
                fallbackUrl = "https://live-hls-web-aja.getaj.net/AJA/index.m3u8",
                category = "NEWS",
                countryCode = "QA",
                bitrate = 64,
                popularity = 1000,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "news_al_arabiya_fm",
                name = "العربية إف إم Al Arabiya FM",
                primaryUrl = "https://fm.alarabiya.net/fm/myStream/playlist.m3u8",
                fallbackUrl = "",
                category = "NEWS",
                countryCode = "SA",
                bitrate = 128,
                popularity = 999,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "news_sky_news_arabia",
                name = "سكاي نيوز عربية Sky News Arabia",
                primaryUrl = "https://stream.skynewsarabia.com/hls/sna.m3u8",
                fallbackUrl = "http://stream.skynewsarabia.com/hls/sna_720.m3u8",
                category = "NEWS",
                countryCode = "AE",
                bitrate = 128,
                popularity = 998,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "news_monte_carlo",
                name = "مونت كارلو الدولية Monte Carlo Doualiya",
                primaryUrl = "https://montecarlodoualiya128k.ice.infomaniak.ch/mc-doualiya.mp3",
                fallbackUrl = "https://montecarlo.ice.infomaniak.ch/mc-doualiya-128.mp3",
                category = "NEWS",
                countryCode = "FR",
                bitrate = 128,
                popularity = 997,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "news_alqahera_news",
                name = "القاهرة الإخبارية Al Qahera News",
                primaryUrl = "https://stream.zeno.fm/0787d89z1heuv",
                fallbackUrl = "",
                category = "NEWS",
                countryCode = "EG",
                bitrate = 128,
                popularity = 996,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "news_asharq_news",
                name = "الشرق للأخبار Asharq News Bloomberg",
                primaryUrl = "https://l3.itworkscdn.net/alarabyradiolive/alarabyradio_audio/icecast.audio",
                fallbackUrl = "",
                category = "NEWS",
                countryCode = "SA",
                bitrate = 128,
                popularity = 995,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "news_bbc_arabic",
                name = "بي بي سي عربي BBC Arabic Radio",
                primaryUrl = "http://bbcwssc.ic.llnwd.net/stream/bbcwssc_mp1_ws-araba",
                fallbackUrl = "https://stream.zeno.fm/6598822v04zuv",
                category = "NEWS",
                countryCode = "UK",
                bitrate = 64,
                popularity = 994,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "news_radio_sawa",
                name = "راديو سوا Radio Sawa",
                primaryUrl = "https://stream.zeno.fm/wvqgc9kb1d0uv",
                fallbackUrl = "",
                category = "NEWS",
                countryCode = "US",
                bitrate = 128,
                popularity = 993,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "news_france24_arabic",
                name = "فرانس 24 عربي France 24 Audio",
                primaryUrl = "https://replaynewsar.ice.infomaniak.ch/replaynewsar-128.mp3",
                fallbackUrl = "",
                category = "NEWS",
                countryCode = "FR",
                bitrate = 128,
                popularity = 992,
                lastUpdated = now
            ),

            // --- إذاعات القرآن الكريم والتلاوات المتخصصة ---
            StationEntity(
                uuid = "quran_saudi",
                name = "إذاعة القرآن الكريم - مكة المكرمة",
                primaryUrl = "http://n01.radiojar.com/0tpy1h0kxtzuv",
                fallbackUrl = "https://edge.mixlr.com/channel/rwumx",
                category = "QURAN",
                countryCode = "SA",
                bitrate = 128,
                popularity = 995,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "quran_abdulbasit",
                name = "الشيخ عبدالباسط عبدالصمد (تلاوة مرتلة)",
                primaryUrl = "https://radio.mp3islam.com/listen/abdulbasit/radio.mp3",
                fallbackUrl = "https://radio.xecod.com/station/abdulbasit",
                category = "QURAN",
                countryCode = "EG",
                bitrate = 128,
                popularity = 994,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "quran_minshawi",
                name = "الشيخ محمد صديق المنشاوي",
                primaryUrl = "https://radio.mp3islam.com/listen/minshawi/radio.mp3",
                fallbackUrl = "https://radio.xecod.com/station/alminshawi",
                category = "QURAN",
                countryCode = "EG",
                bitrate = 128,
                popularity = 993,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "quran_hussary",
                name = "الشيخ محمود خليل الحصري",
                primaryUrl = "https://serverkw.quran-uni.com:8018/;",
                fallbackUrl = "https://qurango.net/radio/mahmoud_khalil_alhussary",
                category = "QURAN",
                countryCode = "EG",
                bitrate = 128,
                popularity = 992,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "quran_mustafa_ismail",
                name = "الشيخ مصطفى إسماعيل",
                primaryUrl = "https://serverkw.quran-uni.com:8224/;",
                fallbackUrl = "https://qurango.net/radio/mustafa_ismail",
                category = "QURAN",
                countryCode = "EG",
                bitrate = 128,
                popularity = 991,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "quran_afasy",
                name = "الشيخ مشاري بن راشد العفاسي",
                primaryUrl = "https://radio.mp3islam.com/listen/mishary/radio.mp3",
                fallbackUrl = "https://radio.xecod.com/station/alafasi",
                category = "QURAN",
                countryCode = "KW",
                bitrate = 128,
                popularity = 990,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "quran_sudais",
                name = "الشيخ عبدالرحمن السديس",
                primaryUrl = "https://radio.mp3islam.com/listen/sudais/radio.mp3",
                fallbackUrl = "",
                category = "QURAN",
                countryCode = "SA",
                bitrate = 128,
                popularity = 989,
                lastUpdated = now
            ),

            // --- الموسيقى والفن الأصيل ---
            StationEntity(
                uuid = "music_om_kalthoum",
                name = "إذاعة كوكب الشرق أم كلثوم",
                primaryUrl = "https://stream.zeno.fm/zsgrfxg71s8uv",
                fallbackUrl = "",
                category = "MUSIC",
                countryCode = "EG",
                bitrate = 128,
                popularity = 990,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "music_halim",
                name = "إذاعة العندليب عبدالحليم حافظ",
                primaryUrl = "https://stream.zeno.fm/8a4eqkd0pnhvv",
                fallbackUrl = "",
                category = "MUSIC",
                countryCode = "EG",
                bitrate = 128,
                popularity = 989,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "music_fairuz",
                name = "إذاعة جارة القمر فيروز Fairuz Radio",
                primaryUrl = "https://stream.zeno.fm/xkhnk4vee18uv",
                fallbackUrl = "https://stream.zeno.fm/zv3e5wykprhvv.mp3",
                category = "MUSIC",
                countryCode = "LB",
                bitrate = 128,
                popularity = 988,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "music_amr_diab",
                name = "إذاعة الهضبة عمرو دياب Diab FM",
                primaryUrl = "https://stream-34.zeno.fm/rf64mx02qa0uv",
                fallbackUrl = "https://stream-40.zeno.fm/xa4yhh4k838uv",
                category = "MUSIC",
                countryCode = "EG",
                bitrate = 128,
                popularity = 987,
                lastUpdated = now
            ),
            StationEntity(
                uuid = "music_90s_egypt",
                name = "تسعينات إف إم Tes3enat FM مصر",
                primaryUrl = "http://178.33.135.244:20095/;",
                fallbackUrl = "",
                category = "MUSIC",
                countryCode = "EG",
                bitrate = 128,
                popularity = 986,
                lastUpdated = now
            )
        )
    }
}
