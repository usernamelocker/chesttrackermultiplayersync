"""CMSync authoritative server: handshake / push(delta) / pull / snapshots / view."""
from __future__ import annotations
import hashlib
import json
import time
from contextlib import contextmanager

from fastapi import FastAPI, Header, HTTPException, Query
from fastapi.responses import JSONResponse

import config
import db
from models import Change, HandshakeRequest, PushRequest

app = FastAPI(title="CMSync", version="2.0.0")
_last_snapshot: dict[str, float] = {}

@contextmanager
def _con():
    con = db.connect(config.DB_PATH)
    try:
        yield con
    finally:
        con.close()

def _check_identity(ident, token: str | None) -> None:
    if ident.protocolVersion != 2:
        raise HTTPException(400, "unsupported protocolVersion (want 2)")
    if config.EXPECTED_SERVER_ID and ident.serverId != config.EXPECTED_SERVER_ID:
        raise HTTPException(200, "wrong server")  # handled as ACCESS_DENIED below
    if ident.playerUuid not in config.WHITELIST_UUIDS:
        raise HTTPException(200, "not whitelisted")
    if config.SHARED_TOKEN and token != config.SHARED_TOKEN:
        raise HTTPException(401, "bad token")

def _deny(reason: str, status: int = 200):
    if status == 401:
        raise HTTPException(401, reason)
    return {"status": "ACCESS_DENIED", "reason": reason}

@app.get("/health")
def health():
    return {"ok": True, "time": time.time(), "expectedServer": config.EXPECTED_SERVER_ID}

@app.post("/api/handshake")
def handshake(req: HandshakeRequest, x_cmsync_token: str | None = Header(default=None, alias="X-CMSync-Token")):
    if req.protocolVersion != 2:
        raise HTTPException(400, "unsupported protocolVersion")
    if config.EXPECTED_SERVER_ID and req.serverId != config.EXPECTED_SERVER_ID:
        return {"status": "ACCESS_DENIED", "reason": "wrong serverId"}
    if req.playerUuid not in config.WHITELIST_UUIDS:
        return {"status": "ACCESS_DENIED", "reason": "not whitelisted"}
    if config.SHARED_TOKEN and x_cmsync_token != config.SHARED_TOKEN:
        raise HTTPException(401, "bad token")
    return {"status": "SYNCED"}

@app.post("/api/push")
def push(req: PushRequest, x_cmsync_token: str | None = Header(default=None, alias="X-CMSync-Token")):
    # access check per request (no sessions)
    if req.protocolVersion != 2:
        raise HTTPException(400, "unsupported protocolVersion")
    if config.EXPECTED_SERVER_ID and req.serverId != config.EXPECTED_SERVER_ID:
        return _deny("wrong serverId")
    if req.playerUuid not in config.WHITELIST_UUIDS:
        return _deny("not whitelisted")
    if config.SHARED_TOKEN and x_cmsync_token != config.SHARED_TOKEN:
        return _deny("bad token", 401)

    changes = [c.model_dump() for c in req.changes]
    with _con() as con:
        existing = db.container_count(con, req.serverId)
        deletes = sum(1 for c in changes if c.get("deleted"))

        # Hub-wipe protection: empty push against non-empty server = ignore, keep snapshot.
        if not changes and existing > 0:
            return {"status": "SYNCED", "applied": 0, "skipped_stale": 0,
                    "note": "ignored empty push (possible hub-wipe)", "containers": existing}

        # Mass-delete guard: quarantine, snapshot first, do not apply deletes.
        if db.should_quarantine_mass_delete(existing, deletes,
                                            config.MAX_DELETE_FRACTION, config.MAX_DELETE_COUNT):
            snap_id = db.take_snapshot(con, req.serverId, config.SNAPSHOT_KEEP)
            return JSONResponse({"status": "QUARANTINED",
                                 "reason": f"mass delete: {deletes} deletes vs {existing} stored",
                                 "snapshotId": snap_id, "containers": existing})

        # v1-compat: full-snapshot posts arrive as PushRequest with many upserts; LWW handles them.
        res = db.apply_changes(con, req.serverId, changes)
        _maybe_snapshot(con, req.serverId)
        res.update({"status": "SYNCED", "containers": db.container_count(con, req.serverId)})
        return res

