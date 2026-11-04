#!/usr/bin/env python3
"""Regenerate TrustedStations.kt with quality filter: drop junk names, prefer famous."""
import json, sys, re

cands = json.load(open("/tmp/cands2.json"))
verify = json.load(open("/tmp/verify2.json"))

JUNK_PAT = re.compile(r'^[._\-x\s]*$|[a-z]_[a-z]|[Xx]{2,}|^(ivi|s_o|corleone|thomas lawrence|beautiful recitation)', re.I)
ARABIC_RANGES = re.compile(r'[\u0600-\u06FF]')

def is_junk(c):
    n = (c["name"] or "").strip()
    if len(n) < 2: return True
    if JUNK_PAT.search(n): return True
    # drop non-arabic-country stations whose name has no arabic and lang not arabic
    cc = c.get("cc","")
    arab_countries = {"EG","SA","AE","QA","KW","BH","OM","YE","JO","LB","SY","IQ","MA","DZ","TN","LY","SD","PS","MR","EH"}
    if cc in arab_countries:
        return False
    if "arabic" in (c.get("lang") or "") or ARABIC_RANGES.search(n):
        return False
    return True  # foreign station, foreign name -> drop from seed list

def classify(name, tags, lang, cc):
    t = (tags or "").lower(); n = (name or "").lower()
    if any(k in t for k in ("quran","koran","religion","islamic","duaa","adhkar")) or "quran" in n or "قران" in n or "قرآن" in n or "sunna" in n or "recitation" in n or "مرتل" in n:
        return "QURAN"
    if any(k in t for k in ("news","journal","اخبار")) or "news" in n or "aljazeera" in n or "ajrad" in n or "الجزيرة" in n or "إذاعة" in n and "خبر" in n:
        return "NEWS"
    if any(k in t for k in ("sport","رياضة")) or "sport" in n:
        return "SPORTS"
    if any(k in t for k in ("talk","chat","culture","society","منوعات")) and not any(k in t for k in ("music","pop","rock")):
        return "TALK"
    return "MUSIC"

# famous boost map (name substring -> priority pop floor)
FAMOUS = {
    "nogoum": 900, "nagham": 850, "9090": 800, "mix fm": 780, "radio hits": 760,
    "sha3by": 740, "tes3enat": 720, "on sports": 700, "amr diab": 690, "diab fm": 680,
    "om kalthoum": 670, "أم كلثوم": 670, "abdel halim": 660, "عبدالحليم": 660,
    "elissa": 650, "fairuz": 640, "فيروز": 640, "nrj": 630, "energy": 620,
    "mbc": 900, "rotana": 880, "alifalif": 610, "monte carlo": 870, "مونت كارلو": 870,
    "jazeera": 950, "الجزيرة": 950, "sky news": 940, "سكاي نيوز": 940,
    "mosaique": 830, "jawhara": 820, "medi 1": 810, "shems": 600, "hit radio": 590,
    "quran": 580, "قران": 580, "قرآن": 580, "mp3quran": 570, "qurango": 570,
    "virgin": 560, "star": 550, "knooz": 540, "radioscots": 530, "el gouna": 520,
    "sawt": 510, "aswat": 505, "sumay": 500, "nile": 490, "dubai": 480, "abu dhabi": 470,
    "kuwait": 460, "qatar radio": 450, "bahdja": 440, "jil fm": 430, "chouf": 420,
    "safa": 410, "ninar": 400, "maidan": 390, "orient": 380, "al araby": 860, "العربي": 860,
}
def famous_pop(c):
    n = (c["name"] or "").lower()
    best = 0
    for k, v in FAMOUS.items():
        if k in n:
            best = max(best, v)
    return best

ok_rows = [c for c in cands if verify.get(c["url"], {}).get("ok")]
print(f"VERIFIED_OK={len(ok_rows)}", file=sys.stderr)

seen_url, seen_name = set(), set()
final = []
for c in ok_rows:
    if is_junk(c):
        continue
    ukey = c["url"].lower().rstrip("/")
    nkey = re.sub(r"\s+", "", (c["name"] or "").lower())[:20]
    if ukey in seen_url or nkey in seen_name:
        continue
    seen_url.add(ukey); seen_name.add(nkey)
    final.append(c)

# sort: manual first by popularity desc, then api by clickcount
def sort_key(c):
    manual = 1 if str(c["uuid"]).startswith("manual-") else 0
    fp = famous_pop(c)
    return (-manual, -fp, -(c.get("clickcount") or 0))
final.sort(key=sort_key)

def esc(s):
    return (s or "").replace("\\", "\\\\").replace('"', '\\"')

lines = ["package com.example.radiojooo.data.local","","import com.example.radiojooo.data.model.StationEntity","","/**",
 " * قائمة المحطات الموثوقة — فحص صارم بتاريخ 2026-11-05",
 " * ==========================================================",
 " * كل رابط تم تحميل آخر ~400KB منه والتأكد إنه بيانات صوت حقيقية (MP3/AAC/OGG frames أو HLS segments),",
 " * مش مجرد HTTP 200. أسماء الحرافيش/غير العربية اتشالت من القائمة.",
 f" * عدد المحطات: {len(final)} — كلها شغالة وقت الفحص.",
 " */",
 "object TrustedStations {","", "    val list: List<StationEntity> = listOf("]
for c in final:
    fp = famous_pop(c)
    pop = max(fp, min(999, (c.get("clickcount") or 0)//50 + (c.get("votes") or 0)))
    pop = max(pop, 10)
    br = c.get("bitrate") or 0
    if br > 320: br = 128
    lines.append(f'        StationEntity("{esc(c["uuid"])}", "{esc(c["name"])}", "{esc(c["url"])}", "", "{classify(c["name"], c["tags"], c["lang"], c["cc"])}", "{c["cc"]}", {br}, false, {pop}, 0L),')
lines += ["    )","}"]
open("app/src/main/java/com/example/radiojooo/data/local/TrustedStations.kt","w").write("\n".join(lines)+"\n")
json.dump(final, open("/tmp/final_stations.json","w"), ensure_ascii=False, indent=1)
print(f"WROTE {len(final)}", file=sys.stderr)
