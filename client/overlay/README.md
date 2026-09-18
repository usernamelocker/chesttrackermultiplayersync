# Client overlay — now lives in `mod/`

The full buildable mod (QMSync 1.21.11 + CMSync changes) is vendored at repo-root
`mod/` — see `mod/CMSYNC.md`. This folder keeps the original patch-file copies for
reference; `mod/src/.../impl/cmsync/` is what ships.

## What the overlay changes vs upstream

* `impl/cmsync/` — sync client: sidecar settings, HTTP v2, async queue lane, sync
  manager (push deltas + pull merge, full NBT in `raw`), chat commands
  (`/cmsync status|stop`), item normalizer. Connect UI is the CMSync tab in the
  Memory Bank menu (token box masked).
* `impl/memory/EnderChestKeys.java` — per-player ender chest keys + migration.
* Recording: ender chest + hypixel personal keys namespaced per player UUID.
* `ChestTrackerScreen` — ender chest profiles (one icon + per-player buttons).
* `EditMemoryBankScreen` — CMSync tab (URL/token/connect/stop); QMSync tab kept
  for the website system, relabeled.
* `FilteringSettings.manualMode` defaults to false (auto-record).
* `MemoryBankAccessImpl` — runs the ender chest migration on load.

## Configure in-game

Memory Bank menu → CMSync tab (URL + token → Connect), `/cmsync status`,
`/cmsync stop`. `serverId` for the server `.env` comes from `/cmsync status`.

## Safety behavior (matches server)

* Only syncs on the bound `serverId` (hub joins ignored).
* Empty local bank + non-empty server → push skipped, pull still runs (no hub-wipe).
* Broken/emptied containers propagate as tombstones (mass breaks go through with one
  warning; an established bank reading empty holds pull-only — use wipe for fresh starts).
* Deletes propagate as tombstones (30d TTL); pulls are range-gated (5k blocks).
