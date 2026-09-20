"""End-to-end API test via FastAPI TestClient (no server process needed).

Run:  python test_api.py
Covers: health, handshake allow/deny, delta push, pull, view totals,
empty-push ignore (hub-wipe), mass-delete quarantine, snapshot restore.
"""
import os
import sys
import tempfile

_tmp = tempfile.mkdtemp(prefix="cmsync-api-test-")
os.environ["EXPECTED_SERVER_ID"] = "multiplayer/mc_test"
os.environ["WHITELIST_UUIDS"] = ("11111111-1111-1111-1111-111111111111,"
                                 "22222222-2222-2222-2222-222222222222")
os.environ["SHARED_TOKEN"] = ""
os.environ["ADMIN_TOKEN"] = ""
os.environ["DB_PATH"] = os.path.join(_tmp, "t.db")

sys.path.insert(0, os.path.dirname(__file__))
from fastapi.testclient import TestClient  # noqa: E402
import app  # noqa: E402

c = TestClient(app.app)
ALICE = "11111111-1111-1111-1111-111111111111"
BOB = "22222222-2222-2222-2222-222222222222"
SID = "multiplayer/mc_test"


def ident(uuid, mc="1.21.11", sid=SID):
    return {"protocolVersion": 2, "playerUuid": uuid, "playerName": "T",
            "serverId": sid, "serverName": "T", "mcVersion": mc, "modVersion": "cmsync.1"}


def change(key, pos, hour, items, uuid=ALICE, mc="1.21.11", deleted=False):
    return {"key": key, "pos": pos, "deleted": deleted,
            "updatedAt": f"2026-09-15T{hour:02d}:00:00Z", "updatedBy": uuid,
            "mcVersion": mc, "items": items}


r = c.get("/health")
assert r.status_code == 200 and r.json()["ok"], r.text
print("health ok")

r = c.get("/")
assert r.status_code == 200 and r.json()["service"] == "cmsync", r.text
assert c.get("/favicon.ico").status_code == 204
print("index ok")

assert c.post("/api/handshake", json=ident(ALICE)).json()["status"] == "SYNCED"
assert c.post("/api/handshake", json=ident("99999999-9999-9999-9999-999999999999")).json()["status"] == "ACCESS_DENIED"
assert c.post("/api/handshake", json=ident(ALICE, sid="multiplayer/other")).json()["status"] == "ACCESS_DENIED"
print("handshake ok")

r = c.post("/api/push", json={"protocolVersion": 2})
assert r.status_code == 422, (r.status_code, r.text)
assert isinstance(r.json().get("detail"), list), r.text
print("422 shape ok (validation detail preserved for logs)")

iron = [{"id": "minecraft:iron_ingot", "count": 5}]
diamond = [{"id": "minecraft:diamond", "count": 2}]
body = dict(ident(ALICE), fullHash="h1", changes=[
    change("minecraft:overworld", "1,2,3", 10, iron),
    dict(change("minecraft:overworld", "4,5,6", 10, diamond, mc="26.2"),
         raw={"v": 2, "mc": "26.2",
              "memory": {"items": [{"id": "minecraft:diamond", "count": 2, "nbt": "ENCH:data"}]},
              "override": {"customName": "Vault", "manualMode": "REMEMBER"}}),
])
r = c.post("/api/push", json=body).json()
assert r["status"] == "SYNCED" and r["applied"] == 2 and r["revision"] == 2, r
print("push ok")

r = c.get("/api/pull", params={"serverId": SID, "playerUuid": BOB}).json()
assert r["status"] == "SYNCED" and len(r["changes"]) == 2, r
vault = [x for x in r["changes"] if x["pos"] == "4,5,6"][0]
assert vault["raw"]["override"]["customName"] == "Vault", vault
assert vault["raw"]["memory"]["items"][0]["id"] == "minecraft:diamond", vault
print("pull ok (raw blob round-trips untouched)")

r = c.get("/api/pull", params={"serverId": SID, "playerUuid": BOB, "since": 1}).json()
assert r["revision"] == 2 and len(r["changes"]) == 1 and r["changes"][0]["pos"] == "4,5,6", r
print("incremental pull ok")

r = c.get(f"/api/view/{SID}").json()
assert r["totals"] == {"minecraft:iron_ingot": 5, "minecraft:diamond": 2}, r
assert r["containers"] == 2, r
print("view ok")

