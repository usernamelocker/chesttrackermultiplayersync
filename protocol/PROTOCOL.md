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
  (JackFredLib `Sanitizer`). Examples: `mc.hypixel.net` →
  `multiplayer/mc_hypixel_net`; `123.45.67.89:25565` → `multiplayer/123_45_67_89_25565`.
  The address is whatever each player typed in their server list, so the port counts:
  `play.example.com` and `play.example.com:25565` are DIFFERENT ids — all players must
  type the address identically. Singleplayer/LAN/realms use `singleplayer/<world>`,
  `lan/<motd>`, `realms/<hex>` instead.
  Proxy networks (same server, several addresses): list the extra ids in
  `SERVER_ID_ALIASES` and the server canonicalizes them into `EXPECTED_SERVER_ID`,
  so all addresses share one bank instead of splitting. Comparison is
  case-insensitive; clients additionally lowercase the id on the wire (26.x
  libraries preserve capitalisation, 1.21.11 lowercases).
  Server compares this against `EXPECTED_SERVER_ID`. Hub/lobby connections use a different `serverId`
  and must be rejected/ignored by config.
* `mcVersion` + `modVersion` drive cross-version normalization (see `normalization.md`).
* Auth v2 MVP: UUID whitelist + optional per-player token header `X-CMSync-Token`.
  There are no sessions; every request re-checks `serverId` + whitelist.

## Endpoints

### `POST <base>/api/handshake`

Same as v1 + version fields. Responses: `{status: SYNCED, generation}` or
`{status: ACCESS_DENIED, reason}` (also honors 401/403 for bad tokens).
Client stores base URL per bank on `SYNCED` only. `generation` newer than the
client's clears locals (wipe propagation).

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
* Server applies per-entry LWW on `updatedAt`; stale entries ignored, **ties keep
  the stored version** (this is what makes lossy fallback reconstructions safe —
  see `normalization.md`).
* **Mass deletes go straight through:** bursts (deletes > `MAX_DELETE_COUNT`, default 50,
  or > `MAX_DELETE_FRACTION`, default 20%, of banks ≥ `MIN_QUARANTINE_BANK`, default 10)
  trigger a pre-delete snapshot + warning log, then apply. Only fully-empty pushes are
  ignored (hub-wipe protection); an established bank reading completely empty is held
  client-side instead.
* Empty `changes` with `fullHash` matching server = no-op (used for keepalive/hash check).
* **Empty-bank rule:** if `changes` is empty AND `fullHash` == hash(empty) while server has >0 containers,
  server ignores (protects hub-wipe). Client must also skip push in that case.

### `GET <base>/api/pull?serverId=…&playerUuid=…&px=…&py=…&pz=…&dim=…`

Returns the filtered full state (clients LWW-merge it locally; there is no
incremental cursor — `since` is accepted but unused, kept for compat):

```json
{
  "status": "SYNCED",
  "serverTime": 1758...,
  "cursor": 42,
  "changes": [ "...same shape as push..." ],
  "tombstones": [{"key": "...", "pos": "...", "deleted_at": "..."}],
  "owners": {"<uuid>": "<playerName>"},
  "generation": 3,
  "containers": 1234,
  "revision": 42
}
```

`revision`/`cursor` is the newest durable server revision included in the
response. A client sends that number back as `since` on its next pull. With a
position-gated pull, a durable per-client range cursor makes a stationary pull
incremental while a move to a new position or dimension returns the current
relevant state before incremental pulls resume.

* **Range gate:** with player position (`px,py,pz` + dimension `dim`), only same-dimension
  containers within `RANGE_BLOCKS` (default 5000) are returned — plus ender-style keys,
  which have no position and always pass. Tombstones are gated the same way (positions
  leak too). Without position params the pull is ungated (old-client compatible).
* `owners` maps ender-chest key owners to last-seen names (powers profile labels).
* Ender chests sync under per-player keys (`chesttracker:ender_chest/<uuid>` etc.),
  so teammates' ender chests never merge — the server treats keys opaquely.

Client merges into loaded `MemoryBankImpl` on client thread (see `mod/src/.../impl/cmsync/CMSyncManager`).

### Snapshots / restore (backups)

* Server auto-snapshots full merged state every `SNAPSHOT_INTERVAL_MIN` (default 15) into `snapshots` table
  + keeps filesystem `.backup` via cron (`server/backup.py`).
* `GET /api/snapshots?serverId=` lists `{id, createdAt, containers}`.
* `POST /api/restore {serverId, snapshotId}` (admin token) restores.
* `POST /api/wipe` (admin token, two-step): `{confirm:false}` → `{status:CONFIRM_REQUIRED,
  challenge, containers, warning}`; then `{confirm:true, challenge}` within 60s →
  `{status:WIPED, generation, snapshotId, cleared:{...}}`. Wipes memories, tombstones
  and owners (snapshots kept, pre-wipe snapshot taken) and bumps the wipe `generation`.
* `generation` rides on handshake/pull responses. Clients holding an older generation
  clear their local banks on next contact — including players offline during the wipe.
* Broken/emptied containers propagate as `deleted:true` changes (tombstones, 30d TTL);
  mass breaks apply straight through (pre-delete snapshot + warning log), an
  established bank reading completely empty holds pull-only client-side.
* `GET /health` → `{ok, time, expectedServer, cmsync}` (server behavior version).
* `GET /api/view/{serverId}` → merged counts for website/Discord (no auth beyond token if configured).

## Status strings

`SYNCED | ACCESS_DENIED | URL_NOT_FOUND | NOT_A_CMSYNC_SERVER | CONNECTION_FAILED | VALIDATION_ERROR | QUARANTINED | WIPED | CONFIRM_REQUIRED`
Same chat semantics as QMSync: report failure once per outage, recovery once.
(`QUARANTINED` is still classified client-side but the server no longer emits it —
mass deletes snapshot + apply instead.)

## Compatibility

* v1 clients (`POST /api/sync` full snapshot) still accepted: server diffs snapshot into deltas internally.
* v2 clients send `protocolVersion: 2`. Server rejects unknown major with HTTP 400.
* Cross-version item rules: `normalization.md` (the `schema.json` once referenced
  here was never written — the JSON shapes above are the spec).
