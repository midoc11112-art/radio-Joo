/* RADIO-JOO Autonomous Flagship Worker (v3) - Open Arab/Egypt/Quran Discovery + MP3Quran + Self-Healing */
const MIRRORS = [
  "https://de1.api.radio-browser.info",
  "https://fi1.api.radio-browser.info",
  "https://at1.api.radio-browser.info",
  "https://de2.api.radio-browser.info",
  "https://nl1.api.radio-browser.info"
];
const ARAB_CC = new Set("EG SA AE KW QA BH OM YE IQ SY LB JO PS LY TN DZ MA MR SD SO DJ KM".split(" "));
const TTL = 6 * 3600 * 1000, THROTTLE = 30000, MAXSRV = 15, BADMS = 3 * 86400000, HSMS = 7 * 86400000;
let MEM = null, BUILDING = null, LASTREF = 0;

function norm(s) {
  return String(s || "").toLowerCase()
    .replace(/[\u064B-\u065F\u0670\u0640]/g, "")
    .replace(/[\u0623\u0625\u0622\u0671]/g, "\u0627")
    .replace(/\u0629/g, "\u0647")
    .replace(/\u0649/g, "\u064A")
    .replace(/[^a-z0-9\u0600-\u06FF ]+/g, " ")
    .replace(/\s+/g, " ").trim();
}
function h32(s) {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
  return (h >>> 0).toString(36);
}
const ukey = u => String(u).replace(/^https?:\/\//i, "").replace(/\/+$/, "").toLowerCase();

const RE_QURAN = /qur ?an|koran|kuran|tilawa|tarteel|tajweed|islam|muslim|sunnah|hadith|fatwa|nasheed|makkah|mecca|medina|qurango|mp3quran|قرآن|قران|تلاوة|ترتيل|تجويد|اسلام|فتاوى|ابتهالات|اناشيد|الحرم|المنشاوي|الحصري|عبد الباسط|المعيقلي|العفاسي|السديس|الشريم|الغامدي|الحذيفي|الدوسري|القطامي|الطبلاوي|البنا|مصطفى اسماعيل|الاجيمي|الشاطري/;
const RE_SPORTS = /sport|riyada|riyadah|football|soccer|kooora|kora|رياضة|رياضه|كورة|كوره|ملاعب|الدوري|اون سبورت|الشباب والرياضة/;
const RE_NEWS = /news|akhbar|jazeera|arabiya|hadath|france ?24|dw arabic|rt arabic|monte carlo|sawa|alhurra|sputnik|trt|alaraby|bbc arab|sky news|asharq|qahera news|extra news|اخبار|نشرة|الجزيرة|العربية|الحدث|سكاي نيوز|مونت كارلو|فرانس 24|سبوتنيك|الحرة|سوا|الميادين|الشرق|القاهرة الاخبارية|اكسترا نيوز|راديو مصر/;
const RE_TALK = /talk|hewar|discussion|call ?in|حوار|برنامج|توك|نقاش|مصر الجديدة|ميدي 1/;
const RE_TARAB = /tarab|tarb|oriental|golden (era|age)|vintage|andalus|muwashah|classic arabic|kalthoum|halim|fairuz|fayrouz|wahab|atrash|asmahan|warda|najat|طرب|الزمن الجميل|زمان|قدیم|موشحات|ام كلثوم|عبد الحليم|فيروز|عبد الوهاب|فريد الاطرش|اسمهان|وردة|نجاة|صباح فخري|سيد درويش/;
const RE_MUSIC = /music|musique|pop|hits|song|dance|hip hop|rap|rock|jazz|mahragan|chaabi|shaabi|top ?40|lounge|rnb|nogoum|nile fm|mega fm|nagham|9090|mix fm|nrj|rotana|mbc fm|mosaique|tes3enat|aghani|masriyat|rehab|hawak|موسيقى|مهرجانات|شعبي|اغاني|نجوم|نايل|ميجا|نغم|روتانا|تسعينات|مصريات|رحاب|هواك|ميكس|انرجي/;
const RE_DROP = /christian|gospel|bible|church|catholic|orthodox|corleone|x_x|s_o_l_l_y|solly|nfeshal|test stream|مسيحي|كنيسة|انجيل/;
const RE_EGYPT = /egypt|misr|masr|cairo|alexandria|nile|ertu|nogoum|mega|nagham|9090|shaabi|masriyat|tes3enat|rehab|hawak|barnamg|sawt el arab|shabab|on sport|مصر|القاهرة|الاسكندرية|النيل|نجوم|ميجا|نغم|شعبي|مصريات|تسعينات|رحاب|هواك|البرنامج العام|صوت العرب|الشباب والرياضة|اون سبورت/;

/* Strict Pure-Audio Guard: ZERO TV, ZERO Video */
const VID_EXT = /\.(?:mp4|m4v|mkv|webm|flv|avi|mov|wmv|mpd|3gp|ts)(?:[?#]|$)/i;
const VID_CODEC = /264|265|hevc|avc|vp[89]|mpeg-?ts|mp2t|video/i;
const TV_WORD = /\btv\b|television|\bhdtv\b|\bvideo\b|تلفزيون|تليفزيون|قناة فضائية|مباشر فيديو/i;

function isPureAudio(u, name, codec, tags) {
  if (!u || !/^https?:\/\//i.test(u)) return false;
  if (VID_EXT.test(u)) return false;
  if (VID_CODEC.test(codec || "")) return false;
  if (TV_WORD.test(name || "")) return false;
  if (Array.isArray(tags) && tags.some(t => /^(tv|television|video|webtv)$/.test(t))) return false;
  return true;
}

/* Priority Boosters for Iconic Stations so they always appear at the top of each tab */
const BOOST = [
  [/quran.{0,20}(cairo|egypt|misr|masr)|القرآن.{0,20}القاهرة|القران.{0,20}القاهرة/, 990000000, "Quran Cairo - إذاعة القرآن الكريم من القاهرة", "QURAN", "EG"],
  [/makkah|mecca|الحرم المكي|مكة المكرمة/, 980000000, "Saudi Quran Makkah - إذاعة القرآن الكريم من مكة المكرمة", "QURAN", "SA"],
  [/madinah|medina|nabawi|المدينة المنورة|المسجد النبوي/, 975000000, "Saudi Quran Madinah - إذاعة القرآن الكريم من المدينة المنورة", "QURAN", "SA"],
  [/nogoum|نجوم اف ام/, 960000000, "Nogoum FM - نجوم إف إم", "MUSIC", "EG"],
  [/nile ?fm|نايل اف ام/, 955000000, "Nile FM - نايل إف إم", "MUSIC", "EG"],
  [/mega ?fm|ميجا اف ام/, 950000000, "Mega FM - ميجا إف إم", "MUSIC", "EG"],
  [/nagham|نغم اف ام/, 945000000, "Nagham FM - نغم إف إم", "MUSIC", "EG"],
  [/9090/, 940000000, "9090 FM - الراديو 9090", "MUSIC", "EG"],
  [/radio ?hits|راديو هيتس/, 935000000, "Radio Hits - راديو هيتس", "MUSIC", "EG"],
  [/shaabi|شعبي اف ام/, 930000000, "Shaabi FM - شعبي إف إم", "MUSIC", "EG"],
  [/tes3enat|تسعينات/, 928000000, "Tes3enat FM - تسعينات إف إم", "MUSIC", "EG"],
  [/aghani|اغاني اغاني/, 926000000, "Aghani Aghani - أغاني أغاني", "MUSIC", "EG"],
  [/masriyat|مصريات/, 924000000, "Masriyat - مصريات", "MUSIC", "EG"],
  [/barnamg|البرنامج العام/, 922000000, "Al Barnamg Al Aam - البرنامج العام", "EGYPT", "EG"],
  [/sawt.{0,6}arab|صوت العرب/, 920000000, "Sawt El Arab - صوت العرب", "EGYPT", "EG"],
  [/on ?sport|اون سبورت/, 918000000, "ON Sport FM - أون سبورت إف إم", "SPORTS", "EG"],
  [/shabab|الشباب والرياضة/, 916000000, "Youth & Sports - الشباب والرياضة", "SPORTS", "EG"],
  [/jazeera|الجزيرة/, 970000000, "Al Jazeera - الجزيرة", "NEWS", "QA"],
  [/arabiya|العربية/, 965000000, "Al Arabiya - العربية", "NEWS", "SA"],
  [/bbc.{0,10}arab|بي بي سي/, 960000000, "BBC Arabic - بي بي سي عربي", "NEWS", "GB"],
  [/sky ?news|سكاي نيوز/, 955000000, "Sky News Arabia - سكاي نيوز عربية", "NEWS", "AE"],
  [/radio ?masr|راديو مصر/, 952000000, "Radio Masr - راديو مصر", "NEWS", "EG"],
  [/hadath|الحدث/, 950000000, "Al Hadath - الحدث", "NEWS", "SA"],
  [/qahera.{0,10}news|القاهرة الاخبارية/, 948000000, "Al Qahera News - القاهرة الإخبارية", "NEWS", "EG"],
  [/monte ?carlo|مونت كارلو/, 945000000, "Monte Carlo Doualiya - مونت كارلو الدولية", "NEWS", "FR"],
  [/france ?24|فرانس 24/, 942000000, "France 24 Arabic - فرانس 24", "NEWS", "FR"],
  [/asharq|الشرق/, 940000000, "Asharq News - الشرق للأخبار", "NEWS", "SA"],
  [/extra ?news|اكسترا نيوز/, 938000000, "Extra News - إكسترا نيوز", "NEWS", "EG"],
  [/sputnik|سبوتنيك/, 935000000, "Sputnik Arabic - سبوتنيك عربي", "NEWS", "RU"],
  [/rt.{0,8}arab|روسيا اليوم/, 932000000, "RT Arabic - روسيا اليوم", "NEWS", "RU"],
  [/dw.{0,8}arab|دويتشه فيله/, 930000000, "DW Arabic - دويتشه فيله", "NEWS", "DE"],
  [/mayadeen|الميادين/, 928000000, "Al Mayadeen - الميادين", "NEWS", "LB"],
  [/alhurra|الحرة/, 925000000, "Alhurra - الحرة", "NEWS", "US"],
  [/sawa|راديو سوا/, 922000000, "Radio Sawa - راديو سوا", "NEWS", "US"],
  [/kalthoum|ام كلثوم/, 960000000, "Umm Kulthum - أم كلثوم", "MUSIC", "EG"],
  [/halim|عبد الحليم/, 955000000, "Abdel Halim Hafez - عبد الحليم حافظ", "MUSIC", "EG"],
  [/fairuz|fayrouz|فيروز/, 950000000, "Fairouz - فيروز", "MUSIC", "LB"],
  [/wahab|عبد الوهاب/, 945000000, "Mohamed Abdel Wahab - محمد عبد الوهاب", "MUSIC", "EG"],
  [/rotana|روتانا/, 940000000, "Rotana FM - روتانا إف إم", "MUSIC", "SA"],
  [/mbc ?fm/, 935000000, "MBC FM - إم بي سي إف إم", "MUSIC", "SA"]
];

const SEEDS = [
  ["Quran Cairo - إذاعة القرآن الكريم من القاهرة", "QURAN", "EG", "https://stream.radiojar.com/8s5u5tpdtwzuv", 64],
  ["Quran Cairo - إذاعة القرآن الكريم من القاهرة", "QURAN", "EG", "https://n0a.radiojar.com/8s5u5tpdtwzuv", 64],
  ["Nogoum FM - نجوم إف إم", "MUSIC", "EG", "https://audio.nrpstream.com/listen/nogoum_fm/radio.mp3", 64],
  ["Nile FM - نايل إف إم", "MUSIC", "EG", "https://audio.nrpstream.com/listen/nile_fm/radio.mp3", 64],
  ["Mega FM - ميجا إف إم", "MUSIC", "EG", "https://audio.nrpstream.com/listen/mega_fm/radio.mp3", 64],
  ["Nagham FM - نغم إف إم", "MUSIC", "EG", "https://ahram.radioca.st/stream", 64],
  ["Radio 9090 - الراديو 9090", "MUSIC", "EG", "https://9090streaming.mobtada.com/9090FMEGYPT", 64],
  ["Radio Hits - راديو هيتس", "MUSIC", "EG", "https://n02.radiojar.com/8s5u5tpdtwzuv", 64],
  ["Shaabi FM - شعبي إف إم", "MUSIC", "EG", "https://radio95.radioca.st/stream", 64],
  ["Tes3enat FM - تسعينات إف إم", "MUSIC", "EG", "https://eu1.fastcast4u.com/proxy/tes3enat?mp=/1", 64],
  ["Aghani Aghani - أغاني أغاني", "MUSIC", "EG", "https://streaming.cdn.customchannels.net/aghani_aghani/playlist.m3u8", 64],
  ["Radio Masr - راديو مصر", "NEWS", "EG", "https://live.radiomasr.net/RADIOMASR", 64],
  ["ON Sport FM - أون سبورت إف إم", "SPORTS", "EG", "https://carina.streamerr.co:2020/stream/onsportfm", 64],
  ["Al Jazeera - الجزيرة", "NEWS", "QA", "https://live-hls-audio-web-aja.getaj.net/AJA/index.m3u8", 48],
  ["Al Arabiya - العربية", "NEWS", "SA", "https://live.alarabiya.net/alarabiapublish/alarabiya.stream/playlist.m3u8", 48],
  ["BBC Arabic - بي بي سي عربي", "NEWS", "GB", "https://stream.live.vc.bbcmedia.co.uk/bbc_arabic_radio", 48],
  ["Sky News Arabia - سكاي نيوز عربية", "NEWS", "AE", "https://radio.skynewsarabia.com/stream/radio/sna", 64],
  ["Monte Carlo Doualiya - مونت كارلو الدولية", "NEWS", "FR", "https://montecarlodoualiya128k.ice.infomaniak.ch/mc-doualiya-128.mp3", 64],
  ["France 24 Arabic - فرانس 24", "NEWS", "FR", "https://france24.ice.infomaniak.ch/france24-ar-64.mp3", 64],
  ["Asharq News - الشرق للأخبار", "NEWS", "SA", "https://svs.itworkscdn.net/asharqradio/asharqradio.stream/playlist.m3u8", 48],
  ["Sputnik Arabic - سبوتنيك عربي", "NEWS", "RU", "https://icecast-rian.cdnvideo.ru/voicearb", 64],
  ["RT Arabic - روسيا اليوم", "NEWS", "RU", "https://rt-arb.rttv.com/live/rtarab/playlist.m3u8", 64],
  ["DW Arabic - دويتشه فيله", "NEWS", "DE", "https://dwamdstream103.akamaized.net/hls/live/2015526/dwstream103/index.m3u8", 48],
  ["Omni Quran - إذاعة القرآن المنوعة", "QURAN", "SA", "https://backup.qurango.net/radio/mix", 64],
  ["Omni Quran Tarateel - تراتيل خاشعة", "QURAN", "SA", "https://backup.qurango.net/radio/tarateel", 64]
];

const NOISE = new Set(["fm","radio","live","stream","streaming","online","hd","mp3","aac","official","station","channel","the","mhz","khz","kbps","www","com","net","http","https","el","al","اذاعة","راديو","بث","مباشر","اونلاين"]);
function canonKey(name) {
  let s = norm(name)
    .replace(/\b(?:8[7-9]|9\d|10[0-8])(?:[.,]\d{1,2})?\b/g, " ")
    .replace(/\b\d{2,3}\s?k(?:bps)?\b/g, " ")
    .replace(/اف ام/g, " ");
  const parts = s.split(" ").filter(Boolean)
    .map(w => (w.length > 4 && w.startsWith("ال")) ? w.slice(2) : w)
    .filter(w => !NOISE.has(w));
  const key = parts.join("");
  return key.length >= 2 ? key : norm(name).replace(/\s+/g, "");
}

function classifyStation(name, tags, cc) {
  const nn = norm(name);
  const full = nn + " " + (tags || []).join(" ");
  if (RE_QURAN.test(full)) return "QURAN";
  if (RE_SPORTS.test(full)) return "SPORTS";
  if (RE_NEWS.test(full)) return "NEWS";
  if (RE_TALK.test(full)) return "TALK";
  if (RE_TARAB.test(full) || RE_MUSIC.test(full)) return "MUSIC";
  if (cc === "EG" || RE_EGYPT.test(full)) return "EGYPT";
  return "MUSIC";
}

function getKV(env) {
  return env.RADIO_KV || env.KV || Object.values(env || {}).find(v => v && typeof v.get === "function" && typeof v.put === "function") || null;
}

async function loadHS(env) {
  const out = { bad: new Set(), kb: new Map() };
  const kv = getKV(env); if (!kv) return out;
  try {
    const t = await kv.get("hs"); if (!t) return out;
    const o = JSON.parse(t), now = Date.now();
    for (const k in o) {
      const x = o[k]; if (now - x.t > HSMS) continue;
      if (x.f >= 2 && now - x.t < BADMS) out.bad.add(k);
      if (x.k >= 8 && x.k <= 400) out.kb.set(k, x.k);
    }
  } catch (e) { }
  return out;
}

function kbOf(x, hs) { return x.b > 0 ? x.b : (hs.kb.get(h32(ukey(x.u))) || 0); }
function srvRank(x, hs) {
  const k = h32(ukey(x.u)), kb = kbOf(x, hs);
  const bucket = kb <= 0 ? 3 : kb <= 48 ? 0 : kb <= 80 ? 1 : kb <= 128 ? 2 : 3;
  return (hs.bad.has(k) ? 5000 : 0) + (/^https:/i.test(x.u) ? 0 : 150) + bucket * 25 + (x.seed ? 10 : 0);
}

async function fetchJSON(url, timeout = 20000) {
  try {
    const r = await fetch(url, { headers: { "User-Agent": "RadioJoo/3.0", "Accept": "application/json" }, signal: AbortSignal.timeout(timeout) });
    if (r.ok) return await r.json();
  } catch (e) { }
  return null;
}

async function fetchRB(path) {
  const st = Math.floor(Math.random() * MIRRORS.length);
  for (let i = 0; i < 3; i++) {
    const j = await fetchJSON(MIRRORS[(st + i) % MIRRORS.length] + path, 22000);
    if (Array.isArray(j) && j.length) return j;
  }
  return [];
}

async function build(env) {
  const hs = await loadHS(env);
  const b = "/json/stations/search?hidebroken=true&order=clickcount&reverse=true&limit=";
  const queries = [
    b + "2000&language=arabic",
    b + "1000&countrycode=EG",
    b + "1000&tag=quran",
    b + "500&tag=arabic",
    b + "300&countrycode=SA",
    b + "300&countrycode=AE",
    b + "300&countrycode=LB",
    b + "300&countrycode=QA",
    b + "200&name=qurango",
    b + "200&name=mp3quran",
    b + "200&name=tarab",
    b + "200&name=sputnik"
  ];

  const [rbResults, mp3Quran] = await Promise.all([
    Promise.all(queries.map(q => fetchRB(q))),
    fetchJSON("https://www.mp3quran.net/api/v3/radios?language=ar", 15000)
  ]);

  const rawItems = [];

  // 1. Ingest Radio-Browser stations
  for (const arr of rbResults) {
    for (const r of arr) {
      const u = r.url_resolved || r.url;
      const n = String(r.name || "").replace(/\s+/g, " ").trim();
      if (!n || n.length < 2) continue;
      const tags = String(r.tags || "").split(",").map(norm).filter(Boolean);
      if (!isPureAudio(u, n, r.codec, tags)) continue;
      const nn = norm(n);
      if (RE_DROP.test(nn + " " + tags.join(" "))) continue;
      const cc = String(r.countrycode || "").toUpperCase();
      const lang = String(r.language || "").toLowerCase();
      const isArab = ARAB_CC.has(cc) || lang.includes("arab") || /[\u0600-\u06FF]/.test(n) || RE_QURAN.test(nn) || RE_NEWS.test(nn) || RE_TARAB.test(nn);
      if (!isArab) continue;
      rawItems.push({
        n, nn, u, cc, tags,
        b: r.bitrate | 0,
        sc: (r.clickcount | 0) + (r.votes | 0) * 10,
        seed: false
      });
    }
  }

  // 2. Ingest MP3Quran Live Radios (100+ pure-audio low-bitrate Quran stations)
  if (mp3Quran && Array.isArray(mp3Quran.radios)) {
    for (const q of mp3Quran.radios) {
      const n = String(q.name || "").trim();
      const u = String(q.url || "").trim();
      if (!n || !isPureAudio(u, n, "MP3", ["quran"])) continue;
      rawItems.push({
        n: n.includes("إذاعة") ? n : ("إذاعة " + n),
        nn: norm(n),
        u,
        cc: RE_EGYPT.test(norm(n)) ? "EG" : "SA",
        tags: ["quran", "arabic"],
        b: 64,
        sc: 5000,
        seed: false
      });
    }
  }

  // 3. Ingest Curated Seeds
  for (const [n, cat, cc, u, b] of SEEDS) {
    rawItems.push({
      n, nn: norm(n), u, cc, tags: [cat.toLowerCase()], b, sc: 1000, seed: true, forcedCat: cat
    });
  }

  // Group & Deduplicate by Canonical Name (merging all servers under one station)
  const groups = new Map();
  for (const item of rawItems) {
    let matchedBoost = null;
    for (const bRule of BOOST) {
      if (bRule[0].test(item.nn)) { matchedBoost = bRule; break; }
    }
    const key = matchedBoost ? ("B|" + matchedBoost[2]) : ("G|" + canonKey(item.n));
    if (!key || key.length < 4) continue;
    let g = groups.get(key);
    if (!g) { g = { items: [], boost: matchedBoost }; groups.set(key, g); }
    g.items.push(item);
  }

  const stations = [];
  for (const [key, g] of groups) {
    g.items.sort((a, z) => z.sc - a.sc);
    const best = g.items.find(x => !x.seed) || g.items[0];
    const bRule = g.boost;
    const name = bRule ? bRule[2] : best.n;
    const cc = bRule ? bRule[4] : (g.items.map(x => x.cc).find(c => c && c.length === 2) || (RE_EGYPT.test(best.nn) ? "EG" : "AR"));
    const allTags = [...new Set(g.items.flatMap(x => x.tags))];
    const cat = bRule ? bRule[3] : (best.forcedCat || classifyStation(name, allTags, cc));

    // Merge & sort servers: working first, HTTPS first, cheapest bitrate first
    const ranked = g.items.slice().sort((a, z) => srvRank(a, hs) - srvRank(z, hs));
    const seenUrls = new Set(), servers = [];
    for (const x of ranked) {
      const uk = ukey(x.u);
      if (seenUrls.has(uk)) continue;
      seenUrls.add(uk);
      servers.push({ u: x.u, b: kbOf(x, hs), h: 0 });
      if (servers.length >= MAXSRV) break;
    }
    if (!servers.length) continue;

    let pop = bRule ? bRule[1] : g.items.reduce((acc, x) => acc + x.sc, 0);
    pop += servers.length * 500;
    const id = "rj" + h32(key) + h32(key.split("").reverse().join(""));
    stations.push({ id, n: name, c: cat, cc, p: Math.min(2000000000, pop), s: servers });
  }

  if (stations.length < 50) return null;
  stations.sort((a, z) => z.p - a.p);

  const cats = {}; let totalServers = 0;
  for (const s of stations) { cats[s.c] = (cats[s.c] || 0) + 1; totalServers += s.s.length; }
  const body = JSON.stringify({ v: 2, stations });
  return { body, t: Date.now(), etag: '"' + h32(body) + stations.length + '"', n: stations.length, s: totalServers, cats };
}

async function loadStored(env) {
  if (MEM) return MEM;
  const kv = getKV(env); if (!kv) return null;
  try {
    const [body, meta] = await Promise.all([kv.get("catalog"), kv.get("catalog_meta")]);
    if (body && meta) { MEM = Object.assign({ body }, JSON.parse(meta)); return MEM; }
  } catch (e) { }
  return null;
}
function rebuild(env) {
  if (BUILDING) return BUILDING;
  BUILDING = (async () => {
    try {
      const c = await build(env); if (!c) return null;
      MEM = c;
      const kv = getKV(env);
      if (kv) { try { await kv.put("catalog", c.body); await kv.put("catalog_meta", JSON.stringify({ t: c.t, etag: c.etag, n: c.n, s: c.s, cats: c.cats })); } catch (e) { } }
      return c;
    } finally { BUILDING = null; }
  })();
  return BUILDING;
}
async function catalog(env, ctx) {
  const c = await loadStored(env);
  if (c && Date.now() - c.t < TTL) return c;
  if (c) { ctx.waitUntil(rebuild(env)); return c; }
  return await rebuild(env);
}
const json = (o, status = 200) => new Response(JSON.stringify(o), { status, headers: { "Content-Type": "application/json; charset=utf-8" } });

export default {
  async fetch(req, env, ctx) {
    const url = new URL(req.url);
    const path = url.pathname.replace(/\/+$/, "");
    if (path === "/v2/stations") {
      const c = await catalog(env, ctx);
      if (!c) return json({ stations: [], retry: 15 });
      if (req.headers.get("If-None-Match") === c.etag) return new Response(null, { status: 304, headers: { ETag: c.etag } });
      return new Response(c.body, { headers: { "Content-Type": "application/json; charset=utf-8", "ETag": c.etag, "Cache-Control": "public, max-age=300" } });
    }
    if (path === "/v2/refresh") {
      const c0 = await loadStored(env);
      if (c0 && Date.now() - LASTREF < THROTTLE) return json({ stations: c0.n, servers: c0.s, verified: c0.s, throttled: true, cats: c0.cats || {} });
      LASTREF = Date.now();
      const c = (await rebuild(env)) || c0;
      if (!c) return json({ stations: 0, servers: 0, verified: 0, throttled: false });
      return json({ stations: c.n, servers: c.s, verified: c.s, throttled: false, cats: c.cats || {} });
    }
    if (path === "/v2/heal") {
      const c = await catalog(env, ctx); if (!c) return json({}, 404);
      const id = url.searchParams.get("id");
      const all = JSON.parse(c.body).stations;
      const st = all.find(s => s.id === id);
      if (!st) return json({}, 404);
      const q0 = st.n.split(" - ")[0].trim();
      const found = await fetchRB("/json/stations/search?hidebroken=true&limit=60&order=clickcount&reverse=true&name=" + encodeURIComponent(q0));
      const have = new Set(st.s.map(x => ukey(x.u)));
      for (const r of found) {
        const u = r.url_resolved || r.url;
        if (!isPureAudio(u, r.name, r.codec, []) || have.has(ukey(u))) continue;
        have.add(ukey(u));
        st.s.push({ u, b: r.bitrate | 0, h: 0 });
        if (st.s.length >= MAXSRV) break;
      }
      return json({ station: st });
    }
    if (path === "/v2/report") {
      try {
        let items = [];
        if (req.method === "POST") {
          const j = await req.json().catch(() => null);
          if (j && Array.isArray(j.r)) items = j.r.slice(0, 200);
        }
        const kv = getKV(env);
        if (items.length && kv) {
          const t = await kv.get("hs"); const o = t ? JSON.parse(t) : {}; const now = Date.now();
          for (const it of items) {
            if (!it || typeof it.u !== "string") continue;
            const k = h32(ukey(it.u)); const x = o[k] || { f: 0, o: 0, t: 0, k: 0 };
            if (it.ok) { x.o++; x.f = 0; } else x.f++;
            const kb = it.b | 0; if (kb >= 8 && kb <= 400) x.k = kb;
            x.t = now; o[k] = x;
          }
          await kv.put("hs", JSON.stringify(o));
        }
      } catch (e) { }
      return json({ ok: true });
    }
    return json({ ok: true, service: "radio-joo-proxy", v: 3 });
  },
  async scheduled(event, env, ctx) { ctx.waitUntil(rebuild(env)); },
};