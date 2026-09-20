# E2E CI workflow (build-once frontend bundle)

`.github/workflows/tests-e2e.yml` ("CI: E2E") is the single workflow for every
suite that drives a real frontend bundle:

- `Integration Tests` — Playwright specs under `frontend/playwright` (sharded).
- `Run composable test suite (mocked backend)` — `mem:frontend/composable-component-tests`.
- `Run Plugin API Test Suite (mocked)` — `plugins/apps/plugin-api-test-suite`.

Triggers: PR/push touching `frontend/**`, `common/**`, `render-wasm/**`,
`plugins/**` (or the workflow file), plus `workflow_dispatch` (integration only).
A `plugins/**` change runs the whole set on purpose: the bundle embeds the
built plugins.

## Invariants

- ONE `frontend/scripts/build` per SHA. The `build-bundle` job restores
  `actions/cache` key `frontend-bundle-<sha>`, builds only on a miss, and saves
  the key before the job ends. A re-run of the same SHA reuses the cache.
- Consumer jobs (`needs: build-bundle`) restore the same key with
  `fail-on-cache-miss: true` and NEVER run `frontend/scripts/build`.
- The bundle is `frontend/resources/public`. The integration specs serve it
  with `frontend/scripts/e2e-server.js`; each mocked plugin driver serves it
  with its own zero-dependency `ci/static-server.ts` (duplicated in both
  suites — keep the copies in sync).
- Mocked plugin jobs install only `plugins/` deps, so their drivers must not
  import anything from `frontend/node_modules` at runtime (e.g. no
  `frontend/scripts/e2e-server.js`, which needs `express`).
- Cache key comes from `git rev-parse HEAD` (the checked-out ref), not
  `github.sha`, because `workflow_dispatch` can target a different ref.
- Job `name:` values are the GitHub check contexts. Keep them stable: branch
  protection may match them by name. Renaming the workflow file/name is safe.

## Adding a bundle-consuming suite

Add a job with `needs: build-bundle`, a `Restore Cache` step
(`actions/cache/restore@v5`, key `needs.build-bundle.outputs.bundle_key`,
`fail-on-cache-miss: true`), then that suite's own deps. Never add a build step.

## Scope

Distinct from `Bundles Builder` (`.github/workflows/build-bundle.yml`), the
release path that zips the bundle (`manage.sh build-bundle`) and uploads it to S3.
