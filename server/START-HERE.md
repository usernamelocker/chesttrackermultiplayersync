# START HERE (Portainer, step by step)

You don't need to understand the grey recipe box (that's YAML — just ignore what it says).
You only fill in a small table of settings. Portainer pulls everything from GitHub itself.

## One-time setup (about 5 minutes)

1. Portainer → **Stacks** → **Add stack** → name it `cmsync`
2. Under **Build method**, pick **Repository** (NOT Web editor):
   - Repository URL: `https://github.com/usernamelocker/chesttrackermultiplayersync`
   - Branch: `main`
   - Compose path: `server/docker-compose.yml`
   - Turn ON **Repository authentication** (the repo is private) and enter your GitHub
     username + a token/password. Any token with `repo` read access works.
3. Scroll to **Environment variables** and add (copy the names letter for letter):
   - `EXPECTED_SERVER_ID` → for now type `TEMP` (you'll replace it below, it must match the game exactly)
   - Token mode (what you asked for): leave `WHITELIST_UUIDS` **empty**, set
     `SHARED_TOKEN` to a long random password (e.g. 24+ random letters/numbers).
     Everyone who knows it can sync — no UUID list needed.
   - Leave everything else alone — all the other settings already have safe defaults.
4. Click **Deploy the stack**. Then Containers → click `cmsync` → **Logs**.
   Wait until you see `Uvicorn running on ... port 8000`. That means it's alive.
5. In your browser open `http://YOUR-VPS-IP:8000/health`
   (replace YOUR-VPS-IP with the VPS address from Portainer or your host).
   You should see `{"ok":true,...}`. Done — server is up.

## Connect the game (needs the mod built)

1. In-game: `/cmsync status` → note the `server: [... ]` value, e.g. `multiplayer/mc_...`
2. Back in Portainer → Stacks → `cmsync` → **Editor** tab → Environment variables →
   set `EXPECTED_SERVER_ID` to that exact value → **Update the stack** (Redeploy).
3. In-game: `/cmsync gui` → paste `http://YOUR-VPS-IP:8000` → Connect →
   `/cmsync status` should say `synced` within ~10 seconds.

## Domain (your "ig")

* Skip it for testing — `http://IP:8000` works fine.
* Later, if you want `https://cmsync.yourdomain.com`: point the domain at the VPS,
  then add a reverse-proxy rule (Nginx/Traefik) forwarding to `cmsync:8000`.
  Players then use the `https://` address instead. Ask me when you get there.

## If something is red

* Logs say `not whitelisted` → UUID typo. Fix `WHITELIST_UUIDS`, Update stack.
* Logs say `wrong serverId` → `EXPECTED_SERVER_ID` doesn't match `/cmsync status`. Copy it exactly.
* `/health` doesn't load → stack didn't deploy: check stack status in Portainer → Stacks
  (red = build/deploy error, open it and read the message).
* Updating settings later = same flow: Stacks → `cmsync` → change env → **Update the stack**.
