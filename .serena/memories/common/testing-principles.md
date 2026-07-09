# Testing Principles (Cross-Cutting)

Shared testing principles for all modules (backend, frontend, common, render-wasm, plugins). Module-specific commands and helpers are in each module's own testing memory.

## Core Principles

- **Test State, Not Interactions** — assert on outcomes, not method calls; survives refactoring
- **DAMP over DRY** — tests are specifications; duplication OK if each test is self-contained and readable
- **Prefer Real Implementations** — hierarchy: Real > Fake > Stub > Mock; mock only at boundaries (network, filesystem, email)
- **Arrange-Act-Assert** — every test: setup / action / verify
- **One Assertion Per Concept** — each test verifies one behavior; split compound assertions
- **Descriptive Test Names** — names read like specifications

## Anti-Patterns

- Testing implementation details → breaks on refactor
- Flaky tests (timing, order-dependent) → erode trust
- Mocking everything → tests pass, production breaks
- No test isolation → pass individually, fail together
- Testing framework code → waste of time
- Snapshot abuse → nobody reviews, break on any change

## Verification Checklist

After completing any implementation:
- Every new behavior has a corresponding test
- All tests pass for touched modules
- Bug fixes include a reproduction test that failed before the fix
- Test names describe the behavior being verified
- No tests were skipped or disabled

## Red Flags

- Writing code without corresponding tests
- Tests that pass on the first run (may not be testing what you think)
- Bug fixes without reproduction tests
- Tests that test framework behavior instead of app behavior
- Skipping tests to make the suite pass

## Rationalizations

- "I'll write tests after" → you won't; they'll test implementation not behavior
- "Too simple to test" → simple gets complicated; test documents expected behavior
- "Tests slow me down" → they speed up every future change
- "I tested manually" → manual testing doesn't persist
- "Code is self-explanatory" → tests ARE the specification
- "Just a prototype" → prototypes become production; test debt accumulates