def _maybe_snapshot(con, server_id: str) -> None:
    now = time.time()
    last = _last_snapshot.get(server_id, 0)
    if now - last >= config.SNAPSHOT_INTERVAL_MIN * 60:
        db.take_snapshot(con, server_id, config.SNAPSHOT_KEEP)
        db.prune_tombstones(con, config.TOMBSTONE_TTL_DAYS)
        _last_snapshot[server_id] = now

@app.get("/api/pull")
def pull(serverId: str = Query(...), since: str | None = Query(default=None),
         playerUuid: str = Query(...),
         x_cmsync_token: str | None = Header(default=None, alias="X-CMSync-Token")):
    if config.EXPECTED_SERVER_ID and serverId != config.EXPECTED_SERVER_ID:
        return {"status": "ACCESS_DENIED", "reason": "wrong serverId"}
    if playerUuid not in config.WHITELIST_UUIDS:
        return {"status": "ACCESS_DENIED", "reason": "not whitelisted"}
    if config.SHARED_TOKEN and x_cmsync_token != config.SHARED_TOKEN:
        raise HTTPException(401, "bad token")
    with _con() as con:
        state = db.full_state(con, serverId)
        changes = []
        for key, positions in state.items():
            for pos, mem in positions.items():
                if since and mem.get("updatedAt", "") <= since:
                    continue
                changes.append({"key": key, "pos": pos, "deleted": False, **mem})
        tombs = [dict(r) for r in con.execute(
            "SELECT key,pos,deleted_at FROM tombstones WHERE server_id=?", (serverId,))]
        return {"status": "SYNCED", "serverTime": time.time(), "cursor": since or "",
                "changes": changes, "tombstones": tombs,
                "containers": sum(len(v) for v in state.values())}

@app.get("/api/view/{server_id:path}")
def view(server_id: str):
    """Website/Discord read model: aggregated counts from normalized items."""
    with _con() as con:
        state = db.full_state(con, server_id)
    totals: dict[str, int] = {}
    for positions in state.values():
        for mem in positions.values():
            for it in mem.get("items", []):
                totals[it["id"]] = totals.get(it["id"], 0) + int(it.get("count", 0))
    return {"serverId": server_id, "containers": sum(len(v) for v in state.values()),
            "totals": totals, "keys": list(state.keys())}

@app.get("/api/snapshots")
def snapshots(serverId: str):
    with _con() as con:
        return {"snapshots": db.list_snapshots(con, serverId)}

@app.post("/api/restore")
def restore(body: dict, x_cmsync_token: str | None = Header(default=None, alias="X-CMSync-Token")):
    if config.ADMIN_TOKEN and x_cmsync_token != config.ADMIN_TOKEN:
        raise HTTPException(401, "admin only")
    with _con() as con:
        n = db.restore_snapshot(con, body["serverId"], int(body["snapshotId"]))
        return {"status": "SYNCED", "restored": n}

# v1 compat: QMSync POST /api/sync full snapshot -> diff into deltas is client-driven;
# accept raw v1 payload here so old 1.21.11-only clients don't 404.
@app.post("/api/sync")
def sync_v1(body: dict):
    ident_keys = ("playerUuid", "serverId")
    if not all(k in body for k in ident_keys):
        raise HTTPException(400, "bad v1 payload")
    if config.EXPECTED_SERVER_ID and body.get("serverId") != config.EXPECTED_SERVER_ID:
        return {"status": "ACCESS_DENIED"}
    if body.get("playerUuid") not in config.WHITELIST_UUIDS:
        return {"status": "ACCESS_DENIED"}
    # v1 carries full `data`; without per-entry timestamps we store as-is with now.
    import datetime
    now = datetime.datetime.utcnow().isoformat() + "Z"
    data = body.get("data", {})
    changes = []
    for key, keyobj in data.items():
        for pos, mem in (keyobj.get("memories", {}) or {}).items():
            items = mem.get("items", []) if isinstance(mem, dict) else []
            norm = [{"id": (it.get("id") if isinstance(it, dict) else "unknown"), "count": int(it.get("count", 1)) if isinstance(it, dict) else 1}
                    for it in items if isinstance(it, dict) and it.get("id")]
            changes.append({"key": key, "pos": pos, "deleted": False, "updatedAt": now,
                            "updatedBy": body.get("playerUuid"), "mcVersion": body.get("mcVersion", "v1"),
                            "items": norm, "raw": mem})
    with _con() as con:
        res = db.apply_changes(con, body["serverId"], changes)
        res.update({"status": "SYNCED"})
        return res
