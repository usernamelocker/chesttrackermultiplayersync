# Progress (updated 2026-09-17)

## Builds

* 1.21.11: prereleases 1-6 on `main` (`mod/`), latest `cmsync-1.21.11-6`.
* 26.1.2: branch `26.1.2` (ponuing base 2.8.4 + same overlay, 3 API adaptations),
  [prerelease cmsync-26.1.2-1](https://github.com/usernamelocker/chesttrackermultiplayersync/releases/tag/cmsync-26.1.2-1).
  Needs Java 25 + Fabric API + YACL on 26.1.2. Same VPS serves both versions.

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
* Prerelease 2 (`cmsync-1.21.11-2`, `releases/chesttracker-2.8.1+1.21.11+cmsync2.jar`):
  live-test fixes — push rejections surface the server reason in chat with a 5-min quiet
  cooldown (pulls continue, no more fail/re-established spam); bare `/cmsync` prints help;
  GUI Connect handshakes before saving; server logs exact 422 field+body.
* Prerelease 3 (`cmsync-1.21.11-3`, `releases/chesttracker-2.8.1+1.21.11+cmsync3.jar`):
  URL tolerance — GUI box and `/cmsync connect` accept `host:port` without `http://`.
  Server fingerprints POST senders (UA + body length) to trace empty-body 422s.
* Prerelease 4 (`cmsync-1.21.11-4`, `releases/chesttracker-2.8.1+1.21.11+cmsync4.jar`):
  ROOT CAUSE of all 422s — forced HTTP/1.1. Java's default HTTP_2 sends an h2c upgrade
  the server drops bodies on (every POST arrived empty; proven by raw-socket repro:
  same bytes + `Upgrade: h2c` → identical 422, plain → 200 SYNCED). INSTALL THIS ONE.
* Prerelease 5 (`cmsync-1.21.11-5`, `releases/chesttracker-2.8.1+1.21.11+cmsync5.jar`):
  FULL NBT SYNC — pushes carry the native Memory record (enchantments, item names,
  shulker/nested contents, container block+name) plus user overrides, inside `raw`.
  Same-MC-version peers restore everything; other versions get names+counts.
  No VPS change needed (server already stores raw opaquely). INSTALL THIS ONE.
* Prerelease 6 (`cmsync-1.21.11-6`): Manual Mode OFF by default (auto-record);
  CMSync tab in Memory Bank menu (URL + masked token + connect + stop), QMSync tab
  relabeled Website; per-player ender chests (one icon + per-player profiles, own
  migration); server 5k range gate on pulls (+tombstones) with ender exemption;
  player-name owners for profiles. INSTALL THIS ONE (needs VPS redeploy too).
* Prerelease 7 (`cmsync-1.21.11-7`): ender dropdown with player-head profiles
  (hover names, auto-close); quieter failures (5s-60s backoff, HTTP code + duration
  in `/cmsync status`, slow-push warnings); timeouts 10s/30s. INSTALL THIS ONE.
* Native tab inside EditMemoryBankScreen (v1 uses standalone `/cmsync gui` screen instead — simpler, version-proof).
* 26.1.2/26.2 branch builds (same overlay, see porting doc).
* Live Portainer deploy (repo is on GitHub private; needs your 2 env vars + Deploy click).

## Your 3 inputs needed to go live

1. `/cmsync status` → exact `serverId` → Portainer env `EXPECTED_SERVER_ID`
2. Friend UUIDs → Portainer env `WHITELIST_UUIDS`
3. `http://VPS-IP:7000/health` → `{"ok":true}`, then connect from game
