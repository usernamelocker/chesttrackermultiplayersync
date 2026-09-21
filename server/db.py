"""SQLite store: per-container merge (LWW), tombstones, snapshots. stdlib only."""
from __future__ import annotations
import json
import sqlite3
import time
from pathlib import Path

SCHEMA = """
PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS memories(
  server_id TEXT NOT NULL,
  key TEXT NOT NULL,
  pos TEXT NOT NULL,
  items_norm TEXT NOT NULL,
  raw TEXT,
  mc_version TEXT,
  updated_by TEXT,
  updated_at TEXT NOT NULL,
  PRIMARY KEY(server_id, key, pos)
);
CREATE TABLE IF NOT EXISTS tombstones(
  server_id TEXT NOT NULL,
  key TEXT NOT NULL,
  pos TEXT NOT NULL,
  deleted_at TEXT NOT NULL,
  deleted_by TEXT,
  PRIMARY KEY(server_id, key, pos)
);
CREATE TABLE IF NOT EXISTS snapshots(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  server_id TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  containers INTEGER NOT NULL,
  payload TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT);
CREATE TABLE IF NOT EXISTS change_events(
  server_id TEXT NOT NULL,
  revision INTEGER NOT NULL,
  key TEXT NOT NULL,
  pos TEXT NOT NULL,
  deleted INTEGER NOT NULL,
  payload TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  PRIMARY KEY(server_id, revision)
);
CREATE INDEX IF NOT EXISTS change_events_server_revision
  ON change_events(server_id, revision);
CREATE TABLE IF NOT EXISTS pull_sessions(
  server_id TEXT NOT NULL,
  client_id TEXT NOT NULL,
  dim TEXT,
  px INTEGER,
  py INTEGER,
  pz INTEGER,
  revision INTEGER NOT NULL,
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  PRIMARY KEY(server_id, client_id)
);
CREATE TABLE IF NOT EXISTS wipe_challenges(
  server_id TEXT PRIMARY KEY,
  challenge TEXT NOT NULL,
  expires_at REAL NOT NULL,
  state TEXT NOT NULL DEFAULT 'pending',
  result TEXT,
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE TABLE IF NOT EXISTS key_owners(
  server_id TEXT NOT NULL,
  key TEXT NOT NULL,
  uuid TEXT NOT NULL,
  name TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY(server_id, key, uuid)
);
"""

# Personal-inventory namespaces (per-player ender chests etc.): exempt from the
# range gate (no meaningful position) and tracked for profile names.
ENDER_KEY_PREFIXES = ("chesttracker:ender_chest", "hypixel:skyblock_ender_chest",
                       "shareenderchest:contents")


def is_ender_key(key: str) -> bool:
    return (key or "").startswith(ENDER_KEY_PREFIXES)

def connect(db_path: str) -> sqlite3.Connection:
    Path(db_path).parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(db_path)
    con.row_factory = sqlite3.Row
    con.executescript(SCHEMA)
    return con

def container_count(con: sqlite3.Connection, server_id: str) -> int:
    row = con.execute("SELECT COUNT(*) c FROM memories WHERE server_id=?", (server_id,)).fetchone()
    return int(row["c"])

def full_state(con: sqlite3.Connection, server_id: str) -> dict:
    out: dict = {}
    for r in con.execute("SELECT key,pos,items_norm,raw,mc_version,updated_by,updated_at FROM memories WHERE server_id=?", (server_id,)):
        out.setdefault(r["key"], {})[r["pos"]] = {
            "items": json.loads(r["items_norm"]),
            "raw": json.loads(r["raw"]) if r["raw"] else None,
            "mcVersion": r["mc_version"],
            "updatedBy": r["updated_by"],
            "updatedAt": r["updated_at"],
        }
    return out

def _logical_version(con: sqlite3.Connection, server_id: str, key: str, pos: str) -> str | None:
    """Return the authoritative version for one logical container.

    Memories and tombstones are stored separately for compatibility with the
    existing schema, but they represent one LWW stream.  A row in either table
    therefore participates in the same comparison.
    """
    row = con.execute(
        """SELECT MAX(version) AS version FROM (
               SELECT updated_at AS version FROM memories
                WHERE server_id=? AND key=? AND pos=?
               UNION ALL
               SELECT deleted_at AS version FROM tombstones
                WHERE server_id=? AND key=? AND pos=?
           )""",
        (server_id, key, pos, server_id, key, pos),
    ).fetchone()
    return row["version"] if row and row["version"] is not None else None

