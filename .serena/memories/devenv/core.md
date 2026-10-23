# Devenv startup and configuration

Compose-based dev environment under `docker/devenv/`, driven by `manage.sh`. Parallel instances share infra + Postgres + RustFS; each instance has its own `main` container, Valkey, source checkout, tmux session. The main database (`penpot`) and the S3 bucket (`penpot`) are shared by all instances on purpose; backend-test databases are isolated per instance (`penpot_test` on ws0, `penpot_test_wsN` on wsN) with per-instance Valkey DB numbers (`6+N`, since runtime backends occupy `0..5`).

## Compose project layout

- `penpotdev-infra`: shared `postgres`, `rustfs`, `valkey`, `mailer`, `ldap`. File: `docker-compose.infra.yml`.
- `penpotdev-wsN` (N=0,1,…): per-instance `main` + `redis` (Valkey). File: `docker-compose.main.yml`. ws0 (a.k.a. `main`) binds `$PWD`; ws1+ bind clones at `${PENPOT_WORKSPACES_DIR}/wsN/` (default `~/.penpot/penpot_workspaces/`), maintained by the developer.
- Optional overlay `docker-compose.opencode.yml`: added by `instance-compose` as an extra `-f` only when `PENPOT_OPENCODE_CONFIG_DIR` is set (i.e. `run-devenv --opencode-config-dir DIR` ran in this process). Bind-mounts the host dir at `/home/penpot/.config/opencode` (`:z`). Flag-only, per-call; not read from ambient env. Parser `parse-opencode-config-dir` absolutizes (`~`, realpath) because compose resolves relative bind sources against the compose file's dir. Only instances brought up with the flag get the mount.
- All projects join external network `penpot_shared`. Created idempotently by `ensure-devenv-network`, never removed by lifecycle commands.

## Source-of-truth files

- `docker/devenv/defaults.env`: ws0 baseline — container/volume names, runtime env, published host ports, tmux defaults. `manage.sh` aborts if unreadable.
- For ws1+, `instance-env-overrides` computes the per-instance overrides (container/volume names, host ports offset `10000·N`, `PENPOT_PUBLIC_URI`, `PENPOT_REDIS_URI`, `PENPOT_TEST_DATABASE_URI`, `PENPOT_TEST_REDIS_URI`) and `instance-compose` injects them as env vars at compose time — never written to disk, recomputed each call so they can't drift. ws0 uses `defaults.env` as-is.
- Per-instance test databases are created by `ensure-instance-test-database` (called from `start-instance`): `CREATE DATABASE` guarded by a `pg_database` existence check, so it is idempotent. Needed because `postgresql_init.sql` only runs on first volume creation. The backend applies migrations itself on first use, so an empty database is enough.
- `backend/scripts/_env`: backend-internal only — secret keys, `PENPOT_FLAGS` (with `enable-backend-worker` gated on `PENPOT_BACKEND_WORKER`), `JAVA_OPTS`, `setup_s3_bucket()`. Never duplicates `defaults.env`.
- Compose files use pure `${VAR}` substitution; missing var = compose fails.

## Invariants

- `infra-compose` / `instance-compose` wrap `docker compose` with `env -i`, then re-inject what compose needs. Stripping is required because `defaults.env` is sourced into manage.sh's shell at startup (stale values would leak); the ws1+ overrides are deliberately re-injected as shell env vars precisely because Compose gives shell precedence over `--env-file`, so they override the `defaults.env` baseline.
- Volume names pinned via `name:` (PENPOT_*_VOLUME), decoupled from the compose project name. Replacement services use new volumes and leave old volumes untouched. PostgreSQL 18 mounts `penpotdev_postgres_data_pg18` at `/var/lib/postgresql`; the old PG16 volume is not migrated automatically.
- Network aliases (`- main`, `- redis`) are not declared in main.yml. Compose's auto-service-alias still registers `redis` on the shared network, so DNS for `redis` is non-deterministic with multiple instances. Backend uses `PENPOT_REDIS_URI=redis://penpot-devenv-wsN-valkey/0` (container_name) instead.
- No cross-project `depends_on`. `manage.sh ensure-infra-up` uses Compose `--wait`; PostgreSQL, RustFS, and Mailpit expose healthchecks. PostgreSQL checks TCP so its temporary init server cannot report ready.
- `JAVA_OPTS` in `manage.sh` is shadowed inside the container by `_env`. The `-e JAVA_OPTS=...` flag only matters for processes that don't source `_env`.
- opencode is not part of the devenv image. `scripts/setup-opencode` installs the `@opencode/cli` global pnpm package into the penpot user's `$PNPM_HOME` (`/home/penpot/.local/share/pnpm`, inside the user-data volume, so it survives a container re-creation); nothing pins the version in the repo, it tracks the `latest` dist-tag at install time. pnpm 12 puts the global packages in `$PNPM_HOME/global` and the bin dir in `$PNPM_HOME/bin`, and aborts with `ERR_PNPM_GLOBAL_BIN_DIR_NOT_IN_PATH` unless that bin dir is on `PATH`; `files/bashrc` and the script both add it. The script exports `PNPM_HOME` itself because non-interactive shells never read `~/.bashrc`.
- `files/entrypoint.sh` chowns penpot's whole `~/.local` and `~/.cache` on every start, because `EXTERNAL_UID` remaps penpot's UID to the host user and build-time ownership then stops matching. That covers pnpm's global dir, store, `package-manager-store` and metadata cache, plus opencode's own state; without it, global installs and pnpm's `packageManager` auto-download fail on permissions. The dotfiles copied from `/root` (`.bashrc`, `.vimrc`, `.tmux.conf`) are chowned in the same pass. `~/.config` stays out on purpose (the opencode overlay bind-mounts a host directory inside it) and so does `/home/penpot` itself, which holds the repo bind mount.

