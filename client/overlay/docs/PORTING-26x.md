# Porting overlay: 1.21.11 → 26.1.2 / 26.2

Overlay targets QMSync `1.21.11` APIs. Same files compile on 26.x with at most these tweaks
(checkout the matching QMSync/upstream branch first, then copy `impl/cmsync/*` unchanged and fix imports).

## Known risk points

| Area | 1.21.11 | 26.x watch-out |
|---|---|---|
| Game version string | `Minecraft.getInstance().getGameVersion()` | if removed, use `SharedConstants.getCurrentVersion().name()` |
| Item registry | `BuiltInRegistries.ITEM.getKey/getValue` | same, but new items may be unknown on 1.21.11 → `fromNormList` already skips unknown |
| `Identifier.parse` | `Identifier.parse(s)` | older branches used `Identifier.tryParse`; keep `parse` on 26.x, fallback on 1.21.11 if needed |
| `Memory` ctor | `(items,name,otherPositions,container,loaded,world,real,entityId,entityUuid)` | verify order; `Memory.CODEC` may gain fields — overlay never parses `raw`, only normalized |
| `Coordinate` | `Coordinate.getCurrent().{id,userFriendlyName}` | stable (JackFredLib); `serverId` strings differ per server, not per version — that's the point |
| Fabric API | `ClientTickEvents`, `ClientCommandManager` | same; bump `fabric-loader`/`fabric-api` per `gradle.properties` |
| Java | 21 (CI uses 25 to build) | keep `gradlew.bat check build` green on both branches |

## Process per version branch

1. `git checkout 26.2` (or `26.1.2`) in your QMSync fork; `copy .env.example .env` + tokens.
2. Copy `impl/cmsync/*` in, add the 2-line hook in `ChestTracker.onInitializeClient()`.
3. `gradlew.bat check build`; fix only import-level breaks, never protocol shape.
4. Cross-test: 1.21.11 client + 26.x client vs same `serverId` (see `docs/TESTING.md`).
5. Publish per-version jars (Modrinth supports multiple game versions per release).

## Rule

Protocol (`protocol/PROTOCOL.md` + `schema.json`) is version-frozen at v2. If a 26.x codec forces a change,
bump to v3 and keep server backward-compat with v2 (like `/api/sync` v1 compat in `server/app.py`).
