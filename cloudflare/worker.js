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
      cached = await env.STATIONS_KV.get("stations_v21", "json");
    } catch (e) {}

    const now = Date.now();
    const twoHours = 2 * 60 * 60 * 1000;

    if (cached && cached.lastUpdated && (now - cached.lastUpdated) < twoHours && cached.stations.length > 30) {
      return new Response(JSON.stringify({
        version: "21.0.0",
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
        await env.STATIONS_KV.put("stations_v21", JSON.stringify({
          lastUpdated: now,
          stations: stations
        }), { expirationTtl: 86400 });
      } catch (e) {}
    }

    return new Response(JSON.stringify({
      version: "21.0.0",
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

  // ✅ إزالة التكرار (نحتفظ بالأولوية للمحطات المعروفة)
  const unique = removeDuplicatesSmart(allStations);

  // ✅ ترتيب: المعروفين الأول، بعدين حسب الشعبية
  unique.sort((a, b) => {
    if (a.known && !b.known) return -1;
    if (!a.known && b.known) return 1;
    return b.popularity - a.popularity;
  });

  // ✅ نشيل خاصية known قبل ما نرجع
  return unique.map(({ known, ...rest }) => rest);
}

// محطات معروفة URLsها ثابتة ومتأكد منها مباشرة (مولدة من TrustedStations.kt - فحص 2026-11-04)
function getKnownWorkingStations() {
  return [
    { uuid: "manual-jazeera-voice", name: "صوت الجزيرة Al Jazeera Voice", primaryUrl: "https://live-hls-audio-web-aja.getaj.net/VOICE-AJA/index.m3u8", fallbackUrl: "", category: "NEWS", countryCode: "QA", bitrate: 99, popularity: 200, tags: "", language: "arabic", known: true },
    { uuid: "manual-sky-radio", name: "سكاي نيوز راديو Sky News Arabia Radio", primaryUrl: "https://stream.skynewsarabia.com/hls/sna.m3u8", fallbackUrl: "", category: "NEWS", countryCode: "AE", bitrate: 99, popularity: 180, tags: "", language: "arabic", known: true },
    { uuid: "manual-mbc-fm", name: "MBC FM السعودية", primaryUrl: "https://radio-mbcfm.mbc.net/radio-mbcfm_1.m3u8", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 99, popularity: 170, tags: "", language: "arabic", known: true },
    { uuid: "manual-rotana-fm", name: "روتانا إف إم Rotana FM", primaryUrl: "http://philae.shoutca.st:8250/stream", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 160, tags: "", language: "arabic", known: true },
    { uuid: "manual-bbc-world", name: "بي بي سي BBC World Service", primaryUrl: "http://stream.live.vc.bbcmedia.co.uk/bbc_world_service", fallbackUrl: "", category: "NEWS", countryCode: "GB", bitrate: 96, popularity: 150, tags: "", language: "arabic", known: true },
    { uuid: "manual-mcd", name: "مونت كارلو الدولية Monte Carlo Doualiya", primaryUrl: "https://montecarlodoualiya128k.ice.infomaniak.ch/mc-doualiya.mp3", fallbackUrl: "", category: "NEWS", countryCode: "FR", bitrate: 128, popularity: 140, tags: "", language: "arabic", known: true },
    { uuid: "manual-alifalif", name: "ألف ألف إف إم AlifAlif FM", primaryUrl: "https://alifalifjobs.com/radio/8000/AlifAlifLive.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 130, tags: "", language: "arabic", known: true },
    { uuid: "8e63e8b6-4e70-4bc0-9f5d-07e8477a4480", name: "إذاعة القرآن الكريم من القاهرة", primaryUrl: "http://n12.radiojar.com/8s5u5tpdtwzuv", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 0, popularity: 305, tags: "", language: "arabic", known: false },
    { uuid: "9616a843-0601-11e8-ae97-52543be04c81", name: "Mosaique FM", primaryUrl: "http://radio.mosaiquefm.net:8000/mosalive", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 90, popularity: 140, tags: "", language: "arabic", known: false },
    { uuid: "a2c020ae-97b4-4466-9d23-b594d6412e25", name: "اذاعة القرآن الكريم", primaryUrl: "http://stream.radiojar.com/0tpy1h0kxtzuv", fallbackUrl: "", category: "QURAN", countryCode: "DZ", bitrate: 0, popularity: 105, tags: "", language: "arabic", known: false },
    { uuid: "0b14ae20-92b3-4db7-834f-ee6ba39ad080", name: "إذاعة مشاري العفاسي", primaryUrl: "https://qurango.net/radio/mishary_alafasi", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 101, tags: "", language: "arabic", known: false },
    { uuid: "3f5d8a52-dea9-4201-9575-a58adc8e9263", name: "إذاعة محمود خليل الحصري", primaryUrl: "https://qurango.net/radio/mahmoud_khalil_alhussary_warsh", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 98, tags: "", language: "arabic", known: false },
    { uuid: "047fcfb5-9e59-478e-9e6a-a006cc401868", name: "Nogoum FM", primaryUrl: "https://stream-159.zeno.fm/qb1zvsykm98uv", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 82, tags: "", language: "arabic", known: false },
    { uuid: "41e19a4b-c517-486b-a111-6b3472eee741", name: "OM Kalthom أم كلثوم", primaryUrl: "https://stream.zeno.fm/zsgrfxg71s8uv", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 77, tags: "", language: "arabic", known: false },
    { uuid: "961a5858-0601-11e8-ae97-52543be04c81", name: "Radio 9090 90.9", primaryUrl: "http://9090streaming.mobtada.com/9090FMEGYPT", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 64, popularity: 73, tags: "", language: "arabic", known: false },
    { uuid: "96414555-0601-11e8-ae97-52543be04c81", name: "Radio Aswat", primaryUrl: "http://broadcast.ice.infomaniak.ch/aswat-high.mp3?.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 68, tags: "", language: "arabic", known: false },
    { uuid: "bc1e8eb3-a94e-48d7-892a-e790a1599b60", name: "SumerFm Iraq", primaryUrl: "https://l3.itworkscdn.net/itwaudio/9012/stream", fallbackUrl: "", category: "MUSIC", countryCode: "IQ", bitrate: 128, popularity: 67, tags: "", language: "arabic", known: false },
    { uuid: "0155ec12-7308-468a-88dc-fcbd440e01da", name: "إذاعة مصطفى إسماعيل", primaryUrl: "https://qurango.net/radio/mustafa_ismail", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 65, tags: "", language: "arabic", known: false },
    { uuid: "215dd8ad-56af-4234-9ace-573b20567d28", name: "فيروز", primaryUrl: "https://stream.zeno.fm/9vrahqqfuuhvv", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 64, tags: "", language: "arabic", known: false },
    { uuid: "580ef7ef-3ebe-45fd-8c90-25e365c64bc3", name: "Abdel Halim Hafez عبدالحليم حافظ", primaryUrl: "https://stream.zeno.fm/8a4eqkd0pnhvv", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 62, tags: "", language: "arabic", known: false },
    { uuid: "701b70e7-2126-4b4b-b1db-ea5295b43bfe", name: "Radio Plus Agadir FM 92.4", primaryUrl: "https://stream-158.zeno.fm/bqdbb6hd0neuv", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 58, tags: "", language: "arabic", known: false },
    { uuid: "98688060-6cbe-4f73-be94-da8d8efc2ead", name: "Al araby", primaryUrl: "https://l3.itworkscdn.net/alarabyradiolive/alarabyradio_audio/icecast.audio", fallbackUrl: "", category: "NEWS", countryCode: "QA", bitrate: 56, popularity: 55, tags: "", language: "arabic", known: false },
    { uuid: "939fa825-05d8-41c9-96a5-37a83f0737c1", name: "القران الكريم من القاهره", primaryUrl: "http://n0c.radiojar.com/8s5u5tpdtwzuv", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 0, popularity: 51, tags: "", language: "arabic", known: false },
    { uuid: "7eb9e8cd-bf7f-4bcc-afe2-3f56608911e6", name: "Elissa FM", primaryUrl: "https://stream.zeno.fm/v7n499m8ckhvv", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 51, tags: "", language: "arabic", known: false },
    { uuid: "30a739aa-0ef2-484c-a45a-4be7fbcb9603", name: "Arab Mix Drama", primaryUrl: "https://stream.zeno.fm/egynebf171zuv.acc", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 51, tags: "", language: "arabic", known: false },
    { uuid: "a9bee505-526e-4c7c-bf03-9797bbc8e28b", name: "تراتيل قصيرة متميزة---", primaryUrl: "https://qurango.net/radio/tarateel", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 49, tags: "", language: "arabic", known: false },
    { uuid: "5c2e9722-be4e-40b5-9ece-3ae2d4c36ffe", name: "إذاعة القرآن الكريم (مكة المكرمة)", primaryUrl: "https://edge.mixlr.com/channel/rwumx", fallbackUrl: "", category: "QURAN", countryCode: "SA", bitrate: 0, popularity: 49, tags: "", language: "arabic", known: false },
    { uuid: "961ee9aa-0601-11e8-ae97-52543be04c81", name: "Jawhara FM", primaryUrl: "http://streaming2.toutech.net:8000/jawharafm", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 48, tags: "", language: "arabic", known: false },
    { uuid: "ed186ea7-ac3e-47f5-bf33-99fbb15ffc86", name: "Jil FM", primaryUrl: "https://webradio1.tda.dz:8001/Jeunesse_64K.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 64, popularity: 46, tags: "", language: "arabic", known: false },
    { uuid: "807f5b26-6035-4803-812e-3199fc97b7e9", name: "Saudi Quran1", primaryUrl: "http://n01.radiojar.com/0tpy1h0kxtzuv", fallbackUrl: "", category: "QURAN", countryCode: "SA", bitrate: 0, popularity: 46, tags: "", language: "arabic", known: false },
    { uuid: "05fb8b90-3826-4719-b309-0ea5112a4921", name: "El Bahdja - الجزائر العاصمة", primaryUrl: "https://webradio3.tda.dz:8001/ElBahdja_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 64, popularity: 44, tags: "", language: "arabic", known: false },
    { uuid: "688986ac-3229-4adc-a96c-fcfe079328fe", name: "Adwaafm", primaryUrl: "http://stream.zeno.fm/5bxh2nh0x1zuv", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 43, tags: "", language: "arabic", known: false },
    { uuid: "500bc504-c47f-46bc-9c9a-16e15bee2353", name: "KnOOz FM", primaryUrl: "http://streaming.knoozfm.net:8000/knoozfm", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 43, tags: "", language: "arabic", known: false },
    { uuid: "f0d6b0a0-a6b7-4574-8921-f581357ea998", name: "Fairuz فيروز", primaryUrl: "https://stream.zeno.fm/xkhnk4vee18uv", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 43, tags: "", language: "arabic", known: false },
    { uuid: "5e1c31e7-7541-439a-9081-6f78508bb637", name: "إذاعة ياسر الدوسري", primaryUrl: "https://qurango.net/radio/yasser_aldosari", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 42, tags: "", language: "arabic", known: false },
    { uuid: "b1d17829-6522-481b-bb5c-839c3fd44353", name: "Radio Hits 88.2 Cairo", primaryUrl: "https://radiohits882.radioca.st/;", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 128, popularity: 41, tags: "", language: "arabic", known: false },
    { uuid: "6b77cd64-68ef-4984-91ec-9b7e77a26eda", name: "Radio Liban Libre", primaryUrl: "https://edge.mixlr.com/channel/qtqeb", fallbackUrl: "", category: "NEWS", countryCode: "LB", bitrate: 0, popularity: 41, tags: "", language: "arabic", known: false },
    { uuid: "a111b115-a103-4ed3-9181-c0eb9d4ef61d", name: "القران الكريم من القاهره1", primaryUrl: "http://n0e.radiojar.com/8s5u5tpdtwzuv", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 0, popularity: 40, tags: "", language: "arabic", known: false },
    { uuid: "0e39d9f4-8d44-409e-87c9-be51c7f91f67", name: "إذاعة القرآن الكريم", primaryUrl: "https://n0a.radiojar.com/0tpy1h0kxtzuv", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 0, popularity: 40, tags: "", language: "arabic", known: false },
    { uuid: "5db41df8-9921-421f-a618-84864ededf86", name: "إذاعة القرآن الكريم (القاهرة)", primaryUrl: "https://stream.radiojar.com/8s5u5tpdtwzuv", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 0, popularity: 38, tags: "", language: "arabic", known: false },
    { uuid: "a481fed1-b46b-44da-9f9b-cc5c4820189b", name: "إذاعة الأخبار كل 5 دقائق REPLAY NEWS - العربية", primaryUrl: "https://replaynewsar.ice.infomaniak.ch/replaynewsar-128.mp3", fallbackUrl: "", category: "NEWS", countryCode: "SA", bitrate: 128, popularity: 38, tags: "", language: "arabic", known: false },
    { uuid: "d3434153-afc3-4bdc-a98b-51ddca3990f1", name: "القارئ محمد أيوب", primaryUrl: "https://qurango.net/radio/mohammed_ayyub", fallbackUrl: "", category: "QURAN", countryCode: "IQ", bitrate: 128, popularity: 38, tags: "", language: "arabic", known: false },
    { uuid: "bcfe2aae-33fa-494a-a571-c3bbc8715c7d", name: "راديو الناس", primaryUrl: "https://cdna.streamgates.net/RadioNas/Live-Audio/icecast.audio", fallbackUrl: "", category: "NEWS", countryCode: "PS", bitrate: 64, popularity: 37, tags: "", language: "arabic", known: false },
    { uuid: "2c260d07-110c-473c-8a7f-df4b937b31a8", name: "Radio Manarat", primaryUrl: "https://listen.radioking.com/radio/252934/stream/297385", fallbackUrl: "", category: "NEWS", countryCode: "MA", bitrate: 192, popularity: 37, tags: "", language: "arabic", known: false },
    { uuid: "59f4eecd-7193-4bc3-8bc3-c1463d92b22d", name: "Alseraj Radio Quran", primaryUrl: "http://192.99.170.8:5550/stream", fallbackUrl: "", category: "QURAN", countryCode: "IQ", bitrate: 96, popularity: 37, tags: "", language: "arabic", known: false },
    { uuid: "06d8af5e-3f5a-4473-8f71-8343140a938f", name: "Algérie Internationale الجزائر الدولية", primaryUrl: "https://webradio3.tda.dz:8001/Internationale_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 64, popularity: 36, tags: "", language: "arabic", known: false },
    { uuid: "8a2c22b7-5350-4ed2-9781-a4f36eccd230", name: "صور من حياة الصحابة", primaryUrl: "https://qurango.net/radio/sahabah", fallbackUrl: "", category: "QURAN", countryCode: "DZ", bitrate: 128, popularity: 35, tags: "", language: "arabic", known: false },
    { uuid: "0cb24613-f3ba-41d6-9410-b3aaaa49c3f9", name: "Al rasheed fm", primaryUrl: "https://streaming.shoutcast.com/alrasheed-fm?1719771692024", fallbackUrl: "", category: "MUSIC", countryCode: "IQ", bitrate: 320, popularity: 35, tags: "", language: "arabic", known: false },
    { uuid: "45fbac1d-4838-4b51-b3eb-7c92fba5ef1a", name: "Hit Radio (Morocco)", primaryUrl: "https://hitradio-maroc.ice.infomaniak.ch/hitradio-maroc-128.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 34, tags: "", language: "arabic", known: false },
    { uuid: "448ba0a5-0c40-4f53-adc6-9d7f8b6e5f6a", name: "Darusalam Radio", primaryUrl: "https://streams.radio.co/s0975ec186/listen", fallbackUrl: "", category: "MUSIC", countryCode: "IQ", bitrate: 128, popularity: 34, tags: "", language: "arabic", known: false },
    { uuid: "e9744b90-2619-4a0c-93b4-5996e213068a", name: "Mp3Quran Main", primaryUrl: "https://qurango.net/radio/mix", fallbackUrl: "", category: "QURAN", countryCode: "SA", bitrate: 128, popularity: 32, tags: "", language: "arabic", known: false },
    { uuid: "e1393f04-f2eb-42d0-961f-e4de13a489b4", name: "radio maria iraq", primaryUrl: "http://dreamsiteradiocp4.com:8046/stream", fallbackUrl: "", category: "MUSIC", countryCode: "IQ", bitrate: 64, popularity: 32, tags: "", language: "arabic", known: false },
    { uuid: "a29b614a-6dd1-4e98-a7f7-a70eb21f82fb", name: "Al-bilad Radio/Yaqoubi", primaryUrl: "https://streamer.radio.co/sb38bf6e66/listen", fallbackUrl: "", category: "MUSIC", countryCode: "IQ", bitrate: 64, popularity: 31, tags: "", language: "arabic", known: false },
    { uuid: "d4e5cd2a-46da-447a-b92a-ef2abf64c329", name: "Ajyal - Palestine", primaryUrl: "https://streamer.mada.ps:8208/ajyal", fallbackUrl: "", category: "MUSIC", countryCode: "PS", bitrate: 128, popularity: 30, tags: "", language: "arabic", known: false },
    { uuid: "6a11c952-ea0c-4887-99a3-96b0139ee3fc", name: "Rap Lbeldi Maroc - Hip Hop Music from Morocco FR", primaryUrl: "https://stream.zeno.fm/ivdufrl7q0quv.m3u", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 29, tags: "", language: "arabic", known: false },
    { uuid: "66c753bc-f48c-4ad9-bdea-b272c7125fa8", name: "Skyrock Casablanca", primaryUrl: "https://icecast.skyrock.net/s/casa_aac_64k", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 29, tags: "", language: "arabic", known: false },
    { uuid: "82a04c36-2885-4e19-8c4b-6eda5a2ae90d", name: "Radio Tunisie Sfax", primaryUrl: "http://rtstream.tanitweb.com/sfax", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 28, tags: "", language: "arabic", known: false },
    { uuid: "65579ebd-da1b-412f-8da6-7a82853e5cc3", name: "إذاعة السنة", primaryUrl: "http://andromeda.shoutca.st:8189/live", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 32, popularity: 28, tags: "", language: "arabic", known: false },
    { uuid: "f2c7caa3-758b-4571-bdda-3f96bea62f13", name: "Aziz Mustaphi", primaryUrl: "https://d3ctxlq1ktw2nl.cloudfront.net/production/2022-0-3/240607172-44100-2-afb2485b0c30e.mp3", fallbackUrl: "", category: "QURAN", countryCode: "MA", bitrate: 0, popularity: 27, tags: "", language: "arabic", known: false },
    { uuid: "a5acbf43-ebe5-41b9-aba0-a21388484e39", name: "Med Radio", primaryUrl: "http://medradio.ice.infomaniak.ch/medradio-128.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 96, popularity: 27, tags: "", language: "arabic", known: false },
    { uuid: "22f064ab-be27-4e6e-8f34-8d6509c97007", name: "Chaine 1 القناة الأولى", primaryUrl: "https://webradio1.tda.dz:8001/Chaine1_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 64, popularity: 27, tags: "", language: "arabic", known: false },
    { uuid: "0d468016-910f-497f-b75e-26aed21086e9", name: "Quran Radio راديو القرآن - Abdulbasit Abdulsamad", primaryUrl: "https://radio.mp3islam.com/listen/abdulbasit/radio.mp3", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 64, popularity: 27, tags: "", language: "arabic", known: false },
    { uuid: "79e3bcb9-a030-4832-870e-0933f3bde362", name: "Radio Shanasheel", primaryUrl: "https://stream-152.zeno.fm/a33ka0d94p8uv", fallbackUrl: "", category: "MUSIC", countryCode: "IQ", bitrate: 128, popularity: 27, tags: "", language: "arabic", known: false },
    { uuid: "d241e555-9691-46fb-bbe2-7573b6d14e4d", name: "صوت كل لبنان", primaryUrl: "https://audio.osina.cloud:9323/stream", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 26, tags: "", language: "arabic", known: false },
    { uuid: "a6c8c797-e8b5-4a82-b5eb-756062a17c90", name: "Zitouna Zitouna FM", primaryUrl: "https://radio.radiotunisienne.tn/radiozaitouna", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 192, popularity: 25, tags: "", language: "arabic", known: false },
    { uuid: "4aea2140-cdf1-404b-bd3d-471c2f26fd12", name: "مختصر السيرة", primaryUrl: "https://qurango.net/radio/almukhtasar_fi_alsiyra", fallbackUrl: "", category: "QURAN", countryCode: "DZ", bitrate: 128, popularity: 25, tags: "", language: "arabic", known: false },
    { uuid: "78fe4c57-8bfd-481b-a368-292581e4f6f5", name: "Turkmen FM تركمان إف إم", primaryUrl: "https://radyo1.radyo-dinle.tc/8284/stream;", fallbackUrl: "", category: "NEWS", countryCode: "IQ", bitrate: 128, popularity: 25, tags: "", language: "arabic", known: false },
    { uuid: "6df8b651-cc31-44ed-bdbe-c602f85b707e", name: "Marrakechplus", primaryUrl: "https://cast5.my-control-panel.com/proxy/marrakec/stream", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 24, tags: "", language: "arabic", known: false },
    { uuid: "23754a47-c75e-11e8-a54a-52543be04c81", name: "Radio القارئ علي حجاج السويسي", primaryUrl: "http://live.mp3quran.net:9842/", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 24, tags: "", language: "arabic", known: false },
    { uuid: "b0d990d7-167a-44a1-830e-35dedaa2982d", name: "Al rasheed fm )Kirkuk)", primaryUrl: "https://streaming.shoutcast.com/alrasheed-kirkuk", fallbackUrl: "", category: "MUSIC", countryCode: "IQ", bitrate: 128, popularity: 24, tags: "", language: "arabic", known: false },
    { uuid: "29e75334-77df-4a21-bce3-258197bfbb18", name: "Chaine 3 القناة الثالثة", primaryUrl: "https://webradio1.tda.dz:8001/Chaine3_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 64, popularity: 23, tags: "", language: "arabic", known: false },
    { uuid: "77c8094d-6822-4221-be7b-1d54ab477d78", name: "Quran Radio راديو القرآن - Abdulrahman Alsudais", primaryUrl: "https://radio.mp3islam.com/listen/sudais/radio.mp3", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 64, popularity: 23, tags: "", language: "arabic", known: false },
    { uuid: "0a97035a-bd76-46da-acbb-894f0876da1f", name: "Quran Radio راديو القرآن - Muhammad Al-Minshawi", primaryUrl: "https://radio.mp3islam.com/listen/minshawi/radio.mp3", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 64, popularity: 23, tags: "", language: "arabic", known: false },
    { uuid: "f221e5e9-8d01-4906-bd6b-2e8985dbbea7", name: "رحبانيات", primaryUrl: "https://stream.zeno.fm/4g8xck48kfhvv", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 23, tags: "", language: "arabic", known: false },
    { uuid: "2f91a94c-c986-4799-8553-2258069628c6", name: "Adeem", primaryUrl: "https://usa19.fastcast4u.com/adeem", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 23, tags: "", language: "arabic", known: false },
    { uuid: "7cca8bd6-a173-4fca-9a74-ca1e15a631e5", name: "Quran Radio From Sharjah", primaryUrl: "https://l3.itworkscdn.net/smcquranlive/quranradiolive/icecast.audio", fallbackUrl: "", category: "QURAN", countryCode: "AE", bitrate: 56, popularity: 23, tags: "", language: "arabic", known: false },
    { uuid: "5bdf14c4-12dd-49c0-a931-53fc5898b3bc", name: "Radio Tunisie Jeunes", primaryUrl: "http://rtstream.tanitweb.com/jeunes", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 22, tags: "", language: "arabic", known: false },
    { uuid: "b343319c-f06d-4565-908a-3c80926cd2b6", name: "Radio Tunisie Kef", primaryUrl: "http://rtstream.tanitweb.com/kef", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 22, tags: "", language: "arabic", known: false },
    { uuid: "5f344328-0823-42fa-b2c5-39375543bbee", name: "Zaman FM زمان", primaryUrl: "https://webradio2.tda.dz:8001/Zamen_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 64, popularity: 22, tags: "", language: "arabic", known: false },
    { uuid: "5b4e5f2a-6069-4562-9e0a-945bde91a83f", name: "Radio Tunisie Monastir", primaryUrl: "http://rtstream.tanitweb.com/monastir", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 21, tags: "", language: "arabic", known: false },
    { uuid: "2cb54ac0-37d7-4742-8259-18e07a6cb2c7", name: "محمود خليل الحصري - حفص عن عاصم مرتل - mp3quran", primaryUrl: "https://qurango.net/radio/mahmoud_khalil_alhussary", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 21, tags: "", language: "arabic", known: false },
    { uuid: "5c04a6d1-2444-4ae1-8a2b-024cf9a0d033", name: "Quran Radio راديو القرآن - Mishary Alafasy - مشا", primaryUrl: "https://radio.mp3islam.com/listen/mishary/radio.mp3", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 64, popularity: 21, tags: "", language: "arabic", known: false },
    { uuid: "7defb860-5ce0-481d-a827-1330c6611686", name: "Tripoli FM Lebanon", primaryUrl: "https://cast4.my-control-panel.com/proxy/tripolifm/stream", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 21, tags: "", language: "arabic", known: false },
    { uuid: "368ef657-f8b5-47ce-b578-337e401cc744", name: "Voice of Lebanon (Sawtlebnan) Radio", primaryUrl: "https://l3.itworkscdn.net/itwaudio/9054/stream", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 21, tags: "", language: "arabic", known: false },
    { uuid: "ebbd977c-e0a9-4650-8c3b-9d97b37988a8", name: "A f R o 505 / By _MX2", primaryUrl: "https://stream.zeno.fm/7wozjkc8x0vvv", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 20, tags: "", language: "arabic", known: false },
    { uuid: "6787cf45-924e-4cac-84e1-7f6675712ae3", name: "Radio Express FM", primaryUrl: "https://expressfm.ice.infomaniak.ch/expressfm-64.mp3", fallbackUrl: "", category: "NEWS", countryCode: "TN", bitrate: 64, popularity: 19, tags: "", language: "arabic", known: false },
    { uuid: "d706e494-cd28-46ad-ac79-ca49aa0f1a9f", name: "راديو فن Fenn Radio", primaryUrl: "https://stream.zeno.fm/76e9f1gsduhvv", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 19, tags: "", language: "arabic", known: false },
    { uuid: "df238336-9ab7-4697-840a-6f10c3ddde3e", name: "صوت الغد استراليا", primaryUrl: "http://s4.voscast.com:7648/;stream.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "DZ", bitrate: 80, popularity: 19, tags: "", language: "arabic", known: false },
    { uuid: "f07420b2-244c-45fd-aa5a-321f4dbf8df6", name: "Coran القرآن الكريم", primaryUrl: "https://webradio1.tda.dz:8001/Coran_64K.mp3", fallbackUrl: "", category: "QURAN", countryCode: "DZ", bitrate: 64, popularity: 19, tags: "", language: "arabic", known: false },
    { uuid: "26c23dc4-1b31-473c-a9c4-746d9c7ae77b", name: "Tarab Radio", primaryUrl: "https://stream.zeno.fm/fy8achbq97zuv", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 18, tags: "", language: "arabic", known: false },
    { uuid: "e2d75665-c4a9-4433-b953-3a5346ae5fb6", name: "Fred Film Radio(لغة عربية)", primaryUrl: "https://s10.webradio-hosting.com/proxy/fredradioar/stream", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 18, tags: "", language: "arabic", known: false },
    { uuid: "8a753c11-ee4a-42bf-8e4d-6d852cac9030", name: "القارئ علي الحذيفي رواية قالون", primaryUrl: "https://qurango.net/radio/ali_alhuthaifi_qalon", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 18, tags: "", language: "arabic", known: false },
    { uuid: "4ce99c8e-47bd-4d4d-896b-e9d5310384d3", name: "Quran Radio راديو القرآن - Kuwait الكويت", primaryUrl: "https://radio.mp3islam.com/listen/quran_radio/radio.mp3", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 64, popularity: 18, tags: "", language: "arabic", known: false },
    { uuid: "38ebb90e-1fce-4884-9d60-98dab8fc4520", name: "ذكريات", primaryUrl: "https://stream.zeno.fm/ctmapun6ot5vv", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 18, tags: "", language: "arabic", known: false },
    { uuid: "cd5a924a-29a6-4114-84ef-8effa816cfa7", name: "FM المستقبل", primaryUrl: "https://edge.mixlr.com/channel/vnvcj", fallbackUrl: "", category: "MUSIC", countryCode: "IQ", bitrate: 0, popularity: 18, tags: "", language: "arabic", known: false },
    { uuid: "125ec344-a2a1-4e36-8f66-77401f457d20", name: "Aswat FM", primaryUrl: "https://broadcast.ice.infomaniak.ch/aswat-high.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 17, tags: "", language: "arabic", known: false },
    { uuid: "38ba2533-565e-4ebf-a76b-4498d6ce1203", name: "Oran وهران", primaryUrl: "https://webradio2.tda.dz:8001/Oran_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 64, popularity: 17, tags: "", language: "arabic", known: false },
    { uuid: "10f95a01-1066-471b-89c5-f9092e73a373", name: "Quran Radio راديو القرآن - Saud Al-Shuraim سعود", primaryUrl: "https://radio.mp3islam.com/listen/alshuraim/radio.mp3", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 64, popularity: 17, tags: "", language: "arabic", known: false },
    { uuid: "4a116b98-e289-4630-9b62-b93a67159c7e", name: "Anghamy", primaryUrl: "https://radio.avazfarsi.com:8010/radio.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 17, tags: "", language: "arabic", known: false },
    { uuid: "9e88f54e-7a06-4225-a01e-9644a48d8499", name: "Medina FM Tarab (128 kbps)", primaryUrl: "https://medinatarab.ice.infomaniak.ch/medinatarab-128.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 16, tags: "", language: "arabic", known: false },
    { uuid: "7fcb9e0d-803e-4c4a-8c50-a7679084546d", name: "SBA Riyadh Radio 91.5 FM", primaryUrl: "https://stream-154.zeno.fm/pdeizhgrtrstv", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 16, tags: "", language: "arabic", known: false },
    { uuid: "7f8f3ce0-4780-4557-ba61-2df564ef3f75", name: "Medina FM Nostalgie (128 kbps)", primaryUrl: "https://medinanachat.ice.infomaniak.ch/medinanachat-128.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 15, tags: "", language: "arabic", known: false },
    { uuid: "0502df2d-a93e-4d8a-8547-ae9d341a1128", name: "MEDINA FM", primaryUrl: "https://medinafm.ice.infomaniak.ch/medinafm-64.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 64, popularity: 15, tags: "", language: "arabic", known: false },
    { uuid: "050966be-de77-49f0-bad9-6ef200d219f3", name: "Ambiance FM", primaryUrl: "https://stream.zeno.fm/0rehsamc9xxtv", fallbackUrl: "", category: "NEWS", countryCode: "TN", bitrate: 192, popularity: 15, tags: "", language: "arabic", known: false },
    { uuid: "89a65395-9af4-4c9d-9300-d765029785e8", name: "Radio Misk", primaryUrl: "https://live.misk.art/stream", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 192, popularity: 15, tags: "", language: "arabic", known: false },
    { uuid: "2bff5955-3a88-46cd-92e9-57033683a7f0", name: "Setif سطيف", primaryUrl: "https://webradio3.tda.dz:8001/Setif_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 64, popularity: 15, tags: "", language: "arabic", known: false },
    { uuid: "279b39aa-6e80-4e2a-bb3f-f68f8cf9b6c8", name: "Tlemcen تلمسان", primaryUrl: "https://webradio1.tda.dz:8001/Tlemcen_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 64, popularity: 15, tags: "", language: "arabic", known: false },
    { uuid: "d275b356-1068-4012-a46a-46e340e12ab9", name: "Fame FM Lebanon", primaryUrl: "https://l3.itworkscdn.net/itwaudio/9044/stream", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 15, tags: "", language: "arabic", known: false },
    { uuid: "b8ab13a0-938d-4600-92bc-44d2a85b13ae", name: "Radio Duhok FM", primaryUrl: "https://stream-149.zeno.fm/unwq3jdyhmbuv", fallbackUrl: "", category: "NEWS", countryCode: "IQ", bitrate: 128, popularity: 15, tags: "", language: "arabic", known: false },
    { uuid: "e4113924-a6ce-4a48-8781-97507dda0d67", name: "mosaiquefm", primaryUrl: "https://radio.mosaiquefm.net/mosalive", fallbackUrl: "", category: "NEWS", countryCode: "TN", bitrate: 90, popularity: 14, tags: "", language: "arabic", known: false },
    { uuid: "8fd71912-47d5-49ec-b587-067cdd656218", name: "Radio Tunisie Tataouine", primaryUrl: "http://rtstream.tanitweb.com/tataouine", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 64, popularity: 14, tags: "", language: "arabic", known: false },
    { uuid: "26eb1c40-f314-4976-8969-a70d1fcce1fc", name: "Hala FM", primaryUrl: "https://listen-halafm.sharp-stream.com/halafmlow.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "OM", bitrate: 64, popularity: 14, tags: "", language: "arabic", known: false },
    { uuid: "6a329c2f-af42-4224-83b4-13358dbe5db5", name: "Maura ✨ ( AR )", primaryUrl: "https://stream.zeno.fm/fpasc3up26ptv", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 14, tags: "", language: "arabic", known: false },
    { uuid: "e4df1ea1-b0c6-43b1-a0fb-fa685a42d72f", name: "Quran Radio راديو القرآن - Maher Al Mueaqly - ما", primaryUrl: "https://radio.mp3islam.com/listen/maher/radio.mp3", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 64, popularity: 14, tags: "", language: "arabic", known: false },
    { uuid: "234b8531-a493-4f79-8837-f8f2ad9bbc6b", name: "Virgin Radio Lebanon Stars", primaryUrl: "https://stream.zeno.fm/h66yxnapga0uv", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 14, tags: "", language: "arabic", known: false },
    { uuid: "e68ac2a4-9abc-4eea-a390-b0426864e503", name: "ZINE BLADI", primaryUrl: "https://asmaki.ma:8000/radio.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 96, popularity: 13, tags: "", language: "arabic", known: false },
    { uuid: "0ce6c6eb-a206-4cfd-ac95-5128424b5948", name: "Corleone Radio", primaryUrl: "http://198.244.189.86:8000/listen/corleone", fallbackUrl: "", category: "MUSIC", countryCode: "EG", bitrate: 0, popularity: 13, tags: "", language: "arabic", known: false },
    { uuid: "344e6e4e-a7b3-11e8-a8ed-52543be04c81", name: "RadioSunna", primaryUrl: "http://andromeda.shoutca.st:8189/stream", fallbackUrl: "", category: "NEWS", countryCode: "SA", bitrate: 32, popularity: 13, tags: "", language: "arabic", known: false },
    { uuid: "3da33a8b-06b7-4fd7-8001-121cee38e651", name: "HOUB Radio (حُب)", primaryUrl: "http://nap.casthost.net:8028/stream", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 64, popularity: 13, tags: "", language: "arabic", known: false },
    { uuid: "cbf8067e-8c54-4d62-81b9-df6589d782b1", name: "Sawt El Ghad", primaryUrl: "https://l3.itworkscdn.net/itwaudio/9030/stream", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 13, tags: "", language: "arabic", known: false },
    { uuid: "cb9d0283-160c-40a6-8322-f59f90bc3975", name: "Radio alHara", primaryUrl: "https://stream.radioalhara.net/ra", fallbackUrl: "", category: "MUSIC", countryCode: "PS", bitrate: 128, popularity: 12, tags: "", language: "arabic", known: false },
    { uuid: "a9d04fb3-fd0e-4dbd-877e-5dd2d5d6e618", name: "Radio Sawt Alamal", primaryUrl: "https://stream.zeno.fm/qgw73bqh5qruv", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 12, tags: "", language: "arabic", known: false },
    { uuid: "716b3744-d497-4dcf-a0fb-d4563c3e58b1", name: "radio achkid fm راديوأشكيدإف إم", primaryUrl: "https://stream.zeno.fm/7nqu31p6xg0uv", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 12, tags: "", language: "arabic", known: false },
    { uuid: "a0357a8d-b426-4e35-8d34-3fdd1f17115b", name: "radio yan plus", primaryUrl: "https://stream.zeno.fm/7nqu31p6xg0uv.m3u", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 12, tags: "", language: "arabic", known: false },
    { uuid: "bd0dfbbf-7594-4980-98de-8311c4522b2e", name: "Capradio", primaryUrl: "https://listen.radioking.com/radio/710810/stream/776366", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 192, popularity: 12, tags: "", language: "arabic", known: false },
    { uuid: "3194ab53-148e-482c-b392-6b33ad40d9b1", name: "Medina FM Andalou (128 kbps)", primaryUrl: "https://medinaandalou.ice.infomaniak.ch/medinaandalou-128.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 12, tags: "", language: "arabic", known: false },
    { uuid: "e1d85f39-df4d-4e56-a789-a0773b7dda69", name: "SBA Radio Call Of Islam 107.5 FM", primaryUrl: "http://104.7.66.64:8010/;?icy=http", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 320, popularity: 12, tags: "", language: "arabic", known: false },
    { uuid: "eccd4ceb-feb4-4994-8518-f2ee78d2c360", name: "lbi Zaman", primaryUrl: "http://dione.shoutca.st:8165/;", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 12, tags: "", language: "arabic", known: false },
    { uuid: "f27afa46-8650-4a88-a632-6f46e61427b2", name: "Sawt El Ghad - Tarab", primaryUrl: "https://sawtelghad.org:7007/;", fallbackUrl: "", category: "MUSIC", countryCode: "PS", bitrate: 128, popularity: 11, tags: "", language: "arabic", known: false },
    { uuid: "9f26b055-db67-447a-81e6-ddb5c8c40dc3", name: "Radio Adwaa FM 6", primaryUrl: "https://stream.zeno.fm/tvxvud2kpm0uv", fallbackUrl: "", category: "NEWS", countryCode: "MA", bitrate: 128, popularity: 11, tags: "", language: "arabic", known: false },
    { uuid: "0bb1d938-2f1e-4ff5-8c43-2fa25ae65f68", name: "U RADIO", primaryUrl: "https://uradio-aac.ice.infomaniak.ch/uradio.aac", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 128, popularity: 11, tags: "", language: "arabic", known: false },
    { uuid: "c6c77aaf-e899-463d-a9f5-a556180a4f82", name: "Tizi Ouzou تيزي وزو", primaryUrl: "https://webradio2.tda.dz:8001/TiziOuzou_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 64, popularity: 11, tags: "", language: "arabic", known: false },
    { uuid: "9f2d0bd8-689a-425f-8456-7ed239d6990e", name: "عظمها وافي و غاربها طويل", primaryUrl: "https://stream.zeno.fm/9i7dabxxfwsuv", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 11, tags: "", language: "arabic", known: false },
    { uuid: "4bf7b172-016d-41c8-81cb-facdf87ca46e", name: "Remix S-DJ", primaryUrl: "https://stream.zeno.fm/pdeizhgrtrstv", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 11, tags: "", language: "arabic", known: false },
    { uuid: "f25fc24b-c5b9-4800-8d51-3e26bb89a99d", name: "Quran Radio راديو القرآن - Yasser AlDosari - ياس", primaryUrl: "https://radio.mp3islam.com/listen/yaser/radio.mp3", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 64, popularity: 11, tags: "", language: "arabic", known: false },
    { uuid: "5e489ee4-30fe-4933-a0b8-3d739a866d30", name: "راديو عالبال", primaryUrl: "https://albal-lbnet2.radioca.st/stream.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 11, tags: "", language: "arabic", known: false },
    { uuid: "88ac049f-d017-11e9-a861-52543be04c81", name: "lbi Radio", primaryUrl: "http://andromeda.shoutca.st:8192/;stream.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 11, tags: "", language: "arabic", known: false },
    { uuid: "7d1378e3-8e91-46f9-a98d-6f7426e60a3c", name: "Sawt El Mada", primaryUrl: "http://audiostreaming.itworkscdn.com:9018/stream", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 11, tags: "", language: "arabic", known: false },
    { uuid: "47c10505-5ce4-4b1b-b6f0-16c33bb6b4e8", name: "Virgin Radio Lebanon", primaryUrl: "https://stream.zeno.fm/dwxw3p9vea0uv", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 11, tags: "", language: "arabic", known: false },
    { uuid: "b330bfd3-c757-4fea-8bee-ef49f97af878", name: "Radio Tunisie Culturelle", primaryUrl: "http://rtstream.tanitweb.com/culturelle", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 10, tags: "", language: "arabic", known: false },
    { uuid: "dd6f040c-77fe-4b15-8801-6fce689ebc20", name: "Tamanrasset تمنراست", primaryUrl: "https://webradio1.tda.dz:8001/Tamanrasset_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 64, popularity: 10, tags: "", language: "arabic", known: false },
    { uuid: "3a2be197-6831-4db2-9e4e-0208b714e738", name: "Chaine 2 القناة الثانية", primaryUrl: "https://webradio2.tda.dz:8001/Chaine2_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 64, popularity: 10, tags: "", language: "arabic", known: false },
    { uuid: "a1887b6d-dee2-48be-8770-cbdef7951a3e", name: "Culture", primaryUrl: "https://webradio3.tda.dz:8001/Culture_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 64, popularity: 10, tags: "", language: "arabic", known: false },
    { uuid: "46d54920-eece-4b59-b6f9-9ef271c41371", name: "Ayat Al Quran Radio", primaryUrl: "https://stream.zeno.fm/1fatuk10fkhvv", fallbackUrl: "", category: "QURAN", countryCode: "LB", bitrate: 128, popularity: 10, tags: "", language: "arabic", known: false },
    { uuid: "7e347b79-508b-4b0c-a7a5-a635ad2af3d0", name: "Rotana Radio 99.9 FM Jordan", primaryUrl: "http://45.63.116.205:8000/stream", fallbackUrl: "", category: "MUSIC", countryCode: "JO", bitrate: 0, popularity: 10, tags: "", language: "arabic", known: false },
    { uuid: "3537590f-8d22-40b9-82d8-cd7dd1a5de44", name: "Radio Star Maroc FM", primaryUrl: "https://a2.asurahosting.com:6100/radio.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 320, popularity: 9, tags: "", language: "arabic", known: false },
    { uuid: "948f53ca-9253-4383-8339-83aea217ec17", name: "MFM RADIO", primaryUrl: "https://a5.asurahosting.com:7980/radio.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "MA", bitrate: 0, popularity: 9, tags: "", language: "arabic", known: false },
    { uuid: "06236334-a972-436b-bb36-7f0c91765520", name: "Radio Elyssa - راديو عليسة", primaryUrl: "https://tv.elyssatv.com/radio/live.mp3", fallbackUrl: "", category: "NEWS", countryCode: "TN", bitrate: 0, popularity: 9, tags: "", language: "arabic", known: false },
    { uuid: "848d2bf5-2e14-4e87-8184-21aa5b655d59", name: "Radio Tunisie Gafsa", primaryUrl: "http://rtstream.tanitweb.com/gafsa", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 128, popularity: 9, tags: "", language: "arabic", known: false },
    { uuid: "ac734bf8-3abd-4edd-be9f-507a58274b68", name: "Illizi اليزي", primaryUrl: "https://webradio2.tda.dz:8001/Illizi_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 64, popularity: 9, tags: "", language: "arabic", known: false },
    { uuid: "2a972250-15f4-426c-8eae-f282a3dde53f", name: "Mostaganem مستغانم", primaryUrl: "https://webradio3.tda.dz:8001/Mostaganem_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 64, popularity: 9, tags: "", language: "arabic", known: false },
    { uuid: "1fa1ad84-e008-4af2-9a61-1b37daeb34ff", name: "Bordj Bou Arreridj برج بوعريريج", primaryUrl: "https://webradio3.tda.dz:8001/BordjBouArreridj_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 64, popularity: 9, tags: "", language: "arabic", known: false },
    { uuid: "8efd6d38-d14b-4a09-853c-10df4d012de7", name: "Ghardaïa غرداية", primaryUrl: "https://webradio1.tda.dz:8001/Ghardaia_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 64, popularity: 9, tags: "", language: "arabic", known: false },
    { uuid: "4caa43a0-ed29-44f4-bb5d-47042a46c6c4", name: "Annaba عنابة", primaryUrl: "https://webradio1.tda.dz:8001/Annaba_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 64, popularity: 9, tags: "", language: "arabic", known: false },
    { uuid: "d1c087ac-267a-425e-8dc5-6ba4644e5249", name: "Constantine قسنطينة", primaryUrl: "https://webradio3.tda.dz:8001/Constantine_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 64, popularity: 9, tags: "", language: "arabic", known: false },
    { uuid: "067ee4b5-2d94-4888-8eb4-cc5b17da1fff", name: "مقدمة رسالة ابن أبي زيد القيرواني1", primaryUrl: "https://www.al-badr.net/download/esound/choroohat/abuzaid_alkirawani/001.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 0, popularity: 9, tags: "", language: "arabic", known: false },
    { uuid: "ce30edd2-f94a-4e37-8409-c8df9dd1330a", name: "إذاعة القرآن الكريم (مكة المكرمة)", primaryUrl: "https://n05.radiojar.com/4wqre23fytzuv", fallbackUrl: "", category: "QURAN", countryCode: "SA", bitrate: 0, popularity: 9, tags: "", language: "arabic", known: false },
    { uuid: "dd1a2ef3-0136-4ed7-8215-3a7b3564d044", name: "Quran Radio راديو القرآن - Ali Alhuthaifi - علي", primaryUrl: "https://radio.mp3islam.com/listen/alhuthaifi/radio.mp3", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 64, popularity: 9, tags: "", language: "arabic", known: false },
    { uuid: "bd561679-5317-4a66-8393-c6f06970792d", name: "Yarmouk University FM", primaryUrl: "https://yufm.yu.edu.jo/broadwave.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "JO", bitrate: 0, popularity: 9, tags: "", language: "arabic", known: false },
    { uuid: "ef2cb53f-3f77-482c-942b-a2826cb324af", name: "Al Wisal", primaryUrl: "http://uk7.internet-radio.com:8067/;", fallbackUrl: "", category: "NEWS", countryCode: "OM", bitrate: 96, popularity: 8, tags: "", language: "arabic", known: false },
    { uuid: "7d3a9ce0-e63b-4518-b8cf-c0513727c376", name: "Bejaïa بجاية", primaryUrl: "https://webradio1.tda.dz:8001/Bejaia_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 64, popularity: 8, tags: "", language: "arabic", known: false },
    { uuid: "cc6747c6-64ff-4ac3-88f5-08791402ad5e", name: "Biskra بسكرة", primaryUrl: "https://webradio3.tda.dz:8001/Biskra_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 64, popularity: 8, tags: "", language: "arabic", known: false },
    { uuid: "aa7e35fb-8b75-425f-8710-3cbd64eefd7d", name: "Djelfa الجلفة", primaryUrl: "https://webradio2.tda.dz:8001/Djelfa_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 64, popularity: 8, tags: "", language: "arabic", known: false },
    { uuid: "25b1c6d3-ae89-47e0-8d60-71cf87f896a8", name: "محمود خليل الحصري - حفص عن عاصم مرتل - mp3quran", primaryUrl: "https://backup.qurango.net/radio/mahmoud_khalil_alhussary", fallbackUrl: "", category: "QURAN", countryCode: "EG", bitrate: 128, popularity: 8, tags: "", language: "arabic", known: false },
    { uuid: "b568ab8e-9d51-471d-8708-04deade2463d", name: "Islamic Tagalog Radio", primaryUrl: "https://islamicbulletin.site:8030/stream", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 320, popularity: 8, tags: "", language: "arabic", known: false },
    { uuid: "efd4a052-0170-46c7-b8b9-c812da9a1c85", name: "asmaaxx/ʙʏ _MX2", primaryUrl: "https://stream.zeno.fm/nkukhjz4vgtuv", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 8, tags: "", language: "arabic", known: false },
    { uuid: "e9b972cd-408d-4540-b3d1-78eb2d084edb", name: "Mohamed Tablaoui", primaryUrl: "http://live.mp3quran.net:9834/", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 8, tags: "", language: "arabic", known: false },
    { uuid: "00714981-4dc7-4515-9210-b1963261e136", name: "OFW Radio", primaryUrl: "https://s74.radiolize.com:8020/radio.mp3?t=1780673757445", fallbackUrl: "", category: "MUSIC", countryCode: "KW", bitrate: 96, popularity: 8, tags: "", language: "arabic", known: false },
    { uuid: "12e6be24-23f1-42ea-8d6e-b816f901aba6", name: "Aghani Aghani 87.9 FM", primaryUrl: "https://streaming.nrjaudio.fm/ou6pfgxp336f", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 8, tags: "", language: "arabic", known: false },
    { uuid: "cafebc2c-32b8-4499-bfbf-d26feb9a8751", name: "راديو عالبال", primaryUrl: "https://albal-lbnet2.radioca.st/stream", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 128, popularity: 8, tags: "", language: "arabic", known: false },
    { uuid: "d8ec020b-195f-4309-8385-9224d54c9262", name: "Jijel جيجل", primaryUrl: "https://webradio2.tda.dz:8001/Jijel_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 64, popularity: 7, tags: "", language: "arabic", known: false },
    { uuid: "b3174d2d-98a5-47b9-9716-38ba7955d600", name: "Aïn Témouchent عين تموشنت", primaryUrl: "https://webradio1.tda.dz:8001/AinTimouchent_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 64, popularity: 7, tags: "", language: "arabic", known: false },
    { uuid: "3755cc7b-1da6-40f7-b618-5a8a6f82490f", name: "Jow Radio - Jow Charki", primaryUrl: "https://azuracast.dataplayapps.com/listen/jow_oriental/radio.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 192, popularity: 7, tags: "", language: "arabic", known: false },
    { uuid: "f897f6a8-e423-461f-b226-a7393d21ecda", name: "Béchar بشار", primaryUrl: "https://webradio1.tda.dz:8001/Bechar_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 64, popularity: 7, tags: "", language: "arabic", known: false },
    { uuid: "406b6273-5994-41e2-b130-8014230873db", name: "_MX2✨", primaryUrl: "https://stream.zeno.fm/66gp9qukxaquv", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 7, tags: "", language: "arabic", known: false },
    { uuid: "c3eb7a69-e0d0-47ea-b39c-75515fc10010", name: "Salah Boukhater", primaryUrl: "http://live.mp3quran.net:9878/;stream.mp3", fallbackUrl: "", category: "MUSIC", countryCode: "SA", bitrate: 128, popularity: 7, tags: "", language: "arabic", known: false },
    { uuid: "7385669d-fb03-4555-a21f-e2283e0c648c", name: "Sky ABC Radio 96.6 FM", primaryUrl: "https://stream-02.surfernetwork.com/iysqd3wuj38uv", fallbackUrl: "", category: "NEWS", countryCode: "SA", bitrate: 128, popularity: 7, tags: "", language: "arabic", known: false },
    { uuid: "49d1b1e2-7da4-4b6c-a95b-3129fd4afe2e", name: "Sunan ad-Darimi", primaryUrl: "https://serverkw.quran-uni.com:8358/;", fallbackUrl: "", category: "QURAN", countryCode: "KW", bitrate: 128, popularity: 7, tags: "", language: "arabic", known: false },
    { uuid: "eec68cdb-9fde-4a44-81d1-4e752f05fdf5", name: "Radio Liban", primaryUrl: "https://media2.streambrothers.com:2020/stream/8194", fallbackUrl: "", category: "MUSIC", countryCode: "LB", bitrate: 64, popularity: 7, tags: "", language: "arabic", known: false },
    { uuid: "1ee6530c-1890-4fbe-807f-69135a8c6f49", name: "Al Arabiya 99 FM", primaryUrl: "https://stream.radiojar.com/s1mygd1frxquv", fallbackUrl: "", category: "MUSIC", countryCode: "AE", bitrate: 0, popularity: 7, tags: "", language: "arabic", known: false },
    { uuid: "49545fc3-653c-4e36-b250-359024629b45", name: "Radio Azawan (192 kbps)", primaryUrl: "https://az-maroc.ice.infomaniak.ch/az-maroc-128.mp3", fallbackUrl: "", category: "NEWS", countryCode: "MA", bitrate: 192, popularity: 6, tags: "", language: "arabic", known: false },
    { uuid: "bde2191e-4736-41b9-bbe7-816521af8662", name: "Radio Azrou Live", primaryUrl: "https://stream.zeno.fm/nzdw7gqc0s8uv", fallbackUrl: "", category: "NEWS", countryCode: "MA", bitrate: 128, popularity: 6, tags: "", language: "arabic", known: false },
    { uuid: "e8676934-ceb6-47ce-8360-9a06a6f21fa3", name: "Radio ElWassat", primaryUrl: "https://radiosbz.bfi-tech.fr/live", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 0, popularity: 6, tags: "", language: "arabic", known: false },
    { uuid: "5ec5f732-cb9c-408b-8391-b583962984d8", name: "oxygene fm", primaryUrl: "http://stream.radios-arra.fr:8000/oxygenefm", fallbackUrl: "", category: "MUSIC", countryCode: "TN", bitrate: 192, popularity: 6, tags: "", language: "arabic", known: false },
    { uuid: "c49800f2-d0d6-4d58-b4d9-4672415f0bd9", name: "Ouargla ورقلة", primaryUrl: "https://webradio1.tda.dz:8001/Ouargla_64K.mp3", fallbackUrl: "", category: "NEWS", countryCode: "DZ", bitrate: 64, popularity: 6, tags: "", language: "arabic", known: false },
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

async function proxyStream(streamUrl) {
  const videoExtensions = [".mp4", ".mkv", ".avi", ".mov", ".webm", ".flv"];
  if (videoExtensions.some(ext => streamUrl.toLowerCase().includes(ext))) {
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

    if (contentType.toLowerCase().includes("video/")) {
      return new Response("Video blocked", { status: 403 });
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