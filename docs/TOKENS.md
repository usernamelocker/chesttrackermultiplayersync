# Tokens & access: where players type what

## In the menu

Memory Bank menu (`` ` `` key → pencil icon):

* **Sync tab** — server URL + **shared token** (masked, stored on this PC only) +
  Connect / Pause / Resume / Stop. The token is sent as `X-CMSync-Token` to YOUR server.
* **CM Settings tab** — sync interval, ender chest, container names, chat
  notifications, **Admin token** (masked, this PC only). The admin token is used
  **only** by `/cmsync wipealldata` — putting it in the Sync tab token box just
  gives "Access denied" (it's not the sync password).

## Commands

```
/cmsync status    connection, server/bank ids, last sync, last detail, local counts
/cmsync stop      disconnect + forget URL/token (admin token in CM Settings is kept)
/cmsync wipealldata [confirm]   two-step shared wipe, needs the admin token
```

## Server side

The server's full-bank delete protection is independent of player credentials:
an exact delete-only push covering an established bank is returned as
`QUARANTINED`, while `/cmsync wipealldata` remains the explicit admin wipe path.
See the [protocol contract](../protocol/PROTOCOL.md) and
[backup/recovery guide](BACKUPS.md).

Two modes (see `server/stack.env.example`):

* **Token mode** (simplest): `WHITELIST_UUIDS` empty + `SHARED_TOKEN` set.
  Everyone with the password syncs. Wrong/missing password → 401, shown as
  ACCESS DENIED.
* **UUID mode**: `WHITELIST_UUIDS` set → only those UUIDs in (token optional extra).
* `ADMIN_TOKEN` (use a **different** password) guards `/api/wipe` + `/api/restore`.
  ⚠️ Escape `$` as `$$` in Portainer/compose values, or the stored token silently
  won't match what players type.
* `serverId` check is **case-insensitive** (`EXPECTED_SERVER_ID` + `SERVER_ID_ALIASES`
  merge spellings into one bank). Still ACCESS_DENIED? The server log prints
  `handshake DENIED <reason> player=<uuid> got=<id> want=<id>` — that line names it.
