package com.example.radiojooo.data.local

import com.example.radiojooo.data.model.StationEntity

/**
 * قائمة المحطات الموثوقة — LIST_VERSION = "20261104B"
 * =========================================================
 * كل رابط تم فحصه فعلياً بتاريخ 2026-11-04 بواسطة deep audio probe:
 * تحميل البث لمدة 7 ثوانٍ والتأكد من وصول بيانات صوت حقيقية (>=4KB/s)
 * أو HLS playlist صالح بـ HTTP 200 مع مقاطع حية.
 * التصنيف (category) يدوي لكل محطة — لا يعتمد على تاجز Radio Browser.
 * عدد المحطات: 51 — كلها شغالة وقت الفحص.
 */
object TrustedStations {

    /** رقم إصدار القائمة — لما يتغير، التطبيق بيمسح البذر القديم ويستبدله بالجديد */
    const val LIST_VERSION = "20261104B"

    val list: List<StationEntity> = listOf(

        StationEntity("v2-488939558", "صوت الجزيرة Al Jazeera Voice", "https://live-hls-audio-web-aja.getaj.net/VOICE-AJA/01.m3u8", "", "NEWS", "QA", 128, false, 999, 0L),
        StationEntity("v2-218986724", "سكاي نيوز عربية Sky News Arabia", "https://stream.skynewsarabia.com/hls/sna.m3u8", "", "NEWS", "AE", 128, false, 998, 0L),
        StationEntity("v2-276430621", "مونت كارلو الدولية Monte Carlo Doualiya", "https://montecarlodoualiya128k.ice.infomaniak.ch/mc-doualiya.mp3", "", "TALK", "FR", 128, false, 997, 0L),
        StationEntity("v2-274997283", "راديو وياك Radiowayak مصر", "https://work.radiowayak.org/listen/live/live.mp3", "", "TALK", "EG", 128, false, 996, 0L),
        StationEntity("v2-771409511", "ألف ألف إف إم AlifAlif FM السعودية", "https://alifalifjobs.com/radio/8000/AlifAlifLive.mp3", "", "TALK", "SA", 128, false, 995, 0L),
        StationEntity("v2-923322247", "صوت الأمل Sawt Alamal المغرب", "https://stream.zeno.fm/qgw73bqh5qruv", "", "TALK", "MA", 128, false, 994, 0L),
        StationEntity("v2-481539132", "راديو تونس الثقافية", "http://rtstream.tanitweb.com/culturelle", "", "TALK", "TN", 128, false, 993, 0L),
        StationEntity("v2-898872686", "إذاعة القرآن الكريم من القاهرة", "https://n0d.radiojar.com/8s5u5tpdtwzuv.mp3?rj-ttl=5&rj-tok=AAABhwLBHbYAfz9OyE3ZQvJh-w", "", "QURAN", "EG", 128, false, 992, 0L),
        StationEntity("v2-662079550", "القرآن الكريم إذاعة الحرمين Saudi Quran", "http://n01.radiojar.com/0tpy1h0kxtzuv?rj-ttl=5&rj-tok=AAABjrUiYUAAxwNU2wDdcOap9w", "", "QURAN", "SA", 128, false, 991, 0L),
        StationEntity("v2-422907639", "الشيخ عبدالباسط عبدالصمد", "https://radio.mp3islam.com/listen/abdulbasit/radio.mp3", "", "QURAN", "KW", 128, false, 990, 0L),
        StationEntity("v2-379608058", "الشيخ محمد صديق المنشاوي", "https://radio.mp3islam.com/listen/minshawi/radio.mp3", "", "QURAN", "KW", 128, false, 989, 0L),
        StationEntity("v2-479808795", "الشيخ عبدالرحمن السديس", "https://radio.mp3islam.com/listen/sudais/radio.mp3", "", "QURAN", "KW", 128, false, 988, 0L),
        StationEntity("v2-706457037", "الشيخ محمود خليل الحصري", "https://serverkw.quran-uni.com:8018/;", "", "QURAN", "KW", 128, false, 987, 0L),
        StationEntity("v2-787541356", "الشيخ مصطفى إسماعيل", "https://serverkw.quran-uni.com:8224/;", "", "QURAN", "KW", 128, false, 986, 0L),
        StationEntity("v2-481941196", "تراتيل قصيرة متميزة Mp3Quran", "https://qurango.net/radio/tarateel", "", "QURAN", "SA", 128, false, 985, 0L),
        StationEntity("v2-789228241", "السراج القرآني العراق", "http://192.99.170.8:5550/stream", "", "QURAN", "IQ", 128, false, 984, 0L),
        StationEntity("v2-721801364", "راديو القرآن شارجة Sharjah Quran", "https://l3.itworkscdn.net/smcquranlive/quranradiolive/icecast.audio", "", "QURAN", "AE", 128, false, 983, 0L),
        StationEntity("v2-286354526", "راديو 9090", "http://9090streaming.mobtada.com/9090FMEGYPT", "", "MUSIC", "EG", 128, false, 982, 0L),
        StationEntity("v2-376828372", "ميكس إف إم 87.8 Mix FM", "https://eu1.fastcast4u.com/proxy/prontofm?mp=/1", "", "MUSIC", "EG", 128, false, 981, 0L),
        StationEntity("v2-776806704", "راديو هيتس 88.2 Hits FM", "https://radiohits882.radioca.st/stream", "", "MUSIC", "EG", 128, false, 980, 0L),
        StationEntity("v2-467917362", "شعبي إف إم SHA3BY FM", "https://radio95.radioca.st/stream", "", "MUSIC", "EG", 128, false, 979, 0L),
        StationEntity("v2-973194640", "أون سبورت إف إم On Sport FM", "https://carina.streamerr.co:2020/stream/OnSportFM", "", "SPORTS", "EG", 128, false, 978, 0L),
        StationEntity("v2-217926903", "El Gouna Radio الجونة", "http://82.201.132.237:8000/;", "", "MUSIC", "EG", 128, false, 977, 0L),
        StationEntity("v2-396224450", "عرب ميكس Arab Mix FM", "https://stream.zeno.fm/na3vpvn10qruv", "", "MUSIC", "EG", 128, false, 976, 0L),
        StationEntity("v2-721506438", "أم كلثوم OM Kalthoum Radio", "https://stream.zeno.fm/zsgrfxg71s8uv", "", "MUSIC", "EG", 128, false, 975, 0L),
        StationEntity("v2-372831013", "عبدالحليم حافظ Abdel Halim Hafez", "https://stream.zeno.fm/8a4eqkd0pnhvv", "", "MUSIC", "EG", 128, false, 974, 0L),
        StationEntity("v2-674600948", "دياب إف إم Diab FM", "https://stream-34.zeno.fm/rf64mx02qa0uv?zs=-xjlLLwRS8uKrffFxK4vLA", "", "MUSIC", "EG", 128, false, 973, 0L),
        StationEntity("v2-824212928", "90s FM مصر", "http://eu1.fastcast4u.com/proxy/prontofm", "", "MUSIC", "EG", 128, false, 972, 0L),
        StationEntity("v2-908911267", "MBC FM", "https://radio-mbcfm.mbc.net/radio-mbcfm_1.m3u8", "", "MUSIC", "SA", 128, false, 971, 0L),
        StationEntity("v2-878199610", "روتانا إف إم الرياض Rotana FM", "http://philae.shoutca.st:8250/stream", "", "MUSIC", "SA", 128, false, 970, 0L),
        StationEntity("v2-601487148", "هلا إف إم عُمان Hala FM", "https://listen-halafm.sharp-stream.com/halafmlow.mp3", "", "MUSIC", "OM", 128, false, 969, 0L),
        StationEntity("v2-421562332", "صوت المدى Sawt El Mada لبنان", "http://audiostreaming.itworkscdn.com:9018/stream", "", "MUSIC", "LB", 128, false, 968, 0L),
        StationEntity("v2-928152654", "راديو الشرق Radio Orient لبنان", "https://stream.rcs.revma.com/7hnrkawf4p8uv.mp3", "", "MUSIC", "LB", 128, false, 967, 0L),
        StationEntity("v2-754422481", "لوبي راديو LBI Radio لبنان", "http://andromeda.shoutca.st:8192/;stream.mp3", "", "MUSIC", "LB", 128, false, 966, 0L),
        StationEntity("v2-758887718", "صوت الغد Sawt El Ghad لبنان", "https://l3.itworkscdn.net/itwaudio/9030/stream", "", "MUSIC", "LB", 128, false, 965, 0L),
        StationEntity("v2-321652460", "صوت الهدى 92.1 Sawt Al Hoda لبنان", "https://l3.itworkscdn.net/itwaudio/9032/stream", "", "MUSIC", "LB", 128, false, 964, 0L),
        StationEntity("v2-728990969", "صوت الحب Sawt Al Hob لبنان", "https://stream.zeno.fm/0b97ef7rxchvv", "", "MUSIC", "LB", 128, false, 963, 0L),
        StationEntity("v2-91755662", "فيروز Fairuz Radio لبنان", "https://stream.zeno.fm/xkhnk4vee18uv", "", "MUSIC", "LB", 128, false, 962, 0L),
        StationEntity("v2-645422222", "صوت الفرح Sawt Al Farah سوريا", "https://radio.garden/api/ara/content/listen/TBbZ87vu/channel.mp3", "", "MUSIC", "SY", 128, false, 961, 0L),
        StationEntity("v2-590371317", "سمر إف إم العراق Sumer FM", "https://l3.itworkscdn.net/itwaudio/9012/stream", "", "MUSIC", "IQ", 128, false, 960, 0L),
        StationEntity("v2-750328825", "الرشيد إف إم بغداد Al Rasheed FM", "https://streaming.shoutcast.com/alrasheed-fm?1719771692024", "", "MUSIC", "IQ", 128, false, 959, 0L),
        StationEntity("v2-524997264", "أجيال إف إم فلسطين Ajyal", "https://streamer.mada.ps:8208/ajyal", "", "MUSIC", "PS", 128, false, 958, 0L),
        StationEntity("v2-410631709", "صوت الغد طرب Sawt El Ghad Tarab فلسطين", "https://sawtelghad.org:7007/;", "", "MUSIC", "PS", 128, false, 957, 0L),
        StationEntity("v2-963769008", "موزاييك إف إم تونس Mosaique FM", "http://radio.mosaiquefm.net:8000/mosalive", "", "MUSIC", "TN", 128, false, 956, 0L),
        StationEntity("v2-201077772", "موزاييك طرب Mosaique FM Tarab", "https://radio.mosaiquefm.net/mosatarab", "", "MUSIC", "TN", 128, false, 955, 0L),
        StationEntity("v2-553925789", "جوهرة إف إم تونس Jawhara FM", "http://streaming2.toutech.net:8000/jawharafm", "", "MUSIC", "TN", 128, false, 954, 0L),
        StationEntity("v2-253313062", "إذاعة أصوات Aswat FM المغرب", "http://broadcast.ice.infomaniak.ch/aswat-high.mp3", "", "MUSIC", "MA", 128, false, 953, 0L),
        StationEntity("v2-859269927", "ميدية إف إم Medina FM المغرب", "https://medinafm.ice.infomaniak.ch/medinafm-128.mp3", "", "MUSIC", "MA", 128, false, 952, 0L),
        StationEntity("v2-422507426", "ميدية طرب Medina FM Tarab المغرب", "https://medinatarab.ice.infomaniak.ch/medinatarab-128.mp3", "", "MUSIC", "MA", 128, false, 951, 0L),
        StationEntity("v2-130712976", "بوير إف إم الجزائر Beur FM", "https://beurfm.ice.infomaniak.ch/beurfm-high.mp3", "", "MUSIC", "DZ", 128, false, 950, 0L),
        StationEntity("v2-864570639", "عمّان إف إم 92 Mood FM الأردن", "https://securestreams2.autopo.st:1241/live", "", "MUSIC", "JO", 128, false, 949, 0L),
    )
}
