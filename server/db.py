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
"""

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

def should_quarantine_mass_delete(existing: int, delete_count: int,
                                  max_fraction: float = 0.20, max_count: int = 50) -> bool:
    """Guard: propagate deletes, but not mass wipes. Empty server never quarantines."""
    if existing <= 0 or delete_count <= 0:
        return False
    return delete_count >= max_count or (delete_count / max(1, existing)) >= max_fraction

def is_empty_hash_push(full_hash: str, changes: list) -> bool:
    # Client sends sha256("[]")-style empty marker when it has nothing; server double-checks.
    # We treat any push with zero changes as potential hub-wipe and let caller decide via counts.
    return len(changes) == 0
