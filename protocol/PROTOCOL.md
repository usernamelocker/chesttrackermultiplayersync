# CMSync Protocol v2

CMSync v2 extends the original QMSync handshake and full-snapshot API with
authenticated delta push/pull synchronization. The supported client versions
are Minecraft `1.21.11` and `26.1.2`.

The server stores opaque item data and does not interpret Minecraft codecs. The
cross-version item format is specified separately in
[normalization.md](normalization.md).

## Identity and server ids

Every v2 request identifies the client and bank:

```json
{
  "protocolVersion": 2,
  "playerUuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
  "playerName": "Steve",
  "serverId": "multiplayer/mc_hypixel_net",
  "serverName": "Multiplayer: Hypixel",
  "mcVersion": "1.21.11",
  "modVersion": "cmsync"
}
```

`serverId` comes from the client coordinate id. Multiplayer addresses are
sanitized into `multiplayer/<address>`. Players must enter the same address,
including port spelling, unless the server config maps alternate ids through
`SERVER_ID_ALIASES`. Comparison is case-insensitive.

The server checks `EXPECTED_SERVER_ID`, whitelist membership, and the optional
`X-CMSync-Token` header on every request. There are no sessions.

## Handshake

`POST /api/handshake` returns:

```json
{"status":"SYNCED","generation":3}
```

or an access error. A client receiving a newer generation clears its local bank
before it pushes again.

## Delta push

`POST /api/push` accepts one entry per logical container `(key,pos)`:

```json
{
  "protocolVersion": 2,
  "playerUuid": "...",
  "playerName": "Steve",
  "serverId": "multiplayer/mc_hypixel_net",
  "mcVersion": "1.21.11",
  "generation": 3,
  "baseHash": "sha256-or-null",
  "fullHash": "sha256-of-normalized-view",
  "changes": [
    {
      "key": "minecraft:overworld",
      "pos": "102,64,-33",
      "deleted": false,
      "updatedAt": "2026-09-15T18:00:00Z",
      "updatedBy": "uuid",
      "mcVersion": "1.21.11",
      "items": [{"id":"minecraft:iron_ingot","count":64}],
      "raw": {"v":2,"mc":"1.21.11","memory":{},"portable":{}}
    }
  ]
}
```

Rules:

- `generation` must equal the server generation. Older pushes receive
  `STALE_GENERATION` and must pull first.
- Memory rows and tombstones use one LWW comparison on `updatedAt`. A stale
  upsert cannot resurrect a newer tombstone, and a stale delete cannot replace a
  newer delete. Equal timestamps keep the stored value.
- `items` is the normalized search/aggregation view. `raw` is opaque to the
  server and may contain native and portable representations.
- Empty pushes against a non-empty bank are ignored as hub-wipe protection.
- Partial mass deletes are snapshotted, logged, and applied.
- A delete-only push covering every currently stored container in a bank with at
  least `FULL_WIPE_MIN_BANK` containers (default 10) is treated as a possible
  client memory-bank wipe. The server snapshots the bank and returns
  `QUARANTINED` without applying the changes. Existing clients recognize this
  response. The authenticated `/api/wipe` endpoint is unaffected.

Successful push responses include `status: SYNCED`, `applied`,
`skipped_stale`, `containers`, and `revision`.

## Incremental pull

`GET /api/pull?serverId=...&playerUuid=...&since=N&px=...&py=...&pz=...&dim=...`
returns changes relevant to the client and the newest durable revision:

```json
{
  "status":"SYNCED",
  "changes":[],
  "tombstones":[],
  "owners":{},
  "generation":3,
  "revision":42,
  "cursor":42,
  "containers":1234
}
```

The server keeps a durable change/event log. A stationary client receives only
events after `since`. If the client moves or changes dimension, the server first
returns the relevant current state so a previous range cursor cannot hide newly
nearby containers. The response revision is the value for the next request.

With `px`, `py`, `pz`, and `dim`, pulls include nearby Overworld and Nether
containers while the player is in either dimension. Horizontal Nether
coordinates are multiplied by 8 for the comparison, so `RANGE_BLOCKS=5000`
means an effective 625-block horizontal Nether radius. Y is unchanged. Other
dimensions remain isolated. Ender-style keys have no meaningful position and
always pass the range gate. Without position parameters, the pull is ungated for
compatibility.

## Snapshots, restore, and wipe

- `GET /api/snapshots?serverId=...` lists admin-protected snapshots.
- `POST /api/restore` with `{serverId,snapshotId}` restores a snapshot and bumps
  the server generation.
- `POST /api/wipe` is an admin-token two-step operation. A challenge response is
  followed by confirmation within 60 seconds. It removes memories, tombstones,
  and owners, keeps recovery snapshots, and advances the generation.

The client command `/cmsync wipealldata` uses this explicit wipe endpoint. It is
not subject to the accidental-push quarantine.

## Other endpoints

- `GET /health` returns service health and server identity.
- `GET /api/view/{serverId}` returns normalized aggregate totals for web/Discord
  consumers.
- `GET /api/pullWebPage` returns an authenticated full read view for the website.
- `POST /api/sync` remains a compatibility endpoint for old v1 clients.

## Status values

`SYNCED`, `ACCESS_DENIED`, `STALE_GENERATION`, `URL_NOT_FOUND`,
`NOT_A_CMSYNC_SERVER`, `CONNECTION_FAILED`, `VALIDATION_ERROR`, `QUARANTINED`,
`WIPED`, and `CONFIRM_REQUIRED`.

## Related documentation

- [Portable item/component format](normalization.md)
- [Repository architecture](../docs/ARCHITECTURE.md)
- [Server setup](../server/START-HERE.md)
- [Testing and manual verification](../docs/TESTING.md)
