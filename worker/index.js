const MIRRORS = ["https://de1.api.radio-browser.info", "https://fi1.api.radio-browser.info", "https://at1.api.radio-browser.info", "https://de2.api.radio-browser.info"];
const ARAB = new Set("EG SA AE KW QA BH OM YE IQ SY LB JO PS LY TN DZ MA MR SD SO DJ KM".split(" "));
const TTL = 6 * 3600 * 1000, THROTTLE = 45000;
let MEM = null, BUILDING = null, LASTREF = 0;

const BW = { A: "\u0627", b: "\u0628", t: "\u062A", v: "\u062B", j: "\u062C", H: "\u062D", x: "\u062E", d: "\u062F", "*": "\u0630", r: "\u0631", z: "\u0632", s: "\u0633", "$": "\u0634", S: "\u0635", D: "\u0636", T: "\u0637", Z: "\u0638", E: "\u0639", g: "\u063A", f: "\u0641", q: "\u0642", k: "\u0643", l: "\u0644", m: "\u0645", n: "\u0646", h: "\u0647", w: "\u0648", y: "\u064A", "'": "\u0621" };
const B = s => s.replace(/[A-Za-z*$']/g, c => BW[c] || c);
const rx = (lat, ar) => new RegExp(lat.concat(ar.map(B)).join("|"));
const AR_SCRIPT = /[\u0600-\u06FF]/;

function norm(s) {
  return String(s || "").toLowerCase()
    .replace(/[\u064B-\u065F\u0670\u0640]/g, "")
    .replace(/[\u0623\u0625\u0622\u0671]/g, "\u0627")
    .replace(/\u0629/g, "\u0647")
    .replace(/\u0649/g, "\u064A")
    .replace(/[^a-z0-9\u0600-\u06FF ]+/g, " ")
    .replace(/\s+/g, " ").trim();
}
function h32(s) { let h = 2166136261; for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); } return (h >>> 0).toString(36); }
const ukey = u => String(u).replace(/^https?:\/\//i, "").replace(/\/+$/, "").toLowerCase();

const RE = {
  quran: rx(["qur ?an|koran|kuran|tilawa|tarteel|tajweed|islam|muslim|sunnah|hadith|fatwa|nasheed|makkah|mecca|medina"],
    ["qrAn", "tlAwh", "trtyl", "tjwyd", "AslAm", "ftAwy", "ftwy", "AbthAlAt", "An$Ad", "AlHrm Almky", "dEA'", "Almn$Awy", "AlHSry", "Ebd AlbAsT", "AlmEyqly", "AlEfAsy", "Alsdys", "Al$rym", "AlgAmdy", "AlH*yfy", "Aldwsry", "AlqTAmy"]),
  sports: rx(["sport|riyada|riyadah|football|soccer|kooora|kora "], ["ryADh", "kwrh", "mlAEb", "Aldwry", "krh Alqdm"]),
  news: rx(["news|akhbar|al ?jazeera|aljazeera|al ?arabiya|alarabiya|france ?24|dw arabic|rt arabic|monte carlo|radio sawa|alhurra"],
    ["AxbAr", "n$rh", "Aljzyrh", "skAy nywz", "mwnt kArlw", "frAns 24", "EAjl"]),
  talk: rx(["talk|hewar|discussion|call ?in"], ["HwAr", "brnAmj", "twk", "nqA$"]),
  tarab: rx(["tarab|tarb|oriental|oum ?kalthoum|umm ?kulthum|om ?kalthoum|oum ?kalsoum|fairuz|fayrouz|abdel ?halim|abdel ?wahab|farid.{0,12}atrash|golden (era|age)|vintage|andalus|muwashah|arabic classic|classic arabic"],
    ["Trb", "Am klvwm", "fyrwz", "Ebd AlHlym", "Ebd AlwhAb", "fryd AlATr$", "Alzmn Aljmyl", "zmAn", "qdym", "mw$HAt", "ASAlh"]),
  music: rx(["music|musique|pop|hits|song|dance|hip hop|rap|rock|jazz|mahragan|mahraganat|chaabi|shaabi|top ?40|lounge|rnb"], ["mwsyqy", "mhrjAnAt", "$Eby", "AgAny"]),
  drop: rx(["christian|gospel|bible|church|catholic|orthodox"], ["msyHy", "knys"]),
};
const TAG_NEWS = /^(news|news talk|akhbar|world news)$/, TAG_TALK = /^(talk|talk radio|news talk|discussion)$/;
const EGYNAME = rx(["egypt|misr|masr|cairo|alexandria|nile|ertu"], ["mSr", "AlqAhrh", "AlAskndryh"]);

/* ===== FAME TABLE: array order = real-world fame rank (earlier = more famous) =====
   c = forced category, eg = Egyptian only (cc EG or unknown), multi = regional radio (kept separate per name),
   intl = international broadcaster (must be Arabic), nocc = hide country so it does not enter Egypt chip */
const FAME = [];
const F = (lat, ar, o) => FAME.push(Object.assign({ re: rx(lat, ar || []) }, o || {}));
const QN = "(?:qur ?an|koran|kuran)";
const EGO = { c: "EGYPT", eg: 1 };
const M = { multi: 1 };

// --- EGYPT ---
F(["nogoum|nogom|nujum|nogum"], ["njwm"], EGO);
F(["nile ?fm|nilefm|nile radio"], ["nAyl|nyl Af Am"], EGO);
F(["radio ?masr\\b|masr ?fm|radio ?misr\\b"], ["rAdyw mSr(?!y)"], EGO);
F(["mega ?fm|mega 92"], ["myjA"], EGO);
F(["nagham|naghem"], ["ngm"], EGO);
F(["9090"], [], EGO);
F(["radio ?hits|hits ?fm"], ["hyts"], EGO);
F(["sha+bi ?fm|sha+bi radio|sha3by"], ["$Eby Af Am|rAdyw $Eby"], EGO);
F(["masr el ?(?:gdid|jdid|gedid)|misr el ?gdid|new egypt"], ["mSr Aljdydh"], EGO);
F(["general (?:program|programme)|barnameg ?(?:el|al)? ?aam"], ["AlbrnAmj AlEAm"], EGO);
F(["voice of (?:the )?arabs|sawt ?(?:el|al)? ?arab|sout el arab"], ["Swt AlErb"], EGO);
F(["middle east (?:radio|fm)|sharq ?(?:el|al)? ?awsat\\b|el sharq el awsat"], ["Al$rq AlAwsT"], EGO);
F(["youth ?(?:and)? ?sports|shabab ?(?:wa|wel|we)? ?riyada"], ["Al$bAb wAlryADh"], { c: "SPORTS", eg: 1 });
F(["cultural (?:program|programme)"], ["AlbrnAmj AlvqAfy"], EGO);
F(["egyptian radio|ertu\\b|radio of egypt"], ["AlA*AEh AlmSryh"], EGO);
F([`${QN}.{0,30}(?:cairo|egypt|misr|masr)|(?:cairo|egypt).{0,30}${QN}`], ["qrAn.{0,30}(?:AlqAhrh|mSr)|(?:AlqAhrh|mSr).{0,30}qrAn"], { c: "QURAN", eg: 1 });

// --- NEWS ---
F(["al ?jazeera|aljazeera|jazeera"], ["Aljzyrh"], { c: "NEWS" });
F(["al ?arabiya|alarabiya|al ?arabiyah", "^" + B("AlErbyh") + "( |$)"], ["qnAh AlErbyh"], { c: "NEWS" });
F(["sky ?news.{0,8}arab|sky ?news arabia"], ["skAy nywz"], { c: "NEWS", intl: 1 });
F(["bbc.{0,12}arab|bbc arabic"], ["by by sy"], { c: "NEWS", intl: 1 });
F(["monte ?carlo|mcd (?:fm|arabic|doualiya)|mc doualiya"], ["mwnt kArlw"], { c: "NEWS", intl: 1 });
F(["france ?24"], ["frAns ?24"], { c: "NEWS", intl: 1 });
F(["asharq|ashark|al ?sharq (?:news|bloomberg)"], ["Al$rq llAxbAr|Al$rq blwmbrj|Al$rq bylwmbyrj"], { c: "NEWS" });
F(["\\bdw\\b|deutsche welle"], ["dwyt$h fylh|dw Erbyh"], { c: "NEWS", intl: 1 });
F(["\\brt\\b.{0,10}arab|russia today"], ["rwsyA Alywm"], { c: "NEWS", intl: 1 });
F(["radio sawa|\\bsawa\\b"], ["rAdyw swA"], { c: "NEWS", intl: 1 });
F(["alhurra|al ?hurra"], ["AlHrh"], { c: "NEWS", intl: 1 });
F(["al ?mayadeen|almayadeen"], ["AlmyAdyn"], { c: "NEWS" });
F(["trt ?arab"], ["ty Ar ty Erby"], { c: "NEWS", intl: 1 });
F(["sputnik"], ["sbwtnyk"], { c: "NEWS", intl: 1 });

// --- QURAN ---
F([`saudi.{0,25}${QN}|${QN}.{0,25}saudi|ksa.{0,10}${QN}|makk?ah.{0,15}(?:radio|${QN})|mecca.{0,15}(?:radio|${QN})|al ?haram`], ["qrAn.{0,30}AlsEwdyh|AlsEwdyh.{0,30}qrAn|AlHrm Almky|AlHrmyn"], { c: "QURAN" });
F(["min?shawi|menshawy|minshawy|menshawi"], ["Almn$Awy"], { c: "QURAN" });
F(["h[ou]s+ar[iy]"], ["AlHSry"], { c: "QURAN" });
F(["abd(?:ul|el|al)? ?(?:al ?)?bas[iy]t|abdel ?bas+et|abdulbasit"], ["Ebd AlbAsT"], { c: "QURAN" });
F(["muaiqly|mueaqly|moaiqly|moayqly|maher.{0,12}(?:mu|mo)"], ["AlmEyqly"], { c: "QURAN" });
F(["afasy|alafasy|alafasi|afasi"], ["AlEfAsy"], { c: "QURAN" });
F(["sudais|sudays|sudaiss"], ["Alsdys"], { c: "QURAN" });
F(["shuraim|shuraym|shureym"], ["Al$rym"], { c: "QURAN" });
F(["ghamdi|ghamidi"], ["AlgAmdy"], { c: "QURAN" });
F(["dosari|dossary|dosary"], ["Aldwsry"], { c: "QURAN" });
F(["hudhaify|huthaify|hudhayfi|hudaify"], ["AlH*yfy"], { c: "QURAN" });
F(["qatami"], ["AlqTAmy"], { c: "QURAN" });
F(["ajmi|al ?ajmy"], ["AlEjmy"], { c: "QURAN" });
F(["shatri|shaatree"], ["Al$ATry"], { c: "QURAN" });
F(["tablawi|toblawi"], ["AlTblAwy"], { c: "QURAN" });
F(["al ?majd\\b|almajd\\b"], ["Almjd"], { c: "QURAN" });

// --- TARAB / MUSIC ICONS ---
F(["oum ?kal[ts]h?oum|umm? ?kul[ts]h?oum|om ?kalthoum|oum ?koulsoum|umm?kalthum|um ?kalthoum|umm ?kulthum|um ?kulthum|om ?kalsoum"], ["Am klvwm"], { c: "MUSIC", nocc: 1 });
F(["abd(?:el|ul)? ?hal[ei]+m|abdelhalim"], ["Ebd AlHlym"], { c: "MUSIC", nocc: 1 });
F(["fairuz|fayrouz|fairouz|feyrouz|fayruz"], ["fyrwz"], { c: "MUSIC", nocc: 1 });
F(["abd(?:el|ul)? ?wahab|abdelwahab"], ["Ebd AlwhAb"], { c: "MUSIC", nocc: 1 });
F(["far[ie]e?d.{0,12}atrash"], ["fryd AlATr$"], { c: "MUSIC", nocc: 1 });
F(["masriyat|masryat|masriat"], ["mSryAt"], { c: "MUSIC", eg: 1 });
F(["asmahan|asmahane"], ["AsmhAn"], { c: "MUSIC", nocc: 1 });
F(["sabah fakhri"], ["SbAH fxry"], { c: "MUSIC", nocc: 1 });
F(["sayed darwish|sayyid darwish"], ["syd drwy$"], { c: "MUSIC", nocc: 1 });
F(["najat (?:el|al)? ?saghira"], ["njAh AlSgyrh"], { c: "MUSIC", nocc: 1 });
F(["warda al ?jaz"], ["wrdh Aljz"], { c: "MUSIC", nocc: 1 });
F(["golden (?:era|age)|zaman el ?gameel|zaman al ?jameel|zaman ?gameel"], ["Alzmn Aljmyl|zmAn Aljmyl"], { c: "MUSIC", multi: 1 });

// --- MAJOR ARAB STATE / REGIONAL RADIOS ---
F(["saudi (?:radio|broadcasting)|\\bsbc\\b|mbc ?fm|rotana|panorama fm|jeddah (?:radio|fm)|riyadh (?:radio|fm)"], ["A*AEh AlsEwdyh|A*AEh AlryAD|rwtAnA|A*AEh jdh"], M);
F(["radio jordan|jordan (?:radio|broadcasting)|hayat fm|amen fm|beat fm|mood fm"], ["A*AEh AlArdn|hyAh Af Am"], M);
F(["emarat|dubai (?:radio|fm|92|eye)|abu dhabi (?:radio|fm|classic)|sharjah (?:radio|fm)|radio sharjah|uae (?:radio|quran)"], ["A*AEh dby|A*AEh Al$Arqh|AlAmArAt|AbwZby"], M);
F(["kuwait (?:radio|quran|fm)|radio kuwait|marina fm|kuwait broadcasting"], ["A*AEh AlkwyT|AlkwyT"], M);
F(["qatar (?:radio|quran|broadcasting)|radio qatar|\\bqbs\\b|al ?rayyan|qatar sports"], ["A*AEh qTr|AlryAn|qTr"], M);
F(["bahrain (?:radio|fm|quran)|radio bahrain"], ["A*AEh AlbHryn"], M);
F(["oman (?:radio|fm|quran)|radio oman|sultanate of oman"], ["A*AEh slTnh EmAn|EmAn"], M);
F(["sawt (?:lubnan|lebnan|libnan|el ?ghad|al ?ghad)|voice of lebanon|lebanese radio|radio liban|radio lebanon|tele liban|nrj (?:lebanon|liban)|radio one lebanon|light fm|sawt el mada"], ["swt lbnAn|Swt Algd|Alnwr"], M);
F(["syria(?:n)? (?:radio|broadcasting)|damascus (?:fm|radio)|sham fm|radio syria"], ["A*AEh swryA|A*AEh dm$q|$Am Af Am"], M);
F(["iraq(?:i)? (?:radio|media|network)|radio iraq|dijlah|al ?rasheed|\\bimn\\b|nas radio|mirbad|al ?sumaria"], ["A*AEh AlErAq|djlh|Alr$yd"], M);
F(["palestin|filastin|falastin|raya fm|ajyal|nisaa|nablus|aqsa|bethlehem 2000|ramallah|\\bquds\\b"], ["flsTyn|rAyh|AjyAl|nsA'|AlAqSy|nAbls|rAm Allh"], M);
F(["yemen (?:radio|national|fm)|radio yemen|aden (?:radio|fm)|sana ?a (?:radio|fm)|radio aden"], ["A*AEh Alymn|A*AEh Edn"], M);
F(["medi ?1|hit radio|chada|\\bmfm\\b|radio mars|aswat|mohammed (?:vi|6|sixth)|moroccan radio|radio maroc|radio plus|atlantic radio|cap radio|luxe radio|2m radio"], ["mydy 1|A*AEh Almgrb|mHmd AlsAds"], M);
F(["alg.{0,2}ri|radio dzair|dzair|jil fm|bahdja|chaine (?:1|2|3)|radio coran"], ["A*AEh AljzAyr|AljzAyr"], M);
F(["radio tunis|tunisia|tunisie|mosaique|shems fm|jawhara|express fm|zitouna|diwan fm|cap fm|nationale tunisienne|\\bratn\\b"], ["twns|Alzytwnh"], M);
F(["libya (?:radio|fm|quran)|radio libya|libya al ?(?:watan|hadith)"], ["A*AEh lybyA"], M);
F(["sudan(?:ese)? (?:radio|national|quran|broadcasting)|omdurman|radio sudan"], ["swdAn|AmdrmAn"], M);
F(["mauritani|nouakchott|somali (?:national )?radio|radio mogadishu|mogadishu|djibouti|comoros|radio comores"], ["mwrytAnyA|AlSwmAl|jybwty"], M);

const FC = new Map();
function fameOf(nn) {
  let v = FC.get(nn); if (v !== undefined) return v;
  let best = -1, bl = 0;
  for (let i = 0; i < FAME.length; i++) {
    const m = FAME[i].re.exec(nn);
    if (m && m[0].length > bl) { bl = m[0].length; best = i; }
  }
  FC.set(nn, best);
  return best;
}

const NOISE = new Set(["fm", "radio", "live", "stream", "streaming", "online", "hd", "mp3", "aac", "official", "station", "channel", "the", "mhz", "khz", "kbps", "www", "com", "net", "http", "https", "el", "al"].concat(["A*AEh", "AlA*AEh", "rAdyw", "AlrAdyw", "bv", "mbA$r", "mbA$rh", "AwnlAyn"].map(B)));
const AF_AM = new RegExp(B("Af Am"), "g");
function canon(name) {
  let s = String(name || "").toLowerCase()
    .replace(/\b(?:8[7-9]|9\d|10[0-8])(?:[.,]\d{1,2})?\b(?!\d)/g, " ")
    .replace(/\b\d{2,3}\s?k(?:bps)?\b/g, " ");
  s = norm(s).replace(AF_AM, " ");
  return s.split(" ").filter(Boolean)
    .map(w => (w.length > 4 && w.startsWith("\u0627\u0644")) ? w.slice(2) : w)
    .filter(w => !NOISE.has(w)).join("");
}
function gkey(r) {
  const e = r.f >= 0 ? FAME[r.f] : null;
  if (e && !e.multi) return "F" + r.f;
  let c = canon(r.n);
  if (c.length < 2) c = r.nn.replace(/ /g, "");
  if (c.length < 2) return "";
  return c + "|" + (e ? r.cc : "");
}

async function rb(path) {
  const st = Math.floor(Math.random() * MIRRORS.length);
  for (let i = 0; i < 2; i++) {
    try {
      const r = await fetch(MIRRORS[(st + i) % MIRRORS.length] + path, { headers: { "User-Agent": "RadioJoo/2.0", "Accept": "application/json" }, signal: AbortSignal.timeout(25000) });
      if (r.ok) { const j = await r.json(); if (Array.isArray(j)) return j; }
    } catch (e) { }
  }
  return [];
}
function compact(a) {
  const out = [];
  for (const r of a) {
    const u = r.url_resolved || r.url;
    if (!u || !/^https?:\/\//i.test(u)) continue;
    const n = String(r.name || "").replace(/\s+/g, " ").trim();
    if (!n) continue;
    const nn = norm(n);
    out.push({
      id: r.stationuuid || (n + u), n, nn, u,
      cc: String(r.countrycode || "").toUpperCase(),
      lang: String(r.language || "").toLowerCase(),
      tags: String(r.tags || "").split(",").map(norm).filter(Boolean),
      v: r.votes | 0, c: r.clickcount | 0, t: r.clicktrend | 0, b: r.bitrate | 0,
      f: fameOf(nn),
    });
  }
  return out;
}
function keep(r) {
  if (RE.drop.test(r.nn + " " + r.tags.join(" "))) return false;
  const e = r.f >= 0 ? FAME[r.f] : null;
  if (e) {
    if (e.eg) return r.cc === "EG" || !r.cc;
    if (e.multi) return ARAB.has(r.cc);
    if (e.intl) return r.lang.includes("arab") || AR_SCRIPT.test(r.n) || ARAB.has(r.cc) || /arab/.test(r.nn);
    return true;
  }
  if (r.cc === "EG") return true;
  return !r.cc && EGYNAME.test(r.nn);
}
const score = r => r.c + r.v * 8 + Math.max(0, r.t) * 3;
function classify(name, tags, cc) {
  const nn = norm(name);
  const text = nn + " | " + tags.join(" | ");
  const musicTag = tags.some(t => RE.music.test(t));
  if (RE.quran.test(text)) return "QURAN";
  if (RE.sports.test(text)) return "SPORTS";
  if (RE.news.test(nn) || (tags.some(t => TAG_NEWS.test(t)) && !musicTag)) return "NEWS";
  if (RE.talk.test(nn) || (tags.some(t => TAG_TALK.test(t)) && !musicTag)) return "TALK";
  if (RE.tarab.test(text)) return "MUSIC";
  if (cc === "EG") return (musicTag || RE.music.test(nn)) ? "MUSIC" : "EGYPT";
  return "MUSIC";
}
async function build() {
  FC.clear();
  const b = "/json/stations/search?hidebroken=true&order=clickcount&reverse=true&limit=";
  const Q = [b + "6000&language=arabic", b + "4000&countrycode=EG"];
  for (const cc of ARAB) if (cc !== "EG") Q.push(b + "1500&countrycode=" + cc);
  for (const t of ["quran", "islamic", "islam", "tarab", "oriental"]) Q.push(b + "2000&tag=" + encodeURIComponent(t));
  for (const t of ["news", "talk", "music", "sports"]) Q.push(b + "3000&language=arabic&tag=" + t);
  for (const n of ["quran", "koran", B("qrAn"), B("AlqrAn"), B("A*AEh"), "\u0625" + B("*AEh"), B("rAdyw")]) Q.push(b + "2000&name=" + encodeURIComponent(n));
  const all = new Map();
  for (let i = 0; i < Q.length; i += 5) {
    const res = await Promise.all(Q.slice(i, i + 5).map(q => rb(q)));
    for (const arr of res) for (const r of compact(arr)) if (!all.has(r.id)) all.set(r.id, r);
  }
  const groups = new Map();
  for (const r of all.values()) {
    if (!keep(r)) continue;
    const key = gkey(r);
    if (!key) continue;
    let g = groups.get(key); if (!g) groups.set(key, g = []);
    g.push(r);
  }
  const stations = []; let servers = 0;
  for (const [key, g] of groups) {
    g.sort((a, z) => score(z) - score(a));
    const best = g[0];
    const e = best.f >= 0 ? FAME[best.f] : null;
    let cc = g.map(x => x.cc).find(Boolean) || "";
    if (e && e.eg && !cc) cc = "EG";
    if (e && e.nocc) cc = "";
    const tags = [...new Set(g.flatMap(x => x.tags))];
    const cat = (e && e.c) ? e.c : classify(best.n, tags, cc);
    let sc = score(best); for (let i = 1; i < g.length; i++) sc += score(g[i]) * 0.5;
    sc = Math.round(sc);
    const p = e ? (1900000000 - best.f * 1000000 + Math.min(999999, sc)) : Math.min(1500000000, sc);
    const seen = new Set(), sv = [];
    const ordered = g.slice().sort((a, z) => (/^https:/i.test(a.u) ? 0 : 1) - (/^https:/i.test(z.u) ? 0 : 1));
    for (const x of ordered) { const k = ukey(x.u); if (seen.has(k)) continue; seen.add(k); sv.push({ u: x.u, b: x.b, h: 0 }); if (sv.length >= 8) break; }
    servers += sv.length;
    stations.push({ id: "rj" + h32(key) + h32(key.split("").reverse().join("")), n: best.n, c: cat, cc, p: Math.min(2000000000, p), s: sv });
  }
  if (stations.length < 60) return null;
  stations.sort((a, z) => z.p - a.p);
  const cats = {};
  for (const s of stations) cats[s.c] = (cats[s.c] || 0) + 1;
  const body = JSON.stringify({ v: 2, stations });
  return { body, t: Date.now(), etag: '"' + h32(body) + stations.length + '"', n: stations.length, s: servers, cats };
}
function getKV(env) {
  return env.RADIO_KV || env.KV || Object.values(env || {}).find(v => v && typeof v.get === "function" && typeof v.put === "function" && typeof v.list === "function") || null;
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
      const c = await build(); if (!c) return null;
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
async function probe(u) {
  try {
    const r = await fetch(u, { headers: { "Range": "bytes=0-1", "Icy-MetaData": "0", "User-Agent": "RadioJoo/2.0" }, signal: AbortSignal.timeout(4000) });
    const ct = (r.headers.get("content-type") || "").toLowerCase();
    try { r.body && r.body.cancel(); } catch (e) { }
    return (r.ok || r.status === 206) && /audio|mpegurl|ogg|octet-stream|aac|mpeg/.test(ct);
  } catch (e) { return false; }
}
export default {
  async fetch(req, env, ctx) {
    const url = new URL(req.url);
    const path = url.pathname.replace(/\/+$/, "");
    if (path === "/v2/stations") {
      const c = await catalog(env, ctx);
      if (!c) return json({ stations: [], retry: 25 });
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
      const st = JSON.parse(c.body).stations.find(s => s.id === id);
      if (!st) return json({}, 404);
      const nn0 = norm(st.n);
      const sk = gkey({ n: st.n, nn: nn0, cc: st.cc || "", f: fameOf(nn0) }).split("|")[0];
      const found = compact(await rb("/json/stations/search?hidebroken=true&limit=40&order=clickcount&reverse=true&name=" + encodeURIComponent(st.n)));
      const have = new Set(st.s.map(x => ukey(x.u)));
      const extra = found.filter(r => gkey(r).split("|")[0] === sk && !have.has(ukey(r.u))).sort((a, z) => score(z) - score(a)).map(r => ({ u: r.u, b: r.b, h: 0 }));
      const cand = st.s.concat(extra).slice(0, 12);
      const alive = await Promise.all(cand.map(x => probe(x.u)));
      st.s = cand.filter((x, i) => alive[i]).concat(cand.filter((x, i) => !alive[i])).slice(0, 10);
      return json({ station: st });
    }
    if (path === "/v2/report") return json({ ok: true });
    return json({ ok: true, service: "radio-joo-proxy", v: 2 });
  },
  async scheduled(event, env, ctx) { ctx.waitUntil(rebuild(env)); },
};