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
      cached = await env.STATIONS_KV.get("stations_v23", "json");
    } catch (e) {}

    const now = Date.now();
    const twoHours = 2 * 60 * 60 * 1000;

    if (cached && cached.lastUpdated && (now - cached.lastUpdated) < twoHours && cached.stations.length > 30) {
      return new Response(JSON.stringify({
        version: "23.0.0",
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
        await env.STATIONS_KV.put("stations_v23", JSON.stringify({
          lastUpdated: now,
          stations: stations
        }), { expirationTtl: 86400 });
      } catch (e) {}
    }

    return new Response(JSON.stringify({
      version: "23.0.0",
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
    { uuid: "v2-824212928", name: "90s FM مصر", primaryUrl: "http://eu1.fastcast4u.com/proxy/prontofm", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 972, tags: "", language: "arabic", known: true },
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