r = c.post("/api/push", json=dict(ident(ALICE), fullHash="empty", changes=[])).json()
assert r["status"] == "SYNCED" and r.get("note", "").startswith("ignored empty"), r
assert c.get(f"/api/view/{SID}").json()["containers"] == 2
print("empty-push guard ok")

bulk = [change("minecraft:overworld", f"{i},64,0", 11,
              [{"id": "minecraft:stone", "count": 1}]) for i in range(60)]
r = c.post("/api/push", json=dict(ident(ALICE), fullHash="h2", changes=bulk)).json()
assert r["status"] == "SYNCED", r
assert c.get(f"/api/view/{SID}").json()["containers"] == 62
wiped = [change("minecraft:overworld", f"{i},64,0", 12, [], deleted=True) for i in range(55)]
r = c.post("/api/push", json=dict(ident(ALICE), fullHash="h3", changes=wiped)).json()
assert r["status"] == "SYNCED" and r["applied"] == 55, r  # mass deletes apply (snapshot+warn server-side)
assert c.get(f"/api/view/{SID}").json()["containers"] == 7, "62 - 55 deletes"
print("mass delete ok")

snaps = c.get("/api/snapshots", params={"serverId": SID}).json()["snapshots"]
assert len(snaps) >= 2, snaps
first_id = snaps[-1]["id"]  # oldest = the 2-container snapshot
import config  # noqa: E402
config.ADMIN_TOKEN = "restore-secret"
r = c.post("/api/restore", json={"serverId": SID, "snapshotId": first_id},
           headers={"X-CMSync-Token": "restore-secret"}).json()
assert r["status"] == "SYNCED" and r["restored"] == 2 and r["generation"] == 1, r
assert c.get(f"/api/view/{SID}").json()["containers"] == 2
print("restore ok")

# --- token mode: whitelist empty, password required instead of UUIDs ---
config.WHITELIST_UUIDS = set()
config.SHARED_TOKEN = "test-secret"
config.ADMIN_TOKEN = "test-secret"
TOK = {"X-CMSync-Token": "test-secret"}
STRANGER = "99999999-9999-9999-9999-999999999999"

r = c.post("/api/handshake", json=ident(STRANGER))
assert r.status_code == 401, (r.status_code, r.text)
r = c.post("/api/handshake", json=ident(STRANGER), headers={"X-CMSync-Token": "wrong"})
assert r.status_code == 401, (r.status_code, r.text)
r = c.post("/api/handshake", json=ident(STRANGER), headers=TOK).json()
assert r["status"] == "SYNCED", r  # unknown UUID allowed: whitelist skipped
r = c.post("/api/push", json=dict(ident(STRANGER), fullHash="t1", changes=[
    change("minecraft:overworld", "9,9,9", 13, iron, uuid=STRANGER)]), headers=TOK).json()
assert r["status"] == "SYNCED" and r["applied"] == 1, r
r = c.get("/api/pull", params={"serverId": SID, "playerUuid": STRANGER}, headers=TOK).json()
assert r["status"] == "SYNCED" and any(x["pos"] == "9,9,9" for x in r["changes"]), r
print("token mode ok")

# --- alias mode: second proxy address merges into the canonical bank ---
CANON = "multiplayer/fabriccraft_net"
ALIAS = "multiplayer/vip_fabriccraft_net"
config.EXPECTED_SERVER_ID = CANON
config.SERVER_ID_ALIASES = {ALIAS}


def fident(uuid, sid):
    d = ident(uuid)
    d["serverId"] = sid
    return d


r = c.post("/api/handshake", json=fident(STRANGER, ALIAS), headers=TOK).json()
assert r["status"] == "SYNCED", r  # alias accepted, not "wrong serverId"
r = c.post("/api/handshake", json=fident(STRANGER, "multiplayer/stranger_server"), headers=TOK).json()
assert r["status"] == "ACCESS_DENIED", r  # unknown ids still denied
r = c.post("/api/push", json=dict(fident(STRANGER, ALIAS), fullHash="a1", changes=[
    change("minecraft:overworld", "7,7,7", 14, diamond, uuid=STRANGER)]), headers=TOK).json()
assert r["status"] == "SYNCED" and r["applied"] == 1, r
r = c.get(f"/api/view/{CANON}").json()
assert r["totals"].get("minecraft:diamond", 0) >= 2, r  # stored under canonical id
r = c.get("/api/pull", params={"serverId": ALIAS, "playerUuid": STRANGER}, headers=TOK).json()
assert r["status"] == "SYNCED" and any(x["pos"] == "7,7,7" for x in r["changes"]), r
print("alias mode ok")

