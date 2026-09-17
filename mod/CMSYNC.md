# CMSync mod (buildable)

This folder is the **full mod source**: QMSync branch `1.21.11` (itself a fork of
`ponuing/ChestTracker`) with the CMSync multiplayer overlay applied. Clone this repo
and build — nothing else to copy or patch.

## Our changes vs upstream (all of them)

* `src/client/java/red/jackf/chesttracker/impl/cmsync/` — 7 files: settings sidecar,
  HTTP v2 client, async queue lane, sync manager (push deltas + pull merge, full NBT
  in `raw`), chat commands, setup screen, item normalizer.
* `src/client/java/red/jackf/chesttracker/impl/ChestTracker.java` — 2 added lines in
  `onInitializeClient()`: `CMSyncManager.INSTANCE.setup()` + `CMSyncCommand.register()`.
* `server/`, `protocol/`, `docs/` (repo root) — the sync server, shared contract, guides.
* Dropped only `doc/class_diagram.png` (regenerable via `doc/*.py`).

## Build

Prereqs: Java 21+, plus a GitHub classic token with `read:packages` (the mod pulls
`JackFredLib`/`WhereIsIt` from GitHub Packages — even public packages need auth).

```powershell
cd mod
copy .env.example .env   # fill GITHUB_ACTOR (your username) + GITHUB_TOKEN
.\gradlew.bat build
# jar: mod\build\libs\chesttracker-*.jar (needs Fabric API + YACL in mods/)
```

Gradle also reads real environment variables (`GITHUB_ACTOR`/`GITHUB_TOKEN`), so CI
can inject them instead of a `.env` file (which is gitignored — never commit it).

## In-game

`/cmsync gui` (URL + token boxes) or `/cmsync connect <url> <token>`,
`/cmsync status`, `/cmsync stop`. Server setup: repo-root `server/START-HERE.md`.

## License

LGPL-3.0 (see `LICENSE`) — upstream terms preserved. Keep this mod source public
if you distribute built jars.
