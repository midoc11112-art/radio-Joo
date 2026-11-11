// Radio-Joo Cloud Brain v3
// - Discovers Arabic / Egyptian radio stations (radio-browser + curated seeds), verifies every stream,
//   keeps up to 5 servers per station ranked LOWEST bitrate first, learns from phone reports,
//   and heals dead stations on demand. Runs by itself on a Cron Trigger. State lives in one KV key.
import SEEDS from "./seeds.js";

const RB_MIRRORS = ["https://de1.api.radio-browser.info", "https://nl1.api.radio-browser.info", "https://at1.api.radio-browser.info", "https://de2.api.radio-browser.info"];
const UA = "RadioJoo-Cloud/3.0";
const MAX_SERVERS = 5;
const REDISCOVER_MS = 6 * 3600e3;
const ARAB_CC = ["EG", "SA", "AE", "KW", "QA", "BH", "OM", "JO", "LB", "SY", "IQ", "PS", "YE", "SD", "LY", "TN", "DZ", "MA", "MR", "SO", "DJ", "KM"];
const TAGS = ["quran", "news", "talk", "tarab", "sports", "islam"];
const MIN_VOTES = { QURAN: 120, NEWS: 50, TALK: 50, SPORTS: 50, MUSIC: 300, EGYPT: 0 };
const POP_BASE = { QURAN: 1000, EGYPT: 960, NEWS: 900, TALK: 860, SPORTS: 850, MUSIC: 800 };
const CORS = { "Access-Control-Allow-Origin": "*" };

// discovery plan: Egypt first, then the curated seeds, then the rest of the Arab world and tag pools
const TASKS = [
  { k: "cc", cc: "EG" },
  ...SEEDS.map((_, i) => ({ k: "seed", i })),
  ...ARAB_CC.filter((c) => c !== "EG").map((cc) => ({ k: "cc", cc })),
  { k: "lang" },
  ...TAGS.map((tag) => ({ k: "tag", tag })),
];

// ------------------------------------------------------------------ text helpers
const DIAC = /[\u064B-\u0652\u0640]/g;
const GENERIC = new Set(["radio", "راديو", "اذاعه", "fm", "اف", "ام", "live", "مباشر", "online", "the", "ال"]);
const isAr = (t) => /[\u0600-\u06ff]/.test(t);
export function norm(s) {
  s = (s || "").toLowerCase().replace(DIAC, "").replace(/[إأآ]/g, "ا").replace(/ة/g, "ه").replace(/ى/g, "ي");
  return s.replace(/[^0-9a-z\u0621-\u064a ]+/g, " ").split(/\s+/).filter(Boolean).join(" ");
}
function dice(a, b) {
  if (a === b) return 1;
  if (a.length < 2 || b.length < 2) return 0;
  const bg = (s) => { const m = new Map(); for (let i = 0; i < s.length - 1; i++) { const g = s.slice(i, i + 2); m.set(g, (m.get(g) || 0) + 1); } return m; };
  const A = bg(a), B = bg(b); let hit = 0;
  for (const [g, n] of A) hit += Math.min(n, B.get(g) || 0);
  return (2 * hit) / (a.length - 1 + b.length - 1);
}
export function nameScore(stationName, queries) {
  const n = norm(stationName), all = n.split(" ");
  const nt = all.filter((t) => !GENERIC.has(t)); const ntt = nt.length ? nt : all;
  let best = 0;
  for (const q of queries) {
    const qn = norm(q); const qa = qn.split(" ").filter(Boolean);
    const qt = qa.filter((t) => !GENERIC.has(t)); const qq = qt.length ? qt : qa;
    if (!qq.length) continue;
    if (qq.length === 1) {
      const extras = ntt.filter((t) => t !== qq[0] && !/^\d+$/.test(t) && isAr(t) === isAr(qq[0]));
      if (ntt.includes(qq[0]) && (!extras.length || /^\d+$/.test(qq[0]))) { best = Math.max(best, 1); continue; }
    } else if (qq.every((t) => all.includes(t) || n.includes(t))) { best = Math.max(best, 1); continue; }
    best = Math.max(best, dice(qn, n) * 0.95);
  }
  return best;
}
export function nameKey(name) {
  const t = norm(name).split(" ").filter((x) => x && !GENERIC.has(x) && !/^\d+$/.test(x));
  return t.join(" ") || norm(name);
}
export function classify(name, tags, cc) {
  const t = norm((name || "") + " " + String(tags || "").replace(/,/g, " "));
  const has = (...ks) => ks.some((k) => t.includes(norm(k)));
  if (has("quran", "قران", "تلاوه", "islam", "اسلام", "sunnah", "سنه")) return "QURAN";
  if (has("sport", "رياض", "سبورت", "kora", "كوره")) return "SPORTS";
  if (has("news", "اخبار", "الجزيره", "العربيه", "sky news", "bbc", "france 24", "rfi", "monte carlo", "مونت كارلو")) return "NEWS";
  if (has("talk", "حوار", "توك", "ثقاف", "cultural", "program", "variety")) return "TALK";
  if (has("music", "اغاني", "طرب", "hits", "pop", "oriental", "tarab", "khaleeji", "song")) return "MUSIC";
  return cc === "EG" ? "EGYPT" : null;
}
const ukey = (u) => u.replace(/^https?:\/\/(www\.)?/i, "").split("?")[0].replace(/[\/;]+$/, "").toLowerCase();
const bucket = (k) => (!k ? 3 : k <= 40 ? 0 : k <= 72 ? 1 : k <= 112 ? 2 : 3);   // data-saver tiers: ~32k, ~64k, ~96k, more

