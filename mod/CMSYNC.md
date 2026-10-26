# CMSync mod — Minecraft 26.1.2

This branch builds the older Minecraft **26.1.2** Fabric client with **Java 25**.
The primary client and GitHub default branch are `26.2`; `main` follows that
latest code and remains the Portainer deployment reference.

| Branch | Minecraft | Java | Role |
|---|---|---|---|
| `26.2` | 26.2 | 25 | Default branch and primary client |
| `26.1.2` | 26.1.2 | 25 | Older compatible client |
| `1.21.11` | 1.21.11 | 21 | Older compatible client |
| `main` | 26.2 | 25 | Latest-code alias for the existing VPS deployment |

## Shared server and compatibility

All branches contain the same server runtime and Docker configuration. One
protocol-v2 server serves all supported game versions. Keep Portainer on
`refs/heads/main`; keep its stack name, compose path, ports, environment settings,
and named volumes unchanged. A client upgrade does not require a database
migration or VPS rebuild. Apply future shared server fixes to every branch.

Install the jar and Fabric API/YACL releases that match your Minecraft version.
The game binaries are not interchangeable. Portable `cmsync.item` v1 envelopes
preserve component data understood by a peer; unknown items cannot be displayed
by older games. Existing release binaries retain their original capabilities.
See the [protocol](../protocol/PROTOCOL.md),
[item format](../protocol/normalization.md), and
[compatibility checks](../docs/TESTING.md).

## Build this older client

Set `JAVA_HOME` to a JDK 25. This older build still resolves
JackFredLib/Where Is It through GitHub Packages and requires a GitHub token
with `read:packages`. The public-dependency build improvements are on `26.2`.

```powershell
cd mod
copy .env.example .env   # fill GITHUB_ACTOR and GITHUB_TOKEN locally
.\gradlew.bat build --no-daemon
```

Do not commit `.env`. Output jars are under `mod/build/libs/`; install the jar
without `-sources`. The new game-registry codec tests are on `26.2`/`main`;
server checks and the manual multiplayer matrix are in
[TESTING.md](../docs/TESTING.md).

## In-game use

Open the Memory Bank menu with the grave key, then configure the **Sync** tab
with the shared server URL and token. **CM Settings** contains the sync interval,
ender-chest and name options, notifications, and the separate admin token.
`/cmsync status` reports sync state. `/cmsync stop` disconnects and forgets the
connection. `/cmsync wipealldata` is an intentional, confirmed admin wipe;
ordinary suspected full-bank deletes remain quarantined by the shared server.

See the [repository README](../README.md),
[Portainer guide](../server/PORTAINER.md), and
[upstream feature overview](README.md).

## License

The upstream LGPL-3.0 terms are preserved; see [LICENSE](LICENSE).