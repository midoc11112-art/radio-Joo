export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, {
        headers: {
          "Access-Control-Allow-Origin": "*",
          "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
          "Access-Control-Allow-Headers": "*",
          "Access-Control-Max-Age": "86400",
        },
      });
    }

    const url = new URL(request.url);
    const path = url.pathname;

    if (path === "/stations") {
      return await getStations(env);
    }

    const streamUrl = url.searchParams.get("url");
    if (!streamUrl) return new Response("Missing url parameter", { status: 400 });

    return await proxyStream(streamUrl);
  }
};

async function getStations(env) {
  try {
    let cached = null;
    try {
      cached = await env.STATIONS_KV.get("stations_v24", "json");
    } catch (e) {}

    const now = Date.now();
    const twoHours = 2 * 60 * 60 * 1000;

    if (cached && cached.lastUpdated && (now - cached.lastUpdated) < twoHours && cached.stations.length > 30) {
      return new Response(JSON.stringify({
        version: "24.0.0",
        lastUpdated: new Date(cached.lastUpdated).toISOString(),
        totalStations: cached.stations.length,
        stations: cached.stations
      }), {
        headers: {
          "Content-Type": "application/json",
          "Access-Control-Allow-Origin": "*",
          "Cache-Control": "public, max-age=1800",
        },
      });
    }

    const stations = await buildFinalList();

    if (stations.length > 0) {
      try {
        await env.STATIONS_KV.put("stations_v24", JSON.stringify({
          lastUpdated: now,
          stations: stations
        }), { expirationTtl: 86400 });
      } catch (e) {}
    }

    return new Response(JSON.stringify({
      version: "24.0.0",
      lastUpdated: new Date(now).toISOString(),
      totalStations: stations.length,
      stations: stations
    }), {
      headers: {
        "Content-Type": "application/json",
        "Access-Control-Allow-Origin": "*",
        "Cache-Control": "public, max-age=1800",
      },
    });
  } catch (e) {
    return new Response(JSON.stringify({ error: e.message, stations: [] }), { status: 500 });
  }
}


// بوابة الجودة: نرفض محطات Radio Browser المجهولة قليلة الشعبية (محطات الحرفوش).
// مقبولة لو: شعبية >= 100، أو اسمها يطابق محطة معروفة/موثوقة.
function passesQualityGate(st) {
  if (st.known) return true;
  if ((st.popularity || 0) >= 100) return true;
  const lower = (st.name || "").toLowerCase();
  const keywords = ["jazeera","الجزيرة","sky news","سكاي","arabiya","العربية","bbc","mbc",
    "monte carlo","مونت كارلو","quran","قرآن","nagham","نغم","nogoum","نجوم",
    "9090","mix fm","hits","sha3bi","rotana","روتانا","fairuz","فيروز",
    "um kulthum","om kalthoum","أم كلثوم","amr diab","diab","nrj",
    "medina","aswat","أصوات","orient","ajyal","sumer","hala",
    "mona","lbc","sawt el ghad","نساء","إذاعة","راديو","radio"];
  return keywords.some(k => lower.includes(k));
}

async function buildFinalList() {
  const allStations = [];

  // ✅ المرحلة 1: محطات معروفة URLsها ثابتة وشغالة 100%
  const knownStations = getKnownWorkingStations();
  allStations.push(...knownStations);

  // ✅ المرحلة 2: نبحث عن محطات مشهورة بالاسم (بالإنجليزي)
  const searchedStations = await searchByName();
  allStations.push(...searchedStations);

  // ✅ المرحلة 3: محطات من الدول العربية (lastcheckok=1)
  const countryStations = await fetchFromCountries();
  allStations.push(...countryStations);

  // ✅ بوابة الجودة: تسقط المحطات المجهولة ضعيفة الشعبية قبل الدمج
  const gated = allStations.filter(passesQualityGate);

  // ✅ إزالة التكرار (نحتفظ بالأولوية للمحطات المعروفة)
  const unique = removeDuplicatesSmart(gated);

  // ✅ ترتيب: المعروفين الأول، بعدين حسب الشعبية
  unique.sort((a, b) => {
    if (a.known && !b.known) return -1;
    if (!a.known && b.known) return 1;
    return b.popularity - a.popularity;
  });

  // ✅ نشيل خاصية known قبل ما نرجع
  return unique.map(({ known, ...rest }) => rest);
}

