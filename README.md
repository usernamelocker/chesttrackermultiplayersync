# ChestTracker Multiplayer Sync (CMSync)

Shared ChestTracker state for a private group: **in-game shared search across players,
per-player ender chest profiles, plus a website/Discord read view** — backed by a small
self-hosted server. Cross-version: **`1.21.11` and `26.1.2` clients sync together**
against the same `serverId`.

## Start here (pick one)

| You are… | Do this |
|---|---|
| **Playing** (just want the mod) | Download the jar for your MC version from [GitHub Releases](https://github.com/usernamelocker/chesttrackermultiplayersync/releases) (`cmsync-1.21.11-*` or `cmsync-26.1.2-*`). Needs Fabric API + YACL. Then [connect in-game](docs/TOKENS.md). |
| **Hosting the server** | Follow [`server/START-HERE.md`](server/START-HERE.md) (Portainer, ~5 min). Reference: [`server/PORTAINER.md`](server/PORTAINER.md), [`server/README.md`](server/README.md). |
| **Building the mod** | [`mod/CMSYNC.md`](mod/CMSYNC.md) — branches, prereqs (Java 21 vs 25), build commands. |
| **Debugging sync** | [`docs/TOKENS.md`](docs/TOKENS.md) (access modes) + `cmsync.log` in `<game>/chesttracker/` (every cycle, handshake, wipe and transport error, with reasons). |

## How it works (60 seconds)

- Each bank syncs to a server-side store keyed by `serverId` (`multiplayer/<address>`,
  case-insensitive, aliases merge — see [`protocol/PROTOCOL.md`](protocol/PROTOCOL.md)).
- Merge is **per-container** `(key, pos)`, last-observation-wins on `updatedAt`.
  Broken/emptied containers propagate as **tombstones**; pulls are **range-gated**
  across nearby Overworld/Nether containers (5000 Overworld-equivalent blocks;
  625 Nether blocks horizontally by default); other dimensions remain isolated.
  Ender-style keys always pass.
- Pushes carry full-fidelity NBT (`raw`) for same-version restores plus a
  names+counts view for search and cross-version fallback. Fallback reconstructions
  keep the observation time, so lossy data can never outrank genuine records
  (see [`protocol/normalization.md`](protocol/normalization.md)).
- Mass deletes apply straight through (server snapshots first + warns); only a
  fully-empty push against a non-empty server is ignored (hub-wipe protection).
- `/cmsync wipealldata` (admin token, two-step) zeroes a server and bumps a
  generation counter so offline clients clear on return. Snapshots are kept.

## Layout

```
chesttrackermultiplayersync/
  mod/                Buildable mod source. main branch = 1.21.11, 26.1.2 branch = 26.1.2.
                      The CMSync overlay lives in mod/src/.../impl/cmsync/ (see mod/CMSYNC.md).
  server/             FastAPI + SQLite authoritative store (app.py, db.py, models.py,
                      backup.py). Deploys via Portainer from server/docker-compose.yml.
  protocol/           HTTP contract (PROTOCOL.md) + item normalization rules (normalization.md).
  docs/               ARCHITECTURE.md, TOKENS.md, TESTING.md, BACKUPS.md.
  client/overlay/     Retired: early patch copies. mod/ is the source of truth.
  releases/           Local jar copies (gitignored). Real releases are on GitHub.
```

## Docs index

- Protocol: [`protocol/PROTOCOL.md`](protocol/PROTOCOL.md), [`protocol/normalization.md`](protocol/normalization.md)
- Server: [`server/START-HERE.md`](server/START-HERE.md) (setup), [`server/PORTAINER.md`](server/PORTAINER.md) (operations), [`server/README.md`](server/README.md) (local dev), [`server/stack.env.example`](server/stack.env.example) (env reference)
- Mod: [`mod/CMSYNC.md`](mod/CMSYNC.md) (build + in-game use), [`mod/README.md`](mod/README.md) (upstream mod manual)
- Concepts: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), [`docs/TOKENS.md`](docs/TOKENS.md), [`docs/TESTING.md`](docs/TESTING.md), [`docs/BACKUPS.md`](docs/BACKUPS.md)

## Branches & releases

- `main` → MC 1.21.11 · `26.1.2` → MC 26.1.2. The server (`server/`) serves all versions.
- Releases are per version (`cmsync-1.21.11-N`, `cmsync-26.1.2-N`), each with the exact
  commit hash in the jar name. Server and protocol stay backward compatible —
  update clients freely, redeploy the server stack when server files change.

## License

The mod links against ChestTracker upstream: **LGPL-3.0**, see [`mod/LICENSE`](mod/LICENSE) —
keep any mod fork public and preserve credits.
