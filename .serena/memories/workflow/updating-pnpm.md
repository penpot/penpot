# Updating pnpm across workspaces

Run from the repo root and redirect logs to a file rather than piping tool output through filters. For Node pins see `mem:workflow/updating-node`; for devenv cache ownership see `mem:devenv/core`.

## Layout and pins

- Each workspace root has its own `pnpm-workspace.yaml` and `pnpm-lock.yaml`; packages inside a module are members, not nested workspaces. A nested workspace file changes which lockfile pnpm uses. See `scripts/sync-pnpm-version` for the current scan and workspace list.
- Every workspace config points to the shared `<repo>/.pnpm-store` relative to its root. Nested workspaces do not inherit that setting. The content-addressed store survives a normal `node_modules` clean.
- Keep the same `packageManager` value, including its integrity hash, across first-party manifests. The sync script excludes vendored trees, `.opencode/`, caches, `target/` build output and Docker build-context copies; `--check` detects drift in the scanned manifests.
- pnpm is installed as a system binary; do not use corepack. A mismatched manifest pin can cause pnpm to download the pinned version. The devenv and production image Dockerfiles also pin `PNPM_VERSION` and architecture-specific checksums. Plugin deploy workflows read the pnpm version from the plugins manifest; their Node pin is separate.

## Procedure

1. Resolve the target version first; a registry dist-tag moves over time.
2. Update `PNPM_VERSION` and architecture checksums in `docker/devenv/Dockerfile` and `docker/images/Dockerfile.{media-processor,exporter,mcp}`. Compute SHA-256 from each GitHub release tarball; npm `dist.integrity` is a different hash of a different artifact.
3. Run `scripts/sync-pnpm-version <version>` to stamp manifests. It resolves npm integrity through `pnpm view` and Node; the explicit version works even when the system pnpm is older. Without an argument it uses `pnpm --version`.
4. Refresh lockfiles in every workspace. `scripts/sync-pnpm-version --install` runs `pnpm install` in each; for a version-only bump, `pnpm install --lockfile-only` in each root can suffice. Inspect diffs: unexpected dependency upgrades indicate re-resolution, not merely a pnpm self-reference update.

## Gotchas and verification

- `ERR_PNPM_IGNORED_BUILDS` can leave an `allowBuilds` placeholder in a workspace config. Set the intended policy, such as the repo's `esbuild: true`, remove obsolete build-dependency keys ignored by current pnpm, and rerun the affected install.
- Backport and maintenance merges can leave Docker, Node, workflow and manifest pins on different versions. Resolve the intended consistent set across all pin sites, not just the merge direction; follow `mem:workflow/updating-node` for Node policy.
- Lockfile conflicts can span importer, package and snapshot self-reference entries. Preserve each workspace's own dependency resolution when updating those entries. Regenerating from an older lockfile can resolve unrelated ranges upward; inspect any real re-resolution separately. `--lockfile-only --fix-lockfile` can fill manifest-required entries after a manual merge.
- Frontend installs can need GitHub access for the `@penpot/svgo` git dependency even when the lockfile exists. If GitHub is unreachable, resolve relevant lockfile hunks without claiming an install passed.
- Run `scripts/sync-pnpm-version --check` and `pnpm install --frozen-lockfile` in each workspace; inspect the lockfile diff and check that scripts, workflows, Dockerfiles and docs introduce no corepack calls.

## Cleaning stale node_modules

- `scripts/clean-node-modules --dry-run` previews removal across workspaces and members; normal cleanup leaves the shared store intact. `--store` also deletes the store, so use it only with explicit permission. The script excludes `external/` and `.opencode/`.
- Reinstall in each workspace after cleaning. The frontend postinstall also reinstalls and builds `plugins-runtime`.
