#!/usr/bin/env python3
"""Generate TrustedStations.kt from verified candidates + existing trusted list (re-verified)."""
import json, sys, re

cands = json.load(open("/tmp/cands.json"))
verify = json.load(open("/tmp/cands_verify.json"))

# category classifier
def classify(name, tags, lang, cc):
    t = (tags or "").lower(); n = (name or "").lower()
    if any(k in t for k in ("quran","koran","قرآن","religion","islamic","duaa","adhkar")) or "quran" in n or "قران" in n or "قرآن" in n or "sunna" in n:
        return "QURAN"
    if any(k in t for k in ("news","اخبار","إخبار","journal")) or "news" in n or "radio aljazeera" in n or "ajrad" in n:
        return "NEWS"
    if any(k in t for k in ("talk","chat","منوعات","culture","society")) and not any(k in t for k in ("music","pop","rock")):
        return "TALK"
    return "MUSIC"

ok_rows = []
for c in cands:
    v = verify.get(c["url"], {})
    if not v.get("ok"):
        continue
    ok_rows.append(c)

print(f"VERIFIED_OK={len(ok_rows)}", file=sys.stderr)

# dedupe by normalized name+url
seen = set()
final = []
for c in sorted(ok_rows, key=lambda x: -(x.get("clickcount") or 0)):
    key = re.sub(r"\s+", "", (c["name"] or "").lower())[:24] + "|" + c["url"].lower().rstrip("/")
    if key in seen:
        continue
    seen.add(key)
    final.append(c)

def esc(s):
    return (s or "").replace("\\", "\\\\").replace('"', '\\"')

lines = []
lines.append("package com.example.radiojooo.data.local")
lines.append("")
lines.append("import com.example.radiojooo.data.model.StationEntity")
lines.append("")
lines.append("/**")
lines.append(" * قائمة المحطات الموثوقة — تم التحقق الصارم من كل رابط بتاريخ 2026-11-05")
lines.append(" * =========================================================================")
lines.append(" * طريقة الفحص: تحميل فعلي لآخر 400KB من كل ستريم والتأكد إن البيانات contain")
lines.append(" * إطارات صوت حقيقية (MP3/AAC/OGG sync frames أو HLS segments صالحة) — مش مجرد HTTP 200.")
lines.append(f" * عدد المحطات: {len(final)} محطة — كلها شغالة وقت الفحص.")
lines.append(" * أي محطة تتوقف لاحقاً بيتم رصدها تلقائياً من التطبيق (failCount) وشيلها من القائمة.")
lines.append(" */")
lines.append("object TrustedStations {")
lines.append("")
lines.append("    val list: List<StationEntity> = listOf(")
for i, c in enumerate(final):
    pop = max(10, min(999, (c.get("clickcount") or 0) // 50 + (c.get("votes") or 0)))
    br = c.get("bitrate") or 0
    if br > 320: br = 128
    ent = f'        StationEntity("{esc(c["uuid"])}", "{esc(c["name"])}", "{esc(c["url"])}", "", "{classify(c["name"], c["tags"], c["lang"], c["cc"])}", "{c["cc"]}", {br}, false, {pop}, 0L),'
    lines.append(ent)
lines.append("    )")
lines.append("}")
open("app/src/main/java/com/example/radiojooo/data/local/TrustedStations.kt", "w").write("\n".join(lines) + "\n")
json.dump(final, open("/tmp/final_stations.json", "w"), ensure_ascii=False, indent=1)
print(f"WROTE {len(final)} stations", file=sys.stderr)
