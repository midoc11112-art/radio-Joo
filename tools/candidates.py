#!/usr/bin/env python3
"""Query Radio Browser API for candidate streams (Egypt + news + talk + popular Arab),
dedupe, and print a URL list for strict validation."""
import json, sys, urllib.request, urllib.parse

BASES = [
    "https://de1.api.radio-browser.info",
    "https://fi1.api.radio-browser.info",
    "https://all.api.radio-browser.info",
]

def api(path):
    last_err = None
    for b in BASES:
        try:
            req = urllib.request.Request(b + path, headers={"User-Agent": "RadioJoo-tools/1.0"})
            with urllib.request.urlopen(req, timeout=25) as r:
                return json.loads(r.read().decode())
        except Exception as e:
            last_err = e
    print(f"API FAIL {path}: {last_err}", file=sys.stderr)
    return []

queries = [
    # Egypt music/talk famous stations by name (english)
    "/json/stations/search?name=Nogoum&hidebroken=true&limit=8&order=clickcount&reverse=true",
    "/json/stations/search?name=Nagham&hidebroken=true&limit=8&order=clickcount&reverse=true",
    "/json/stations/search?name=9090&hidebroken=true&limit=8&order=clickcount&reverse=true",
    "/json/stations/search?name=Mixis&hidebroken=true&limit=8&order=clickcount&reverse=true",
    "/json/stations/search?name=Megahit&hidebroken=true&limit=8&order=clickcount&reverse=true",
    "/json/stations/search?name=Radio%20Hits&hidebroken=true&limit=8&order=clickcount&reverse=true",
    "/json/stations/search?name=Vozapop&hidebroken=true&limit=5&order=clickcount&reverse=true",
    "/json/stations/search?name=Ina&hidebroken=true&limit=8&order=clickcount&reverse=true&language=arabic",
    "/json/stations/search?tag=egypt&hidebroken=true&limit=60&order=clickcount&reverse=true",
    "/json/stations/search?countrycode=EG&hidebroken=true&limit=80&order=clickcount&reverse=true",
    "/json/stations/search?tag=arabic&hidebroken=true&limit=40&order=votes&reverse=true",
    "/json/stations/search?tag=talk&hidebroken=true&limit=30&order=clickcount&reverse=true",
    "/json/stations/search?tag=news&hidebroken=true&limit=40&order=clickcount&reverse=true",
    "/json/stations/search?name=Al%20Adalah&hidebroken=true&limit=5&order=clickcount&reverse=true",
    "/json/stations/search?name=Maidan&hidebroken=true&limit=5&order=clickcount&reverse=true",
    "/json/stations/search?name=Sawt&hidebroken=true&limit=10&order=clickcount&reverse=true&language=arabic",
    "/json/stations/search?name=Quran&hidebroken=true&limit=15&order=clickcount&reverse=true",
    "/json/stations/search?name=Ghalia&hidebroken=true&limit=5&order=clickcount&reverse=true",
    "/json/stations/search?name=Umm%20Kulthum&hidebroken=true&limit=6&order=clickcount&reverse=true",
    "/json/stations/search?name=Abdel%20Halim&hidebroken=true&limit=6&order=clickcount&reverse=true",
    "/json/stations/search?name=Fairuz&hidebroken=true&limit=6&order=clickcount&reverse=true",
    "/json/stations/search?name=Wadi&hidebroken=true&limit=5&order=clickcount&reverse=true&language=arabic",
    "/json/stations/search?name=Sawt&hidebroken=true&limit=5&order=clickcount&reverse=true&country=Jordan",
    "/json/stations/search?name=Amman&hidebroken=true&limit=5&order=clickcount&reverse=true",
    "/json/stations/search?name=Ninar&hidebroken=true&limit=5&order=clickcount&reverse=true",
    "/json/stations/search?name=Safa&hidebroken=true&limit=6&order=clickcount&reverse=true",
    "/json/stations/search?name=energy&hidebroken=true&limit=15&order=clickcount&reverse=true",
    "/json/stations/search?name=MBC&hidebroken=true&limit=15&order=clickcount&reverse=true",
    "/json/stations/search?name=Radioscots&hidebroken=true&limit=5&order=clickcount&reverse=true",
    "/json/stations/search?name=Shababiya&hidebroken=true&limit=5&order=clickcount&reverse=true",
    "/json/stations/search?name=Eta&hidebroken=true&limit=8&order=clickcount&reverse=true&language=arabic",
    "/json/stations/bycountrycodeexact/EG?hidebroken=true",
]