## Worker policy

Backend workers run only on ws0. `_env` gates `enable-backend-worker` on `PENPOT_BACKEND_WORKER`; ws1+ inject it as false. Workers are pure fire-and-forget: `jobs/submit` inserts a row into the shared Postgres `job` table and returns (the legacy `task` table stays dormant); RPC handlers never wait on completion and workers never publish to msgbus. The reason for "ws0 only" is avoiding multi-instance worker races (cron dedup is best-effort across instances, `jobs/submit` `dedupe` is racy across submitters); details in `mem:prod-infra/core`.

Each workspace is independent and can be started/stopped in any order. Shared infra (Postgres, RustFS, etc.) is shut down only when no instances remain running.

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

Everything else (frontend dev, backend API, exporter, storybook, REPLs, plugin dev, MCP inspector/WebSocket) is in-process or same-origin via Caddy/nginx. Infra publishes RustFS S3 API 9000, RustFS console 9001, and Mailpit UI 1080 on loopback; ldap 10389/10636 remains a singleton without offsets. Mailpit stores its SQLite database in `penpotdev_mailpit_data`.

## Tmux + MCP routing

`docker/devenv/files/start-tmux.sh` is session-level idempotent. Reads `PENPOT_TMUX_ATTACH`. If the session exists it attaches or exits; otherwise creates 4 base windows (frontend watch / storybook / exporter / backend) plus `mcp` (when `enable-mcp` in `PENPOT_FLAGS`) and `serena` (when `SERENA_ENABLED=true`). `run-devenv --agentic` always sets both env vars. The legacy `run-devenv` alias doesn't, hence its 4-window-only session. To switch from a legacy session to agentic, `stop-devenv` then `run-devenv --agentic` — the conditional windows are only added at session create time.

