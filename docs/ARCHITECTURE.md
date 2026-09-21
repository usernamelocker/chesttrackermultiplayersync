# Architecture

```
┌─ Client (1.21.11 + impl/cmsync) ─┐   ┌─ Client (26.1.2 + same overlay) ─┐
│ local bank (offline cache)        │   │ local bank                       │
│ CMSyncManager tick:               │   │ CMSyncManager tick:              │
│  snapshot + full NBT → POST /push │──▶│                                  │
│  GET /pull → LWW merge into bank  │◀──│                                  │
└───────────────────────────────────┘   └──────────────────────────────────┘
                              │ HTTP(S)
                              ▼
              ┌─ VPS: FastAPI + SQLite (WAL) ─┐
              │ memories (server,key,pos PK)  │  LWW on updatedAt (ties keep stored)
              │ tombstones (30d TTL)          │  single deletes propagate
              │ snapshots (every 15min, keep) │  mass deletes: snapshot + warn, apply
              │ GET /api/view → website/Discord│
              └───────────────────────────────┘
```

## Why this shape

* **No direct DB from clients** (no creds in jars, no JDBC on Fabric).
* **Local bank always works offline** — server is a merge layer, not a live dependency.
* **Per-container LWW** `(key,pos)` + `updatedAt` avoids full-bank clobber.
* **Cross-version:** merge/search/count on normalized `{id,count}`; full NBT rides
  along opaquely (`raw`) for same-version restores — see
  [`../protocol/normalization.md`](../protocol/normalization.md).
* Ender chests sync under per-player keys, never merged across players.

## Data flow

1. Player opens chest → provider → `MemoryKeyImpl.add(pos, memory)` with
   `realTimestamp=now` (nested NBT, e.g. shulker contents, kept in the stack).
2. `CMSyncManager.tick` (every `intervalSeconds`): ItemStack copies + detached
   `Memory` clones on the client thread → encode + hash + `POST /api/push` on the
   queue lane. Deletes detected against the last snapshot ride as tombstones.
3. Server: `serverId` (case-insensitive) + token check → hub-wipe guard (empty push
   vs non-empty store ignored) → mass-delete snapshot + warning → `apply_changes`
   LWW → periodic snapshot.
4. Client `GET /api/pull` (range-gated: same dimension + 5000 Overworld-equivalent
   blocks; Nether horizontal coordinates use the 1:8 scale, ender keys exempt)
   → same-version full-NBT restore, else names+counts fallback **stamped
   with the observation time** (never `now` — see normalization.md) → merge on
   client thread → search/render picks it up.
5. Website: `GET /api/view/{serverId}` aggregates normalized totals.

## Key files

* `protocol/PROTOCOL.md`, `protocol/normalization.md`
* `server/app.py`, `server/db.py`, `server/models.py`, `server/config.py`,
  `server/backup.py`, `server/smoke.py`
* `mod/src/client/java/red/jackf/chesttracker/impl/cmsync/*` (7 files),
  `impl/memory/EnderChestKeys.java`, Sync/CM Settings tabs in
  `impl/gui/screen/EditMemoryBankScreen.java`
