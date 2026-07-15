# Testing

## Overview

Tests are proof that code works. Every behavior change needs a test.

Testing in this monorepo varies by module. Each module has its own test
commands, helpers, runner registration requirements, and conventions. This
memory covers cross-cutting testing principles. For module-specific commands
and helpers, consult:

- `mem:common/testing` — CLJC unit tests (JVM + JS), test helpers, fixture
  builders, production-path change helpers
- `mem:frontend/testing` — CLJS unit tests, Playwright E2E integration tests,
  live browser verification via nREPL
- Backend — JVM `clojure.test` under `backend/test/`; see `mem:backend/core`

## When to Use

- Implementing new logic or behavior
- Fixing any bug (reproduction test required)
- Modifying existing functionality
- Adding edge case handling

**When NOT to use:** Pure configuration changes, documentation updates, or
static content changes with no behavioral impact.

## TDD: Recommended Workflow

Write a failing test before writing the code that makes it pass. For bug fixes,
reproduce the bug with a test before attempting a fix.

When TDD isn't practical (exploratory work, tight coupling to unknown APIs),
still write tests before considering the work complete.

```
    RED                GREEN              REFACTOR
 Write a test    Write minimal code    Clean up the
 that fails  ──→  to make it pass  ──→  implementation  ──→  (repeat)
      │                  │                    │
      ▼                  ▼                    ▼
   Test FAILS        Test PASSES         Tests still PASS
```

- **RED** — Write the test first. It must fail. A test that passes immediately
  proves nothing.
- **GREEN** — Write the minimum code to make the test pass. Don't over-engineer.
- **REFACTOR** — With tests green, improve the code without changing behavior:
  extract shared logic, improve naming, remove duplication. Run tests after
  every step.

## The Prove-It Pattern (Bug Fixes)

When a bug is reported, **do not start by trying to fix it.** Start by writing
a test that reproduces it:

1. Write a test that demonstrates the bug
2. Confirm the test FAILS (proving the bug exists)
3. Implement the fix
4. Confirm the test PASSES (proving the fix works)
5. Run the full test suite for the module (no regressions)

## Core Principles

- **Test State, Not Interactions** — assert on outcomes, not method calls;
  survives refactoring
- **DAMP over DRY** — tests are specifications; duplication is OK if each test
  is self-contained and readable. A test should tell a complete story without
  requiring the reader to trace through shared helpers.
- **Prefer Real Implementations** — hierarchy: Real > Fake > Stub > Mock;
  mock only at boundaries (network, RPC, filesystem, email)
- **Arrange-Act-Assert** — every test: setup / action / verify
- **One Assertion Per Concept** — each test verifies one behavior; split
  compound assertions
- **Descriptive Test Names** — names read like specifications

## Prefer Real Implementations Over Mocks

Work down this list:

1. **Real implementation** — Test the actual code with real collaborators.
   Highest confidence.
2. **Fake** — A simplified but functional in-memory implementation (e.g.
   atom/dict-backed store instead of a real database).
3. **Stub** — Returns canned data. Use when the collaborator's logic is
   irrelevant.
4. **Mock** — Last resort, only at boundaries. Use only when verifying
   interaction with an external system that cannot be faked.

**Rule of thumb:** If you can write a fake or use the real implementation, do
that. If you find yourself asserting on call counts or invocation order, ask
whether a fake would be clearer.

## Fixtures over Manual Setup

Use fixture/`beforeEach` mechanisms for shared setup and teardown. Each test
should own its state so tests don't interfere with each other. Shorter-scope
fixtures (`:each` / per-test) are preferred; longer-scope fixtures (`:once` /
suite-level) are only for expensive, immutable shared setup.

## Parametrized Tests

Use your test framework's parametrize/table-driven mechanism to test multiple
scenarios with a single test body. Keeps tests concise and surfaces all cases
at a glance.

## Test Pyramid

```
          ╱╲
         ╱  ╲         E2E (few)
        ╱    ╲        Full flows, real browser/server
       ╱──────╲
      ╱        ╲      Integration (some)
     ╱          ╲     Cross-module, test DB
    ╱────────────╲
   ╱              ╲   Unit (most)
  ╱                ╲  Pure logic, fast
 ╱──────────────────╲
```

Prefer unit tests for pure logic. Reach for integration/E2E tests when covering
RPC handlers, database queries, or full user flows. In the frontend, Playwright
E2E tests should not be added unless explicitly requested.

## Anti-Patterns

| Anti-Pattern | Problem | Fix |
|---|---|---|
| Testing implementation details | Breaks on refactor | Test inputs/outputs |
| Flaky tests (timing, order-dependent) | Erodes trust | Deterministic assertions, isolate state |
| Mocking everything | Tests pass, production breaks | Prefer real implementations or fakes |
| No test isolation | Pass individually, fail together | Per-test state fixtures |
| Testing framework/platform code | Wastes time | Only test YOUR code |
| Snapshot abuse | Nobody reviews, break on any change | Focused assertions |
| Skipping tests to make suite pass | Hides real failures | Fix the test or fix the code |

## Verification Checklist

After completing any implementation:

- [ ] Every new behavior has a corresponding test
- [ ] All tests pass for touched modules
- [ ] Bug fixes include a reproduction test that failed before the fix
- [ ] Test names describe the behavior being verified
- [ ] No tests were skipped or disabled
- [ ] Lint/formatter passes for touched modules
- [ ] New test files registered in the module's runner/entrypoint (see module
      testing memory)