MCP plugin routing is same-origin: frontend uses `<public-uri>/mcp/ws`, per-instance nginx proxies to MCP port 4401 in-container. For the plugin↔MCP server wiring (how the browser plugin discovers the URL, the in-memory connection registry, why DB-mediated routing isn't needed), see `mem:mcp/core`.

## Workspace orchestration (ws1+)

Workspace directories are user-maintained at `${PENPOT_WORKSPACES_DIR}/wsN`. `run-devenv --agentic --ws i` syncs only when `--sync` is passed, with one exception: if the workspace directory is missing on first use, sync runs implicitly to seed it.

`sync-workspace wsN`:
1. `assert-clean-git-state` — refuses on `.git/{rebase-apply,rebase-merge,MERGE_HEAD,CHERRY_PICK_HEAD,index.lock}`. No `--sync-force` escape.
2. `rsync -a --delete $PWD/.git/ $workspace/.git/`.
3. `git ls-files -z --cached --others --exclude-standard` → `rsync --files-from` (Git is the authority on tracked files; rsync's gitignore filter would drop committed files under gitignored parents like `.clj-kondo/config.edn`).
4. Initial-only copy of `frontend/resources/public/js/config.js` (gitignored, but agentic mode needs it). After the first sync the workspace's copy belongs to the developer — subsequent syncs leave it alone.
5. `git switch -C "wsN/<current-branch>"` inside the workspace.

No `--delete` on the working-tree pass: gitignored caches in the workspace survive. Workspace dir + named volumes survive `compose down`.

## CLI surface

- `run-devenv --agentic [--ws main|0|wsN|N] [--sync] [--serena-context CTX] [--opencode-config-dir DIR]`: bring one instance up. Agentic only — MCP and Serena windows are always created. Default target main. Errors out if the target is already running. `--sync` is rejected on main; on ws1+ it's optional (forced only when the workspace dir does not exist yet). `--opencode-config-dir DIR` bind-mounts DIR at `~/.config/opencode` in-container via the optional overlay above; mount applies at container creation, so changing it requires stop + re-run.
- `stop-devenv [--ws main|0|wsN|N] [--all]`: stop instances. Flags mutually exclusive. `--ws N` stops just that workspace. `--ws 0` or no flag stops ws0; shared infra shuts down only if no other instances remain. `--all` stops every ws highest-first then ws0, then infra.
- `run-devenv`: legacy alias, ws0 non-agentic attached.
- `attach-devenv [--ws main|0|wsN|N]`: pure attach. Fails fast if instance/session missing.
- `run-devenv-shell [--instance 0|wsN|N] [cmd...]`: bash in target instance. (`--instance` flag not yet renamed to `--ws`.)
- `start-devenv` / `log-devenv` / `drop-devenv`: legacy paths around ws0 + shared infra. `drop-devenv` never removes volumes.

`exporter/scripts/run` and `wait-and-start.sh` source `backend/scripts/_env` then `_env.local` if present.

## Image and dependency pins

Two Dockerfile trees, both multi-arch (`linux/amd64,linux/arm64`):
`docker/devenv` (dev) and `docker/images` + `docker/imagemagick` (prod).

- Hand-pinned versions, one source each:
  - `docker/devenv/Dockerfile`: `NODE_VERSION` (+ per-arch `NODE_SHA256`),
    `PNPM_VERSION` (+ `PNPM_SHA256`), `CADDY_VERSION`, `CLOJURE_VERSION`,
    Zulu JDK URL + `ESUM`, `RUSTUP_VERSION`/`RUST_VERSION`/
    `EMSCRIPTEN_VERSION`, `CLJKONDO_VERSION`, `BABASHKA_VERSION`,
    `CLJFMT_VERSION`, `PIXI_VERSION`, `GITHUB_CLI_VERSION`,
    `AWS_CLI_VERSION` (+ `BINARY_SHA256`), `UV_VERSION`, `SERENA_VERSION`,
    `postgresql-client-18`, and the `penpotapp/imagemagick:<tag>`
    `COPY --from`.
  - `docker/images/Dockerfile.backend`: Zulu JDK URL + `ESUM`. The `jlink`
    `--add-modules` list must keep `jdk.management`
    (`mem:backend/subtleties`).
  - `docker/images/Dockerfile.{exporter,mcp,media-processor}`: `ARG
    PNPM_VERSION` (+ arch SHAs, same values as devenv).
- Node and pnpm must agree in five places: `.nvmrc`, the devenv tarball pin,
  the two `plugins-deploy-*` workflows that set `runtime: node@<version>`, and
  the four Dockerfile pins. pnpm procedure: `mem:workflow/updating-pnpm`.
- `dhi.io` base images are pinned `tag@sha256:<index digest>`; Renovate moves
  them (`penpot-renovate[bot]`, commits titled `:arrow_up: Update DHI base
  images`). Tag and digest move together: a new tag with the old digest still
  pulls the old image, and the digest must be the multi-arch index, not a
  per-platform one.
- Every hand-pinned checksum is per architecture; one wrong value breaks half
  of the multi-arch build.
- `SERENA_UPDATE_VERSION` in `docker-compose.main.yml` must stay empty. Any
  value makes `start-tmux.sh` reinstall that Serena release on every start,
  overriding the `SERENA_VERSION` baked into the image (it silently replaced
  1.7.0 with 1.6.1). Set it only to test one specific release on purpose.
- Infra images live in `docker-compose.infra.yml`: `rustfs/rustfs` (owns the
  S3 data volume), `axllent/mailpit` (SQLite in a volume), `valkey/valkey`,
  `postgres:18.6`, `rroemhild/test-openldap`. Keep the volume names stable
  when bumping them; replacement services get new volumes and leave the old
  data untouched.
- Build locally: `docker build -f docker/devenv/Dockerfile docker/devenv/`
  and `cd docker/images && ./build.sh <image> --local`. CI:
  `build-docker-devenv.yml`, `build-docker.yml`.
