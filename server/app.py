"""CMSync authoritative server: handshake / push(delta) / pull / snapshots / view."""
from __future__ import annotations
import hashlib
import json
import secrets
import time
from contextlib import contextmanager

from fastapi import FastAPI, Header, HTTPException, Query
from fastapi.exceptions import RequestValidationError
from fastapi.requests import Request
from fastapi.responses import JSONResponse

import logging

import config
import db
from models import Change, HandshakeRequest, PushRequest

app = FastAPI(title="CMSync", version="2.0.0")
CMSYNC_SERVER = "2.3"  # bump on any server behavior change; visible in /health
_log = logging.getLogger("cmsync")
# Explicit handler: uvicorn's default config leaves the root logger handler-less,
# so INFO records would silently vanish (only WARNING+ reaches stderr).
_log.addHandler(logging.StreamHandler())
_log.setLevel(logging.INFO)
_log.propagate = False
_last_snapshot: dict[str, float] = {}


@app.exception_handler(RequestValidationError)
async def _log_validation_error(request: Request, exc: RequestValidationError):
    """Log the exact failing field + body (default handler stays silent in logs).
    This is how we diagnose client 422s from Portainer without guessing."""
    try:
        raw = (await request.body()).decode("utf-8", "replace")[:2000]
    except Exception:
        raw = "<unreadable>"
    _log.warning("422 %s %s errors=%s body=%s", request.method, request.url.path, exc.errors(), raw)
    return JSONResponse(status_code=422, content={"detail": exc.errors()})


@app.middleware("http")
async def _log_post_sender(request: Request, call_next):
    """Fingerprint POST senders (client library + body size). Tells apart the real
    mod (Java-http-client, bodies present) from hand tests, bots, or anything on
    the player's machine that strips POST bodies (which arrive empty)."""
    if request.method == "POST":
        _log.info("POST %s ua=%s len=%s", request.url.path,
                  request.headers.get("user-agent", "?")[:80],
                  request.headers.get("content-length", "?"))
    return await call_next(request)

@contextmanager
def _con():
    con = db.connect(config.DB_PATH)
    try:
        yield con
    finally:
        con.close()

def _gate(player_uuid: str | None, token: str | None):
    """Shared access check. Returns an ACCESS_DENIED dict, raises 401 on bad
    token, or None if allowed.

    Two modes (see .env.example):
    * UUID mode: WHITELIST_UUIDS set -> only those UUIDs in (token optional extra).
    * Token mode: WHITELIST_UUIDS empty -> whitelist skipped, SHARED_TOKEN required.
    """
    if config.WHITELIST_UUIDS and player_uuid not in config.WHITELIST_UUIDS:
        return {"status": "ACCESS_DENIED", "reason": "not whitelisted"}
    if config.SHARED_TOKEN and token != config.SHARED_TOKEN:
        raise HTTPException(401, "bad token")
    return None

def _deny(reason: str, status: int = 200):
    if status == 401:
        raise HTTPException(401, reason)
    return {"status": "ACCESS_DENIED", "reason": reason}

@app.get("/health")
def health():
    return {"ok": True, "time": time.time(), "expectedServer": config.EXPECTED_SERVER_ID,
            "cmsync": CMSYNC_SERVER}


@app.get("/", include_in_schema=False)
def index():
    return {"service": "cmsync", "health": "/health", "protocol": 2}


@app.get("/favicon.ico", include_in_schema=False)
def favicon():
    from fastapi.responses import Response
    return Response(status_code=204)

@app.post("/api/handshake")
def handshake(req: HandshakeRequest, x_cmsync_token: str | None = Header(default=None, alias="X-CMSync-Token")):
    if req.protocolVersion != 2:
        raise HTTPException(400, "unsupported protocolVersion")
    sid = config.canonical_server_id(req.serverId)
    if config.EXPECTED_SERVER_ID and sid != config.EXPECTED_SERVER_ID:
        return {"status": "ACCESS_DENIED", "reason": "wrong serverId"}
    denied = _gate(req.playerUuid, x_cmsync_token)
    if denied:
        return denied
    with _con() as con:
        return {"status": "SYNCED", "generation": db.get_generation(con, sid)}