// ------------------------------------------------------------------ probing (real audio only)
const MP3_V1 = [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320];
const MP3_V2 = [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160];
export function mp3Bitrate(d) {
  for (let i = 0; i < Math.min(d.length - 4, 6000); i++) {
    if (d[i] === 0xff && (d[i + 1] & 0xe0) === 0xe0) {
      const ver = (d[i + 1] >> 3) & 3, layer = (d[i + 1] >> 1) & 3, idx = d[i + 2] >> 4;
      if (layer === 1 && ver !== 1 && idx > 0 && idx < 15) return (ver === 3 ? MP3_V1 : MP3_V2)[idx];
    }
  }
  return 0;
}
function looksAudio(d) {
  if (d.length < 1500) return false;
  let sync = 0;
  for (let i = 0; i < d.length - 4; i++) { if (d[i] === 0xff && (d[i + 1] & 0xe0) === 0xe0) { sync++; i += 3; } }
  if (sync >= 3) return true;
  let adts = 0;
  for (let i = 0; i < d.length - 1; i++) if (d[i] === 0xff && (d[i + 1] === 0xf1 || d[i + 1] === 0xf9)) adts++;
  if (adts >= 3) return true;
  const head = String.fromCharCode(...d.slice(0, 4));
  return head === "OggS" || head.startsWith("ID3");
}
export function analyzeHls(text) {
  if (!text.trimStart().startsWith("#EXTM3U")) return { ok: false, why: "bad-hls" };
  const lines = text.split(/\r?\n/).map((l) => l.trim());
  const variants = lines.filter((l) => l.startsWith("#EXT-X-STREAM-INF"));
  if (!variants.length) return lines.some((l) => l && !l.startsWith("#")) ? { ok: true, kbps: 0 } : { ok: false, why: "hls-empty" };
  const audio = [];
  for (const v of variants) {
    if (/RESOLUTION=|FRAME-RATE=/.test(v) || /CODECS="[^"]*(avc1|hvc1|hev1|vp0?9|av01)/.test(v)) continue;
    const m = v.match(/BANDWIDTH=(\d+)/); audio.push(m ? Math.round(parseInt(m[1], 10) / 1000) : 0);
  }
  return audio.length ? { ok: true, kbps: Math.min(...audio) } : { ok: false, why: "hls-video-only" };
}
// -> { ok, hard, kbps, hls }. hard=false means inconclusive (timeout / network / unusual server) and must not count as a failure
export async function probe(url, ms = 6500) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), ms);
  try {
    if (/youtube\.com|youtu\.be|twitch\.tv|\/video\//i.test(url)) return { ok: false, hard: true };
    const r = await fetch(url, { headers: { "User-Agent": UA, "Icy-MetaData": "0", "Accept": "*/*" }, redirect: "follow", signal: ctl.signal });
    if (!r.ok) { try { r.body && r.body.cancel(); } catch {} return { ok: false, hard: r.status === 404 || r.status === 410 || r.status === 403 }; }
    const ct = (r.headers.get("content-type") || "").toLowerCase();
    const isM3u8 = ct.includes("mpegurl") || /\.m3u8(\?|$)/i.test(new URL(r.url || url).pathname + "");
    if (isM3u8) {
      const a = analyzeHls((await r.text()).slice(0, 65536));
      return { ok: a.ok, hard: true, kbps: a.kbps || 0, hls: 1 };
    }
    if (ct.startsWith("video/") && !ct.includes("mp2t")) { try { r.body.cancel(); } catch {} return { ok: false, hard: true }; }
    if (ct.includes("html") || ct.includes("json")) { try { r.body.cancel(); } catch {} return { ok: false, hard: true }; }
    const reader = r.body.getReader(); const chunks = []; let n = 0;
    while (n < 16000) { const { value, done } = await reader.read(); if (done) break; chunks.push(value); n += value.length; }
    try { await reader.cancel(); } catch {}
    const data = new Uint8Array(n); let o = 0; for (const c of chunks) { data.set(c, o); o += c.length; }
    const br = parseInt((r.headers.get("icy-br") || "").split(",")[0], 10) || mp3Bitrate(data);
    const ok = ((ct.startsWith("audio/") || ct.includes("ogg") || ct.includes("aac")) && n >= 1500) || looksAudio(data);
    return { ok, hard: ok ? false : n > 0, kbps: br };
  } catch (e) {
    return { ok: false, hard: false };
  } finally { clearTimeout(timer); }
}

