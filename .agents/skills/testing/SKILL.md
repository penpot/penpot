---
name: testing
description: Enforce TDD workflow and testing best practices for Penpot. Use when implementing features, fixing bugs, or modifying behavior. Reads testing memory for full guidance.
---

# Testing Skill

Enforces test-driven development and Penpot testing conventions.

## When to Use

- Implementing new logic or behavior
- Fixing any bug (reproduction test required)
- Modifying existing functionality
- Adding edge case handling

**Skip:** Pure configuration changes, documentation updates, or static content with no behavioral impact.

## Workflow

Follow TDD (Red → Green → Refactor) whenever practical:

1. **RED** — Write a failing test first
2. **GREEN** — Write minimal code to pass
3. **REFACTOR** — Clean up while tests stay green

For bug fixes, use the Prove-It Pattern: write a test that reproduces the bug, confirm it fails, implement the fix, confirm it passes.

## Required Reading

Before writing any test, read:

1. `.serena/memories/testing.md` — cross-cutting testing principles, TDD workflow, anti-patterns, execution discipline
2. Module-specific testing memory for the affected module:
   - `mem:common/testing` — CLJC unit tests
   - `mem:frontend/testing` — CLJS unit tests, Playwright E2E
   - `mem:backend/testing` — JVM clojure.test conventions
   - `mem:exporter/testing` — exporter unit tests

## Key Rules

- Every behavior change needs a test
- Test state, not interactions
- DAMP over DRY — tests are specifications; duplication is OK if each test is self-contained and readable
- Prefer Real > Fake > Stub > Mock
- Arrange-Act-Assert structure
- One assertion per concept
- Never pipe test output to filters — redirect to file first
- Register new test files in the module's runner/entrypoint

## Verification

After completing implementation:

- [ ] Every new behavior has a test
- [ ] All tests pass for touched modules
- [ ] Bug fixes include a reproduction test
- [ ] Lint/formatter passes
