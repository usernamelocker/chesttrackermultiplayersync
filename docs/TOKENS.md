# Tokens: where players type them (now) + UI plan

## Now (no UI yet, command only)

```
/cmsync connect https://cmsync.example.com
/cmsync connect https://cmsync.example.com mySecretPassword
/cmsync connect http://192.168.1.10:8000
/cmsync status
/cmsync stop
```

* URL + optional token are typed in chat. Token is the 2nd word (split on first space).
* Stored locally only: `<minecraft>/config/chesttracker/cmsync_<bank>.json`
  (`url`, `token`, `boundServerId`). Never sent anywhere except as `X-CMSync-Token` header to YOUR server.
* URL box tolerates a missing scheme: `host:7000` becomes `http://host:7000` automatically.
* Chat command splits on whitespace, so passwords with spaces only work via the `/cmsync gui` box.
* `SHARED_TOKEN` empty on server = no password needed, whitelist UUIDs alone gate access.
  Set `SHARED_TOKEN` on server + players include it in connect if you want password + whitelist.
* `ADMIN_TOKEN` (defaults to shared) only for `/api/restore` — players never type this in-game.

## Why no UI yet?

QMSync has a QMSync tab in Edit Memory Bank screen (pause/interval/privacy toggles).
Our overlay `CMSyncSettings` is a sidecar so we didn't have to fork `Metadata.CODEC` yet.
Next step is a matching CMSync tab:

* fields: server URL, token (password box), pause/resume, interval slider, ender-chest toggle, status line
* buttons: Connect / Stop / Copy serverId (for `.env` EXPECTED_SERVER_ID)
* lives in same Edit Memory Bank screen, reuses YACL widgets QMSync already depends on

Say the word and I'll scaffold `CMSyncGuiTab.java` against the 1.21.11 base
(needs a full QMSync checkout to compile — see `client/overlay/docs/APPLY.md`).