@app.post("/api/push")
def push(req: PushRequest, x_cmsync_token: str | None = Header(default=None, alias="X-CMSync-Token")):
    # access check per request (no sessions)
    if req.protocolVersion != 2:
        raise HTTPException(400, "unsupported protocolVersion")
    sid = config.canonical_server_id(req.serverId)
    if config.EXPECTED_SERVER_ID and sid != config.EXPECTED_SERVER_ID:
        return _deny("wrong serverId")
    try:
        denied = _gate(req.playerUuid, x_cmsync_token)
    except HTTPException:
        return _deny("bad token", 401)
    if denied:
        return denied

    changes = [c.model_dump() for c in req.changes]
    with _con() as con:
        db.record_owners(con, sid, changes, req.playerUuid, req.playerName)
        existing = db.container_count(con, sid)
        deletes = sum(1 for c in changes if c.get("deleted"))

        # Hub-wipe protection: empty push against non-empty server = ignore, keep snapshot.
        if not changes and existing > 0:
            return {"status": "SYNCED", "applied": 0, "skipped_stale": 0,
                    "note": "ignored empty push (possible hub-wipe)", "containers": existing}

        # Mass deletes go straight through (with a pre-delete snapshot + warning log
        # so accidents stay recoverable). Only fully-empty pushes are ignored (hub-wipe).
        if deletes > 0 and db.mass_delete_detected(existing, deletes,
                                            config.MAX_DELETE_FRACTION, config.MAX_DELETE_COUNT,
                                            config.MIN_QUARANTINE_BANK):
            snap_id = db.take_snapshot(con, sid, config.SNAPSHOT_KEEP)
            _log.warning("MASS DELETE %s: %s deletes vs %s stored by %s (snapshot %s)",
                         sid, deletes, existing, req.playerUuid, snap_id)

        # v1-compat: full-snapshot posts arrive as PushRequest with many upserts; LWW handles them.
        res = db.apply_changes(con, sid, changes)
        _maybe_snapshot(con, sid)
        res.update({"status": "SYNCED", "containers": db.container_count(con, sid)})
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
         px: int | None = Query(default=None), py: int | None = Query(default=None),
         pz: int | None = Query(default=None), dim: str | None = Query(default=None),
         x_cmsync_token: str | None = Header(default=None, alias="X-CMSync-Token")):
    sid = config.canonical_server_id(serverId)
    if config.EXPECTED_SERVER_ID and sid != config.EXPECTED_SERVER_ID:
        return {"status": "ACCESS_DENIED", "reason": "wrong serverId"}
    denied = _gate(playerUuid, x_cmsync_token)
    if denied:
        return denied
    with _con() as con:
        changes, tombs = db.select_pull(con, sid, dim, px, py, pz, config.RANGE_BLOCKS)
        owners = db.get_owners(con, sid)
        return {"status": "SYNCED", "serverTime": time.time(), "cursor": since or "",
                "changes": changes, "tombstones": tombs, "owners": owners,
                "generation": db.get_generation(con, sid),
                "containers": db.container_count(con, sid)}

@app.get("/api/pullWebPage")
def pullWebPage(serverId: str = Query(...), since: str | None = Query(default=None),
         playerUuid: str = Query(...),
         x_cmsync_token: str | None = Header(default=None, alias="X-CMSync-Token")):
    sid = config.canonical_server_id(serverId)
    if config.EXPECTED_SERVER_ID and sid != config.EXPECTED_SERVER_ID:
        return {"status": "ACCESS_DENIED", "reason": "wrong serverId"}
    denied = _gate(playerUuid, x_cmsync_token)
    if denied:
        return denied
    with _con() as con:
        state = db.full_state(con, sid)
        changes = []
        for key, positions in state.items():
            for pos, mem in positions.items():
                if since and mem.get("updatedAt", "") <= since:
                    continue
                changes.append({"key": key, "pos": pos, "deleted": False, **mem})
        tombs = [dict(r) for r in con.execute(
            "SELECT key,pos,deleted_at FROM tombstones WHERE server_id=?", (sid,))]
        return {"status": "SYNCED", "serverTime": time.time(), "cursor": since or "",
                "changes": changes, "tombstones": tombs, "owners": db.get_owners(con, sid),
                "containers": sum(len(v) for v in state.values())}

