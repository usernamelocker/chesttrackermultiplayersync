# ChestTracker Multiplayer Sync (CMSync)

CMSync shares ChestTracker memory state between players through a self-hosted
FastAPI/SQLite server. It supports shared search, per-player ender-chest
profiles, cross-version item data, and a website/Discord read view.

Minecraft **26.2 (Fabric, Java 25)** is the primary target for new builds.
Older `1.21.11` and `26.1.2` releases can keep using the same sync server and
protocol v2. Install a jar built for your Minecraft version; a 26.2 jar cannot
run on an older game. Back up your local memory banks before upgrading.

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
mod/                Buildable client source; this update targets 26.2.
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

- `codex/minecraft-26.2` contains the primary Minecraft `26.2` client update.
- `main` builds Minecraft `1.21.11`.
- `26.1.2` builds Minecraft `26.1.2`.
- This update builds Minecraft `26.2`; existing branches retain the older clients.
- New releases should identify 26.2 as primary and include a 26.2 jar and
  `SHA256SUMS.txt`. Keep older compatible jars available, clearly labeled by
  Minecraft version. The build output is `mod/build/libs/chesttracker-*.jar`;
  its filename includes the Minecraft version. Use the jar without `-sources`.
- Validation results and remaining in-game checks are recorded in
  [TESTING.md](docs/TESTING.md#262-port-validation-2026-10-27).
- Required external mods: Fabric API and YACL for **26.2**. Where Is It and
  Searchables are bundled. Optional integrations need their own 26.2 releases.
- Redeploy the server stack when server runtime or deployment files change;
  documentation and test changes alone do not need a redeploy. Server-only
  fixes do not require new client logic.

### VPS deployment for the 26.2 client update

Keep Portainer on its existing server branch (`main` in the setup guide), with
the same compose path, environment variables, and named data volumes. Updating
the client to 26.2 does not require a server rebuild, database migration, or
switching the VPS to the client update branch. This port keeps protocol v2.
The older client branches and existing release downloads remain available.

The 26.2 branch was based on `26.1.2`, whose server history already differs from
`main`. Do not merge that branch into the deployed branch merely to install
the new client. Review any future server changes separately. See
[Portainer upgrade guidance](server/PORTAINER.md#upgrading-clients-to-minecraft-262).

Compatibility means shared item search, observations, tombstones, overrides,
and wipe generations across releases. A client can only display items and
components its game understands. Portable data preserves foreign values when
supported by the receiving release; older jars that predate the portable format
can only offer their existing fallback behavior. See the
[three-version test checklist](docs/TESTING.md#cross-version-12111--2612--262).

## License

The mod links against ChestTracker upstream under LGPL-3.0; see
[mod/LICENSE](mod/LICENSE).
