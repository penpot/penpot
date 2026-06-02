# Devenv startup and configuration

Compose-based dev environment under `docker/devenv/`, driven by `manage.sh`. Parallel instances share infra + Postgres + MinIO; each instance has its own `main` container, Valkey, source checkout, tmux session.

## Compose project layout

- `penpotdev-infra`: shared `postgres`, `minio`, `minio-setup`, `mailer`, `ldap`. File: `docker-compose.infra.yml`.
- `penpotdev-wsN` (N=0,1,…): per-instance `main` + `redis` (Valkey). File: `docker-compose.main.yml`. ws0 (a.k.a. `main`) binds `$PWD`; ws1+ bind clones at `${PENPOT_WORKSPACES_DIR}/wsN/` (default `~/.penpot/penpot_workspaces/`), maintained by the developer.
- All projects join external network `penpot_shared`. Created idempotently by `ensure-devenv-network`, never removed by lifecycle commands.

## Source-of-truth files

- `docker/devenv/defaults.env`: ws0 baseline — container/volume names, runtime env, published host ports, tmux defaults. `manage.sh` aborts if unreadable.
- For ws1+, `instance-env-overrides` computes the per-instance overrides (container/volume names, host ports offset `10000·N`, `PENPOT_PUBLIC_URI`, `PENPOT_REDIS_URI`, `PENPOT_BACKEND_WORKER=false`) and `instance-compose` injects them as env vars at compose time — never written to disk, recomputed each call so they can't drift. ws0 uses `defaults.env` as-is.
- `backend/scripts/_env`: backend-internal only — secret keys, `PENPOT_FLAGS` (with `enable-backend-worker` gated on `PENPOT_BACKEND_WORKER`), `JAVA_OPTS`, `setup_minio()`. Never duplicates `defaults.env`.
- Compose files use pure `${VAR}` substitution; missing var = compose fails.

## Invariants

- `infra-compose` / `instance-compose` wrap `docker compose` with `env -i`, then re-inject what compose needs. Stripping is required because `defaults.env` is sourced into manage.sh's shell at startup (stale values would leak); the ws1+ overrides are deliberately re-injected as shell env vars precisely because Compose gives shell precedence over `--env-file`, so they override the `defaults.env` baseline.
- Volume names pinned via `name:` (PENPOT_*_VOLUME), decoupled from the compose project name. ws1+ inject distinct per-instance volume names; ws0 keeps the historical `penpotdev_*` physical names so project renames never require data migration.
- Network aliases (`- main`, `- redis`) are not declared in main.yml. Compose's auto-service-alias still registers `redis` on the shared network, so DNS for `redis` is non-deterministic with multiple instances. Backend uses `PENPOT_REDIS_URI=redis://penpot-devenv-wsN-valkey/0` (container_name) instead.
- No cross-project `depends_on`. `manage.sh ensure-infra-up` `docker wait`s on the `minio-setup` one-shot.
- `JAVA_OPTS` in `manage.sh` is shadowed inside the container by `_env`. The `-e JAVA_OPTS=...` flag only matters for processes that don't source `_env`.

## Worker policy

Backend workers run only on ws0. `_env` gates `enable-backend-worker` on `PENPOT_BACKEND_WORKER`; ws1+ inject it as false. ws0 must be running whenever any ws1+ is running, and is the last instance to stop — `run-devenv-agentic --ws N` (N≥1) auto-starts ws0 first; `stop-devenv` refuses to stop ws0 while any ws1+ is up. Workers are pure fire-and-forget: `wrk/submit!` inserts a row into the shared Postgres `task` table and returns; RPC handlers never wait on completion and workers never publish to msgbus. The reason for "ws0 only" is avoiding multi-instance worker races (cron dedup is best-effort across instances, `wrk/submit!` `dedupe` is racy across submitters); details in `mem:prod-infra/core`.

## Port layout

Container-internal ports fixed; host side offset `10000·N`.

