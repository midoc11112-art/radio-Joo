/* RADIO-JOO flagship catalog worker (schema v2). ASCII-only source: Arabic is produced from Buckwalter via B()/AR(). */
const MIRRORS = ["https://de1.api.radio-browser.info", "https://fi1.api.radio-browser.info", "https://at1.api.radio-browser.info", "https://de2.api.radio-browser.info"];
const ARAB = new Set("EG SA AE KW QA BH OM YE IQ SY LB JO PS LY TN DZ MA MR SD SO DJ KM".split(" "));
const TTL = 6 * 3600 * 1000, THROTTLE = 45000, SUBCAP = 48, MAXSRV = 12, BADMS = 3 * 86400000, HSMS = 7 * 86400000;
let MEM = null, BUILDING = null, LASTREF = 0;

const BW = { A: "\u0627", b: "\u0628", t: "\u062A", v: "\u062B", j: "\u062C", H: "\u062D", x: "\u062E", d: "\u062F", "*": "\u0630", r: "\u0631", z: "\u0632", s: "\u0633", "$": "\u0634", S: "\u0635", D: "\u0636", T: "\u0637", Z: "\u0638", E: "\u0639", g: "\u063A", f: "\u0641", q: "\u0642", k: "\u0643", l: "\u0644", m: "\u0645", n: "\u0646", h: "\u0647", w: "\u0648", y: "\u064A", "'": "\u0621" };
const B = s => s.replace(/[A-Za-z*$']/g, c => BW[c] || c);
/* display Arabic: O=ta marbuta, U=alef hamza above, I=alef hamza below, Q=alef madda */
const AR = s => B(s).replace(/O/g, "\u0629").replace(/U/g, "\u0623").replace(/I/g, "\u0625").replace(/Q/g, "\u0622");
const rx = (lat, ar) => new RegExp(lat.concat((ar || []).map(B)).join("|"));
const AR_SCRIPT = /[\u0600-\u06FF]/;
const ENG = /\b(?:english|espanol|francais|french|exterior|deutsch|spanish)\b/;

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
  tarab: rx(["tarab|tarb|oriental|golden (era|age)|vintage|andalus|muwashah|arabic classic|classic arabic"],
    ["Trb", "Alzmn Aljmyl", "zmAn", "qdym", "mw$HAt", "ASAlh"]),
  music: rx(["music|musique|pop|hits|song|dance|hip hop|rap|rock|jazz|mahragan|mahraganat|chaabi|shaabi|top ?40|lounge|rnb"], ["mwsyqy", "mhrjAnAt", "$Eby", "AgAny"]),
  drop: rx(["christian|gospel|bible|church|catholic|orthodox|corleone|x_x|s_o_l_l_y|solly|aravic|nfeshal|test stream"], ["msyHy", "knys"]),
};
const TAG_NEWS = /^(news|news talk|akhbar|world news)$/, TAG_TALK = /^(talk|talk radio|news talk|discussion)$/;
const EGYNAME = rx(["egypt|misr|masr|cairo|alexandria|nile|ertu"], ["mSr", "AlqAhrh", "AlAskndryh"]);

/* ===== PURE-AUDIO GUARDS (no TV, no video) ===== */
const VID_EXT = /\.(?:mp4|m4v|mkv|webm|flv|avi|mov|wmv|mpd|3gp|ts)(?:[?#]|$)/i;
const VID_CODEC = /264|265|hevc|avc|vp[89]|mpeg-?ts|mp2t|video/i;
const AUD_CODEC = /mp3|mpeg|aac|mp4a|ogg|opus|vorbis|flac/i;
const TVNAME = new RegExp("\\btv\\b|television|\\bhdtv\\b|\\bvideo\\b|" + B("tlfzywn"));
const TVTAG = /^(tv|television|video|tv channel|webtv)$/;
function audioOK(r) {
  if (VID_EXT.test(r.u)) return false;
  if (r.seed) return true;
  if (VID_CODEC.test(r.codec)) return false;
  if (TVNAME.test(r.nn) || r.tags.some(t => TVTAG.test(t))) return false;
  if (/\.m3u8(?:[?#]|$)/i.test(r.u) && !AUD_CODEC.test(r.codec)) return false;
  return true;
}

/* ===== HUMAN-CURATED ALLOWLIST (array order = real-world fame order, earlier = more famous) =====
   c = category | lim: "eg" (Egyptian networks only) / "any" (icons, reciters) / default (needs Arabic evidence)
   ccf: forced country ("EG", "SA", "" ...) | "keep" = real cc | "egcue" = EG only if name is Egyptian | undefined = first non-EG cc
   fam: family of stations kept separate per name (cap/min limit how many) | x: exclusion regex */
const FAME = [];
function F(en, ar, lat, arp, o) {
  o = o || {};
  FAME.push({
    en: en, name: ar ? en + " - " + AR(ar) : en,
    re: rx(lat, arp),
    x: o.x ? rx(o.x[0], o.x[1]) : null,
    c: o.c, lim: o.lim, ccf: o.ccf, fam: !!o.fam, cap: o.cap || 0, min: o.min || 0, q: o.q || null
  });
}
const QN = "(?:qur ?an|koran|kuran)";
const EGO = { c: "EGYPT", lim: "eg", ccf: "EG" };
const EGM = { c: "MUSIC", lim: "eg", ccf: "EG" };
const QE = { c: "QURAN", lim: "any", ccf: "EG" };
const QG = { c: "QURAN", lim: "any" };
const TR = { c: "MUSIC", lim: "any", ccf: "" };
const NQX = [["qur ?an|koran|makk?ah|madin|nabawi|haram|tilawa"], ["qrAn|mkh|Almdynh|Alnbwy|AlHrm|tlAwh"]];

/* ---- #1 QURAN CAIRO ---- */
F("Quran Cairo", "I*AEO AlqrQn Alkrym mn AlqAhrO",
  [`${QN}.{0,30}(?:cairo|egypt|misr|masr)|(?:cairo|egypt|misr|masr).{0,30}${QN}`],
  ["qrAn.{0,30}(?:AlqAhrh|mSr)|(?:AlqAhrh|mSr).{0,30}qrAn"], Object.assign({ q: "quran cairo" }, QE));

/* ---- EGYPT BLOCK (cc=EG always; c = real functional category) ---- */
F("Nogoum FM", "njwm Af Am", ["nogoum|nogom|nujum|nogum|nogoom|nojoom|negoum"], ["njwm Af Am|Af Am njwm"], Object.assign({ q: "nogoum" }, EGM));
F("Nile FM", "nAyl Af Am", ["nile ?fm|nilefm|nile radio"], ["nAyl Af Am|nyl Af Am"], Object.assign({ q: "nile fm" }, EGM));
F("Mega FM", "myjA Af Am", ["mega ?fm|mega 92|mega ?masr"], ["myjA Af Am|myjA"], Object.assign({ q: "mega" }, EGM));
F("Nagham FM", "ngm Af Am", ["nagham|naghem"], ["ngm Af Am|Af Am ngm"], Object.assign({ q: "nagham" }, EGM));
F("Al Barnamg Al Aam", "AlbrnAmj AlEAm", ["general (?:program|programme)|barnameg ?(?:el|al)? ?aam|el barnameg el aam|albarnamg"], ["AlbrnAmj AlEAm"], Object.assign({ q: "barnamg" }, EGO));
F("Sawt El Arab", "Swt AlErb", ["voice of (?:the )?arabs|sawt ?(?:el|al)? ?arab|sout el arab|saout el arab"], ["Swt AlErb"], Object.assign({ q: "sawt el arab" }, EGO));
F("9090 FM", null, ["9090|90 ?90 ?fm"], ["9090"], Object.assign({ q: "9090" }, EGM));
F("Radio Hits", "rAdyw hyts", ["radio ?hits|hits ?fm|hits ?radio"], ["rAdyw hyts|hyts Af Am"], Object.assign({ q: "hits" }, EGM));
F("Shaabi FM", "$Eby Af Am", ["sha+bi ?fm|sha+bi radio|sha3by|el ?shaabi"], ["$Eby Af Am|rAdyw $Eby"], Object.assign({ q: "shaabi" }, EGM));
F("Mix FM", "myks Af Am", ["mix ?fm|mix 106|mix ?egypt|mix ?masr"], ["myks Af Am"], Object.assign({ q: "mix fm" }, EGM));
F("NRJ Egypt", "Inrjy mSr", ["nrj ?(?:egypt|masr|misr)|energy ?(?:fm )?(?:egypt|masr|98)|energy 98"], ["Anrjy"], Object.assign({ q: "nrj" }, EGM));
F("ON Sport FM", "Awn sbwrt Af Am", ["\\bon ?sports?\\b|\\bonsport|on time sports"], ["Awn sbwrt|An sbwrt|Awn tAym sbwrt"], { c: "SPORTS", lim: "eg", ccf: "EG", q: "on sport" });
F("Youth & Sports", "Al$bAb wAlryADO", ["youth ?(?:and )?sports|shabab ?(?:wa|wel|we|w)? ?(?:el)? ?riyad[a-z]{0,2}|el ?shabab ?w ?el ?riyad[a-z]{0,2}"], ["Al$bAb wAlryADh|$bAb wAlryADh|Al$bAb wryADh"], { c: "SPORTS", lim: "eg", ccf: "EG", q: "shabab" });
F("Masr El Gdida", "rAdyw mSr AljdydO", ["masr el ?(?:gdid|jdid|gedid)\\w*|misr el ?gdid\\w*|new egypt"], ["mSr Aljdydh|mSr Aljdyd"], { c: "TALK", lim: "eg", ccf: "EG", q: "masr el gdida" });
F("Rehab FM", "rhAb Af Am", ["rehab ?(?:fm|radio|city)"], ["rhAb Af Am"], Object.assign({ q: "rehab" }, EGM));
F("Hawak FM", "hwAk Af Am", ["hawak|hawwak"], ["hwAk"], Object.assign({ q: "hawak" }, EGM));
F("Masriyat", "mSryAt", ["masriyat|masryat|masriat|masreyat"], ["mSryAt"], Object.assign({ q: "masriyat" }, EGM));
F("Tes3enat FM", "tsEynAt Af Am", ["tes3enat|tis3enat|tes3inat|tesaenat|\\b90s ?fm\\b|nineties ?fm"], ["tsEynAt"], Object.assign({ q: "tes3enat" }, EGM));
F("Aghani Aghani", "UgAny UgAny", ["aghani ?aghani|agani ?agani|aghany ?aghany"], ["AgAny AgAny"], Object.assign({ q: "aghani" }, EGM));
F("Al Sharq Al Awsat", "I*AEO Al$rq AlUwsT", ["middle east (?:radio|fm)|sharq ?(?:el|al)? ?awsat\\b|el sharq el awsat"], ["Al$rq AlAwsT"], Object.assign({ q: "sharq el awsat" }, EGO));
F("Cultural Program", "AlbrnAmj AlvqAfy", ["cultural (?:program|programme)|el ?thaqafi"], ["AlbrnAmj AlvqAfy"], EGO);
F("Egyptian Radio ERTU", "AlI*AEO AlmSryO", ["egyptian radio|\\bertu\\b|radio of egypt"], ["AlA*AEh AlmSryh"], Object.assign({ q: "ertu" }, EGO));

/* ---- NEWS (fame order; Egyptian news carry cc=EG) ---- */
F("Al Jazeera", "AljzyrO", ["al ?jazeera|aljazeera|\\bjazeera\\b"], ["Aljzyrh"],
  { c: "NEWS", q: "jazeera", x: [["mubasher|documentar|balkan|children|kids|\\bsports?\\b|\\bbein\\b"], ["mbA$r|wvAEqyh|ryADh"]] });
F("Al Arabiya", "AlErbyO", ["al ?arabiya|alarabiya|al ?arabiyah|^" + B("AlErbyh") + "( |$)"], ["qnAh AlErbyh"],
  { c: "NEWS", q: "arabiya", x: [["hadath"], ["AlHdv"]] });
F("BBC Arabic", "by by sy ErbyO", ["bbc.{0,12}arab"], ["by by sy"], { c: "NEWS", q: "bbc arabic" });
F("Sky News Arabia", "skAy nywz ErbyO", ["sky ?news.{0,10}arab|sky ?arabia|skynews ?arabia"], ["skAy nywz|skAy Erbyh"], { c: "NEWS", q: "sky news arabia" });
F("Radio Masr", "rAdyw mSr", ["radio ?masr\\b|radio ?misr\\b|masr ?fm"], ["rAdyw mSr(?!y)"], { c: "NEWS", lim: "eg", ccf: "EG", q: "radio masr" });
F("Al Hadath", "AlHdv", ["al ?hadath|alhadath"], ["AlHdv"], { c: "NEWS", q: "hadath" });
F("Al Qahera News", "qnAO AlqAhrO AlIxbAryO", ["al ?qahera ?news|alqahera ?news|al ?qaherah ?news|qahera news|cairo news"], ["qnAh AlqAhrh|AlqAhrh AlAxbAryh"], { c: "NEWS", lim: "eg", ccf: "EG", q: "qahera" });
F("Monte Carlo Doualiya", "mwnty kArlw AldwlyO", ["monte ?carlo|doualiya|\\bmcd\\b"], ["mwnt kArlw|mwnty kArlw|mwntykArlw"], { c: "NEWS", q: "monte carlo" });
F("France 24 Arabic", "frAns 24 bAlErbyO", ["france ?24"], ["frAns ?24"], { c: "NEWS", q: "france 24" });
F("Asharq News", "Al$rq llUxbAr", ["asharq ?(?:news|bloomberg|business)|al ?sharq ?(?:news|bloomberg)"], ["Al$rq llAxbAr|Al$rq blwmbrj|Al$rq bylwmbyrj|Al$rq nywz"], { c: "NEWS", q: "asharq" });
F("Extra News", "IkstrA nywz", ["extra ?news|extranews"], ["AkstrA nywz"], { c: "NEWS", lim: "eg", ccf: "EG", q: "extra news" });
F("DW Arabic", "dwyt$h fylh", ["\\bdw\\b|deutsche welle"], ["dwyt$h fylh|dw Erbyh"], { c: "NEWS", q: "dw arabic" });
F("Al Mayadeen", "AlmyAdyn", ["al ?mayadeen|almayadeen"], ["AlmyAdyn"], { c: "NEWS", q: "mayadeen" });
F("RT Arabic", "rwsyA Alywm", ["\\brt\\b.{0,10}arab|russia today.{0,10}arab|rt arabic|rt arabia"], ["rwsyA Alywm"], { c: "NEWS", q: "rt arabic" });
F("Alhurra", "AlHrO", ["alhurra|al ?hurra"], ["AlHrh"], { c: "NEWS", q: "alhurra" });
F("Radio Sawa", "rAdyw swA", ["radio sawa|\\bsawa\\b"], ["rAdyw swA"], { c: "NEWS", q: "sawa" });
F("Sputnik Arabic", "sbwtnyk", ["sputnik"], ["sbwtnyk"], { c: "NEWS", q: "sputnik" });
F("TRT Arabic", "ty Ar ty Erby", ["trt ?arab"], ["ty Ar ty Erby"], { c: "NEWS", q: "trt arabic" });
F("Al Araby", "AlErby Aljdyd", ["al ?araby\\b|alaraby|araby ?tv"], ["AlErby Aljdyd|tlfzywn AlErby|rAdyw AlErby"], { c: "NEWS", q: "araby" });
F("Saudi Radio", "I*AEO AlsEwdyO", ["saudi (?:radio|broadcasting)|radio saudi|saudi arabia radio"], ["A*AEh AlsEwdyh|A*AEh Almmlkh"], { c: "NEWS", ccf: "SA", q: "saudi radio", x: NQX });
F("Radio Kuwait", "I*AEO AlkwyT", ["radio kuwait|kuwait radio|kuwait (?:fm|news)"], ["A*AEh AlkwyT"], { c: "NEWS", ccf: "KW", q: "kuwait radio", x: NQX });
F("Qatar Radio", "I*AEO qTr", ["qatar radio|radio qatar|qatar (?:fm|broadcasting)"], ["A*AEh qTr"], { c: "NEWS", ccf: "QA", q: "qatar radio", x: NQX });
F("Radio Jordan", "I*AEO AlUrdn", ["radio jordan|jordan radio|hashemite kingdom radio"], ["A*AEh AlArdn"], { c: "NEWS", ccf: "JO", q: "radio jordan", x: NQX });
F("Voice of Lebanon", "Swt lbnAn", ["voice of lebanon|sawt ?(?:el|al)? ?lubnan|sawt lebnan"], ["Swt lbnAn"], { c: "NEWS", ccf: "LB", q: "voice of lebanon", x: NQX });
F("Medi 1 Radio", "rAdyw mydy 1", ["medi ?1\\b|medi1|radio medi"], ["mydy 1|mydy1"], { c: "TALK", ccf: "MA", q: "medi 1", x: NQX });
F("Radio Oman", "I*AEO slTnO EmAn", ["radio oman|oman radio|sultanate of oman radio|oman (?:fm|news)"], ["A*AEh slTnh EmAn|A*AEh EmAn"], { c: "NEWS", ccf: "OM", q: "radio oman", x: NQX });
F("Radio Bahrain", "I*AEO AlbHryn", ["radio bahrain|bahrain radio|bahrain (?:fm|news)"], ["A*AEh AlbHryn"], { c: "NEWS", ccf: "BH", q: "radio bahrain", x: NQX });
F("Iraqi Media Network", "$bkO AlEAlAm AlErAqy", ["iraqi media network|radio iraq|iraq radio|republic of iraq radio"], ["$bkh AlEAlAm AlErAqy|A*AEh AlErAq"], { c: "NEWS", ccf: "IQ", q: "iraqi media", x: NQX });
F("Voice of Palestine", "Swt flsTyn", ["voice of palestine|sawt ?(?:el|al)? ?filast[iy]n|palestine radio|radio palestine"], ["Swt flsTyn|A*AEh flsTyn"], { c: "NEWS", ccf: "PS", q: "voice of palestine", x: NQX });
F("Omdurman Radio", "I*AEO Um drmAn", ["omdurman|um ?durman|radio sudan|sudan radio"], ["Am drmAn|A*AEh AlswdAn"], { c: "NEWS", ccf: "SD", q: "omdurman", x: NQX });
F("Radio Algerie", "I*AEO AljzAyr", ["radio ?alg.?rie|algerian radio|radio algeria|algeria radio"], ["A*AEh AljzAyr|A*AEh AljzA'r"], { c: "NEWS", ccf: "DZ", q: "radio algerie", x: NQX });
F("Radio Tunisienne", "AlI*AEO AltwnsyO", ["radio tunisienne|tunisian national radio|radio tunis(?:ie)?\\b|tunisia radio"], ["A*AEh Altwnsyh"], { c: "NEWS", ccf: "TN", q: "radio tunis", x: NQX });

/* ---- QURAN (Saudi, Al Majd, Omni family, then reciters: Egyptian = cc EG, Gulf never EG) ---- */
F("Saudi Quran Makkah", "I*AEO AlqrQn Alkrym mn mkO AlmkrmO",
  [`makk?ah.{0,25}${QN}|${QN}.{0,25}makk?ah|mecca.{0,25}${QN}|${QN}.{0,25}mecca|makk?ah ?(?:live|radio)|haramain|masjid ?al ?haram|saudi.{0,25}${QN}|ksa.{0,10}${QN}|${QN}.{0,25}saudi`],
  ["qrAn.{0,25}mkh|mkh.{0,25}qrAn|AlHrm Almky|AlHrmyn|Almsjd AlHrAm|AlsEwdyh.{0,25}qrAn|qrAn.{0,25}AlsEwdyh"], { c: "QURAN", ccf: "SA", q: "makkah" });
F("Saudi Quran Madinah", "I*AEO AlqrQn Alkrym mn AlmdynO Almnwrh",
  [`madin[ae]h?.{0,25}${QN}|${QN}.{0,25}madin[ae]h?|medin[ae]h?.{0,25}${QN}|${QN}.{0,25}medin[ae]h?|nabawi|prophet.{0,10}mosque`],
  ["qrAn.{0,25}Almdynh|Almdynh.{0,25}qrAn|Almsjd Alnbwy|AlHrm Alnbwy"], { c: "QURAN", ccf: "SA", q: "madinah" });
F("Al Majd Quran", "Almjd llqrQn Alkrym",
  [`al ?majd.{0,25}${QN}|${QN}.{0,25}al ?majd|almajd ?quran`],
  ["Almjd.{0,25}qrAn|qrAn.{0,25}Almjd|Almjd llqrAn"], { c: "QURAN", ccf: "SA", q: "majd quran" });
F("Omni Quran", "Amny qrQn", ["omni ?quran|qurango|mp3 ?quran|mp3quran|omni ?radio|omniradio"], ["Amny qrAn"], { c: "QURAN", ccf: "", fam: 1, cap: 100, min: 0, q: "omni quran" });
F("Mishary Alafasy", "m$Ary AlEfAsy", ["afasy|alafasy|alafasi|afasi|mish[ae]ry ?(?:rashid|al)?|mishari"], ["AlEfAsy|m$Ary AlEfAsy|m$Ary rA$d"], Object.assign({ q: "alafasy" }, QG));
F("Abdulrahman Al Sudais", "Ebd AlrHmn Alsdys", ["sudais|sudays|sudaiss|soudais|sodais"], ["Alsdys"], Object.assign({ q: "sudais" }, QG));
F("Abdul Basit Abdul Samad", "Ebd AlbAsT Ebd AlSmd", ["abd(?:ul|el|al)? ?(?:al ?)?bas[iy]t|abdel ?bas+et|abdulbasit|abdelbasit|abdul ?baset"], ["Ebd AlbAsT|EbdAlbAsT"], Object.assign({ q: "abdul basit" }, QE));
F("Al Husary", "AlHSry", ["h[ou]s+ar[iy]"], ["AlHSry"], Object.assign({ q: "husary" }, QE));
F("Minshawi", "Almn$Awy", ["min?shawi|menshawy|minshawy|menshawi"], ["Almn$Awy"], Object.assign({ q: "minshawi" }, QE));
F("Maher Al Muaiqly", "mAhr AlmEyqly", ["muaiqly|mueaqly|moaiqly|moayqly|maher.{0,12}(?:mu|mo)|maher al ?mu"], ["AlmEyqly|mAhr AlmEyqly"], Object.assign({ q: "muaiqly" }, QG));
F("Saud Al Shuraim", "swd Al$rym", ["shuraim|shuraym|shureym|alshuraim"], ["Al$rym"], Object.assign({ q: "shuraim" }, QG));
F("Saad Al Ghamdi", "sEd AlgAmdy", ["ghamdi|ghamidi|ghamdy|alghamdi"], ["AlgAmdy"], Object.assign({ q: "ghamdi" }, QG));
F("Mustafa Ismail", "mSTfy IsmAEyl", ["m[ou]+stafa ?isma[ei]+l|mostafa ismael|mustapha ismail"], ["mSTfy AsmAEyl"], Object.assign({ q: "mustafa ismail" }, QE));
F("Al Tablawi", "AlTblAwy", ["tablawi|toblawi|tabliwi|tablawy"], ["AlTblAwy"], Object.assign({ q: "tablawi" }, QE));
F("Mahmoud Ali Al Banna", "mHmwd Ely AlbnA", ["mahmoud ?(?:ali|aly)? ?(?:al ?|el ?)?bann?a|(?:sheikh|shaikh) ?(?:al|el) ?banna|\\bal ?banna\\b|\\bel ?banna\\b"], ["mHmwd Ely AlbnA|AlSyx AlbnA"], Object.assign({ q: "banna" }, QE));
F("Yasser Al Dosari", "yAsr Aldwsry", ["dosari|dossary|dosary|daosari"], ["Aldwsry"], Object.assign({ q: "dosari" }, QG));
F("Nasser Al Qatami", "nASr AlqTAmy", ["qatami|qatamy"], ["AlqTAmy"], Object.assign({ q: "qatami" }, QG));
F("Ahmed Al Ajmi", "UHmd AlEjmy", ["\\bajmi\\b|\\bal ?ajmy\\b|\\bajmy\\b"], ["AlEjmy"], Object.assign({ q: "ajmi" }, QG));
F("Abu Bakr Al Shatri", "Ubw bkr Al$ATry", ["shatri|shaatree|shatry|shaatri"], ["Al$ATry"], Object.assign({ q: "shatri" }, QG));
F("Al Hudhaify", "Ely AlH*yfy", ["hudhaify|huthaify|hudhayfi|hudaify|hudhaifi"], ["AlH*yfy"], Object.assign({ q: "hudhaify" }, QG));
F("Hani Al Rifai", "hAny AlrfAEy", ["hani ?(?:al ?|er ?)?rifa"], ["hAny AlrfAEy"], Object.assign({ q: "rifai" }, QG));
F("Abdullah Basfar", "Ebd Allh bSfr", ["basfar"], ["bSfr"], Object.assign({ q: "basfar" }, QG));
F("Muhammad Jibreel", "mHmd jbryl", ["(?:muhammad|mohamed|mohammed) ?(?:ayyub ?)?j[iy]br[ei]+l|\\bjibreel\\b|\\bjibril\\b"], ["mHmd jbryl"], Object.assign({ q: "jibreel" }, QG));
F("Quran Radio", "qrQn", [QN], ["qrAn"], { c: "QURAN", ccf: "egcue", fam: 1, cap: 25, min: 100, x: [["english|translation|urdu|tafseer|tafsir|indonesia|malay|hindi|bengali|turkish|persian|farsi"], []] });

/* ---- TARAB ICONS ---- */
F("Umm Kulthum", "Um klvwm", ["k(?:a|o|u|ou)l[ts]h?[ou]+m"], ["Am klvwm|Am kltwm|klvwm|kltwm"], Object.assign({ q: "kalth" }, TR));
F("Abdel Halim Hafez", "Ebd AlHlym HAfZ", ["abd(?:el|ul|al)? ?hal[ei]+m|abdelhalim"], ["Ebd AlHlym"], Object.assign({ q: "abdel halim" }, TR));
F("Fairouz", "fyrwz", ["fairuz|fayrouz|fairouz|feyrouz|fayruz|fairoz|fayrooz|fairooz"], ["fyrwz"], Object.assign({ q: "fairuz" }, TR));
F("Mohamed Abdel Wahab", "mHmd Ebd AlwhAb", ["abd(?:el|ul|al)? ?wahab|abdelwahab"], ["Ebd AlwhAb"], Object.assign({ q: "wahab" }, TR));
F("Farid Al Atrash", "fryd AlUTr$", ["far[ie]e?d.{0,12}atrash|farid el ?atrash"], ["fryd AlATr$"], Object.assign({ q: "atrash" }, TR));
F("Asmahan", "UsmhAn", ["asmahan|asmahane"], ["AsmhAn"], TR);
F("Sabah Fakhri", "SbAH fxry", ["sabah ?fakhri|sabah el ?fakhri|sabah fakhry"], ["SbAH fxry"], TR);
F("Sayed Darwish", "syd drwy$", ["sayed darwish|sayyid darwish|sayed derwish"], ["syd drwy$"], TR);
F("Warda Al Jazairia", "wrdO AljzA'ryO", ["warda al ?jaz|warda el ?gaz|warda el ?jaz"], ["wrdh Aljz"], TR);
F("Najat Al Saghira", "njAh AlSgyrO", ["najat (?:el|al)? ?saghira|nagat (?:el|al)? ?saghira"], ["njAh AlSgyrh"], TR);
F("Zaman El Gameel", "Alzmn Aljmyl", ["zaman (?:el|al) ?(?:gameel|jameel|gamil|jamil)|zaman ?gameel|golden (?:era|age)|arabic classic|classic arabic|oriental classic"], ["Alzmn Aljmyl|zmAn Aljmyl"], { c: "MUSIC", ccf: "" });
F("Tarab Radio", "Trb", ["\\btarab\\b|\\btaraab\\b|\\btarb\\b"], ["Trb"], { c: "MUSIC", ccf: "", fam: 1, cap: 12, min: 60 });

/* ---- ARAB POP ---- */
F("Rotana FM", "rwtAnA Af Am", ["rotana"], ["rwtAnA"], { c: "MUSIC", q: "rotana", x: [["cinema|\\btv\\b|movies|drama|clip|khalijia"], []] });
F("MBC FM", "Am by sy Af Am", ["mbc ?fm|mbc ?radio"], ["Am by sy Af Am"], { c: "MUSIC", q: "mbc fm" });
F("Mosaique FM", "mwzAyk Af Am", ["mosaique|mosaic ?fm"], ["mwzAyk"], { c: "MUSIC", ccf: "TN", q: "mosaique" });

/* ---- SPORTS ---- */
F("Kora FM", "kwrO Af Am", ["kora ?fm|kooora ?fm|radio kora|kooora radio|\\bkooora\\b"], ["kwrh Af Am|rAdyw kwrh"], { c: "SPORTS", q: "kora" });
F("Arab Sports Radio", "ryADO", ["\\bsports?\\b|riyada|riyadah|kooora|\\bkora\\b"], ["ryADh|kwrh|mlAEb"], { c: "SPORTS", ccf: "keep", fam: 1, cap: 10, min: 40 });

const FAMIDX = new Map(FAME.map((e, i) => [e.en, i]));

/* ===== matching ===== */
const CC = new Map();
function cands(nn) {
  let v = CC.get(nn); if (v) return v;
  v = [];
  for (let i = 0; i < FAME.length; i++) {
    const e = FAME[i];
    const m = e.re.exec(nn);
    if (!m) continue;
    if (e.x && e.x.test(nn)) continue;
    v.push([i, m[0].length + (e.fam ? 0 : 1000)]);
  }
  CC.set(nn, v);
  return v;
}
function limOK(e, r) {
  if (e.lim === "any") return true;
  if (e.lim === "eg") return r.cc === "EG" || (!r.cc && (r.lang.includes("arab") || AR_SCRIPT.test(r.n) || EGYNAME.test(r.nn)));
  return r.lang.includes("arab") || AR_SCRIPT.test(r.n) || ARAB.has(r.cc) || /arab/.test(r.nn);
}
function pick(r) {
  const cs = cands(r.nn);
  if (!cs.length) return -1;
  if (ENG.test(r.nn) && !AR_SCRIPT.test(r.n)) return -1;
  let best = -1, bs = -1;
  for (const [i, s] of cs) {
    if (!limOK(FAME[i], r)) continue;
    if (s > bs) { bs = s; best = i; }
  }
  return best;
}
function prep(r) {
  r.e = -1;
  if (r.fe !== undefined) { r.e = r.fe; return true; }
  if (r.nn.length < 2) return false;
  if (RE.drop.test(r.nn + " " + r.tags.join(" "))) return false;
  if (r.rel) r.e = pick(r);
  if (r.e >= 0) return true;
  return r.cc === "EG";
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
function canonName(r) {
  let c = canon(r.n);
  if (c.length < 2) c = r.nn.replace(/ /g, "");
  return c.length < 2 ? "" : c;
}
function gkey(r) {
  if (r.e >= 0) {
    const e = FAME[r.e];
    if (!e.fam) return "F|" + e.en;
    const c = canonName(r);
    return c ? "M|" + e.en + "|" + c : "";
  }
  const c = canonName(r);
  return c ? "X|" + c : "";
}

/* name queries, most valuable first (subrequest budget truncates the tail) */
const NAMEQ = ["jazeera", "arabiya", "bbc arabic", "monte carlo", "france 24", "sky news arabia", "asharq", "dw arabic", "mayadeen", "rt arabic", "alhurra", "sawa", "sputnik", "trt arabic", "alaraby",
  "quran cairo", "makkah", "madinah", "omni quran", "qurango", "mp3quran", "kalth", "fairuz", "rotana", "mbc fm", "tarab", "kora", "nogoum", "nile fm", "mega fm"];

/* Backup stream URLs. [fameName|"", displayName|null, cc, url]. Unverified seeds rank AFTER Radio-Browser-verified servers and are probed when budget allows. */
const OM = "Omni Quran - ";
const SEEDS = [
  ["Quran Cairo", null, "EG", "https://stream.radiojar.com/8s5u5tpdtwzuv"],
  ["Quran Cairo", null, "EG", "https://n0a.radiojar.com/8s5u5tpdtwzuv"],
  ["Nile FM", null, "EG", "https://audio.nrpstream.com/listen/nile_fm/radio.mp3"],
  ["Nogoum FM", null, "EG", "https://audio.nrpstream.com/listen/nogoum_fm/radio.mp3"],
  ["Mega FM", null, "EG", "https://audio.nrpstream.com/listen/mega_fm/radio.mp3"],
  ["Al Jazeera", null, "QA", "https://live-hls-audio-web-aja.getaj.net/AJA/index.m3u8"],
  ["BBC Arabic", null, "GB", "https://stream.live.vc.bbcmedia.co.uk/bbc_arabic_radio"],
  ["Monte Carlo Doualiya", null, "FR", "https://montecarlodoualiya128k.ice.infomaniak.ch/mc-doualiya-128.mp3"],
  ["Sky News Arabia", null, "AE", "https://radio.skynewsarabia.com/stream/radio/sna"],
  ["Asharq News", null, "SA", "https://svs.itworkscdn.net/asharqradio/asharqradio.stream/playlist.m3u8"],
  ["", OM + AR("mnwEAt"), "", "https://backup.qurango.net/radio/mix"],
  ["", OM + AR("trAtyl"), "", "https://backup.qurango.net/radio/tarateel"],
  ["Mishary Alafasy", null, "", "https://backup.qurango.net/radio/mishary_alafasi"],
  ["Maher Al Muaiqly", null, "", "https://backup.qurango.net/radio/maher_al_meaqli"],
  ["Yasser Al Dosari", null, "", "https://backup.qurango.net/radio/yasser_aldosari"],
  ["Ahmed Al Ajmi", null, "", "https://backup.qurango.net/radio/ahmad_alajmy"],
  ["Saad Al Ghamdi", null, "", "https://backup.qurango.net/radio/saad_alghamdi"],
  ["Nasser Al Qatami", null, "", "https://backup.qurango.net/radio/nasser_alqatami"],
  ["Abdulrahman Al Sudais", null, "", "https://backup.qurango.net/radio/abdulrahman_alsudaes"],
  ["Saud Al Shuraim", null, "", "https://backup.qurango.net/radio/saud_alshuraim"],
  ["Minshawi", null, "EG", "https://backup.qurango.net/radio/mohammed_siddiq_alminshawi"],
  ["Al Husary", null, "EG", "https://backup.qurango.net/radio/mahmood_khalil_alhussary"],
  ["Abdul Basit Abdul Samad", null, "EG", "https://backup.qurango.net/radio/abdulbasit_abdulsamad"],
  ["Mustafa Ismail", null, "EG", "https://backup.qurango.net/radio/mustafa_ismail"],
  ["Mahmoud Ali Al Banna", null, "EG", "https://backup.qurango.net/radio/mahmoud_ali_albanna"],
  ["Abu Bakr Al Shatri", null, "", "https://backup.qurango.net/radio/abu_bakr_al_shatri"]
];

async function rb(path, bud) {
  const st = Math.floor(Math.random() * MIRRORS.length);
  for (let i = 0; i < 2; i++) {
    if (bud.n >= bud.cap) return [];
    bud.n++;
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
    const cc = String(r.countrycode || "").toUpperCase();
    const lang = String(r.language || "").toLowerCase();
    const rel = !cc || ARAB.has(cc) || lang.includes("arab") || AR_SCRIPT.test(n) || /arab|quran|koran|qurango|mp3|omni|kalth|kulth|fairuz|fayrouz/.test(nn);
    const x = {
      id: r.stationuuid || (n + u), n, nn, u, cc, lang, rel, seed: !!r._seed,
      codec: String(r.codec || ""),
      tags: String(r.tags || "").split(",").map(norm).filter(Boolean),
      v: r.votes | 0, c: r.clickcount | 0, t: r.clicktrend | 0, b: r.bitrate | 0, e: -1,
    };
    if (!audioOK(x)) continue;
    out.push(x);
  }
  return out;
}
const score = r => r.c + r.v * 8 + Math.max(0, r.t) * 3;
function classify(name, tags) {
  const nn = norm(name);
  const text = nn + " | " + tags.join(" | ");
  const musicTag = tags.some(t => RE.music.test(t));
  if (RE.quran.test(text)) return "QURAN";
  if (RE.sports.test(text)) return "SPORTS";
  if (RE.news.test(nn) || (tags.some(t => TAG_NEWS.test(t)) && !musicTag)) return "NEWS";
  if (RE.talk.test(nn) || (tags.some(t => TAG_TALK.test(t)) && !musicTag)) return "TALK";
  if (RE.tarab.test(text) || musicTag || RE.music.test(nn)) return "MUSIC";
  return "EGYPT";
}
function seedRec(s, i) {
  const fi = s[0] ? FAMIDX.get(s[0]) : undefined;
  const nm = s[1] || (fi !== undefined ? FAME[fi].name : s[0]);
  const r = compact([{ stationuuid: "seed" + i, name: nm, url: s[3], countrycode: s[2], language: "arabic", codec: "MP3", tags: "", votes: 0, clickcount: 1, bitrate: 128, _seed: 1 }])[0];
  if (r && fi !== undefined) r.fe = fi;
  return r;
}
/* server ordering: not-reported-bad, https, then CHEAPEST bitrate first (<=40 / <=72 / <=112 kbps / rest), RB-verified before seed */
function kbOf(x, hs) { return x.b > 0 ? x.b : (hs.kb.get(h32(ukey(x.u))) || 0); }
function srvRank(x, hs) {
  const k = h32(ukey(x.u)), kb = kbOf(x, hs);
  const bucket = kb <= 0 ? 3 : kb <= 40 ? 0 : kb <= 72 ? 1 : kb <= 112 ? 2 : 3;
  return (hs.bad.has(k) ? 1000 : 0) + (/^https:/i.test(x.u) ? 0 : 200) + bucket * 20 + (x.seed ? 5 : 0);
}
async function probeAll(urls) {
  const out = [];
  for (let i = 0; i < urls.length; i += 6) out.push(...await Promise.all(urls.slice(i, i + 6).map(probe)));
  return out;
}

/* ===== audio probe: returns "ok" | "video" | "dead" ===== */
async function probe(u) {
  try {
    const hls = /\.m3u8(?:[?#]|$)/i.test(u);
    const hd = { "Icy-MetaData": "0", "User-Agent": "RadioJoo/2.0" };
    if (!hls) hd.Range = "bytes=0-2047";
    const r = await fetch(u, { headers: hd, signal: AbortSignal.timeout(4500) });
    const ct = (r.headers.get("content-type") || "").toLowerCase();
    const drop = () => { try { r.body && r.body.cancel(); } catch (e) { } };
    if (!(r.ok || r.status === 206)) { drop(); return "dead"; }
    if (/^video\//.test(ct) || /mp2t|x-flv|x-matroska/.test(ct)) { drop(); return "video"; }
    if (hls || /mpegurl/.test(ct)) {
      const t = (await r.text()).slice(0, 30000);
      if (!/#EXTM3U/.test(t)) return "dead";
      if (/RESOLUTION=|CODECS="[^"]*(?:avc|hvc|hev|vp0|av01)|#EXT-X-I-FRAME/i.test(t)) return "video";
      return "ok";
    }
    drop();
    return /audio|ogg|octet-stream|aac|mpeg|binary|stream/.test(ct) ? "ok" : "dead";
  } catch (e) { return "dead"; }
}
async function verify(list, bud) {
  const jobs = [];
  for (const st of list) for (const x of st.s) if (x.k) jobs.push(x);
  const todo = jobs.slice(0, Math.max(0, bud.cap - bud.n));
  bud.n += todo.length;
  for (let i = 0; i < todo.length; i += 6) {
    const part = todo.slice(i, i + 6);
    const res = await Promise.all(part.map(x => probe(x.u)));
    part.forEach((x, j) => { x.v = res[j]; });
  }
  const out = [];
  for (const st of list) {
    let s = st.s.filter(x => x.v !== "video");
    const live = s.filter(x => x.v !== "dead");
    if (live.length) s = live;
    if (!s.length) continue;
    out.push(Object.assign({}, st, { s: s.map(x => ({ u: x.u, b: x.b, h: 0 })) }));
  }
  return out;
}

function getKV(env) {
  return env.RADIO_KV || env.KV || Object.values(env || {}).find(v => v && typeof v.get === "function" && typeof v.put === "function" && typeof v.list === "function") || null;
}
async function loadHS(env) {
  const out = { bad: new Set(), kb: new Map() };
  const kv = getKV(env); if (!kv) return out;
  try {
    const t = await kv.get("hs"); if (!t) return out;
    const o = JSON.parse(t), now = Date.now();
    for (const k in o) {
      const x = o[k]; if (now - x.t > HSMS) continue;
      if (x.f >= 3 && now - x.t < BADMS) out.bad.add(k);
      if (x.k >= 8 && x.k <= 400) out.kb.set(k, x.k);
    }
  } catch (e) { }
  return out;
}
async function loadHeal(env) {
  const kv = getKV(env); if (!kv) return {};
  try { const t = await kv.get("heal"); return t ? JSON.parse(t) : {}; } catch (e) { return {}; }
}
async function healSave(env, id, servers) {
  const kv = getKV(env); if (!kv) return;
  try {
    const t = await kv.get("heal"); const o = t ? JSON.parse(t) : {};
    delete o[id]; o[id] = servers.slice(0, 12).map(x => ({ u: x.u, b: x.b | 0 }));
    const ks = Object.keys(o); if (ks.length > 300) ks.slice(0, ks.length - 300).forEach(k => delete o[k]);
    await kv.put("heal", JSON.stringify(o));
  } catch (e) { }
}
async function persistCatalog(env, c, stations) {
  const body = JSON.stringify({ v: 2, stations });
  let servers = 0; const cats = {};
  for (const s of stations) { servers += s.s.length; cats[s.c] = (cats[s.c] || 0) + 1; }
  const nc = { body, t: c.t, etag: '"' + h32(body) + stations.length + '"', n: stations.length, s: servers, cats };
  MEM = nc;
  const kv = getKV(env);
  if (kv) { try { await kv.put("catalog", body); await kv.put("catalog_meta", JSON.stringify({ t: nc.t, etag: nc.etag, n: nc.n, s: nc.s, cats })); } catch (e) { } }
}

async function build(env) {
  CC.clear();
  const bud = { n: 0, cap: SUBCAP };
  const hs = await loadHS(env); const healMap = await loadHeal(env);
  const b = "/json/stations/search?hidebroken=true&order=clickcount&reverse=true&limit=";
  const Q = [b + "6000&language=arabic", b + "3500&countrycode=EG", b + "2500&tag=quran"];
  for (const n of NAMEQ) Q.push(b + "120&name=" + encodeURIComponent(n));
  const all = new Map();
  for (let i = 0; i < Q.length; i += 6) {
    const res = await Promise.all(Q.slice(i, i + 6).map(q => rb(q, bud)));
    for (const arr of res) for (const r of compact(arr)) if (!all.has(r.id)) all.set(r.id, r);
  }
  SEEDS.forEach((s, i) => { const r = seedRec(s, i); if (r) all.set(r.id, r); });

  const groups = new Map();
  for (const r of all.values()) {
    if (!prep(r)) continue;
    const key = gkey(r); if (!key) continue;
    let g = groups.get(key); if (!g) groups.set(key, g = []);
    g.push(r);
  }

  const tmp = [];
  for (const [key, g] of groups) {
    g.sort((a, z) => score(z) - score(a));
    const best = g.find(x => !x.seed) || g[0];
    const idx = g[0].e;
    const e = idx >= 0 ? FAME[idx] : null;
    let cc;
    if (!e) cc = "EG";
    else if (e.ccf === "keep") cc = g.map(x => x.cc).find(Boolean) || "";
    else if (e.ccf === "egcue") cc = (g.some(x => x.cc === "EG") && EGYNAME.test(best.nn)) ? "EG" : (g.map(x => x.cc).find(x => x && x !== "EG") || "");
    else if (e.ccf !== undefined) cc = e.ccf;
    else if (e.lim === "eg") cc = "EG";
    else cc = g.map(x => x.cc).find(x => x && x !== "EG") || "";
    const tags = [...new Set(g.flatMap(x => x.tags))];
    const cat = e ? e.c : classify(best.n, tags);
    if (!e && cat === "QURAN" && !EGYNAME.test(best.nn)) continue;
    let sc = score(best); for (let i = 1; i < g.length; i++) sc += score(g[i]) * 0.5;
    sc = Math.round(sc);
    const p = e ? (1900000000 - idx * 1000000 + Math.min(999999, sc)) : (1000000000 + Math.min(500000000, sc));
    const sid = "rj" + h32(key) + h32(key.split("").reverse().join(""));
    const seen = new Set(), sv = [];
    const hsrv = (healMap[sid] || []).map(x => ({ u: x.u, b: x.b | 0, seed: false }));
    let pool = g.concat(hsrv).sort((a, z) => srvRank(a, hs) - srvRank(z, hs));
    const good = pool.filter(x => !hs.bad.has(h32(ukey(x.u))));
    if (good.length >= 3) pool = good;
    if (pool.filter(x => /^https:/i.test(x.u)).length >= 2) pool = pool.filter(x => /^https:/i.test(x.u));
    for (const x of pool) {
      const k = ukey(x.u); if (seen.has(k)) continue; seen.add(k);
      sv.push({ u: x.u, b: kbOf(x, hs), h: 0, k: (x.seed || /\.m3u8(?:[?#]|$)/i.test(x.u)) ? 1 : 0 });
      if (sv.length >= MAXSRV) break;
    }
    const nm = e ? (e.fam ? (g.find(x => x.seed) && !g.some(x => !x.seed) ? g[0].n : best.n) : e.name) : best.n;
    tmp.push({ idx, fam: !!(e && e.fam), ex: !e, sc, st: { id: sid, n: nm, c: cat, cc, p: Math.min(2000000000, Math.round(p)), s: sv } });
  }

  let stations = [];
  const fb = new Map(), extras = [];
  for (const x of tmp) {
    if (x.ex) extras.push(x);
    else if (x.fam) { let a = fb.get(x.idx); if (!a) fb.set(x.idx, a = []); a.push(x); }
    else stations.push(x.st);
  }
  for (const [idx, arr] of fb) {
    const e = FAME[idx];
    arr.filter(x => x.sc >= e.min).sort((a, z) => z.sc - a.sc).slice(0, e.cap || 50).forEach(x => stations.push(x.st));
  }
  extras.filter(x => x.sc >= 15).sort((a, z) => z.sc - a.sc).slice(0, 140).forEach(x => stations.push(x.st));

  if (stations.length < 60) return null;
  stations.sort((a, z) => z.p - a.p);
  stations = await verify(stations, bud);
  if (stations.length < 60) return null;
  const cats = {}; let servers = 0;
  for (const s of stations) { cats[s.c] = (cats[s.c] || 0) + 1; servers += s.s.length; }
  const body = JSON.stringify({ v: 2, stations });
  return { body, t: Date.now(), etag: '"' + h32(body) + stations.length + '"', n: stations.length, s: servers, cats };
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
      const all = JSON.parse(c.body).stations;
      const ix = all.findIndex(s => s.id === id);
      if (ix < 0) return json({}, 404);
      const st = all[ix];
      const hs = await loadHS(env);
      const bud = { n: 0, cap: 24 };
      const q0 = st.n.split(" - ")[0];
      const r0 = { n: q0, nn: norm(q0), cc: st.cc || "", lang: "arabic", tags: [], rel: true, e: -1 };
      prep(r0);
      const k0 = gkey(r0);
      const sq = (r0.e >= 0 && FAME[r0.e].q) ? FAME[r0.e].q : q0;
      const qs = sq === q0 ? [sq] : [sq, q0];
      const arrs = await Promise.all(qs.map(q => rb("/json/stations/search?hidebroken=true&limit=80&order=clickcount&reverse=true&name=" + encodeURIComponent(q), bud)));
      const found = compact([].concat(...arrs));
      const have = new Set(st.s.map(x => ukey(x.u)));
      const seenX = new Set();
      const extra = !k0 ? [] : found.filter(r => prep(r) && gkey(r) === k0 && !have.has(ukey(r.u)) && !seenX.has(ukey(r.u)) && seenX.add(ukey(r.u)))
        .sort((a, z) => score(z) - score(a)).map(r => ({ u: r.u, b: r.b, h: 0 }));
      const cand = st.s.concat(extra).slice(0, 18);
      const res = await probeAll(cand.map(x => x.u));
      const ok = [], rest = [];
      cand.forEach((x, i) => { if (res[i] === "video") return; (res[i] === "ok" ? ok : rest).push(x); });
      const rk = (a, z) => srvRank(a, hs) - srvRank(z, hs);
      ok.sort(rk); rest.sort(rk);
      const added = ok.filter(x => !have.has(ukey(x.u)));
      st.s = ok.concat(rest).slice(0, 14);
      if (added.length || cand.length !== ok.length + rest.length) {
        await healSave(env, id, ok);
        all[ix] = st;
        await persistCatalog(env, c, all);
      }
      return json({ station: st });
    }
    if (path === "/v2/report") {
      try {
        let items = [];
        const u1 = url.searchParams.get("u");
        if (u1) items.push({ u: u1, ok: url.searchParams.get("ok") === "1" ? 1 : 0, b: 0 });
        if (req.method === "POST") {
          const j = await req.json().catch(() => null);
          if (j) {
            if (Array.isArray(j.r)) items = items.concat(j.r.slice(0, 200));
            else if (j.u || j.url) items.push({ u: j.u || j.url, ok: j.ok ? 1 : 0, b: j.b | 0 });
          }
        }
        const kv = getKV(env);
        if (items.length && kv) {
          const t = await kv.get("hs"); const o = t ? JSON.parse(t) : {}; const now = Date.now();
          for (const it of items) {
            if (!it || typeof it.u !== "string" || !/^https?:\/\//i.test(it.u)) continue;
            const k = h32(ukey(it.u)); const x = o[k] || { f: 0, o: 0, t: 0, k: 0 };
            if (it.ok) { x.o++; x.f = 0; } else x.f++;
            const kb = it.b | 0; if (kb >= 8 && kb <= 400) x.k = kb;
            x.t = now; o[k] = x;
          }
          for (const kk in o) if (now - o[kk].t > HSMS) delete o[kk];
          const ks = Object.keys(o); if (ks.length > 3000) ks.sort((a, z) => o[a].t - o[z].t).slice(0, ks.length - 3000).forEach(kk => delete o[kk]);
          await kv.put("hs", JSON.stringify(o));
        }
      } catch (e) { }
      return json({ ok: true });
    }
    return json({ ok: true, service: "radio-joo-proxy", v: 2 });
  },
  async scheduled(event, env, ctx) { ctx.waitUntil(rebuild(env)); },
};
