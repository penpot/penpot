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
- Every `package.json` (about 35 of them) must carry a `packageManager` field
  with the identical `pnpm@<version>+sha512.<hash>` value. Do not let them drift.
- CI pins no pnpm version; workflows rely on corepack reading
  `packageManager`. Fixing the fields fixes CI.

## Procedure

1. Resolve the target tag first and note the version. Example:
   `npm view pnpm dist-tags --json` for `next-12` (latest 12.x). The tag
   moves over time; always re-check.
2. List every directory with a `package.json`, excluding `node_modules`
   (`fd -H -t f package.json -E node_modules`). This list is the work set;
   do not maintain a hand-written list.
3. Run `corepack use pnpm@<tag>` in workspace roots first, then members.
   `corepack use` stamps `packageManager` in the nearest package.json and
   runs an install. Member runs repeat the workspace install; after the root
   run they are quick no-ops.
4. If a run fails, fix the cause (see gotchas) and re-run that directory.

## Gotchas

- `corepack use` only updates an existing `packageManager` field. If a
  package.json lacks the field, corepack walks up to the nearest ancestor
  that has one and stamps that file instead; the member stays unstamped.
  After the sweep, assert every package.json carries the field. For a
  missing one, insert the identical `pnpm@<version>+sha512.<hash>` string,
  then re-run `corepack use pnpm@<tag>` in that directory.
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

- Every `packageManager` field is byte-identical (same version and hash).
- `pnpm --version` in each workspace prints the target version.
- `pnpm install --frozen-lockfile` succeeds in each of the 11 workspaces.
- `git diff` on lockfiles matches the expectations above.

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
