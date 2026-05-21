# Devenv startup and configuration

Compose-based dev environment under `docker/devenv/`, driven by `manage.sh`. Parallel instances share infra + Postgres + MinIO; each instance has its own `main` container, Valkey, source checkout, tmux session.

## Compose project layout

- `penpotdev-infra`: shared `postgres`, `minio`, `minio-setup`, `mailer`, `ldap`. File: `docker-compose.infra.yml`.
- `penpotdev-wsN` (N=0,1,…): per-instance `main` + `redis` (Valkey). File: `docker-compose.main.yml`. ws0 binds `$PWD`; ws1+ bind clones at `~/.penpot/penpot_workspaces/wsN/`.
- All projects join external network `penpot_shared`. Created idempotently by `ensure-devenv-network`, never removed by lifecycle commands.

## Source-of-truth files

- `docker/devenv/defaults.env`: ws0 baseline — container/volume names, runtime env, published host ports, tmux defaults. `manage.sh` aborts if unreadable.
- `docker/devenv/instances/wsN.env` (N≥1): auto-generated per reconciler pass. Overrides project name, container names, volume names, host ports (offset `10000·N`), `PENPOT_PUBLIC_URI`, `PENPOT_REDIS_URI`, `PENPOT_BACKEND_WORKER=false`, `PENPOT_SOURCE_PATH`. Gitignored.
- `backend/scripts/_env`: backend-internal only — secret keys, `PENPOT_FLAGS` (with `enable-backend-worker` gated on `PENPOT_BACKEND_WORKER`), `JAVA_OPTS`, `setup_minio()`. Never duplicates `defaults.env`.
- Compose files use pure `${VAR}` substitution; missing var = compose fails.

## Invariants

- `infra-compose` / `instance-compose` wrap `docker compose` with `env -i`. Without it, sourcing `defaults.env` into the shell at startup would shadow per-instance overlay `--env-file` (Compose gives shell precedence over `--env-file`).
- Volume names pinned via `name:` (PENPOT_*_VOLUME), decoupled from the compose project name. ws1+ overlays set distinct per-instance volume names; ws0 keeps the historical `penpotdev_*` physical names so project renames never require data migration.
- Network aliases (`- main`, `- redis`) are not declared in main.yml. Compose's auto-service-alias still registers `redis` on the shared network, so DNS for `redis` is non-deterministic with multiple instances. Backend uses `PENPOT_REDIS_URI=redis://penpot-devenv-wsN-valkey/0` (container_name) instead.
- No cross-project `depends_on`. `manage.sh ensure-infra-up` `docker wait`s on the `minio-setup` one-shot.
- `JAVA_OPTS` in `manage.sh` is shadowed inside the container by `_env`. The `-e JAVA_OPTS=...` flag only matters for processes that don't source `_env`.

## Worker policy

Backend workers run only on ws0. Task queue is shared (one Postgres DB) but Pub/Sub is per-instance Valkey: a task triggered from ws0's UI must complete on ws0 so its notification reaches the originating WebSocket. `_env` gates `enable-backend-worker` on `PENPOT_BACKEND_WORKER`; ws1+ overlays set it to false. Known consequence: async tasks triggered from a ws1+ tab won't see completion notifications.

## Port layout

Container-internal ports fixed; host side offset `10000·N`.

| ws0 | ws1 | wsN | container | role |
|---|---|---|---|---|
| 3449 | 13449 | 3449+10000·N | 3449 | public HTTPS (Caddy; `/mcp/ws` same-origin) |
| 3449/udp | 13449/udp | … | 3449/udp | HTTP/3 |
| 4401 | 14401 | … | 4401 | MCP HTTP stream |
| 4403 | 14403 | … | 4403 | MCP REPL |
| 14281 | 24281 | … | 14281 | Serena MCP |
| 14282 | 24282 | … | 24282 | Serena dashboard |

Everything else (frontend dev, backend API, exporter, storybook, REPLs, plugin dev, MCP inspector/WebSocket) is in-process or same-origin via Caddy/nginx. Infra publishes: mailer 1080, ldap 10389/10636 (singletons, not offset).

## Tmux + MCP routing

`docker/devenv/files/start-tmux.sh` is session-level idempotent. Reads `PENPOT_TMUX_SESSION` and `PENPOT_TMUX_ATTACH`. If the session exists it attaches or exits; otherwise creates 4 base windows (frontend watch / storybook / exporter / backend) plus optional `mcp` (when `enable-mcp` in `PENPOT_FLAGS`) and `serena` (when `SERENA_ENABLED=true`). The conditional windows are added only on create — to switch from non-agentic to agentic, kill the session first.

MCP plugin routing is same-origin: frontend uses `<public-uri>/mcp/ws`, per-instance nginx proxies to MCP port 4401 in-container. For the plugin↔MCP server wiring (how the browser plugin discovers the URL, the in-memory connection registry, why DB-mediated routing isn't needed), see `mem:mcp/core`.

## Workspace orchestration (ws1+)

`sync-workspace wsN`:
1. `assert-clean-git-state` — refuses on `.git/{rebase-apply,rebase-merge,MERGE_HEAD,CHERRY_PICK_HEAD,index.lock}`.
2. `rsync -a --delete $PWD/.git/ $workspace/.git/`.
3. `git ls-files -z --cached --others --exclude-standard` → `rsync --files-from` (Git is the authority on tracked files; rsync's gitignore filter would drop committed files under gitignored parents like `.clj-kondo/config.edn`).
4. `git switch -C "wsN/<current-branch>"` inside the workspace.

No `--delete` on the working-tree pass: gitignored caches in the workspace survive. Workspace dir + named volumes survive `compose down`.

## CLI surface

- `run-devenv-agentic [--n-instances N] [--no-mcp] [--no-serena] [--serena-context CTX]`: desired-state reconciler. Brings the running set to exactly `{ws0..ws(N-1)}`. Missing → sync + env-file + `compose up` + detached tmux. Extra → `compose down` highest-first (never `-v`). Running-in-target → left alone. `--n-instances 0` is rejected.
- `run-devenv`: legacy alias, ws0 non-agentic attached.
- `attach-devenv [--instance 0|wsN|N]`: pure attach. Fails fast if instance/session missing.
- `run-devenv-shell [--instance 0|wsN|N] [cmd...]`: bash in target instance.
- `start-devenv` / `stop-devenv` / `log-devenv` / `drop-devenv`: operate on infra + all parallel instances. `drop-devenv` never removes volumes.

`exporter/scripts/run` and `wait-and-start.sh` source `backend/scripts/_env` then `_env.local` if present.
