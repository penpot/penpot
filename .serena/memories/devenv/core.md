# Devenv startup and configuration

Compose-based development environment under `docker/devenv/`, driven by `manage.sh`.

## Source-of-truth layout

- `docker/devenv/defaults.env`: single source of truth for devenv config. Loaded by `manage.sh`'s simple env-file parser and by `docker compose --env-file`. Holds `COMPOSE_PROJECT_NAME`, container names (`PENPOT_MAIN_CONTAINER_NAME`, `PENPOT_VALKEY_CONTAINER_NAME`, `PENPOT_VALKEY_HOSTNAME`), runtime config that is passed into the container env, every published host port, Serena host ports, tmux session/attach defaults. `manage.sh` aborts if the file is unreadable.
- `backend/scripts/_env`: backend-internal defaults only — `PENPOT_*_SHARED_KEY`, `PENPOT_SECRET_KEY`, `PENPOT_FLAGS`, deletion/upload sizes, `PENPOT_NITRATE_BACKEND_URI`, `JAVA_OPTS`, the `setup_minio` function. Never duplicates anything in `defaults.env`.
- `docker/devenv/docker-compose.infra.yml`: shared services — `postgres`, `minio`, `minio-setup`, `mailer`, `ldap`. Attached to external network `penpot_shared`.
- `docker/devenv/docker-compose.main.yml`: main devenv container plus `redis` (valkey). Same network. Pure `${VAR}` references (no inline `:-` defaults) — missing var = compose fails.

## Invariants

- Published ports are host-side only. Compose maps `${PENPOT_*_PORT}:<fixed internal port>` so parallel instances can offset host ports while container-local services keep their normal devenv ports. Do not pass host-side port offsets into processes that expect container-local ports.
- Volume keys in compose are literal (`user_data`, `valkey_data`). Docker prefixes them with `COMPOSE_PROJECT_NAME` to form the actual volume names.
- External network `penpot_shared` is created idempotently by `manage.sh ensure-devenv-network`; `drop-devenv` does **not** remove it.
- `PENPOT_SOURCE_PATH` is set by `manage.sh` to `$PWD` and bind-mounted as `/home/penpot/penpot`. Not in `defaults.env` because its value is dynamic.
- `CURRENT_USER_ID=$(id -u)` is exported by `manage.sh` and passed as `EXTERNAL_UID` so file ownership inside the container matches the host.
- `JAVA_OPTS` exported at the top of `manage.sh` (line ~28) is **shadowed inside the container** by `_env`, which reassigns it unconditionally to a much larger JVM config. The `-e JAVA_OPTS=$JAVA_OPTS` flag that `run-devenv-shell` / `run-devenv-isolated-shell` / `build` pass into `docker run`/`exec` only matters for processes that do not source `_env`.

## MinIO provisioning split

- Shared user/policy: provisioned once by the `minio-setup` one-shot service in the infra compose file. Alias-set loop bounded to 30 attempts. `main` depends on `service_completed_successfully`.
- Per-process bucket creation: `setup_minio()` in `_env`. Idempotent (`mc mb -p`). Short-circuits if `PENPOT_OBJECTS_STORAGE_BACKEND != s3`.

## Tmux session lifecycle

- `docker/devenv/files/start-tmux.sh` is idempotent at the session level. Reads `PENPOT_TMUX_SESSION` (default `penpot`) and `PENPOT_TMUX_ATTACH` (default `true`). If the session exists it attaches or exits depending on `PENPOT_TMUX_ATTACH`; otherwise runs `./scripts/setup` for frontend/exporter and creates the session with frontend-watch / storybook / exporter / backend / optional MCP / optional Serena windows.
- MCP and Serena windows are added only on session create (gated by `enable-mcp` in `PENPOT_FLAGS` and `SERENA_ENABLED=true`). `run-devenv-agentic` against an existing non-agentic session attaches without adding them — kill the session first to recreate.
- `manage.sh run-devenv`: ensures containers, then invokes start-tmux.sh interactively (attaches).
- `manage.sh attach-devenv`: pure attach — fails fast if devenv isn't running or session doesn't exist. Never starts containers. Takes no arguments.

## Lifecycle commands

`manage.sh` thin wrappers around `devenv-compose` (which adds `--env-file defaults.env` and both compose files):
- `start-devenv` / `create-devenv`: pull image if missing, ensure network, `up -d` / `create`.
- `stop-devenv`, `log-devenv`: as expected.
- `drop-devenv`: `down -v` (removes containers + named volumes) and prunes the devenv image. Preserves `penpot_shared`.
- `run-devenv-shell`: starts containers if needed, `docker exec -ti` as `penpot`.
- `run-devenv-isolated-shell` / `build`: one-shot `docker run` against `${COMPOSE_PROJECT_NAME}_user_data` volume + repo bind mount. Not driven by compose.

## Exporter env

`exporter/scripts/run` and `wait-and-start.sh` source `backend/scripts/_env`, then `_env.local` if present. Backend-style env reaches the exporter via that chain.

## MCP routing in parallel devenvs

The normal MCP path is same-origin: frontend computes `<public-uri>/mcp/ws`, the plugin opens that WebSocket, and the instance-local nginx proxies it to the MCP server inside the same main container. This depends on fixed internal ports; per-instance overlays should only change the published host ports and `PENPOT_PUBLIC_URI`.
