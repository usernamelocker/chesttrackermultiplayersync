# CMSync mod — build & use (Minecraft 26.2)

The primary client now targets Minecraft **26.2**, using ponuing/ChestTracker's
26.2 game API migration with CMSync preserved. `26.2` is the default branch;
`26.1.2` and `1.21.11` contain the older clients. Existing older releases remain
compatible with the shared server. See
[Branches](#branches) for build targets. The server in repo-root `server/`
serves all three versions through protocol v2.

Installable jars, source archives, and checksums are published on
[GitHub Releases](https://github.com/usernamelocker/chesttrackermultiplayersync/releases/tag/v2.9.0).
Use the [download guide](../docs/RELEASES.md) to select the right asset.

## Our changes vs upstream

`mod/src/client/java/red/jackf/chesttracker/`:

* `impl/cmsync/` — the sync client:
  `CMSyncSettings` (per-bank sidecar + admin token), `CMSyncHttp` (protocol v2,
  pinned HTTP/1.1, token header), `CMSyncQueue` (single background lane, coalesced),
  `CMSyncManager` (snapshot → push → pull-merge, delete propagation, wipe generations),
  `CMSyncCommand` (`/cmsync status|stop|wipealldata`), `ItemNormalizer`
  (names+counts view + hashes), `PortableItemCodec` (component envelopes and
  foreign data preservation), `CMSyncLog` (`cmsync.log` file logging).
* `impl/memory/EnderChestKeys.java` — per-player ender chest keys
  (`ender_chest/<uuid>` etc.), one-time migration of legacy shared data, legacy-key hiding.
* `impl/gui/screen/ChestTrackerScreen.java` — ender chest profiles (own head icon first,
  per-player buttons, hover names).
* `impl/gui/screen/EditMemoryBankScreen.java` — **Sync** tab (URL + masked token +
  Connect + Pause/Resume + Stop) and **CM Settings** tab (interval, ender chest,
  container names, chat notifications, admin token).
* `mixins/compat/litematica/GuiMaterialListMixin.java` — version-proof Search button
  (anchored at `initGui` TAIL, no fragile field shadows).
* `MemoryBankAccessImpl` — runs the ender chest migration on bank load.

## In-game

The server may return `QUARANTINED` for a suspected exact full-bank delete push;
existing clients keep their local state and report the held wipe. The explicit
`/cmsync wipealldata` command remains allowed.

Memory Bank menu (`` ` `` key → pencil icon):

* **Sync tab** — server URL (`https://host:port` or `host:port`), shared token
  (masked, stored on this PC only), Connect / Pause / Resume / Stop + state label.
* **CM Settings tab** — sync interval (2–3600s), Sync ender chest, Sync container
  names, Chat notifications, Admin token (for `/cmsync wipealldata` only).
* `/cmsync status` — connection, server/bank ids, last sync, last detail, local counts.
* `/cmsync stop` — disconnect + forget URL/token. `/cmsync wipealldata` (+ `confirm`) —
  two-step shared-database wipe (needs the admin token), snapshots kept as backup.
* Every cycle, handshake, wipe, stall and transport error (with its message) is
  appended to `<game>/chesttracker/cmsync.log` (capped ~512KB, rotated).

Requires Java 25, Fabric Loader 0.19.5 or newer, and 26.2 releases of Fabric API
and YACL in `mods/`. The jar declares `~26.2`; use the matching older jar on
1.21.11 or 26.1.2. Where Is It and Searchables are bundled.

## Build (this branch)

Prereqs: **Java 25** (set `JAVA_HOME` to a JDK 25 — building on plain Java 21 fails
with `release version 25 not supported`) and internet access for public Maven
dependencies. No GitHub package token is required to build.

```powershell
cd mod
.\gradlew.bat build --no-daemon
# jar: mod\build\libs\chesttracker-*.jar  (name contains the commit hash)
```

Release credentials may be supplied through a gitignored `.env`; never commit
it. The build runs portable codec tests
against the game registry. Server checks and the in-game compatibility matrix
are documented in [TESTING.md](../docs/TESTING.md). Litematica and MaLiLib are
resolved as versioned compile-only dependencies; old vendored jars are not used.

Release links and GitHub publishing target this CMSync repository. Modrinth and
CurseForge publishing require explicit `modrinth_project_id` or
`curseforge_project_id` Gradle properties plus the corresponding token; upstream
ChestTracker project IDs are not used for CMSync uploads.

For an isolated game startup check, run
`.\gradlew.bat runClient -Prun_directory=build/smoke-client --no-daemon`.

## Branches

| Target | Source | Java | Status |
|---|---|---|---|
| 26.2 | `26.2` (default branch) | 25 | Primary client |
| 26.1.2 | `26.1.2` | 25 | Older compatible client |
| 1.21.11 | `1.21.11` | 21 | Older compatible client |

The server runtime and Docker configuration are the same across these branches.
Portainer uses `refs/heads/26.2` and `server/docker-compose.yml`; the client
target does not select a different sync server implementation. There is no
separate `main` branch. Keep shared server fixes synchronized across branches.

Do not copy a newer jar into an older game installation. Cross-version sync
compatibility does not make game binaries interchangeable. Portable envelopes
remain `cmsync.item` v1 inside protocol v2, and no server behavior change is
needed for this port. Very old clients without portable support keep their
existing normalized fallback limitations.

Porting rule: keep the overlay logic identical; only adapt call sites the compiler
rejects. Protocol (`protocol/PROTOCOL.md`) is version-frozen at v2.

## License

LGPL-3.0 (see `LICENSE`) — upstream terms preserved. Keep this mod source public
if you distribute built jars.
