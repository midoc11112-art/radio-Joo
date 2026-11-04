#!/usr/bin/env python3
"""Strict stream validator: downloads up to 400KB of each stream and checks
that the payload actually contains audio data (MP3/AAC/OGG sync frames or HLS segments)."""
import sys, time, urllib.request, ssl, re, json
from concurrent.futures import ThreadPoolExecutor

ssl_ctx = ssl.create_default_context()
ssl_ctx.check_hostname = False
ssl_ctx.verify_mode = ssl.CERT_NONE

HEADERS = {
    "User-Agent": "RadioJoo/21 (Android ExoPlayer-like)",
    "Accept": "audio/*,*/*;q=0.8",
    "Icy-MetaData": "1",
}

def is_audio_payload(data: bytes) -> bool:
    if len(data) < 2048:
        return False
    mp3_sync = 0
    i = 0
    while i < len(data) - 4:
        if data[i] == 0xFF and (data[i+1] & 0xE0) == 0xE0:
            mp3_sync += 1
            i += 4
        else:
            i += 1
    if mp3_sync >= 3:
        return True
    aac = data.count(b'\xff\xf1') + data.count(b'\xff\xf9')
    if aac >= 3:
        return True
    if b'OggS' in data[:4096]:
        return True
    if data[:4] == b'RIFF':
        return True
    if data[:3] == b'FLV':
        return True
    if len(data) > 188*10:
        ok = all(data[j] == 0x47 for j in range(0, min(len(data), 188*20), 188))
        if ok:
            return True
    return False

def check(url: str):
    result = {"url": url, "ok": False, "detail": "", "bytes": 0}
    try:
        req = urllib.request.Request(url, headers=HEADERS)
        with urllib.request.urlopen(req, timeout=15, context=ssl_ctx) as resp:
            ct = (resp.headers.get("Content-Type") or "").lower()
            code = resp.status
            if 'mpegurl' in ct or 'm3u' in ct or url.lower().split('?')[0].endswith('.m3u8'):
                text = resp.read(200000).decode('utf-8', 'replace')
                result["bytes"] = len(text)
                seg = None
                for line in text.splitlines():
                    line = line.strip()
                    if line and not line.startswith('#'):
                        seg = line
                        break
                if seg is None:
                    m = re.search(r'#EXT-X-STREAM-INF[^\n]*\n([^\n]+)', text)
                    if m:
                        seg = m.group(1).strip()
                if seg is None:
                    result["detail"] = f"HLS playlist but no segment found (HTTP {code})"
                    return result
                base = url.rsplit('/', 1)[0] + '/'
                if seg.startswith('http'):
                    seg_url = seg
                elif seg.startswith('/'):
                    from urllib.parse import urlparse
                    p = urlparse(url)
                    seg_url = f"{p.scheme}://{p.netloc}{seg}"
                else:
                    seg_url = base + seg
                req2 = urllib.request.Request(seg_url, headers=HEADERS)
                with urllib.request.urlopen(req2, timeout=15, context=ssl_ctx) as r2:
                    data = r2.read(400000)
                    result["bytes"] = len(data)
                    ctype2 = (r2.headers.get('Content-Type') or '').lower()
                    if len(data) > 1024 and ('video/mp2t' in ctype2 or 'audio' in ctype2 or is_audio_payload(data) or len(data) > 10000):
                        result["ok"] = True
                        result["detail"] = f"HLS OK (segment {len(data)}B)"
                    else:
                        result["detail"] = f"HLS segment too small/bad ({len(data)}B)"
                return result
            deadline = time.time() + 12
            chunks = []
            got = 0
            while got < 400000 and time.time() < deadline:
                try:
                    c = resp.read(65536)
                except Exception:
                    break
                if not c:
                    break
                chunks.append(c)
                got += len(c)
            data = b"".join(chunks)
            result["bytes"] = len(data)
            if code != 200:
                result["detail"] = f"HTTP {code}"
                return result
            if is_audio_payload(data):
                result["ok"] = True
                result["detail"] = f"audio payload OK ({len(data)}B, ct={ct or '?'})"
            elif got < 1024:
                result["detail"] = f"too little data ({got}B, ct={ct or '?'})"
            else:
                snippet = data[:200].decode('utf-8', 'replace')
                result["detail"] = f"no audio frames ({got}B, ct={ct or '?'}) :: {snippet[:80]!r}"
        return result
    except Exception as e:
        result["detail"] = f"{type(e).__name__}: {str(e)[:120]}"
        return result

def main():
    urls = [u.strip() for u in open(sys.argv[1]) if u.strip()]
    out = {}
    with ThreadPoolExecutor(max_workers=16) as ex:
        futs = {ex.submit(check, u): u for u in urls}
        for f, u in futs.items():
            out[u] = f.result()
    ok = sum(1 for v in out.values() if v["ok"])
    print(f"TOTAL={len(out)} OK={ok} FAIL={len(out)-ok}", file=sys.stderr)
    with open(sys.argv[2], "w") as fh:
        json.dump(out, fh, indent=1)
    for u, v in sorted(out.items(), key=lambda x: (not x[1]["ok"], x[0])):
        mark = "OK " if v["ok"] else "BAD"
        print(f"[{mark}] {v['bytes']:>7}B {u}  :: {v['detail']}")

if __name__ == "__main__":
    main()
