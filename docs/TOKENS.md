# Tokens: where players type them

## In the menu (normal way)

Memory Bank menu (`` ` key → pencil icon) → **CMSync (Team)** tab:
URL box + token box + Connect. The token box shows `***` — the real value is only
in the sidecar file on that PC and is sent as `X-CMSync-Token` to YOUR server.

The old QMSync tab next to it is the website-upload system — different thing,
leave it alone unless you use a QMSync website.

## Commands (leftovers)

```
/cmsync status
/cmsync stop
```

No connect command anymore — connecting happens in the menu tab above.

## Server side

Token mode: `WHITELIST_UUIDS` empty + `SHARED_TOKEN` set. Everyone with the
password syncs. Wrong/missing password reads as ACCESS DENIED.
(`ADMIN_TOKEN`, defaulting to shared, guards `/api/restore`.)
