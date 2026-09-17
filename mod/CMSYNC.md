# CMSync mod — 26.1.2 branch (buildable)

Full mod source: `ponuing/ChestTracker` branch `26.1.2` with the CMSync multiplayer
overlay applied. Clone this repo, check out the `26.1.2` branch, and build.

## Our changes vs upstream (all of them)

* `src/client/java/red/jackf/chesttracker/impl/cmsync/` — 6 files: sidecar settings,
  HTTP v2 client (pinned HTTP/1.1), async queue lane, sync manager (push deltas +
  pull merge, full NBT in `raw`, 5k range-gated pulls), chat commands
  (`/cmsync status|stop`), item normalizer.
* `src/client/java/red/jackf/chesttracker/impl/memory/EnderChestKeys.java` — per-player
  ender chest keys + one-time migration of legacy shared data.
* Recording: ender chest + hypixel personal keys namespaced per player UUID.
* `ChestTrackerScreen` — ender chest profiles (one icon + per-player buttons).
* `EditMemoryBankScreen` — CMSync tab (URL + masked token + connect + stop).
* `MemoryBankAccessImpl` — runs the ender chest migration on load.
* `build.gradle.kts` — GitHub Packages auth via `.env` fallback (same as dotenv setup).
* 26.1.2 adaptations: `ClientCommands` (was `ClientCommandManager`),
  `sendSystemMessage` (was `displayClientMessage`).

## Build

Prereqs: **Java 25** (set `JAVA_HOME` to a JDK 25 — `./gradlew` on plain Java 21 fails
with `release version 25 not supported`), plus a GitHub classic token with
`read:packages` (JackFredLib/WhereIsIt come from GitHub Packages).

```powershell
cd mod
copy .env.example .env   # fill GITHUB_ACTOR (your username) + GITHUB_TOKEN
.\gradlew.bat build      # real env vars also work instead of .env
# jar: mod\build\libs\chesttracker-*.jar (needs Fabric API + YACL in mods/)
```

`.env` is gitignored — never commit it.

## In-game

Memory Bank menu → CMSync tab (URL + token → Connect), `/cmsync status`,
`/cmsync stop`. Server setup: repo-root `server/START-HERE.md` (same server
serves all game versions; 1.21.11 and 26.x clients sync together).

## License

LGPL-3.0 (see `LICENSE`) — upstream terms preserved. Keep this mod source public
if you distribute built jars.
