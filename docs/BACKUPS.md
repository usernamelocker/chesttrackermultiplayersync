# Backups (periodic saves)

Two layers; either alone recovers a wipe.

## 1. DB snapshots (in SQLite, automatic)

* Server auto-`take_snapshot(serverId)` at most every `SNAPSHOT_INTERVAL_MIN` (default 15min) on push.
* Keeps last `SNAPSHOT_KEEP` (default 96 = 24h at 15min).
* Mass deletes snapshot **before applying** (they go through + warning log, nothing
  is rejected — the snapshot is the undo).
* Wipes do not delete snapshots.
* List: `GET /api/snapshots?serverId=...`
* Restore (admin token): `POST /api/restore {"serverId": "...", "snapshotId": 12}` →
  clients pick it up within ~10s.

## 2. Filesystem `.backup` (cron, survives SQLite corruption)

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
