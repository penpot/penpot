# E2E CI bundle contract

- `.github/workflows/tests-e2e.yml` is the source for triggers, jobs and stable check names. Plugin changes also run the frontend suites because the bundle embeds built plugins. Manual dispatch runs integration, not the two mocked plugin jobs.
- `build-bundle` builds `frontend/resources/public` only on a cache miss. Derive the key from the SHA actually checked out, not `github.sha`: manual dispatch can choose a different ref.
- Consumer jobs depend on `build-bundle`, restore its exact output key with `fail-on-cache-miss: true`, and never rebuild. Copy the restore action pin from the current workflow when adding a consumer. Keep job names stable; branch protection can match the check context by name.
- Integration uses `frontend/scripts/e2e-server.js`. The mocked plugin jobs install only plugin dependencies and use their own zero-dependency `ci/static-server.ts` copies; keep those copies aligned and do not import helpers requiring `frontend/node_modules`.
- Local integration setup: `mem:frontend/testing-playwright`; composable suite: `mem:frontend/composable-component-tests`. `.github/workflows/build-bundle.yml` is a distinct release packaging path, not a test bundle producer.
