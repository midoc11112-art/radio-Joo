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
    { uuid: "manual-voice-of-jazeera", name: "صوت الجزيرة Al Jazeera Voice", primaryUrl: "https://live-hls-audio-web-aja.getaj.net/VOICE-AJA/index.m3u8", fallbackUrl: "", category: "NEWS", countryCode: "QA", bitrate: 128, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "manual-skynews-arabia", name: "سكاي نيوز عربية Sky News Arabia", primaryUrl: "https://stream.skynewsarabia.com/hls/sna.m3u8", fallbackUrl: "", category: "NEWS", countryCode: "AE", bitrate: 128, popularity: 995, tags: "", language: "arabic", known: true },
    { uuid: "manual-mbc-fm", name: "MBC FM", primaryUrl: "https://radio-mbcfm.mbc.net/radio-mbcfm_1.m3u8", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 990, tags: "", language: "arabic", known: true },
    { uuid: "manual-quran-cairo-alt", name: "إذاعة القرآن الكريم من القاهرة", primaryUrl: "https://n0d.radiojar.com/8s5u5tpdtwzuv.mp3?rj-ttl=5&rj-tok=AAABhwLBHbYAfz9OyE3ZQvJh-w", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 998, tags: "", language: "arabic", known: true },
    { uuid: "manual-mix-fm-eg", name: "ميكس إف إم 87.8 Mix FM", primaryUrl: "https://eu1.fastcast4u.com/proxy/prontofm?mp=/1", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 985, tags: "", language: "arabic", known: true },
    { uuid: "manual-radiowayak", name: "راديو وياك Radiowayak", primaryUrl: "https://work.radiowayak.org/listen/live/live.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 700, tags: "", language: "arabic", known: true },
    { uuid: "manual-hits882", name: "Hits 88.2 راديو هيتس", primaryUrl: "https://radiohits882.radioca.st/stream", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 980, tags: "", language: "arabic", known: true },
    { uuid: "manual-sha3bi", name: "شعبي إف إم SHA3BY FM", primaryUrl: "https://radio95.radioca.st/stream", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 975, tags: "", language: "arabic", known: true },
    { uuid: "manual-omkalthoum", name: "أم كلثوم OM Kalthoum Radio", primaryUrl: "https://stream.zeno.fm/zsgrfxg71s8uv", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 960, tags: "", language: "arabic", known: true },
    { uuid: "manual-radio9090", name: "راديو 9090", primaryUrl: "http://9090streaming.mobtada.com/9090FMEGYPT", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 988, tags: "", language: "arabic", known: true },
    { uuid: "manual-onsportfm", name: "أون سبورت إف إم On Sport FM", primaryUrl: "https://carina.streamerr.co:2020/stream/OnSportFM", fallbackUrl: "", category: "SPORTS", countryCode: "EG", bitrate: 128, popularity: 970, tags: "", language: "arabic", known: true },
    { uuid: "manual-diabfm", name: "دياب إف إم Diab FM", primaryUrl: "https://stream-34.zeno.fm/rf64mx02qa0uv?zs=-xjlLLwRS8uKrffFxK4vLA", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 940, tags: "", language: "arabic", known: true },
    { uuid: "manual-arabmix256", name: "عرب ميكس 256 Arab Mix", primaryUrl: "https://stream.zeno.fm/wvqgc9kb1d0uv", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 930, tags: "", language: "arabic", known: true },
    { uuid: "manual-salma-recitation", name: "تلاوات خاشعة - جميلة Salma", primaryUrl: "https://qurango.net/radio/salma", fallbackUrl: "", category: "QURAN", countryCode: "SA", bitrate: 128, popularity: 800, tags: "", language: "arabic", known: true },
    { uuid: "manual-ajyal", name: "أجيال إف إم فلسطين Ajyal", primaryUrl: "https://streamer.mada.ps:8208/ajyal", fallbackUrl: "", category: "MUSIC", countryCode: "PS", bitrate: 128, popularity: 890, tags: "", language: "arabic", known: true },
    { uuid: "manual-radio-orient", name: "راديو الشرق Radio Orient لبنان", primaryUrl: "https://stream.rcs.revma.com/7hnrkawf4p8uv.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 880, tags: "", language: "arabic", known: true },
    { uuid: "manual-sumer-fm", name: "سمر إف إم العراق Sumer FM", primaryUrl: "https://l3.itworkscdn.net/itwaudio/9012/stream", fallbackUrl: "", category: "MUSIC", countryCode: "IQ", bitrate: 128, popularity: 750, tags: "", language: "arabic", known: true },
    { uuid: "manual-alifalif", name: "ألف ألف إف إم AlifAlif FM", primaryUrl: "https://alifalifjobs.com/radio/8000/AlifAlifLive.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "manual-aswat", name: "إذاعة أصوات Aswat FM المغرب", primaryUrl: "http://broadcast.ice.infomaniak.ch/aswat-high.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "8dad4ff4-2ae8-4fba-b379-c964f6ae9f4a", name: "Arab Mix FM", primaryUrl: "https://stream.zeno.fm/na3vpvn10qruv", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "b1d17829-6522-481b-bb5c-839c3fd44353", name: "Radio Hits 88.2 Cairo", primaryUrl: "https://radiohits882.radioca.st/;", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "7eb9e8cd-bf7f-4bcc-afe2-3f56608911e6", name: "Elissa FM", primaryUrl: "https://stream.zeno.fm/v7n499m8ckhvv", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "5db41df8-9921-421f-a618-84864ededf86", name: "إذاعة القرآن الكريم (القاهرة)", primaryUrl: "https://stream.radiojar.com/8s5u5tpdtwzuv", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 0, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "59f4eecd-7193-4bc3-8bc3-c1463d92b22d", name: "Alseraj Radio Quran", primaryUrl: "http://192.99.170.8:5550/stream", fallbackUrl: "", category: "QURAN", countryCode: "IQ", bitrate: 96, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "e9744b90-2619-4a0c-93b4-5996e213068a", name: "Mp3Quran Main", primaryUrl: "https://qurango.net/radio/mix", fallbackUrl: "", category: "QURAN", countryCode: "SA", bitrate: 128, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "77c8094d-6822-4221-be7b-1d54ab477d78", name: "Quran Radio راديو القرآن - Abdulrahman Alsudais - عبدالرحمن السديس", primaryUrl: "https://radio.mp3islam.com/listen/sudais/radio.mp3", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 64, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "0f35d49a-3e12-4936-a8e7-a3ae7698ea58", name: "El Gouna Radio", primaryUrl: "http://82.201.132.237:8000/;", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "368ef657-f8b5-47ce-b578-337e401cc744", name: "Voice of Lebanon (Sawtlebnan) Radio", primaryUrl: "https://l3.itworkscdn.net/itwaudio/9054/stream", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "7d1378e3-8e91-46f9-a98d-6f7426e60a3c", name: "Sawt El Mada", primaryUrl: "http://audiostreaming.itworkscdn.com:9018/stream", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "125ec344-a2a1-4e36-8f66-77401f457d20", name: "Aswat FM", primaryUrl: "https://broadcast.ice.infomaniak.ch/aswat-high.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "0b14ae20-92b3-4db7-834f-ee6ba39ad080", name: "إذاعة مشاري العفاسي", primaryUrl: "https://qurango.net/radio/mishary_alafasi", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "3f5d8a52-dea9-4201-9575-a58adc8e9263", name: "إذاعة محمود خليل الحصري", primaryUrl: "https://qurango.net/radio/mahmoud_khalil_alhussary_warsh", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "0155ec12-7308-468a-88dc-fcbd440e01da", name: "إذاعة مصطفى إسماعيل", primaryUrl: "https://qurango.net/radio/mustafa_ismail", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "ceba48a5-9356-4891-a1fc-5ff9ea832120", name: "90s FM", primaryUrl: "http://eu1.fastcast4u.com/proxy/prontofm", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "8bee6f3f-5bbd-4a2a-9cb9-0b75063f35b2", name: "---تراتيل قصيرة متميزة---", primaryUrl: "https://qurango.net/radio/tarateel", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "30a739aa-0ef2-484c-a45a-4be7fbcb9603", name: "Arab Mix Drama", primaryUrl: "https://stream.zeno.fm/egynebf171zuv.acc", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "5e1c31e7-7541-439a-9081-6f78508bb637", name: "إذاعة ياسر الدوسري", primaryUrl: "https://qurango.net/radio/yasser_aldosari", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "6b77cd64-68ef-4984-91ec-9b7e77a26eda", name: "Radio Liban Libre", primaryUrl: "https://edge.mixlr.com/channel/qtqeb", fallbackUrl: "", category: "NEWS", countryCode: "LB", bitrate: 0, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "0cb24613-f3ba-41d6-9410-b3aaaa49c3f9", name: "Al rasheed fm", primaryUrl: "https://streaming.shoutcast.com/alrasheed-fm?1719771692024", fallbackUrl: "", category: "MUSIC", countryCode: "IQ", bitrate: 320, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "23754a47-c75e-11e8-a54a-52543be04c81", name: "Radio القارئ علي حجاج السويسي", primaryUrl: "http://live.mp3quran.net:9842/", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "6aed851e-a68c-43c6-a0b1-f0bb7476e5d3", name: "Abdulrasheed Soufi", primaryUrl: "https://qurango.net/radio/abdulrasheed_soufi_assosi.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "d42cb646-0c16-11e9-a80b-52543be04c81", name: "Libyana Hits FM", primaryUrl: "http://proxima.shoutca.st:8946/stream", fallbackUrl: "", category: "MUSIC", countryCode: "LY", bitrate: 96, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "344e6e4e-a7b3-11e8-a8ed-52543be04c81", name: "RadioSunna", primaryUrl: "http://andromeda.shoutca.st:8189/stream", fallbackUrl: "", category: "QURAN", countryCode: "SA", bitrate: 32, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "88ac049f-d017-11e9-a861-52543be04c81", name: "lbi Radio", primaryUrl: "http://andromeda.shoutca.st:8192/;stream.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "cd370991-b259-423b-a3d3-b972eb2265aa", name: "Radio Tunisie Culturelle", primaryUrl: "http://rtstream.tanitweb.com/culturelle", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "5b61ccc0-f369-4368-ba65-3b10b7f6bae9", name: "Mood Fm 92 Amman", primaryUrl: "https://securestreams2.autopo.st:1241/live", fallbackUrl: "", category: "MUSIC", countryCode: "JO", bitrate: 128, popularity: 999, tags: "", language: "arabic", known: true },
    { uuid: "c5c08ef9-5559-4073-87a8-80bff2b4860b", name: "Monte Carlo Doualiya", primaryUrl: "https://montecarlodoualiya128k.ice.infomaniak.ch/mc-doualiya.mp3", fallbackUrl: "", category: "NEWS", countryCode: "FR", bitrate: 128, popularity: 870, tags: "", language: "arabic", known: true },
    { uuid: "d9bbea87-9051-45cb-abe6-8992a7a76cf1", name: "Universal Health Radio (العربية)", primaryUrl: "https://radio.universalhealthradio.org/uhr-ar.mp3", fallbackUrl: "", category: "NEWS", countryCode: "SA", bitrate: 0, popularity: 860, tags: "", language: "arabic", known: true },
    { uuid: "cd74834d-9504-42e9-a165-4d7194afaf54", name: "Sawt El Nagham", primaryUrl: "https://stream.zeno.fm/66sfwug4ag0uv", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 850, tags: "", language: "arabic", known: true },
    { uuid: "73f665df-37cd-4c4c-b398-6e97e526a01d", name: "LBI Radio Hits", primaryUrl: "https://lbihits-lbnet2.radioca.st/stream", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 760, tags: "", language: "arabic", known: true },
    { uuid: "4a116b98-e289-4630-9b62-b93a67159c7e", name: "Anghamy", primaryUrl: "https://radio.avazfarsi.com:8010/radio.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 671, tags: "", language: "arabic", known: true },
    { uuid: "580ef7ef-3ebe-45fd-8c90-25e365c64bc3", name: "Abdel Halim Hafez عبدالحليم حافظ", primaryUrl: "https://stream.zeno.fm/8a4eqkd0pnhvv", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 660, tags: "", language: "arabic", known: true },
    { uuid: "f0d6b0a0-a6b7-4574-8921-f581357ea998", name: "Fairuz فيروز", primaryUrl: "https://stream.zeno.fm/xkhnk4vee18uv", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 640, tags: "", language: "arabic", known: true },
    { uuid: "7cca8bd6-a173-4fca-9a74-ca1e15a631e5", name: "Quran Radio From Sharjah", primaryUrl: "https://l3.itworkscdn.net/smcquranlive/quranradiolive/icecast.audio", fallbackUrl: "", category: "QURAN", countryCode: "AE", bitrate: 56, popularity: 580, tags: "", language: "arabic", known: true },
    { uuid: "2cad3c1f-d425-45fc-bf91-e30acf81775f", name: "Quran Recitation by Sheikh Wadi Al-Yamani", primaryUrl: "https://serverkw.quran-uni.com:8226/;", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 580, tags: "", language: "arabic", known: true },
    { uuid: "eec68cdb-9fde-4a44-81d1-4e752f05fdf5", name: "Radio Liban", primaryUrl: "https://media2.streambrothers.com:2020/stream/8194", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 64, popularity: 542, tags: "", language: "arabic", known: true },
    { uuid: "26eb1c40-f314-4976-8969-a70d1fcce1fc", name: "Hala FM", primaryUrl: "https://listen-halafm.sharp-stream.com/halafmlow.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "OM", bitrate: 64, popularity: 529, tags: "", language: "arabic", known: true },
    { uuid: "cbf8067e-8c54-4d62-81b9-df6589d782b1", name: "Sawt El Ghad", primaryUrl: "https://l3.itworkscdn.net/itwaudio/9030/stream", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 510, tags: "", language: "arabic", known: true },
    { uuid: "a9d04fb3-fd0e-4dbd-877e-5dd2d5d6e618", name: "Radio Sawt Alamal", primaryUrl: "https://stream.zeno.fm/qgw73bqh5qruv", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 510, tags: "", language: "arabic", known: true },
    { uuid: "f27afa46-8650-4a88-a632-6f46e61427b2", name: "Sawt El Ghad - Tarab", primaryUrl: "https://sawtelghad.org:7007/;", fallbackUrl: "", category: "MUSIC", countryCode: "PS", bitrate: 128, popularity: 510, tags: "", language: "arabic", known: true },
    { uuid: "84fa3e78-74ff-4a18-a40d-0edf87e9fa05", name: "Sawt Al Hoda 92.1 FM", primaryUrl: "https://l3.itworkscdn.net/itwaudio/9032/stream", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 510, tags: "", language: "arabic", known: true },
    { uuid: "52691bbc-72f0-4b1c-93f0-2a0fd13d9018", name: "Sawt Sydney كل الفرق", primaryUrl: "https://usa10.fastcast4u.com/nada22", fallbackUrl: "", category: "MUSIC", countryCode: "AU", bitrate: 128, popularity: 510, tags: "", language: "arabic", known: true },
    { uuid: "4e0c8d68-ad04-46e7-884d-0605979eb8d2", name: "Sawt Al Hob", primaryUrl: "https://stream.zeno.fm/0b97ef7rxchvv", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 510, tags: "", language: "arabic", known: true },
    { uuid: "9e88f54e-7a06-4225-a01e-9644a48d8499", name: "Medina FM Tarab (128 kbps)", primaryUrl: "https://medinatarab.ice.infomaniak.ch/medinatarab-128.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 500, tags: "", language: "arabic", known: true },
    { uuid: "8ada979f-dc6b-49ca-9dc9-bd3377c99aac", name: "Medina FM Nachat (128 kbps)", primaryUrl: "https://medinanachat.ice.infomaniak.ch/medinanachat-128.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 500, tags: "", language: "arabic", known: true },
    { uuid: "0502df2d-a93e-4d8a-8547-ae9d341a1128", name: "MEDINA FM", primaryUrl: "https://medinafm.ice.infomaniak.ch/medinafm-64.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 64, popularity: 500, tags: "", language: "arabic", known: true },
    { uuid: "3194ab53-148e-482c-b392-6b33ad40d9b1", name: "Medina FM Andalou (128 kbps)", primaryUrl: "https://medinaandalou.ice.infomaniak.ch/medinaandalou-128.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 500, tags: "", language: "arabic", known: true },
    { uuid: "91ec0c03-f58c-4e42-a034-067a22f867d3", name: "Medina FM (128 kbps)", primaryUrl: "https://medinafm.ice.infomaniak.ch/medinafm-128.mp3", fallbackUrl: "", category: "NEWS", countryCode: "MA", bitrate: 128, popularity: 500, tags: "", language: "arabic", known: true },
    { uuid: "ef2cb53f-3f77-482c-942b-a2826cb324af", name: "Al Wisal", primaryUrl: "http://uk7.internet-radio.com:8067/;", fallbackUrl: "", category: "NEWS", countryCode: "OM", bitrate: 96, popularity: 463, tags: "", language: "arabic", known: true },
    { uuid: "050966be-de77-49f0-bad9-6ef200d219f3", name: "Ambiance FM", primaryUrl: "https://stream.zeno.fm/0rehsamc9xxtv", fallbackUrl: "", category: "NEWS", countryCode: "TN", bitrate: 192, popularity: 416, tags: "", language: "arabic", known: true },
    { uuid: "b0d990d7-167a-44a1-830e-35dedaa2982d", name: "Al rasheed fm )Kirkuk)", primaryUrl: "https://streaming.shoutcast.com/alrasheed-kirkuk", fallbackUrl: "", category: "MUSIC", countryCode: "IQ", bitrate: 128, popularity: 154, tags: "", language: "arabic", known: true },
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