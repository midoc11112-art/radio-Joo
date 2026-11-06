export default {
  async fetch(request, env, ctx) {
    if (request.method === "OPTIONS") {
      return new Response(null, { headers: { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS", "Access-Control-Allow-Headers": "*" } });
    }
    const url = new URL(request.url);
    const path = url.pathname;
    if (path === "/stations" || path === "/stations/all") return await getStations(env, ctx);
    if (path === "/heal") return await handleHeal(url, env, ctx);
    if (path === "/save-url") return await handleSaveUrl(url, env, ctx);
    if (path === "/get-saved-url") return await handleGetSavedUrl(url, env);
    if (path === "/feedback") return await handleFeedback(url, env);
    if (path === "/health-check") return await handleHealthCheck(url);
    if (path === "/debug") return await handleDebug(env);
    if (path === "/test-arabic") return await handleTestArabic(url, env);
    if (path === "/health") return jsonResp({ status: "ok", version: "110.0.0", features: "rb-byuuid-first, rb-mirrors-parallel, specialized-apis-second, curated-fallback, web-search-signal, 30d-blacklist, 90d-station-url" });
    const streamUrl = url.searchParams.get("url");
    if (!streamUrl) return new Response("Missing url parameter", { status: 400 });
    return await proxyStreamAdaptive(streamUrl, request, ctx);
  }
};

const TASHKEEL_REGEX = /[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06ED]/g;
const TATWEEL_REGEX = /ـ/g;

function normalizeArabic(str) {
  if (!str) return "";
  return String(str).replace(TASHKEEL_REGEX, "").replace(TATWEEL_REGEX, "")
    .replace(/[إأآٱ]/g, "ا").replace(/ة/g, "ه").replace(/ى/g, "ي").replace(/ؤ/g, "و").replace(/ئ/g, "ي")
    .replace(/[^\u0600-\u06FF\w\s]/g, " ").replace(/\s+/g, " ").trim().toLowerCase();
}

function generateNameVariants(name) {
  if (!name) return [];
  const v = new Set(); const o = String(name).trim();
  v.add(o); v.add(normalizeArabic(o));
  const s = o.replace(/\b(fm|راديو|radio|إذاعة|اذاعة|إف\s*إم|اف\s*ام)\b/gi, " ").replace(/\s+/g, " ").trim();
  if (s && s !== o) { v.add(s); v.add(normalizeArabic(s)); }
  const n = o.replace(/[\(\)\[\]{}]/g, " ").replace(/\s+/g, " ").trim();
  if (n && n !== o) { v.add(n); v.add(normalizeArabic(n)); }
  return [...v].filter(x => x && x.length >= 2);
}

const TRANSLIT_MAP = {
  "محمد":"mohamed","أحمد":"ahmed","مصطفى":"mostafa","قرآن":"quran","قران":"quran","تلاوة":"tilawa",
  "مصر":"masr","مصري":"masri","إذاعة":"radio","اذاعة":"radio","راديو":"radio","الجزيرة":"aljazeera",
  "العربية":"alarabiya","سكاي":"sky","سوا":"sawa","الشرق":"asharq","الحدث":"alhadath","مونت":"monte",
  "كارلو":"carlo","فرانس":"france","بي بي سي":"bbc","نجوم":"nogoum","نغم":"nagham","شعبي":"shaabi",
  "طرب":"tarab","أغاني":"aghani","اغاني":"aghani","منشاوي":"minshawi","حصري":"hussary","عبدالباسط":"abdulbasit",
  "شعراوي":"sharawi","كشك":"kishk","رفعت":"rifai","السديس":"sudais","العفاسي":"afasy","فيروز":"fairuz",
  "كلثوم":"kalthoum","حليم":"halim","دياب":"diab","روتانا":"rotana","ميجا":"mega","ميكس":"mix",
  "هيتس":"hits","إنرجي":"nrj","نايل":"nile","البرنامج":"program","العام":"general","صوت":"sawt",
  "العرب":"arab","شباب":"shabab","رياضة":"riyada","إسلامي":"islamic","اسلامي":"islamic","أناشيد":"nasheed"
};

function transliterate(text) {
  if (!text) return "";
  let r = String(text);
  const e = Object.entries(TRANSLIT_MAP).sort((a, b) => b[0].length - a[0].length);
  for (const [ar, en] of e) r = r.split(ar).join(" " + en + " ");
  return r.replace(/\s+/g, " ").trim();
}

const ARAB_COUNTRIES_SET = new Set(["EG","SA","AE","KW","QA","BH","OM","JO","LB","SY","IQ","PS","YE","SD","LY","TN","DZ","MA","MR","DJ","SO","KM"]);

const NOISE_HOSTS = ["uber.radio","exclusive.radio","mystreaming.net","positivity.radio","laut.fm","mrg.fm","streeemer.com","torontocast.com","1.fm/","radioparadise.com","somafm.com","dashitradio","radiorelax.ua","k-sun66","ksunclassics"];

const NOISE_NAME_PATTERNS = [
  /^exclusively\b/i, /^exclusive\s+radio/i, /^greatest\s+hits/i, /^positively\b/i,
  /^starbox\b/i, /^c\.r\.?\s*-/i, /^classical\s+radio/i, /^classica\s+radio/i,
  /^easy\s+(50|60|70|80|90|00|classical|crooners|folk|jazz|instrumentals|kenny|james|rock)\b/i,
  /^love\s+radio\s*[-–]/i, /^tinder\s+radio/i, /^rock\s+the\s+world/i,
  /^world\s+music\s+radio/i, /^just\s+jazz/i, /^boomerang\b/i,
  /^drgnu\b/i, /^sportify/i, /^skyrock/i, /^fun\s+radio/i,
  /^#?\s*top\s*100/i, /^#?\s*top\s*40/i, /^k-sun66/i, /^k-sun\s*66/i,
  /^npr\s+news/i, /^npr\s+program/i, /^suno\s+malayalam/i,
  /^radio\s+maria\s+middle\s+east/i, /^territory\s+fm/i
];

function isArabicStation(s) {
  if (!s) return false;
  const cc = (s.countrycode || s.countryCode || "").toUpperCase();
  if (ARAB_COUNTRIES_SET.has(cc)) return true;
  return /[\u0600-\u06FF]/.test(s.name || "");
}

function isNoiseStation(name, url) {
  if (!name) return true;
  const u = (url || "").toLowerCase();
  if (NOISE_HOSTS.some(h => u.includes(h))) return true;
  return NOISE_NAME_PATTERNS.some(p => p.test(name.trim()));
}

function isRedundantQuran(name) {
  if (!name) return false;
  if (/^translation of the quran/i.test(name.trim())) return !/[\u0600-\u06FF]/.test(name);
  return false;
}

function isRealStreamUrl(url) {
  if (!url) return false;
  const u = url.toLowerCase();
  if (u.includes("onlineradiobox.com/json")) return false;
  if (u.includes("/play?platform=")) return false;
  if (u.includes("radio.garden/api")) return false;
  if (u.includes("tunein.com/search")) return false;
  if (u.endsWith("/play")) return false;
  if (u.includes("radios.com.br/play")) return false;
  return true;
}

function isStrictlyAudio(station) {
  const name = (station.name || "").toLowerCase();
  const url = (station.primaryUrl || "").toLowerCase();
  if (/\.(mp4|mkv|webm|avi|mov|flv|wmv)(\?|$|#)/.test(url)) return false;
  if (/\/(video|watch|embed|live-tv)(\/|\?|$)/.test(url)) return false;
  if (/\.tv(\/|\?|$)/i.test(url)) return false;
  if (/\/(1080|720|480|360)[pP]?(\/|\?|$)/.test(url)) return false;
  if (/\btv\b/i.test(name)) return false;
  if (/\bvideo\b/i.test(name)) return false;
  if (/\bhd\b/i.test(name)) return false;
  return true;
}

function getCuratedStations() {
  return [
    { uuid:"eg_program_general", name:"البرنامج العام", primaryUrl:"https://stream.radiojar.com/8wfn1t0mz8mtv", category:"EGYPT", countryCode:"EG", priority:99, bitrate:96, synonyms:["program general","الاذاعه المصريه"] },
    { uuid:"eg_sawt_el_arab", name:"صوت العرب", primaryUrl:"https://stream.radiojar.com/8t50dxpny8mtv", category:"EGYPT", countryCode:"EG", priority:98, bitrate:96, synonyms:["sawt el arab","voice of arabs"] },
    { uuid:"eg_radio_masr", name:"راديو مصر", primaryUrl:"http://live.radiomasr.net:8060/RADIOMASR", fallbackUrl:"https://stream.radiojar.com/8s5uf405n8mtv", category:"EGYPT", countryCode:"EG", priority:97, bitrate:128, synonyms:["radio masr","اذاعه مصر"] },
    { uuid:"eg_nogoum", name:"نجوم إف إم", primaryUrl:"https://audio.nrpstream.com/listen/nogoumfm/radio.mp3", fallbackUrl:"https://stream-159.zeno.fm/qb1zvsykm98uv", category:"MUSIC", countryCode:"EG", priority:96, bitrate:128, synonyms:["nogoum fm","نجوم"] },
    { uuid:"eg_nagham", name:"نغم إف إم", primaryUrl:"https://ahmsamir.radioca.st/stream", category:"MUSIC", countryCode:"EG", priority:95, bitrate:128, synonyms:["nagham fm","نغم"] },
    { uuid:"eg_nilefm", name:"نايل إف إم", primaryUrl:"https://audio.nrpstream.com/listen/nile_fm/radio.mp3", fallbackUrl:"https://stream-154.zeno.fm/8wv4d0g0g9duv", category:"MUSIC", countryCode:"EG", priority:94, bitrate:128, synonyms:["nile fm","نايل"] },
    { uuid:"eg_megafm", name:"ميجا إف إم", primaryUrl:"http://nebula.shoutca.st:8211/mp3", fallbackUrl:"https://stream-155.zeno.fm/xj2w2x7k7duvv", category:"MUSIC", countryCode:"EG", priority:93, bitrate:128, synonyms:["mega fm","ميجا"] },
    { uuid:"eg_9090", name:"الراديو 9090", primaryUrl:"https://9090streaming.mobtada.com/9090FMEGYPT", category:"MUSIC", countryCode:"EG", priority:92, bitrate:64, synonyms:["9090","راديو 9090"] },
    { uuid:"eg_sha3by", name:"شعبي إف إم", primaryUrl:"https://radio95.radioca.st/", category:"MUSIC", countryCode:"EG", priority:91, bitrate:128, synonyms:["shaabi fm","شعبي"] },
    { uuid:"eg_radiohits", name:"راديو هيتس", primaryUrl:"https://stream-153.zeno.fm/2f8zbgqs2nhvv", category:"MUSIC", countryCode:"EG", priority:90, bitrate:128, synonyms:["radio hits","هيتس"] },
    { uuid:"eg_mixfm", name:"ميكس إف إم", primaryUrl:"https://stream-153.zeno.fm/x83nhve8tv8uv", category:"MUSIC", countryCode:"EG", priority:89, bitrate:128, synonyms:["mix fm","ميكس"] },
    { uuid:"eg_nrj", name:"إنرجي إف إم", primaryUrl:"https://stream-152.zeno.fm/xz0a5epy0nhvv", category:"MUSIC", countryCode:"EG", priority:88, bitrate:128, synonyms:["nrj","energy fm","انرجي"] },
    { uuid:"eg_diabfm", name:"دياب إف إم", primaryUrl:"http://stream-36.zeno.fm/rf64mx02qa0uv", category:"MUSIC", countryCode:"EG", priority:87, bitrate:128, synonyms:["diab fm"] },
    { uuid:"eg_amrdiab", name:"عمرو دياب راديو", primaryUrl:"https://stream-40.zeno.fm/xa4yhh4k838uv", category:"MUSIC", countryCode:"EG", priority:86, bitrate:128, synonyms:["amr diab"] },
    { uuid:"eg_masr_elgdida", name:"راديو مصر الجديدة", primaryUrl:"https://streaming.radio.co/scc13a6b96/listen", category:"EGYPT", countryCode:"EG", priority:85, bitrate:128, synonyms:["masr el gdida"] },
    { uuid:"eg_mahatet_masr", name:"محطة مصر", primaryUrl:"https://s3.radio.co/s9cb11828c/listen", category:"EGYPT", countryCode:"EG", priority:84, bitrate:128, synonyms:["mahatet masr"] },
    { uuid:"eg_el_gouna", name:"El Gouna Radio", primaryUrl:"http://82.201.132.237:8000/", category:"EGYPT", countryCode:"EG", priority:83, bitrate:128, synonyms:["الجونه","gouna"] },
    { uuid:"eg_sotak", name:"Radio Sotak", primaryUrl:"https://radio.radiosotak.com/sotak_128", category:"EGYPT", countryCode:"EG", priority:82, bitrate:128, synonyms:["صوتك","sotak"] },
    { uuid:"eg_masrawy", name:"Radio Masrawy", primaryUrl:"https://9090streaming.mobtada.com/9090FMEGYPT", category:"MUSIC", countryCode:"EG", priority:81, bitrate:64, synonyms:["masrawy"] },
    { uuid:"eg_quran_cairo", name:"إذاعة القرآن الكريم من القاهرة", primaryUrl:"https://stream.radiojar.com/8s5u5tpdtwzuv", category:"QURAN", countryCode:"EG", priority:100, bitrate:128, synonyms:["اذاعه القران","quran cairo"] },
    { uuid:"quran_minshawi", name:"المنشاوي", primaryUrl:"https://radio.mp3islam.com/listen/minshawi/radio.mp3", category:"QURAN", countryCode:"EG", priority:100, bitrate:128, synonyms:["minshawi","المنشاوى"] },
    { uuid:"quran_hussary", name:"الحصري", primaryUrl:"https://radio.mp3islam.com/listen/hussary/radio.mp3", category:"QURAN", countryCode:"EG", priority:99, bitrate:128, synonyms:["hussary","الحصرى"] },
    { uuid:"quran_abdulbasit", name:"عبدالباسط عبدالصمد", primaryUrl:"https://radio.mp3islam.com/listen/abdulbasit/radio.mp3", category:"QURAN", countryCode:"EG", priority:98, bitrate:128, synonyms:["abdulbasit"] },
    { uuid:"quran_sudais", name:"السديس", primaryUrl:"https://radio.mp3islam.com/listen/sudais/radio.mp3", category:"QURAN", countryCode:"SA", priority:97, bitrate:128, synonyms:["sudais"] },
    { uuid:"quran_shuraim", name:"الشريم", primaryUrl:"https://radio.mp3islam.com/listen/shuraim/radio.mp3", category:"QURAN", countryCode:"SA", priority:96, bitrate:128, synonyms:["shuraim"] },
    { uuid:"quran_afasy", name:"مشاري العفاسي", primaryUrl:"https://radio.mp3islam.com/listen/afasy/radio.mp3", category:"QURAN", countryCode:"KW", priority:95, bitrate:128, synonyms:["afasy"] },
    { uuid:"quran_ghamdi", name:"سعد الغامدي", primaryUrl:"https://radio.mp3islam.com/listen/ghamdi/radio.mp3", category:"QURAN", countryCode:"SA", priority:94, bitrate:128, synonyms:["ghamdi"] },
    { uuid:"quran_shathry", name:"الشثري", primaryUrl:"https://radio.mp3islam.com/listen/shathry/radio.mp3", category:"QURAN", countryCode:"SA", priority:93, bitrate:128, synonyms:["shathry"] },
    { uuid:"quran_qarni", name:"عبدالله القرني", primaryUrl:"https://radio.mp3islam.com/listen/qarni/radio.mp3", category:"QURAN", countryCode:"SA", priority:92, bitrate:128, synonyms:["qarni"] },
    { uuid:"quran_tarateel", name:"تراتيل قصيرة", primaryUrl:"https://qurango.net/radio/tarateel", category:"QURAN", countryCode:"EG", priority:91, bitrate:128, synonyms:["tarateel"] },
    { uuid:"quran_soufi", name:"عبدالرشيد صوفي", primaryUrl:"https://qurango.net/radio/abdulrasheed_soufi_assosi.mp3", category:"QURAN", countryCode:"EG", priority:90, bitrate:128, synonyms:["soufi"] },
    { uuid:"quran_ayyoub", name:"محمد أيوب", primaryUrl:"https://radio.mp3islam.com/listen/ayyoub/radio.mp3", category:"QURAN", countryCode:"SA", priority:89, bitrate:128, synonyms:["ayyoub"] },
    { uuid:"quran_dossari", name:"ياسر الدوسري", primaryUrl:"https://radio.mp3islam.com/listen/dossari/radio.mp3", category:"QURAN", countryCode:"SA", priority:88, bitrate:128, synonyms:["dossari"] },
    { uuid:"quran_muaiqly", name:"ماهر المعيقلي", primaryUrl:"https://radio.mp3islam.com/listen/muaiqly/radio.mp3", category:"QURAN", countryCode:"SA", priority:87, bitrate:128, synonyms:["muaiqly"] },
    { uuid:"quran_juhani", name:"عبدالله الجهني", primaryUrl:"https://radio.mp3islam.com/listen/juhani/radio.mp3", category:"QURAN", countryCode:"SA", priority:86, bitrate:128, synonyms:["juhani"] },
    { uuid:"quran_ajmi", name:"أحمد العجمي", primaryUrl:"https://radio.mp3islam.com/listen/ajmi/radio.mp3", category:"QURAN", countryCode:"SA", priority:84, bitrate:128, synonyms:["ajmi"] },
    { uuid:"quran_kurdi", name:"رعد الكردي", primaryUrl:"https://radio.mp3islam.com/listen/kurdi/radio.mp3", category:"QURAN", countryCode:"SA", priority:83, bitrate:128, synonyms:["kurdi"] },
    { uuid:"quran_khalid", name:"خالد الجليل", primaryUrl:"https://radio.mp3islam.com/listen/khalid/radio.mp3", category:"QURAN", countryCode:"SA", priority:82, bitrate:128, synonyms:["khalid"] },
    { uuid:"quran_sharawi", name:"الشعراوي", primaryUrl:"https://radio.mp3islam.com/listen/sharawi/radio.mp3", category:"QURAN", countryCode:"EG", priority:81, bitrate:128, synonyms:["sharawi"] },
    { uuid:"quran_rifai", name:"رفعت رفاعي", primaryUrl:"https://radio.mp3islam.com/listen/rifai/radio.mp3", category:"QURAN", countryCode:"EG", priority:80, bitrate:128, synonyms:["rifai"] },
    { uuid:"quran_kishk", name:"كشك", primaryUrl:"https://radio.mp3islam.com/listen/kishk/radio.mp3", category:"QURAN", countryCode:"EG", priority:79, bitrate:128, synonyms:["kishk"] },
    { uuid:"quran_tablaway", name:"الطبلاوي", primaryUrl:"https://radio.mp3islam.com/listen/tablaway/radio.mp3", category:"QURAN", countryCode:"EG", priority:77, bitrate:128, synonyms:["tablaway"] },
    { uuid:"quran_makkah", name:"إذاعة القرآن من مكة", primaryUrl:"https://radio.mp3islam.com/listen/makkah/radio.mp3", category:"QURAN", countryCode:"SA", priority:76, bitrate:128, synonyms:["makkah"] },
    { uuid:"intl_bbc_arabic", name:"بي بي سي عربي", primaryUrl:"https://stream.live.vc.bbcmedia.co.uk/bbc_arabic_radio", category:"NEWS", countryCode:"GB", priority:100, bitrate:96, synonyms:["bbc arabic"] },
    { uuid:"intl_monte_carlo", name:"مونت كارلو الدولية", primaryUrl:"https://montecarlodoualiya128k.ice.infomaniak.ch/mc-doualiya.mp3", category:"NEWS", countryCode:"FR", priority:99, bitrate:128, synonyms:["monte carlo"] },
    { uuid:"intl_france24", name:"فرانس 24 عربي", primaryUrl:"https://live.france24.com/hls/live/2037222-b/F24_AR_HI_HLS/master_5000.m3u8", category:"NEWS", countryCode:"FR", priority:98, bitrate:128, synonyms:["france24"] },
    { uuid:"intl_dw", name:"DW عربية", primaryUrl:"https://dw.audiostream.io/dw/1002/mp3/64/dw05.mp3", category:"NEWS", countryCode:"DE", priority:97, bitrate:64, synonyms:["dw arabic"] },
    { uuid:"intl_aljazeera", name:"الجزيرة", primaryUrl:"https://live-hls-audio-web-aja.getaj.net/VOICE-AJA/index.m3u8", category:"NEWS", countryCode:"QA", priority:96, bitrate:128, synonyms:["aljazeera"] },
    { uuid:"intl_sky_arabia", name:"سكاي نيوز عربية", primaryUrl:"https://radio.skynewsarabia.com/stream/radio/skynewsarabia", category:"NEWS", countryCode:"AE", priority:95, bitrate:128, synonyms:["sky news"] },
    { uuid:"intl_bbc_arabic_radio", name:"BBC Arabic Radio", primaryUrl:"https://stream.live.vc.bbcmedia.co.uk/bbc_arabic_radio", category:"NEWS", countryCode:"GB", priority:79, bitrate:96, synonyms:["bbc"] },
    { uuid:"intl_sky_arabia_radio", name:"Sky News Arabia Radio", primaryUrl:"https://radio.skynewsarabia.com/stream/radio/skynewsarabia", category:"NEWS", countryCode:"AE", priority:76, bitrate:128, synonyms:["sky arabia"] },
    { uuid:"intl_monte_carlo_orient", name:"مونت كارلو الشرق", primaryUrl:"https://montecarlodoualiya128k.ice.infomaniak.ch/mc-doualiya.mp3", category:"MUSIC", countryCode:"FR", priority:80, bitrate:128, synonyms:["monte carlo orient"] },
    { uuid:"isl_sunnah", name:"إذاعة السنة النبوية", primaryUrl:"https://radio.mp3islam.com/listen/sunnah/radio.mp3", category:"ISLAMIC", countryCode:"SA", priority:99, bitrate:128, synonyms:["sunnah"] },
    { uuid:"isl_anasheed", name:"أناشيد إسلامية", primaryUrl:"https://radio.mp3islam.com/listen/nasheed/radio.mp3", category:"ISLAMIC", countryCode:"SA", priority:98, bitrate:128, synonyms:["nasheed"] },
    { uuid:"isl_tarateel2", name:"تراتيل", primaryUrl:"https://radio.mp3islam.com/listen/tarateel/radio.mp3", category:"ISLAMIC", countryCode:"SA", priority:97, bitrate:128, synonyms:["tarateel"] },
    { uuid:"isl_islamic_radio", name:"Islamic Radio", primaryUrl:"https://radio.mp3islam.com/listen/islamic/radio.mp3", category:"ISLAMIC", countryCode:"SA", priority:96, bitrate:128, synonyms:["islamic"] },
    { uuid:"isl_quran_radio", name:"Quran Radio", primaryUrl:"https://radio.mp3islam.com/listen/quran/radio.mp3", category:"ISLAMIC", countryCode:"SA", priority:95, bitrate:128, synonyms:["quran"] },
    { uuid:"isl_sunnah_radio", name:"Sunnah Radio", primaryUrl:"https://radio.mp3islam.com/listen/sunnah/radio.mp3", category:"ISLAMIC", countryCode:"SA", priority:94, bitrate:128, synonyms:["sunnah radio"] },
    { uuid:"isl_lectures", name:"Islamic Lectures", primaryUrl:"https://radio.mp3islam.com/listen/lectures/radio.mp3", category:"ISLAMIC", countryCode:"SA", priority:93, bitrate:128, synonyms:["lectures"] },
    { uuid:"isl_sheikh", name:"Sheikh Lectures", primaryUrl:"https://radio.mp3islam.com/listen/sheikh/radio.mp3", category:"ISLAMIC", countryCode:"SA", priority:92, bitrate:128, synonyms:["sheikh"] },
    { uuid:"isl_music", name:"Islamic Music", primaryUrl:"https://radio.mp3islam.com/listen/islamic-music/radio.mp3", category:"ISLAMIC", countryCode:"SA", priority:91, bitrate:128, synonyms:["islamic music"] },
    { uuid:"isl_nasheed_radio", name:"Nasheed Radio", primaryUrl:"https://radio.mp3islam.com/listen/nasheed/radio.mp3", category:"ISLAMIC", countryCode:"SA", priority:90, bitrate:128, synonyms:["nasheed radio"] },
    { uuid:"isl_dua", name:"Dua Radio", primaryUrl:"https://radio.mp3islam.com/listen/dua/radio.mp3", category:"ISLAMIC", countryCode:"SA", priority:89, bitrate:128, synonyms:["dua"] },
    { uuid:"isl_ruqyah", name:"Ruqyah Radio", primaryUrl:"https://radio.mp3islam.com/listen/ruqyah/radio.mp3", category:"ISLAMIC", countryCode:"SA", priority:88, bitrate:128, synonyms:["ruqyah"] },
    { uuid:"isl_kids", name:"Islamic Kids", primaryUrl:"https://radio.mp3islam.com/listen/kids/radio.mp3", category:"ISLAMIC", countryCode:"SA", priority:87, bitrate:128, synonyms:["kids"] },
    { uuid:"isl_family", name:"Islamic Family", primaryUrl:"https://radio.mp3islam.com/listen/family/radio.mp3", category:"ISLAMIC", countryCode:"SA", priority:86, bitrate:128, synonyms:["family"] },
    { uuid:"isl_hadith", name:"Hadith Radio", primaryUrl:"https://radio.mp3islam.com/listen/hadith/radio.mp3", category:"ISLAMIC", countryCode:"SA", priority:85, bitrate:128, synonyms:["hadith"] },
    { uuid:"isl_fiqh", name:"Fiqh Radio", primaryUrl:"https://radio.mp3islam.com/listen/fiqh/radio.mp3", category:"ISLAMIC", countryCode:"SA", priority:84, bitrate:128, synonyms:["fiqh"] },
    { uuid:"isl_seerah", name:"Seerah Radio", primaryUrl:"https://radio.mp3islam.com/listen/seerah/radio.mp3", category:"ISLAMIC", countryCode:"SA", priority:83, bitrate:128, synonyms:["seerah"] },
    { uuid:"isl_tajweed", name:"Tajweed Radio", primaryUrl:"https://radio.mp3islam.com/listen/tajweed/radio.mp3", category:"ISLAMIC", countryCode:"SA", priority:82, bitrate:128, synonyms:["tajweed"] },
    { uuid:"isl_tafseer", name:"Tafseer Radio", primaryUrl:"https://radio.mp3islam.com/listen/tafseer/radio.mp3", category:"ISLAMIC", countryCode:"SA", priority:81, bitrate:128, synonyms:["tafseer"] }
  ];
}

async function getStations(env, ctx) {
  try {
    const cacheKey = "stations:v110_0:arabic-clean";
    const cached = await env.STATIONS_KV?.get(cacheKey, "json");
    const now = Date.now();
    if (cached && cached.ts && (now - cached.ts) < 1800000 && cached.stations?.length > 200) {
      return jsonResp({ version: "110.0.0", total: cached.stations.length, stations: cached.stations, cached: true });
    }
    const curated = getCuratedStations();
    const extended = await fetchExtendedArabic();
    const allStations = [...curated, ...extended];
    const filtered = allStations.filter(isStrictlyAudio).filter(s => isRealStreamUrl(s.primaryUrl));
    const uniqueMap = new Map();
    for (const s of filtered) {
      const key = (s.primaryUrl || "").toLowerCase().trim() || normalizeArabic(s.name);
      if (!uniqueMap.has(key)) uniqueMap.set(key, s);
      else { const existing = uniqueMap.get(key); if ((s.priority || 0) > (existing.priority || 0)) uniqueMap.set(key, s); }
    }
    const finalStations = Array.from(uniqueMap.values());
    finalStations.sort((a, b) => (b.priority || b.popularity || 0) - (a.priority || a.popularity || 0));
    ctx.waitUntil(env.STATIONS_KV?.put(cacheKey, JSON.stringify({ ts: now, stations: finalStations }), { expirationTtl: 3600 }).catch(() => {}));
    return jsonResp({ version: "110.0.0", total: finalStations.length, stations: finalStations });
  } catch (e) { return jsonResp({ error: e.message, stations: [] }, 500); }
}

async function fetchExtendedArabic() {
  const stations = [];
  const seen = new Set();
  const API = "https://all.api.radio-browser.info/json/stations/search";
  const queries = [];
  for (const cc of ARAB_COUNTRIES_SET) queries.push(`${API}?countrycode=${cc}&limit=500&hidebroken=true&lastcheckok=1&order=clickcount&reverse=true`);
  queries.push(`${API}?language=arabic&limit=5000&hidebroken=true&lastcheckok=1&order=clickcount&reverse=true`);
  for (const tag of ["quran","islamic"]) queries.push(`${API}?tag=${tag}&limit=1000&hidebroken=true&lastcheckok=1&order=clickcount&reverse=true`);
  const results = await Promise.allSettled(queries.map(q => fetch(q, { headers: { "User-Agent": "RadioJoo/110.0" }, cf: { cacheTtl: 3600 } }).then(r => r.ok ? r.json() : []).catch(() => [])));
  for (const r of results) {
    if (r.status !== "fulfilled" || !Array.isArray(r.value)) continue;
    for (const s of r.value) {
      if (!s?.name || !s?.stationuuid) continue;
      if (seen.has(s.stationuuid)) continue;
      if (!isArabicStation(s)) continue;
      const url = s.url_resolved || s.url;
      if (!url || !url.startsWith("http")) continue;
      if (!isRealStreamUrl(url)) continue;
      if (isNoiseStation(s.name, url)) continue;
      if (isRedundantQuran(s.name)) continue;
      seen.add(s.stationuuid);
      const known = isKnownBrand(s.name);
      const autoPriority = calculateExtendedPriority(s.clickcount || 0, s.votes || 0, known);
      stations.push({ uuid: s.stationuuid, name: s.name.trim(), primaryUrl: url, fallbackUrl: "", category: determineCategory(s.tags, s.name, s.countrycode), countryCode: s.countrycode || "", bitrate: s.bitrate || 0, popularity: s.clickcount || 0, known: known, priority: autoPriority });
    }
  }
  return stations;
}

function calculateExtendedPriority(clickcount, votes, known) {
  if (known) return 55;
  if (clickcount > 1000) return 50;
  if (clickcount > 500) return 45;
  if (clickcount > 100) return 40;
  if (clickcount > 50) return 35;
  if (clickcount > 20) return 30;
  if (clickcount > 10) return 25;
  if (clickcount > 5) return 20;
  if (votes > 5) return 15;
  if (clickcount > 1) return 10;
  return 5;
}

async function handleHeal(url, env, ctx) {
  const station = {
    uuid: url.searchParams.get("uuid") || "",
    name: url.searchParams.get("name") || "",
    country: url.searchParams.get("country") || "",
    category: url.searchParams.get("category") || ""
  };
  if (!station.name) return jsonResp({ success: false, reason: "no_name" }, 400);
  const t0 = Date.now();
  const cacheKey = "heal:" + (station.uuid || normalizeArabic(station.name));
  const cachedUrl = await env.STATIONS_KV?.get(cacheKey);
  if (cachedUrl && await verifyFast(cachedUrl, 1500)) {
    return jsonResp({ success: true, newUrl: cachedUrl, source: "cache", elapsed_ms: Date.now() - t0 });
  }

  const isRbUuid = station.uuid && station.uuid.length > 20 && !station.uuid.startsWith("eg_") && !station.uuid.startsWith("quran_") && !station.uuid.startsWith("intl_") && !station.uuid.startsWith("isl_");
  if (isRbUuid) {
    try {
      const rbUrl = "https://all.api.radio-browser.info/json/stations/byuuid/" + encodeURIComponent(station.uuid);
      const r = await fetch(rbUrl, { headers: { "User-Agent": "RadioJoo/110.0" } });
      if (r.ok) {
        const data = await r.json();
        if (Array.isArray(data) && data.length > 0) {
          const s = data[0];
          const candUrl = s.url_resolved || s.url;
          if (candUrl && candUrl.startsWith("http") && isRealStreamUrl(candUrl) && !isNoiseStation(s.name || "", candUrl)) {
            if (await verifyFast(candUrl, 1200)) {
              ctx.waitUntil(env.STATIONS_KV?.put(cacheKey, candUrl, { expirationTtl: 2592000 }).catch(() => {}));
              ctx.waitUntil(env.STATIONS_KV?.put("station-url:" + station.uuid, candUrl, { expirationTtl: 7776000 }).catch(() => {}));
              return jsonResp({ success: true, newUrl: candUrl, matchedName: s.name, source: "rb_byuuid", confidence: 1.0, elapsed_ms: Date.now() - t0 });
            }
          }
        }
      }
    } catch {}
  }

  const rbApis = ["https://all.api.radio-browser.info/json/stations/search","https://de1.api.radio-browser.info/json/stations/search","https://nl1.api.radio-browser.info/json/stations/search","https://at1.api.radio-browser.info/json/stations/search"];
  const nameVariants = generateNameVariants(station.name).slice(0, 3);
  const rbPromises = [];
  for (const api of rbApis) {
    for (const variant of nameVariants) {
      let q = api + "?name=" + encodeURIComponent(variant) + "&limit=15&hidebroken=true&lastcheckok=1&order=clickcount&reverse=true";
      if (station.country && station.country.length === 2) q += "&countrycode=" + station.country;
      rbPromises.push(fetch(q, { headers: { "User-Agent": "RadioJoo/110.0" }, cf: { cacheTtl: 300 } }).then(r => r.ok ? r.json() : []).catch(() => []));
    }
  }
  if (station.country && station.country.length === 2) rbPromises.push(fetch("https://all.api.radio-browser.info/json/stations/search?countrycode=" + station.country + "&language=arabic&limit=30&hidebroken=true&lastcheckok=1&order=clickcount&reverse=true", { headers: { "User-Agent": "RadioJoo/110.0" } }).then(r => r.ok ? r.json() : []).catch(() => []));
  if (station.category && station.category.length >= 3) rbPromises.push(fetch("https://all.api.radio-browser.info/json/stations/search?tag=" + encodeURIComponent(station.category.toLowerCase()) + "&language=arabic&limit=20&hidebroken=true&lastcheckok=1&order=clickcount&reverse=true", { headers: { "User-Agent": "RadioJoo/110.0" } }).then(r => r.ok ? r.json() : []).catch(() => []));
  const translitName = transliterate(station.name);
  if (translitName && translitName.length >= 3 && translitName !== station.name) rbPromises.push(fetch("https://all.api.radio-browser.info/json/stations/search?name=" + encodeURIComponent(translitName) + "&limit=10&hidebroken=true&lastcheckok=1&order=clickcount&reverse=true", { headers: { "User-Agent": "RadioJoo/110.0" } }).then(r => r.ok ? r.json() : []).catch(() => []));

  const rbResults = await Promise.allSettled(rbPromises);
  const rbCandidates = [];
  const seenUrls = new Set();
  for (const r of rbResults) {
    if (r.status !== "fulfilled" || !Array.isArray(r.value)) continue;
    for (const s of r.value) {
      const u = s.url_resolved || s.url;
      if (!u || seenUrls.has(u) || !u.startsWith("http")) continue;
      if (!isRealStreamUrl(u) || isNoiseStation(s.name || "", u)) continue;
      seenUrls.add(u);
      rbCandidates.push({ url: u, name: s.name, source: "rb_search", clickcount: s.clickcount || 0 });
    }
  }
  rbCandidates.sort((a, b) => b.clickcount - a.clickcount);
  if (rbCandidates.length > 0) {
    const validUrl = await raceVerify(rbCandidates.slice(0, 8).map(c => c.url), 1200);
    if (validUrl) {
      const matched = rbCandidates.find(c => c.url === validUrl);
      ctx.waitUntil(env.STATIONS_KV?.put(cacheKey, validUrl, { expirationTtl: 2592000 }).catch(() => {}));
      if (station.uuid) ctx.waitUntil(env.STATIONS_KV?.put("station-url:" + station.uuid, validUrl, { expirationTtl: 7776000 }).catch(() => {}));
      return jsonResp({ success: true, newUrl: validUrl, matchedName: matched?.name || station.name, source: "rb_search", confidence: 0.9, elapsed_ms: Date.now() - t0 });
    }
  }

  const specializedPromises = [searchTuneIn(station.name), searchXiph(station.name), searchDeezer(station.name), searchItunes(station.name), searchRadioGarden(station.name)];
  const specializedResults = await Promise.allSettled(specializedPromises.map(p => Promise.race([p.catch(() => []), new Promise(r => setTimeout(() => r([]), 2000))])));
  const specializedCandidates = [];
  for (const r of specializedResults) {
    if (r.status !== "fulfilled" || !Array.isArray(r.value)) continue;
    for (const item of r.value) {
      if (!item.url || !item.url.startsWith("http") || seenUrls.has(item.url)) continue;
      if (!isRealStreamUrl(item.url)) continue;
      seenUrls.add(item.url);
      specializedCandidates.push(item);
    }
  }
  if (specializedCandidates.length > 0) {
    const validUrl = await raceVerify(specializedCandidates.slice(0, 6).map(c => c.url), 1200);
    if (validUrl) {
      const matched = specializedCandidates.find(c => c.url === validUrl);
      ctx.waitUntil(env.STATIONS_KV?.put(cacheKey, validUrl, { expirationTtl: 2592000 }).catch(() => {}));
      if (station.uuid) ctx.waitUntil(env.STATIONS_KV?.put("station-url:" + station.uuid, validUrl, { expirationTtl: 7776000 }).catch(() => {}));
      return jsonResp({ success: true, newUrl: validUrl, matchedName: matched?.name || station.name, source: matched?.source || "specialized", confidence: 0.8, elapsed_ms: Date.now() - t0 });
    }
  }

  const curated = getCuratedStations();
  const targetFps = [makeFP(station.name), makeFP(transliterate(station.name))].filter(Boolean);
  let bestMatch = null, bestScore = 0;
  for (const c of curated) {
    const cFps = [makeFP(c.name), makeFP(transliterate(c.name)), ...(c.synonyms || []).map(s => makeFP(s))].filter(Boolean);
    for (const tf of targetFps) for (const cf of cFps) {
      const sim = levenshteinSim(tf, cf);
      if (sim > bestScore && sim >= 0.85) { bestScore = sim; bestMatch = c; }
    }
  }
  if (bestMatch) {
    const urls = [bestMatch.primaryUrl, ...(bestMatch.fallbackUrl || "").split(",").filter(Boolean)];
    const validUrl = await raceVerify(urls, 1500);
    if (validUrl) {
      ctx.waitUntil(env.STATIONS_KV?.put(cacheKey, validUrl, { expirationTtl: 2592000 }).catch(() => {}));
      if (station.uuid) ctx.waitUntil(env.STATIONS_KV?.put("station-url:" + station.uuid, validUrl, { expirationTtl: 7776000 }).catch(() => {}));
      return jsonResp({ success: true, newUrl: validUrl, matchedName: bestMatch.name, source: "curated", confidence: 0.95, elapsed_ms: Date.now() - t0 });
    }
  }

  return jsonResp({ success: false, needsWebSearch: true, elapsed_ms: Date.now() - t0 });
}

async function searchTuneIn(name) {
  const variants = generateNameVariants(name).slice(0, 2);
  const results = [];
  for (const v of variants) {
    try {
      const res = await fetch("https://opml.radiotime.com/search.ashx?query=" + encodeURIComponent(v) + "&render=json", { headers: { "User-Agent": "Mozilla/5.0" } });
      if (!res.ok) continue;
      const data = await res.json();
      if (data?.body) for (const item of data.body) if (item.type === "audio" && item.url) results.push({ url: item.url, name: item.text || name, source: "tunein" });
    } catch {}
  }
  return results;
}

async function searchXiph(name) {
  try {
    const translit = transliterate(name);
    const q = encodeURIComponent(translit && translit.length >= 2 ? translit : name);
    const res = await fetch("https://dir.xiph.org/search?search=" + q, { headers: { "User-Agent": "Mozilla/5.0" } });
    if (!res.ok) return [];
    const html = await res.text();
    const matches = html.match(/https?:\/\/[^\s"'<>]+\.(?:mp3|ogg|aac)/g) || [];
    return matches.slice(0, 5).map(u => ({ url: u, name: name, source: "xiph" }));
  } catch { return []; }
}

async function searchDeezer(name) {
  try {
    const res = await fetch("https://api.deezer.com/search/radio?q=" + encodeURIComponent(name), { headers: { "User-Agent": "Mozilla/5.0" } });
    if (!res.ok) return [];
    const data = await res.json();
    return data?.data ? data.data.slice(0, 5).map(r => ({ url: r.tracklist || "", name: r.title, source: "deezer" })).filter(r => r.url) : [];
  } catch { return []; }
}

async function searchItunes(name) {
  try {
    const res = await fetch("https://itunes.apple.com/search?term=" + encodeURIComponent(name) + "&media=radio&limit=10", { headers: { "User-Agent": "Mozilla/5.0" } });
    if (!res.ok) return [];
    const data = await res.json();
    return data?.results ? data.results.filter(r => r.stationUrl).slice(0, 5).map(r => ({ url: r.stationUrl, name: r.stationName, source: "itunes" })) : [];
  } catch { return []; }
}

async function searchRadioGarden(name) {
  try {
    const res = await fetch("https://radio.garden/api/search?q=" + encodeURIComponent(name), { headers: { "User-Agent": "Mozilla/5.0" } });
    if (!res.ok) return [];
    const data = await res.json();
    if (data?.hits?.hits) {
      const results = [];
      for (const hit of data.hits.hits.slice(0, 5)) {
        if (hit._source?.url) results.push({ url: "https://radio.garden/api/ara/content/listen/" + hit._source.url + "/channel.mp3", name: hit._source.title, source: "radiogarden" });
      }
      return results;
    }
    return [];
  } catch { return []; }
}

async function raceVerify(urls, timeoutMs = 1500) {
  if (!urls || urls.length === 0) return null;
  try { return await Promise.any(urls.map(u => verifyFast(u, timeoutMs).then(ok => ok ? u : null))) || null; }
  catch { return null; }
}

async function verifyFast(url, timeoutMs = 1200) {
  if (!url || !url.startsWith("http")) return false;
  try {
    const c = new AbortController();
    const t = setTimeout(() => c.abort(), timeoutMs);
    const res = await fetch(url, { method: "HEAD", headers: { "User-Agent": "Mozilla/5.0" }, redirect: "follow", signal: c.signal });
    clearTimeout(t);
    if (res.ok || res.status === 206) {
      const ct = (res.headers.get("Content-Type") || "").toLowerCase();
      const cl = parseInt(res.headers.get("Content-Length") || "0");
      if (ct.includes("text/html") || ct.includes("application/json")) return false;
      if (cl > 0 && cl < 5000) return false;
      return true;
    }
    return false;
  } catch { return false; }
}

async function handleSaveUrl(url, env, ctx) {
  const uuid = url.searchParams.get("uuid") || "";
  const newUrl = url.searchParams.get("newUrl") || "";
  const oldUrl = url.searchParams.get("oldUrl") || "";
  if (!uuid || !newUrl) return jsonResp({ success: false, reason: "missing_params" }, 400);
  if (!newUrl.startsWith("http")) return jsonResp({ success: false, reason: "invalid_url" }, 400);
  const isAlive = await verifyFast(newUrl, 2000);
  if (!isAlive) return jsonResp({ success: false, reason: "url_dead" }, 400);
  ctx.waitUntil(env.STATIONS_KV?.put("station-url:" + uuid, newUrl, { expirationTtl: 7776000 }).catch(() => {}));
  if (oldUrl && oldUrl !== newUrl) ctx.waitUntil(env.STATIONS_KV?.put("blacklist:url:" + oldUrl, "1", { expirationTtl: 2592000 }).catch(() => {}));
  return jsonResp({ success: true, verified: true });
}

async function handleGetSavedUrl(url, env) {
  const uuid = url.searchParams.get("uuid") || "";
  if (!uuid) return jsonResp({ success: false }, 400);
  const saved = await env.STATIONS_KV?.get("station-url:" + uuid);
  if (saved) return jsonResp({ success: true, url: saved });
  return jsonResp({ success: false });
}

async function handleFeedback(url, env) { return jsonResp({ status: "learned" }); }

async function handleHealthCheck(url) {
  const u = url.searchParams.get("url");
  if (!u) return new Response("Missing", { status: 400 });
  try {
    let res = await fetch(u, { method: "HEAD", redirect: "follow" });
    if (!res.ok || res.status === 403) res = await fetch(u, { method: "GET", headers: { "Range": "bytes=0-100" }, redirect: "follow" });
    if (!res.ok && res.status !== 206) return new Response("FAIL", { status: 200 });
    const icyBr = res.headers.get("icy-br") || res.headers.get("x-audio-bitrate");
    if (icyBr && !isNaN(parseInt(icyBr))) return new Response(icyBr.toString(), { status: 200 });
    return new Response("OK", { status: 200 });
  } catch { return new Response("FAIL", { status: 502 }); }
}

function makeFP(name) {
  if (!name) return "";
  return normalizeArabic(name).replace(/(fm|راديو|radio|اذاعه|اذاعة|إف\s*إم|اف\s*ام)/gi, "").replace(/[^a-z\u0600-\u06FF]/g, "").substring(0, 20);
}

function levenshteinSim(a, b) {
  if (!a || !b) return 0;
  if (a === b) return 1;
  const m = Array(b.length + 1).fill(null).map(() => Array(a.length + 1).fill(0));
  for (let i = 0; i <= a.length; i++) m[0][i] = i;
  for (let j = 0; j <= b.length; j++) m[j][0] = j;
  for (let j = 1; j <= b.length; j++) for (let i = 1; i <= a.length; i++) m[j][i] = Math.min(m[j-1][i]+1, m[j][i-1]+1, m[j-1][i-1] + (a[i-1] === b[j-1] ? 0 : 1));
  return 1 - m[b.length][a.length] / Math.max(a.length, b.length);
}

function isKnownBrand(name) {
  if (!name) return false;
  const n = name.toLowerCase();
  const known = ["aljazeera","الجزيرة","alarabiya","العربية","sky news","skynews","سكاي","bbc","بي بي سي","cnn","france 24","france24","فرانس","dw","rt arabic","sputnik","سبوتنيك","trt","cnbc","asharq","الشرق","al hadath","الحدث","monte carlo","مونت كارلو","mbc","ام بي سي","sawa","سوا","alhurra","الحرة","al mayadeen","الميادين","al alam","العالم","i24","euronews","ekhbariya","الإخبارية","extra news","اكسترا","nile news","نايل نيوز","dmc","al nahar","النهار","sada el balad","صدى البلد","nogoum","نجوم","nagham","نغم","mega fm","ميجا","mix fm","ميكس","9090","nrj","إنيرجي","nile fm","نايل","program general","البرنامج العام","radio masr","راديو مصر","shabab","شباب","riyada","رياضة","sawt el arab","صوت العرب","al aghani","الأغاني","quran","قرآن","sudais","السديس","afasy","العفاسي","minshawi","المنشاوي","hussary","الحصري","abdulbasit","عبدالباسط","sharawi","شعراوي","kishk","كشك","rifai","رفعت","kalthoum","كلثوم","fairuz","فيروز","halim","حليم","diab","دياب","rotana","روتانا","onsport","أون سبورت"];
  return known.some(b => n.includes(b));
}

function determineCategory(tags = "", name = "", cc = "") {
  const t = (tags||"").toLowerCase(), n = (name||"").toLowerCase(), c = (cc||"").toUpperCase();
  if (/quran|islamic|قرآن|قران|تلاوة|sudais|afasy|minshawi|hussary|abdulbasit|sharawi|kishk|rifai/.test(n) || /quran|islamic/.test(t)) return "QURAN";
  if (/sport|رياضة|riyada|onsport/.test(n) || /sport/.test(t)) return "SPORTS";
  if (/news|jazeera|arabiya|sky news|bbc|cnn|france 24|dw|rt arabic|sputnik|trt|cnbc|asharq|hadath|monte carlo|sawa|hurra|mayadeen|alam|i24|euronews|ekhbariya|nile news|extra news|dmc news|nahar|sada el balad|أخبار|اخبار|إخبارية/.test(n) || /news|أخبار/.test(t)) return "NEWS";
  if (/talk|توك شو|حوار/.test(n)) return "TALK";
  if (/music|موسيقى|طرب|أغاني|اغاني|tarab|hits|mix|mega|nile fm|nogoum|nagham|9090|nrj|90s|diab|rotana/.test(n) || /music|طرب/.test(t)) return "MUSIC";
  if (c === "EG") return "EGYPT";
  return "MUSIC";
}

async function handleDebug(env) {
  try {
    const cached = await env.STATIONS_KV?.get("stations:v110_0:arabic-clean", "json");
    return jsonResp({ version: "110.0.0", cached: cached ? { count: cached.stations?.length || 0, age_sec: Math.floor((Date.now() - cached.ts) / 1000) } : { count: 0 }, curatedCount: getCuratedStations().length });
  } catch (e) { return jsonResp({ error: e.message }, 500); }
}

async function handleTestArabic(url, env) {
  const q = url.searchParams.get("q");
  if (!q) return jsonResp({ error: "missing q" }, 400);
  return jsonResp({ query: q, normalized: normalizeArabic(q), variants: generateNameVariants(q), transliterated: transliterate(q) });
}

function jsonResp(o, s = 200) {
  return new Response(JSON.stringify(o), { status: s, headers: { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" } });
}

async function proxyStreamAdaptive(streamUrl, request, ctx) {
  const isHls = streamUrl.toLowerCase().includes(".m3u8");
  if (!isHls && [".mp4",".mkv",".webm"].some(e => streamUrl.toLowerCase().includes(e))) return new Response("Video blocked", { status: 403 });
  const profiles = [
    { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124.0.0.0 Safari/537.36", "Accept": "audio/*", "Icy-MetaData": "0" },
    { "User-Agent": "VLC/3.0.20 LibVLC/3.0.20", "Accept": "*/*", "Icy-MetaData": "0" }
  ];
  for (const headers of profiles) {
    try {
      const c = new AbortController();
      const t = setTimeout(() => c.abort(), 15000);
      const res = await fetch(streamUrl, { headers: new Headers(headers), redirect: "follow", signal: c.signal });
      clearTimeout(t);
      if (res.ok || res.status === 206) {
        if ((res.headers.get("Content-Type")||"").includes("mpegurl") || isHls) {
          const text = await res.text();
          if (text.startsWith("#EXTM3U")) {
            const filtered = filterHlsPlaylist(text, 32000);
            const base = new URL(request.url).origin;
            const rewritten = filtered.split("\n").map(line => {
              const l = line.trim();
              if (l.startsWith("#EXT-X-KEY")) return l.replace(/URI="([^"]+)"/, (m, uri) => { try { return `URI="${base}/?url=${encodeURIComponent(new URL(uri, streamUrl).toString())}"`; } catch { return m; } });
              if (!l || l.startsWith("#")) return line;
              try { return base + "/?url=" + encodeURIComponent(new URL(l, streamUrl).toString()); } catch { return line; }
            }).join("\n");
            return new Response(rewritten, { headers: { "Content-Type": "application/vnd.apple.mpegurl", "Access-Control-Allow-Origin": "*" } });
          }
        }
        const nh = new Headers(); nh.set("Access-Control-Allow-Origin", "*");
        return new Response(res.body, { status: res.status, headers: nh });
      }
    } catch {}
  }
  return new Response("Proxy error", { status: 502 });
}

function filterHlsPlaylist(playlist, maxBitrate) {
  const lines = playlist.split("\n");
  const out = [];
  let skipNext = false;
  for (let i = 0; i < lines.length; i++) {
    const l = lines[i].trim();
    if (l.startsWith("#EXT-X-STREAM-INF")) {
      const bwMatch = l.match(/BANDWIDTH=(\d+)/);
      const isVideoVariant = l.includes("RESOLUTION=") || l.includes("FRAME-RATE=");
      if (isVideoVariant || (bwMatch && parseInt(bwMatch[1]) > maxBitrate)) { skipNext = true; continue; }
    }
    if (skipNext) { skipNext = false; continue; }
    out.push(lines[i]);
  }
  if (out.filter(l => l.includes("#EXT-X-STREAM-INF")).length === 0) return "#EXTM3U\n#EXT-X-ERROR: NO_AUDIO_STREAM\n";
  return out.join("\n");
}