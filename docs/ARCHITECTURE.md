# Architecture

CMSync has two independent client builds and one shared server implementation.
The clients retain an offline-first local memory bank; the server is an
authenticated merge and distribution layer.

```text
Client 1.21.11 --+                         +-- local bank + CMSyncManager
                 +-- HTTPS push/pull -------+
Client 26.1.2  --+                         +-- local bank + CMSyncManager
                                              |
                              FastAPI + SQLite server
                              memories and tombstones
                              revisions and change events
                              snapshots and generations
```

## Design decisions

- Clients never access SQLite directly and never receive database credentials.
- Local memory remains usable while offline. Successful server responses are
  required before a change is considered synchronized.
- A logical container is `(server_id, key, pos)`. Memory rows and tombstones are
  compared through the same LWW version, so stale updates cannot resurrect a
  newer deletion.
- Normalized item data powers identity, search, counts, and the website view.
  Native serialization and the portable item envelope preserve richer data; see
  [item normalization](../protocol/normalization.md).
- Ender-style keys include the player identity and therefore do not merge
  different players' ender chests.

## Data flow

1. A memory provider records a container observation with its observation time.
2. `CMSyncManager` snapshots and detaches client-thread data, encodes it, and
   sends a delta to `/api/push` on the background queue.
3. The server checks identity, authentication, and generation. Empty pushes
   against a non-empty bank are ignored. A delete-only push covering every
   stored container in an established bank is snapshotted and returned as
   `QUARANTINED`; it is not applied. Partial mass deletes are snapshotted,
   logged, and applied.
4. The client calls `/api/pull?since=N`. Durable revisions provide incremental
   delivery while stationary; moving or changing dimension causes the client to
   receive the relevant current state before incremental delivery resumes.
5. Spatial pulls include nearby Overworld and Nether containers while the player
   is in either dimension. Nether horizontal coordinates are scaled by 8 when
   compared with the Overworld-equivalent range. Other dimensions are isolated;
   ender-style keys have no meaningful position and bypass the range gate.
6. The client LWW-merges the response on the client thread. Same-version native
   data is preferred, then portable data, then normalized fallback data.
7. `/api/view/{serverId}` aggregates `items_norm` for website/Discord consumers.

## Wipes, restore, and recovery

`/cmsync wipealldata` calls the authenticated two-step `/api/wipe` endpoint. A
successful wipe keeps a snapshot, clears memories/tombstones/owners, increments
the generation, and causes returning offline clients to clear stale local data.
The push quarantine does not block this explicit admin action.

An accidental full-bank push returns a `snapshotId` and logs
`FULL WIPE QUARANTINED`. Restore it through `/api/restore`; operational details
are in [BACKUPS.md](BACKUPS.md) and [PORTAINER.md](../server/PORTAINER.md).

## Key files

- Protocol: `protocol/PROTOCOL.md`, `protocol/normalization.md`
- Server: `server/app.py`, `server/db.py`, `server/models.py`,
  `server/config.py`, `server/backup.py`
- Client sync: `mod/src/client/java/red/jackf/chesttracker/impl/cmsync/`
- Ender profiles: `mod/src/client/java/red/jackf/chesttracker/impl/memory/EnderChestKeys.java`
- Retired overlay: `client/overlay/README.md`
