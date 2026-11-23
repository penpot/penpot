---
name: testing
description: Enforce TDD workflow and testing best practices for Penpot. Use when implementing features, fixing bugs, or modifying behavior. Reads testing memory for full guidance.
---

# Testing

## When to use

Use for new behavior, behavior changes, edge cases, and bug fixes. Skip
documentation, configuration, and static content changes with no behavioral impact.

## Workflow

1. Before writing tests, read `mem:testing` and its applicable module testing
   references for test commands, fixtures, and runner registration.
2. Test every behavior change. Prefer red → green → refactor. For bug fixes,
   first write a reproducing test and confirm it fails for the expected reason;
   then fix the bug and confirm the test passes.
3. Register new test files if the module requires it. Verify tests, lint, and
   formatting with `local-ci` before finishing.
