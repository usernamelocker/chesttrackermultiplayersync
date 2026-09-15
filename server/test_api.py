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

assert c.post("/api/handshake", json=ident(ALICE)).json()["status"] == "SYNCED"
assert c.post("/api/handshake", json=ident("99999999-9999-9999-9999-999999999999")).json()["status"] == "ACCESS_DENIED"
assert c.post("/api/handshake", json=ident(ALICE, sid="multiplayer/other")).json()["status"] == "ACCESS_DENIED"
print("handshake ok")

iron = [{"id": "minecraft:iron_ingot", "count": 5}]
diamond = [{"id": "minecraft:diamond", "count": 2}]
body = dict(ident(ALICE), fullHash="h1", changes=[
    change("minecraft:overworld", "1,2,3", 10, iron),
    change("minecraft:overworld", "4,5,6", 10, diamond, mc="26.2"),
])
r = c.post("/api/push", json=body).json()
assert r["status"] == "SYNCED" and r["applied"] == 2, r
print("push ok")

r = c.get("/api/pull", params={"serverId": SID, "playerUuid": BOB}).json()
assert r["status"] == "SYNCED" and len(r["changes"]) == 2, r
print("pull ok")

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
assert r["status"] == "QUARANTINED", r
assert c.get(f"/api/view/{SID}").json()["containers"] == 62, "mass delete must not apply"
print("quarantine ok")

snaps = c.get("/api/snapshots", params={"serverId": SID}).json()["snapshots"]
assert len(snaps) >= 2, snaps
first_id = snaps[-1]["id"]  # oldest = the 2-container snapshot
r = c.post("/api/restore", json={"serverId": SID, "snapshotId": first_id}).json()
assert r["status"] == "SYNCED" and r["restored"] == 2, r
assert c.get(f"/api/view/{SID}").json()["containers"] == 2
print("restore ok")

print("ALL API TESTS PASSED")
