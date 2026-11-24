# Devenv

- Start with `manage.sh` for command parsing and workspace overrides, `docker/devenv/defaults.env` for defaults, and `docker/devenv/docker-compose.{infra,main}.yml` for service and volume definitions. For commands and ports see `docs/technical-guide/developer/devenv.md`.

## Workspace isolation

- Workspaces share PostgreSQL, the application database and RustFS bucket, and one Valkey server. Each workspace has its own runtime Valkey database and tenant. Valkey Pub/Sub is not scoped to a logical database; `app.msgbus` prefixes topics with the tenant. Do not infer shared notifications from a shared server or shared application rows.
- Backend tests must use the workspace's `PENPOT_TEST_DATABASE_URI` and `PENPOT_TEST_REDIS_URI`. The PostgreSQL test database and Valkey test database number are separate from runtime data; `instance-test-db-name` and `instance-test-redis-db` in `manage.sh` are the source of truth. Provision the test database explicitly for new workspaces: PostgreSQL init scripts run only on a fresh volume.
- Jobs can be inserted into shared PostgreSQL but are dispatched by tenant. Check effective flags, tenant and Valkey destination when a job sits pending; do not assume a worker in another workspace will pick it up. See `mem:prod-infra/core` and `mem:backend/jobs`.

## Lifecycle and configuration

- `infra-compose` and `instance-compose` clear the inherited shell environment, then reinject required values. Compose gives these overrides precedence over `--env-file`; letting sourced defaults leak back into Compose can override the intended workspace values.
- Shared infrastructure stays up until the last workspace stops. Keep named volumes independent of Compose project names; replacing a service or major database version must not silently reuse or erase an incompatible volume. PostgreSQL readiness probes use TCP so the temporary init server does not count as ready.
- `--sync` copies Git metadata and files into the target workspace; it is not a merge. Do not run it over local work you need to keep. The first start seeds a missing workspace; later starts do not sync unless requested. Git selects the files, including tracked paths under ignored directories. Ignored caches survive, and the workspace owns its `config.js` after the initial copy.
- tmux adds conditional windows when it creates a session, not when attaching. The OpenCode config overlay applies at container creation; passing a different path to an attach does not change the mount. MCP uses the workspace's same-origin proxy; see `mem:mcp/core`.
- The backend `_env` can override launcher `JAVA_OPTS`. Frontend devenv flags are applied before user `config.js` flags so the user can override them; backend audit consumers and persistence have their own flags. See `mem:audit-log` for the relationship between consumers and full audit rows.

## Tooling and images

- `scripts/setup-opencode` installs into the persisted user's `PNPM_HOME`, not the image. Non-interactive shells do not load `.bashrc`; keep the script's `PNPM_HOME` and bin-path handling. On UID remap the entrypoint fixes ownership of tool caches and user data, not the host-mounted repo or config.
- `SERENA_UPDATE_VERSION` overrides the image's `SERENA_VERSION` at startup. An older override can silently downgrade Serena; keep them aligned or leave the override empty.
- Check architecture-specific hashes and use the dependency workflows instead of copying pin lists: `mem:workflow/updating-node`, `mem:workflow/updating-pnpm`, `mem:workflow/docker-images`.