def get_revision(con: sqlite3.Connection, server_id: str) -> int:
    row = con.execute("SELECT v FROM meta WHERE k=?", (f"rev:{server_id}",)).fetchone()
    try:
        return int(row["v"]) if row else 0
    except (ValueError, TypeError):
        return 0

def _append_event(con: sqlite3.Connection, server_id: str, change: dict) -> int:
    revision = get_revision(con, server_id) + 1
    con.execute("INSERT INTO meta(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v",
                (f"rev:{server_id}", str(revision)))
    payload = dict(change)
    payload.setdefault("deleted", False)
    payload.setdefault("items", [])
    con.execute(
        "INSERT INTO change_events(server_id,revision,key,pos,deleted,payload) VALUES(?,?,?,?,?,?)",
        (server_id, revision, payload["key"], payload["pos"],
         1 if payload.get("deleted") else 0, json.dumps(payload)),
    )
    return revision

def apply_changes(con: sqlite3.Connection, server_id: str, changes: list[dict]) -> dict:
    """LWW per (key,pos). Returns {applied, skipped_stale}."""
    applied = skipped = 0
    for c in changes:
        key, pos = c["key"], c["pos"]
        current_version = _logical_version(con, server_id, key, pos)
        if current_version is not None and current_version >= c["updatedAt"]:
            skipped += 1
            continue

        if c.get("deleted"):
            con.execute("DELETE FROM memories WHERE server_id=? AND key=? AND pos=?", (server_id, key, pos))
            con.execute("INSERT OR REPLACE INTO tombstones(server_id,key,pos,deleted_at,deleted_by) VALUES(?,?,?,?,?)",
                        (server_id, key, pos, c["updatedAt"], c.get("updatedBy")))
            _append_event(con, server_id, c)
        else:
            con.execute("DELETE FROM tombstones WHERE server_id=? AND key=? AND pos=?", (server_id, key, pos))
            con.execute(
                """INSERT INTO memories(
                       server_id,key,pos,items_norm,raw,mc_version,updated_by,updated_at
                   ) VALUES(?,?,?,?,?,?,?,?)
                   ON CONFLICT(server_id,key,pos) DO UPDATE SET
                       items_norm=excluded.items_norm,
                       raw=excluded.raw,
                       mc_version=excluded.mc_version,
                       updated_by=excluded.updated_by,
                       updated_at=excluded.updated_at""",
                (
                    server_id,
                    key,
                    pos,
                    json.dumps(c.get("items", [])),
                    json.dumps(c["raw"]) if c.get("raw") is not None else None,
                    c.get("mcVersion"),
                    c.get("updatedBy"),
                    c["updatedAt"],
                ),
            )
            _append_event(con, server_id, c)
        applied += 1
    con.commit()
    return {"applied": applied, "skipped_stale": skipped}

def take_snapshot(con: sqlite3.Connection, server_id: str, keep: int = 96) -> int:
    state = full_state(con, server_id)
    containers = sum(len(v) for v in state.values())
    cur = con.execute("INSERT INTO snapshots(server_id,containers,payload) VALUES(?,?,?)",
                      (server_id, containers, json.dumps(state)))
    con.execute("DELETE FROM snapshots WHERE id NOT IN "
                "(SELECT id FROM snapshots WHERE server_id=? ORDER BY id DESC LIMIT ?)", (server_id, keep))
    con.commit()
    return int(cur.lastrowid)

def list_snapshots(con: sqlite3.Connection, server_id: str, limit: int = 20) -> list[dict]:
    return [dict(r) for r in con.execute(
        "SELECT id,server_id,created_at,containers FROM snapshots WHERE server_id=? ORDER BY id DESC LIMIT ?",
        (server_id, limit))]

