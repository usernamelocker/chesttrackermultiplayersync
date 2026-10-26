# Backups (periodic saves)

Two layers; either alone recovers a wipe.

This is the shared server for 26.2 (primary), 26.1.2, and 1.21.11. A 26.2 client
upgrade does not change the database format or require new volumes. Keep the
existing data when changing the repository reference; see
[Portainer operations](../server/PORTAINER.md).

## 1. DB snapshots (in SQLite, automatic)

* Server auto-`take_snapshot(serverId)` at most every `SNAPSHOT_INTERVAL_MIN` (default 15min) on push.
* Keeps last `SNAPSHOT_KEEP` (default 96 = 24h at 15min).
* Partial mass deletes snapshot **before applying** and log a warning. Exact
  full-bank delete pushes against established banks are instead quarantined
  without being applied; the snapshot is retained for recovery.
* Wipes do not delete snapshots.
* List: `GET /api/snapshots?serverId=...`
* Restore (admin token): `POST /api/restore {"serverId": "...", "snapshotId": 12}` →
  clients pick it up within ~10s.

## 2. Filesystem `.backup` (cron, survives SQLite corruption)

Partial mass deletes are snapshotted before being applied. An exact full-bank
delete push is snapshotted and returned as `QUARANTINED` without being applied.
For an accidental full-bank push, restore its snapshot; for an intentional wipe,
use `/cmsync wipealldata`, which is not blocked by the push guard.

```cron
*/15 * * * * /opt/cmsync/.venv/bin/python /opt/cmsync/backup.py
```

* Uses online-safe `sqlite3.backup()`, keeps last 14 files in `server/backups/`.
* Tombstones older than `TOMBSTONE_TTL_DAYS` (default 30) are pruned on snapshot ticks.
* Docker: snapshots live in the `cmsync-data` volume; run
  `python /app/backup.py` in the container console for file backups
  (`cmsync-backups` volume). Export `cmsync-data` before big tests.

## Restore drill (do once)

1. `GET /api/snapshots?serverId=<id>` → pick id.
2. `POST /api/restore` with `X-CMSync-Token: <ADMIN_TOKEN>`.
3. In-game `/cmsync status` → `containers` back, search works.
4. If DB file itself is corrupt: stop service, copy newest `backups/cmsync-*.db` over `data/cmsync.db`, start.