# manual candidates (official/known stream endpoints to validate)
manual = [
    {"stationuuid": "manual-voice-of-jazeera-alt", "name": "صوت الجزيرة Al Jazeera Voice", "url_resolved": "https://live-hls-web-aja.getaj.net/VOICE-AJA/index.m3u8", "tags": "news,quran,talk", "countrycode": "QA", "language": "arabic", "bitrate": 128},
    {"stationuuid": "manual-sky-news-arabia-alt", "name": "سكاي نيوز عربية راديو Sky News Arabia", "url_resolved": "https://stream.skynewsarabia.com/sna/audio_128.m3u8", "tags": "news", "countrycode": "AE", "language": "arabic", "bitrate": 128},
    {"stationuuid": "manual-montecarlo-alt", "name": "مونت كارلو الدولية (بديل)", "url_resolved": "https://montecarlodoualiya128k.ice.infomaniak.ch/mc-doualiya-128.mp3", "tags": "news,talk", "countrycode": "FR", "language": "arabic", "bitrate": 128},
    {"stationuuid": "manual-quran-makkah-sc", "name": "إذاعة القرآن الكريم من مكة المكرمة", "url_resolved": "https://qurango.net/radio/quran_makkah", "tags": "quran", "countrycode": "SA", "language": "arabic", "bitrate": 128},
    {"stationuuid": "manual-quran-madinah-sc", "name": "إذاعة القرآن الكريم من المدينة المنورة", "url_resolved": "https://qurango.net/radio/quran_hafs_madinah", "tags": "quran", "countrycode": "SA", "language": "arabic", "bitrate": 128},
    {"stationuuid": "manual-haram-ctv", "name": "إذاعة المسجد الحرام", "url_resolved": "https://2683.stream.zeno.fm/djzqydmkm98uv", "tags": "quran,religion", "countrycode": "SA", "language": "arabic", "bitrate": 128},
    {"stationuuid": "manual-elta3anya", "name": "الراديو الثالث - إذاعة الشارع", "url_resolved": "https://stream.zeno.fm/1s7x0pekm98uv", "tags": "talk,egypt", "countrycode": "EG", "language": "arabic", "bitrate": 128},
    {"stationuuid": "manual-nilefm", "name": "نايل إف إم Nile FM", "url_resolved": "https://nilefm.live2.gracepoint.digital/nilefm", "tags": "music,egypt", "countrycode": "EG", "language": "english", "bitrate": 128},
    {"stationuuid": "manual-radio-masr-egp", "name": "راديو مصر", "url_resolved": "https://rtsegtts.snrtstream.com/rsi/index.m3u8", "tags": "general,egypt", "countrycode": "EG", "language": "arabic", "bitrate": 128},
]

seen_urls = set()
candidates = []
for q in queries:
    rows = api(q)
    for s in rows:
        u = (s.get("url_resolved") or s.get("url") or "").strip()
        if not u or u in seen_urls:
            continue
        tags = (s.get("tags") or "").lower()
        lang = (s.get("language") or "").lower()
        cc = (s.get("countrycode") or "").upper()
        name = s.get("name") or ""
        arabic_ok = ("arabic" in lang or any('؀' <= ch <= 'ۿ' for ch in name)
                     or cc in ("EG","SA","AE","QA","KW","BH","OM","YE","JO","LB","SY","IQ",
                               "MA","DZ","TN","LY","SD","PS","MR","EH"))
        if not arabic_ok:
            continue
        seen_urls.add(u)
        candidates.append({
            "uuid": s.get("stationuuid"), "name": name.strip(), "url": u,
            "tags": tags[:120], "cc": cc, "lang": lang,
            "bitrate": s.get("bitrate") or 0, "clickcount": s.get("clickcount") or 0,
            "votes": s.get("votes") or 0, "codec": s.get("codec") or "",
        })

for m in manual:
    u = m["url_resolved"]
    if u in seen_urls:
        continue
    seen_urls.add(u)
    candidates.append({
        "uuid": m["stationuuid"], "name": m["name"], "url": u,
        "tags": m["tags"], "cc": m["countrycode"], "lang": m["language"],
        "bitrate": m["bitrate"], "clickcount": 0, "votes": 0, "codec": "",
    })

print(f"CANDIDATES={len(candidates)}", file=sys.stderr)
json.dump(candidates, open(sys.argv[1], "w"), ensure_ascii=False, indent=1)
with open(sys.argv[2], "w") as f:
    for c in candidates:
        f.write(c["url"] + "\n")
