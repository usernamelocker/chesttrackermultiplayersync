"""Live smoke test against a REAL deployed server (stdlib only, no deps).

Usage:
    python smoke.py http://VPS-IP:8000 YOURTOKEN

Runs: / -> /health -> handshake (both fabriccraft ids) -> push 1 test
container -> pull sees it -> view totals -> delete test container -> done.
Leaves no junk behind (the test container is deleted at the end).
"""
import json
import sys
import urllib.request

BASE = sys.argv[1].rstrip("/") if len(sys.argv) > 1 else "http://127.0.0.1:8000"
TOKEN = sys.argv[2] if len(sys.argv) > 2 else ""
UUID = "00000000-0000-0000-0000-000000000000"
CANON = "multiplayer/fabriccraft_net"
ALIAS = "multiplayer/vip_fabriccraft_net"
FAILS = []


def call(method, path, body=None, params=""):
    req = urllib.request.Request(BASE + path + params, method=method)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    if TOKEN:
        req.add_header("X-CMSync-Token", TOKEN)
    try:
        with urllib.request.urlopen(req, data=data, timeout=15) as r:
            return r.status, json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode() or "{}")
        except Exception:
            return e.code, {}


def check(name, cond, detail=""):
    print(("PASS " if cond else "FAIL ") + name, detail)
    if not cond:
        FAILS.append(name)


def ident(sid):
    return {"protocolVersion": 2, "playerUuid": UUID, "playerName": "smoke",
            "serverId": sid, "serverName": "smoke", "mcVersion": "1.21.11",
            "modVersion": "smoke.1"}


s, b = call("GET", "/")
check("root", s == 200 and b.get("service") == "cmsync", b)

s, b = call("GET", "/health")
check("health", s == 200 and b.get("ok") is True, b)

for sid in (CANON, ALIAS):
    s, b = call("POST", "/api/handshake", ident(sid))
    check(f"handshake {sid}", s == 200 and b.get("status") == "SYNCED", (s, b))

item = [{"id": "minecraft:dirt", "count": 1}]
chg = {"key": "minecraft:overworld", "pos": "123,64,456", "deleted": False,
       "updatedAt": "2026-09-16T00:00:00Z", "updatedBy": UUID,
       "mcVersion": "1.21.11", "items": item}
s, b = call("POST", "/api/push", dict(ident(ALIAS), fullHash="smoke", changes=[chg]))
check("push via alias", s == 200 and b.get("status") == "SYNCED", (s, b))

s, b = call("GET", "/api/pull", params=f"?serverId={CANON}&playerUuid={UUID}")
check("pull canonical sees alias push",
      s == 200 and any(x.get("pos") == "123,64,456" for x in b.get("changes", [])), (s, b))

s, b = call("GET", f"/api/view/{CANON}")
check("view totals", s == 200 and b.get("totals", {}).get("minecraft:dirt", 0) >= 1, (s, b))

bye = dict(chg, deleted=True, items=[], updatedAt="2026-09-16T00:01:00Z")
s, b = call("POST", "/api/push", dict(ident(CANON), fullHash="smoke2", changes=[bye]))
check("cleanup delete", s == 200 and b.get("status") == "SYNCED", (s, b))

print("SMOKE " + ("PASSED" if not FAILS else f"FAILED: {FAILS}"))
sys.exit(1 if FAILS else 0)
