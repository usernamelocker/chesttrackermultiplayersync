# CMSync repository guidance

## Project scope

CMSync synchronizes ChestTracker memory state between Minecraft clients and a
self-hosted FastAPI/SQLite server. Supported client versions are Minecraft
`1.21.11` and `26.1.2`.

## Source of truth

- `mod/` is the active, buildable client source.
- `server/` is the authoritative merge service and deployment source.
- `protocol/PROTOCOL.md` defines the HTTP contract.
- `protocol/normalization.md` defines normalized and portable item data.
- `client/overlay/` is retired history; do not edit it as an implementation.

## Branches and builds

- `main` targets Minecraft `1.21.11` and builds with Java 21.
- `26.1.2` targets Minecraft `26.1.2` and builds with Java 25.
- Build from `mod/` with `gradlew.bat build --no-daemon`.
- The mod has no useful Java unit-test suite; server tests are in `server/`.
- Run server tests with `python -m pytest test_api.py test_server.py` from
  `server/`. `test_api.py` exercises the FastAPI contract without a server.

## Synchronization invariants

- A logical container is `(server_id, key, pos)`.
- Memory rows and tombstones share one LWW comparison using canonical
  observation timestamps. A stale upsert must never resurrect a newer delete.
- Client data is offline-first. A failed push must remain retryable; do not
  advance local synchronization baselines before acknowledgement.
- Pulls use durable revisions and `since`. Position-gated pulls include nearby
  Overworld and Nether containers while the player is in either dimension;
  Nether horizontal coordinates use the 1:8 portal scale. Other dimensions are
  isolated and ender-style keys bypass spatial gating.
- `items_norm` is for search and aggregation. Native `raw` data and the portable
  `cmsync.item` envelope preserve richer cross-version item data, including
  unknown components and nested item structures.
- Partial mass deletes are allowed after snapshot/logging. A delete-only push
  covering every stored container in an established bank is quarantined as
  `QUARANTINED` and not applied. The explicit admin `/api/wipe` endpoint used by
  `/cmsync wipealldata` must remain unaffected.
- Wipes advance the server generation so offline clients cannot immediately
  re-upload stale local state.

## Documentation and validation

Keep the root `README.md`, `docs/`, `protocol/`, and `server/` documentation
consistent with implementation behavior. Check local Markdown links after doc
changes. Review `git diff` and `git diff --check` before committing.

## Git and release rules

- Preserve unrelated user changes.
- Make logically separated commits with descriptive messages.
- Never force-push, rewrite unrelated history, delete branches, or create tags
  unless explicitly requested.
- Releases contain both supported-version jars and `SHA256SUMS.txt`. Server-only
  changes may still be released with compatibility artifacts, but document when
  no new client implementation is required.
- Push only when the user explicitly requests it; use normal branch pushes and
  never force-push.

## Useful references

- [Repository README](README.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Protocol](protocol/PROTOCOL.md)
- [Portable item format](protocol/normalization.md)
- [Server setup](server/START-HERE.md)
- [Testing](docs/TESTING.md)
