# Updating pnpm Across All Workspaces

Canonical procedure. Run it from the repo root with the log redirected to a
file (never pipe tool output through filters).

## Layout facts

- The repo has 11 pnpm workspaces, each with its own `pnpm-workspace.yaml`
  and `pnpm-lock.yaml`: the repo root plus `backend`, `common`, `docs`,
  `exporter`, `frontend`, `library`, `mcp`, `media-processor`, `plugins`,
  and `render-wasm`.
- Every package inside a module workspace (for example all `plugins/apps/*`
  and `plugins/libs/*` packages) is a plain member of that module's
  workspace. Members must not carry their own `pnpm-workspace.yaml` or
  `pnpm-lock.yaml`; their dependencies resolve through the parent
  workspace's lockfile.
- One shared pnpm store for the whole repo: `<repo>/.pnpm-store`. Every
  workspace yaml sets it explicitly: `storeDir: .pnpm-store` at the root,
  `storeDir: ../.pnpm-store` in each module. pnpm resolves the value
  against the workspace root, so all workspaces land on the same store.
  Do not remove these lines: nested workspaces do not inherit settings,
  and without them each workspace may resolve a different store.
- The store survives `node_modules` cleans. It is content-addressed and
  integrity-verified, so it cannot go stale; staleness lives in
  node_modules. Only `scripts/clean-node-modules --store` removes it.
- Every first-party `package.json` (35 files: workspace roots plus
  members) must carry a `packageManager` field with the identical
  `pnpm@<version>+sha512.<hash>` value. Do not let them drift.
  `external/` (vendored trees with their own lifecycles), `.opencode/`,
  `.pnpm-store/`, `bundles/` and `docker/images/bundle-*` copies are
  never stamped.
- pnpm is a system binary everywhere (devenv image, CI runners, Docker
  images). Nothing may call corepack: it is gone from Node 25+. pnpm
  auto-downloads the `packageManager` version on mismatch
  (`pmOnFail: download`, the default), so drift self-heals; aligned pins
  just skip the download.
- The pnpm version pin lives in three places that move together:
  `PNPM_VERSION` (+ arch SHAs) in `docker/devenv/Dockerfile` and in
  `docker/images/Dockerfile.{media-processor,exporter,mcp}`, and the
  `packageManager` fields (stamped by the script below). The
  `plugins-deploy-*` workflows need no pnpm pin: a single `pnpm/setup`
  step (`working-directory: plugins`, `install: false`) reads it from
  the manifest and installs the pinned Node via `runtime: node@<exact>`;
  bump that pin together with `.nvmrc` on Node updates.

## Procedure

1. Resolve the target version first and note it. Example:
   `pnpm view pnpm dist-tags --json` for `latest-12` (latest 12.x). The
   tag moves over time; always re-check.
2. Bump `PNPM_VERSION` (+ arch SHAs) in `docker/devenv/Dockerfile` and in
   `docker/images/Dockerfile.{media-processor,exporter,mcp}`.
3. From the repo root, on a host whose system pnpm is the target version
   (rebuilt devenv), run `scripts/sync-pnpm-version`. It resolves the
   integrity hash via `pnpm view` + node (no npm, no corepack, no
   python3) and stamps the identical field into all 35 files, replacing
   only the value line (each file keeps its own indent) and inserting
   the key after `"name"`/`"version"` where missing. Explicit version
   instead: `scripts/sync-pnpm-version <version>`.
4. Run `scripts/sync-pnpm-version --install` (plain `pnpm install` in
   each of the 11 workspace roots) to refresh lockfile metadata, then
   fix any failing workspace (see gotchas) and re-run that directory.

## Gotchas

- A workspace may fail with `ERR_PNPM_IGNORED_BUILDS`, and pnpm then writes
  a placeholder scaffold into its `pnpm-workspace.yaml`:
  `allowBuilds: esbuild: set this to true or false`. Current pnpm writes
  only the `allowBuilds` placeholder; any legacy key still present
  (`ignoredBuiltDependencies`, `onlyBuiltDependencies`,
  `neverBuiltDependencies`) is ignored since pnpm 11. Repo convention is
  `allowBuilds: esbuild: true`. Replace the placeholder and drop the
  legacy entry, then re-run.
- `plugins/apps/composable-test-suite` once had its own
  `pnpm-workspace.yaml` and acted as a nested workspace root. That state is
  gone on purpose: pnpm picks the nearest `pnpm-workspace.yaml` walking up,
  so a nested one silently forks install and lockfile behavior. Do not
  reintroduce it.
- Expect metadata-only lockfile diffs when only the pnpm version moves:
  the pnpm self-reference entries, plus a new `packageManagerDependencies`
  section in lockfiles last written by older pnpm. Large diffs mean
  re-resolution; inspect them before accepting.

## Verification

- `scripts/sync-pnpm-version --check` passes: every `packageManager`
  field is byte-identical (same version and hash).
- `pnpm install --frozen-lockfile` succeeds in each of the 11 workspaces.
- `git diff` on lockfiles matches the expectations above.
- No `corepack` call remains in scripts, workflows, Dockerfiles or docs
  (`rg corepack` shows only `CHANGES.md` history).

## Cleaning stale node_modules

- `scripts/clean-node-modules` removes every workspace `node_modules`: the
  repo root, all module workspaces, and all member packages. Use it when
  installs misbehave after dependency changes: clean, reinstall, done.
- Flags: `-n/--dry-run` lists without deleting; `--store` also removes the
  shared pnpm store at `<repo>/.pnpm-store` (the next install re-downloads
  what it held). `external/` (vendored dependency trees with their own
  lifecycles) and `.opencode/` are always ignored.
- The script never touches the pnpm store by default, so the reinstall
  after cleaning reuses cached packages (zero downloads).
- After cleaning, run `pnpm install` in each workspace root to restore the
  development environment; `frontend` postinstall also reinstalls and
  builds `plugins-runtime`.
