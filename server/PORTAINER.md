# Portainer deploy — notes

Standard path is `START-HERE.md` (Stack from Repository, env table in the UI).
This file is the advanced companion.

## Canonical Portainer setup (recap)

* Build method: **Repository** → `https://github.com/usernamelocker/chesttrackermultiplayersync`,
  branch `main`, compose path `server/docker-compose.yml`, authentication ON (private repo).
* Only two env vars matter at first: `EXPECTED_SERVER_ID`, `WHITELIST_UUIDS`.
  Everything else has safe defaults baked into `docker-compose.yml`.
* Data lives in named volumes `cmsync-data` (SQLite) and `cmsync-backups` (file backups) —
  both survive stack updates. Visible under Portainer → Volumes.

## Option B: plain docker compose on the VPS (needs ssh)

```
cd /opt/cmsync/server
cp .env.example .env   # fill EXPECTED_SERVER_ID + WHITELIST_UUIDS
docker compose up -d --build
curl localhost:7000/health
```

## Backups in Docker

* Snapshots auto-write into SQLite (`cmsync-data` volume — survives restarts).
* Filesystem `.backup`: Portainer → Containers → `cmsync` → Console →
  `python /app/backup.py` (writes into `cmsync-backups` volume).
* Before big tests: Portainer → Volumes → export `cmsync-data`.

## Local first

`python test_server.py` + `python test_api.py` in `server/` catch whitelist/serverId
mistakes in seconds, no deploy needed.
