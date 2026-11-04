#!/usr/bin/env python3
import sys, json
sys.path.insert(0, "/workspace/tools")
from check_streams import check
urls = [u.strip() for u in open(sys.argv[1]) if u.strip()]
res = {}
for u in urls:
    r = check(u)
    res[u] = r
    print(("OK " if r["ok"] else "BAD"), r["bytes"], u, "::", r["detail"])
json.dump(res, open(sys.argv[2], "w"))
