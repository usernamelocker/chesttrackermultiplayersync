# ChestTracker Multiplayer Sync (CMSync)

CMSync shares ChestTracker memories between Minecraft clients through one
self-hosted FastAPI/SQLite server. **Minecraft Java 26.2 Fabric is the primary
version**, and `26.2` is the GitHub default branch. Older 26.1.2 and 1.21.11
clients can keep using the same protocol-v2 server.

## Downloads and installation

**[Download the 26.2 jar](https://github.com/usernamelocker/chesttrackermultiplayersync/releases/download/v2.9.0/chesttracker-2.9.0-mc26.2.jar)**
· [Release notes and all assets](https://github.com/usernamelocker/chesttrackermultiplayersync/releases/tag/v2.9.0)
· [SHA256SUMS.txt](https://github.com/usernamelocker/chesttrackermultiplayersync/releases/download/v2.9.0/SHA256SUMS.txt)

For 26.2, install Java 25, Fabric Loader **0.19.5 or newer**, and the **26.2**
versions of Fabric API and YACL. Put the CMSync jar in your client's `mods/`
folder. Where Is It, JackFredLib, and Searchables are bundled. Optional mods
need their own compatible 26.2 releases. Back up local memory banks first.

Older players must use their matching older jars. The latest release also
includes unchanged 26.1.2 and 1.21.11 compatibility jars from v1.0.8;
see [Downloads and releases](docs/RELEASES.md) for exact filenames and links.
Do not install a `-sources.jar` in Minecraft.

## Start here

| Task | Guide |
|---|---|
| Connect a player | [Tokens and access](docs/TOKENS.md) |
| Deploy the shared server | [Server setup](server/START-HERE.md) |
| Maintain an existing VPS | [Portainer operations](server/PORTAINER.md) |
| Build a client | [Client build guide](mod/CMSYNC.md) |
| Download or verify a release | [Downloads and releases](docs/RELEASES.md) |
| Diagnose or test sync | [Testing](docs/TESTING.md) |
| Recover data | [Backups](docs/BACKUPS.md) |

## Version branches

| Branch | Minecraft | Build Java | Status |
|---|---|---|---|
| [`26.2`](https://github.com/usernamelocker/chesttrackermultiplayersync/tree/26.2) | 26.2 | 25 | Default branch and primary development target |
| [`26.1.2`](https://github.com/usernamelocker/chesttrackermultiplayersync/tree/26.1.2) | 26.1.2 | 25 | Older compatible client |
| [`1.21.11`](https://github.com/usernamelocker/chesttrackermultiplayersync/tree/1.21.11) | 1.21.11 | 21 | Older compatible client |

There is no separate `main` branch. All three branches contain identical
`server/` files. The branch selects the client source target; the server is
shared and does not need a separate deployment for each Minecraft version.

## Existing VPS deployment

Use repository reference **`refs/heads/26.2`**, compose path
**`server/docker-compose.yml`**, and the existing `/server` build context.
If Portainer still explicitly references `main`, update that reference before
its next Git pull or redeploy. A folder path and a branch reference are separate
settings; selecting `/server` alone does not select the Git branch.

The server runtime and Docker configuration preserve the previous production
behavior. Keep the stack name, ports, tokens, server IDs, and named volumes
unchanged. Installing the 26.2 client requires no server rebuild or database
migration. See [Portainer operations](server/PORTAINER.md).

## Synchronization and compatibility

- Local memories remain available offline. Failed pushes remain retryable.
- Containers are keyed by `(serverId, key, pos)`. Observations and tombstones
  share last-observation-wins ordering; stale updates cannot undo newer deletes.
- Incremental pulls use durable revisions. Spatial pulls include nearby
  Overworld/Nether containers with the 1:8 portal scale; other dimensions are
  isolated and ender-chest keys are exempt from spatial gating.
- Normalized item IDs/counts support search. Native data and portable
  `cmsync.item` v1 envelopes preserve richer components where peers support them.
  Older games cannot display unknown items, and older binaries retain their
  original fallback behavior.
- Suspected exact full-bank delete pushes are snapshotted and quarantined.
  `/cmsync wipealldata` remains the explicit, confirmed admin wipe path.

See [Architecture](docs/ARCHITECTURE.md), [Protocol v2](protocol/PROTOCOL.md),
[Item format](protocol/normalization.md), and the
[validation record](docs/TESTING.md#262-port-validation-2026-10-27).

## Repository layout

```text
mod/                Client source for the checked-out version branch.
server/             Shared server runtime, tests, and deployment files.
protocol/           HTTP contract and portable item format.
docs/               Downloads, operations, architecture, and validation.
client/overlay/     Retired history; do not use as an implementation.
```

## License

Upstream LGPL-3.0 terms are preserved; see [mod/LICENSE](mod/LICENSE).