# --- range gate: near kept, far + other-dim withheld, ender exempt, legacy full ---
EUUID = "33333333-3333-3333-3333-333333333333"


def rchange(key, pos, hour):
    return {"key": key, "pos": pos, "deleted": False, "updatedAt": f"2026-09-15T{hour:02d}:00:00Z",
            "updatedBy": EUUID, "mcVersion": "1.21.11", "items": [{"id": "minecraft:stone", "count": 1}]}


r = c.post("/api/push", json=dict(fident(STRANGER, CANON), fullHash="r1", changes=[
    rchange("minecraft:overworld", "100,64,100", 15),
    rchange("minecraft:overworld", "9000,64,9000", 15),
    rchange("minecraft:the_nether", "100,64,100", 15),
    rchange(f"chesttracker:ender_chest/{EUUID}", "0,0,0", 15),
]), headers=TOK).json()
assert r["status"] == "SYNCED" and r["applied"] == 4, r
r = c.get("/api/pull", params={"serverId": CANON, "playerUuid": STRANGER,
                               "px": 0, "py": 64, "pz": 0, "dim": "minecraft:overworld"}, headers=TOK).json()
assert r["status"] == "SYNCED", r
got = {(x["key"], x["pos"]) for x in r["changes"]}
assert ("minecraft:overworld", "100,64,100") in got, got
assert not any(p == "9000,64,9000" for _, p in got), got
assert not any(k == "minecraft:the_nether" for k, _ in got), got
assert (f"chesttracker:ender_chest/{EUUID}", "0,0,0") in got, got
assert r["owners"].get(EUUID) == "T", r["owners"]
r = c.get("/api/pull", params={"serverId": CANON, "playerUuid": STRANGER}, headers=TOK).json()
got = {(x["key"], x["pos"]) for x in r["changes"]}
assert ("minecraft:overworld", "9000,64,9000") in got, got  # legacy: no pos, no gate
assert ("minecraft:the_nether", "100,64,100") in got, got
print("range gate ok")

# --- pullWebPage: ungated full pull for the website (coords included by design) ---
r = c.get("/api/pullWebPage", params={"serverId": CANON, "playerUuid": STRANGER}, headers=TOK).json()
assert r["status"] == "SYNCED", r
got = {(x["key"], x["pos"]) for x in r["changes"]}
assert ("minecraft:overworld", "9000,64,9000") in got, got
assert ("minecraft:the_nether", "100,64,100") in got, got
assert all("pos" in t for t in r["tombstones"]), r["tombstones"]
assert EUUID in r.get("owners", {}), r.get("owners")
print("pullWebPage ok")

# --- wipe: admin two-step, generation bumps, snapshots kept ---
r = c.post("/api/wipe", json=dict(fident(STRANGER, CANON), confirm=False),
           headers={"X-CMSync-Token": "wrong"})
assert r.status_code == 401, (r.status_code, r.text)
r = c.post("/api/wipe", json=dict(fident(STRANGER, CANON), confirm=False), headers=TOK).json()
assert r["status"] == "CONFIRM_REQUIRED" and r["challenge"] and r["containers"] > 0, r
r = c.post("/api/wipe", json=dict(fident(STRANGER, CANON), confirm=True, challenge="nope"),
           headers=TOK)
assert r.status_code == 400 and r.json()["status"] == "BAD_CHALLENGE", (r.status_code, r.text)
r = c.post("/api/wipe", json=dict(fident(STRANGER, CANON), confirm=True,
                                  challenge=c.post("/api/wipe", json=dict(fident(STRANGER, CANON),
                                  confirm=False), headers=TOK).json()["challenge"]), headers=TOK).json()
assert r["status"] == "WIPED" and r["generation"] == 1, r
assert c.get(f"/api/view/{CANON}").json()["containers"] == 0
assert c.get("/api/snapshots", params={"serverId": CANON}).json()["snapshots"], "snapshots kept"
r = c.post("/api/handshake", json=fident(STRANGER, CANON), headers=TOK).json()
assert r["status"] == "SYNCED" and r["generation"] == 1, r
r = c.get("/api/pull", params={"serverId": CANON, "playerUuid": STRANGER}, headers=TOK).json()
assert r["generation"] == 1 and r["changes"] == [], r
print("wipe ok")

print("ALL API TESTS PASSED")