def restore_snapshot(con: sqlite3.Connection, server_id: str, snapshot_id: int) -> int:
    row = con.execute("SELECT payload FROM snapshots WHERE id=? AND server_id=?", (snapshot_id, server_id)).fetchone()
    if not row:
        raise KeyError("snapshot not found")
    state: dict = json.loads(row["payload"])
    old_memories = con.execute(
        "SELECT key,pos,items_norm,raw,mc_version,updated_by,updated_at FROM memories WHERE server_id=?",
        (server_id,),
    ).fetchall()
    old_tombstones = con.execute(
        "SELECT key,pos,deleted_at,deleted_by FROM tombstones WHERE server_id=?", (server_id,)
    ).fetchall()
    con.execute("DELETE FROM memories WHERE server_id=?", (server_id,))
    con.execute("DELETE FROM tombstones WHERE server_id=?", (server_id,))
    for mem in old_memories:
        _append_event(con, server_id, {"key": mem["key"], "pos": mem["pos"], "deleted": True,
                                       "updatedAt": mem["updated_at"], "updatedBy": mem["updated_by"],
                                       "items": []})
    for tomb in old_tombstones:
        _append_event(con, server_id, {"key": tomb["key"], "pos": tomb["pos"], "deleted": True,
                                       "updatedAt": tomb["deleted_at"], "updatedBy": tomb["deleted_by"],
                                       "items": []})
    n = 0
    for key, positions in state.items():
        for pos, mem in positions.items():
            con.execute("INSERT INTO memories(server_id,key,pos,items_norm,raw,mc_version,updated_by,updated_at)"
                        " VALUES(?,?,?,?,?,?,?,?)",
                        (server_id, key, pos, json.dumps(mem.get("items", [])),
                         json.dumps(mem["raw"]) if mem.get("raw") is not None else None,
                         mem.get("mcVersion"), mem.get("updatedBy"), mem.get("updatedAt", "1970-01-01T00:00:00Z")))
            _append_event(con, server_id, {"key": key, "pos": pos, "deleted": False,
                                           "updatedAt": mem.get("updatedAt", "1970-01-01T00:00:00Z"),
                                           "updatedBy": mem.get("updatedBy"),
                                           "mcVersion": mem.get("mcVersion"),
                                           "items": mem.get("items", []), "raw": mem.get("raw")})
            n += 1
    con.execute("INSERT OR REPLACE INTO meta(k,v) VALUES(?,?)",
                (f"gen:{server_id}", str(get_generation(con, server_id) + 1)))
    con.commit()
    return n

def prune_tombstones(con: sqlite3.Connection, ttl_days: int = 30) -> int:
    cur = con.execute("DELETE FROM tombstones WHERE deleted_at < strftime('%Y-%m-%dT%H:%M:%fZ','now', ?)",
                      (f"-{ttl_days} days",))
    con.commit()
    return cur.rowcount


def get_generation(con: sqlite3.Connection, server_id: str) -> int:
    """Wipe generation: bumped on every wipe. Clients holding an older generation
    clear their local banks on next contact (catches offline players too)."""
    row = con.execute("SELECT v FROM meta WHERE k=?", (f"gen:{server_id}",)).fetchone()
    try:
        return int(row["v"]) if row else 0
    except (ValueError, TypeError):
        return 0


def start_wipe_challenge(con: sqlite3.Connection, server_id: str,
                         challenge: str, expires_at: float) -> dict:
    """Create a shared challenge, safe across multiple Uvicorn workers.

    A pending/running challenge is reused instead of replaced so a retry from a
    different worker cannot invalidate the confirmation currently in flight.
    """
    now = time.time()
    con.execute("BEGIN IMMEDIATE")
    row = con.execute(
        "SELECT challenge,expires_at,state,result FROM wipe_challenges WHERE server_id=?",
        (server_id,),
    ).fetchone()
    if row and float(row["expires_at"]) > now and row["state"] in ("pending", "running"):
        con.commit()
        return dict(row)
    con.execute(
        "INSERT INTO wipe_challenges(server_id,challenge,expires_at,state,result) VALUES(?,?,?,?,NULL) "
        "ON CONFLICT(server_id) DO UPDATE SET challenge=excluded.challenge,expires_at=excluded.expires_at,"
        "state='pending',result=NULL,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')",
        (server_id, challenge, expires_at, "pending"),
    )
    con.commit()
    return {"challenge": challenge, "expires_at": expires_at, "state": "pending", "result": None}


def claim_wipe_challenge(con: sqlite3.Connection, server_id: str,
                         challenge: str) -> dict | None:
    """Claim one confirmation, or return its durable running/completed state."""
    con.execute("BEGIN IMMEDIATE")
    row = con.execute(
        "SELECT challenge,expires_at,state,result FROM wipe_challenges WHERE server_id=?",
        (server_id,),
    ).fetchone()
    if not row or row["challenge"] != challenge or float(row["expires_at"]) <= time.time():
        con.commit()
        return None
    if row["state"] == "completed":
        con.commit()
        return dict(row)
    if row["state"] == "running":
        con.commit()
        return dict(row)
    con.execute(
        "UPDATE wipe_challenges SET state='running',updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') "
        "WHERE server_id=? AND challenge=?",
        (server_id, challenge),
    )
    con.commit()
    return {"challenge": challenge, "expires_at": row["expires_at"], "state": "claimed", "result": None}


