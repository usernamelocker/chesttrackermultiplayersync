# CMSync mod — build & use (26.1.2 branch)

This branch (`26.1.2`) is ponuing/ChestTracker `26.1.2` with the CMSync
multiplayer overlay applied. The `main` branch is the same overlay on
QMSync `1.21.11` — see [Branches](#branches) for the (small) differences.
The server in repo-root `server/` serves all versions.

## Our changes vs upstream

`mod/src/client/java/red/jackf/chesttracker/`:

* `impl/cmsync/` (7 files) — the sync client:
  `CMSyncSettings` (per-bank sidecar + admin token), `CMSyncHttp` (protocol v2,
  pinned HTTP/1.1, token header), `CMSyncQueue` (single background lane, coalesced),
  `CMSyncManager` (snapshot → push → pull-merge, delete propagation, wipe generations),
  `CMSyncCommand` (`/cmsync status|stop|wipealldata`), `ItemNormalizer`
  (names+counts view + hashes), `CMSyncLog` (`cmsync.log` file logging).
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

Requires Fabric API + YACL in `mods/`.

## Build (this branch)

Prereqs: **Java 25** (set `JAVA_HOME` to a JDK 25 — building on plain Java 21 fails
with `release version 25 not supported`), plus a GitHub classic token with
`read:packages` (JackFredLib/WhereIsIt come from GitHub Packages).

```powershell
cd mod
copy .env.example .env   # fill GITHUB_ACTOR (your username) + GITHUB_TOKEN
.\gradlew.bat build -x test
# jar: mod\build\libs\chesttracker-*.jar  (name contains the commit hash)
```

`.env` is gitignored — never commit it. No unit tests in the mod (`check` only);
server tests live in `server/` (`pytest test_api.py test_server.py`).

## Branches

| | `main` (this file) | `26.1.2` |
|---|---|---|
| Base | QMSync `1.21.11` | ponuing/ChestTracker `26.1.2` |
| Java to build | 21 (default toolchain) | **25** (`JAVA_HOME` → a JDK 25, else `release version 25 not supported`) |
| `sendChat` | `player.displayClientMessage(msg, false)` | `player.sendSystemMessage(msg)` |
| Commands registration | `ClientCommandManager` | `ClientCommands` |
| Litematica mixin anchor | `initGui` TAIL (same shape both branches) | `initGui` TAIL (same shape both branches) |
| Releases | `cmsync-1.21.11-N` | `cmsync-26.1.2-N` |

Porting rule: keep the overlay logic identical; only adapt call sites the compiler
rejects. Protocol (`protocol/PROTOCOL.md`) is version-frozen at v2.

## License

LGPL-3.0 (see `LICENSE`) — upstream terms preserved. Keep this mod source public
if you distribute built jars.
