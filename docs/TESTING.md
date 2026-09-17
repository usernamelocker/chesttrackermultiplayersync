# Testing

## Server (no MC needed)

```powershell
cd server
python test_server.py
# lww ok / guard ok / snapshot ok / ALL SERVER TESTS PASSED
```

Covers: LWW newer-wins across `mcVersion`, stale ignored, mass-delete thresholds,
snapshot → delete → restore.

## Manual 2-client test (do this before inviting friends)

1. Server: set `.env` (`EXPECTED_SERVER_ID` from step 3, 2 UUIDs in `WHITELIST_UUIDS`), `uvicorn app:app`.
2. Mod: apply overlay to QMSync checkout (see `client/overlay/README.md`), `gradlew.bat runClient` twice
   (or 2 PCs) with different accounts on same MC server.
3. Player A: `/cmsync status` → copy `server: [...]\[multiplayer/...]` into server `.env`, restart server.
4. Both: Memory Bank menu → CMSync tab → connect → expect `SYNCED`.
5. A opens chest with iron → wait 10s → B searches iron (grave key) → must find A's chest.
6. B breaks/empties that chest → A pulls tombstone within ~10-15s.
7. Hub-wipe test: A joins lobby/hub (different `serverId`) → `/cmsync status` shows bound mismatch, no push;
   server `containers` count unchanged (`GET /api/view/<serverId>`).
8. Mass-delete test: backup first (`POST /api/snapshots` exists), then wipe 60 chests locally → expect
   `QUARANTINED` chat warning + server snapshot row, data intact.

## Cross-version (1.21.11 + 26.x)

* Same steps with one client on each version branch (see `client/overlay/docs/PORTING-26x.md`).
* Expect: counts/search match by `id`; enchanted/custom-component detail may be plain on older client.
* Unknown new-version `id`s must not crash older client (skipped with warning in log).
