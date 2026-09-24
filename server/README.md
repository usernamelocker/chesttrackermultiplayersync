# CMSync server (FastAPI + SQLite)

Authoritative merge store. Clients keep a local cache; this server merges
**per-container deltas** (last-observation-wins) and serves pulls, snapshots and
a website/Discord read view. Serves all MC versions at once. Production path is
Portainer — see [`START-HERE.md`](START-HERE.md).

## Run locally

```powershell
cd server
python -m venv .venv; .\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
copy .env.example .env   # set EXPECTED_SERVER_ID + SHARED_TOKEN (see below)
   python -m pytest test_api.py test_server.py   # no server needed
uvicorn app:app --host 127.0.0.1 --port 8000
curl http://127.0.0.1:8000/health
```

Live smoke test against a deployed server (stdlib only):
`python smoke.py http://VPS-IP:7000 YOURTOKEN` — handshake → push → pull → view →
delete, leaves nothing behind.

## Configure

`.env` (or Portainer env table — same names; reference: `stack.env.example`):

* `EXPECTED_SERVER_ID` (e.g. `multiplayer/fabriccraft_net` — get the exact value
  from `/cmsync status` in-game). Compared **case-insensitively**; extra join
  addresses go in `SERVER_ID_ALIASES` and merge into one bank.
* Access, pick one: token mode (`WHITELIST_UUIDS` empty + `SHARED_TOKEN` set —
  everyone with the password syncs) or UUID mode (`WHITELIST_UUIDS` set, token
  optional extra). `ADMIN_TOKEN` (different password!) guards `/api/wipe` and
  `/api/restore`; ⚠️ escape `$` as `$$` in compose/Portainer values.
* Tuning: `DB_PATH`, `SNAPSHOT_INTERVAL_MIN=15`, `SNAPSHOT_KEEP=96`,
  `MAX_DELETE_FRACTION=0.20`, `MAX_DELETE_COUNT=50`, `RANGE_BLOCKS=5000`,
  `MIN_QUARANTINE_BANK=10`, `FULL_WIPE_MIN_BANK=10`,
  `MAX_UPDATE_FUTURE_SECONDS=300`, `TOMBSTONE_TTL_DAYS=30`. `RANGE_BLOCKS`
  is Overworld-equivalent; Nether horizontal coordinates use the 1:8 portal
  scale (so the default is 625 Nether blocks).

## Endpoints

Full contract: [`../protocol/PROTOCOL.md`](../protocol/PROTOCOL.md).

* `POST /api/handshake` → `{SYNCED + generation | ACCESS_DENIED + reason}`
* `POST /api/push` → applies deltas (LWW, ties keep stored) → `{SYNCED + counts}`;
  an exact full-bank delete is snapshotted and returned as `QUARANTINED`
* `GET /api/pull?serverId=&playerUuid=&px=&py=&pz=&dim=` → range-gated changes,
* `GET /api/snapshots?serverId=` → admin-token-protected snapshot metadata,
  tombstones, ender-chest `owners`, generation
* `POST /api/wipe` (admin, two-step challenge) → `{WIPED + generation}`
* `POST /api/restore` (admin), `GET /api/snapshots`, `GET /api/view/{serverId}`
  (auth-free aggregates), `GET /health`
* `POST /api/sync` — v1 compat for old QMSync-only clients.

## Deploy options

* **Portainer (primary):** [`START-HERE.md`](START-HERE.md) + [`PORTAINER.md`](PORTAINER.md).
  Compose: `docker-compose.yml` (builds `./Dockerfile`, publishes `7000→8000`,
  volumes `cmsync-data` + `cmsync-backups`).
* Plain docker compose (needs ssh): `cp .env.example .env`, `docker compose up -d --build`.
* systemd/nginx classic: `chesttracker-sync.service` + `nginx.example.conf` +
  certbot; backups via cron → `*/15 * * * * /opt/cmsync/.venv/bin/python /opt/cmsync/backup.py`.
