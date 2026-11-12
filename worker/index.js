const MIRRORS = ["https://de1.api.radio-browser.info", "https://fi1.api.radio-browser.info", "https://at1.api.radio-browser.info", "https://de2.api.radio-browser.info"];
const ARAB = new Set("EG SA AE KW QA BH OM YE IQ SY LB JO PS LY TN DZ MA MR SD SO DJ KM".split(" "));
const TTL = 6 * 3600 * 1000, THROTTLE = 45000;
let MEM = null, BUILDING = null, LASTREF = 0;

const BW = { A: "\u0627", b: "\u0628", t: "\u062A", v: "\u062B", j: "\u062C", H: "\u062D", x: "\u062E", d: "\u062F", "*": "\u0630", r: "\u0631", z: "\u0632", s: "\u0633", "$": "\u0634", S: "\u0635", D: "\u0636", T: "\u0637", Z: "\u0638", E: "\u0639", g: "\u063A", f: "\u0641", q: "\u0642", k: "\u0643", l: "\u0644", m: "\u0645", n: "\u0646", h: "\u0647", w: "\u0648", y: "\u064A", "'": "\u0621" };
const B = s => s.replace(/[A-Za-z*$']/g, c => BW[c] || c);
const rx = (lat, ar) => new RegExp(lat.concat(ar.map(B)).join("|"));

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

const FAME = [
  rx(["(quran|koran).{0,30}cairo|cairo.{0,30}(quran|koran)"], ["qrAn.{0,30}AlqAhrh|AlqAhrh.{0,30}qrAn"]),
  rx(["nile ?fm|nile radio"], ["nAyl"]),
  rx(["nogoum|nogom|nujum"], ["njwm"]),
  rx(["mega ?fm"], ["myjA"]),
  rx(["radio masr|masr fm"], ["rAdyw mSr"]),
  rx(["sharq.{0,6}awsat|middle east"], ["Al$rq AlAwsT"]),
  rx(["hits ?fm|9090"], ["hyts"]),
  rx(["al ?jazeera"], ["Aljzyrh"]),
  rx(["sky news arabia"], ["skAy nywz"]),
  rx(["bbc.{0,4}arabic"], []),
  rx(["monte carlo|mcd"], ["mwnt kArlw"]),
  rx(["al ?arabiya"], []),
  rx(["oum ?kalthoum|umm ?kulthum|om ?kalthoum"], ["Am klvwm"]),
  rx(["fairuz|fayrouz"], ["fyrwz"]),
  rx(["maher|muaiqly|mueaqly"], ["AlmEyqly"]),
  rx(["afasy|alafasy"], ["AlEfAsy"]),
  rx(["minshawi|husary|hussary|abdul ?basit|abdel ?basset"], ["Almn$Awy", "AlHSry", "Ebd AlbAsT"]),
  rx(["sudais|shuraim|ghamdi|dosari"], ["Alsdys", "Al$rym", "AlgAmdy", "Aldwsry"]),
];

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
    out.push({
      id: r.stationuuid || (n + u), n, nn: norm(n), u,
      cc: String(r.countrycode || "").toUpperCase(),
      lang: String(r.language || "").toLowerCase(),
      tags: String(r.tags || "").split(",").map(norm).filter(Boolean),
      v: r.votes | 0, c: r.clickcount | 0, t: r.clicktrend | 0, b: r.bitrate | 0,
    });
  }
  return out;
}
function keep(r) {
  if (RE.drop.test(r.nn + " " + r.tags.join(" "))) return false;
  if (r.cc === "EG") return true;
  if (r.lang.includes("arab")) return true;
  if (ARAB.has(r.cc) && !r.lang) return true;
  return /[\u0600-\u06FF]/.test(r.n);
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
function fame(text) {
  for (let i = 0; i < FAME.length; i++) if (FAME[i].test(text)) return 1e8 + (FAME.length - i) * 1e6;
  return 0;
}
async function build() {
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
    const key = r.nn.replace(/ /g, "") + "|" + r.cc;
    if (key.length < 3) continue;
    let g = groups.get(key); if (!g) groups.set(key, g = []);
    g.push(r);
  }
  const stations = []; let servers = 0;
  for (const [key, g] of groups) {
    g.sort((a, z) => score(z) - score(a));
    const best = g[0];
    const cc = g.map(x => x.cc).find(Boolean) || "";
    const tags = [...new Set(g.flatMap(x => x.tags))];
    const cat = classify(best.n, tags, cc);
    let p = score(best); for (let i = 1; i < g.length; i++) p += score(g[i]) * 0.5;
    p += fame(best.nn + " | " + tags.join(" | "));
    const seen = new Set(), sv = [];
    const ordered = g.slice().sort((a, z) => (/^https:/i.test(a.u) ? 0 : 1) - (/^https:/i.test(z.u) ? 0 : 1));
    for (const x of ordered) { const k = ukey(x.u); if (seen.has(k)) continue; seen.add(k); sv.push({ u: x.u, b: x.b, h: 0 }); if (sv.length >= 8) break; }
    servers += sv.length;
    stations.push({ id: "rj" + h32(key) + h32(key.split("").reverse().join("")), n: best.n, c: cat, cc, p: Math.min(2000000000, Math.round(p)), s: sv });
  }
  if (stations.length < 150) return null;
  stations.sort((a, z) => z.p - a.p);
  const body = JSON.stringify({ v: 2, stations });
  return { body, t: Date.now(), etag: '"' + h32(body) + stations.length + '"', n: stations.length, s: servers };
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
      if (kv) { try { await kv.put("catalog", c.body); await kv.put("catalog_meta", JSON.stringify({ t: c.t, etag: c.etag, n: c.n, s: c.s })); } catch (e) { } }
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
      if (c0 && Date.now() - LASTREF < THROTTLE) return json({ stations: c0.n, servers: c0.s, verified: c0.s, throttled: true });
      LASTREF = Date.now();
      const c = (await rebuild(env)) || c0;
      if (!c) return json({ stations: 0, servers: 0, verified: 0, throttled: false });
      return json({ stations: c.n, servers: c.s, verified: c.s, throttled: false });
    }
    if (path === "/v2/heal") {
      const c = await catalog(env, ctx); if (!c) return json({}, 404);
      const id = url.searchParams.get("id");
      const st = JSON.parse(c.body).stations.find(s => s.id === id);
      if (!st) return json({}, 404);
      const key = norm(st.n).replace(/ /g, "");
      const found = compact(await rb("/json/stations/search?hidebroken=true&limit=40&order=clickcount&reverse=true&name=" + encodeURIComponent(st.n)));
      const have = new Set(st.s.map(x => ukey(x.u)));
      const extra = found.filter(r => norm(r.n).replace(/ /g, "") === key && !have.has(ukey(r.u))).sort((a, z) => score(z) - score(a)).map(r => ({ u: r.u, b: r.b, h: 0 }));
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