# Porting overlay: 1.21.11 → 26.1.2 / 26.2

## Status

* **26.1.2: DONE** — branch `26.1.2` in this repo (ponuing base 2.8.4 + overlay),
  release `cmsync-26.1.2-1`. Base is ponuing, not QMSync (QMSync has no 26.1 line).
* 26.2: not started (QMSync has a `26.2` branch; same process as below).

## What 26.1.2 actually needed (recorded 2026-09-17)

Base: `ponuing/ChestTracker@26.1.2` (no QMSync package — website sync absent, fine).
Same overlay files + per-player ender keys + menu tab + profiles, with 3 API fixes:

| Area | 1.21.11 | 26.1.2 reality |
|---|---|---|
| Client commands | `ClientCommandManager` | **renamed to `ClientCommands`** (same `literal`/`argument` helpers) |
| Chat messages | `player.displayClientMessage(msg, false)` | **removed** → `player.sendSystemMessage(msg)` |
| Misc | — | `collectProfiles` arg slip (own bug, not drift); `addFormatter`/`SharedConstants.getCurrentVersion()`/`ResourceKey.identifier()` all fine |
| Java | 21 builds it | **needs JDK 25** (`release version 25 not supported` otherwise — set `JAVA_HOME`) |
| Remap | Loom 1.14 emits intermediary jars | 26.x pipeline ships MojMap-named jars (verified identical form to upstream's official release) |
| Deps | `.env` via dotenv map | ponuing base reads `System.getenv` — `.env` support backported (same dotenv snippet) |

## Process per version branch

1. Clone the matching upstream branch shallow; `copy .env.example .env` + tokens.
2. Copy `impl/cmsync/*` + `EnderChestKeys` in, add the 2-line hook in `ChestTracker.onInitializeClient()`.
3. Port the upstream-file edits (recording keys, viewer profiles, menu tab) against that
   version's structure; `gradlew.bat check build`; fix only import-level breaks, never protocol shape.
4. Cross-test: 1.21.11 client + 26.x client vs same `serverId` (see `docs/TESTING.md`).
5. Publish per-version jars (Modrinth supports multiple game versions per release).

## Rule

Protocol (`protocol/PROTOCOL.md` + `schema.json`) is version-frozen at v2. If a 26.x codec forces a change,
bump to v3 and keep server backward-compat with v2 (like `/api/sync` v1 compat in `server/app.py`).
