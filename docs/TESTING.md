# Testing

## Server (no MC needed)

```powershell
cd server
   python -m pytest test_api.py test_server.py
```

Covers: LWW newer-wins across `mcVersion`, stale ignored, shared memory/tombstone
ordering, mass-delete thresholds, exact full-bank quarantine,
range gate + owner tracking, snapshot → delete → restore, case-insensitive server ids.
Live smoke against a deployed server (stdlib only, cleans up after itself):

```powershell
python smoke.py http://VPS-IP:7000 YOURTOKEN
```

## Manual 2-client test (do this before inviting friends)

1. Server: fill `.env`/stack env (`EXPECTED_SERVER_ID` from step 3, `SHARED_TOKEN`),
   deploy. Or local: `uvicorn app:app` + `smoke.py`.
2. Mod: install the release jars for both MC versions (see repo README) on 2 PCs
   (or 2 game dirs) with different accounts, same MC server.
3. Player A: `/cmsync status` → `server: [multiplayer/...]` must equal
   `EXPECTED_SERVER_ID` (or an alias) — same address typed identically by everyone.
4. Both: Memory Bank menu → Sync tab → URL + token → Connect → `synced` ≤10s.
5. A opens chest with iron → wait 10s → B searches iron (grave key) → must find A's chest.
6. B breaks/empties that chest → A pulls the tombstone within ~15s (fast delete path).
7. Hub-wipe test: A joins lobby/hub (different `serverId`) → nothing pushes;
   server `containers` count unchanged (`GET /api/view/<serverId>`).
8. Shulker test: chest with a filled shulker → all clients keep seeing the contents
   after minutes (regression: fallback merges once stamped `now()` and wiped nested NBT).
9. Admin test: put `ADMIN_TOKEN` in CM Settings → `/cmsync wipealldata` + `confirm` →
   server zeroed, all clients cleared (generation bump), snapshot kept.

## Cross-version (1.21.11 + 26.1.2)

Also test a delete-only push covering every stored container: the server must
return `QUARANTINED`, create a snapshot, and keep the containers. Confirm that
`/cmsync wipealldata` still succeeds for an intentional admin wipe.

* Same steps with one client per version against the same `serverId`.
* Expect: counts/search match by `id`; native data restores when the receiving
  version can decode it, while the portable envelope supplies known components
  across versions.
* Test a nested container/bundle component created on 26.1.2, edit the item on
  1.21.11, and verify the unknown component survives when it returns to 26.1.2.
  Unknown newer ids/components must not crash the older client.

## When something fails

* `<game>/chesttracker/cmsync.log` (client): every cycle, handshake (with server's
  reason), push/pull outcomes with HTTP code + duration, merge counts, transport
  exception messages. Paste the newest lines when reporting.
* Server container logs: `handshake/pull DENIED ... got=... want=...`, `MASS DELETE`,
  `WIPE`, `422 ... errors=[...]` (exact field + body).
* Classic: `push failed: CONNECTION_FAILED` every cycle + pull fine behind a proxy =
  proxy body limit (`client_max_body_size 20m;` in nginx).
