import re

# 1) Parse trusted stations from Kotlin source
src = open('app/src/main/java/com/example/radiojooo/data/local/TrustedStations.kt').read()
pat = r'StationEntity\(\s*"([^"]*)",\s*"([^"]*)",\s*"([^"]*)",\s*"[^"]*",\s*"([^"]*)",\s*"([^"]*)",\s*(\d+),\s*(true|false),\s*(\d+)'
rows = re.findall(pat, src)
assert len(rows) == 185, "expected 185 rows, got %d" % len(rows)

def esc(s):
    return s.replace('\\', '\\\\').replace('"', '\\"')

lines = []
for (uuid, name, url, cat, cc, bit, fav, pop) in rows:
    known = uuid.startswith("manual-")
    lines.append('    { uuid: "%s", name: "%s", primaryUrl: "%s", fallbackUrl: "", category: "%s", countryCode: "%s", bitrate: %s, popularity: %s, tags: "", language: "arabic", known: %s },'
        % (esc(uuid), esc(name), esc(url), cat or "MUSIC", cc, bit, pop, "true" if known else "false"))

block = ("// محطات معروفة URLsها ثابتة ومتأكد منها مباشرة (مولدة من TrustedStations.kt - فحص 2026-11-04)\n"
         "function getKnownWorkingStations() {\n  return [\n" + "\n".join(lines) + "\n  ];\n}")

# 2) Extract JS body from multipart response of current worker
worker = open('worker_current.js').read()
start_idx = worker.index('export default')
end_marker = '--ad845e87cdd988a6aa05c99dc92271a92d4ba87a4ca3c2bb24ee51879dd3--'
end_idx = worker.rindex(end_marker)
js = worker[start_idx:end_idx].rstrip().rstrip('-').rstrip()
assert js.startswith('export default')

# 3) Replace getKnownWorkingStations function wholesale
i = js.index('function getKnownWorkingStations')
line_start = js.rfind('\n', 0, i) + 1
prev_line_start = js.rfind('\n', 0, line_start - 1) + 1
if js[prev_line_start:line_start].strip().startswith('//'):
    line_start = prev_line_start
brace_start = js.index('{', i)
depth = 0; p = brace_start
while True:
    c = js[p]
    if c == '{': depth += 1
    elif c == '}':
        depth -= 1
        if depth == 0: break
    p += 1
func_end = p + 1
if js[func_end:func_end+1] == ';': func_end += 1
new_js = js[:line_start] + block + js[func_end:]

# 4) Fix removeDuplicatesSmart: known list must never be dropped by URL/name collision.
di = new_js.index('function removeDuplicatesSmart')

# 4a) declare knownSet after seenNames
decl = 'const seenNames = new Set();'
sn = new_js.index(decl, di) + len(decl)
new_js = new_js[:sn] + '\n  const knownSet = new Set();' + new_js[sn:]

# 4b) register known url/name keys before the dedup check in the known loop
cond = 'if (!seenUrls.has(urlKey) && !seenNames.has(nameKey)) {'
ci = new_js.index(cond, di)
new_js = new_js[:ci] + 'knownSet.add(urlKey);\n      knownSet.add(nameKey);\n      ' + new_js[ci:]

# 4c) guard the second (non-known) loop: skip duplicates of any known station
old_guard_target = 'if (!s.known) {'
gi = new_js.index(old_guard_target, ci) + len(old_guard_target)
guard_code = ("\n      const uk = (s.uuid || \"\").trim();"
              "\n      const uKey2 = (s.primaryUrl || \"\").toLowerCase().replace(/\\/$/, \"\");"
              "\n      const nKey2 = (s.name || \"\").toLowerCase().trim();"
              "\n      if (uk.startsWith(\"manual-\") || knownSet.has(uKey2) || knownSet.has(nKey2)) continue;")
new_js = new_js[:gi] + guard_code + new_js[gi:]

open('worker.js', 'w').write(new_js)
print("OK bytes:", len(new_js))
