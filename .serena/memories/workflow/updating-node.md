# Updating Node across the toolchain

No single pin controls every build. Select the target version within the project's Node line unless a major bump is authorized, then align all pin sites. `.nvmrc` currently sets the project line; do not adopt a newer major merely because it appears on another branch.

## Pin sites

- `.nvmrc` uses `v<X.Y.Z>`.
- `docker/devenv/Dockerfile` sets `NODE_VERSION` with the leading `v` and verifies the Node Linux tarballs for arm64 and x64. Obtain both SHA-256 values from that version's `SHASUMS256.txt` and verify the downloaded artifacts.
- `docker/images/Dockerfile.mcp` has a DHI `-dev` build stage and a non-`-dev` release stage. `Dockerfile.media-processor` and `Dockerfile.exporter` use the DHI `-dev` image.
- `.github/workflows/plugins-deploy-api-doc.yml` and `plugins-deploy-styles-doc.yml` pin `runtime: node@<X.Y.Z>` in `pnpm/setup`. Use the `.nvmrc` version without `v`; pnpm itself comes from the plugins manifest. For pnpm pins see `mem:workflow/updating-pnpm`.
- DHI tags and digests are a pair: update both, or the old digest still selects the old image. Exact-version tags can lag upstream; anonymous access may return 401, so verify availability through an authorized build rather than assuming the tag exists.

## Merge and verification

- Backports can update Dockerfiles or workflows without updating `.nvmrc`. Resolve the intended project version across all sites, including both MCP stages, rather than choosing solely by branch direction or highest version.
- Validate both plugin deploy workflows as YAML; the devenv Python may lack PyYAML, so use an available YAML validator or `uv run --with pyyaml`.
- Search the affected files for the old version and inspect each DHI tag and digest together. After rebuilding the devenv, check `node --version` against `.nvmrc` and `pnpm --version` against its Dockerfile pin. A DHI image change also needs an authorized build to validate the image.
