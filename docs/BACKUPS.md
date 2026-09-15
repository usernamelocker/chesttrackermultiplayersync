# Backups (periodic saves — you asked for this)

Two layers; either alone recovers a wipe.

## 1. DB snapshots (in SQLite, automatic)

* Server auto-`take_snapshot(serverId)` at most every `SNAPSHOT_INTERVAL_MIN` (default 15min) on push.
* Keeps last `SNAPSHOT_KEEP` (default 96 = 24h at 15min).
* Mass-delete quarantine also snapshots **before** rejecting.
* List: `GET /api/snapshots?serverId=...`
* Restore (admin token): `POST /api/restore {"serverId": "...", "snapshotId": 12}` → clients pull within ~10s.

## 2. Filesystem `.backup` (cron, survives SQLite corruption)

```cron
*/15 * * * * /opt/cmsync/.venv/bin/python /opt/cmsync/backup.py
```

* Uses online-safe `sqlite3.backup()`, keeps last 14 files in `server/backups/`.
* Also prunes `tombstones` older than `TOMBSTONE_TTL_DAYS` (default 30).

## Restore drill (do once)

1. `GET /api/snapshots?serverId=<id>` → pick id.
2. `POST /api/restore` with `X-CMSync-Token: <ADMIN_TOKEN>`.
3. In-game `/cmsync status` → `containers` back, search works.
4. If DB file itself is corrupt: stop service, copy newest `backups/cmsync-*.db` over `data/cmsync.db`, start.
