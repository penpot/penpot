# Testing

Every behavior change needs a test. Read the affected modules' testing guidance for commands, fixtures and runner registration: `mem:backend/testing`, `mem:common/testing`, `mem:frontend/testing` and `mem:exporter/testing`. Documentation and configuration changes with no behavioral impact do not need behavior tests.

## Workflow

- Prefer RED → GREEN → REFACTOR: write a failing test, implement the smallest change that passes, then refactor and rerun tests. For a bug, first demonstrate the failure and confirm it fails before fixing it. If TDD is impractical during exploration, still add tests before completing the work.
- Assert observable outcomes rather than implementation details. Keep each test readable, isolated and focused on one behavior; code-facing test text must be in English.
- Prefer real implementations, then fakes, stubs and mocks. Mock at external boundaries when needed; call counts or order assertions should have a behavioral reason. Use per-test setup and teardown; suite-wide fixtures are for expensive immutable setup.
- Use table-driven tests when each case has the same short body. Cases with complex setup, asynchronous work or shared mutable state are clearer as separate tests.
- Choose the appropriate level: unit tests for pure logic, integration tests for RPC or database boundaries, and end-to-end tests for full flows. Do not add frontend Playwright E2E tests unless the user explicitly requests them.
- Avoid timing-dependent or order-dependent tests, broad snapshots, and tests of framework behavior. Never skip or disable a test merely to make checks pass.

## Execution and verification

- Never pipe test output directly to filters. Redirect it to a file first, inspect the full result and exit status, then search the file if needed. Use focused runs to narrow scope, not output filters to hide failures.
- Run the affected modules' tests, lint and format checks; use `mem:scripts/ci` for the local CI entry point. A focused test does not replace the required module checks. Register new test namespaces where the module's runner requires it.
- For backend direct and focused JVM runs, preserve existing `PENPOT_FLAGS` and append `enable-backend-asserts` before the process starts; otherwise assertions can compile out. Prefer `./scripts/ci backend` for the full checks.