@app.get("/api/view/{server_id:path}")
def view(server_id: str):
    """Website/Discord read model: aggregated counts from normalized items."""
    sid = config.canonical_server_id(server_id)
    with _con() as con:
        state = db.full_state(con, sid)
    totals: dict[str, int] = {}
    for positions in state.values():
        for mem in positions.values():
            for it in mem.get("items", []):
                totals[it["id"]] = totals.get(it["id"], 0) + int(it.get("count", 0))
    return {"serverId": sid, "containers": sum(len(v) for v in state.values()),
            "totals": totals, "keys": list(state.keys())}

@app.get("/api/snapshots")
def snapshots(serverId: str):
    with _con() as con:
        return {"snapshots": db.list_snapshots(con, config.canonical_server_id(serverId))}

@app.post("/api/restore")
def restore(body: dict, x_cmsync_token: str | None = Header(default=None, alias="X-CMSync-Token")):
    if config.ADMIN_TOKEN and x_cmsync_token != config.ADMIN_TOKEN:
        raise HTTPException(401, "admin only")
    with _con() as con:
        n = db.restore_snapshot(con, config.canonical_server_id(body["serverId"]), int(body["snapshotId"]))
        return {"status": "SYNCED", "restored": n}


# Two-step wipe: POST {confirm:false} -> challenge, then POST {confirm:true, challenge}.
# Requires ADMIN_TOKEN (wipe stays disabled while it is empty). Snapshots are kept
# as the recovery path; memories, tombstones and owners go to zero and the wipe
# generation bumps so connected (and later returning) clients clear their locals too.
_pending_wipes: dict[str, tuple[str, float]] = {}


@app.post("/api/wipe")
def wipe(body: dict, x_cmsync_token: str | None = Header(default=None, alias="X-CMSync-Token")):
    if not config.ADMIN_TOKEN or x_cmsync_token != config.ADMIN_TOKEN:
        raise HTTPException(401, "admin only")
    sid = config.canonical_server_id(body.get("serverId", ""))
    if config.EXPECTED_SERVER_ID and sid != config.EXPECTED_SERVER_ID:
        return {"status": "ACCESS_DENIED", "reason": "wrong serverId"}
    with _con() as con:
        if not body.get("confirm"):
            challenge = secrets.token_hex(16)
            _pending_wipes[sid] = (challenge, time.time() + 60)
            return {"status": "CONFIRM_REQUIRED", "challenge": challenge,
                    "containers": db.container_count(con, sid),
                    "warning": ("This deletes ALL stored item data for this server "
                                "(memories, deletes, owners). Snapshots are kept.")}

        pend = _pending_wipes.pop(sid, None)
        if not pend or pend[0] != body.get("challenge") or time.time() > pend[1]:
            return JSONResponse(status_code=400, content={"status": "BAD_CHALLENGE",
                               "reason": "stale or wrong challenge; start over"})
        result = db.wipe_server(con, sid)
        result.update({"status": "WIPED"})
        _log.warning("WIPE %s by %s: %s", sid, body.get("playerUuid"), result)
        return result

# v1 compat: QMSync POST /api/sync full snapshot -> diff into deltas is client-driven;
# accept raw v1 payload here so old 1.21.11-only clients don't 404.
@app.post("/api/sync")
def sync_v1(body: dict, x_cmsync_token: str | None = Header(default=None, alias="X-CMSync-Token")):
    ident_keys = ("playerUuid", "serverId")
    if not all(k in body for k in ident_keys):
        raise HTTPException(400, "bad v1 payload")
    sid = config.canonical_server_id(body.get("serverId", ""))
    if config.EXPECTED_SERVER_ID and sid != config.EXPECTED_SERVER_ID:
        return {"status": "ACCESS_DENIED"}
    # NOTE: v1 has no token header; in token mode (whitelist empty + SHARED_TOKEN
    # set) v1 clients are rejected — token mode needs the cmsync overlay client.
    try:
        if _gate(body.get("playerUuid"), x_cmsync_token):
            return {"status": "ACCESS_DENIED"}
    except HTTPException:
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
        res = db.apply_changes(con, sid, changes)
        res.update({"status": "SYNCED"})
        return res
