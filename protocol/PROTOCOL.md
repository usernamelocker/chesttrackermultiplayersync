# CMSync Protocol v2 (extends QMSync v1)

Base: `QMSync/doc/qmsync-api.md` protocol v1 (`POST /api/handshake`, `POST /api/sync` full snapshot).
v2 keeps v1 working, adds **delta push / pull merge** so multiple players share one bank in-game
and across MC versions (`1.21.11`, `26.1.2`, `26.2`).

## Identity (every request)

```json
{
  "protocolVersion": 2,
  "playerUuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
  "playerName": "Steve",
  "serverId": "multiplayer/mc_hypixel_net",
  "serverName": "Multiplayer: Hypixel",
  "mcVersion": "1.21.11",
  "modVersion": "qmsync-1.21.11+cmsync.1"
}
```

* `serverId` is `Coordinate.id()`. For multiplayer: `"multiplayer/" + sanitize(address)`,
  where `sanitize` replaces every `. : / "` and vanilla illegal filename char with `_`
  (JackFredLib `Sanitizer`, verified against source). Examples: `mc.hypixel.net` →
  `multiplayer/mc_hypixel_net`; `123.45.67.89:25565` → `multiplayer/123_45_67_89_25565`.
  The address is whatever each player typed in their server list, so the port counts:
  `play.example.com` and `play.example.com:25565` are DIFFERENT ids — all players must
  type the address identically. Singleplayer/LAN/realms use `singleplayer/<world>`,
  `lan/<motd>`, `realms/<hex>` instead.
  Proxy networks (same server, several addresses): list the extra ids in
  `SERVER_ID_ALIASES` and the server canonicalizes them into `EXPECTED_SERVER_ID`,
  so all addresses share one bank instead of splitting.
  Server compares this against `EXPECTED_SERVER_ID`. Hub/lobby connections use a different `serverId`
  and must be rejected/ignored by config.
* `mcVersion` + `modVersion` drive cross-version normalization (see `normalization.md`).
* Auth v2 MVP: UUID whitelist + optional per-player token header `X-CMSync-Token`.
  There are no sessions; every request re-checks `serverId` + whitelist.

## Endpoints

### `POST <base>/api/handshake`

Same as v1 + version fields. Responses: `{status: SYNCED|ACCESS_DENIED}` (also honors 401/403).
Client stores base URL per bank on `SYNCED` only.

### `POST <base>/api/push` (delta, preferred)

```json
{
  "identity...": "...",
  "baseHash": "sha256-of-last-pulled-merged-state-or-null",
  "fullHash": "sha256-of-sender-full-normalized-view",
  "changes": [
    {
      "key": "minecraft:overworld",
      "pos": "102,64,-33",
      "deleted": false,
      "updatedAt": "2026-09-15T18:00:00Z",
      "updatedBy": "uuid",
      "mcVersion": "1.21.11",
      "items": [{"id": "minecraft:iron_ingot", "count": 64, "componentsDigest": "…"}],
      "raw": {"v": 2, "mc": "1.21.11", "memory": {"items": [...full NBT...], "name": {...}},
              "override": {"customName": "...", "manualMode": "..."}}
    }
  ]
}
```

* One entry per container `(key,pos)`. `deleted=true` creates a tombstone.
* Server applies per-entry LWW on `updatedAt`; stale entries ignored.
* **Mass-delete guard:** if a single push deletes > `MAX_DELETE_FRACTION` (default 20%) or
  > `MAX_DELETE_COUNT` (default 50), server quarantines it: stores snapshot, returns
  `{status: QUARANTINED, reason}` and does NOT apply deletes. Client shows chat warning.
* Empty `changes` with `fullHash` matching server = no-op (used for keepalive/hash check).
* **Empty-bank rule:** if `changes` is empty AND `fullHash` == hash(empty) while server has >0 containers,
  server ignores (protects hub-wipe). Client must also skip push in that case.

### `GET <base>/api/pull?serverId=…&since=…&px=…&py=…&pz=…&dim=…`

Returns changes since opaque cursor:

```json
{
  "status": "SYNCED",
  "serverTime": "2026-09-15T18:01:00Z",
  "cursor": "12345",
  "changes": [ "...same shape as push..." ],
  "tombstones": [{"key": "...", "pos": "...", "deletedAt": "..."}],
  "owners": {"<uuid>": "<playerName>"}
}
```

* **Range gate:** with player position (`px,py,pz` + dimension `dim`), only same-dimension
  containers within `RANGE_BLOCKS` (default 5000) are returned — plus ender-style keys,
  which have no position and always pass. Tombstones are gated the same way (positions
  leak too). Without position params the pull is ungated (old-client compatible).
* `owners` maps ender-chest key owners to last-seen names (powers profile labels).
* Ender chests sync under per-player keys (`chesttracker:ender_chest/<uuid>` etc.),
  so teammates' ender chests never merge — the server treats keys opaquely.

Client merges into loaded `MemoryBankImpl` on client thread (see overlay `CMSyncManager`).

### Snapshots / restore (backups)

* Server auto-snapshots full merged state every `SNAPSHOT_INTERVAL_MIN` (default 15) into `snapshots` table
  + keeps filesystem `.backup` via cron (`server/backup.py`).
* `GET /api/snapshots?serverId=` lists `{id, createdAt, containers}`.
* `POST /api/restore {serverId, snapshotId}` (admin token) restores.
* `GET /health` → `{ok:true, time, containers}`.
* `GET /api/view/{serverId}` → merged counts for website/Discord (no auth beyond token if configured).

## Status strings

`SYNCED | ACCESS_DENIED | QUARANTINED | URL_NOT_FOUND | NOT_A_CMSYNC_SERVER | CONNECTION_FAILED | VALIDATION_ERROR`
Same chat semantics as QMSync: report failure once per outage, recovery once.

## Compatibility

* v1 clients (`POST /api/sync` full snapshot) still accepted: server diffs snapshot into deltas internally.
* v2 clients send `protocolVersion: 2`. Server rejects unknown major with HTTP 400.
* See `schema.json` for machine-readable shapes, `normalization.md` for cross-version item rules.
