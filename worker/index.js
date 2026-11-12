const MIRRORS = ["https://de1.api.radio-browser.info", "https://fi1.api.radio-browser.info", "https://at1.api.radio-browser.info", "https://de2.api.radio-browser.info"];
const ARAB = new Set("EG SA AE KW QA BH OM YE IQ SY LB JO PS LY TN DZ MA MR SD SO DJ KM".split(" "));
const TTL = 6 * 3600 * 1000, THROTTLE = 45000;
let MEM = null, BUILDING = null, LASTREF = 0;

const BW = { A: "\u0627", b: "\u0628", t: "\u062A", v: "\u062B", j: "\u062C", H: "\u062D", x: "\u062E", d: "\u062F", "*": "\u0630", r: "\u0631", z: "\u0632", s: "\u0633", "$": "\u0634", S: "\u0635", D: "\u0636", T: "\u0637", Z: "\u0638", E: "\u0639", g: "\u063A", f: "\u0641", q: "\u0642", k: "\u0643", l: "\u0644", m: "\u0645", n: "\u0646", h: "\u0647", w: "\u0648", y: "\u064A", "'": "\u0621" };
const B = s => s.replace(/[A-Za-z*$']/g, c => BW[c] || c);
const rx = (lat, ar) => new RegExp(lat.concat(ar.map(B)).join("|"));
const AR_SCRIPT = /[\u0600-\u06FF]/;
const arabicish = r => r.lang.includes("arab") || AR_SCRIPT.test(r.n) || ARAB.has(r.cc);

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

/* ===== HUMAN-CURATED FAME TABLE =====
   Array order = real-world fame rank (earlier = more famous). One entry = ONE merged station.
   c = functional category (Egyptian stations keep cc=EG so they ALSO show in the Egypt tab)
   eg = Egyptian only (cc EG or unknown) | any = keep from any country | intl = international broadcaster
   multi = family of stations (kept separate per name) | nocc = hide country | pb = priority base for multi */
const FAME = [];
const F = (nm, lat, ar, o) => FAME.push(Object.assign({ name: nm ? nm.replace(/\|(.*)$/, (m, a) => " - " + B(a)) : null, re: rx(lat, ar || []) }, o || {}));
const QN = "(?:qur ?an|koran|kuran)";
const EGO = { c: "EGYPT", eg: 1 };
const EGM = { c: "MUSIC", eg: 1 };
const QR = { c: "QURAN", any: 1 };
const TR = { c: "MUSIC", nocc: 1, any: 1 };
const NW = { c: "NEWS" };
const NI = { c: "NEWS", intl: 1 };
const M = { multi: 1 };

// 0-3 QURAN MAIN
F("Quran Cairo|qrAn AlqAhrh", [`${QN}.{0,30}(?:cairo|egypt|misr|masr)|(?:cairo|egypt|misr|masr).{0,30}${QN}`], ["qrAn.{0,30}(?:AlqAhrh|mSr)|(?:AlqAhrh|mSr).{0,30}qrAn"], { c: "QURAN", eg: 1 });
F("Saudi Quran Makkah|qrAn mkh AlsEwdyh", [`saudi.{0,25}${QN}|${QN}.{0,25}saudi|ksa.{0,10}${QN}|makk?ah|mecca|al ?haram|masjid ?al ?haram`], ["AlHrm Almky|AlHrmyn|qrAn.{0,30}AlsEwdyh|AlsEwdyh.{0,30}qrAn|Almsjd AlHrAm"], { c: "QURAN" });
F("Saudi Quran Madinah|qrAn Almdynh", [`(?:madin\\w*|medin\\w*|nabaw\\w*).{0,25}${QN}|${QN}.{0,25}(?:madin\\w*|medin\\w*|nabaw\\w*)|prophet.{0,8}mosque`], ["qrAn.{0,25}Almdynh|Almdynh.{0,25}qrAn|Almsjd Alnbwy"], { c: "QURAN" });
F("Al Majd Quran|Almjd llqrAn AlkrAym", ["al ?majd\\b|almajd\\b"], ["Almjd"], { c: "QURAN" });

// 4-8 TARAB ICONS
F("Umm Kulthum|Am klvwm", ["oum ?kal[ts]h?oum|umm? ?kul[ts]h?oum|om ?kalthoum|oum ?koulsoum|umm?kalthum|um ?kalthoum|umm ?kulthum|um ?kulthum|om ?kalsoum|oum ?kalsoum"], ["Am klvwm"], TR);
F("Abdel Halim Hafez|Ebd AlHlym HAfZ", ["abd(?:el|ul)? ?hal[ei]+m|abdelhalim"], ["Ebd AlHlym"], TR);
F("Fairouz|fyrwz", ["fairuz|fayrouz|fairouz|feyrouz|fayruz|fairoz"], ["fyrwz"], TR);
F("Mohamed Abdel Wahab|mHmd Ebd AlwhAb", ["abd(?:el|ul)? ?wahab|abdelwahab"], ["Ebd AlwhAb"], TR);
F("Farid Al Atrash|fryd AlATr$", ["far[ie]e?d.{0,12}atrash|farid el atrash"], ["fryd AlATr$"], TR);

// 9-15 NEWS
F("Al Jazeera|Aljzyrh", ["al ?jazeera|aljazeera|jazeera"], ["Aljzyrh"], NW);
F("Al Arabiya|AlErbyh", ["al ?arabiya|alarabiya|al ?arabiyah|alarabiyah", "^" + B("AlErbyh") + "( |$)"], ["qnAh AlErbyh"], NW);
F("Sky News Arabia|skAy nywz Erbyh", ["sky ?news.{0,8}arab|sky ?news arabia"], ["skAy nywz"], NI);
F("BBC Arabic|by by sy Erbyh", ["bbc.{0,12}arab|bbc arabic"], ["by by sy"], NI);
F("Monte Carlo Doualiya|mwnty kArlw Aldwlyh", ["monte ?carlo|mcd (?:fm|arabic|doualiya)|mc doualiya"], ["mwnt kArlw|mwnty kArlw|mwntykArlw"], NI);
F("France 24 Arabic|frAns 24 bAlErbyh", ["france ?24"], ["frAns ?24"], NI);
F("Asharq News|Al$rq llAxbAr", ["asharq|ashark|al ?sharq (?:news|bloomberg)"], ["Al$rq llAxbAr|Al$rq blwmbrj|Al$rq bylwmbyrj"], NW);

// 16-37 EGYPT (cc=EG always; c = functional category)
F("Nogoum FM|njwm Af Am", ["nogoum|nogom|nujum|nogum|nogoom"], ["njwm Af Am|Af Am njwm"], EGM);
F("Nile FM|nAyl Af Am", ["nile ?fm|nilefm|nile radio"], ["nAyl Af Am|nyl Af Am"], EGM);
F("Radio Masr|rAdyw mSr", ["radio ?masr\\b|masr ?fm|radio ?misr\\b"], ["rAdyw mSr(?!y)"], { c: "NEWS", eg: 1 });
F("Mega FM|myjA Af Am", ["mega ?fm|mega 92|mega ?masr"], ["myjA Af Am|myjA"], EGM);
F("Nagham FM|ngm Af Am", ["nagham|naghem"], ["ngm Af Am|Af Am ngm"], EGM);
F("9090 FM|9090 Af Am", ["9090|90 ?90 ?fm"], ["9090"], EGM);
F("Radio Hits|rAdyw hyts", ["radio ?hits|hits ?fm|hits ?radio"], ["rAdyw hyts|hyts Af Am"], EGM);
F("Shaabi FM|$Eby Af Am", ["sha+bi ?fm|sha+bi radio|sha3by|el ?shaabi"], ["$Eby Af Am|rAdyw $Eby"], EGM);
F("Masr El Gdida|rAdyw mSr Aljdydh", ["masr el ?(?:gdid|jdid|gedid)|misr el ?gdid|new egypt"], ["mSr Aljdydh|mSr Aljdyd"], { c: "TALK", eg: 1 });
F("Al Barnamg Al Aam|AlbrnAmj AlEAm", ["general (?:program|programme)|barnameg ?(?:el|al)? ?aam|el barnameg el aam"], ["AlbrnAmj AlEAm"], EGO);
F("Sawt El Arab|Swt AlErb", ["voice of (?:the )?arabs|sawt ?(?:el|al)? ?arab|sout el arab"], ["Swt AlErb"], EGO);
F("Al Sharq Al Awsat|A*AEh Al$rq AlAwsT", ["middle east (?:radio|fm)|sharq ?(?:el|al)? ?awsat\\b|el sharq el awsat"], ["Al$rq AlAwsT"], EGO);
F("ON Sport FM|Awn spwrt Af Am", ["on ?sport|onsport|ontime ?sports"], ["Awn spwrt|An spwrt"], { c: "SPORTS", eg: 1 });
F("Youth & Sports|Al$bAb wAlryADh", ["youth ?(?:and)? ?sports|shabab ?(?:wa|wel|we)? ?riyada|el ?shabab ?w ?el ?riyada"], ["Al$bAb wAlryADh|Al$bAb wAl ryADh"], { c: "SPORTS", eg: 1 });
F("Mix FM|myks Af Am", ["mix ?fm|mix 106|mix ?egypt"], ["myks Af Am"], EGM);
F("NRJ Egypt|Anrjy mSr", ["nrj ?(?:egypt|masr|misr)|energy ?(?:fm|egypt|masr)"], ["Anrjy"], EGM);
F("Rehab FM|rhAb Af Am", ["rehab ?(?:fm|radio|city)"], ["rhAb Af Am|rhAb"], EGM);
F("Hawak FM|hwAk Af Am", ["hawak|hawwak"], ["hwAk"], EGM);
F("Masriyat|mSryAt", ["masriyat|masryat|masriat|masreyat"], ["mSryAt"], EGM);
F("Aghani Aghani|AgAny AgAny", ["aghani ?aghani|agani ?agani|aghany ?aghany"], ["AgAny AgAny"], EGM);
F("Cultural Program|AlbrnAmj AlvqAfy", ["cultural (?:program|programme)|el ?thaqafi"], ["AlbrnAmj AlvqAfy"], EGO);
F("Egyptian Radio ERTU|AlA*AEh AlmSryh", ["egyptian radio|ertu\\b|radio of egypt"], ["AlA*AEh AlmSryh"], EGO);

// 38-: MORE MUSIC ICONS / ARAB POP
F("Rotana FM|rwtAnA Af Am", ["rotana ?(?:fm|radio)?"], ["rwtAnA"], { c: "MUSIC" });
F("MBC FM|Am by sy Af Am", ["mbc ?fm|mbc ?radio"], ["Am by sy Af Am"], { c: "MUSIC" });
F("Asmahan|AsmhAn", ["asmahan|asmahane"], ["AsmhAn"], TR);
F("Sabah Fakhri|SbAH fxry", ["sabah ?fakhri|sabah el ?fakhri|sabah fakhry"], ["SbAH fxry"], TR);
F("Sayed Darwish|syd drwy$", ["sayed darwish|sayyid darwish|sayed derwish"], ["syd drwy$"], TR);
F("Warda Al Jazairia|wrdh Aljzayryh", ["warda al ?jaz|warda el ?gazaer"], ["wrdh Aljz"], TR);
F("Najat Al Saghira|njAh AlSgyrh", ["najat (?:el|al)? ?saghira"], ["njAh AlSgyrh"], TR);
F("Zaman El Gameel|zmAn Aljmyl", ["golden (?:era|age)|zaman el ?gameel|zaman al ?jameel|zaman ?gameel"], ["Alzmn Aljmyl|zmAn Aljmyl"], TR);

// NEWS (international, lower)
F("DW Arabic|dwyt$h fylh Erbyh", ["\\bdw\\b|deutsche welle"], ["dwyt$h fylh|dw Erbyh"], NI);
F("RT Arabic|rwsyA Alywm", ["\\brt\\b.{0,10}arab|russia today|rt arabic"], ["rwsyA Alywm"], NI);
F("Alhurra|AlHrh", ["alhurra|al ?hurra"], ["AlHrh"], NI);
F("Radio Sawa|rAdyw swA", ["radio sawa|\\bsawa\\b"], ["rAdyw swA"], NI);
F("Al Mayadeen|AlmyAdyn", ["al ?mayadeen|almayadeen"], ["AlmyAdyn"], NW);
F("Sputnik Arabic|sbwtnyk", ["sputnik"], ["sbwtnyk"], NI);
F("TRT Arabic|ty Ar ty Erby", ["trt ?arab"], ["ty Ar ty Erby"], NI);

// SPORTS
F("Kora FM|kwrh Af Am", ["kora ?fm|kooora ?fm|radio kora"], ["kwrh Af Am"], { c: "SPORTS" });

// QURAN RECITERS (order = fame)
F("Minshawi|Almn$Awy", ["min?shawi|menshawy|minshawy|menshawi"], ["Almn$Awy"], QR);
F("Al Husary|AlHSry", ["h[ou]s+ar[iy]"], ["AlHSry"], QR);
F("Abdul Basit Abdul Samad|Ebd AlbAsT Ebd AlSmd", ["abd(?:ul|el|al)? ?(?:al ?)?bas[iy]t|abdel ?bas+et|abdulbasit"], ["Ebd AlbAsT"], QR);
F("Mustafa Ismail|mSTfy AsmAEyl", ["m[ou]+stafa ?isma[ei]+l|mostafa ismael"], ["mSTfy AsmAEyl"], QR);
F("Mahmoud Ali Al Banna|mHmwd Ely AlbnA", ["mahmoud ?(?:ali|aly)? ?(?:al ?)?banna|\\bbanna\\b"], ["mHmwd Ely AlbnA|AlSyx AlbnA"], QR);
F("Al Tablawi|AlTblAwy", ["tablawi|toblawi|tabliwi"], ["AlTblAwy"], QR);
F("Maher Al Muaiqly|mAhr AlmEyqly", ["muaiqly|mueaqly|moaiqly|moayqly|maher.{0,12}(?:mu|mo)"], ["AlmEyqly"], QR);
F("Mishary Alafasy|m$Ary AlEfAsy", ["afasy|alafasy|alafasi|afasi|mish[ae]ry"], ["AlEfAsy|m$Ary"], QR);
F("Abdulrahman Al Sudais|Ebd AlrHmn Alsdys", ["sudais|sudays|sudaiss|soudais"], ["Alsdys"], QR);
F("Saud Al Shuraim|swd Al$rym", ["shuraim|shuraym|shureym"], ["Al$rym"], QR);
F("Saad Al Ghamdi|sEd AlgAmdy", ["ghamdi|ghamidi|ghamdy"], ["AlgAmdy"], QR);
F("Yasser Al Dosari|yAsr Aldwsry", ["dosari|dossary|dosary"], ["Aldwsry"], QR);
F("Nasser Al Qatami|nASr AlqTAmy", ["qatami"], ["AlqTAmy"], QR);
F("Ahmed Al Ajmi|AHmd AlEjmy", ["ajmi|al ?ajmy|ajmy"], ["AlEjmy"], QR);
F("Abu Bakr Al Shatri|Abw bkr Al$ATry", ["shatri|shaatree"], ["Al$ATry"], QR);
F("Al Hudhaify|AlH*yfy", ["hudhaify|huthaify|hudhayfi|hudaify"], ["AlH*yfy"], QR);
F("Hani Al Rifai|hAny AlrfAEy", ["hani ?(?:al ?)?rifa"], ["hAny AlrfAEy"], QR);
F("Abdullah Basfar|Ebd Allh bSfr", ["basfar"], ["bSfr"], QR);
F("Muhammad Jibreel|mHmd jbryl", ["jibreel|jibril|gibril"], ["mHmd jbryl"], QR);

// OMNI QURAN (MP3Quran / Qurango) - one station per stream family, ranked right after the reciters
F(null, ["omni ?quran|qurango|mp3 ?quran|mp3quran"], ["Amny qrAn"], { c: "QURAN", multi: 1, pb: 1700000000 });

// MAJOR ARAB STATE / REGIONAL RADIOS (family entries, filtered by popularity later)
F(null, ["saudi (?:radio|broadcasting)|\\bsbc\\b|panorama fm|jeddah (?:radio|fm)|riyadh (?:radio|fm)|mix fm saudi|mazika"], ["A*AEh AlsEwdyh|A*AEh AlryAD|A*AEh jdh"], M);
F(null, ["radio jordan|jordan (?:radio|broadcasting)|hayat fm|amen fm|beat fm|mood fm|sawt el ?ghad"], ["A*AEh AlArdn|hyAh Af Am"], M);
F(null, ["emarat|dubai (?:radio|fm|92|eye)|abu dhabi (?:radio|fm|classic)|sharjah (?:radio|fm)|radio sharjah|uae (?:radio|quran)"], ["A*AEh dby|A*AEh Al$Arqh|AlAmArAt|AbwZby"], M);
F(null, ["kuwait (?:radio|quran|fm)|radio kuwait|marina fm|kuwait broadcasting"], ["A*AEh AlkwyT|AlkwyT"], M);
F(null, ["qatar (?:radio|quran|broadcasting)|radio qatar|\\bqbs\\b|al ?rayyan|qatar sports"], ["A*AEh qTr|AlryAn|qTr"], M);
F(null, ["bahrain (?:radio|fm|quran)|radio bahrain"], ["A*AEh AlbHryn"], M);
F(null, ["oman (?:radio|fm|quran)|radio oman|sultanate of oman"], ["A*AEh slTnh EmAn|EmAn"], M);
F(null, ["sawt (?:lubnan|lebnan|libnan|el ?ghad|al ?ghad)|voice of lebanon|lebanese radio|radio liban|radio lebanon|tele liban|nrj (?:lebanon|liban)|radio one lebanon|light fm|sawt el mada"], ["swt lbnAn|Swt Algd|Alnwr"], M);
F(null, ["syria(?:n)? (?:radio|broadcasting)|damascus (?:fm|radio)|sham fm|radio syria"], ["A*AEh swryA|A*AEh dm$q|$Am Af Am"], M);
F(null, ["iraq(?:i)? (?:radio|media|network)|radio iraq|dijlah|al ?rasheed|\\bimn\\b|nas radio|mirbad|al ?sumaria"], ["A*AEh AlErAq|djlh|Alr$yd"], M);
F(null, ["palestin|filastin|falastin|raya fm|ajyal|nisaa|nablus|aqsa|bethlehem 2000|ramallah|\\bquds\\b"], ["flsTyn|rAyh|AjyAl|nsA'|AlAqSy|nAbls|rAm Allh"], M);
F(null, ["yemen (?:radio|national|fm)|radio yemen|aden (?:radio|fm)|sana ?a (?:radio|fm)|radio aden"], ["A*AEh Alymn|A*AEh Edn"], M);
F(null, ["medi ?1|hit radio|chada|\\bmfm\\b|radio mars|aswat|mohammed (?:vi|6|sixth)|moroccan radio|radio maroc|radio plus|atlantic radio|cap radio|luxe radio|2m radio"], ["mydy 1|A*AEh Almgrb|mHmd AlsAds"], M);
F(null, ["alg.{0,2}ri|radio dzair|dzair|jil fm|bahdja|chaine (?:1|2|3)|radio coran"], ["A*AEh AljzAyr|AljzAyr"], M);
F(null, ["radio tunis|tunisia|tunisie|mosaique|shems fm|jawhara|express fm|zitouna|diwan fm|cap fm|nationale tunisienne|\\bratn\\b"], ["twns|Alzytwnh"], M);
F(null, ["libya (?:radio|fm|quran)|radio libya|libya al ?(?:watan|hadith)"], ["A*AEh lybyA"], M);
F(null, ["sudan(?:ese)? (?:radio|national|quran|broadcasting)|omdurman|radio sudan"], ["swdAn|AmdrmAn"], M);
F(null, ["mauritani|nouakchott|somali (?:national )?radio|radio mogadishu|mogadishu|djibouti|comoros|radio comores"], ["mwrytAnyA|AlSwmAl|jybwty"], M);

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

/* Extra stream URLs (Omni Quran / Qurango). solo=1 -> created as its own station; otherwise only added as backup to an existing station. */
const SEEDS = [
  ["Quran Cairo Radio", "EG", "https://stream.radiojar.com/8s5u5tpdtwzuv", 9000000, 1],
  ["Omni Quran Mix", "", "https://backup.qurango.net/radio/mix", 1000, 1],
  ["Omni Quran Tarateel", "", "https://backup.qurango.net/radio/tarateel", 900, 1],
  ["Mishary Alafasy Radio", "", "https://backup.qurango.net/radio/mishary_alafasi", 0, 0],
  ["Maher Al Muaiqly Radio", "", "https://backup.qurango.net/radio/maher_al_meaqli", 0, 0],
  ["Yasser Al Dosari Radio", "", "https://backup.qurango.net/radio/yasser_aldosari", 0, 0],
  ["Ahmad Al Ajmi Radio", "", "https://backup.qurango.net/radio/ahmad_alajmy", 0, 0],
  ["Saad Al Ghamdi Radio", "", "https://backup.qurango.net/radio/saad_alghamdi", 0, 0],
  ["Nasser Al Qatami Radio", "", "https://backup.qurango.net/radio/nasser_alqatami", 0, 0],
  ["Abdulrahman Al Sudais Radio", "", "https://backup.qurango.net/radio/abdulrahman_alsudaes", 0, 0],
  ["Saud Al Shuraim Radio", "", "https://backup.qurango.net/radio/saud_alshuraim", 0, 0],
  ["Minshawi Radio", "", "https://backup.qurango.net/radio/mohammed_siddiq_alminshawi", 0, 0],
  ["Husary Radio", "", "https://backup.qurango.net/radio/mahmood_khalil_alhussary", 0, 0],
  ["Abdul Basit Abdul Samad Radio", "", "https://backup.qurango.net/radio/abdulbasit_abdulsamad", 0, 0],
  ["Mustafa Ismail Radio", "", "https://backup.qurango.net/radio/mustafa_ismail", 0, 0],
  ["Mahmoud Ali Al Banna Radio", "", "https://backup.qurango.net/radio/mahmoud_ali_albanna", 0, 0],
  ["Abu Bakr Al Shatri Radio", "", "https://backup.qurango.net/radio/abu_bakr_al_shatri", 0, 0],
];

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
    const cc = String(r.countrycode || "").toUpperCase();
    const lang = String(r.language || "").toLowerCase();
    const relevant = !cc || ARAB.has(cc) || lang.includes("arab") || AR_SCRIPT.test(n) || /quran|koran|qurango|mp3/.test(nn);
    out.push({
      id: r.stationuuid || (n + u), n, nn, u, cc, lang,
      tags: String(r.tags || "").split(",").map(norm).filter(Boolean),
      v: r.votes | 0, c: r.clickcount | 0, t: r.clicktrend | 0, b: r.bitrate | 0,
      f: relevant ? fameOf(nn) : -1,
    });
  }
  return out;
}
/* 0 = drop, 1 = core (always kept), 2 = regional family (top by popularity), 3 = extra (only if very popular) */
function kindOf(r) {
  if (r.nn.length < 2) return 0;
  if (RE.drop.test(r.nn + " " + r.tags.join(" "))) return 0;
  const e = r.f >= 0 ? FAME[r.f] : null;
  if (e) {
    if (/\benglish\b/.test(r.nn) && !AR_SCRIPT.test(r.n) && !e.any) return 0;
    if (e.eg) return (r.cc === "EG" || !r.cc) ? 1 : 0;
    if (e.multi) { if (e.pb) return 1; return ARAB.has(r.cc) ? 2 : 0; }
    if (e.any) return 1;
    return arabicish(r) ? 1 : 0;
  }
  if (r.cc === "EG") return 1;
  if (!r.cc && EGYNAME.test(r.nn)) return 1;
  return arabicish(r) ? 3 : 0;
}
function prep(r) {
  const k = kindOf(r); if (!k) return false;
  const e = r.f >= 0 ? FAME[r.f] : null;
  r.k = k;
  r.eg = r.cc === "EG" || (!r.cc && k === 1 && !!((e && e.eg) || (!e && EGYNAME.test(r.nn))));
  return true;
}
const score = r => r.c + r.v * 8 + Math.max(0, r.t) * 3;
function classify(name, tags, eg, strict) {
  const nn = norm(name);
  const text = nn + " | " + tags.join(" | ");
  const musicTag = tags.some(t => RE.music.test(t));
  if (RE.quran.test(text)) return "QURAN";
  if (RE.sports.test(text)) return "SPORTS";
  if (RE.news.test(nn) || (tags.some(t => TAG_NEWS.test(t)) && !musicTag)) return "NEWS";
  if (RE.talk.test(nn) || (tags.some(t => TAG_TALK.test(t)) && !musicTag)) return "TALK";
  if (RE.tarab.test(text)) return "MUSIC";
  if (musicTag || RE.music.test(nn)) return "MUSIC";
  if (eg) return "EGYPT";
  return strict ? null : "MUSIC";
}
function seedRec(s, i) {
  return compact([{ stationuuid: "seed" + i, name: s[0], url: s[2], countrycode: s[1], language: "arabic", tags: "quran", votes: 0, clickcount: s[3] || 0, bitrate: 128 }])[0];
}
async function build() {
  FC.clear();
  const b = "/json/stations/search?hidebroken=true&order=clickcount&reverse=true&limit=";
  const Q = [b + "6000&language=arabic", b + "4000&countrycode=EG"];
  for (const cc of ["SA", "AE", "KW", "QA", "LB", "JO", "IQ", "MA", "DZ", "TN", "SY", "LY", "SD", "OM"]) Q.push(b + "500&countrycode=" + cc);
  for (const t of ["quran", "islamic", "tarab"]) Q.push(b + "2000&tag=" + t);
  for (const t of ["news", "talk", "sports"]) Q.push(b + "1000&language=arabic&tag=" + t);
  for (const n of ["omni quran", "qurango", "mp3quran", "quran", B("AlqrAn")]) Q.push(b + "1500&name=" + encodeURIComponent(n));
  for (const n of ["egypt", "masr", "misr", "cairo", "nile"]) Q.push(b + "600&name=" + encodeURIComponent(n));
  const all = new Map();
  for (let i = 0; i < Q.length; i += 6) {
    const res = await Promise.all(Q.slice(i, i + 6).map(q => rb(q)));
    for (const arr of res) for (const r of compact(arr)) if (!all.has(r.id)) all.set(r.id, r);
  }
  const deferred = [];
  SEEDS.forEach((s, i) => { const r = seedRec(s, i); if (!r) return; if (s[4]) all.set(r.id, r); else deferred.push(r); });
  const groups = new Map();
  for (const r of all.values()) {
    if (!prep(r)) continue;
    const key = gkey(r); if (!key) continue;
    let g = groups.get(key); if (!g) groups.set(key, g = []);
    g.push(r);
  }
  for (const r of deferred) { if (!prep(r)) continue; const g = groups.get(gkey(r)); if (g) g.push(r); }

  const tmp = []; let servers = 0;
  for (const [key, g] of groups) {
    g.sort((a, z) => score(z) - score(a));
    const best = g[0];
    const kind = Math.min.apply(null, g.map(x => x.k));
    const e = best.f >= 0 ? FAME[best.f] : null;
    const isEG = g.some(x => x.eg);
    let cc;
    if (e && e.nocc) cc = "";
    else if (isEG) cc = "EG";
    else cc = g.map(x => x.cc).find(Boolean) || "";
    const tags = [...new Set(g.flatMap(x => x.tags))];
    const cat = (e && e.c) ? e.c : classify(best.n, tags, isEG, kind === 3);
    if (!cat) continue;
    let sc = score(best); for (let i = 1; i < g.length; i++) sc += score(g[i]) * 0.5;
    sc = Math.round(sc);
    let p;
    if (e && !e.multi) p = 1900000000 - best.f * 1000000 + Math.min(999999, sc);
    else if (e && e.pb) p = e.pb + Math.min(50000000, sc);
    else if (kind === 1 && isEG) p = 1500000000 + Math.min(300000000, sc);
    else if (kind === 1) p = 1300000000 + Math.min(100000000, sc);
    else if (kind === 2) p = 1200000000 + Math.min(90000000, sc);
    else p = Math.min(900000000, sc);
    const seen = new Set(), sv = [];
    const ordered = g.slice().sort((a, z) => (/^https:/i.test(a.u) ? 0 : 1) - (/^https:/i.test(z.u) ? 0 : 1));
    for (const x of ordered) { const k = ukey(x.u); if (seen.has(k)) continue; seen.add(k); sv.push({ u: x.u, b: x.b, h: 0 }); if (sv.length >= 8) break; }
    tmp.push({ kind, sc, st: { id: "rj" + h32(key) + h32(key.split("").reverse().join("")), n: (e && e.name) ? e.name : best.n, c: cat, cc, p: Math.min(2000000000, Math.round(p)), s: sv } });
  }
  const core = [], multi = [], extra = [];
  for (const x of tmp) (x.kind === 1 ? core : x.kind === 2 ? multi : extra).push(x);
  const bySc = (a, z) => z.sc - a.sc;
  multi.sort(bySc); extra.sort(bySc);
  const selM = multi.filter(x => x.sc >= 40).slice(0, 110);
  const qx = extra.filter(x => x.st.c === "QURAN" && x.sc >= 30).slice(0, 70);
  const ox = extra.filter(x => x.st.c !== "QURAN" && x.sc >= 300).slice(0, 70);
  const stations = core.concat(selM, qx, ox).map(x => x.st);
  if (stations.length < 60) return null;
  stations.sort((a, z) => z.p - a.p);
  const cats = {};
  for (const s of stations) { cats[s.c] = (cats[s.c] || 0) + 1; servers += s.s.length; }
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
      const q0 = st.n.split(" - ")[0];
      const nn0 = norm(q0);
      const sk = gkey({ n: q0, nn: nn0, cc: st.cc || "", f: fameOf(nn0) }).split("|")[0];
      const found = compact(await rb("/json/stations/search?hidebroken=true&limit=40&order=clickcount&reverse=true&name=" + encodeURIComponent(q0)));
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