| ws0 | ws1 | wsN | container | role |
|---|---|---|---|---|
| 3449 | 13449 | 3449+10000·N | 3449 | public HTTPS (Caddy; `/mcp/ws` same-origin) |
| 3449/udp | 13449/udp | … | 3449/udp | HTTP/3 |
| 4401 | 14401 | … | 4401 | MCP HTTP stream |
| 4403 | 14403 | … | 4403 | MCP REPL |
| 14181 | 24181 | … | 14281 | Serena MCP |
| 14182 | 24182 | … | 24282 | Serena dashboard |

Everything else (frontend dev, backend API, exporter, storybook, REPLs, plugin dev, MCP inspector/WebSocket) is in-process or same-origin via Caddy/nginx. Infra publishes: mailer 1080, ldap 10389/10636 (singletons, not offset).

## Tmux + MCP routing

`docker/devenv/files/start-tmux.sh` is session-level idempotent. Reads `PENPOT_TMUX_ATTACH`. If the session exists it attaches or exits; otherwise creates 4 base windows (frontend watch / storybook / exporter / backend) plus `mcp` (when `enable-mcp` in `PENPOT_FLAGS`) and `serena` (when `SERENA_ENABLED=true`). `run-devenv-agentic` always sets both env vars. The legacy `run-devenv` alias doesn't, hence its 4-window-only session. To switch from a legacy session to agentic, `stop-devenv` then `run-devenv-agentic` — the conditional windows are only added at session create time.

MCP plugin routing is same-origin: frontend uses `<public-uri>/mcp/ws`, per-instance nginx proxies to MCP port 4401 in-container. For the plugin↔MCP server wiring (how the browser plugin discovers the URL, the in-memory connection registry, why DB-mediated routing isn't needed), see `mem:mcp/core`.

## Workspace orchestration (ws1+)

Workspace directories are user-maintained at `${PENPOT_WORKSPACES_DIR}/wsN`. `run-devenv-agentic --ws i` syncs only when `--sync` is passed, with one exception: if the workspace directory is missing on first use, sync runs implicitly to seed it.

`sync-workspace wsN`:
1. `assert-clean-git-state` — refuses on `.git/{rebase-apply,rebase-merge,MERGE_HEAD,CHERRY_PICK_HEAD,index.lock}`. No `--sync-force` escape.
2. `rsync -a --delete $PWD/.git/ $workspace/.git/`.
3. `git ls-files -z --cached --others --exclude-standard` → `rsync --files-from` (Git is the authority on tracked files; rsync's gitignore filter would drop committed files under gitignored parents like `.clj-kondo/config.edn`).
4. Initial-only copy of `frontend/resources/public/js/config.js` (gitignored, but agentic mode needs it). After the first sync the workspace's copy belongs to the developer — subsequent syncs leave it alone.
5. `git switch -C "wsN/<current-branch>"` inside the workspace.

No `--delete` on the working-tree pass: gitignored caches in the workspace survive. Workspace dir + named volumes survive `compose down`.

## CLI surface

- `run-devenv-agentic [--ws main|0|wsN|N] [--sync] [--serena-context CTX]`: bring one instance up. Agentic only — MCP and Serena windows are always created. Default target main. Errors out if the target is already running. `--sync` is rejected on main; on ws1+ it's optional (forced only when the workspace dir does not exist yet). Auto-starts ws0 first when the target is ws1+ and ws0 is not yet up.
- `stop-devenv [--ws main|0|wsN|N] [--all]`: stop instances. Flags mutually exclusive. `--ws N` (N≥1) stops just that workspace. `--ws 0` or no flag stops ws0 + shared infra, refused while any ws1+ is running. `--all` stops every ws highest-first then ws0, then infra.
- `run-devenv`: legacy alias, ws0 non-agentic attached.
- `attach-devenv [--ws main|0|wsN|N]`: pure attach. Fails fast if instance/session missing.
- `run-devenv-shell [--instance 0|wsN|N] [cmd...]`: bash in target instance. (`--instance` flag not yet renamed to `--ws`.)
- `start-devenv` / `log-devenv` / `drop-devenv`: legacy paths around ws0 + shared infra. `drop-devenv` never removes volumes.

`exporter/scripts/run` and `wait-and-start.sh` source `backend/scripts/_env` then `_env.local` if present.