def complete_wipe_challenge(con: sqlite3.Connection, server_id: str,
                            challenge: str, result: dict) -> None:
    con.execute(
        "UPDATE wipe_challenges SET state='completed',result=?,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') "
        "WHERE server_id=? AND challenge=?",
        (json.dumps(result), server_id, challenge),
    )
    con.commit()


def wipe_server(con: sqlite3.Connection, server_id: str, keep_snapshots: bool = True) -> dict:
    """Delete memories, tombstones and owners; snapshot first; bump generation.
    Snapshots are kept as the recovery path (pass keep_snapshots=False to nuke all)."""
    snap_id = take_snapshot(con, server_id)
    old_memories = con.execute("SELECT key,pos,updated_at,updated_by FROM memories WHERE server_id=?", (server_id,)).fetchall()
    old_tombstones = con.execute("SELECT key,pos,deleted_at,deleted_by FROM tombstones WHERE server_id=?", (server_id,)).fetchall()
    mem = con.execute("DELETE FROM memories WHERE server_id=?", (server_id,)).rowcount
    tomb = con.execute("DELETE FROM tombstones WHERE server_id=?", (server_id,)).rowcount
    for row in old_memories:
        _append_event(con, server_id, {"key": row["key"], "pos": row["pos"], "deleted": True,
                                       "updatedAt": row["updated_at"], "updatedBy": row["updated_by"], "items": []})
    for row in old_tombstones:
        _append_event(con, server_id, {"key": row["key"], "pos": row["pos"], "deleted": True,
                                       "updatedAt": row["deleted_at"], "updatedBy": row["deleted_by"], "items": []})
    own = con.execute("DELETE FROM key_owners WHERE server_id=?", (server_id,)).rowcount
    if not keep_snapshots:
        con.execute("DELETE FROM snapshots WHERE server_id=?", (server_id,))
    con.execute("INSERT OR REPLACE INTO meta(k,v) VALUES(?,?)",
                (f"gen:{server_id}", str(get_generation(con, server_id) + 1)))
    con.commit()
    return {"memories": mem, "tombstones": tomb, "owners": own,
            "snapshotId": snap_id, "generation": get_generation(con, server_id)}


def record_owners(con: sqlite3.Connection, server_id: str, changes: list[dict],
                  identity_uuid: str | None, identity_name: str | None) -> int:
    """Remember uuid->name per ender-chest key (powers profile labels)."""
    n = 0
    for c in changes:
        if not is_ender_key(c.get("key", "")):
            continue
        uuid = c.get("updatedBy") or identity_uuid
        if not uuid:
            continue
        con.execute("INSERT OR REPLACE INTO key_owners(server_id,key,uuid,name,updated_at)"
                    " VALUES(?,?,?,?,?)",
                    (server_id, c["key"], uuid, identity_name or uuid,
                     c.get("updatedAt", "1970-01-01T00:00:00Z")))
        n += 1
    if n:
        con.commit()
    return n


def get_owners(con: sqlite3.Connection, server_id: str) -> dict:
    rows = con.execute(
        """SELECT ko.uuid AS uuid, ko.name AS name FROM key_owners ko
           JOIN (SELECT uuid, MAX(updated_at) AS m FROM key_owners
                 WHERE server_id=? GROUP BY uuid) latest
           ON latest.uuid = ko.uuid AND latest.m = ko.updated_at
           WHERE ko.server_id=?""", (server_id, server_id))
    return {r["uuid"]: r["name"] for r in rows}


def _parse_pos(pos: str) -> tuple[int, int, int] | None:
    try:
        x, y, z = (int(p.strip()) for p in pos.split(","))
        return (x, y, z)
    except (ValueError, AttributeError):
        return None


def in_range(pos: str, dim_key: str, player_dim: str,
             px: int, py: int, pz: int, radius: int) -> bool:
    """Range gate in Overworld-equivalent blocks (3D).

    World data remains dimension-scoped: Nether records are only compared with
    a player in the Nether.  Inside the Nether, horizontal coordinates are
    multiplied by eight so the default 5,000-block security radius represents
    the same Overworld-equivalent area (625 Nether blocks).  Y is not scaled.
    Ender-style keys (no meaningful position) always pass; unknown/unparseable
    positions fail closed.
    """
    if is_ender_key(dim_key):
        return True
    if dim_key != player_dim:
        return False
    parsed = _parse_pos(pos)
    if parsed is None:
        return False
    x, y, z = parsed
    horizontal_scale = 8 if player_dim == "minecraft:the_nether" else 1
    dx = (x - px) * horizontal_scale
    dz = (z - pz) * horizontal_scale
    return dx ** 2 + (y - py) ** 2 + dz ** 2 <= radius * radius


