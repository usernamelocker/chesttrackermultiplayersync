# Client overlay (QMSync 1.21.11 base → CMSync v2)

Drop-in package `red.jackf.chesttracker.impl.cmsync` that turns upload-only QMSync into
**bidirectional multiplayer sync** (push deltas + pull merge) while keeping local `JsonBackend` cache.

Target base: `KarolexDev/QMSync` branch `1.21.11`. Cross-version clients (`1.21.11`, `26.1.2`, `26.2`)
talk to the same server via normalized `{id,count}` items (see `protocol/normalization.md`).

## Files

```
src/client/java/red/jackf/chesttracker/impl/cmsync/
  CMSyncSettings.java   per-bank sidecar (url, token, enabled/paused, interval) — no Metadata.CODEC edit needed
  CMSyncHttp.java       handshake/push/pull, protocolVersion=2, X-CMSync-Token header
  CMSyncQueue.java      single background lane: max 1 job running, extra ticks coalesce (never blocks FPS)
  ItemNormalizer.java   BlockPos <-> "x,y,z", ItemStack <-> {id,count,digest}, sha256
  CMSyncManager.java    tick loop: fast copy on client thread, Gson/HTTP only on queue, LWW merge + guards
  CMSyncCommand.java    /cmsync connect|stop|status
```

## Async model (your friend's ask: queue, no client block)

* Tick (20x/sec) does only guards + `ItemStack.copy()` snapshot. No Gson, no HTTP on render thread.
* `CMSyncQueue` = 1 daemon thread `cmsync-net`, `MIN_PRIORITY`. `tryClaim()` coalesces: if busy, tick skips.
* Background job: normalize → hash → `POST /push` → `GET /pull` (blocking `join()` is fine off-thread).
* Merge back via `client.execute()` on main thread. `droppedCoalesced()` counts skipped ticks.

## Apply (3 hooks, ~10 lines)

1. Copy `cmsync/*.java` into your QMSync checkout at
   `src/client/java/red/jackf/chesttracker/impl/cmsync/`.
2. In `ChestTracker.onInitializeClient()` (next to `QMSyncManager.INSTANCE.setup()`):
   ```java
   red.jackf.chesttracker.impl.cmsync.CMSyncManager.INSTANCE.setup();
   red.jackf.chesttracker.impl.cmsync.CMSyncCommand.register();
   ```
3. Optional but recommended: when CMSync is active for a bank, skip QMSync upload for that bank
   (avoids double-post). In `QMSyncManager.tick()` early-return if
   `CMSyncManager.INSTANCE.isActiveFor(bank.getId())`.

No `Backend` replacement needed — local saves stay authoritative offline; server is merge layer.
If you later want a true `Backend.Type.REMOTE`, wrap `CMSyncManager.pull()` in `load()`.

## Configure in-game

```
/cmsync connect https://cmsync.example.com [token]
/cmsync status        # shows serverId (put this in server .env EXPECTED_SERVER_ID), counts, last sync
/cmsync stop
/cmsync restore <snapshotId>   # admin token only, then auto-pull
```

Get your exact `serverId` from `/cmsync status` (e.g. `multiplayer/mc_play_myserver_com`).

## Safety behavior (matches server)

* Only syncs when bank id == connected `Coordinate.id()` allowlist (hub joins ignored).
* Empty local bank + non-empty server → push skipped, pull still runs (no hub-wipe).
* Local mass-delete (>20% or >50) → push held, chat warning, snapshot on server after confirm.
* Deletes propagate as tombstones (30d TTL), single deletes OK.
