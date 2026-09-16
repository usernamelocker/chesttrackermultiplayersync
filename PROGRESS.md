# Progress (updated 2026-09-15)

## Done

* **Server** (`server/`): FastAPI + SQLite, `handshake/push/pull/view/snapshots/restore/health` + v1 compat.
  Tests pass: `python test_server.py` → lww/guard/snapshot ok; `python test_api.py` → full e2e ok.
* **Merge safety**: per-container LWW, tombstones, empty-push ignore (hub-wipe), mass-delete quarantine
  server + local hold, auto snapshots every 15min + `backup.py`.
* **Client overlay** (`client/overlay/.../cmsync/`, 7 files): Settings sidecar, Http v2, **Queue lane**
  (1 thread, coalesce, client thread only copies), Manager (push+pull), Command
  (`connect/gui/stop/status`), **Screen** (`/cmsync gui` URL+token boxes — answers token UI question).
* **Deploy**: `Dockerfile`, `docker-compose.yml` (Portainer Repository build), `stack.env.example`,
  `START-HERE.md` (exact clicks), systemd + nginx examples.
* **Docs**: `protocol/` (v2 + schema + cross-version rule), `docs/` (architecture/testing/backups/tokens),
  `PORTING-26x.md`, `APPLY.md`, `scripts/apply-overlay.ps1`.

## Not yet (needs full QMSync checkout + MC Gradle)

* ~~Compile jar~~ DONE 2026-09-16: `chesttracker-2.8.1+1.21.11+dev-662ea6a.jar` (3.5 MB, BUILD SUCCESSFUL,
  all 7 `impl/cmsync` classes verified inside) → GitHub prerelease `cmsync-1.21.11-1`
  + local copy in `releases/` (gitignored). Requires Fabric API + YACL on 1.21.11.
* Native tab inside EditMemoryBankScreen (v1 uses standalone `/cmsync gui` screen instead — simpler, version-proof).
* 26.1.2/26.2 branch builds (same overlay, see porting doc).
* Live Portainer deploy (repo is on GitHub private; needs your 2 env vars + Deploy click).

## Your 3 inputs needed to go live

1. `/cmsync status` → exact `serverId` → Portainer env `EXPECTED_SERVER_ID`
2. Friend UUIDs → Portainer env `WHITELIST_UUIDS`
3. `http://VPS-IP:7000/health` → `{"ok":true}`, then connect from game
