# Testing

## Server (no MC needed)

```powershell
cd server
python -m pytest test_api.py test_server.py test_portable_format.py test_cross_version.py
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

## Cross-version (1.21.11 + 26.1.2 + 26.2)

Also test a delete-only push covering every stored container: the server must
return `QUARANTINED`, create a snapshot, and keep the containers. Confirm that
`/cmsync wipealldata` still succeeds for an intentional admin wipe.

* Same steps with one client per version against the same `serverId`.
* Expect: counts/search match by `id`; native data restores when the receiving
  version can decode it, while the portable envelope supplies known components
  across versions.
* Test each pair of versions in both directions. Use existing older release jars
  as well as the new 26.2 build, against the same server and bank.
* Test a nested container/bundle component created on 26.2, edit the item on
  1.21.11 and 26.1.2, and verify the unknown component survives its return to 26.2
  when the older release supports portable shadows.
  Unknown newer ids/components must not crash the older client.
* Reset a known component to its default and confirm that its old shadow value
  does not return. Explicit removals must also survive a push/pull cycle.
* Test the Sync and CM Settings tabs, ender profiles, and every screen's back
  button. Check names above chests and search highlights on OpenGL and Vulkan.
* Repeat with optional 26.2 mods: Mod Menu, Jade, WTHIT, Shulker Box Tooltip,
  and Litematica + MaLiLib. Open the material list and use Search Missing.
* Disconnect during a push, reconnect, and check that unacknowledged changes
  retry. Restart an offline older client after an admin wipe; stale state must
  not repopulate the bank.

## Client automated checks (Java 25)

From `mod/`, run `.\gradlew.bat build --no-daemon`. The `test` task exercises
the real portable codec and game registry: foreign JSON preservation, component
reset/removal, malformed components, and bounded slot allocation. Server tests
exercise storage and HTTP compatibility; they do not replace the in-game matrix.

## 26.2 port validation (2026-10-27)

- `gradlew.bat build --no-daemon`: client compilation, six portable-codec tests,
  and access-widener validation passed on Java 25.
- Server regression suite: 21 tests passed, including all nine writer/reader
  combinations of 1.21.11, 26.1.2, and 26.2 through protocol-v2 HTTP endpoints.
- Local Markdown file links resolve, and `git diff --check` passes.
- The isolated 26.2 development client initialized OpenGL, resources, CMSync,
  and the Jade integration. Offline development accounts report expected
  Minecraft/Realms authentication errors; those do not indicate a sync failure.
- An additional startup pass completed resource initialization with Litematica
  0.28.8, MaLiLib 0.29.6, Shulker Box Tooltip 5.4.3, WTHIT 20.0.0, Bad Packets
  0.12.2, Jade 26.2.11, and Mod Menu 20.0.3 installed, without a mixin crash.
- Real multiplayer sessions, visual screen behavior, world-label rendering,
  Vulkan, and round trips through existing older jars still require the manual
  matrix above. The Windows inspection helper was unavailable during this run,
  so startup evidence comes from game logs, not visual verification.

These checks establish the tested behavior; they do not guarantee that every
older mod release or optional-mod combination is bug-free.

## When something fails

* `<game>/chesttracker/cmsync.log` (client): every cycle, handshake (with server's
  reason), push/pull outcomes with HTTP code + duration, merge counts, transport
  exception messages. Paste the newest lines when reporting.
* Server container logs: `handshake/pull DENIED ... got=... want=...`, `MASS DELETE`,
  `WIPE`, `422 ... errors=[...]` (exact field + body).
* Classic: `push failed: CONNECTION_FAILED` every cycle + pull fine behind a proxy =
  proxy body limit (`client_max_body_size 20m;` in nginx).
