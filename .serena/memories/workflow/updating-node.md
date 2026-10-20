# Updating Node Across the Toolchain

No single source of truth: nothing reads `.nvmrc` at build time, and the version is repeated in a different shape per file. Six sites move together, and the image ones carry a digest beside the tag.

## Pin sites

- `.nvmrc` → `v<X.Y.Z>`, with the leading `v`.
- `docker/devenv/Dockerfile`, `setup-node` stage → `ENV NODE_VERSION=v<X.Y.Z>`; downloads `https://nodejs.org/dist/${NODE_VERSION}/node-${NODE_VERSION}-linux-{arm64,x64}.tar.gz`, one tarball per arch, and verifies each against a `NODE_SHA256` right below its `BINARY_URL`. Both hashes come from `SHASUMS256.txt` in that same dist directory, so a bump must recompute the two.
- `docker/images/Dockerfile.mcp` → two stages: `FROM dhi.io/node:<X.Y.Z>-debian13-dev AS build` and `FROM dhi.io/node:<X.Y.Z>-debian13 AS image`. The release stage carries no `-dev`.
- `docker/images/Dockerfile.media-processor` and `docker/images/Dockerfile.exporter` → single `FROM dhi.io/node:<X.Y.Z>-debian13-dev`.
- `.github/workflows/plugins-deploy-api-doc.yml` and `.github/workflows/plugins-deploy-styles-doc.yml` → `runtime: node@<X.Y.Z>` in the `pnpm/setup` step. Its `working-directory: plugins` makes pnpm's own version come from the manifest, so this pin is the Node one; keep it equal to `.nvmrc` minus the `v`.
- DHI tags take the bare version; only the devenv `NODE_VERSION` carries the `v`.
- Every `dhi.io` base image in the repo (the node ones above, plus `debian-base` and `nginx`) is pinned by digest: `FROM dhi.io/<image>:<tag>@sha256:<digest>`. Tag and digest are a pair; Renovate's `Update DHI base images` commits rewrite both in one go, so a hand-made tag bump that leaves the digest behind keeps the old image.

## Gotchas

- DHI tags are exact-version and lag the upstream release, and dhi.io answers 401 to anonymous requests, so a tag that does not exist yet can only be caught by a real build. `build-docker.yml` and `build-docker-devenv.yml` log into dhi.io, so the failure surfaces in CI, not locally.
- Node 24.x is the Krypton LTS line: take the newest patch of that major unless a major bump is the point.
- Renovate also opens these bumps, on `renovate-*` branches and with its config living outside this repo; a manual bump and Renovate's must not diverge on any of the six sites.
- Both workflows must stay valid YAML. The `python3` in the devenv has no `yaml` module, so validate with `uv run --with pyyaml python3 -c ...` instead of a bare import.

## Verification

- `rg '<old version>'` finds nothing outside `CHANGES.md` and untracked scratch files (a repo-wide grep without path filters hits multi-megabyte ClojureScript analyzer caches: always scope it). Grep `dhi\.io/node` as well: a line whose tag moved but whose digest did not still pulls the old image.
- After a devenv rebuild, `node --version` matches `.nvmrc` and `pnpm --version` matches `PNPM_VERSION` in `docker/devenv/Dockerfile`.
