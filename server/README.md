# CMSync server (FastAPI + SQLite)

Authoritative merge store. Clients keep local `JsonBackend` cache; this server merges **per-container deltas**.

## Run locally

```powershell
cd server
python -m venv .venv; .\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
copy .env.example .env   # set EXPECTED_SERVER_ID + WHITELIST_UUIDS
python test_server.py    # no server needed, tests merge/guard/snapshot
uvicorn app:app --host 127.0.0.1 --port 8000
curl http://127.0.0.1:8000/health
```

## Configure

`.env`: `EXPECTED_SERVER_ID` (e.g. `multiplayer/mc_play_myserver_com` — get exact value from
`/cmsync status` in-game), `WHITELIST_UUIDS` (comma UUIDs), optional `SHARED_TOKEN` /
`ADMIN_TOKEN`, `DB_PATH`, `SNAPSHOT_INTERVAL_MIN=15`, `MAX_DELETE_FRACTION=0.20`,
`MAX_DELETE_COUNT=50`.

## VPS (Ubuntu)

Local first (fast loop): `python test_server.py`, then `uvicorn` + test with 1 client.
Then deploy — pick one:

* **Portainer (you have it): see `PORTAINER.md`** — paste stack, set `EXPECTED_SERVER_ID` +
  `WHITELIST_UUIDS`, deploy, check `/health`. No ssh needed.
* systemd/nginx classic:
  1. `apt install python3-venv nginx certbot`, copy `server/` to `/opt/cmsync`, venv + `pip install`.
  2. `systemd`: copy `chesttracker-sync.service` to `/etc/systemd/system/`, `systemctl enable --now`.
  3. `nginx`: adapt `nginx.example.conf`, `certbot --nginx`, force HTTPS.
  4. Backups: `crontab -e` → `*/15 * * * * /opt/cmsync/.venv/bin/python /opt/cmsync/backup.py`.
* Docker compose: `cp .env.example .env`, `docker compose up -d --build`.

## Endpoints

See `../protocol/PROTOCOL.md`. v1 `POST /api/sync` still accepted for old QMSync-only clients.