// ------------------------------------------------------------------ state (one KV key)
const mem = { cat: null };
const emptyCat = () => ({ v: 0, t: 0, cur: { d: 0, nd: 0 }, st: [] });
async function load(env) {
  if (!env.RADIO_KV) return mem.cat || (mem.cat = emptyCat());
  const c = await env.RADIO_KV.get("cat", "json");
  return c && c.st ? c : emptyCat();
}
async function save(env, cat) {
  cat.t = Date.now(); cat.v++;
  if (!env.RADIO_KV) { mem.cat = cat; return; }
  await env.RADIO_KV.put("cat", JSON.stringify(cat));
}
function newServer(url, kbps, src) {
  return { u: url, b: kbps || 0, h: /\.m3u8(\?|$)/i.test(url) ? 1 : 0, ok: 0, f: 0, ms: 0, t: 0, s: src || "rb" };
}
function rankServers(st) {
  st.s.sort((a, b) => ((a.f >= 2) - (b.f >= 2)) || (bucket(a.b) - bucket(b.b)) || ((a.ms || 9999) - (b.ms || 9999)));
}
function urlIndex(cat) {
  const m = new Map();
  cat.st.forEach((st) => st.s.forEach((s) => m.set(ukey(s.u), st)));
  return m;
}
function addEntry(cat, idx, { id, name, kname, cat: category, cc, urls, pop, seed }) {
  const key = nameKey(kname || name);
  let st = cat.st.find((x) => x.k === key && (!x.cc || !cc || x.cc === cc));
  if (!st) { st = { id, n: name, c: category, cc: cc || "", p: pop, k: key, sd: seed ? 1 : 0, s: [] }; cat.st.push(st); }
  else if (seed && !st.sd) { st.sd = 1; st.id = id; st.c = category; st.p = Math.max(st.p, pop); }
  if (!st.cc && cc) st.cc = cc;
  for (const [u, kbps] of urls) {
    if (!/^https?:\/\//i.test(u)) continue;
    const k = ukey(u);
    const owner = idx.get(k);
    if (owner && owner !== st) continue;
    if (!owner) { st.s.push(newServer(u, kbps, seed ? "seed" : "rb")); idx.set(k, st); }
  }
  if (st.s.length > 12) st.s = st.s.slice(0, 12);
}
const recUrls = (r) => [r.url_resolved, r.url].filter((u, i, a) => u && /^https?:\/\//i.test(u) && a.indexOf(u) === i).map((u) => [u, r.bitrate | 0]);

// ------------------------------------------------------------------ radio-browser access
async function rb(path, budget) {
  let last;
  for (let i = 0; i < RB_MIRRORS.length; i++) {
    if (budget.left <= 0) throw new Error("budget");
    budget.left--;
    try {
      const r = await fetch(RB_MIRRORS[i] + path, { headers: { "User-Agent": UA } });
      if (r.ok) return await r.json();
      last = new Error("http " + r.status);
    } catch (e) { last = e; }
  }
  throw last || new Error("radio-browser unreachable");
}
const q = (o) => new URLSearchParams({ ...o, hidebroken: "true", order: "votes", reverse: "true" }).toString();

function addPool(cat, idx, recs, ccDefault) {
  for (const r of recs) {
    const cc = (r.countrycode || ccDefault || "").toUpperCase();
    const category = classify(r.name, r.tags, cc);
    if (!category) continue;
    if (cc !== "EG" && (r.votes | 0) < (MIN_VOTES[category] ?? 100)) continue;
    const urls = recUrls(r); if (!urls.length) continue;
    addEntry(cat, idx, { id: "rb_" + String(r.stationuuid).slice(0, 8), name: String(r.name).trim(), cat: category, cc, urls,
      pop: Math.max(1, (POP_BASE[category] || 800) - 150 + Math.min(120, Math.floor((r.votes | 0) / 40))), seed: false });
  }
}
async function runTask(cat, idx, task, budget) {
  if (task.k === "cc") addPool(cat, idx, await rb(`/json/stations/bycountrycodeexact/${task.cc}?` + q({ limit: 600 }), budget), task.cc);
  else if (task.k === "lang") addPool(cat, idx, await rb("/json/stations/search?" + q({ language: "arabic", limit: 800 }), budget), "");
  else if (task.k === "tag") addPool(cat, idx, await rb("/json/stations/search?" + q({ tag: task.tag, language: "arabic", limit: 300 }), budget), "");
  else if (task.k === "seed") await seedTask(cat, idx, task.i, budget);
}
async function seedTask(cat, idx, i, budget) {
  const [id, category, cc, queries, cands] = SEEDS[i];
  const urls = cands.map((u) => [u, 0]); let matched = "";
  for (const useCc of cc ? [true, false] : [false]) {
    for (const qq of queries.slice(0, 3)) {
      if (budget.left < 8) break;
      let res = [];
      try { res = await rb("/json/stations/search?" + q({ name: qq, limit: 30, ...(useCc ? { countrycode: cc } : {}) }), budget); } catch { continue; }
      const ranked = res.map((r) => [nameScore(r.name, queries), r.votes | 0, r]).filter((x) => x[0] >= 0.82).sort((a, b) => b[0] - a[0] || b[1] - a[1]).slice(0, 10);
      for (const [, , r] of ranked) { if (!matched) matched = r.name; urls.push(...recUrls(r)); }
      if (urls.length >= 6) break;
    }
    if (urls.length >= 3) break;
  }
  if (!urls.length) return;
  const display = /[\u0600-\u06ff]/.test(queries[0]) || !matched ? queries[0] : matched;
  addEntry(cat, idx, { id, name: display, kname: matched || queries[0], cat: category, cc, urls, pop: POP_BASE[category] - Math.floor(i / 3), seed: true });
}

// ------------------------------------------------------------------ one brain cycle (cron or admin step)
export async function cycle(env, opts = {}) {
  const limit = parseInt(env.SUBREQ_LIMIT || "45", 10);
  const budget = { left: limit };
  const cat = await load(env);
  const idx = urlIndex(cat);
  const now = Date.now();
  let did = 0;
  if (cat.cur.d >= TASKS.length && now > cat.cur.nd) cat.cur.d = 0;                     // time to re-discover
  while (cat.cur.d < TASKS.length && budget.left > (opts.keepForProbe ?? 14)) {
    try { await runTask(cat, idx, TASKS[cat.cur.d], budget); } catch (e) { if (String(e.message) === "budget") break; }
    cat.cur.d++; did++;
    if (cat.cur.d >= TASKS.length) cat.cur.nd = now + REDISCOVER_MS;
  }
  // probe the least-recently-checked servers with the remaining budget
  const flat = [];
  cat.st.forEach((st) => st.s.forEach((s) => flat.push([st, s])));
  flat.sort((a, b) => a[1].t - b[1].t);
  const batch = flat.slice(0, Math.max(0, budget.left - 1));
  budget.left -= batch.length;
  const results = await Promise.all(batch.map(([, s]) => probe(s.u)));
  batch.forEach(([, s], i) => applyProbe(s, results[i]));
  prune(cat);
  await save(env, cat);
  return { tasksDone: did, cursor: cat.cur.d, tasks: TASKS.length, probed: batch.length, stations: cat.st.length };
}
function applyProbe(s, r) {
  s.t = Date.now();
  if (r.ok) { s.ok++; s.f = 0; if (r.kbps) s.b = r.kbps; if (r.hls) s.h = 1; }
  else if (r.hard) s.f++;               // inconclusive results (timeout / ICY parser) never count against a server
}
function prune(cat) {
  for (const st of cat.st) {
    st.s = st.s.filter((s) => !(s.f >= 6 && s.ok === 0) && s.f < 12);
    rankServers(st);
    st.s = st.s.slice(0, Math.max(MAX_SERVERS, 0) + 3);
  }
  cat.st = cat.st.filter((st) => st.s.length > 0);
}

// ------------------------------------------------------------------ public views
function publicStations(cat) {
  const out = [];
  for (const st of cat.st) {
    const good = st.s.filter((s) => s.f < 3).slice(0, MAX_SERVERS);
    if (!good.length) continue;
    out.push({ id: st.id, n: st.n, c: st.c, cc: st.cc, p: st.p, b: good[0].b || 0, s: good.map((s) => ({ u: s.u, b: s.b || 0, ...(s.h ? { h: 1 } : {}) })) });
  }
  out.sort((a, b) => b.p - a.p);
  return out;
}
const json = (o, status = 200, extra = {}) => new Response(JSON.stringify(o), { status, headers: { "Content-Type": "application/json; charset=utf-8", ...CORS, ...extra } });

// ------------------------------------------------------------------ HLS relay: audio-only, cheapest variant, no video ever
export function rewriteHls(text, targetUrl, base) {
  const proxied = (u) => `${base}/r?u=${encodeURIComponent(new URL(u, targetUrl).toString())}`;
  const lines = text.split(/\r?\n/);
  const isMaster = lines.some((l) => l.trim().startsWith("#EXT-X-STREAM-INF"));
  const nextUri = (from) => { for (let j = from + 1; j < lines.length; j++) { const t = lines[j].trim(); if (t && !t.startsWith("#")) return j; } return -1; };
  let keep = -1;
  if (isMaster) {
    let best = Infinity;
    lines.forEach((l, i) => {
      const t = l.trim();
      if (!t.startsWith("#EXT-X-STREAM-INF")) return;
      if (/RESOLUTION=|FRAME-RATE=/.test(t) || /CODECS="[^"]*(avc1|hvc1|hev1|vp0?9|av01)/.test(t)) return;
      const bw = parseInt((t.match(/BANDWIDTH=(\d+)/) || [])[1] || "0", 10) || 1;
      if (bw < best) { best = bw; keep = i; }
    });
    if (keep < 0) return null;
  }
  const out = [];
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i], t = line.trim();
    if (!t) { out.push(line); continue; }
    if (t.startsWith("#EXT-X-STREAM-INF")) {
      const j = nextUri(i);
      if (i === keep && j > 0) { out.push(t); out.push(proxied(lines[j].trim())); }
      if (j > 0) i = j;
      continue;
    }
    if (t.startsWith("#EXT-X-I-FRAME-STREAM-INF")) continue;
    if (t.startsWith("#EXT-X-MEDIA") && /TYPE=(VIDEO|SUBTITLES|CLOSED-CAPTIONS)/.test(t)) continue;
    if (t.startsWith("#")) { out.push(t.includes('URI="') ? t.replace(/URI="([^"]+)"/g, (_, p) => `URI="${proxied(p)}"`) : line); continue; }
    out.push(proxied(t));
  }
  return out.join("\n");
}
async function relay(target, request) {
  if (!/^https?:\/\//i.test(target)) return new Response("bad url", { status: 400, headers: CORS });
  const r = await fetch(target, { headers: { "User-Agent": UA, "Accept": "*/*", "Icy-MetaData": "0" }, redirect: "follow" });
  if (!r.ok) return new Response("upstream " + r.status, { status: 502, headers: CORS });
  const ct = (r.headers.get("content-type") || "").toLowerCase();
  if (ct.includes("mpegurl") || /\.m3u8(\?|$)/i.test(new URL(target).pathname + "")) {
    const body = rewriteHls(await r.text(), target, new URL(request.url).origin);
    if (body === null) return new Response("video-only stream", { status: 403, headers: CORS });
    return new Response(body, { headers: { "Content-Type": "application/vnd.apple.mpegurl", "Cache-Control": "no-store", ...CORS } });
  }
  if (ct.startsWith("video/") && !ct.includes("mp2t")) return new Response("video blocked", { status: 403, headers: CORS });
  return new Response(r.body, { headers: { "Content-Type": ct || "audio/mpeg", "Cache-Control": "no-store", ...CORS } });
}

// ------------------------------------------------------------------ on-demand healing and learning from phones
async function heal(env, id) {
  const cat = await load(env);
  const st = cat.st.find((x) => x.id === id);
  if (!st) return json({ error: "unknown station" }, 404);
  const budget = { left: 30 };
  const seed = SEEDS.find((x) => x[0] === id);
  const queries = seed ? seed[3] : [st.n];
  const idx = urlIndex(cat);
  const found = [];
  for (const qq of queries.slice(0, 3)) {
    if (budget.left < 12) break;
    let res = []; try { res = await rb("/json/stations/search?" + q({ name: qq, limit: 25 }), budget); } catch { continue; }
    for (const r of res) if (nameScore(r.name, queries) >= 0.82) found.push(...recUrls(r));
  }
  const fresh = found.filter(([u], i, a) => a.findIndex((x) => x[0] === u) === i && !idx.has(ukey(u))).slice(0, Math.min(10, budget.left));
  const res = await Promise.all(fresh.map(([u]) => probe(u)));
  fresh.forEach(([u, kbps], i) => {
    if (res[i].ok) { const s = newServer(u, res[i].kbps || kbps, "heal"); s.ok = 1; s.t = Date.now(); st.s.push(s); }
  });
  // known servers that kept failing get another chance too
  for (const s of st.s) if (s.f >= 2 && budget.left > 0) { budget.left--; applyProbe(s, await probe(s.u)); }
  prune(cat);
  await save(env, cat);
  const pub = publicStations({ st: [st] })[0];
  return json({ station: pub || null, added: res.filter((r) => r.ok).length });
}
async function report(env, body) {
  const cat = await load(env);
  const idx = urlIndex(cat);
  let n = 0;
  for (const e of (body && body.r) || []) {
    const st = idx.get(ukey(String(e.u || ""))); if (!st) continue;
    const s = st.s.find((x) => ukey(x.u) === ukey(e.u)); if (!s) continue;
    if (e.ok) { s.ok++; s.f = 0; if (e.ms) s.ms = s.ms ? Math.round(s.ms * 0.7 + e.ms * 0.3) : e.ms; if (e.b && e.b > 0 && e.b < 400) s.b = Math.round(e.b); }
    else s.f++;
    s.t = Date.now(); n++;
  }
  if (n) { prune(cat); await save(env, cat); }
  return n;
}

// ------------------------------------------------------------------ entry points
export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const p = url.pathname;
    if (request.method === "OPTIONS") return new Response(null, { headers: { ...CORS, "Access-Control-Allow-Methods": "GET, POST, OPTIONS", "Access-Control-Allow-Headers": "*" } });
    try {
      if (p === "/v2/stations") {
        const cat = await load(env);
        if (cat.st.length < parseInt(env.MIN_READY || "30", 10)) { ctx.waitUntil(cycle(env, { keepForProbe: 8 })); return json({ v: 0, n: 0, stations: [], retry: 25 }, 200, { "Cache-Control": "no-store" }); }
        const etag = `W/"${cat.v}"`;
        if (request.headers.get("If-None-Match") === etag) return new Response(null, { status: 304, headers: { ETag: etag, ...CORS } });
        const stations = publicStations(cat);
        return json({ v: cat.v, t: cat.t, n: stations.length, stations }, 200, { ETag: etag, "Cache-Control": "public, max-age=300" });
      }
      if (p === "/v2/heal") return await heal(env, url.searchParams.get("id") || "");
      if (p === "/v2/report" && request.method === "POST") return json({ applied: await report(env, await request.json().catch(() => ({}))) });
      if (p === "/r") return await relay(url.searchParams.get("u") || "", request);
      if (p === "/v2/health") {
        const cat = await load(env);
        const by = {}; cat.st.forEach((s) => { by[s.c] = (by[s.c] || 0) + 1; });
        return json({ status: "ok", version: "3.0.0", v: cat.v, updated: cat.t, stations: cat.st.length, byCategory: by, discovery: `${cat.cur.d}/${TASKS.length}`, kv: !!env.RADIO_KV });
      }
      if (p === "/v2/admin/step") {
        if (env.ADMIN_KEY && url.searchParams.get("key") !== env.ADMIN_KEY) return json({ error: "forbidden" }, 403);
        return json(await cycle(env, { keepForProbe: parseInt(url.searchParams.get("probe") || "14", 10) }));
      }
      return json({ name: "radio-joo-cloud", endpoints: ["/v2/stations", "/v2/heal?id=", "/v2/report", "/r?u=", "/v2/health"] });
    } catch (e) {
      return json({ error: String(e && e.message || e) }, 500);
    }
  },
  async scheduled(event, env, ctx) {
    ctx.waitUntil(cycle(env));
  },
};