def select_pull(con: sqlite3.Connection, server_id: str, player_dim: str | None = None,
                px: int | None = None, py: int | None = None, pz: int | None = None,
                radius: int = 5000, since: int | None = None) -> tuple[list[dict], list[dict]]:
    """Gated pull: without a player position this is the legacy full pull (compat);
    with one, only same-dimension, Overworld-equivalent in-range entries plus
    ender keys are returned — including tombstones (positions leak too)."""
    gated = player_dim is not None and px is not None and py is not None and pz is not None
    if since is not None:
        changes: list[dict] = []
        tombs: list[dict] = []
        for row in con.execute(
                "SELECT payload FROM change_events WHERE server_id=? AND revision>? ORDER BY revision",
                (server_id, since)):
            payload = json.loads(row["payload"])
            if gated and not in_range(payload.get("pos", ""), payload.get("key", ""),
                                      player_dim, px, py, pz, radius):
                continue
            if payload.get("deleted"):
                tombs.append({"key": payload["key"], "pos": payload["pos"],
                              "deleted_at": payload.get("updatedAt"),
                              "deleted_by": payload.get("updatedBy")})
            else:
                changes.append(payload)
        return changes, tombs
    changes: list[dict] = []
    for key, positions in full_state(con, server_id).items():
        for pos, mem in positions.items():
            if gated and not in_range(pos, key, player_dim, px, py, pz, radius):
                continue
            changes.append({"key": key, "pos": pos, "deleted": False, **mem})
    tombs = []
    for r in con.execute("SELECT key,pos,deleted_at FROM tombstones WHERE server_id=?", (server_id,)):
        if gated and not in_range(r["pos"], r["key"], player_dim, px, py, pz, radius):
            continue
        tombs.append(dict(r))
    return changes, tombs

def select_pull_for_client(con: sqlite3.Connection, server_id: str, client_id: str,
                           player_dim: str | None, px: int | None, py: int | None,
                           pz: int | None, radius: int, since: int | None) -> tuple[list[dict], list[dict]]:
    """Use a durable per-client range cursor so moving into a new range cannot
    skip older events. A changed position/dimension receives the current relevant
    state; a stationary client receives only events after its revision."""
    gated = player_dim is not None and px is not None and py is not None and pz is not None
    if not gated:
        return select_pull(con, server_id, player_dim, px, py, pz, radius, since)
    session = con.execute(
        "SELECT dim,px,py,pz,revision FROM pull_sessions WHERE server_id=? AND client_id=?",
        (server_id, client_id),
    ).fetchone()
    moved = session is None or (session["dim"], session["px"], session["py"], session["pz"]) != \
        (player_dim, px, py, pz)
    stale_cursor = session is not None and (since is None or since < int(session["revision"]))
    effective_since = None if moved or stale_cursor else since
    changes, tombs = select_pull(con, server_id, player_dim, px, py, pz, radius, effective_since)
    con.execute(
        """INSERT INTO pull_sessions(server_id,client_id,dim,px,py,pz,revision)
           VALUES(?,?,?,?,?,?,?)
           ON CONFLICT(server_id,client_id) DO UPDATE SET
             dim=excluded.dim,px=excluded.px,py=excluded.py,pz=excluded.pz,
             revision=excluded.revision,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')""",
        (server_id, client_id, player_dim, px, py, pz, get_revision(con, server_id)),
    )
    con.commit()
    return changes, tombs

def mass_delete_detected(existing: int, delete_count: int,
                           max_fraction: float = 0.20, max_count: int = 50,
                           min_bank: int = 10) -> bool:
    """True for wipe-sized delete bursts. Advisory only: callers snapshot + log,
    then apply anyway (tiny banks exempt from the fraction rule)."""
    if existing <= 0 or delete_count <= 0:
        return False
    if delete_count >= max_count:
        return True
    if existing < min_bank:
        return False
    return (delete_count / max(1, existing)) >= max_fraction


def should_quarantine_mass_delete(*args, **kwargs) -> bool:
    """Backward-compat alias (the quarantine itself was removed)."""
    return mass_delete_detected(*args, **kwargs)

def is_empty_hash_push(full_hash: str, changes: list) -> bool:
    # Client sends sha256("[]")-style empty marker when it has nothing; server double-checks.
    # We treat any push with zero changes as potential hub-wipe and let caller decide via counts.
    return len(changes) == 0
