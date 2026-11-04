package com.example.radiojooo.data.local

import com.example.radiojooo.data.model.StationEntity

/**
 * قائمة المحطات الموثوقة — LIST_VERSION = 20261105A
 * ==========================================================
 * كل رابط تم تحميله فعلياً بتاريخ 2026-11-05 والتأكد إنه بيانات صوت حقيقية.
 * محطات الـ HLS (الجزيرة/سكاي نيوز/MBC) بتشتغل عبر إعادة كتابة الـ playlist في الوكيل.
 * عدد المحطات: 71 — كلها شغالة وقت الفحص.
 */
object TrustedStations {

    /** رقم إصدار القائمة — لما يتغير، التطبيق بيمسح البذر القديم ويستبدده بالجديد */
    const val LIST_VERSION = "20261105A"

    val list: List<StationEntity> = listOf(
        StationEntity("manual-voice-of-jazeera", "صوت الجزيرة Al Jazeera Voice", "https://live-hls-audio-web-aja.getaj.net/VOICE-AJA/index.m3u8", "", "NEWS", "QA", 128, false, 999, 0L),
        StationEntity("manual-skynews-arabia", "سكاي نيوز عربية Sky News Arabia", "https://stream.skynewsarabia.com/hls/sna.m3u8", "", "NEWS", "AE", 128, false, 995, 0L),
        StationEntity("manual-mbc-fm", "MBC FM", "https://radio-mbcfm.mbc.net/radio-mbcfm_1.m3u8", "", "MUSIC", "SA", 128, false, 990, 0L),
        StationEntity("manual-quran-cairo-alt", "إذاعة القرآن الكريم من القاهرة", "https://n0d.radiojar.com/8s5u5tpdtwzuv.mp3?rj-ttl=5&rj-tok=AAABhwLBHbYAfz9OyE3ZQvJh-w", "", "QURAN", "EG", 128, false, 998, 0L),
        StationEntity("manual-mix-fm-eg", "ميكس إف إم 87.8 Mix FM", "https://eu1.fastcast4u.com/proxy/prontofm?mp=/1", "", "MUSIC", "EG", 128, false, 985, 0L),
        StationEntity("manual-radiowayak", "راديو وياك Radiowayak", "https://work.radiowayak.org/listen/live/live.mp3", "", "MUSIC", "EG", 128, false, 700, 0L),
        StationEntity("manual-hits882", "Hits 88.2 راديو هيتس", "https://radiohits882.radioca.st/stream", "", "MUSIC", "EG", 128, false, 980, 0L),
        StationEntity("manual-sha3bi", "شعبي إف إم SHA3BY FM", "https://radio95.radioca.st/stream", "", "MUSIC", "EG", 128, false, 975, 0L),
        StationEntity("manual-omkalthoum", "أم كلثوم OM Kalthoum Radio", "https://stream.zeno.fm/zsgrfxg71s8uv", "", "MUSIC", "EG", 128, false, 960, 0L),
        StationEntity("manual-radio9090", "راديو 9090", "http://9090streaming.mobtada.com/9090FMEGYPT", "", "MUSIC", "EG", 128, false, 988, 0L),
        StationEntity("manual-onsportfm", "أون سبورت إف إم On Sport FM", "https://carina.streamerr.co:2020/stream/OnSportFM", "", "SPORTS", "EG", 128, false, 970, 0L),
        StationEntity("manual-diabfm", "دياب إف إم Diab FM", "https://stream-34.zeno.fm/rf64mx02qa0uv?zs=-xjlLLwRS8uKrffFxK4vLA", "", "MUSIC", "EG", 128, false, 940, 0L),
        StationEntity("manual-arabmix256", "عرب ميكس 256 Arab Mix", "https://stream.zeno.fm/wvqgc9kb1d0uv", "", "MUSIC", "EG", 128, false, 930, 0L),
        StationEntity("manual-salma-recitation", "تلاوات خاشعة - جميلة Salma", "https://qurango.net/radio/salma", "", "QURAN", "SA", 128, false, 800, 0L),
        StationEntity("manual-ajyal", "أجيال إف إم فلسطين Ajyal", "https://streamer.mada.ps:8208/ajyal", "", "MUSIC", "PS", 128, false, 890, 0L),
        StationEntity("manual-radio-orient", "راديو الشرق Radio Orient لبنان", "https://stream.rcs.revma.com/7hnrkawf4p8uv.mp3", "", "MUSIC", "LB", 128, false, 880, 0L),
        StationEntity("manual-sumer-fm", "سمر إف إم العراق Sumer FM", "https://l3.itworkscdn.net/itwaudio/9012/stream", "", "MUSIC", "IQ", 128, false, 750, 0L),
        StationEntity("manual-alifalif", "ألف ألف إف إم AlifAlif FM", "https://alifalifjobs.com/radio/8000/AlifAlifLive.mp3", "", "MUSIC", "SA", 128, false, 999, 0L),
        StationEntity("manual-aswat", "إذاعة أصوات Aswat FM المغرب", "http://broadcast.ice.infomaniak.ch/aswat-high.mp3", "", "MUSIC", "MA", 128, false, 999, 0L),
        StationEntity("8dad4ff4-2ae8-4fba-b379-c964f6ae9f4a", "Arab Mix FM", "https://stream.zeno.fm/na3vpvn10qruv", "", "MUSIC", "EG", 128, false, 999, 0L),
        StationEntity("b1d17829-6522-481b-bb5c-839c3fd44353", "Radio Hits 88.2 Cairo", "https://radiohits882.radioca.st/;", "", "MUSIC", "EG", 128, false, 999, 0L),
        StationEntity("7eb9e8cd-bf7f-4bcc-afe2-3f56608911e6", "Elissa FM", "https://stream.zeno.fm/v7n499m8ckhvv", "", "MUSIC", "EG", 128, false, 999, 0L),
        StationEntity("5db41df8-9921-421f-a618-84864ededf86", "إذاعة القرآن الكريم (القاهرة)", "https://stream.radiojar.com/8s5u5tpdtwzuv", "", "QURAN", "EG", 0, false, 999, 0L),
        StationEntity("59f4eecd-7193-4bc3-8bc3-c1463d92b22d", "Alseraj Radio Quran", "http://192.99.170.8:5550/stream", "", "QURAN", "IQ", 96, false, 999, 0L),
        StationEntity("e9744b90-2619-4a0c-93b4-5996e213068a", "Mp3Quran Main", "https://qurango.net/radio/mix", "", "QURAN", "SA", 128, false, 999, 0L),
        StationEntity("77c8094d-6822-4221-be7b-1d54ab477d78", "Quran Radio راديو القرآن - Abdulrahman Alsudais - عبدالرحمن السديس", "https://radio.mp3islam.com/listen/sudais/radio.mp3", "", "QURAN", "KW", 64, false, 999, 0L),
        StationEntity("0f35d49a-3e12-4936-a8e7-a3ae7698ea58", "El Gouna Radio", "http://82.201.132.237:8000/;", "", "MUSIC", "EG", 128, false, 999, 0L),
        StationEntity("368ef657-f8b5-47ce-b578-337e401cc744", "Voice of Lebanon (Sawtlebnan) Radio", "https://l3.itworkscdn.net/itwaudio/9054/stream", "", "MUSIC", "LB", 128, false, 999, 0L),
        StationEntity("7d1378e3-8e91-46f9-a98d-6f7426e60a3c", "Sawt El Mada", "http://audiostreaming.itworkscdn.com:9018/stream", "", "MUSIC", "LB", 128, false, 999, 0L),
        StationEntity("125ec344-a2a1-4e36-8f66-77401f457d20", "Aswat FM", "https://broadcast.ice.infomaniak.ch/aswat-high.mp3", "", "MUSIC", "MA", 128, false, 999, 0L),
        StationEntity("0b14ae20-92b3-4db7-834f-ee6ba39ad080", "إذاعة مشاري العفاسي", "https://qurango.net/radio/mishary_alafasi", "", "MUSIC", "EG", 128, false, 999, 0L),
        StationEntity("3f5d8a52-dea9-4201-9575-a58adc8e9263", "إذاعة محمود خليل الحصري", "https://qurango.net/radio/mahmoud_khalil_alhussary_warsh", "", "QURAN", "EG", 128, false, 999, 0L),
        StationEntity("0155ec12-7308-468a-88dc-fcbd440e01da", "إذاعة مصطفى إسماعيل", "https://qurango.net/radio/mustafa_ismail", "", "MUSIC", "EG", 128, false, 999, 0L),
        StationEntity("ceba48a5-9356-4891-a1fc-5ff9ea832120", "90s FM", "http://eu1.fastcast4u.com/proxy/prontofm", "", "MUSIC", "EG", 128, false, 999, 0L),
        StationEntity("8bee6f3f-5bbd-4a2a-9cb9-0b75063f35b2", "---تراتيل قصيرة متميزة---", "https://qurango.net/radio/tarateel", "", "QURAN", "EG", 128, false, 999, 0L),
        StationEntity("30a739aa-0ef2-484c-a45a-4be7fbcb9603", "Arab Mix Drama", "https://stream.zeno.fm/egynebf171zuv.acc", "", "MUSIC", "EG", 128, false, 999, 0L),
        StationEntity("5e1c31e7-7541-439a-9081-6f78508bb637", "إذاعة ياسر الدوسري", "https://qurango.net/radio/yasser_aldosari", "", "MUSIC", "EG", 128, false, 999, 0L),
        StationEntity("6b77cd64-68ef-4984-91ec-9b7e77a26eda", "Radio Liban Libre", "https://edge.mixlr.com/channel/qtqeb", "", "NEWS", "LB", 0, false, 999, 0L),
        StationEntity("0cb24613-f3ba-41d6-9410-b3aaaa49c3f9", "Al rasheed fm", "https://streaming.shoutcast.com/alrasheed-fm?1719771692024", "", "MUSIC", "IQ", 320, false, 999, 0L),
        StationEntity("23754a47-c75e-11e8-a54a-52543be04c81", "Radio القارئ علي حجاج السويسي", "http://live.mp3quran.net:9842/", "", "MUSIC", "EG", 128, false, 999, 0L),
        StationEntity("6aed851e-a68c-43c6-a0b1-f0bb7476e5d3", "Abdulrasheed Soufi", "https://qurango.net/radio/abdulrasheed_soufi_assosi.mp3", "", "MUSIC", "EG", 128, false, 999, 0L),
        StationEntity("d42cb646-0c16-11e9-a80b-52543be04c81", "Libyana Hits FM", "http://proxima.shoutca.st:8946/stream", "", "MUSIC", "LY", 96, false, 999, 0L),
        StationEntity("344e6e4e-a7b3-11e8-a8ed-52543be04c81", "RadioSunna", "http://andromeda.shoutca.st:8189/stream", "", "QURAN", "SA", 32, false, 999, 0L),
        StationEntity("88ac049f-d017-11e9-a861-52543be04c81", "lbi Radio", "http://andromeda.shoutca.st:8192/;stream.mp3", "", "MUSIC", "LB", 128, false, 999, 0L),
        StationEntity("cd370991-b259-423b-a3d3-b972eb2265aa", "Radio Tunisie Culturelle", "http://rtstream.tanitweb.com/culturelle", "", "MUSIC", "TN", 128, false, 999, 0L),
        StationEntity("5b61ccc0-f369-4368-ba65-3b10b7f6bae9", "Mood Fm 92 Amman", "https://securestreams2.autopo.st:1241/live", "", "MUSIC", "JO", 128, false, 999, 0L),
        StationEntity("c5c08ef9-5559-4073-87a8-80bff2b4860b", "Monte Carlo Doualiya", "https://montecarlodoualiya128k.ice.infomaniak.ch/mc-doualiya.mp3", "", "NEWS", "FR", 128, false, 870, 0L),
        StationEntity("d9bbea87-9051-45cb-abe6-8992a7a76cf1", "Universal Health Radio (العربية)", "https://radio.universalhealthradio.org/uhr-ar.mp3", "", "NEWS", "SA", 0, false, 860, 0L),
        StationEntity("cd74834d-9504-42e9-a165-4d7194afaf54", "Sawt El Nagham", "https://stream.zeno.fm/66sfwug4ag0uv", "", "MUSIC", "LB", 128, false, 850, 0L),
        StationEntity("73f665df-37cd-4c4c-b398-6e97e526a01d", "LBI Radio Hits", "https://lbihits-lbnet2.radioca.st/stream", "", "MUSIC", "LB", 128, false, 760, 0L),
        StationEntity("4a116b98-e289-4630-9b62-b93a67159c7e", "Anghamy", "https://radio.avazfarsi.com:8010/radio.mp3", "", "MUSIC", "LB", 128, false, 671, 0L),
        StationEntity("580ef7ef-3ebe-45fd-8c90-25e365c64bc3", "Abdel Halim Hafez عبدالحليم حافظ", "https://stream.zeno.fm/8a4eqkd0pnhvv", "", "MUSIC", "EG", 128, false, 660, 0L),
        StationEntity("f0d6b0a0-a6b7-4574-8921-f581357ea998", "Fairuz فيروز", "https://stream.zeno.fm/xkhnk4vee18uv", "", "MUSIC", "LB", 128, false, 640, 0L),
        StationEntity("7cca8bd6-a173-4fca-9a74-ca1e15a631e5", "Quran Radio From Sharjah", "https://l3.itworkscdn.net/smcquranlive/quranradiolive/icecast.audio", "", "QURAN", "AE", 56, false, 580, 0L),
        StationEntity("2cad3c1f-d425-45fc-bf91-e30acf81775f", "Quran Recitation by Sheikh Wadi Al-Yamani", "https://serverkw.quran-uni.com:8226/;", "", "QURAN", "KW", 128, false, 580, 0L),
        StationEntity("eec68cdb-9fde-4a44-81d1-4e752f05fdf5", "Radio Liban", "https://media2.streambrothers.com:2020/stream/8194", "", "MUSIC", "LB", 64, false, 542, 0L),
        StationEntity("26eb1c40-f314-4976-8969-a70d1fcce1fc", "Hala FM", "https://listen-halafm.sharp-stream.com/halafmlow.mp3", "", "MUSIC", "OM", 64, false, 529, 0L),
        StationEntity("cbf8067e-8c54-4d62-81b9-df6589d782b1", "Sawt El Ghad", "https://l3.itworkscdn.net/itwaudio/9030/stream", "", "MUSIC", "LB", 128, false, 510, 0L),
        StationEntity("a9d04fb3-fd0e-4dbd-877e-5dd2d5d6e618", "Radio Sawt Alamal", "https://stream.zeno.fm/qgw73bqh5qruv", "", "MUSIC", "MA", 128, false, 510, 0L),
        StationEntity("f27afa46-8650-4a88-a632-6f46e61427b2", "Sawt El Ghad - Tarab", "https://sawtelghad.org:7007/;", "", "MUSIC", "PS", 128, false, 510, 0L),
        StationEntity("84fa3e78-74ff-4a18-a40d-0edf87e9fa05", "Sawt Al Hoda 92.1 FM", "https://l3.itworkscdn.net/itwaudio/9032/stream", "", "MUSIC", "LB", 128, false, 510, 0L),
        StationEntity("52691bbc-72f0-4b1c-93f0-2a0fd13d9018", "Sawt Sydney كل الفرق", "https://usa10.fastcast4u.com/nada22", "", "MUSIC", "AU", 128, false, 510, 0L),
        StationEntity("4e0c8d68-ad04-46e7-884d-0605979eb8d2", "Sawt Al Hob", "https://stream.zeno.fm/0b97ef7rxchvv", "", "MUSIC", "LB", 128, false, 510, 0L),
        StationEntity("9e88f54e-7a06-4225-a01e-9644a48d8499", "Medina FM Tarab (128 kbps)", "https://medinatarab.ice.infomaniak.ch/medinatarab-128.mp3", "", "MUSIC", "MA", 128, false, 500, 0L),
        StationEntity("8ada979f-dc6b-49ca-9dc9-bd3377c99aac", "Medina FM Nachat (128 kbps)", "https://medinanachat.ice.infomaniak.ch/medinanachat-128.mp3", "", "MUSIC", "MA", 128, false, 500, 0L),
        StationEntity("0502df2d-a93e-4d8a-8547-ae9d341a1128", "MEDINA FM", "https://medinafm.ice.infomaniak.ch/medinafm-64.mp3", "", "MUSIC", "MA", 64, false, 500, 0L),
        StationEntity("3194ab53-148e-482c-b392-6b33ad40d9b1", "Medina FM Andalou (128 kbps)", "https://medinaandalou.ice.infomaniak.ch/medinaandalou-128.mp3", "", "MUSIC", "MA", 128, false, 500, 0L),
        StationEntity("91ec0c03-f58c-4e42-a034-067a22f867d3", "Medina FM (128 kbps)", "https://medinafm.ice.infomaniak.ch/medinafm-128.mp3", "", "NEWS", "MA", 128, false, 500, 0L),
        StationEntity("ef2cb53f-3f77-482c-942b-a2826cb324af", "Al Wisal", "http://uk7.internet-radio.com:8067/;", "", "NEWS", "OM", 96, false, 463, 0L),
        StationEntity("050966be-de77-49f0-bad9-6ef200d219f3", "Ambiance FM", "https://stream.zeno.fm/0rehsamc9xxtv", "", "NEWS", "TN", 192, false, 416, 0L),
        StationEntity("b0d990d7-167a-44a1-830e-35dedaa2982d", "Al rasheed fm )Kirkuk)", "https://streaming.shoutcast.com/alrasheed-kirkuk", "", "MUSIC", "IQ", 128, false, 154, 0L),
    )
}
