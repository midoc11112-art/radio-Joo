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
        version: "25.0.0",
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
      version: "25.0.0",
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
    { uuid: "edc133eb-0736-4056-a955-2f02fdc8c0e2", name: "صوت الجزيرة Al Jazeera Voice", primaryUrl: "https://live-hls-audio-web-aja.getaj.net/VOICE-AJA/01.m3u8", fallbackUrl: "", category: "NEWS", countryCode: "QA", bitrate: 128, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "b8305209-741d-4cd4-8a6d-fac3a5f43e1b", name: "ميكس إف إم 87.8 Mix FM مصر", primaryUrl: "https://stream-29.zeno.fm/na3vpvn10qruv", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 986, tags: "", language: "arabic", known: true },

    { uuid: "a20107ea-1e10-4f28-adc1-64b50b7b1d74", name: "سكاي نيوز عربية Sky News Arabia", primaryUrl: "https://stream.skynewsarabia.com/hls/sna.m3u8", fallbackUrl: "", category: "NEWS", countryCode: "AE", bitrate: 128, popularity: 998, tags: "", language: "arabic", known: true },

    { uuid: "58b9b6e3-f638-4467-993c-71e6345435f0", name: "مونت كارلو الدولية Monte Carlo Doualiya", primaryUrl: "https://montecarlodoualiya128k.ice.infomaniak.ch/mc-doualiya.mp3", fallbackUrl: "", category: "NEWS", countryCode: "FR", bitrate: 128, popularity: 997, tags: "", language: "arabic", known: true },

    { uuid: "feddd190-e5ff-4a0a-93c2-90068dd04608", name: "راديو وياك Radiowayak مصر", primaryUrl: "https://work.radiowayak.org/listen/live/live.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 996, tags: "", language: "arabic", known: true },

    { uuid: "f483fc26-d6c7-4d16-8b81-03e4e8683643", name: "ألف ألف إف إم AlifAlif FM السعودية", primaryUrl: "https://alifalifjobs.com/radio/8000/AlifAlifLive.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 995, tags: "", language: "arabic", known: true },

    { uuid: "a9d04fb3-fd0e-4dbd-877e-5dd2d5d6e618", name: "صوت الأمل Sawt Alamal المغرب", primaryUrl: "https://stream.zeno.fm/qgw73bqh5qruv", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 994, tags: "", language: "arabic", known: true },

    { uuid: "b330bfd3-c757-4fea-8bee-ef49f97af878", name: "راديو تونس الثقافية", primaryUrl: "http://rtstream.tanitweb.com/culturelle", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 993, tags: "", language: "arabic", known: true },

    { uuid: "8e63e8b6-4e70-4bc0-9f5d-07e8477a4480", name: "إذاعة القرآن الكريم من القاهرة", primaryUrl: "https://n0d.radiojar.com/8s5u5tpdtwzuv.mp3?rj-ttl=5&rj-tok=AAABhwLBHbYAfz9OyE3ZQvJh-w", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 992, tags: "", language: "arabic", known: true },

    { uuid: "807f5b26-6035-4803-812e-3199fc97b7e9", name: "القرآن الكريم إذاعة الحرمين Saudi Quran", primaryUrl: "http://n01.radiojar.com/0tpy1h0kxtzuv?rj-ttl=5&rj-tok=AAABjrUiYUAAxwNU2wDdcOap9w", fallbackUrl: "", category: "QURAN", countryCode: "SA", bitrate: 128, popularity: 991, tags: "", language: "arabic", known: true },

    { uuid: "0d468016-910f-497f-b75e-26aed21086e9", name: "الشيخ عبدالباسط عبدالصمد", primaryUrl: "https://radio.mp3islam.com/listen/abdulbasit/radio.mp3", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 990, tags: "", language: "arabic", known: true },

    { uuid: "0a97035a-bd76-46da-acbb-894f0876da1f", name: "الشيخ محمد صديق المنشاوي", primaryUrl: "https://radio.mp3islam.com/listen/minshawi/radio.mp3", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 989, tags: "", language: "arabic", known: true },

    { uuid: "77c8094d-6822-4221-be7b-1d54ab477d78", name: "الشيخ عبدالرحمن السديس", primaryUrl: "https://radio.mp3islam.com/listen/sudais/radio.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "KW", bitrate: 128, popularity: 988, tags: "", language: "arabic", known: true },

    { uuid: "cd37f590-64d5-43c0-8e9e-0b9b3734e35f", name: "الشيخ محمود خليل الحصري", primaryUrl: "https://serverkw.quran-uni.com:8018/;", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 987, tags: "", language: "arabic", known: true },

    { uuid: "aab8cf39-c021-4248-98fb-7c55152457f3", name: "الشيخ مصطفى إسماعيل", primaryUrl: "https://serverkw.quran-uni.com:8224/;", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 986, tags: "", language: "arabic", known: true },

    { uuid: "a9bee505-526e-4c7c-bf03-9797bbc8e28b", name: "تراتيل قصيرة متميزة Mp3Quran", primaryUrl: "https://qurango.net/radio/tarateel", fallbackUrl: "", category: "QURAN", countryCode: "SA", bitrate: 128, popularity: 985, tags: "", language: "arabic", known: true },

    { uuid: "59f4eecd-7193-4bc3-8bc3-c1463d92b22d", name: "السراج القرآني العراق", primaryUrl: "http://192.99.170.8:5550/stream", fallbackUrl: "", category: "QURAN", countryCode: "IQ", bitrate: 128, popularity: 984, tags: "", language: "arabic", known: true },

    { uuid: "7cca8bd6-a173-4fca-9a74-ca1e15a631e5", name: "راديو القرآن شارجة Sharjah Quran", primaryUrl: "https://l3.itworkscdn.net/smcquranlive/quranradiolive/icecast.audio", fallbackUrl: "", category: "QURAN", countryCode: "AE", bitrate: 128, popularity: 983, tags: "", language: "arabic", known: true },

    { uuid: "961a5858-0601-11e8-ae97-52543be04c81", name: "راديو 9090", primaryUrl: "http://9090streaming.mobtada.com/9090FMEGYPT", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 982, tags: "", language: "arabic", known: true },

    { uuid: "f2f74c1a-40e1-4587-b72e-91f67d6fd3b8", name: "ميكس إف إم 87.8 Mix FM", primaryUrl: "https://eu1.fastcast4u.com/proxy/prontofm?mp=/1", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 981, tags: "", language: "arabic", known: true },

    { uuid: "b1d17829-6522-481b-bb5c-839c3fd44353", name: "راديو هيتس 88.2 Hits FM", primaryUrl: "https://radiohits882.radioca.st/;", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 980, tags: "", language: "arabic", known: true },

    { uuid: "9346c66b-f42a-4a06-865d-e96dc44b1aa3", name: "شعبي إف إم SHA3BY FM", primaryUrl: "https://radio95.radioca.st/;", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 979, tags: "", language: "arabic", known: true },

    { uuid: "e25470de-0bea-43c1-8c84-475c6c500c1a", name: "أون سبورت إف إم On Sport FM", primaryUrl: "https://carina.streamerr.co:2020/stream/OnSportFM", fallbackUrl: "", category: "SPORTS", countryCode: "EG", bitrate: 128, popularity: 978, tags: "", language: "arabic", known: true },

    { uuid: "0f35d49a-3e12-4936-a8e7-a3ae7698ea58", name: "El Gouna Radio الجونة", primaryUrl: "http://82.201.132.237:8000/;", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 977, tags: "", language: "arabic", known: true },

    { uuid: "8dad4ff4-2ae8-4fba-b379-c964f6ae9f4a", name: "عرب ميكس Arab Mix FM", primaryUrl: "https://stream.zeno.fm/na3vpvn10qruv", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 976, tags: "", language: "arabic", known: true },

    { uuid: "41e19a4b-c517-486b-a111-6b3472eee741", name: "أم كلثوم OM Kalthoum Radio", primaryUrl: "https://stream.zeno.fm/zsgrfxg71s8uv", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 975, tags: "", language: "arabic", known: true },

    { uuid: "580ef7ef-3ebe-45fd-8c90-25e365c64bc3", name: "عبدالحليم حافظ Abdel Halim Hafez", primaryUrl: "https://stream.zeno.fm/8a4eqkd0pnhvv", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 974, tags: "", language: "arabic", known: true },

    { uuid: "82496629-2eeb-4ed7-8dc5-e72a8a3b4fa5", name: "دياب إف إم Diab FM", primaryUrl: "https://stream-34.zeno.fm/rf64mx02qa0uv?zs=-xjlLLwRS8uKrffFxK4vLA", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 973, tags: "", language: "arabic", known: true },

    { uuid: "0bd2d571-7fc8-4158-8e8e-0635c9a330ad", name: "MBC FM", primaryUrl: "https://radio-mbcfm.mbc.net/radio-mbcfm_1.m3u8", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 971, tags: "", language: "arabic", known: true },

    { uuid: "fc985cbf-dc77-4f7a-b353-d981c1344c9b", name: "روتانا إف إم الرياض Rotana FM", primaryUrl: "http://philae.shoutca.st:8250/stream", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 970, tags: "", language: "arabic", known: true },

    { uuid: "26eb1c40-f314-4976-8969-a70d1fcce1fc", name: "هلا إف إم عُمان Hala FM", primaryUrl: "https://listen-halafm.sharp-stream.com/halafmlow.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "OM", bitrate: 128, popularity: 969, tags: "", language: "arabic", known: true },

    { uuid: "7d1378e3-8e91-46f9-a98d-6f7426e60a3c", name: "صوت المدى Sawt El Mada لبنان", primaryUrl: "http://audiostreaming.itworkscdn.com:9018/stream", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 968, tags: "", language: "arabic", known: true },

    { uuid: "e3ecb332-be77-4641-9b52-39cb4e79e042", name: "راديو الشرق Radio Orient لبنان", primaryUrl: "https://arabica.streamabc.net/76-radioorient-mp3-256-1050481", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 967, tags: "", language: "arabic", known: true },

    { uuid: "88ac049f-d017-11e9-a861-52543be04c81", name: "لوبي راديو LBI Radio لبنان", primaryUrl: "http://andromeda.shoutca.st:8192/;stream.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 966, tags: "", language: "arabic", known: true },

    { uuid: "cbf8067e-8c54-4d62-81b9-df6589d782b1", name: "صوت الغد Sawt El Ghad لبنان", primaryUrl: "https://l3.itworkscdn.net/itwaudio/9030/stream", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 965, tags: "", language: "arabic", known: true },

    { uuid: "84fa3e78-74ff-4a18-a40d-0edf87e9fa05", name: "صوت الهدى 92.1 Sawt Al Hoda لبنان", primaryUrl: "https://l3.itworkscdn.net/itwaudio/9032/stream", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 964, tags: "", language: "arabic", known: true },

    { uuid: "4e0c8d68-ad04-46e7-884d-0605979eb8d2", name: "صوت الحب Sawt Al Hob لبنان", primaryUrl: "https://stream.zeno.fm/0b97ef7rxchvv", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 963, tags: "", language: "arabic", known: true },

    { uuid: "f0d6b0a0-a6b7-4574-8921-f581357ea998", name: "فيروز Fairuz Radio لبنان", primaryUrl: "https://stream.zeno.fm/xkhnk4vee18uv", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 962, tags: "", language: "arabic", known: true },

    { uuid: "033b6d50-6d02-4cdb-ac9a-73faba3881d9", name: "صوت الفرح Sawt Al Farah سوريا", primaryUrl: "https://radio.garden/api/ara/content/listen/TBbZ87vu/channel.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "SY", bitrate: 128, popularity: 961, tags: "", language: "arabic", known: true },

    { uuid: "bc1e8eb3-a94e-48d7-892a-e790a1599b60", name: "سمر إف إم العراق Sumer FM", primaryUrl: "https://l3.itworkscdn.net/itwaudio/9012/stream", fallbackUrl: "", category: "MUSIC", countryCode: "IQ", bitrate: 128, popularity: 960, tags: "", language: "arabic", known: true },

    { uuid: "0cb24613-f3ba-41d6-9410-b3aaaa49c3f9", name: "الرشيد إف إم بغداد Al Rasheed FM", primaryUrl: "https://streaming.shoutcast.com/alrasheed-fm?1719771692024", fallbackUrl: "", category: "MUSIC", countryCode: "IQ", bitrate: 128, popularity: 959, tags: "", language: "arabic", known: true },

    { uuid: "d4e5cd2a-46da-447a-b92a-ef2abf64c329", name: "أجيال إف إم فلسطين Ajyal", primaryUrl: "https://streamer.mada.ps:8208/ajyal", fallbackUrl: "", category: "MUSIC", countryCode: "PS", bitrate: 128, popularity: 958, tags: "", language: "arabic", known: true },

    { uuid: "f27afa46-8650-4a88-a632-6f46e61427b2", name: "صوت الغد طرب Sawt El Ghad Tarab فلسطين", primaryUrl: "https://sawtelghad.org:7007/;", fallbackUrl: "", category: "MUSIC", countryCode: "PS", bitrate: 128, popularity: 957, tags: "", language: "arabic", known: true },

    { uuid: "9616a843-0601-11e8-ae97-52543be04c81", name: "موزاييك إف إم تونس Mosaique FM", primaryUrl: "http://radio.mosaiquefm.net:8000/mosalive", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 956, tags: "", language: "arabic", known: true },

    { uuid: "064734c4-5ca8-4b17-b1ad-587e1c2896d6", name: "موزاييك طرب Mosaique FM Tarab", primaryUrl: "https://radio.mosaiquefm.net/mosatarab", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 955, tags: "", language: "arabic", known: true },

    { uuid: "961ee9aa-0601-11e8-ae97-52543be04c81", name: "جوهرة إف إم تونس Jawhara FM", primaryUrl: "http://streaming2.toutech.net:8000/jawharafm", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 954, tags: "", language: "arabic", known: true },

    { uuid: "125ec344-a2a1-4e36-8f66-77401f457d20", name: "إذاعة أصوات Aswat FM المغرب", primaryUrl: "https://broadcast.ice.infomaniak.ch/aswat-high.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 953, tags: "", language: "arabic", known: true },

    { uuid: "91ec0c03-f58c-4e42-a034-067a22f867d3", name: "ميدية إف إم Medina FM المغرب", primaryUrl: "https://medinafm.ice.infomaniak.ch/medinafm-128.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 952, tags: "", language: "arabic", known: true },

    { uuid: "9e88f54e-7a06-4225-a01e-9644a48d8499", name: "ميدية طرب Medina FM Tarab المغرب", primaryUrl: "https://medinatarab.ice.infomaniak.ch/medinatarab-128.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 951, tags: "", language: "arabic", known: true },

    { uuid: "8cd4aff2-15af-454f-84b7-a8904548f704", name: "بوير إف إم الجزائر Beur FM", primaryUrl: "https://beurfm.ice.infomaniak.ch/beurfm-high.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 950, tags: "", language: "arabic", known: true },

    { uuid: "5b61ccc0-f369-4368-ba65-3b10b7f6bae9", name: "عمّان إف إم 92 Mood FM الأردن", primaryUrl: "https://securestreams2.autopo.st:1241/live", fallbackUrl: "", category: "MUSIC", countryCode: "JO", bitrate: 128, popularity: 949, tags: "", language: "arabic", known: true },

    
    { uuid: "a2c020ae-97b4-4466-9d23-b594d6412e25", name: "اذاعة القرآن الكريم", primaryUrl: "http://stream.radiojar.com/0tpy1h0kxtzuv", fallbackUrl: "", category: "QURAN", countryCode: "DZ", bitrate: 128, popularity: 898, tags: "", language: "arabic", known: true },

    { uuid: "3f5d8a52-dea9-4201-9575-a58adc8e9263", name: "إذاعة محمود خليل الحصري", primaryUrl: "https://qurango.net/radio/mahmoud_khalil_alhussary_warsh", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 897, tags: "", language: "arabic", known: true },

    { uuid: "0155ec12-7308-468a-88dc-fcbd440e01da", name: "إذاعة مصطفى إسماعيل", primaryUrl: "https://qurango.net/radio/mustafa_ismail", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 896, tags: "", language: "arabic", known: true },

    { uuid: "939fa825-05d8-41c9-96a5-37a83f0737c1", name: "القران الكريم من القاهره", primaryUrl: "http://n0c.radiojar.com/8s5u5tpdtwzuv?rj-ttl=5&amp;rj-tok=AAABdI5Qnd0AEmvTTG5DVtU31A&amp;autoplay=1%22%20type=%22audio/mpeg", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 895, tags: "", language: "arabic", known: true },

    { uuid: "23754a47-c75e-11e8-a54a-52543be04c81", name: "Radio القارئ علي حجاج السويسي", primaryUrl: "http://live.mp3quran.net:9842/", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 894, tags: "", language: "arabic", known: true },

    { uuid: "0e39d9f4-8d44-409e-87c9-be51c7f91f67", name: "إذاعة القرآن الكريم", primaryUrl: "https://n0a.radiojar.com/0tpy1h0kxtzuv?rj-ttl=5&rj-tok=AAABhdgGORQA-2acfyF3_4WY2g", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 893, tags: "", language: "arabic", known: true },

    { uuid: "a111b115-a103-4ed3-9181-c0eb9d4ef61d", name: "القران الكريم من القاهره1", primaryUrl: "http://n0e.radiojar.com/8s5u5tpdtwzuv?rj=&rj-tok=AAABg5BDP0EA2ElNLJYUJTsVcg&rj-ttl=5", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 892, tags: "", language: "arabic", known: true },

    { uuid: "961ffb71-0601-11e8-ae97-52543be04c81", name: "Quran Radio Station-Nablus", primaryUrl: "http://www.quran-radio.org:8002/", fallbackUrl: "", category: "QURAN", countryCode: "PS", bitrate: 128, popularity: 891, tags: "", language: "arabic", known: true },

    { uuid: "6d7bb86d-65bc-4b84-9202-0fc79d2e203c", name: "Mp3Quran Main", primaryUrl: "https://qurango.net/radio/mix", fallbackUrl: "", category: "QURAN", countryCode: "SA", bitrate: 128, popularity: 890, tags: "", language: "arabic", known: true },

    { uuid: "5db41df8-9921-421f-a618-84864ededf86", name: "إذاعة القرآن الكريم (القاهرة)", primaryUrl: "https://stream.radiojar.com/8s5u5tpdtwzuv", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 889, tags: "", language: "arabic", known: true },

    
    { uuid: "e1affe77-8021-424b-ad88-d648919f17fe", name: "Quran Radio راديو القرآن - Mishary Alafasy - مشاري العفاسي", primaryUrl: "https://radio.mp3islam.com/listen/mishary/radio.mp3", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 887, tags: "", language: "arabic", known: true },

    { uuid: "a182e5ba-a840-40bf-9e51-01bef3ad9e24", name: "Aziz Mustaphi", primaryUrl: "https://d3ctxlq1ktw2nl.cloudfront.net/production/2022-0-3/240607172-44100-2-afb2485b0c30e.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 886, tags: "", language: "arabic", known: true },

    { uuid: "10f95a01-1066-471b-89c5-f9092e73a373", name: "Quran Radio راديو القرآن - Saud Al-Shuraim سعود الشريم", primaryUrl: "https://radio.mp3islam.com/listen/alshuraim/radio.mp3", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 885, tags: "", language: "arabic", known: true },

    { uuid: "5c2e9722-be4e-40b5-9ece-3ae2d4c36ffe", name: "إذاعة القرآن الكريم (مكة المكرمة)", primaryUrl: "https://edge.mixlr.com/channel/rwumx", fallbackUrl: "", category: "QURAN", countryCode: "SA", bitrate: 128, popularity: 884, tags: "", language: "arabic", known: true },

    { uuid: "2cc238bc-2466-4e27-a5ad-af7e02991220", name: "Quran Program Oman   إذاعة-القرآن-الكريم", primaryUrl: "https://partwota.cdn.mgmlcdn.com/quranrdoorg/quranrdo.stream_aac/chunklist.m3u8", fallbackUrl: "", category: "QURAN", countryCode: "OM", bitrate: 128, popularity: 883, tags: "", language: "arabic", known: true },

    { uuid: "f25fc24b-c5b9-4800-8d51-3e26bb89a99d", name: "Quran Radio راديو القرآن - Yasser AlDosari - ياسر الدوسري", primaryUrl: "https://radio.mp3islam.com/listen/yaser/radio.mp3", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 882, tags: "", language: "arabic", known: true },

    { uuid: "dd1a2ef3-0136-4ed7-8215-3a7b3564d044", name: "Quran Radio راديو القرآن - Ali Alhuthaifi - علي الحذيفي", primaryUrl: "https://radio.mp3islam.com/listen/alhuthaifi/radio.mp3", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 881, tags: "", language: "arabic", known: true },

    { uuid: "4ce99c8e-47bd-4d4d-896b-e9d5310384d3", name: "Quran Radio راديو القرآن - Kuwait الكويت", primaryUrl: "https://radio.mp3islam.com/listen/quran_radio/radio.mp3", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 880, tags: "", language: "arabic", known: true },

    { uuid: "e4df1ea1-b0c6-43b1-a0fb-fa685a42d72f", name: "Quran Radio راديو القرآن - Maher Al Mueaqly - ماهر المعيقلي", primaryUrl: "https://radio.mp3islam.com/listen/maher/radio.mp3", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 879, tags: "", language: "arabic", known: true },

    { uuid: "0d3dd7bf-4b22-48b4-b8ad-16faf7dd59f7", name: ".Quran radio", primaryUrl: "https://n09.radiojar.com/0tpy1h0kxtzuv?rj-ttl=5&rj-tok=AAABjKoABHgAdULpynjQ7EwB6A", fallbackUrl: "", category: "QURAN", countryCode: "SA", bitrate: 128, popularity: 878, tags: "", language: "arabic", known: true },

    { uuid: "f11a1158-1ee0-4772-bcb0-110522be1e18", name: "Hayat FM", primaryUrl: "https://dc1.serverse.com/proxy/kjxwtpdt/stream", fallbackUrl: "", category: "MUSIC", countryCode: "JO", bitrate: 128, popularity: 877, tags: "", language: "arabic", known: true },

    { uuid: "55ecc6c2-4d2a-4725-ad0d-f4d61599341f", name: "الشيخ فارس عباد", primaryUrl: "https://qurango.net/radio/fares_abbad", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 876, tags: "", language: "arabic", known: true },

    { uuid: "26e47152-04dd-42e1-bd3a-8b83fe88e357", name: "الشيخ صلاح بو خاطر", primaryUrl: "https://qurango.net/radio/slaah_bukhatir", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 875, tags: "", language: "arabic", known: true },

    { uuid: "06e1ce15-a839-4bd6-9d77-6c4c32eb75cc", name: "Quran Radio (JO)", primaryUrl: "https://jrtv-live.ercdn.net/jrradio/quranradio.m3u8", fallbackUrl: "", category: "QURAN", countryCode: "JO", bitrate: 128, popularity: 874, tags: "", language: "arabic", known: true },

    { uuid: "952840ba-f2d8-42ca-9e5f-7e169b880c8c", name: "Bahrain Quran 106.1 FM", primaryUrl: "https://5c7b683162943.streamlock.net/live/ngrp:radio-106-1_all/playlist.m3u8", fallbackUrl: "", category: "QURAN", countryCode: "BH", bitrate: 128, popularity: 873, tags: "", language: "arabic", known: true },

    { uuid: "f07420b2-244c-45fd-aa5a-321f4dbf8df6", name: "Coran القرآن الكريم", primaryUrl: "https://webradio1.tda.dz:8001/Coran_64K.mp3", fallbackUrl: "", category: "QURAN", countryCode: "DZ", bitrate: 128, popularity: 872, tags: "", language: "arabic", known: true },

    { uuid: "2c82c815-59af-4eb3-80b6-43e4ad80bea2", name: "Almunir Radio – Tripoli", primaryUrl: "https://live.munir.ly/proxy/almunirtip/stream", fallbackUrl: "", category: "MUSIC", countryCode: "LY", bitrate: 128, popularity: 871, tags: "", language: "arabic", known: true },

    { uuid: "2b1ad85e-2a5b-4c83-9a98-62e84d188cd8", name: "Almunir Radio – Benghazi", primaryUrl: "https://live.munir.ly/proxy/almunir/stream", fallbackUrl: "", category: "MUSIC", countryCode: "LY", bitrate: 128, popularity: 870, tags: "", language: "arabic", known: true },

    { uuid: "07ac862b-358a-4bd1-bfdb-4a00b62eccab", name: "OmniRadio - Sheikh Mahmoud Ali Al-Banna (الشيخ محمود علي البنا)", primaryUrl: "https://radio.xecod.com/station/albanna", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 869, tags: "", language: "arabic", known: true },

    { uuid: "2cb54ac0-37d7-4742-8259-18e07a6cb2c7", name: "محمود خليل الحصري - حفص عن عاصم مرتل - mp3quran", primaryUrl: "https://qurango.net/radio/mahmoud_khalil_alhussary", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 868, tags: "", language: "arabic", known: true },

    { uuid: "33d261b8-79cc-4a96-ba2f-a84f2884c01a", name: "OmniRadio - Sheikh Mahmoud Khalil Al-Hussary (الشيخ محمود خليل الحصري)", primaryUrl: "https://radio.xecod.com/station/alhussary", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 867, tags: "", language: "arabic", known: true },

    { uuid: "7a9176e8-e8ab-4bcc-9fb8-a7ae352ec1fe", name: "OmniRadio - Holy Quran Radio Cairo (إذاعة القرآن الكريم (القاهرة))", primaryUrl: "https://radio.xecod.com/station/quran-cairo", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 866, tags: "", language: "arabic", known: true },

    { uuid: "2daea367-aeda-429d-9582-4531c1d35eea", name: "OmniRadio - Sheikh Abdulbasit Abdulsamad (الشيخ عبد الباسط عبد الصمد)", primaryUrl: "https://radio.xecod.com/station/abdulbasit", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 865, tags: "", language: "arabic", known: true },

    { uuid: "748b64b1-1bd5-4157-a20b-36db219adf12", name: "OmniRadio - Sheikh Ahmad Tamim Al-Maraghi (الشيخ أحمد تميم المراغي)", primaryUrl: "https://radio.xecod.com/station/ahmad-tamim", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 864, tags: "", language: "arabic", known: true },

    { uuid: "a27fcd99-57ae-4482-ba90-edeec5282b50", name: "OmniRadio - Sheikh Mishary Rashid Al-Afasy (الشيخ مشاري بن راشد العفاسي)", primaryUrl: "https://radio.xecod.com/station/alafasi", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 863, tags: "", language: "arabic", known: true },

    { uuid: "d7a8abc4-9195-4be0-a7d7-6fda72e80260", name: "OmniRadio - Sheikh Majid Farouk (الشيخ ماجد فاروق)", primaryUrl: "https://radio.xecod.com/station/majid-farouk", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 862, tags: "", language: "arabic", known: true },

    { uuid: "46d54920-eece-4b59-b6f9-9ef271c41371", name: "Ayat Al Quran Radio", primaryUrl: "https://stream.zeno.fm/1fatuk10fkhvv", fallbackUrl: "", category: "QURAN", countryCode: "LB", bitrate: 128, popularity: 861, tags: "", language: "arabic", known: true },

    { uuid: "daa09c4e-3e34-45db-87bb-c3b8571f17a1", name: "Irtiqaa Way Radio - Quran", primaryUrl: "https://streaming.radio.co/s596b42805/listen", fallbackUrl: "", category: "QURAN", countryCode: "LB", bitrate: 128, popularity: 860, tags: "", language: "arabic", known: true },

    { uuid: "8536db71-aa3b-49cb-80f6-d2afb3471363", name: "OmniRadio - Abu Bakr Al-Shatri (أبوبكر الشاطري)", primaryUrl: "https://radio.xecod.com/station/abubakr-shatri", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 859, tags: "", language: "arabic", known: true },

    { uuid: "d13ccaa5-dd4e-46c1-8aa2-f0bcd39b92db", name: "OmniRadio - Salah Bukhatir (صلاح بوخاطر)", primaryUrl: "https://radio.xecod.com/station/salah-bukhatir", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 858, tags: "", language: "arabic", known: true },

    { uuid: "2f6d41e9-30fc-4056-923b-ea9fae168d78", name: "OmniRadio - Sheikh Ammar Al-Mulla (الشيخ عمار الملا)", primaryUrl: "https://radio.xecod.com/station/ammar-almulla", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 857, tags: "", language: "arabic", known: true },

    { uuid: "ce82a694-dca3-47a4-bf3c-ff1bafa8a5cf", name: "OmniRadio - Sheikh Bandar Balila (الشيخ بندر بليلة)", primaryUrl: "https://radio.xecod.com/station/bandar-balila", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 856, tags: "", language: "arabic", known: true },

    { uuid: "6191e950-ca3a-4a80-b207-5d591a9bbd2d", name: "OmniRadio - Khalid Abu Shadi (خالد أبو شادي)", primaryUrl: "https://radio.xecod.com/station/khaledabushady", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 855, tags: "", language: "arabic", known: true },

    { uuid: "fbf6dcee-086b-4459-b9e0-8a8fe607a92e", name: "OmniRadio - Sheikh Mohammed Siddiq Al-Minshawi (الشيخ محمد صديق المنشاوي)", primaryUrl: "https://radio.xecod.com/station/alminshawi", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 854, tags: "", language: "arabic", known: true },

    { uuid: "e82181dd-b7dd-4ba1-8c1b-2c7c8c715602", name: "Quran Radio 93.3 FM Lebanon", primaryUrl: "https://audio.osina.cloud:7987/stream", fallbackUrl: "", category: "QURAN", countryCode: "LB", bitrate: 128, popularity: 853, tags: "", language: "arabic", known: true },

    { uuid: "aad245cf-6e7a-4b99-a215-c6f21c6aea4c", name: "Translation of the Quran in Farsi", primaryUrl: "https://serverkw.quran-uni.com:8144/;", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 852, tags: "", language: "arabic", known: true },

    { uuid: "d3fbfdc1-597b-4d72-85a2-f5514ed229ff", name: "Quran Recitation by Sheikh Sa`d Al-Ghamidi", primaryUrl: "https://serverkw.quran-uni.com:8026/;", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 851, tags: "", language: "arabic", known: true },

    { uuid: "f6525431-74d1-4357-a5ec-af5bc4ce4e10", name: "Ruqyah Al-Afasy", primaryUrl: "https://serverkw.quran-uni.com:8028/;", fallbackUrl: "", category: "MUSIC", countryCode: "KW", bitrate: 128, popularity: 850, tags: "", language: "arabic", known: true },

    { uuid: "43fd050f-0fdf-45d0-8e25-f6d230ad5fcb", name: "Irtiqaa Way Radio 87.5 FM", primaryUrl: "https://streams.radio.co/s8c9468a81/listen", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 849, tags: "", language: "arabic", known: true },

    { uuid: "c627c3c7-b8e0-407b-a38e-77491b813abe", name: "Translation of the Quran in Dutch", primaryUrl: "https://serverkw.quran-uni.com:8382/;", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 848, tags: "", language: "arabic", known: true },

    { uuid: "785e2b4f-8a3c-433f-afd6-6b5ff8dd9f3e", name: "Translation of the Quran in Assamese", primaryUrl: "https://serverkw.quran-uni.com:8384/;", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 847, tags: "", language: "arabic", known: true },

    { uuid: "aae5e83c-a051-4595-9321-50996f3b6b45", name: "Al-Adab Al-Mufrad", primaryUrl: "https://serverkw.quran-uni.com:8366/;", fallbackUrl: "", category: "MUSIC", countryCode: "KW", bitrate: 128, popularity: 846, tags: "", language: "arabic", known: true },

    { uuid: "479e43d7-902b-4240-92a2-1bd3a29f4799", name: "Translation of the Quran in Azerbaijani", primaryUrl: "https://serverkw.quran-uni.com:8386/;", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 845, tags: "", language: "arabic", known: true },

    { uuid: "b94e8e4f-2784-4374-a5c4-64193a38da4c", name: "Translation of the Quran in Punjabi", primaryUrl: "https://serverkw.quran-uni.com:8140/;", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 844, tags: "", language: "arabic", known: true },

    { uuid: "26543653-6512-4800-82a1-6e6c2d953f17", name: "Translation of the Quran in Italiano", primaryUrl: "https://serverkw.quran-uni.com:8146/;", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 843, tags: "", language: "arabic", known: true },

    { uuid: "48b980fe-aa44-479b-b51c-4175c2d556ff", name: "Translation of the Quran in Turkish", primaryUrl: "https://serverkw.quran-uni.com:8258/;", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 842, tags: "", language: "arabic", known: true },

    { uuid: "af423759-5871-4b28-a923-c8da5bf7a14a", name: "Musnad Ahmad - Syrians to Kufans", primaryUrl: "https://serverkw.quran-uni.com:8350/;", fallbackUrl: "", category: "MUSIC", countryCode: "KW", bitrate: 128, popularity: 841, tags: "", language: "arabic", known: true },

    { uuid: "e0386942-841f-4446-8ecd-afbd935bcc84", name: "Al-Mushaf Al-Muallim with Spanish Translation (Latin America)", primaryUrl: "https://serverkw.quran-uni.com:8096/;", fallbackUrl: "", category: "MUSIC", countryCode: "KW", bitrate: 128, popularity: 840, tags: "", language: "arabic", known: true },

    { uuid: "f2d01650-b84f-4d85-aa3f-4c085bf211ff", name: "Hits 1 Algérie", primaryUrl: "https://radio12.pro-fhi.net/listen/whmnrlow/stream", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 839, tags: "", language: "arabic", known: true },

    { uuid: "e4113924-a6ce-4a48-8781-97507dda0d67", name: "Mosaique FM", primaryUrl: "https://radio.mosaiquefm.net/mosalive", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 838, tags: "", language: "arabic", known: true },

        { uuid: "3c7aba81-3375-41e3-b268-5231fcf576ba", name: "Tes3enat FM", primaryUrl: "http://178.33.135.244:20095/;?DIST=TuneIn&TGT=TuneIn&maxServers=2&partnertok=eyJhbGciOiJIUzI1NiIsImtpZCI6InR1bmVpbiIsInR5cCI6IkpXVCJ9.eyJ0cnVzdGVkX3BhcnRuZXIiOnRydWUsImlhdCI6MTYzNjA1OTE3OCwiaXNzIjoidGlzcnYifQ.Me0snc2PBcQhvlzte9L7zQxa-IHgNinhu3XdNJ6_Xa8", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 836, tags: "", language: "arabic", known: true },

    { uuid: "39215908-b5f6-40d9-875c-57248dfe314a", name: "Amr Diab Radio", primaryUrl: "https://stream-40.zeno.fm/xa4yhh4k838uv?zs=gojgaFRaRrK1wgGIwdv6xA", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 835, tags: "", language: "arabic", known: true },

    { uuid: "49b2c8f9-c7ed-4df5-afc3-93b8f6028121", name: "109.0 FM Christmas Kids", primaryUrl: "http://stream.dar.fm/97821", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 834, tags: "", language: "arabic", known: true },

    { uuid: "b8807476-2ada-4600-9dc0-7b501691afc1", name: "bldi", primaryUrl: "http://beldifm.hf.ma/radio/8000/radio.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 833, tags: "", language: "arabic", known: true },

    { uuid: "9409faee-d774-450b-945f-38ecc3aec8c1", name: "صوت الغد استراليا", primaryUrl: "http://s4.voscast.com:7648/;stream.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 832, tags: "", language: "arabic", known: true },

    { uuid: "ac0d65b9-a656-4d0e-a6a5-f4b78770a05f", name: "Diab FM", primaryUrl: "http://stream-36.zeno.fm/rf64mx02qa0uv?zs=omRb6KEjQ3u0-JsaJKdhQg", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 831, tags: "", language: "arabic", known: true },

    { uuid: "b019fb4e-adfd-43a9-a00d-68e086e0b06f", name: "MA:-Hit Radio Maroc", primaryUrl: "https://stream.zeno.fm/une3a02mb", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 830, tags: "", language: "arabic", known: true },

    { uuid: "d42cb646-0c16-11e9-a80b-52543be04c81", name: "Libyana Hits FM", primaryUrl: "http://proxima.shoutca.st:8946/stream", fallbackUrl: "", category: "MUSIC", countryCode: "LY", bitrate: 128, popularity: 829, tags: "", language: "arabic", known: true },

    { uuid: "e8d4a75f-f345-4bc7-a837-ce2adc3a2579", name: "Fayroz", primaryUrl: "https://stream.zeno.fm/zv3e5wykprhvv.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 828, tags: "", language: "arabic", known: true },

    
    { uuid: "40c2a3dd-33c6-4196-b45e-8790b4f8c636", name: "قديم FM", primaryUrl: "http://usa6.fastcast4u.com:5109/live", fallbackUrl: "", category: "MUSIC", countryCode: "SY", bitrate: 128, popularity: 826, tags: "", language: "arabic", known: true },

        { uuid: "702da7ed-b420-46ee-a962-6e59348fbe6e", name: "Hala Sabratha", primaryUrl: "http://stream.zeno.fm/1dy2395z3k8uv", fallbackUrl: "", category: "MUSIC", countryCode: "LY", bitrate: 128, popularity: 824, tags: "", language: "arabic", known: true },

    { uuid: "92722b77-b256-4b95-8e24-b264982eb9d3", name: "radio coran", primaryUrl: "http://n0b.radiojar.com/0tpy1h0kxtzuv?", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 823, tags: "", language: "arabic", known: true },

    
            
        { uuid: "3eef853f-b1b8-4ceb-9dab-039de121801d", name: "Exclusive Radio – Queen", primaryUrl: "https://streaming.exclusive.radio/er/queen/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 817, tags: "", language: "arabic", known: true },

    { uuid: "d3434153-afc3-4bdc-a98b-51ddca3990f1", name: ". القارئ محمد أيوب", primaryUrl: "https://qurango.net/radio/mohammed_ayyub", fallbackUrl: "", category: "MUSIC", countryCode: "IQ", bitrate: 128, popularity: 816, tags: "", language: "arabic", known: true },

        { uuid: "8a753c11-ee4a-42bf-8e4d-6d852cac9030", name: "القارئ علي الحذيفي رواية قالون", primaryUrl: "https://qurango.net/radio/ali_alhuthaifi_qalon", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 814, tags: "", language: "arabic", known: true },

    { uuid: "66c753bc-f48c-4ad9-bdea-b272c7125fa8", name: "Skyrock Casablanca", primaryUrl: "https://icecast.skyrock.net/s/casa_aac_64k", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 813, tags: "", language: "arabic", known: true },

    { uuid: "9d874da9-550c-4176-a595-8ee83e0c72bc", name: "Yabiladi Chaabi Maroc", primaryUrl: "https://radio.yabiladi.com:8102/;stream.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 812, tags: "", language: "arabic", known: true },

        
        { uuid: "9637cdcf-0601-11e8-ae97-52543be04c81", name: "Beirut Nights", primaryUrl: "https://byblosnights.com:8443/relay", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 808, tags: "", language: "arabic", known: true },

        
    { uuid: "4aea2140-cdf1-404b-bd3d-471c2f26fd12", name: ". مختصر السيرة", primaryUrl: "https://qurango.net/radio/almukhtasar_fi_alsiyra", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 805, tags: "", language: "arabic", known: true },

    { uuid: "8a2c22b7-5350-4ed2-9781-a4f36eccd230", name: ".صور من حياة الصحابة", primaryUrl: "https://qurango.net/radio/sahabah", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 804, tags: "", language: "arabic", known: true },

    
    { uuid: "e1393f04-f2eb-42d0-961f-e4de13a489b4", name: "radio maria iraq", primaryUrl: "http://dreamsiteradiocp4.com:8046/stream", fallbackUrl: "", category: "MUSIC", countryCode: "IQ", bitrate: 128, popularity: 802, tags: "", language: "arabic", known: true },

            { uuid: "0925f9a5-2cee-4900-b8f0-d6b0b64a90af", name: "Virgin Radio Oman", primaryUrl: "http://uk5.internet-radio.com:8115/stream", fallbackUrl: "", category: "MUSIC", countryCode: "OM", bitrate: 128, popularity: 799, tags: "", language: "arabic", known: true },

        { uuid: "ff211247-c741-11e9-8502-52543be04c81", name: "Beirut Nights Radio (AAC)", primaryUrl: "http://byblosnights.com:6190/", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 797, tags: "", language: "arabic", known: true },

        { uuid: "6a11c952-ea0c-4887-99a3-96b0139ee3fc", name: "Rap Lbeldi Maroc - Hip Hop Music from Morocco FR AR", primaryUrl: "https://stream.zeno.fm/ivdufrl7q0quv", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 795, tags: "", language: "arabic", known: true },

    { uuid: "d9cad3a7-4709-472f-934b-039303e37f79", name: "Nidaa FM", primaryUrl: "http://nidaa.fm:8811/stream.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 794, tags: "", language: "arabic", known: true },

        { uuid: "f22c37de-9126-4acc-9867-5de89979b012", name: "ygg", primaryUrl: "http://online.svitle.org:6728/kids.128.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 792, tags: "", language: "arabic", known: true },

        { uuid: "78fe4c57-8bfd-481b-a368-292581e4f6f5", name: "Turkmen FM  تركمان إف إم", primaryUrl: "https://radyo1.radyo-dinle.tc/8284/stream;", fallbackUrl: "", category: "MUSIC", countryCode: "IQ", bitrate: 128, popularity: 790, tags: "", language: "arabic", known: true },

    { uuid: "047fcfb5-9e59-478e-9e6a-a006cc401868", name: "Nogoum FM", primaryUrl: "https://stream-159.zeno.fm/qb1zvsykm98uv?zt=eyJhbGciOiJIUzI1NiJ9.eyJzdHJlYW0iOiJxYjF6dnN5a205OHV2IiwiaG9zdCI6InN0cmVhbS0xNTkuemVuby5mbSIsInJ0dGwiOjUsImp0aSI6Ild2WTItbVhJU2Z5VlBEOHc3a2J1X0EiLCJpYXQiOjE3NzA5ODcyOTUsImV4cCI6MTc3MDk4NzM1NX0.ob8jdjoAgAzIcrxMk5b127wgaxpnJHEASSChnE5v5ro", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 789, tags: "", language: "arabic", known: true },

    { uuid: "e7e418ef-a7c1-48d1-a1ac-64010dcb7fa4", name: "seba", primaryUrl: "http://213.6.93.254:8000/sebafm", fallbackUrl: "", category: "MUSIC", countryCode: "PS", bitrate: 128, popularity: 788, tags: "", language: "arabic", known: true },

    { uuid: "96475698-0601-11e8-ae97-52543be04c81", name: "Light FM 90.5 & 90.7 Beirut", primaryUrl: "http://23623.live.streamtheworld.com/LIGHTFMAAC_SC", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 787, tags: "", language: "arabic", known: true },

    { uuid: "17e85b1e-1eaf-465e-8753-4178e605f7b9", name: "Arab Mix 256", primaryUrl: "https://stream.zeno.fm/wvqgc9kb1d0uv", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 786, tags: "", language: "arabic", known: true },

    { uuid: "9637bb37-0601-11e8-ae97-52543be04c81", name: "Byblos Radio", primaryUrl: "http://byblosnights.com:8000/byblosaac", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 785, tags: "", language: "arabic", known: true },

    { uuid: "65579ebd-da1b-412f-8da6-7a82853e5cc3", name: "إذاعة السنة", primaryUrl: "http://andromeda.shoutca.st:8189/live", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 784, tags: "", language: "arabic", known: true },

    { uuid: "136e67ff-f7af-40cd-acbf-2b07ff116f54", name: "MRC:-Radio Adwaa Music 3", primaryUrl: "https://stream.zeno.fm/x4451xh0x1zuv", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 783, tags: "", language: "arabic", known: true },

    { uuid: "cfc734ad-6e1b-4e04-9a4b-6932f2f78ca7", name: "في ضلال السيرة النبوية", primaryUrl: "https://qurango.net/radio/fi_zilal_alsiyra", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 782, tags: "", language: "arabic", known: true },

    { uuid: "1ab7c1a5-8ce7-4bf2-a8b1-3e390811d9bf", name: "Classical Radio - Mozart", primaryUrl: "https://drive.uber.radio/uber/crmozart/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 781, tags: "", language: "arabic", known: true },

            { uuid: "215dd8ad-56af-4234-9ace-573b20567d28", name: "فيروز", primaryUrl: "https://stream.zeno.fm/9vrahqqfuuhvv", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 778, tags: "", language: "arabic", known: true },

    { uuid: "e0cb2d0e-e6c1-4aa4-8748-37a087e343c2", name: "MRC:-Hip Hop Soul Radio 	Radio Music", primaryUrl: "https://stream.zeno.fm/k7cdrm450s8uv", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 777, tags: "", language: "arabic", known: true },

    
            { uuid: "d6d9ace0-eb06-4a81-890b-3c24550003d5", name: "Radyo  Şirvan Perwer", primaryUrl: "https://stream-156.zeno.fm/x0xhw6c6g2zuv?zs=AfL9IRdsQ_qHIebZBo-9GA", fallbackUrl: "", category: "MUSIC", countryCode: "IQ", bitrate: 128, popularity: 773, tags: "", language: "arabic", known: true },

    { uuid: "51660ccb-c743-11e9-8502-52543be04c81", name: "Byblos Radio (AAC)", primaryUrl: "http://byblosnights.com:8360/", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 772, tags: "", language: "arabic", known: true },

    { uuid: "12a6599c-f8bf-47c6-9f92-19b2dd189ebb", name: "إذاعة ميراث الأنبياء", primaryUrl: "https://radio.al7eah.net/8028/;", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 771, tags: "", language: "arabic", known: true },

    
        { uuid: "30a739aa-0ef2-484c-a45a-4be7fbcb9603", name: "Arab Mix Drama", primaryUrl: "https://stream.zeno.fm/egynebf171zuv.acc", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 768, tags: "", language: "arabic", known: true },

        { uuid: "74bf8976-5e4b-4f55-8483-f94161c48fca", name: "Jordan Radio (Main)", primaryUrl: "https://jrtv-live.ercdn.net/jrradio/jordanradiovideo.m3u8", fallbackUrl: "", category: "MUSIC", countryCode: "JO", bitrate: 128, popularity: 766, tags: "", language: "arabic", known: true },

                        
    { uuid: "a66ea931-c72f-4e37-b880-96a98c1100cd", name: "أذكار الصباح", primaryUrl: "https://qurango.net/radio/athkar_sabah", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 759, tags: "", language: "arabic", known: true },

        { uuid: "8db26ee7-3de4-4cc4-b119-d710e0f0c046", name: "Radio fouedb Music", primaryUrl: "https://streaming.live365.com/a82574", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 757, tags: "", language: "arabic", known: true },

                                
    { uuid: "e2011342-2b07-40b7-a64d-af695146085b", name: "Aljazeera Arabic", primaryUrl: "https://live-hls-web-aja.getaj.net/AJA/index.m3u8", fallbackUrl: "", category: "MUSIC", countryCode: "QA", bitrate: 128, popularity: 748, tags: "", language: "arabic", known: true },

    { uuid: "9121aa97-ef1e-11e8-a471-52543be04c81", name: "Sky Arabia News TV", primaryUrl: "http://stream.skynewsarabia.com/hls/sna_720.m3u8", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 128, popularity: 747, tags: "", language: "arabic", known: true },

    { uuid: "7c53ff0d-1fd7-49f5-9a63-1c6594de4677", name: "صوت المقاومة", primaryUrl: "https://node-23.zeno.fm/b67qheuk54zuv", fallbackUrl: "", category: "MUSIC", countryCode: "YE", bitrate: 128, popularity: 746, tags: "", language: "arabic", known: true },

    { uuid: "244cf1a8-52d8-4e26-bf23-821a21129bbf", name: "قناة الجزيرة", primaryUrl: "https://live-hls-web-aja.getaj.net/AJA/06.m3u8", fallbackUrl: "", category: "MUSIC", countryCode: "QA", bitrate: 128, popularity: 745, tags: "", language: "arabic", known: true },

    { uuid: "6b77cd64-68ef-4984-91ec-9b7e77a26eda", name: "Radio Liban Libre", primaryUrl: "https://edge.mixlr.com/channel/qtqeb", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 744, tags: "", language: "arabic", known: true },

    { uuid: "e4046469-8cef-4ffd-a95d-528e57e4641b", name: "Al Jazeera - Arabic", primaryUrl: "http://live-hls-audio-web-aja.getaj.net/VOICE-AJA/index.m3u8", fallbackUrl: "", category: "MUSIC", countryCode: "QA", bitrate: 128, popularity: 743, tags: "", language: "arabic", known: true },

    { uuid: "075e5897-3bb9-4eaf-a418-ebdd7cba957e", name: "Al Arabiya FM", primaryUrl: "https://fm.alarabiya.net/fm/myStream/playlist.m3u8", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 742, tags: "", language: "arabic", known: true },

    
    { uuid: "bcfe2aae-33fa-494a-a571-c3bbc8715c7d", name: "راديو الناس", primaryUrl: "https://cdna.streamgates.net/RadioNas/Live-Audio/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "PS", bitrate: 128, popularity: 740, tags: "", language: "arabic", known: true },

    { uuid: "98688060-6cbe-4f73-be94-da8d8efc2ead", name: "Al araby", primaryUrl: "https://l3.itworkscdn.net/alarabyradiolive/alarabyradio_audio/icecast.audio", fallbackUrl: "", category: "MUSIC", countryCode: "QA", bitrate: 128, popularity: 739, tags: "", language: "arabic", known: true },

    { uuid: "abf148ba-cb50-4d30-a0da-ee68620019aa", name: "Kurdistan 24", primaryUrl: "https://audio-edge-3mayu.fra.h.radiomast.io/04b1ce4c-24f2-4172-b756-065832ef78bf", fallbackUrl: "", category: "MUSIC", countryCode: "IQ", bitrate: 128, popularity: 738, tags: "", language: "arabic", known: true },

    { uuid: "a6bc8a3b-67bf-4042-9af8-f7bad29f0734", name: "Al Jazeera - English", primaryUrl: "https://live-hls-audio-web-aje.getaj.net/VOICE-AJE/01.m3u8", fallbackUrl: "", category: "MUSIC", countryCode: "QA", bitrate: 128, popularity: 737, tags: "", language: "arabic", known: true },

    { uuid: "d706e494-cd28-46ad-ac79-ca49aa0f1a9f", name: "راديو فن    Fenn Radio", primaryUrl: "https://stream.zeno.fm/76e9f1gsduhvv", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 736, tags: "", language: "arabic", known: true },

    { uuid: "6787cf45-924e-4cac-84e1-7f6675712ae3", name: "Radio Express FM", primaryUrl: "https://expressfm.ice.infomaniak.ch/expressfm-64.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 735, tags: "", language: "arabic", known: true },

    
    { uuid: "b8ab13a0-938d-4600-92bc-44d2a85b13ae", name: "Radio Duhok FM", primaryUrl: "https://stream-149.zeno.fm/unwq3jdyhmbuv", fallbackUrl: "", category: "MUSIC", countryCode: "IQ", bitrate: 128, popularity: 733, tags: "", language: "arabic", known: true },

    { uuid: "3da33a8b-06b7-4fd7-8001-121cee38e651", name: "HOUB Radio (حُب)", primaryUrl: "http://nap.casthost.net:8028/stream", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 732, tags: "", language: "arabic", known: true },

    { uuid: "a481fed1-b46b-44da-9f9b-cc5c4820189b", name: "إذاعة الأخبار كل 5 دقائق   REPLAY NEWS - العربية", primaryUrl: "https://replaynewsar.ice.infomaniak.ch/replaynewsar-128.mp3", fallbackUrl: "", category: "NEWS", countryCode: "SA", bitrate: 128, popularity: 731, tags: "", language: "arabic", known: true },

    { uuid: "ef2cb53f-3f77-482c-942b-a2826cb324af", name: "Al Wisal", primaryUrl: "http://uk7.internet-radio.com:8067/;", fallbackUrl: "", category: "MUSIC", countryCode: "OM", bitrate: 128, popularity: 730, tags: "", language: "arabic", known: true },

    { uuid: "050966be-de77-49f0-bad9-6ef200d219f3", name: "Ambiance FM", primaryUrl: "https://stream.zeno.fm/0rehsamc9xxtv", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 729, tags: "", language: "arabic", known: true },

    { uuid: "ebbd977c-e0a9-4650-8c3b-9d97b37988a8", name: "A f R o 505 / By _MX2", primaryUrl: "https://stream.zeno.fm/7wozjkc8x0vvv", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 728, tags: "", language: "arabic", known: true },

    { uuid: "f2e5b99e-424b-4ac2-bf13-b4840fe91aee", name: "NPR News Radio 107.7 FM", primaryUrl: "http://npr-ice.streamguys1.com/live.aac", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 727, tags: "", language: "arabic", known: true },

    { uuid: "05fb8b90-3826-4719-b309-0ea5112a4921", name: "El Bahdja - الجزائر العاصمة", primaryUrl: "https://webradio3.tda.dz:8001/ElBahdja_64K.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 726, tags: "", language: "arabic", known: true },

        { uuid: "6ec50764-7237-405a-8864-4b9b22b8100b", name: "Al Jazeera Video", primaryUrl: "https://live-hls-apps-aje-fa.getaj.net/AJE/index.m3u8", fallbackUrl: "", category: "MUSIC", countryCode: "QA", bitrate: 128, popularity: 724, tags: "", language: "arabic", known: true },

    { uuid: "a93d497f-74d9-43b9-bb61-fe376ebe240e", name: "Al Jazeera Arabic", primaryUrl: "https://live-hls-web-aja-gcp.thehlive.com/VOICE-AJA/index.m3u8", fallbackUrl: "", category: "MUSIC", countryCode: "QA", bitrate: 128, popularity: 723, tags: "", language: "arabic", known: true },

    { uuid: "5a98544a-340b-4c5a-82b7-4641b67a2264", name: "Raya FM", primaryUrl: "https://rstream.hadara.ps/proxy/raya/stream", fallbackUrl: "", category: "MUSIC", countryCode: "PS", bitrate: 128, popularity: 722, tags: "", language: "arabic", known: true },

    { uuid: "06d8af5e-3f5a-4473-8f71-8343140a938f", name: "Algérie Internationale الجزائر الدولية", primaryUrl: "https://webradio3.tda.dz:8001/Internationale_64K.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 721, tags: "", language: "arabic", known: true },

    { uuid: "dfa44ba9-e0b9-4a22-a848-5b28d2781986", name: "speda radio", primaryUrl: "https://radio.host247.net/spedafm", fallbackUrl: "", category: "MUSIC", countryCode: "IQ", bitrate: 128, popularity: 720, tags: "", language: "arabic", known: true },

    { uuid: "ada3cd11-4035-41b2-899a-45eee0cf8756", name: "Al Jazeera English (Live)", primaryUrl: "https://live-hls-web-aje-gcp.thehlive.com/AJE/index.m3u8", fallbackUrl: "", category: "MUSIC", countryCode: "QA", bitrate: 128, popularity: 719, tags: "", language: "arabic", known: true },

    { uuid: "d9bbea87-9051-45cb-abe6-8992a7a76cf1", name: "Universal Health Radio (العربية)", primaryUrl: "https://radio.universalhealthradio.org/uhr-ar.mp3", fallbackUrl: "", category: "NEWS", countryCode: "SA", bitrate: 128, popularity: 718, tags: "", language: "arabic", known: true },

    { uuid: "22f064ab-be27-4e6e-8f34-8d6509c97007", name: "Chaine 1 القناة الأولى", primaryUrl: "https://webradio1.tda.dz:8001/Chaine1_64K.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 717, tags: "", language: "arabic", known: true },

    { uuid: "c6c77aaf-e899-463d-a9f5-a556180a4f82", name: "Tizi Ouzou  تيزي وزو", primaryUrl: "https://webradio2.tda.dz:8001/TiziOuzou_64K.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 716, tags: "", language: "arabic", known: true },

    { uuid: "2bff5955-3a88-46cd-92e9-57033683a7f0", name: "Setif سطيف", primaryUrl: "https://webradio3.tda.dz:8001/Setif_64K.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 715, tags: "", language: "arabic", known: true },

    { uuid: "38ba2533-565e-4ebf-a76b-4498d6ce1203", name: "Oran وهران", primaryUrl: "https://webradio2.tda.dz:8001/Oran_64K.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 714, tags: "", language: "arabic", known: true },

    { uuid: "54c5a7ab-293b-4273-8cf9-4d408cb1441b", name: "Dreamstar Radio KSA 94.3 FM", primaryUrl: "https://stream-173.zeno.fm/h0y9yu8hzk8uv?zt=eyJhbGciOiJIUzI1NiJ9.eyJzdHJlYW0iOiJoMHk5eXU4aHprOHV2IiwiaG9zdCI6InN0cmVhbS0xNzMuemVuby5mbSIsInJ0dGwiOjUsImp0aSI6IlZidVlINUhnVDZTMk82N0NndEY5cnciLCJpYXQiOjE3MzAxMDA3NTcsImV4cCI6MTczMDEwMDgxN30.nEG8Ng_RkLh72La19iYQBK9312NlFrKm1V4X7371hkU", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 713, tags: "", language: "arabic", known: true },

    { uuid: "5f344328-0823-42fa-b2c5-39375543bbee", name: "Zaman FM زمان", primaryUrl: "https://webradio2.tda.dz:8001/Zamen_64K.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 712, tags: "", language: "arabic", known: true },

    { uuid: "bde2191e-4736-41b9-bbe7-816521af8662", name: "Radio Azrou Live", primaryUrl: "https://stream.zeno.fm/nzdw7gqc0s8uv", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 711, tags: "", language: "arabic", known: true },

    { uuid: "a66ef2d9-3cda-4dfe-8ffd-35476309543a", name: "Chaine 3 القناة الثالثة", primaryUrl: "https://webradio1.tda.dz:8001/Chaine3_64K.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 710, tags: "", language: "arabic", known: true },

    
    { uuid: "49545fc3-653c-4e36-b250-359024629b45", name: "Radio Azawan (192 kbps)", primaryUrl: "https://az-maroc.ice.infomaniak.ch/az-maroc-128.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 708, tags: "", language: "arabic", known: true },

    { uuid: "1fa1ad84-e008-4af2-9a61-1b37daeb34ff", name: "Bordj Bou Arreridj  برج بوعريريج", primaryUrl: "https://webradio3.tda.dz:8001/BordjBouArreridj_64K.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 707, tags: "", language: "arabic", known: true },

    { uuid: "d1c087ac-267a-425e-8dc5-6ba4644e5249", name: "Constantine قسنطينة", primaryUrl: "https://webradio3.tda.dz:8001/Constantine_64K.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 706, tags: "", language: "arabic", known: true },

    { uuid: "8efd6d38-d14b-4a09-853c-10df4d012de7", name: "Ghardaïa غرداية", primaryUrl: "https://webradio1.tda.dz:8001/Ghardaia_64K.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 705, tags: "", language: "arabic", known: true },

    { uuid: "cc6747c6-64ff-4ac3-88f5-08791402ad5e", name: "Biskra بسكرة", primaryUrl: "https://webradio3.tda.dz:8001/Biskra_64K.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 704, tags: "", language: "arabic", known: true },

    { uuid: "a1887b6d-dee2-48be-8770-cbdef7951a3e", name: "Culture", primaryUrl: "https://webradio3.tda.dz:8001/Culture_64K.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 703, tags: "", language: "arabic", known: true },

    { uuid: "3a2be197-6831-4db2-9e4e-0208b714e738", name: "Chaine 2 القناة الثانية", primaryUrl: "https://webradio2.tda.dz:8001/Chaine2_64K.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 702, tags: "", language: "arabic", known: true },

    { uuid: "1b78d198-f777-46a5-8797-8bb023de8db5", name: "Boumerdès بومرداس", primaryUrl: "https://webradio2.tda.dz:8001/Boumerdes_64K.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 701, tags: "", language: "arabic", known: true },

    { uuid: "1caeea47-6bee-411a-aa51-d11f172b9e4e", name: "M'sila المسيلة", primaryUrl: "https://webradio3.tda.dz:8001/Msila_64K.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 700, tags: "", language: "arabic", known: true },

    { uuid: "279b39aa-6e80-4e2a-bb3f-f68f8cf9b6c8", name: "Tlemcen تلمسان", primaryUrl: "https://webradio1.tda.dz:8001/Tlemcen_64K.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 699, tags: "", language: "arabic", known: true },

    { uuid: "aa7e35fb-8b75-425f-8710-3cbd64eefd7d", name: "Djelfa الجلفة", primaryUrl: "https://webradio2.tda.dz:8001/Djelfa_64K.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 698, tags: "", language: "arabic", known: true },

    { uuid: "60044cf2-2d50-40e8-a4c6-be956bc3e74a", name: "Tiaret تيارت", primaryUrl: "https://webradio2.tda.dz:8001/Tiaret_64K.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 697, tags: "", language: "arabic", known: true },

    { uuid: "7385669d-fb03-4555-a21f-e2283e0c648c", name: "Sky ABC Radio 96.6 FM", primaryUrl: "https://stream-02.surfernetwork.com/iysqd3wuj38uv?zt=eyJhbGciOiJIUzI1NiJ9.eyJzdHJlYW0iOiJpeXNxZDN3dWozOHV2IiwiaG9zdCI6InN0cmVhbS0wMi5zdXJmZXJuZXR3b3JrLmNvbSIsInJ0dGwiOjUsImp0aSI6Im1ieUJHT0lOVC1HSXllSFlGbG5KRXciLCJpYXQiOjE3NzgxNjAwMDMsImV4cCI6MTc3ODE2MDA2M30.xYqjN-KICXfpgsxWdN_Ae3gaNqpRsTdELgTkBOVutmg", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 696, tags: "", language: "arabic", known: true },

    { uuid: "4caa43a0-ed29-44f4-bb5d-47042a46c6c4", name: "Annaba عنابة", primaryUrl: "https://webradio1.tda.dz:8001/Annaba_64K.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 128, popularity: 695, tags: "", language: "arabic", known: true },

    { uuid: "efbc6fa2-4a29-41f7-a18e-c1d6a6be8304", name: "RadioSunna", primaryUrl: "http://andromeda.shoutca.st:8189/stream", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 694, tags: "", language: "arabic", known: true },

    { uuid: "2c260d07-110c-473c-8a7f-df4b937b31a8", name: "Radio Manarat", primaryUrl: "https://listen.radioking.com/radio/252934/stream/297385", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 693, tags: "", language: "arabic", known: true },

    { uuid: "e2d75665-c4a9-4433-b953-3a5346ae5fb6", name: "Fred Film Radio(لغة عربية)", primaryUrl: "https://s10.webradio-hosting.com/proxy/fredradioar/stream", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 692, tags: "", language: "arabic", known: true },

        { uuid: "9793ba8c-91b0-4109-b053-d88a29bc2672", name: "HAWA al Sudan", primaryUrl: "https://stream.zeno.fm/mqctx1bfd8zuv", fallbackUrl: "", category: "MUSIC", countryCode: "SD", bitrate: 128, popularity: 690, tags: "", language: "arabic", known: true },

    { uuid: "66b08e8e-3be6-4c4e-a765-9423e0398110", name: "Yaqeen FM 103.7", primaryUrl: "http://45.63.116.205:8000/stream4", fallbackUrl: "", category: "MUSIC", countryCode: "JO", bitrate: 128, popularity: 689, tags: "", language: "arabic", known: true },

    { uuid: "55fe42e3-74dc-4a71-80e3-87ac0d0f8a6b", name: "Fusha FM 102.9 - Jordan Academy of Arabic", primaryUrl: "https://eu9.fastcast4u.com/proxy/arabicla?mp=/1", fallbackUrl: "", category: "MUSIC", countryCode: "JO", bitrate: 128, popularity: 688, tags: "", language: "arabic", known: true },

    { uuid: "b54622a7-cec6-4f49-8037-ca3382a80ebe", name: "Rabee Al Qloub", primaryUrl: "http://radio-ssl.com:8006/stream", fallbackUrl: "", category: "MUSIC", countryCode: "JO", bitrate: 128, popularity: 687, tags: "", language: "arabic", known: true },

    { uuid: "cd74834d-9504-42e9-a165-4d7194afaf54", name: "Sawt El Nagham", primaryUrl: "https://stream.zeno.fm/66sfwug4ag0uv", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 686, tags: "", language: "arabic", known: true },

    
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