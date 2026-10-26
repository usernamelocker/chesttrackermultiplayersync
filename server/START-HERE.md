# Set up the shared CMSync server

Minecraft 26.2 is the primary client release. The same server supports older
26.1.2 and 1.21.11 clients. It is separate from the Minecraft game server.

## Deploy with Portainer

For an existing deployment, follow [Portainer operations](PORTAINER.md) instead
of creating a new stack. Keep the existing settings and data volumes.

For a new deployment:

1. Open **Stacks → Add stack** and choose a name such as `chest-tracker`.
2. Select **Repository** as the build method.
3. Set the repository to `https://github.com/usernamelocker/chesttrackermultiplayersync`.
4. Set the repository reference to **`refs/heads/26.2`**.
5. Set the compose path to **`server/docker-compose.yml`**. The server build
   context is `server/`; no client build or Minecraft installation is needed.
6. Enable repository authentication if the repository is private, using an
   account/token with repository read access.
7. Add the environment settings below, then deploy the stack.
8. Check the container logs for startup and open `http://VPS-IP:7000/health`.

The repository has no `main` branch. Existing stacks pinned to `main` must
change their repository reference to `refs/heads/26.2` before the next pull or
redeploy. The `/server` folder selection is independent of the branch reference.

## Environment settings

| Setting | What to enter |
|---|---|
| `EXPECTED_SERVER_ID` | Exact bank/server ID shown by `/cmsync status`, such as `multiplayer/fabriccraft_net` |
| `SERVER_ID_ALIASES` | Optional comma-separated alternative addresses for the same Minecraft network |
| `SHARED_TOKEN` | Long random password entered by players in the Sync tab |
| `ADMIN_TOKEN` | Different long random password for confirmed wipes and restores |
| `WHITELIST_UUIDS` | Leave empty for shared-token mode; otherwise provide permitted player UUIDs |

Use [stack.env.example](stack.env.example) for the complete settings and defaults.
When entering values into a Compose-interpolated environment table, escape each
literal `$` as `$$`. Keep production credentials out of Git and screenshots.
Defaults publish port `7000` and preserve the database and backups in named volumes.

## Connect players

1. Download the [matching client jar](../docs/RELEASES.md). For 26.2, install
   Java 25, Fabric Loader 0.19.5+, and the 26.2 releases of Fabric API and YACL.
2. Open the Memory Bank menu with the grave key, then open **Sync**.
3. Enter the sync server URL and **shared token**, then connect.
4. Put an admin token only in **CM Settings → Admin token** when admin actions
   are needed. It is not the Sync tab password.
5. Open a chest and check `/cmsync status`. All clients must use the same
   canonical server ID or configured aliases.

## Optional HTTPS reverse proxy

The supplied Compose stack publishes the service on **host port 7000**.
For nginx running on that host, proxy to `127.0.0.1:7000`:

```nginx
server {
    listen 80;
    server_name chesttracker.example.com;
    client_max_body_size 20m;
    location / {
        proxy_pass http://127.0.0.1:7000;
        proxy_http_version 1.1;
        proxy_set_header Host $http_host;
    }
}
```

Point the domain at the VPS, configure a certificate with your normal HTTPS
tooling, and use `https://chesttracker.example.com` in the client. Container
port `8000` is internal; deployments that run uvicorn directly can use their
own configured host port instead. Keep the proxy body limit large enough for pushes.

For denied connections, HTTP 413, validation errors, backups, and redeploys,
see [Portainer operations](PORTAINER.md) and [Testing](../docs/TESTING.md).
