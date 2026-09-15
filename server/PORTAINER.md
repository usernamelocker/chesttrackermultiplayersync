# Portainer deploy (recommended after local test passes)

You have Portainer on Ubuntu — use a Stack, no ssh needed.

## Option A: Portainer Stack (easiest)

1. Portainer → Stacks → Add stack → name `cmsync` → Web editor, paste this
   (same as `docker-compose.yml`, image built from GitHub or local upload):

```yaml
services:
  cmsync:
    image: cmsync:latest
    restart: unless-stopped
    ports:
      - "8000:8000"
    environment:
      EXPECTED_SERVER_ID: multiplayer/mc_YOURSERVER_IP
      WHITELIST_UUIDS: uuid1,uuid2,uuid3
      SHARED_TOKEN: ""
      ADMIN_TOKEN: ""
      DB_PATH: /data/cmsync.db
    volumes:
      - cmsync-data:/data
volumes:
  cmsync-data:
```

2. If Portainer can build: set Build method to Repository, point at your repo `server/` folder.
   If not: `docker build -t cmsync:latest server/` locally, `docker save`, import image in Portainer → Images → Import.
3. Deploy → check Logs → `GET /health` via `http://VPS-IP:8000/health`.
4. Reverse proxy: Portainer → Networks, or your existing nginx/Traefik → route `cmsync.yourdomain.com` → `cmsync:8000`.
   Until domain is ready, players can test `http://VPS-IP:8000` (http ok for test, https for real).

## Option B: plain docker compose on VPS

```
cd /opt/cmsync/server
cp .env.example .env   # fill EXPECTED_SERVER_ID + WHITELIST_UUIDS
docker compose up -d --build
curl localhost:8000/health
```

## Backups in Docker

* Snapshots auto-write into SQLite (`/data/cmsync.db` volume `cmsync-data` — survives restarts).
* Filesystem `.backup`: exec `python /app/backup.py` via Portainer Console, or add cron on host mounting the volume.
* Portainer → Volumes → `cmsync-data` → backup via volume export before big tests.

## Why local first?

`python test_server.py` + `uvicorn` on your PC catches whitelist/serverId mistakes in seconds.
Portainer deploy after that is just copy-paste env vars.
