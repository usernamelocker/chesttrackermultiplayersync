# CMSync client for Minecraft 1.21.11

This is the older **Minecraft 1.21.11** Fabric client branch. Minecraft **26.2**
is the primary release and default branch. The maintained version branches are
`26.2`, `26.1.2`, and `1.21.11`; there is no separate `main` branch.

## Downloads

Use [Downloads and releases](../docs/RELEASES.md) for the matching 1.21.11 jar,
source information, and checksums. The older compatibility jars in v2.9.0 are
unchanged assets from v1.0.8; the new 2.9.0 implementation targets 26.2 only.
Install the Fabric API and YACL versions appropriate for your older game.
Do not install the 26.2 jar or a `-sources.jar` in this game profile.

## Build this older client

Set `JAVA_HOME` to **JDK 21**. This older build still resolves
JackFredLib/Where Is It through GitHub Packages and needs a GitHub token with
`read:packages`. The public-dependency build improvements and six game-registry
codec tests are on `26.2`.

```powershell
cd mod
copy .env.example .env   # fill GITHUB_ACTOR and GITHUB_TOKEN locally
.\gradlew.bat build --no-daemon
```

Keep `.env` out of Git. Build output is under `mod/build/libs/`; select the jar
without `-sources`. See [Testing](../docs/TESTING.md) for shared server checks
and the manual cross-version matrix.

## Shared server and in-game setup

All three branches contain identical `server/` files. One protocol-v2 server
serves all clients. Portainer uses `refs/heads/26.2`, directory `server/`, and
compose path `server/docker-compose.yml`. Keep existing settings and volumes.
A client upgrade requires no server-code or database migration.

Configure the Memory Bank menu's **Sync** tab with the server URL and shared
token. The separate admin token belongs in **CM Settings**, not in Sync.
`/cmsync status` reports state; `/cmsync stop` disconnects. The confirmed
`/cmsync wipealldata` command intentionally wipes shared data and requires admin
access. Ordinary suspected full-bank delete pushes remain quarantined.

Older games cannot display unknown newer items. Existing release binaries
retain their original portable-data/fallback behavior. See
[Protocol v2](../protocol/PROTOCOL.md),
[item format](../protocol/normalization.md),
[access settings](../docs/TOKENS.md), and
[Portainer operations](../server/PORTAINER.md).

## References and license

[Repository overview](../README.md) · [Upstream features](README.md) ·
[LGPL license](LICENSE)