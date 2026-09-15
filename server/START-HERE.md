# START HERE (Portainer, no YAML knowledge needed)

YAML = just the grey recipe box. You paste it once and never touch it again.
What YOU fill is the Environment variables table under it.

## 5 clicks

1. Portainer → Stacks → **Add stack** → name: `cmsync`
2. Web editor: paste ALL of `server/portainer-stack.yml` (Ctrl+A, Ctrl+V)
3. Scroll to **Environment variables** → Add:
   - `EXPECTED_SERVER_ID` = from game `/cmsync status` (looks like `multiplayer/mc_...`). Temp guess ok, fix later.
   - `WHITELIST_UUIDS` = friend UUIDs, comma separated, no spaces (`aaa,bbb,ccc`)
   - Leave `SHARED_TOKEN` / `ADMIN_TOKEN` empty for now (no password)
4. **Deploy the stack** → open Containers → `cmsync` → Logs, wait for `Uvicorn running`
5. Browser: `http://YOUR-VPS-IP:8000/health` → `{"ok":true}` = done

## Domain? (you said "ig")

* Test first with `http://IP:8000` — works, use this today.
* Domain later = pretty name + https. In Portainer add your existing reverse-proxy (Nginx/Traefik) route
  `cmsync.yourdomain.com` → `cmsync:8000`. Say the word and I'll write that proxy rule.
* Players then use `https://cmsync.yourdomain.com` in `/cmsync gui`.

## If red in Logs

* `not whitelisted` → UUID typo or wrong `serverId`. Re-check `/cmsync status` values.
* `wrong serverId` → copy exact `serverId` from game into env, Update stack → Redeploy.
