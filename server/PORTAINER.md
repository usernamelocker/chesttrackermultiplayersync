# Portainer operations

Setup path is [`START-HERE.md`](START-HERE.md) (Stack from Repository + env table).
This file is the operations companion.

## Stack shape

* Build method: **Repository** → repo URL, branch `main`, compose path
  `server/docker-compose.yml`, authentication ON (private repo).
* Compose builds `./Dockerfile`, publishes `7000→8000`, data in named volumes
  `cmsync-data` (SQLite) and `cmsync-backups` (file backups) — both survive updates.
* Env vars (same names as `stack.env.example`): `EXPECTED_SERVER_ID`,
  `SERVER_ID_ALIASES`, `WHITELIST_UUIDS` (empty in token mode), `SHARED_TOKEN`,
  `ADMIN_TOKEN` (**different** password; `$$`-escape every `$`), plus tuning
  (`SNAPSHOT_*`, `MAX_DELETE_*`, `RANGE_BLOCKS`, `TOMBSTONE_TTL_DAYS`).

## Update / redeploy

The full environment reference is [`stack.env.example`](stack.env.example),
including `FULL_WIPE_MIN_BANK`, which protects established banks from accidental
full-bank delete pushes. Partial deletes are not blocked.

Stacks → stack → **Update the stack WITH rebuild** (toggle it on). A plain
container restart keeps the old code — rebuild pulls the new code from GitHub.
Watch Containers → `cmsync` → **Logs** for `Uvicorn running`, then
`http://VPS-IP:7000/health` → `{"ok":true,...}`.

## Diagnose from logs

* `handshake DENIED <reason> player=<uuid> got=<id> want=<id>` — exact mismatch,
  copy this line when reporting connect problems (usually token or serverId).
* `pull DENIED ...` — same for pulls.
* `MASS DELETE <server>: N deletes vs M stored by <uuid> (snapshot N)` — a big
  break/empty wave applied; snapshot id is the undo point (`/api/restore`).
* `WIPE <server> by <uuid>` — someone ran the two-step wipe.
* `422 ... errors=[...]` — a client sent a malformed body (field + body logged).

## Backups in Docker

* Snapshots auto-write into SQLite (`cmsync-data` volume — survives restarts).
* Filesystem `.backup`: Containers → `cmsync` → Console →
  `python /app/backup.py` (writes into `cmsync-backups` volume).
* Before big tests: Volumes → export `cmsync-data`.
* Details + restore drill: [`../docs/BACKUPS.md`](../docs/BACKUPS.md).

## Local first

`python -m pytest test_api.py test_server.py` in `server/` catches
serverId/whitelist/merge mistakes in seconds, no deploy needed.
