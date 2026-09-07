# Updating pnpm Across All Workspaces

Canonical procedure. Run it from the repo root with the log redirected to a
file (never pipe tool output through filters).

## Layout facts

- The repo has 12 pnpm workspaces, each with its own `pnpm-workspace.yaml`
  and `pnpm-lock.yaml`: the repo root plus `backend`, `common`, `docs`,
  `exporter`, `frontend`, `library`, `mcp`, `media-processor`, `plugins`,
  `render-wasm`, and `plugins/apps/composable-test-suite`.
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
  `allowBuilds: esbuild: set this to true or false` plus
  `ignoredBuiltDependencies`. Repo convention is `allowBuilds: esbuild: true`.
  Replace the placeholder and drop the `ignoredBuiltDependencies` entry,
  then re-run.
- `plugins/apps/composable-test-suite` is both a member of the `plugins`
  workspace and its own workspace root: pnpm picks the nearest
  `pnpm-workspace.yaml` walking up, so commands run inside it use its own
  workspace config and lockfile.
- Expect metadata-only lockfile diffs when only the pnpm version moves:
  the pnpm self-reference entries, plus a new `packageManagerDependencies`
  section in lockfiles last written by older pnpm. Large diffs mean
  re-resolution; inspect them before accepting.

## Verification

- Every `packageManager` field is byte-identical (same version and hash).
- `pnpm --version` in each of the 12 workspaces prints the target version.
- `pnpm install --frozen-lockfile` succeeds in each of the 12 workspaces.
- `git diff` on lockfiles matches the expectations above.
