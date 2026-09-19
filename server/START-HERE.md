# START HERE (Portainer, step by step)

You don't need to understand the grey recipe box (that's YAML — just ignore what it says).
You only fill in a small table of settings. Portainer pulls everything from GitHub itself.

## One-time setup (about 5 minutes)

1. Portainer → **Stacks** → **Add stack** → name it `chest-tracker` (any name works).
2. Under **Build method**, pick **Repository** (NOT Web editor):
   - Repository URL: `https://github.com/usernamelocker/chesttrackermultiplayersync`
   - Branch: `main`
   - Compose path: `server/docker-compose.yml`
   - Turn ON **Repository authentication** (the repo is private) and enter your GitHub
     username + a token. Any token with `repo` read access works.
3. Scroll to **Environment variables** and add (copy the names letter for letter —
   full reference with examples: `server/stack.env.example`):
   - `EXPECTED_SERVER_ID` → `multiplayer/fabriccraft_net` (this is `fabriccraft.net`
     with dots as underscores; tell all players to join via exactly `fabriccraft.net`,
     no port, so everyone lands in one bank — comparison is case-insensitive).
   - `SERVER_ID_ALIASES` → `multiplayer/vip_fabriccraft_net` (safety net: players who
     join via `vip.fabriccraft.net` merge into the same bank instead of a split one).
   - Token mode: leave `WHITELIST_UUIDS` **empty**, set `SHARED_TOKEN` to a long random
     password (24+ random letters/numbers). Everyone who knows it can sync.
   - `ADMIN_TOKEN` → a **different** long random password. Used only for
     `/cmsync wipealldata` (entered in-game under CM Settings, never in the Sync tab).
     ⚠️ In Portainer/docker-compose, escape every `$` as `$$`
     (`abc$X` → `abc$$X`) or compose silently eats it and the stored token won't match.
   - Leave everything else alone — safe defaults are baked in.
4. Click **Deploy the stack**. Then Containers → click `cmsync` → **Logs**.
   Wait until you see `Uvicorn running` (it says port 8000 inside the container — normal;
   the compose file publishes it outside as 7000). That means it's alive.
5. In your browser open `http://YOUR-VPS-IP:7000/health`
   (replace YOUR-VPS-IP with the VPS address). You should see `{"ok":true,...}`.
   Done — server is up.

> Changing anything later = same flow: Stacks → stack → change env → **Update the
> stack WITH rebuild** (a plain container restart keeps the old code — rebuild pulls
> the new code from GitHub).

## Connect the game

1. Install the right jar from
   [GitHub Releases](https://github.com/usernamelocker/chesttrackermultiplayersync/releases)
   (`cmsync-1.21.11-*` or `cmsync-26.1.2-*`) + Fabric API + YACL.
2. In-game: Memory Bank menu (`` ` `` key → pencil icon) → **Sync** tab →
   paste `http://YOUR-VPS-IP:7000` + the SHARED token → Connect.
   (URL box also accepts `host:port` without scheme and `https://` addresses.)
3. **CM Settings** tab → check the toggles (interval, ender chest, container names,
   chat notifications) + paste the ADMIN token into the Admin token row if you want
   wipe access on this PC.
4. `/cmsync status` should say `synced` within ~10 seconds.

## Wipe (fresh start)

`/cmsync wipealldata` → read the warning → `/cmsync wipealldata confirm` within
60s. Needs the admin token (CM Settings). Zeroes the server, bumps a generation
counter so every connected (and later returning) client clears its locals too.
Snapshots are kept — see [`../docs/BACKUPS.md`](../docs/BACKUPS.md).

## Custom domain + HTTPS (nginx)

`http://IP:7000` works fine for testing. For `https://chesttracker.example.com`
(no port in the URL):

```nginx
server {
    server_name chesttracker.example.com;
    client_max_body_size 20m;   # REQUIRED: pushes are multi-MB; nginx default is 1m
                                # and every push dies with 413 otherwise

    location / {
        proxy_pass http://127.0.0.1:8000/;
        proxy_http_version 1.1;
        proxy_set_header Host $http_host;
    }

    listen 443 ssl;   # certs via certbot (see below)
}
```

1. Point the domain's A record at the VPS.
2. `certbot --nginx -d chesttracker.example.com` (fills the `ssl_*` lines).
3. `nginx -t && systemctl reload nginx`.
4. In-game URL becomes `https://chesttracker.example.com` (no port).

Alternative without a domain (HTTPS on a port): a Caddy block like
`your.domain:7000 { reverse_proxy localhost:8000 }` gets certs automatically.

## If something is red

* State shows **Access denied** → wrong token (Sync tab needs the SHARED token,
  admin token goes in CM Settings) or wrong `serverId` (compare with
  `/cmsync status` → `server: [...]`; fix `EXPECTED_SERVER_ID`/`SERVER_ID_ALIASES`,
  everyone must join via the identical address).
* Chat spams **push failed: CONNECTION_FAILED** but connects fine → the reverse
  proxy is eating push bodies: set `client_max_body_size 20m;` (above) and reload.
  Confirm in `<game>/chesttracker/cmsync.log` (`push ... http=413`).
* No data arrives but no errors → read the tail of `cmsync.log`:
  `pull ... changes=0` = server withholds (range gate / wrong bank);
  `changes=N` + empty screen = client-side, report the lines.
* Server-side truth: Portainer → container **Logs**. Denied handshakes/pulls log
  `handshake DENIED <reason> player=<uuid> got=<id> want=<id>` — copy that line,
  it names the exact mismatch.
* `/health` doesn't load → stack didn't deploy: Stacks → open it, read the message.
* Wipe says `ACCESS_DENIED admin token required` → put `ADMIN_TOKEN` in the
  CM Settings Admin token row (NOT the Sync tab token box), then retry.
