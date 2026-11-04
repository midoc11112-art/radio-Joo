import json, re

final = json.load(open("/tmp/final_stations.json"))
have_urls = {c["url"].lower().rstrip("/") for c in final}

# Medina FM family (verified OK) — manual entries with correct metadata
medina_ok = json.load(open("/tmp/r2_verify.json"))
medina_adds = [
    {"uuid":"manual-medina-fm","name":"ميدينا إف إم Medina FM المغرب","url":"https://medinafm.ice.infomaniak.ch/medinafm-64.mp3","tags":"music,morocco","cc":"MA","lang":"arabic","bitrate":64,"clickcount":0,"votes":0},
    {"uuid":"manual-medina-tarab","name":"ميدينا إف إم طرب Medina FM Tarab","url":"https://medinatarab.ice.infomaniak.ch/medinatarab-128.mp3","tags":"music,tarab,morocco","cc":"MA","lang":"arabic","bitrate":128,"clickcount":0,"votes":0},
    {"uuid":"manual-medina-nachat","name":"ميدينا إف إم ناشط Medina FM Nachat","url":"https://medinanachat.ice.infomaniak.ch/medinanachat-128.mp3","tags":"music,nasheed,morocco","cc":"MA","lang":"arabic","bitrate":128,"clickcount":0,"votes":0},
]
for a in medina_adds:
    if not medina_ok.get(a["url"], {}).get("ok"): continue
    if a["url"].lower().rstrip("/") in have_urls: continue
    final.append(a); have_urls.add(a["url"].lower().rstrip("/"))

def is_junk_name(n):
    n=(n or "").strip()
    if len(n)<3: return True
    bad=["rautemusik","click fm lebanon","islamina","✨","عظمها","aravic rao","s_o","x_x","ivieshal","corleone","thomas lawrence","beautiful recitation","radio caprice","cina-fm"]
    ln=n.lower()
    return any(b in ln for b in bad)
final=[c for c in final if not is_junk_name(c["name"])]

seen=set(); out=[]
for c in final:
    k=c["url"].lower().rstrip("/")
    if k in seen: continue
    seen.add(k); out.append(c)
final=out

FAMOUS={
 "jazeera":950,"الجزيرة":950,"mbc":900,"nogoum":900,"نجوم":900,"rotana":880,"روتانا":880,
 "monte carlo":870,"مونت كارلو":870,"al araby":860,"العربي":860,"nagham":850,"نغم":850,
 "mosaique":830,"موزاييك":830,"jawhara":820,"جوهرة":820,"medi 1":810,"ميدي 1":810,
 "9090":800,"mix fm":780,"radio hits":760,"sha3by":740,"شعبى":740,"tes3enat":720,"تسعينات":720,
 "on sports":700,"amr diab":690,"عمرو دياب":690,"diab fm":680,"om kalthoum":670,"أم كلثوم":670,
 "abdel halim":660,"عبدالحليم":660,"elissa":650,"إليسا":650,"fairuz":640,"فيروز":640,"nrj":630,
 "energy":620,"alifalif":610,"ألف ألف":610,"shems":600,"شمس":600,"hit radio":590,"quran":580,
 "قران":580,"قرآن":580,"mp3quran":570,"virgin":560,"star":550,"knooz":540,"radioscots":530,
 "el gouna":520,"sawt":510,"سوت":510,"aswat":505,"أصوات":505,"medina":500,"ميدينا":500,
 "nile":490,"نايل":490,"dubai":480,"دبي":480,"abu dhabi":470,"kuwait":460,"bahdja":440,
 "jil":430,"chouf":420,"orient":380,"masr":600,"مصر":600,"safa":410,"maidan":390,"ajyal":400,"صوت":395,
}
def famous_pop(c):
    n=(c["name"] or "").lower(); best=0
    for k,v in FAMOUS.items():
        if k in n: best=max(best,v)
    return best

def classify(name, tags, lang, cc):
    t=(tags or "").lower(); n=(name or "").lower()
    if any(k in t for k in ("quran","koran","religion","islamic")) or "quran" in n or "قران" in n or "قرآن" in n or "sunna" in n or "recitation" in n or "مرتل" in n or "تراتيل" in n:
        return "QURAN"
    if any(k in t for k in ("news","journal")) or "news" in n or "aljazeera" in n or "ajrad" in n or "الجزيرة" in n or "أخبار" in n or "اخبار" in n:
        return "NEWS"
    if "sport" in t or "sport" in n or "رياضة" in n:
        return "SPORTS"
    if any(k in t for k in ("talk","chat","culture","society")) and not any(k in t for k in ("music","pop","rock")):
        return "TALK"
    return "MUSIC"

final.sort(key=lambda c:(-(1 if str(c["uuid"]).startswith("manual-") else 0), -famous_pop(c), -(c.get("clickcount") or 0)))

def esc(s): return (s or "").replace("\\","\\\\").replace('"','\\"')

lines=["package com.example.radiojooo.data.local","","import com.example.radiojooo.data.model.StationEntity","","/**",
" * قائمة المحطات الموثوقة — فحص صارم بتاريخ 2026-11-05",
" * ==========================================================",
" * كل رابط تم تحميل ~400KB منه فعلياً والتأكد إنه بيانات صوت حقيقية (MP3/AAC/OGG frames أو HLS segments).",
" * أسماء الحرافيش والمحطات الأجنبية غير العربية اتشالت من القائمة.",
f" * عدد المحطات: {len(final)} — كلها شغالة وقت الفحص.",
" */",
"object TrustedStations {","","    val list: List<StationEntity> = listOf("]
for c in final:
    fp=famous_pop(c)
    pop=max(fp, min(999,(c.get("clickcount") or 0)//50+(c.get("votes") or 0)), 10)
    br=c.get("bitrate") or 0
    if br>320: br=128
    lines.append(f'        StationEntity("{esc(str(c["uuid"]))}", "{esc(c["name"])}", "{esc(c["url"])}", "", "{classify(c["name"],c["tags"],c.get("lang",""),c["cc"])}", "{(c["cc"] or "").upper()}", {br}, false, {pop}, 0L),')
lines+=["    )","}"]
open("app/src/main/java/com/example/radiojooo/data/local/TrustedStations.kt","w").write("\n".join(lines)+"\n")
json.dump(final, open("/tmp/final_stations.json","w"), ensure_ascii=False, indent=1)
from collections import Counter
print("FINAL COUNT:", len(final))
print("BY COUNTRY:", Counter((c["cc"] or "?") for c in final).most_common(20))
print("BY CAT:", Counter(classify(c["name"],c["tags"],c.get("lang",""),c["cc"]) for c in final))
