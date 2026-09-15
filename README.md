# ChestTracker Multiplayer Sync

Shared ChestTracker state for a private group: **in-game shared search + website/Discord view**, backed by a small VPS server.

* **Base mod:** `KarolexDev/QMSync` branch `1.21.11` (itself a fork of `ponuing/ChestTracker`).
* **Cross-version goal:** clients on `1.21.11`, `26.1.2`, `26.2` sync together against the same `serverId`.
* **Server:** `server/` — Python FastAPI + SQLite (WAL). No direct DB from clients.
* **Protocol:** `protocol/PROTOCOL.md` — v2 (extends QMSync v1 with push/pull deltas, tombstones, version tags).
* **Client patch:** `client/overlay/` — drop-in `impl/cmsync/` package for the 1.21.11 base. Keeps local `JsonBackend` as offline cache.

> License: this repo's own code is MIT (see `LICENSE` when added). The mod patch links against ChestTracker/QMSync which is **LGPL-3.0** — keep any mod fork public and preserve credits.

## Layout

```
chesttrackermultiplayersync/
  protocol/           shared HTTP contract v2 + JSON schemas + item normalization rules
  server/             FastAPI + SQLite authoritative merge store + backups
  client/overlay/     Java overlay (impl/cmsync/*) to apply onto QMSync 1.21.11 base
  docs/               architecture / testing / backups / porting notes
```

## Quickstart

1. Read `protocol/PROTOCOL.md`.
2. Host server: see `server/README.md` (`pip install -r requirements.txt`, `uvicorn app:app`, nginx+TLS).
3. Apply client patch: see `client/overlay/README.md` (copy `impl/cmsync/*` into your QMSync checkout, wire 3 hooks).
4. Test with 2 accounts: `docs/TESTING.md`.

## Key safety rules (agreed)

* Merge **per-container** `(memoryKey,pos)`, last-write-wins on `updatedAt`. Never full-bank overwrite.
* Propagate single deletes via **tombstones**, but **quarantine mass deletes** (>20% or >50 containers) + keep snapshot.
* Only sync on allowlisted `serverId` (hub joins must not wipe). Empty-bank pushes are ignored.
* Periodic server snapshots + SQLite `.backup` cron. See `docs/BACKUPS.md`.
