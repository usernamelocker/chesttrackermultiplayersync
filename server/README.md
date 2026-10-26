# CMSync server reference

One FastAPI/SQLite server supports Minecraft **26.2 (primary)**, 26.1.2, and
1.21.11 clients through protocol v2. Runtime and deployment files are identical
across the three version branches. Deploy from `refs/heads/26.2`, directory
`server/`, using [START-HERE.md](START-HERE.md) or [PORTAINER.md](PORTAINER.md).

## Run locally

```powershell
cd server
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
copy .env.example .env
python -m pytest test_api.py test_server.py test_portable_format.py test_cross_version.py
uvicorn app:app --host 127.0.0.1 --port 8000
```

Fill the local `.env` before running the service. Check `/health` on the port
you selected. The Docker deployment instead publishes host port 7000.
For a disposable live-server smoke test, run
`python smoke.py http://VPS-IP:7000 YOURTOKEN`; it creates and cleans up test data.

## Configuration

[stack.env.example](stack.env.example) documents every setting. Environment
variables take precedence over a local `.env` fallback.

- `EXPECTED_SERVER_ID` identifies the shared bank. `SERVER_ID_ALIASES` maps
  other addresses to the same bank; matching is case-insensitive.
- `SHARED_TOKEN` authenticates player sync. `WHITELIST_UUIDS` optionally restricts
  player UUIDs. `ADMIN_TOKEN` separately protects wipe and restore operations.
- `DB_PATH` is `/data/cmsync.db` in Docker. Keep the existing database volume.
- Snapshot defaults are 15 minutes and 96 retained snapshots. Large deletes
  may create additional recovery snapshots.
- `RANGE_BLOCKS=5000` is measured in Overworld-equivalent coordinates; Nether
  horizontal coordinates use the 1:8 portal scale.
- `FULL_WIPE_MIN_BANK=10` controls exact full-bank delete quarantine. Partial
  mass deletes are snapshotted and applied.
- `MAX_UPDATE_FUTURE_SECONDS=300` limits future observation timestamps;
  `TOMBSTONE_TTL_DAYS=30` controls deletion retention.

## HTTP endpoints

The authoritative contract is [PROTOCOL.md](../protocol/PROTOCOL.md).

| Endpoint | Purpose |
|---|---|
| `GET /health` | Service health, version, and configured server identity |
| `POST /api/handshake` | Authenticate identity and return the wipe generation |
| `POST /api/push` | Apply timestamp-ordered container deltas; quarantine suspected full-bank deletion |
| `GET /api/pull` | Incremental or range-gated memories, tombstones, owners, revision, and generation |
| `GET /api/pullWebPage` | Authenticated website read view without container/tombstone coordinates |
| `GET /api/view/{serverId}` | Public normalized aggregate counts |
| `GET /api/snapshots` | Admin-authenticated snapshot list |
| `POST /api/restore` | Admin-authenticated snapshot restore with generation advance |
| `POST /api/wipe` | Admin-authenticated, two-step confirmed wipe |
| `POST /api/sync` | Legacy v1 compatibility endpoint |

## Deployment and recovery

The Compose file builds only `server/`, maps host 7000 to container 8000, and
uses `cmsync-data` and `cmsync-backups` named volumes. No client jar belongs on
the sync VPS. Client version upgrades require no database migration.

Keep shared server fixes synchronized across all version branches. Read
[Backups](../docs/BACKUPS.md) before a future runtime upgrade and
[Testing](../docs/TESTING.md) for the validation matrix. The systemd and nginx
example files remain available for non-Docker deployments; adapt their paths
and ports to that deployment.
