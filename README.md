# ChestTracker Multiplayer Sync (CMSync)

CMSync shares ChestTracker memory state between players through a self-hosted
FastAPI/SQLite server. It supports shared search, per-player ender-chest
profiles, cross-version item data, and a website/Discord read view.

Supported client versions are Minecraft `1.21.11` and `26.1.2`.

## Start here

| You are... | Read |
|---|---|
| Playing | Download the matching jar from [GitHub Releases](https://github.com/usernamelocker/chesttrackermultiplayersync/releases), then follow [Tokens & access](docs/TOKENS.md). |
| Hosting | Follow [server/START-HERE.md](server/START-HERE.md). Use [Portainer operations](server/PORTAINER.md) for redeploys and diagnosis. |
| Building | Read [mod/CMSYNC.md](mod/CMSYNC.md) for branches, Java versions, and build commands. |
| Debugging | Read [docs/TESTING.md](docs/TESTING.md) and inspect `chesttracker/cmsync.log`. |

## How it works

- Each bank is keyed by `serverId`; aliases can be canonicalized by the server.
- Synchronization is per container `(key, pos)` using last-observation-wins
  timestamps. Memory rows and tombstones share one logical LWW stream.
- Pushes include normalized `{id,count}` data for search and aggregation, native
  data for same-version fidelity, and a portable component envelope for
  cross-version decoding and foreign-component preservation. See
  [protocol/normalization.md](protocol/normalization.md).
- Pulls are incremental using durable revisions and are range-gated. While a
  player is in the Overworld or Nether, nearby containers from both dimensions
  are eligible; Nether horizontal coordinates use the 1:8 portal scale. Other
  dimensions remain isolated and ender-style keys are exempt from spatial gating.
- Partial mass deletes are snapshotted and applied. A delete-only push covering
  every stored container in an established bank is snapshotted and returned as
  `QUARANTINED` instead of being applied. Empty pushes against a non-empty bank
  are also ignored as hub-wipe protection.
- `/cmsync wipealldata` is the intentional admin wipe path. It clears server
  state, advances the generation, and causes offline clients to clear stale
  local state when they return.

## Repository layout

```text
mod/                Buildable mod source. main = 1.21.11; 26.1.2 = 26.1.2.
server/             FastAPI + SQLite authoritative store and deployment files.
protocol/           HTTP contract and cross-version item format.
docs/               Architecture, access, testing, and backup guides.
client/overlay/      Retired patch copies; mod/ is the source of truth.
releases/            Local ignored artifacts; published assets live on GitHub.
```

## Documentation index

- Protocol: [PROTOCOL.md](protocol/PROTOCOL.md), [normalization.md](protocol/normalization.md)
- Server: [START-HERE.md](server/START-HERE.md), [PORTAINER.md](server/PORTAINER.md),
  [README.md](server/README.md), [stack.env.example](server/stack.env.example)
- Mod: [CMSYNC.md](mod/CMSYNC.md), [upstream mod README](mod/README.md)
- Concepts: [ARCHITECTURE.md](docs/ARCHITECTURE.md), [TOKENS.md](docs/TOKENS.md),
  [TESTING.md](docs/TESTING.md), [BACKUPS.md](docs/BACKUPS.md)

## Branches and releases

- `main` builds Minecraft `1.21.11`.
- `26.1.2` builds Minecraft `26.1.2`.
- Releases contain `chesttracker-*-mc1.21.11.jar` and
  `chesttracker-*-mc26.1.2.jar`, plus `SHA256SUMS.txt`.
- Redeploy the server stack whenever files under `server/` change. Client jars
  and server code are released together for compatibility, but server-only
  fixes do not require new client logic.

## License

The mod links against ChestTracker upstream under LGPL-3.0; see
[mod/LICENSE](mod/LICENSE).
