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

def apply_changes(con: sqlite3.Connection, server_id: str, changes: list[dict]) -> dict:
    """LWW per (key,pos). Returns {applied, skipped_stale}."""
    applied = skipped = 0
    for c in changes:
        key, pos = c["key"], c["pos"]
        if c.get("deleted"):
            # delete wins only if newer than existing memory
            cur = con.execute("SELECT updated_at FROM memories WHERE server_id=? AND key=? AND pos=?",
                              (server_id, key, pos)).fetchone()
            if cur and cur["updated_at"] >= c["updatedAt"]:
                skipped += 1
                continue
            con.execute("DELETE FROM memories WHERE server_id=? AND key=? AND pos=?", (server_id, key, pos))
            con.execute("INSERT OR REPLACE INTO tombstones(server_id,key,pos,deleted_at,deleted_by) VALUES(?,?,?,?,?)",
                        (server_id, key, pos, c["updatedAt"], c.get("updatedBy")))
            # clear tombstone if it was a re-add (deleted=false handled below removes tombstone)
            applied += 1
        else:
            cur = con.execute("SELECT updated_at FROM memories WHERE server_id=? AND key=? AND pos=?",
                              (server_id, key, pos)).fetchone()
            if cur and cur["updated_at"] >= c["updatedAt"]:
                skipped += 1
                continue
            con.execute("INSERT OR REPLACE INTO memories(server_id,key,pos,items_norm,raw,mc_version,updated_by,updated_at)"
                        " VALUES(?,?,?,?,?,?,?,?)",
                        (server_id, key, pos, json.dumps(c.get("items", [])),
                         json.dumps(c["raw"]) if c.get("raw") is not None else None,
                         c.get("mcVersion"), c.get("updatedBy"), c["updatedAt"]))
            con.execute("DELETE FROM tombstones WHERE server_id=? AND key=? AND pos=?", (server_id, key, pos))
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
    con.execute("DELETE FROM memories WHERE server_id=?", (server_id,))
    con.execute("DELETE FROM tombstones WHERE server_id=?", (server_id,))
    n = 0
    for key, positions in state.items():
        for pos, mem in positions.items():
            con.execute("INSERT INTO memories(server_id,key,pos,items_norm,raw,mc_version,updated_by,updated_at)"
                        " VALUES(?,?,?,?,?,?,?,?)",
                        (server_id, key, pos, json.dumps(mem.get("items", [])),
                         json.dumps(mem["raw"]) if mem.get("raw") is not None else None,
                         mem.get("mcVersion"), mem.get("updatedBy"), mem.get("updatedAt", "1970-01-01T00:00:00Z")))
            n += 1
    con.commit()
    return n

def prune_tombstones(con: sqlite3.Connection, ttl_days: int = 30) -> int:
    cur = con.execute("DELETE FROM tombstones WHERE deleted_at < strftime('%Y-%m-%dT%H:%M:%fZ','now', ?)",
                      (f"-{ttl_days} days",))
    con.commit()
    return cur.rowcount


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
    """Range gate: same dimension + within radius blocks (3D). Ender-style keys
    (no meaningful position) always pass; unknown/unparseable positions fail closed."""
    if is_ender_key(dim_key):
        return True
    if dim_key != player_dim:
        return False
    parsed = _parse_pos(pos)
    if parsed is None:
        return False
    x, y, z = parsed
    return (x - px) ** 2 + (y - py) ** 2 + (z - pz) ** 2 <= radius * radius


def select_pull(con: sqlite3.Connection, server_id: str, player_dim: str | None = None,
                px: int | None = None, py: int | None = None, pz: int | None = None,
                radius: int = 5000) -> tuple[list[dict], list[dict]]:
    """Gated pull: without a player position this is the legacy full pull (compat);
    with one, only same-dimension in-range entries plus ender keys are returned —
    including tombstones (positions leak too)."""
    gated = player_dim is not None and px is not None and py is not None and pz is not None
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

def should_quarantine_mass_delete(existing: int, delete_count: int,
                                  max_fraction: float = 0.20, max_count: int = 50,
                                  min_bank: int = 10) -> bool:
    """Guard: propagate deletes, but not mass wipes. Empty server never quarantines,
    and tiny banks (< min_bank) are exempt from the fraction rule so a new player
    breaking their only chests isn't flagged — the absolute count rule still applies."""
    if existing <= 0 or delete_count <= 0:
        return False
    if delete_count >= max_count:
        return True
    if existing < min_bank:
        return False
    return (delete_count / max(1, existing)) >= max_fraction

def is_empty_hash_push(full_hash: str, changes: list) -> bool:
    # Client sends sha256("[]")-style empty marker when it has nothing; server double-checks.
    # We treat any push with zero changes as potential hub-wipe and let caller decide via counts.
    return len(changes) == 0
