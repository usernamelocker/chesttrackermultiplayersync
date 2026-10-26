# Portainer operations

Minecraft 26.2 is the primary client target. One shared server serves 26.2,
26.1.2, and 1.21.11; its files are identical on all three version branches.

## Repository settings

| Setting | Value |
|---|---|
| Repository | `https://github.com/usernamelocker/chesttrackermultiplayersync` |
| Repository reference | `refs/heads/26.2` |
| Compose path from repository root | `server/docker-compose.yml` |
| Server directory / build context | `server/` (Docker Compose uses `build: .` there) |
| Published port | Host `7000` to container `8000` |
| Database volume | Existing `cmsync-data`, mounted at `/data` |
| File-backup volume | Existing `cmsync-backups`, mounted at `/app/backups` |

`26.2` is GitHub's default branch. The former `main` branch has been removed.
If an existing stack explicitly references `main` or `refs/heads/main`, change
only that repository reference to `refs/heads/26.2` before the next pull or
redeploy. Selecting `/server` identifies a directory, not a branch. If your
deployment already follows the repository default, confirm it resolves to 26.2.

## Existing stack: keep the deployment intact

Keep the same stack name, compose path, ports, environment variables, database
path, tokens, server IDs, and named volumes. Portainer may prefix volume names
with the stack name; reuse the existing volumes rather than creating a new stack.
Do not remove data volumes as part of a client upgrade.

The server runtime and Docker configuration preserve the previous production
behavior. This client release requires no server rebuild or database migration.
Changing GitHub's default branch does not modify a running container.

Full environment reference: [stack.env.example](stack.env.example). Keep
`ADMIN_TOKEN` separate from `SHARED_TOKEN`. Preserve configured server aliases.

## Deploying a future server update

Only server runtime or deployment changes require a rebuild; Markdown and test
changes do not. Review the server diff, take a backup, then use the existing
stack's **Update stack** operation with image rebuild enabled. A plain container
restart keeps the old image. Confirm the repository reference is 26.2 first.

Check the container logs for `Uvicorn running`, then check
`http://VPS-IP:7000/health`. No VPS action has been performed merely by
publishing a client jar; an independently configured auto-update may still
react to repository changes.

## Logs and recovery

| Log / symptom | Meaning or action |
|---|---|
| `handshake DENIED` / `pull DENIED` | Check the exact server ID, alias, player UUID, or shared token in the log |
| `FULL WIPE QUARANTINED` | An exact full-bank delete push was held; its snapshot is available for recovery |
| `MASS DELETE` | A partial mass delete was snapshotted and applied |
| `WIPE` | The confirmed admin wipe endpoint was used |
| `422 ... errors=` | Inspect the rejected request field and validation error |
| Client push HTTP 413 | Increase the reverse proxy's body limit; the example uses `20m` |

For a filesystem backup, run `python /app/backup.py` in the container console;
it writes into the existing backup volume. SQLite snapshots remain in the
database volume. See [Backups and restore](../docs/BACKUPS.md).

## Local checks

From `server/`:

```powershell
python -m pytest test_api.py test_server.py test_portable_format.py test_cross_version.py
```

See [Server setup](START-HERE.md), [Server reference](README.md),
[Downloads](../docs/RELEASES.md), and [Testing](../docs/TESTING.md).
