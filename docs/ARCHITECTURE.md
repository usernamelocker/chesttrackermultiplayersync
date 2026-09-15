# Architecture

```
┌─ Client (QMSync 1.21.11 + impl/cmsync) ─┐   ┌─ Client (26.x + same overlay) ─┐
│ JsonBackend (local .json cache, offline) │   │ JsonBackend (local cache)       │
│ CMSyncManager tick:                      │   │ CMSyncManager tick:             │
│  snapshot norm {id,count} → POST /push   │──▶│                               │
│  GET /pull → LWW merge into MemoryBank   │◀──│                               │
└──────────────────────────────────────────┘   └───────────────────────────────┘
                              │ HTTPS
                              ▼
              ┌─ VPS: FastAPI + SQLite (WAL) ─┐
              │ memories (server,key,pos PK)  │  LWW on updatedAt
              │ tombstones (30d TTL)          │  single deletes propagate
              │ snapshots (every 15min, keep) │  mass-delete quarantine
              │ GET /api/view → website/Discord│
              └───────────────────────────────┘
```

## Why this shape

* **No direct DB from clients** (no creds in jars, no JDBC on Fabric).
* **Local cache always wins offline** — server is merge layer, not live dependency.
* **Per-container LWW** `(key,pos)` + `updatedAt` (= `Memory.realTimestamp`) avoids full-bank clobber.
* **Cross-version:** merge key is normalized `{id,count}`; `raw` codec bytes are opaque per writer version.
* **QMSync reuse:** same tick/whitelist/serverId model, extended to bidirectional v2. Disable QMSync upload
  when CMSync active for same bank to avoid double traffic.

## Data flow

1. Player opens chest → vanilla provider → `MemoryKeyImpl.add(pos, memory)` → `touch(loadedTime, gameTime)` + `realTimestamp=now`.
2. `CMSyncManager.tick` (every `intervalSeconds`): normalized snapshot → `sha256` dirty check → `POST /api/push`.
3. Server: whitelist + `serverId` check → empty-push ignore → mass-delete quarantine check → `apply_changes` LWW → periodic snapshot.
4. Client `GET /api/pull` → `bank.addMemory/removeMemory` on client thread → normal search/render picks it up.
5. Website: `GET /api/view/{serverId}` aggregates normalized totals.

## Key files

* `protocol/PROTOCOL.md`, `protocol/schema.json`, `protocol/normalization.md`
* `server/app.py`, `server/db.py`, `server/models.py`, `server/backup.py`
* `client/overlay/src/.../cmsync/*`