// محطات معروفة URLsها ثابتة ومتأكد منها صوتياً (مولدة من TrustedStations.kt - فحص 2026-11-05)
function getKnownWorkingStations() {
  // قائمة متأكد منها يدوياً بتاريخ 2026-11-05 — مطابقة تماماً لـ TrustedStations.kt في التطبيق
  return [
    { uuid: "v2-488939558", name: "صوت الجزيرة Al Jazeera Voice", primaryUrl: "https://live-hls-audio-web-aja.getaj.net/VOICE-AJA/01.m3u8", fallbackUrl: "", category: "NEWS", countryCode: "QA", bitrate: 128, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "v2-218986724", name: "سكاي نيوز عربية Sky News Arabia", primaryUrl: "https://stream.skynewsarabia.com/hls/sna.m3u8", fallbackUrl: "", category: "NEWS", countryCode: "AE", bitrate: 128, popularity: 998, tags: "", language: "arabic", known: true },
    { uuid: "v2-276430621", name: "مونت كارلو الدولية Monte Carlo Doualiya", primaryUrl: "https://montecarlodoualiya128k.ice.infomaniak.ch/mc-doualiya.mp3", fallbackUrl: "", category: "TALK", countryCode: "FR", bitrate: 128, popularity: 997, tags: "", language: "arabic", known: true },
    { uuid: "v2-274997283", name: "راديو وياك Radiowayak مصر", primaryUrl: "https://work.radiowayak.org/listen/live/live.mp3", fallbackUrl: "", category: "TALK", countryCode: "EG", bitrate: 128, popularity: 996, tags: "", language: "arabic", known: true },
    { uuid: "v2-771409511", name: "ألف ألف إف إم AlifAlif FM السعودية", primaryUrl: "https://alifalifjobs.com/radio/8000/AlifAlifLive.mp3", fallbackUrl: "", category: "TALK", countryCode: "SA", bitrate: 128, popularity: 995, tags: "", language: "arabic", known: true },
    { uuid: "v2-923322247", name: "صوت الأمل Sawt Alamal المغرب", primaryUrl: "https://stream.zeno.fm/qgw73bqh5qruv", fallbackUrl: "", category: "TALK", countryCode: "MA", bitrate: 128, popularity: 994, tags: "", language: "arabic", known: true },
    { uuid: "v2-481539132", name: "راديو تونس الثقافية", primaryUrl: "http://rtstream.tanitweb.com/culturelle", fallbackUrl: "", category: "TALK", countryCode: "TN", bitrate: 128, popularity: 993, tags: "", language: "arabic", known: true },
    { uuid: "v2-898872686", name: "إذاعة القرآن الكريم من القاهرة", primaryUrl: "https://n0d.radiojar.com/8s5u5tpdtwzuv.mp3?rj-ttl=5&rj-tok=AAABhwLBHbYAfz9OyE3ZQvJh-w", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 992, tags: "", language: "arabic", known: true },
    { uuid: "v2-662079550", name: "القرآن الكريم إذاعة الحرمين Saudi Quran", primaryUrl: "http://n01.radiojar.com/0tpy1h0kxtzuv?rj-ttl=5&rj-tok=AAABjrUiYUAAxwNU2wDdcOap9w", fallbackUrl: "", category: "QURAN", countryCode: "SA", bitrate: 128, popularity: 991, tags: "", language: "arabic", known: true },
    { uuid: "v2-422907639", name: "الشيخ عبدالباسط عبدالصمد", primaryUrl: "https://radio.mp3islam.com/listen/abdulbasit/radio.mp3", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 990, tags: "", language: "arabic", known: true },
    { uuid: "v2-379608058", name: "الشيخ محمد صديق المنشاوي", primaryUrl: "https://radio.mp3islam.com/listen/minshawi/radio.mp3", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 989, tags: "", language: "arabic", known: true },
    { uuid: "v2-479808795", name: "الشيخ عبدالرحمن السديس", primaryUrl: "https://radio.mp3islam.com/listen/sudais/radio.mp3", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 988, tags: "", language: "arabic", known: true },
    { uuid: "v2-706457037", name: "الشيخ محمود خليل الحصري", primaryUrl: "https://serverkw.quran-uni.com:8018/;", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 987, tags: "", language: "arabic", known: true },
    { uuid: "v2-787541356", name: "الشيخ مصطفى إسماعيل", primaryUrl: "https://serverkw.quran-uni.com:8224/;", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 986, tags: "", language: "arabic", known: true },
    { uuid: "v2-481941196", name: "تراتيل قصيرة متميزة Mp3Quran", primaryUrl: "https://qurango.net/radio/tarateel", fallbackUrl: "", category: "QURAN", countryCode: "SA", bitrate: 128, popularity: 985, tags: "", language: "arabic", known: true },
    { uuid: "v2-789228241", name: "السراج القرآني العراق", primaryUrl: "http://192.99.170.8:5550/stream", fallbackUrl: "", category: "QURAN", countryCode: "IQ", bitrate: 128, popularity: 984, tags: "", language: "arabic", known: true },
    { uuid: "v2-721801364", name: "راديو القرآن شارجة Sharjah Quran", primaryUrl: "https://l3.itworkscdn.net/smcquranlive/quranradiolive/icecast.audio", fallbackUrl: "", category: "QURAN", countryCode: "AE", bitrate: 128, popularity: 983, tags: "", language: "arabic", known: true },
    { uuid: "v2-286354526", name: "راديو 9090", primaryUrl: "http://9090streaming.mobtada.com/9090FMEGYPT", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 982, tags: "", language: "arabic", known: true },
    { uuid: "v2-376828372", name: "ميكس إف إم 87.8 Mix FM", primaryUrl: "https://eu1.fastcast4u.com/proxy/prontofm?mp=/1", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 981, tags: "", language: "arabic", known: true },
    { uuid: "v2-776806704", name: "راديو هيتس 88.2 Hits FM", primaryUrl: "https://radiohits882.radioca.st/stream", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 980, tags: "", language: "arabic", known: true },
    { uuid: "v2-467917362", name: "شعبي إف إم SHA3BY FM", primaryUrl: "https://radio95.radioca.st/stream", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 979, tags: "", language: "arabic", known: true },
    { uuid: "v2-973194640", name: "أون سبورت إف إم On Sport FM", primaryUrl: "https://carina.streamerr.co:2020/stream/OnSportFM", fallbackUrl: "", category: "SPORTS", countryCode: "EG", bitrate: 128, popularity: 978, tags: "", language: "arabic", known: true },
    { uuid: "v2-217926903", name: "El Gouna Radio الجونة", primaryUrl: "http://82.201.132.237:8000/;", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 977, tags: "", language: "arabic", known: true },
    { uuid: "v2-396224450", name: "عرب ميكس Arab Mix FM", primaryUrl: "https://stream.zeno.fm/na3vpvn10qruv", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 976, tags: "", language: "arabic", known: true },
    { uuid: "v2-721506438", name: "أم كلثوم OM Kalthoum Radio", primaryUrl: "https://stream.zeno.fm/zsgrfxg71s8uv", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 975, tags: "", language: "arabic", known: true },
    { uuid: "v2-372831013", name: "عبدالحليم حافظ Abdel Halim Hafez", primaryUrl: "https://stream.zeno.fm/8a4eqkd0pnhvv", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 974, tags: "", language: "arabic", known: true },
    { uuid: "v2-674600948", name: "دياب إف إم Diab FM", primaryUrl: "https://stream-34.zeno.fm/rf64mx02qa0uv?zs=-xjlLLwRS8uKrffFxK4vLA", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 973, tags: "", language: "arabic", known: true },
    { uuid: "v2-908911267", name: "MBC FM", primaryUrl: "https://radio-mbcfm.mbc.net/radio-mbcfm_1.m3u8", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 971, tags: "", language: "arabic", known: true },
    { uuid: "v2-878199610", name: "روتانا إف إم الرياض Rotana FM", primaryUrl: "http://philae.shoutca.st:8250/stream", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 970, tags: "", language: "arabic", known: true },
    { uuid: "v2-601487148", name: "هلا إف إم عُمان Hala FM", primaryUrl: "https://listen-halafm.sharp-stream.com/halafmlow.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "OM", bitrate: 128, popularity: 969, tags: "", language: "arabic", known: true },
    { uuid: "v2-421562332", name: "صوت المدى Sawt El Mada لبنان", primaryUrl: "http://audiostreaming.itworkscdn.com:9018/stream", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 968, tags: "", language: "arabic", known: true },
    { uuid: "v2-928152654", name: "راديو الشرق Radio Orient لبنان", primaryUrl: "https://stream.rcs.revma.com/7hnrkawf4p8uv.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 967, tags: "", language: "arabic", known: true },
    { uuid: "v2-754422481", name: "لوبي راديو LBI Radio لبنان", primaryUrl: "http://andromeda.shoutca.st:8192/;stream.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 966, tags: "", language: "arabic", known: true },
    { uuid: "v2-758887718", name: "صوت الغد Sawt El Ghad لبنان", primaryUrl: "https://l3.itworkscdn.net/itwaudio/9030/stream", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 965, tags: "", language: "arabic", known: true },
    { uuid: "v2-321652460", name: "صوت الهدى 92.1 Sawt Al Hoda لبنان", primaryUrl: "https://l3.itworkscdn.net/itwaudio/9032/stream", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 964, tags: "", language: "arabic", known: true },
    { uuid: "v2-728990969", name: "صوت الحب Sawt Al Hob لبنان", primaryUrl: "https://stream.zeno.fm/0b97ef7rxchvv", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 963, tags: "", language: "arabic", known: true },
    { uuid: "v2-91755662", name: "فيروز Fairuz Radio لبنان", primaryUrl: "https://stream.zeno.fm/xkhnk4vee18uv", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 962, tags: "", language: "arabic", known: true },
    { uuid: "v2-645422222", name: "صوت الفرح Sawt Al Farah سوريا", primaryUrl: "https://radio.garden/api/ara/content/listen/TBbZ87vu/channel.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "SY", bitrate: 128, popularity: 961, tags: "", language: "arabic", known: true },
    { uuid: "v2-590371317", name: "سمر إف إم العراق Sumer FM", primaryUrl: "https://l3.itworkscdn.net/itwaudio/9012/stream", fallbackUrl: "", category: "MUSIC", countryCode: "IQ", bitrate: 128, popularity: 960, tags: "", language: "arabic", known: true },
    { uuid: "v2-750328825", name: "الرشيد إف إم بغداد Al Rasheed FM", primaryUrl: "https://streaming.shoutcast.com/alrasheed-fm?1719771692024", fallbackUrl: "", category: "MUSIC", countryCode: "IQ", bitrate: 128, popularity: 959, tags: "", language: "arabic", known: true },
    { uuid: "v2-524997264", name: "أجيال إف إم فلسطين Ajyal", primaryUrl: "https://streamer.mada.ps:8208/ajyal", fallbackUrl: "", category: "MUSIC", countryCode: "PS", bitrate: 128, popularity: 958, tags: "", language: "arabic", known: true },
    { uuid: "v2-410631709", name: "صوت الغد طرب Sawt El Ghad Tarab فلسطين", primaryUrl: "https://sawtelghad.org:7007/;", fallbackUrl: "", category: "MUSIC", countryCode: "PS", bitrate: 128, popularity: 957, tags: "", language: "arabic", known: true },
    { uuid: "v2-963769008", name: "موزاييك إف إم تونس Mosaique FM", primaryUrl: "http://radio.mosaiquefm.net:8000/mosalive", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 956, tags: "", language: "arabic", known: true },
    { uuid: "v2-201077772", name: "موزاييك طرب Mosaique FM Tarab", primaryUrl: "https://radio.mosaiquefm.net/mosatarab", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 955, tags: "", language: "arabic", known: true },
    { uuid: "v2-553925789", name: "جوهرة إف إم تونس Jawhara FM", primaryUrl: "http://streaming2.toutech.net:8000/jawharafm", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 954, tags: "", language: "arabic", known: true },
    { uuid: "v2-253313062", name: "إذاعة أصوات Aswat FM المغرب", primaryUrl: "http://broadcast.ice.infomaniak.ch/aswat-high.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 953, tags: "", language: "arabic", known: true },
    { uuid: "v2-859269927", name: "ميدية إف إم Medina FM المغرب", primaryUrl: "https://medinafm.ice.infomaniak.ch/medinafm-128.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 952, tags: "", language: "arabic", known: true },
    { uuid: "v2-422507426", name: "ميدية طرب Medina FM Tarab المغرب", primaryUrl: "https://medinatarab.ice.infomaniak.ch/medinatarab-128.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 951, tags: "", language: "arabic", known: true },
    { uuid: "v2-130712976", name: "بوير إف إم الجزائر Beur FM", primaryUrl: "https://beurfm.ice.infomaniak.ch/beurfm-high.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 950, tags: "", language: "arabic", known: true },
    { uuid: "v2-864570639", name: "عمّان إف إم 92 Mood FM الأردن", primaryUrl: "https://securestreams2.autopo.st:1241/live", fallbackUrl: "", category: "MUSIC", countryCode: "JO", bitrate: 128, popularity: 949, tags: "", language: "arabic", known: true },
    { uuid: "x-8e63e8b6-4e7", name: "إذاعة القرآن الكريم من القاهرة", primaryUrl: "http://n12.radiojar.com/8s5u5tpdtwzuv?listening-from-radio-garden=1620219571863&rj-tok=AAABeTyl2zYARSWbLVnrEOlqGw&rj-ttl=5", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 899, tags: "", language: "arabic", known: true },
    { uuid: "x-a2c020ae-97b", name: "اذاعة القرآن الكريم", primaryUrl: "http://stream.radiojar.com/0tpy1h0kxtzuv", fallbackUrl: "", category: "QURAN", countryCode: "DZ", bitrate: 128, popularity: 898, tags: "", language: "arabic", known: true },
    { uuid: "x-3f5d8a52-dea", name: "إذاعة محمود خليل الحصري", primaryUrl: "https://qurango.net/radio/mahmoud_khalil_alhussary_warsh", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 897, tags: "", language: "arabic", known: true },
    { uuid: "x-0155ec12-730", name: "إذاعة مصطفى إسماعيل", primaryUrl: "https://qurango.net/radio/mustafa_ismail", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 896, tags: "", language: "arabic", known: true },
    { uuid: "x-939fa825-05d", name: "القران الكريم من القاهره", primaryUrl: "http://n0c.radiojar.com/8s5u5tpdtwzuv?rj-ttl=5&amp;rj-tok=AAABdI5Qnd0AEmvTTG5DVtU31A&amp;autoplay=1%22%20type=%22audio/mpeg", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 895, tags: "", language: "arabic", known: true },
    { uuid: "x-23754a47-c75", name: "Radio القارئ علي حجاج السويسي", primaryUrl: "http://live.mp3quran.net:9842/", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 894, tags: "", language: "arabic", known: true },
    { uuid: "x-0e39d9f4-8d4", name: "إذاعة القرآن الكريم", primaryUrl: "https://n0a.radiojar.com/0tpy1h0kxtzuv?rj-ttl=5&rj-tok=AAABhdgGORQA-2acfyF3_4WY2g", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 893, tags: "", language: "arabic", known: true },
    { uuid: "x-a111b115-a10", name: "القران الكريم من القاهره1", primaryUrl: "http://n0e.radiojar.com/8s5u5tpdtwzuv?rj=&rj-tok=AAABg5BDP0EA2ElNLJYUJTsVcg&rj-ttl=5", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 892, tags: "", language: "arabic", known: true },
    { uuid: "x-961ffb71-060", name: "Quran Radio Station-Nablus", primaryUrl: "http://www.quran-radio.org:8002/", fallbackUrl: "", category: "QURAN", countryCode: "PS", bitrate: 128, popularity: 891, tags: "", language: "arabic", known: true },
    { uuid: "x-e9744b90-261", name: "Mp3Quran Main", primaryUrl: "https://qurango.net/radio/mix", fallbackUrl: "", category: "QURAN", countryCode: "SA", bitrate: 128, popularity: 890, tags: "", language: "arabic", known: true },
    { uuid: "x-5db41df8-992", name: "إذاعة القرآن الكريم (القاهرة)", primaryUrl: "https://stream.radiojar.com/8s5u5tpdtwzuv", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 889, tags: "", language: "arabic", known: true },
    { uuid: "x-59f4eecd-719", name: "Alseraj Radio Quran", primaryUrl: "http://192.99.170.8:5550/stream", fallbackUrl: "", category: "QURAN", countryCode: "IQ", bitrate: 128, popularity: 888, tags: "", language: "arabic", known: true },
    { uuid: "x-5c04a6d1-244", name: "Quran Radio راديو القرآن - Mishary Alafasy - مشاري العفاسي", primaryUrl: "https://radio.mp3islam.com/listen/mishary/radio.mp3", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 887, tags: "", language: "arabic", known: true },
    { uuid: "x-f2c7caa3-758", name: "Aziz Mustaphi", primaryUrl: "https://d3ctxlq1ktw2nl.cloudfront.net/production/2022-0-3/240607172-44100-2-afb2485b0c30e.mp3", fallbackUrl: "", category: "QURAN", countryCode: "MA", bitrate: 128, popularity: 886, tags: "", language: "arabic", known: true },
    { uuid: "x-10f95a01-106", name: "Quran Radio راديو القرآن - Saud Al-Shuraim سعود الشريم", primaryUrl: "https://radio.mp3islam.com/listen/alshuraim/radio.mp3", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 885, tags: "", language: "arabic", known: true },
    { uuid: "x-5c2e9722-be4", name: "إذاعة القرآن الكريم (مكة المكرمة)", primaryUrl: "https://edge.mixlr.com/channel/rwumx", fallbackUrl: "", category: "QURAN", countryCode: "SA", bitrate: 128, popularity: 884, tags: "", language: "arabic", known: true },
    { uuid: "x-2cc238bc-246", name: "Quran Program Oman   إذاعة-القرآن-الكريم", primaryUrl: "https://partwota.cdn.mgmlcdn.com/quranrdoorg/quranrdo.stream_aac/chunklist.m3u8", fallbackUrl: "", category: "QURAN", countryCode: "OM", bitrate: 128, popularity: 883, tags: "", language: "arabic", known: true },
    { uuid: "x-f25fc24b-c5b", name: "Quran Radio راديو القرآن - Yasser AlDosari - ياسر الدوسري", primaryUrl: "https://radio.mp3islam.com/listen/yaser/radio.mp3", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 882, tags: "", language: "arabic", known: true },
    { uuid: "x-dd1a2ef3-013", name: "Quran Radio راديو القرآن - Ali Alhuthaifi - علي الحذيفي", primaryUrl: "https://radio.mp3islam.com/listen/alhuthaifi/radio.mp3", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 881, tags: "", language: "arabic", known: true },
    { uuid: "x-4ce99c8e-47b", name: "Quran Radio راديو القرآن - Kuwait الكويت", primaryUrl: "https://radio.mp3islam.com/listen/quran_radio/radio.mp3", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 880, tags: "", language: "arabic", known: true },
    { uuid: "x-e4df1ea1-b0c", name: "Quran Radio راديو القرآن - Maher Al Mueaqly - ماهر المعيقلي", primaryUrl: "https://radio.mp3islam.com/listen/maher/radio.mp3", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 879, tags: "", language: "arabic", known: true },
    { uuid: "x-7084ad06-e4b", name: ".Quran radio", primaryUrl: "https://n09.radiojar.com/0tpy1h0kxtzuv?rj-ttl=5&rj-tok=AAABjKoABHgAdULpynjQ7EwB6A", fallbackUrl: "", category: "QURAN", countryCode: "SA", bitrate: 128, popularity: 878, tags: "", language: "arabic", known: true },
    { uuid: "x-f11a1158-1ee", name: "Hayat FM", primaryUrl: "https://dc1.serverse.com/proxy/kjxwtpdt/stream", fallbackUrl: "", category: "QURAN", countryCode: "JO", bitrate: 128, popularity: 877, tags: "", language: "arabic", known: true },
    { uuid: "x-55ecc6c2-4d2", name: "الشيخ فارس عباد", primaryUrl: "https://qurango.net/radio/fares_abbad", fallbackUrl: "", category: "QURAN", countryCode: "TN", bitrate: 128, popularity: 876, tags: "", language: "arabic", known: true },
    { uuid: "x-26e47152-04d", name: "الشيخ صلاح بو خاطر", primaryUrl: "https://qurango.net/radio/slaah_bukhatir", fallbackUrl: "", category: "QURAN", countryCode: "TN", bitrate: 128, popularity: 875, tags: "", language: "arabic", known: true },
    { uuid: "x-06e1ce15-a83", name: "Quran Radio (JO)", primaryUrl: "https://jrtv-live.ercdn.net/jrradio/quranradio.m3u8", fallbackUrl: "", category: "QURAN", countryCode: "JO", bitrate: 128, popularity: 874, tags: "", language: "arabic", known: true },
    { uuid: "x-952840ba-f2d", name: "Bahrain Quran 106.1 FM", primaryUrl: "https://5c7b683162943.streamlock.net/live/ngrp:radio-106-1_all/playlist.m3u8", fallbackUrl: "", category: "QURAN", countryCode: "BH", bitrate: 128, popularity: 873, tags: "", language: "arabic", known: true },
    { uuid: "x-f07420b2-244", name: "Coran القرآن الكريم", primaryUrl: "https://webradio1.tda.dz:8001/Coran_64K.mp3", fallbackUrl: "", category: "QURAN", countryCode: "DZ", bitrate: 128, popularity: 872, tags: "", language: "arabic", known: true },
    { uuid: "x-2c82c815-59a", name: "Almunir Radio – Tripoli", primaryUrl: "https://live.munir.ly/proxy/almunirtip/stream", fallbackUrl: "", category: "QURAN", countryCode: "LY", bitrate: 128, popularity: 871, tags: "", language: "arabic", known: true },
    { uuid: "x-2b1ad85e-2a5", name: "Almunir Radio – Benghazi", primaryUrl: "https://live.munir.ly/proxy/almunir/stream", fallbackUrl: "", category: "QURAN", countryCode: "LY", bitrate: 128, popularity: 870, tags: "", language: "arabic", known: true },
    { uuid: "x-2475eaa5-53b", name: "OmniRadio - Sheikh Mahmoud Ali Al-Banna (الشيخ محمود علي البنا)", primaryUrl: "https://radio.xecod.com/station/albanna", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 869, tags: "", language: "arabic", known: true },
    { uuid: "x-2cb54ac0-37d", name: "محمود خليل الحصري - حفص عن عاصم مرتل - mp3quran", primaryUrl: "https://qurango.net/radio/mahmoud_khalil_alhussary", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 868, tags: "", language: "arabic", known: true },
    { uuid: "x-e62ed1be-c05", name: "OmniRadio - Sheikh Mahmoud Khalil Al-Hussary (الشيخ محمود خليل الحصري)", primaryUrl: "https://radio.xecod.com/station/alhussary", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 867, tags: "", language: "arabic", known: true },
    { uuid: "x-13a37b90-41f", name: "OmniRadio - Holy Quran Radio Cairo (إذاعة القرآن الكريم (القاهرة))", primaryUrl: "https://radio.xecod.com/station/quran-cairo", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 866, tags: "", language: "arabic", known: true },
    { uuid: "x-5f3383bd-deb", name: "OmniRadio - Sheikh Abdulbasit Abdulsamad (الشيخ عبد الباسط عبد الصمد)", primaryUrl: "https://radio.xecod.com/station/abdulbasit", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 865, tags: "", language: "arabic", known: true },
    { uuid: "x-e008e07c-4bc", name: "OmniRadio - Sheikh Ahmad Tamim Al-Maraghi (الشيخ أحمد تميم المراغي)", primaryUrl: "https://radio.xecod.com/station/ahmad-tamim", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 864, tags: "", language: "arabic", known: true },
    { uuid: "x-62e78af1-043", name: "OmniRadio - Sheikh Mishary Rashid Al-Afasy (الشيخ مشاري بن راشد العفاسي)", primaryUrl: "https://radio.xecod.com/station/alafasi", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 863, tags: "", language: "arabic", known: true },
    { uuid: "x-b6085e4c-375", name: "OmniRadio - Sheikh Majid Farouk (الشيخ ماجد فاروق)", primaryUrl: "https://radio.xecod.com/station/majid-farouk", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 862, tags: "", language: "arabic", known: true },
    { uuid: "x-46d54920-eec", name: "Ayat Al Quran Radio", primaryUrl: "https://stream.zeno.fm/1fatuk10fkhvv", fallbackUrl: "", category: "QURAN", countryCode: "LB", bitrate: 128, popularity: 861, tags: "", language: "arabic", known: true },
    { uuid: "x-daa09c4e-3e3", name: "Irtiqaa Way Radio - Quran", primaryUrl: "https://streaming.radio.co/s596b42805/listen", fallbackUrl: "", category: "QURAN", countryCode: "LB", bitrate: 128, popularity: 860, tags: "", language: "arabic", known: true },
    { uuid: "x-2601f821-db6", name: "OmniRadio - Abu Bakr Al-Shatri (أبوبكر الشاطري)", primaryUrl: "https://radio.xecod.com/station/abubakr-shatri", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 859, tags: "", language: "arabic", known: true },
    { uuid: "x-d13ccaa5-dd4", name: "OmniRadio - Salah Bukhatir (صلاح بوخاطر)", primaryUrl: "https://radio.xecod.com/station/salah-bukhatir", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 858, tags: "", language: "arabic", known: true },
    { uuid: "x-eefc06d5-ff7", name: "OmniRadio - Sheikh Ammar Al-Mulla (الشيخ عمار الملا)", primaryUrl: "https://radio.xecod.com/station/ammar-almulla", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 857, tags: "", language: "arabic", known: true },
    { uuid: "x-17a713ff-e24", name: "OmniRadio - Sheikh Bandar Balila (الشيخ بندر بليلة)", primaryUrl: "https://radio.xecod.com/station/bandar-balila", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 856, tags: "", language: "arabic", known: true },
    { uuid: "x-645d4aba-6e8", name: "OmniRadio - Khalid Abu Shadi (خالد أبو شادي)", primaryUrl: "https://radio.xecod.com/station/khaledabushady", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 855, tags: "", language: "arabic", known: true },
    { uuid: "x-fbf6dcee-086", name: "OmniRadio - Sheikh Mohammed Siddiq Al-Minshawi (الشيخ محمد صديق المنشاوي)", primaryUrl: "https://radio.xecod.com/station/alminshawi", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 854, tags: "", language: "arabic", known: true },
    { uuid: "x-e82181dd-b7d", name: "Quran Radio 93.3 FM Lebanon", primaryUrl: "https://audio.osina.cloud:7987/stream", fallbackUrl: "", category: "QURAN", countryCode: "LB", bitrate: 128, popularity: 853, tags: "", language: "arabic", known: true },
    { uuid: "x-aad245cf-6e7", name: "Translation of the Quran in Farsi", primaryUrl: "https://serverkw.quran-uni.com:8144/;", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 852, tags: "", language: "arabic", known: true },
    { uuid: "x-d3fbfdc1-597", name: "Quran Recitation by Sheikh Sa`d Al-Ghamidi", primaryUrl: "https://serverkw.quran-uni.com:8026/;", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 851, tags: "", language: "arabic", known: true },
    { uuid: "x-f6525431-74d", name: "Ruqyah Al-Afasy", primaryUrl: "https://serverkw.quran-uni.com:8028/;", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 850, tags: "", language: "arabic", known: true },
    { uuid: "x-43fd050f-0fd", name: "Irtiqaa Way Radio 87.5 FM", primaryUrl: "https://streams.radio.co/s8c9468a81/listen", fallbackUrl: "", category: "QURAN", countryCode: "LB", bitrate: 128, popularity: 849, tags: "", language: "arabic", known: true },
    { uuid: "x-c627c3c7-b8e", name: "Translation of the Quran in Dutch", primaryUrl: "https://serverkw.quran-uni.com:8382/;", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 848, tags: "", language: "arabic", known: true },
    { uuid: "x-785e2b4f-8a3", name: "Translation of the Quran in Assamese", primaryUrl: "https://serverkw.quran-uni.com:8384/;", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 847, tags: "", language: "arabic", known: true },
    { uuid: "x-aae5e83c-a05", name: "Al-Adab Al-Mufrad", primaryUrl: "https://serverkw.quran-uni.com:8366/;", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 846, tags: "", language: "arabic", known: true },
    { uuid: "x-479e43d7-902", name: "Translation of the Quran in Azerbaijani", primaryUrl: "https://serverkw.quran-uni.com:8386/;", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 845, tags: "", language: "arabic", known: true },
    { uuid: "x-b94e8e4f-278", name: "Translation of the Quran in Punjabi", primaryUrl: "https://serverkw.quran-uni.com:8140/;", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 844, tags: "", language: "arabic", known: true },
    { uuid: "x-26543653-651", name: "Translation of the Quran in Italiano", primaryUrl: "https://serverkw.quran-uni.com:8146/;", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 843, tags: "", language: "arabic", known: true },
    { uuid: "x-48b980fe-aa4", name: "Translation of the Quran in Turkish", primaryUrl: "https://serverkw.quran-uni.com:8258/;", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 842, tags: "", language: "arabic", known: true },
    { uuid: "x-af423759-587", name: "Musnad Ahmad - Syrians to Kufans", primaryUrl: "https://serverkw.quran-uni.com:8350/;", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 841, tags: "", language: "arabic", known: true },
    { uuid: "x-e0386942-841", name: "Al-Mushaf Al-Muallim with Spanish Translation (Latin America)", primaryUrl: "https://serverkw.quran-uni.com:8096/;", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 840, tags: "", language: "arabic", known: true },
    { uuid: "x-f2d01650-b84", name: "Hits 1 Algérie", primaryUrl: "https://radio12.pro-fhi.net/listen/whmnrlow/stream", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 839, tags: "", language: "arabic", known: true },
    { uuid: "x-ea78e3db-26b", name: "Mosaique FM", primaryUrl: "https://radio.mosaiquefm.net/mosalive", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 838, tags: "", language: "arabic", known: true },
    { uuid: "x-501e4f18-fd9", name: "Exclusively Pink Floyd", primaryUrl: "https://streaming.exclusive.radio/er/pinkfloyd/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 837, tags: "", language: "arabic", known: true },
    { uuid: "x-3c7aba81-337", name: "Tes3enat FM", primaryUrl: "http://178.33.135.244:20095/;?DIST=TuneIn&TGT=TuneIn&maxServers=2&partnertok=eyJhbGciOiJIUzI1NiIsImtpZCI6InR1bmVpbiIsInR5cCI6IkpXVCJ9.eyJ0cnVzdGVkX3BhcnRuZXIiOnRydWUsImlhdCI6MTYzNjA1OTE3OCwiaXNzIjoidGlzcnYifQ.Me0snc2PBcQhvlzte9L7zQxa-IHgNinhu3XdNJ6_Xa8", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 836, tags: "", language: "arabic", known: true },
    { uuid: "x-39215908-b5f", name: "Amr Diab Radio", primaryUrl: "https://stream-40.zeno.fm/xa4yhh4k838uv?zs=gojgaFRaRrK1wgGIwdv6xA", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 835, tags: "", language: "arabic", known: true },
    { uuid: "x-49b2c8f9-c7e", name: "109.0 FM Christmas Kids", primaryUrl: "http://stream.dar.fm/97821", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 834, tags: "", language: "arabic", known: true },
    { uuid: "x-b8807476-2ad", name: "bldi", primaryUrl: "http://beldifm.hf.ma/radio/8000/radio.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 833, tags: "", language: "arabic", known: true },
    { uuid: "x-df238336-9ab", name: "صوت الغد استراليا", primaryUrl: "http://s4.voscast.com:7648/;stream.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 832, tags: "", language: "arabic", known: true },
    { uuid: "x-ac0d65b9-a65", name: "Diab FM", primaryUrl: "http://stream-36.zeno.fm/rf64mx02qa0uv?zs=omRb6KEjQ3u0-JsaJKdhQg", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 831, tags: "", language: "arabic", known: true },
    { uuid: "x-b019fb4e-adf", name: "MA:-Hit Radio Maroc", primaryUrl: "https://stream.zeno.fm/une3a02mb", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 830, tags: "", language: "arabic", known: true },
    { uuid: "x-d42cb646-0c1", name: "Libyana Hits FM", primaryUrl: "http://proxima.shoutca.st:8946/stream", fallbackUrl: "", category: "MUSIC", countryCode: "LY", bitrate: 128, popularity: 829, tags: "", language: "arabic", known: true },
    { uuid: "x-e8d4a75f-f34", name: "Fayroz", primaryUrl: "https://stream.zeno.fm/zv3e5wykprhvv.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 828, tags: "", language: "arabic", known: true },
    { uuid: "x-bc1e8eb3-a94", name: "SumerFm Iraq", primaryUrl: "https://l3.itworkscdn.net/itwaudio/9012/stream", fallbackUrl: "", category: "MUSIC", countryCode: "IQ", bitrate: 128, popularity: 827, tags: "", language: "arabic", known: true },
    { uuid: "x-40c2a3dd-33c", name: "قديم FM", primaryUrl: "http://usa6.fastcast4u.com:5109/live", fallbackUrl: "", category: "MUSIC", countryCode: "SY", bitrate: 128, popularity: 826, tags: "", language: "arabic", known: true },
    { uuid: "x-9479da9d-825", name: "Exclusively ABBA", primaryUrl: "https://streaming.exclusive.radio/er/abba/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 825, tags: "", language: "arabic", known: true },
    { uuid: "x-702da7ed-b42", name: "Hala Sabratha", primaryUrl: "http://stream.zeno.fm/1dy2395z3k8uv", fallbackUrl: "", category: "MUSIC", countryCode: "LY", bitrate: 128, popularity: 824, tags: "", language: "arabic", known: true },
    { uuid: "x-92722b77-b25", name: "radio coran", primaryUrl: "http://n0b.radiojar.com/0tpy1h0kxtzuv?", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 823, tags: "", language: "arabic", known: true },
    { uuid: "x-88ac049f-d01", name: "lbi Radio", primaryUrl: "http://andromeda.shoutca.st:8192/;stream.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 822, tags: "", language: "arabic", known: true },
    { uuid: "x-e2d15e7a-1b5", name: "Exclusively Genesis", primaryUrl: "https://streaming.exclusive.radio/er/genesis/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 821, tags: "", language: "arabic", known: true },
    { uuid: "x-747ab08f-557", name: "Exclusively The Beatles", primaryUrl: "http://streaming.exclusive.radio/er/beatles/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 820, tags: "", language: "arabic", known: true },
    { uuid: "x-b1d17829-652", name: "Radio Hits 88.2 Cairo", primaryUrl: "https://radiohits882.radioca.st/;", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 819, tags: "", language: "arabic", known: true },
    { uuid: "x-e240cca5-20a", name: "Exclusively Elvis Presley", primaryUrl: "http://streaming.exclusive.radio/er/elvispresley/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 818, tags: "", language: "arabic", known: true },
    { uuid: "x-3eef853f-b1b", name: "Exclusive Radio – Queen", primaryUrl: "https://streaming.exclusive.radio/er/queen/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 817, tags: "", language: "arabic", known: true },
    { uuid: "x-d3434153-afc", name: ". القارئ محمد أيوب", primaryUrl: "https://qurango.net/radio/mohammed_ayyub", fallbackUrl: "", category: "MUSIC", countryCode: "IQ", bitrate: 128, popularity: 816, tags: "", language: "arabic", known: true },
    { uuid: "x-32b2e232-863", name: "Exclusively Guns N’ Roses", primaryUrl: "https://streaming.exclusive.radio/er/gunsnroses/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 815, tags: "", language: "arabic", known: true },
    { uuid: "x-8a753c11-ee4", name: "القارئ علي الحذيفي رواية قالون", primaryUrl: "https://qurango.net/radio/ali_alhuthaifi_qalon", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 814, tags: "", language: "arabic", known: true },
    { uuid: "x-66c753bc-f48", name: "Skyrock Casablanca", primaryUrl: "https://icecast.skyrock.net/s/casa_aac_64k", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 813, tags: "", language: "arabic", known: true },
    { uuid: "x-9d874da9-550", name: "Yabiladi Chaabi Maroc", primaryUrl: "https://radio.yabiladi.com:8102/;stream.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 812, tags: "", language: "arabic", known: true },
    { uuid: "x-d8fc41a4-e01", name: "Exclusively Led Zeppelin", primaryUrl: "https://streaming.exclusive.radio/er-app/ledzeppelin/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 811, tags: "", language: "arabic", known: true },
    { uuid: "x-125ec344-a2a", name: "Aswat FM", primaryUrl: "https://broadcast.ice.infomaniak.ch/aswat-high.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 810, tags: "", language: "arabic", known: true },
    { uuid: "x-b577d001-7e8", name: "Exclusively Coldplay", primaryUrl: "https://streaming.exclusive.radio/er/coldplay/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 809, tags: "", language: "arabic", known: true },
    { uuid: "x-9637cdcf-060", name: "Beirut Nights", primaryUrl: "https://byblosnights.com:8443/relay", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 808, tags: "", language: "arabic", known: true },
    { uuid: "x-875302d0-b86", name: "Exclusively Foo Fighters", primaryUrl: "https://streaming.exclusive.radio/er/foofighters/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 807, tags: "", language: "arabic", known: true },
    { uuid: "x-f2f74c1a-40e", name: "90s FM", primaryUrl: "https://eu1.fastcast4u.com/proxy/prontofm?mp=/1", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 806, tags: "", language: "arabic", known: true },
    { uuid: "x-367578fd-467", name: ". مختصر السيرة", primaryUrl: "https://qurango.net/radio/almukhtasar_fi_alsiyra", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 805, tags: "", language: "arabic", known: true },
    { uuid: "x-da683ec8-287", name: ".صور من حياة الصحابة", primaryUrl: "https://qurango.net/radio/sahabah", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 804, tags: "", language: "arabic", known: true },
    { uuid: "x-8dad4ff4-2ae", name: "Arab Mix FM", primaryUrl: "https://stream.zeno.fm/na3vpvn10qruv", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 803, tags: "", language: "arabic", known: true },
    { uuid: "x-8c8be036-e20", name: "radio maria iraq", primaryUrl: "http://dreamsiteradiocp4.com:8046/stream", fallbackUrl: "", category: "MUSIC", countryCode: "IQ", bitrate: 128, popularity: 802, tags: "", language: "arabic", known: true },
    { uuid: "x-c740d6d9-648", name: "Exclusively Michael Jackson", primaryUrl: "https://streaming.exclusive.radio/er/michaeljackson/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 801, tags: "", language: "arabic", known: true },
    { uuid: "x-721b8f32-807", name: "Exclusively BTS", primaryUrl: "https://streaming.exclusive.radio/er/bts/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 800, tags: "", language: "arabic", known: true },
    { uuid: "x-0925f9a5-2ce", name: "Virgin Radio Oman", primaryUrl: "http://uk5.internet-radio.com:8115/stream", fallbackUrl: "", category: "MUSIC", countryCode: "OM", bitrate: 128, popularity: 799, tags: "", language: "arabic", known: true },
    { uuid: "x-3f877a2c-8d7", name: "Exclusively Eagles", primaryUrl: "https://streaming.exclusive.radio/er/eagles/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 798, tags: "", language: "arabic", known: true },
    { uuid: "x-ff211247-c74", name: "Beirut Nights Radio (AAC)", primaryUrl: "http://byblosnights.com:6190/", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 797, tags: "", language: "arabic", known: true },
    { uuid: "x-3c7742d4-c00", name: "Exclusively The Cure", primaryUrl: "https://streaming.exclusive.radio/er/thecure/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 796, tags: "", language: "arabic", known: true },
    { uuid: "x-6a11c952-ea0", name: "Rap Lbeldi Maroc - Hip Hop Music from Morocco FR AR", primaryUrl: "https://stream.zeno.fm/ivdufrl7q0quv", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 795, tags: "", language: "arabic", known: true },
    { uuid: "x-d9cad3a7-470", name: "Nidaa FM", primaryUrl: "http://nidaa.fm:8811/stream.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 794, tags: "", language: "arabic", known: true },
    { uuid: "x-a63988bf-dc9", name: "Exclusively Led Zeppelin - Only Hits", primaryUrl: "https://streaming.exclusive.radio/er-app/ledzeppelinhits/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 793, tags: "", language: "arabic", known: true },
    { uuid: "x-f22c37de-912", name: "ygg", primaryUrl: "http://online.svitle.org:6728/kids.128.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 792, tags: "", language: "arabic", known: true },
    { uuid: "x-32f1ac32-3b5", name: "Exclusively Nirvana", primaryUrl: "https://streaming.exclusive.radio/er/nirvana/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 791, tags: "", language: "arabic", known: true },
    { uuid: "x-78fe4c57-8bf", name: "Turkmen FM  تركمان إف إم", primaryUrl: "https://radyo1.radyo-dinle.tc/8284/stream;", fallbackUrl: "", category: "MUSIC", countryCode: "IQ", bitrate: 128, popularity: 790, tags: "", language: "arabic", known: true },
    { uuid: "x-047fcfb5-9e5", name: "Nogoum FM", primaryUrl: "https://stream-159.zeno.fm/qb1zvsykm98uv?zt=eyJhbGciOiJIUzI1NiJ9.eyJzdHJlYW0iOiJxYjF6dnN5a205OHV2IiwiaG9zdCI6InN0cmVhbS0xNTkuemVuby5mbSIsInJ0dGwiOjUsImp0aSI6Ild2WTItbVhJU2Z5VlBEOHc3a2J1X0EiLCJpYXQiOjE3NzA5ODcyOTUsImV4cCI6MTc3MDk4NzM1NX0.ob8jdjoAgAzIcrxMk5b127wgaxpnJHEASSChnE5v5ro", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 789, tags: "", language: "arabic", known: true },
    { uuid: "x-e7e418ef-a7c", name: "seba", primaryUrl: "http://213.6.93.254:8000/sebafm", fallbackUrl: "", category: "MUSIC", countryCode: "PS", bitrate: 128, popularity: 788, tags: "", language: "arabic", known: true },
    { uuid: "x-96475698-060", name: "Light FM 90.5 & 90.7 Beirut", primaryUrl: "http://23623.live.streamtheworld.com/LIGHTFMAAC_SC", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 787, tags: "", language: "arabic", known: true },
    { uuid: "x-17e85b1e-1ea", name: "Arab Mix 256", primaryUrl: "https://stream.zeno.fm/wvqgc9kb1d0uv", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 786, tags: "", language: "arabic", known: true },
    { uuid: "x-9637bb37-060", name: "Byblos Radio", primaryUrl: "http://byblosnights.com:8000/byblosaac", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 785, tags: "", language: "arabic", known: true },
    { uuid: "x-65579ebd-da1", name: "إذاعة السنة", primaryUrl: "http://andromeda.shoutca.st:8189/live", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 784, tags: "", language: "arabic", known: true },
    { uuid: "x-136e67ff-f7a", name: "MRC:-Radio Adwaa Music 3", primaryUrl: "https://stream.zeno.fm/x4451xh0x1zuv", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 783, tags: "", language: "arabic", known: true },
    { uuid: "x-cfc734ad-6e1", name: "في ضلال السيرة النبوية", primaryUrl: "https://qurango.net/radio/fi_zilal_alsiyra", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 782, tags: "", language: "arabic", known: true },
    { uuid: "x-1dc6efa7-139", name: "Classical Radio - Mozart", primaryUrl: "https://drive.uber.radio/uber/crmozart/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 781, tags: "", language: "arabic", known: true },
    { uuid: "x-4be3d384-ec9", name: "Exclusively George Michael", primaryUrl: "https://streaming.exclusive.radio/er/georgemichael/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 780, tags: "", language: "arabic", known: true },
    { uuid: "x-6e8ee846-b67", name: "Exclusively The Doors", primaryUrl: "https://streaming.exclusive.radio/er-app/doors/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 779, tags: "", language: "arabic", known: true },
    { uuid: "x-215dd8ad-56a", name: "فيروز", primaryUrl: "https://stream.zeno.fm/9vrahqqfuuhvv", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 778, tags: "", language: "arabic", known: true },
    { uuid: "x-e0cb2d0e-e6c", name: "MRC:-Hip Hop Soul Radio 	Radio Music", primaryUrl: "https://stream.zeno.fm/k7cdrm450s8uv", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 777, tags: "", language: "arabic", known: true },
    { uuid: "x-5b61ccc0-f36", name: "Mood Fm 92 Amman", primaryUrl: "https://securestreams2.autopo.st:1241/live", fallbackUrl: "", category: "MUSIC", countryCode: "JO", bitrate: 128, popularity: 776, tags: "", language: "arabic", known: true },
    { uuid: "x-d7b6cb10-52e", name: "Exclusively Deep Purple", primaryUrl: "https://streaming.exclusive.radio/er/deeppurple/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 775, tags: "", language: "arabic", known: true },
    { uuid: "x-057ef4a9-56a", name: "JS Bach - Classical Radio", primaryUrl: "https://drive.uber.radio/uber/crjsbach/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 774, tags: "", language: "arabic", known: true },
    { uuid: "x-d6d9ace0-eb0", name: "Radyo  Şirvan Perwer", primaryUrl: "https://stream-156.zeno.fm/x0xhw6c6g2zuv?zs=AfL9IRdsQ_qHIebZBo-9GA", fallbackUrl: "", category: "MUSIC", countryCode: "IQ", bitrate: 128, popularity: 773, tags: "", language: "arabic", known: true },
    { uuid: "x-51660ccb-c74", name: "Byblos Radio (AAC)", primaryUrl: "http://byblosnights.com:8360/", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 772, tags: "", language: "arabic", known: true },
    { uuid: "x-12a6599c-f8b", name: "إذاعة ميراث الأنبياء", primaryUrl: "https://radio.al7eah.net/8028/;", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 771, tags: "", language: "arabic", known: true },
    { uuid: "x-86d9ef87-db8", name: "El Gouna Radio", primaryUrl: "http://82.201.132.237:8000/", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 770, tags: "", language: "arabic", known: true },
    { uuid: "x-fb8603a6-cd9", name: "Exclusively Van Halen", primaryUrl: "https://streaming.exclusive.radio/er/vanhalen/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 769, tags: "", language: "arabic", known: true },
    { uuid: "x-30a739aa-0ef", name: "Arab Mix Drama", primaryUrl: "https://stream.zeno.fm/egynebf171zuv.acc", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 768, tags: "", language: "arabic", known: true },
    { uuid: "x-24dada19-a00", name: "Exclusively Ramones", primaryUrl: "https://streaming.exclusive.radio/er/ramones/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 767, tags: "", language: "arabic", known: true },
    { uuid: "x-41c8a217-ef9", name: "Jordan Radio (Main)", primaryUrl: "https://jrtv-live.ercdn.net/jrradio/jordanradiovideo.m3u8", fallbackUrl: "", category: "MUSIC", countryCode: "JO", bitrate: 128, popularity: 766, tags: "", language: "arabic", known: true },
    { uuid: "x-63e84e5a-741", name: "Exclusively Dire Straits", primaryUrl: "https://2.mystreaming.net/er/direstraits/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 765, tags: "", language: "arabic", known: true },
    { uuid: "x-b107964a-426", name: "Exclusively Sting", primaryUrl: "https://streaming.exclusive.radio/er/sting/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 764, tags: "", language: "arabic", known: true },
    { uuid: "x-404380df-281", name: "Exclusively Creedence Clearwater Revival", primaryUrl: "https://streaming.exclusive.radio/er/creedence/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 763, tags: "", language: "arabic", known: true },
    { uuid: "x-197bb600-d9e", name: "Exclusively Bee Gees", primaryUrl: "https://streaming.exclusive.radio/er/beegees/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 762, tags: "", language: "arabic", known: true },
    { uuid: "x-29f29d98-c1f", name: "Exclusively Taylor Swift", primaryUrl: "https://streaming.exclusive.radio/er/taylorswift/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 761, tags: "", language: "arabic", known: true },
    { uuid: "x-fc985cbf-dc7", name: "Rotana FM", primaryUrl: "http://philae.shoutca.st:8250/stream", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 760, tags: "", language: "arabic", known: true },
    { uuid: "x-a66ea931-c72", name: "أذكار الصباح", primaryUrl: "https://qurango.net/radio/athkar_sabah", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 759, tags: "", language: "arabic", known: true },
    { uuid: "x-7275e882-277", name: "Exclusively Fleetwood Mac", primaryUrl: "https://streaming.exclusive.radio/er/fleetwoodmac/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 758, tags: "", language: "arabic", known: true },
    { uuid: "x-8db26ee7-3de", name: "Radio fouedb Music", primaryUrl: "https://streaming.live365.com/a82574", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 757, tags: "", language: "arabic", known: true },
    { uuid: "x-7b12f985-c6d", name: "Exclusively Santana", primaryUrl: "https://streaming.exclusive.radio/er/santana/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 756, tags: "", language: "arabic", known: true },
    { uuid: "x-2528fcc2-781", name: "Exclusively Green Day", primaryUrl: "https://streaming.exclusive.radio/er/greenday/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 755, tags: "", language: "arabic", known: true },
    { uuid: "x-97219d62-7c1", name: "Positively Meditation -om", primaryUrl: "https://streaming.positivity.radio/pr/posimeditation/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 754, tags: "", language: "arabic", known: true },
    { uuid: "x-173bbec6-f08", name: "Positively Good Morning -om", primaryUrl: "https://streaming.positivity.radio/pr/goodmorning/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 753, tags: "", language: "arabic", known: true },
    { uuid: "x-626dd325-07e", name: "Exclusively Meat Loaf", primaryUrl: "https://streaming.exclusive.radio/er/meatloaf/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "SY", bitrate: 128, popularity: 752, tags: "", language: "arabic", known: true },
    { uuid: "x-67a91981-465", name: "Radio Classica - Strauss", primaryUrl: "https://drive.uber.radio/uber/crstrauss/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 751, tags: "", language: "arabic", known: true },
    { uuid: "x-1719542e-3c7", name: "Exclusively Eric Clapton", primaryUrl: "https://streaming.exclusive.radio/er/ericclapton/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 750, tags: "", language: "arabic", known: true },
    { uuid: "x-edc133eb-073", name: "الجزيرة صوت", primaryUrl: "https://live-hls-audio-web-aja.getaj.net/VOICE-AJA/01.m3u8", fallbackUrl: "", category: "NEWS", countryCode: "QA", bitrate: 128, popularity: 749, tags: "", language: "arabic", known: true },
    { uuid: "x-e2011342-2b0", name: "Aljazeera Arabic", primaryUrl: "https://live-hls-web-aja.getaj.net/AJA/index.m3u8", fallbackUrl: "", category: "NEWS", countryCode: "QA", bitrate: 128, popularity: 748, tags: "", language: "arabic", known: true },
    { uuid: "x-9121aa97-ef1", name: "Sky Arabia News TV", primaryUrl: "http://stream.skynewsarabia.com/hls/sna_720.m3u8", fallbackUrl: "", category: "NEWS", countryCode: "AE", bitrate: 128, popularity: 747, tags: "", language: "arabic", known: true },
    { uuid: "x-7c53ff0d-1fd", name: "صوت المقاومة", primaryUrl: "https://node-23.zeno.fm/b67qheuk54zuv", fallbackUrl: "", category: "NEWS", countryCode: "YE", bitrate: 128, popularity: 746, tags: "", language: "arabic", known: true },
    { uuid: "x-244cf1a8-52d", name: "قناة الجزيرة", primaryUrl: "https://live-hls-web-aja.getaj.net/AJA/06.m3u8", fallbackUrl: "", category: "NEWS", countryCode: "QA", bitrate: 128, popularity: 745, tags: "", language: "arabic", known: true },
    { uuid: "x-6b77cd64-68e", name: "Radio Liban Libre", primaryUrl: "https://edge.mixlr.com/channel/qtqeb", fallbackUrl: "", category: "NEWS", countryCode: "LB", bitrate: 128, popularity: 744, tags: "", language: "arabic", known: true },
    { uuid: "x-43023b6f-569", name: "Al Jazeera - Arabic", primaryUrl: "http://live-hls-audio-web-aja.getaj.net/VOICE-AJA/index.m3u8", fallbackUrl: "", category: "NEWS", countryCode: "QA", bitrate: 128, popularity: 743, tags: "", language: "arabic", known: true },
    { uuid: "x-075e5897-3bb", name: "Al Arabiya FM", primaryUrl: "https://fm.alarabiya.net/fm/myStream/playlist.m3u8", fallbackUrl: "", category: "NEWS", countryCode: "SA", bitrate: 128, popularity: 742, tags: "", language: "arabic", known: true },
    { uuid: "x-a20107ea-1e1", name: "sky news arabia radio", primaryUrl: "https://stream.skynewsarabia.com/hls/sna.m3u8", fallbackUrl: "", category: "NEWS", countryCode: "AE", bitrate: 128, popularity: 741, tags: "", language: "arabic", known: true },
    { uuid: "x-bcfe2aae-33f", name: "راديو الناس", primaryUrl: "https://cdna.streamgates.net/RadioNas/Live-Audio/icecast.audio", fallbackUrl: "", category: "NEWS", countryCode: "PS", bitrate: 128, popularity: 740, tags: "", language: "arabic", known: true },
    { uuid: "x-98688060-6cb", name: "Al araby", primaryUrl: "https://l3.itworkscdn.net/alarabyradiolive/alarabyradio_audio/icecast.audio", fallbackUrl: "", category: "NEWS", countryCode: "QA", bitrate: 128, popularity: 739, tags: "", language: "arabic", known: true },
    { uuid: "x-abf148ba-cb5", name: "Kurdistan 24", primaryUrl: "https://audio-edge-3mayu.fra.h.radiomast.io/04b1ce4c-24f2-4172-b756-065832ef78bf", fallbackUrl: "", category: "NEWS", countryCode: "IQ", bitrate: 128, popularity: 738, tags: "", language: "arabic", known: true },
    { uuid: "x-a6bc8a3b-67b", name: "Al Jazeera - English", primaryUrl: "https://live-hls-audio-web-aje.getaj.net/VOICE-AJE/01.m3u8", fallbackUrl: "", category: "NEWS", countryCode: "QA", bitrate: 128, popularity: 737, tags: "", language: "arabic", known: true },
    { uuid: "x-d706e494-cd2", name: "راديو فن    Fenn Radio", primaryUrl: "https://stream.zeno.fm/76e9f1gsduhvv", fallbackUrl: "", category: "NEWS", countryCode: "TN", bitrate: 128, popularity: 736, tags: "", language: "arabic", known: true },
    { uuid: "x-6787cf45-924", name: "Radio Express FM", primaryUrl: "https://expressfm.ice.infomaniak.ch/expressfm-64.mp3", fallbackUrl: "", category: "NEWS", countryCode: "TN", bitrate: 128, popularity: 735, tags: "", language: "arabic", known: true },
    { uuid: "x-58b9b6e3-f63", name: "Monte Carlo Doualiya", primaryUrl: "https://montecarlodoualiya128k.ice.infomaniak.ch/mc-doualiya.mp3", fallbackUrl: "", category: "NEWS", countryCode: "BH", bitrate: 128, popularity: 734, tags: "", language: "arabic", known: true },
    { uuid: "x-b8ab13a0-938", name: "Radio Duhok FM", primaryUrl: "https://stream-149.zeno.fm/unwq3jdyhmbuv", fallbackUrl: "", category: "NEWS", countryCode: "IQ", bitrate: 128, popularity: 733, tags: "", language: "arabic", known: true },
    { uuid: "x-3da33a8b-06b", name: "HOUB Radio (حُب)", primaryUrl: "http://nap.casthost.net:8028/stream", fallbackUrl: "", category: "NEWS", countryCode: "SA", bitrate: 128, popularity: 732, tags: "", language: "arabic", known: true },
    { uuid: "x-a481fed1-b46", name: "إذاعة الأخبار كل 5 دقائق   REPLAY NEWS - العربية", primaryUrl: "https://replaynewsar.ice.infomaniak.ch/replaynewsar-128.mp3", fallbackUrl: "", category: "NEWS", countryCode: "SA", bitrate: 128, popularity: 731, tags: "", language: "arabic", known: true },
    { uuid: "x-ef2cb53f-3f7", name: "Al Wisal", primaryUrl: "http://uk7.internet-radio.com:8067/;", fallbackUrl: "", category: "NEWS", countryCode: "OM", bitrate: 128, popularity: 730, tags: "", language: "arabic", known: true },
    { uuid: "x-050966be-de7", name: "Ambiance FM", primaryUrl: "https://stream.zeno.fm/0rehsamc9xxtv", fallbackUrl: "", category: "NEWS", countryCode: "TN", bitrate: 128, popularity: 729, tags: "", language: "arabic", known: true },
    { uuid: "x-ebbd977c-e0a", name: "A f R o 505 / By _MX2", primaryUrl: "https://stream.zeno.fm/7wozjkc8x0vvv", fallbackUrl: "", category: "NEWS", countryCode: "SA", bitrate: 128, popularity: 728, tags: "", language: "arabic", known: true },
    { uuid: "x-f2e5b99e-424", name: "NPR News Radio 107.7 FM", primaryUrl: "http://npr-ice.streamguys1.com/live.aac", fallbackUrl: "", category: "NEWS", countryCode: "SA", bitrate: 128, popularity: 727, tags: "", language: "arabic", known: true },
    { uuid: "x-05fb8b90-382", name: "El Bahdja - الجزائر العاصمة", primaryUrl: "https://webradio3.tda.dz:8001/ElBahdja_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 128, popularity: 726, tags: "", language: "arabic", known: true },
    { uuid: "x-7edbf4ef-21a", name: "Exclusively Huey Lewis & The News", primaryUrl: "https://nl4.mystreaming.net/er/hueylewis/icecast.audio", fallbackUrl: "", category: "NEWS", countryCode: "AE", bitrate: 128, popularity: 725, tags: "", language: "arabic", known: true },
    { uuid: "x-6ec50764-723", name: "Al Jazeera Video", primaryUrl: "https://live-hls-apps-aje-fa.getaj.net/AJE/index.m3u8", fallbackUrl: "", category: "NEWS", countryCode: "QA", bitrate: 128, popularity: 724, tags: "", language: "arabic", known: true },
    { uuid: "x-a93d497f-74d", name: "Al Jazeera Arabic", primaryUrl: "https://live-hls-web-aja-gcp.thehlive.com/VOICE-AJA/index.m3u8", fallbackUrl: "", category: "NEWS", countryCode: "QA", bitrate: 128, popularity: 723, tags: "", language: "arabic", known: true },
    { uuid: "x-5a98544a-340", name: "Raya FM", primaryUrl: "https://rstream.hadara.ps/proxy/raya/stream", fallbackUrl: "", category: "NEWS", countryCode: "PS", bitrate: 128, popularity: 722, tags: "", language: "arabic", known: true },
    { uuid: "x-06d8af5e-3f5", name: "Algérie Internationale الجزائر الدولية", primaryUrl: "https://webradio3.tda.dz:8001/Internationale_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 128, popularity: 721, tags: "", language: "arabic", known: true },
    { uuid: "x-dfa44ba9-e0b", name: "speda radio", primaryUrl: "https://radio.host247.net/spedafm", fallbackUrl: "", category: "NEWS", countryCode: "IQ", bitrate: 128, popularity: 720, tags: "", language: "arabic", known: true },
    { uuid: "x-ada3cd11-403", name: "Al Jazeera English (Live)", primaryUrl: "https://live-hls-web-aje-gcp.thehlive.com/AJE/index.m3u8", fallbackUrl: "", category: "NEWS", countryCode: "QA", bitrate: 128, popularity: 719, tags: "", language: "arabic", known: true },
    { uuid: "x-d9bbea87-905", name: "Universal Health Radio (العربية)", primaryUrl: "https://radio.universalhealthradio.org/uhr-ar.mp3", fallbackUrl: "", category: "NEWS", countryCode: "SA", bitrate: 128, popularity: 718, tags: "", language: "arabic", known: true },
    { uuid: "x-22f064ab-be2", name: "Chaine 1 القناة الأولى", primaryUrl: "https://webradio1.tda.dz:8001/Chaine1_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 128, popularity: 717, tags: "", language: "arabic", known: true },
    { uuid: "x-c6c77aaf-e89", name: "Tizi Ouzou  تيزي وزو", primaryUrl: "https://webradio2.tda.dz:8001/TiziOuzou_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 128, popularity: 716, tags: "", language: "arabic", known: true },
    { uuid: "x-2bff5955-3a8", name: "Setif سطيف", primaryUrl: "https://webradio3.tda.dz:8001/Setif_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 128, popularity: 715, tags: "", language: "arabic", known: true },
    { uuid: "x-38ba2533-565", name: "Oran وهران", primaryUrl: "https://webradio2.tda.dz:8001/Oran_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 128, popularity: 714, tags: "", language: "arabic", known: true },
    { uuid: "x-54c5a7ab-293", name: "Dreamstar Radio KSA 94.3 FM", primaryUrl: "https://stream-173.zeno.fm/h0y9yu8hzk8uv?zt=eyJhbGciOiJIUzI1NiJ9.eyJzdHJlYW0iOiJoMHk5eXU4aHprOHV2IiwiaG9zdCI6InN0cmVhbS0xNzMuemVuby5mbSIsInJ0dGwiOjUsImp0aSI6IlZidVlINUhnVDZTMk82N0NndEY5cnciLCJpYXQiOjE3MzAxMDA3NTcsImV4cCI6MTczMDEwMDgxN30.nEG8Ng_RkLh72La19iYQBK9312NlFrKm1V4X7371hkU", fallbackUrl: "", category: "NEWS", countryCode: "SA", bitrate: 128, popularity: 713, tags: "", language: "arabic", known: true },
    { uuid: "x-5f344328-082", name: "Zaman FM زمان", primaryUrl: "https://webradio2.tda.dz:8001/Zamen_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 128, popularity: 712, tags: "", language: "arabic", known: true },
    { uuid: "x-bde2191e-473", name: "Radio Azrou Live", primaryUrl: "https://stream.zeno.fm/nzdw7gqc0s8uv", fallbackUrl: "", category: "NEWS", countryCode: "MA", bitrate: 128, popularity: 711, tags: "", language: "arabic", known: true },
    { uuid: "x-29e75334-77d", name: "Chaine 3 القناة الثالثة", primaryUrl: "https://webradio1.tda.dz:8001/Chaine3_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 128, popularity: 710, tags: "", language: "arabic", known: true },
    { uuid: "x-91ec0c03-f58", name: "Medina FM (128 kbps)", primaryUrl: "https://medinafm.ice.infomaniak.ch/medinafm-128.mp3", fallbackUrl: "", category: "NEWS", countryCode: "MA", bitrate: 128, popularity: 709, tags: "", language: "arabic", known: true },
    { uuid: "x-49545fc3-653", name: "Radio Azawan (192 kbps)", primaryUrl: "https://az-maroc.ice.infomaniak.ch/az-maroc-128.mp3", fallbackUrl: "", category: "NEWS", countryCode: "MA", bitrate: 128, popularity: 708, tags: "", language: "arabic", known: true },
    { uuid: "x-1fa1ad84-e00", name: "Bordj Bou Arreridj  برج بوعريريج", primaryUrl: "https://webradio3.tda.dz:8001/BordjBouArreridj_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 128, popularity: 707, tags: "", language: "arabic", known: true },
    { uuid: "x-d1c087ac-267", name: "Constantine قسنطينة", primaryUrl: "https://webradio3.tda.dz:8001/Constantine_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 128, popularity: 706, tags: "", language: "arabic", known: true },
    { uuid: "x-8efd6d38-d14", name: "Ghardaïa غرداية", primaryUrl: "https://webradio1.tda.dz:8001/Ghardaia_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 128, popularity: 705, tags: "", language: "arabic", known: true },
    { uuid: "x-cc6747c6-64f", name: "Biskra بسكرة", primaryUrl: "https://webradio3.tda.dz:8001/Biskra_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 128, popularity: 704, tags: "", language: "arabic", known: true },
    { uuid: "x-a1887b6d-dee", name: "Culture", primaryUrl: "https://webradio3.tda.dz:8001/Culture_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 128, popularity: 703, tags: "", language: "arabic", known: true },
    { uuid: "x-3a2be197-683", name: "Chaine 2 القناة الثانية", primaryUrl: "https://webradio2.tda.dz:8001/Chaine2_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 128, popularity: 702, tags: "", language: "arabic", known: true },
    { uuid: "x-1b78d198-f77", name: "Boumerdès بومرداس", primaryUrl: "https://webradio2.tda.dz:8001/Boumerdes_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 128, popularity: 701, tags: "", language: "arabic", known: true },
    { uuid: "x-1caeea47-6be", name: "M'sila المسيلة", primaryUrl: "https://webradio3.tda.dz:8001/Msila_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 128, popularity: 700, tags: "", language: "arabic", known: true },
    { uuid: "x-279b39aa-6e8", name: "Tlemcen تلمسان", primaryUrl: "https://webradio1.tda.dz:8001/Tlemcen_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 128, popularity: 699, tags: "", language: "arabic", known: true },
    { uuid: "x-aa7e35fb-8b7", name: "Djelfa الجلفة", primaryUrl: "https://webradio2.tda.dz:8001/Djelfa_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 128, popularity: 698, tags: "", language: "arabic", known: true },
    { uuid: "x-60044cf2-2d5", name: "Tiaret تيارت", primaryUrl: "https://webradio2.tda.dz:8001/Tiaret_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 128, popularity: 697, tags: "", language: "arabic", known: true },
    { uuid: "x-7385669d-fb0", name: "Sky ABC Radio 96.6 FM", primaryUrl: "https://stream-02.surfernetwork.com/iysqd3wuj38uv?zt=eyJhbGciOiJIUzI1NiJ9.eyJzdHJlYW0iOiJpeXNxZDN3dWozOHV2IiwiaG9zdCI6InN0cmVhbS0wMi5zdXJmZXJuZXR3b3JrLmNvbSIsInJ0dGwiOjUsImp0aSI6Im1ieUJHT0lOVC1HSXllSFlGbG5KRXciLCJpYXQiOjE3NzgxNjAwMDMsImV4cCI6MTc3ODE2MDA2M30.xYqjN-KICXfpgsxWdN_Ae3gaNqpRsTdELgTkBOVutmg", fallbackUrl: "", category: "NEWS", countryCode: "SA", bitrate: 128, popularity: 696, tags: "", language: "arabic", known: true },
    { uuid: "x-4caa43a0-ed2", name: "Annaba عنابة", primaryUrl: "https://webradio1.tda.dz:8001/Annaba_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 128, popularity: 695, tags: "", language: "arabic", known: true },
    { uuid: "x-344e6e4e-a7b", name: "RadioSunna", primaryUrl: "http://andromeda.shoutca.st:8189/stream", fallbackUrl: "", category: "TALK", countryCode: "SA", bitrate: 128, popularity: 694, tags: "", language: "arabic", known: true },
    { uuid: "x-2c260d07-110", name: "Radio Manarat", primaryUrl: "https://listen.radioking.com/radio/252934/stream/297385", fallbackUrl: "", category: "TALK", countryCode: "MA", bitrate: 128, popularity: 693, tags: "", language: "arabic", known: true },
    { uuid: "x-e2d75665-c4a", name: "Fred Film Radio(لغة عربية)", primaryUrl: "https://s10.webradio-hosting.com/proxy/fredradioar/stream", fallbackUrl: "", category: "TALK", countryCode: "SA", bitrate: 128, popularity: 692, tags: "", language: "arabic", known: true },
    { uuid: "x-3d91ba0d-c4f", name: "Fun Radio For Kids - Bedtime Stories", primaryUrl: "https://drive.uber.radio/uber/forkidzbedtimestories/icecast.audio", fallbackUrl: "", category: "TALK", countryCode: "AE", bitrate: 128, popularity: 691, tags: "", language: "arabic", known: true },
    { uuid: "x-9793ba8c-91b", name: "HAWA al Sudan", primaryUrl: "https://stream.zeno.fm/mqctx1bfd8zuv", fallbackUrl: "", category: "TALK", countryCode: "SD", bitrate: 128, popularity: 690, tags: "", language: "arabic", known: true },
    { uuid: "x-66b08e8e-3be", name: "Yaqeen FM 103.7", primaryUrl: "http://45.63.116.205:8000/stream4", fallbackUrl: "", category: "TALK", countryCode: "JO", bitrate: 128, popularity: 689, tags: "", language: "arabic", known: true },
    { uuid: "x-55fe42e3-74d", name: "Fusha FM 102.9 - Jordan Academy of Arabic", primaryUrl: "https://eu9.fastcast4u.com/proxy/arabicla?mp=/1", fallbackUrl: "", category: "TALK", countryCode: "JO", bitrate: 128, popularity: 688, tags: "", language: "arabic", known: true },
    { uuid: "x-b54622a7-cec", name: "Rabee Al Qloub", primaryUrl: "http://radio-ssl.com:8006/stream", fallbackUrl: "", category: "TALK", countryCode: "JO", bitrate: 128, popularity: 687, tags: "", language: "arabic", known: true },
    { uuid: "x-cd74834d-950", name: "Sawt El Nagham", primaryUrl: "https://stream.zeno.fm/66sfwug4ag0uv", fallbackUrl: "", category: "TALK", countryCode: "LB", bitrate: 128, popularity: 686, tags: "", language: "arabic", known: true },
    { uuid: "x-e25470de-0be", name: "On sports FM", primaryUrl: "https://carina.streamerr.co:2020/stream/OnSportFM", fallbackUrl: "", category: "SPORTS", countryCode: "EG", bitrate: 128, popularity: 685, tags: "", language: "arabic", known: true },
  ];
}

// ✅ البحث عن محطات مشهورة بالاسم (بالإنجليزي)
async function searchByName() {
  const searches = [
    // محطات إخبارية
    "Al Jazeera", "Al Arabiya", "Sky News Arabia", "Al Mayadeen",
    "France 24 Arabic", "DW Arabic", "RT Arabic", "Radio Sawa",
    "Al Hurra", "Asharq News", "Al Qahera News",
    
    // محطات مصرية
    "Nogoum FM", "Nagham FM", "Radio 9090", "Mega FM",
    "Sha3by FM", "Radio Hits", "Nile FM", "ON Sport FM",
    "Radio Masr", "Sawt El Arab", "Om Kalthoum",
    
    // محطات خليجية
    "MBC FM", "Rotana FM", "U FM", "Quran Radio Saudi",
    "Dubai FM", "Abu Dhabi Radio", "Kuwait FM", "Qatar Radio",
    
    // محطات مغاربية
    "Mosaique FM", "Jawhara FM", "Shems FM", "Medi 1",
    "Radio Mars", "Hit Radio Morocco",
    
    // محطات قرآن
    "Quran Radio", "Holy Quran", "Al Quran Al Kareem",
    
    // محطات عراقية وسورية
    "Iraq Radio", "Damascus Radio", "Baghdad Radio",
  ];

  const promises = searches.map(query => 
    fetch("https://de1.api.radio-browser.info/json/stations/byname/" + encodeURIComponent(query) + "?limit=5&order=clickcount&reverse=true&hidebroken=true", {
      headers: { "User-Agent": "RadioJoo/21.0" },
      cf: { cacheTtl: 7200 }
    })
    .then(r => r.ok ? r.json() : [])
    .catch(() => [])
  );

  const results = await Promise.allSettled(promises);
  const stations = [];

  for (const result of results) {
    if (result.status === "fulfilled" && Array.isArray(result.value)) {
      for (const s of result.value) {
        if ((s.url_resolved || s.url) && s.name) {
          stations.push({
            uuid: s.stationuuid || "",
            name: s.name || "",
            primaryUrl: s.url_resolved || s.url || "",
            countryCode: s.countrycode || "",
            bitrate: s.bitrate || 0,
            popularity: s.clickcount || 0,
            tags: (s.tags || "").toLowerCase(),
            language: (s.language || "").toLowerCase(),
            known: false,
          });
        }
      }
    }
  }

  return stations;
}

// ✅ محطات من الدول العربية (lastcheckok=1)
async function fetchFromCountries() {
  const stations = [];
  const countries = ["EG", "SA", "AE", "KW", "QA", "BH", "OM", "JO", "LB", "MA", "DZ", "TN", "IQ", "SY", "YE", "LY", "SD", "PS"];

  const promises = countries.map(code =>
    fetch("https://de1.api.radio-browser.info/json/stations/bycountrycodeexact/" + code + "?limit=300&order=clickcount&reverse=true&hidebroken=true", {
      headers: { "User-Agent": "RadioJoo/21.0" },
      cf: { cacheTtl: 7200 }
    })
    .then(r => r.ok ? r.json() : [])
    .catch(() => [])
  );

  const results = await Promise.allSettled(promises);

  for (const result of results) {
    if (result.status === "fulfilled" && Array.isArray(result.value)) {
      for (const s of result.value) {
        // ✅ بس المحطات اللي Radio Browser متأكد إنها شغالة
        if ((s.lastcheckok === 1 || s.lastcheckok === true) && (s.url_resolved || s.url) && s.name) {
          stations.push({
            uuid: s.stationuuid || "",
            name: s.name || "",
            primaryUrl: s.url_resolved || s.url || "",
            countryCode: s.countrycode || "",
            bitrate: s.bitrate || 0,
            popularity: s.clickcount || 0,
            tags: (s.tags || "").toLowerCase(),
            language: (s.language || "").toLowerCase(),
            known: false,
          });
        }
      }
    }
  }

  return stations;
}

function categorizeStation(station) {
  const name = (station.name || "").toLowerCase();
  const tags = station.tags || "";
  const country = (station.countryCode || "").toUpperCase();

  if (tags.includes("quran") || tags.includes("قرآن") || name.includes("قرآن") || name.includes("quran")) return "QURAN";
  if (tags.includes("news") || tags.includes("أخبار") || tags.includes("talk") || name.includes("news") || name.includes("أخبار") || name.includes("bbc") || name.includes("sky") || name.includes("الجزيرة") || name.includes("العربية")) return "NEWS";
  if (tags.includes("sport") || tags.includes("رياضة") || name.includes("sport") || name.includes("رياضة")) return "SPORTS";
  if (country === "EG") return "EGYPT";
  return "MUSIC";
}

function removeDuplicatesSmart(stations) {
  const unique = [];
  const seenUrls = new Set();
  const seenNames = new Set();
  const knownSet = new Set();
  
  // ✅ الأول: نضيف المحطات المعروفة (known=true)
  for (const s of stations) {
    if (s.known) {
      const urlKey = s.primaryUrl.toLowerCase().replace(/\/$/, "");
      const nameKey = s.name.toLowerCase().trim();
      knownSet.add(urlKey);
      knownSet.add(nameKey);
      if (!seenUrls.has(urlKey) && !seenNames.has(nameKey)) {
        seenUrls.add(urlKey);
        seenNames.add(nameKey);
        unique.push({
          uuid: s.uuid,
          name: s.name,
          primaryUrl: s.primaryUrl,
          fallbackUrl: s.fallbackUrl || "",
          category: s.category || categorizeStation(s),
          countryCode: s.countryCode || "AR",
          bitrate: s.bitrate || 64,
          popularity: s.popularity || 0,
          known: true,
        });
      }
    }
  }
  
  // ✅ بعدين: نضيف المحطات التانية
  for (const s of stations) {
    if (!s.known) {
      const uk = (s.uuid || "").trim();
      const uKey2 = (s.primaryUrl || "").toLowerCase().replace(/\/$/, "");
      const nKey2 = (s.name || "").toLowerCase().trim();
      if (uk.startsWith("manual-") || knownSet.has(uKey2) || knownSet.has(nKey2)) continue;
      const urlKey = s.primaryUrl.toLowerCase().replace(/\/$/, "");
      const nameKey = s.name.toLowerCase().trim();
      if (!seenUrls.has(urlKey) && !seenNames.has(nameKey)) {
        seenUrls.add(urlKey);
        seenNames.add(nameKey);
        unique.push({
          uuid: s.uuid,
          name: s.name,
          primaryUrl: s.primaryUrl,
          fallbackUrl: s.fallbackUrl || "",
          category: s.category || categorizeStation(s),
          countryCode: s.countryCode || "AR",
          bitrate: s.bitrate || 64,
          popularity: s.popularity || 0,
          known: false,
        });
      }
    }
  }
  
  return unique;
}

// إعادة كتابة playlist الـ HLS بحيث كل المقاطع/التحويلات تمر عبر الوكيل نفسه.
// دى المشكلة اللي كانت بتوقف صوت الجزيرة وسكاي نيوز وMBC FM: التطبيق كان بيحمّل
// قائمة التشغيل من الوكيل بنجاح، لكن مقاطع الصوت نفسها كانت بتتحمل مباشرة من سيرفر المحطة
// (بتختلف عن المسار النسبي في القائمة)، فبتفشل أو تتحجب جغرافياً وتتبوظ بعد ثواني.
// دلوقتي المسارات النسبية بتتحول لروابط كاملة والوكيل بيمرر كل حاجة — فتشتغل 100%.
function isLikelyHls(text, contentType) {
  const ct = (contentType || "").toLowerCase();
  if (ct.includes("mpegurl") || ct.includes("m3u")) return true;
  return text.startsWith("#EXTM3U") || text.slice(0, 200).includes("#EXTINF");
}

function rewriteHlsPlaylist(text, baseUrl, workerBase) {
  const lines = text.split("\n");
  const out = [];
  for (let raw of lines) {
    const line = raw.trim();
    if (!line || line.startsWith("#")) { out.push(raw); continue; }
    let abs;
    try { abs = new URL(line, baseUrl).toString(); } catch (e) { out.push(raw); continue; }
    out.push(workerBase + "/?url=" + encodeURIComponent(abs));
  }
  return out.join("\n");
}

async function proxyStream(streamUrl) {
  const videoExtensions = [".mp4", ".mkv", ".avi", ".mov", ".webm", ".flv"];
  const lower = streamUrl.toLowerCase();
  // استثناء روابط الصوت بصيغة m3u8 (قوائم تشغيل HLS صافية) من حظر الفيديو
  const isAudioHls = lower.includes(".m3u8") && !lower.includes("video");
  if (!isAudioHls && videoExtensions.some(ext => lower.includes(ext))) {
    return new Response("Video blocked", { status: 403 });
  }

  try {
    const originRequest = new Request(streamUrl, {
      method: "GET",
      headers: {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Accept": "audio/*",
        "Icy-MetaData": "0",
      },
      redirect: "follow",
    });

    const response = await fetch(originRequest);
    const contentType = response.headers.get("Content-Type") || "";

    if (contentType.toLowerCase().includes("video/") && !isAudioHls) {
      return new Response("Video blocked", { status: 403 });
    }

    // HLS: نقرأ القائمة ونعيد كتابة الروابط ثم نرجعها كنص كامل
    if (response.ok && (contentType.toLowerCase().includes("mpegurl") || lower.includes(".m3u8"))) {
      try {
        const text = await response.text();
        if (isLikelyHls(text, contentType)) {
          const workerBase = "https://radio-joo-proxy.midoc11112.workers.dev";
          const rewritten = rewriteHlsPlaylist(text, response.url || streamUrl, workerBase);
          return new Response(rewritten, {
            status: 200,
            headers: {
              "Content-Type": "application/vnd.apple.mpegurl",
              "Access-Control-Allow-Origin": "*",
              "Cache-Control": "no-cache",
            },
          });
        }
        // مش HLS فعلي — نرجع النص زي ما هو
        return new Response(text, {
          status: response.status,
          headers: { "Content-Type": contentType || "text/plain", "Access-Control-Allow-Origin": "*" },
        });
      } catch (e) { /* fallback للممر العادي */ }
    }

    const newHeaders = new Headers(response.headers);
    newHeaders.set("Access-Control-Allow-Origin", "*");
    if (contentType.includes("audio") || contentType.includes("mpeg")) {
      newHeaders.set("Cache-Control", "public, max-age=86400");
    }

    return new Response(response.body, {
      status: response.status,
      statusText: response.statusText,
      headers: newHeaders,
    });
  } catch (e) {
    return new Response("Proxy error: " + e.message, { status: 502 });
  }
}