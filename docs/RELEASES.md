# Downloads and releases

## Primary release: CMSync 2.9.0 for Minecraft 26.2

[GitHub release v2.9.0](https://github.com/usernamelocker/chesttrackermultiplayersync/releases/tag/v2.9.0)
is the primary release. Its source tag targets the `26.2` branch.

| File | Purpose |
|---|---|
| [chesttracker-2.9.0-mc26.2.jar](https://github.com/usernamelocker/chesttrackermultiplayersync/releases/download/v2.9.0/chesttracker-2.9.0-mc26.2.jar) | Install this in a Minecraft 26.2 client's `mods/` folder |
| [chesttracker-2.9.0-mc26.2-sources.jar](https://github.com/usernamelocker/chesttrackermultiplayersync/releases/download/v2.9.0/chesttracker-2.9.0-mc26.2-sources.jar) | Source archive for developers; not an installable mod |
| [chesttracker-1.0.8-mc26.1.2.jar](https://github.com/usernamelocker/chesttrackermultiplayersync/releases/download/v2.9.0/chesttracker-1.0.8-mc26.1.2.jar) | Unchanged older 26.1.2 compatibility jar from v1.0.8 |
| [chesttracker-1.0.8-mc1.21.11.jar](https://github.com/usernamelocker/chesttrackermultiplayersync/releases/download/v2.9.0/chesttracker-1.0.8-mc1.21.11.jar) | Unchanged older 1.21.11 compatibility jar from v1.0.8 |
| [SHA256SUMS.txt](https://github.com/usernamelocker/chesttrackermultiplayersync/releases/download/v2.9.0/SHA256SUMS.txt) | SHA-256 hashes for every jar in this release |

The new 2.9.0 client implementation and codec fixes apply to 26.2. Older jars
keep their original names, bytes, and capabilities; they have not been rebuilt
or relabeled as 2.9.0. Previous releases and their tags remain available in
[release history](https://github.com/usernamelocker/chesttrackermultiplayersync/releases).

## Requirements

For 26.2: Java 25, Fabric Loader 0.19.5+, Fabric API for 26.2, and YACL for 26.2.
Where Is It, JackFredLib, and Searchables are embedded. Optional integrations
must match 26.2. For an older game, follow the requirements of its matching
older release. One jar cannot be used across all game versions.

Back up `<game>/chesttracker/` before replacing a client jar. Keep the same sync
URL, shared token, and server ID. Never replace the shared token with the admin
token in the Sync tab. See [Tokens and access](TOKENS.md).

## Verify a download

Download `SHA256SUMS.txt` alongside the jars. On Windows:

```powershell
Get-FileHash .\chesttracker-2.9.0-mc26.2.jar -Algorithm SHA256
```

Compare the hash with the entry for that exact filename. On Linux:

```sh
sha256sum --check SHA256SUMS.txt --ignore-missing
```

## Server deployment

One shared protocol-v2 server serves all three Minecraft versions. The 26.2
client release does not require a server-code or database migration. Portainer
should use `refs/heads/26.2` and `server/docker-compose.yml`; keep the existing
stack, settings, ports, and volumes. See [Portainer operations](../server/PORTAINER.md).

## Build and publication

Normal builds from `mod/` use `.\gradlew.bat build --no-daemon` and produce
development jars under `mod/build/libs/`. For the 26.2 release build:

```powershell
cd mod
$env:NEW_TAG = 'v2.9.0'
.\gradlew.bat build --no-daemon
Remove-Item Env:NEW_TAG
```

`NEW_TAG` selects the embedded mod version; running `build` does not publish a
release or create a Git tag. Publish only the verified main jar, source jar,
explicitly labeled older compatibility jars, and regenerated `SHA256SUMS.txt`.
The GitHub release tag must identify the reviewed source commit on `26.2`.
Do not overwrite older assets, tags, or checksums.

Validation and remaining live-game checks are recorded in [TESTING.md](TESTING.md).
