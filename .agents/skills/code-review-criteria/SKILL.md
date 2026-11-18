---
name: code-review-criteria
description: Code review criteria for correctness, clarity, architecture, security and performance. Loaded by the review-code subagent.
---

# Code Review Criteria

Assess whether the change improves the codebase and is safe to merge. Review
the diff in context, its intent and relevant tests. Follow project conventions
and module memories. Do not block on personal preferences or invent problems.
Apply each axis in proportion to the change and its risks.

Follow `review-code` for delegation and its read-only contract. Never run tests,
builds, linters or other state-changing checks during a review or follow-up.
Inspect prior test results or CI evidence when available. Identify their source
and scope; if evidence is missing, stale or inaccessible, say what is
unverified. Never claim a suite passed without evidence.

## Review axes

### Correctness and tests

Check requirements, relevant boundaries, error paths, concurrency and state.
Use the module's contracts to decide where validation belongs; do not demand
redundant guards inside functions with established preconditions. Check that
new functionality has tests covering its behavior and important failure
cases, using the appropriate layer and project testing guidance. Missing
required tests are findings; documentation-only changes need not add tests.
Do not infer that a test failed before a fix without evidence.

### Readability and simplicity

Check names, control flow and maintainability against existing conventions.
Flag duplication when it creates a concrete risk of drift or maintenance
cost, not merely because code appears more than once. In tests, prefer
self-contained, readable cases even when they repeat setup. Suggest the
simplest remedy that preserves behavior.

### Architecture and compatibility

Check module boundaries, existing abstractions, contracts and unnecessary
complexity. Review surrounding code when needed to understand the change.
Do not label a compatibility shim dead code without checking its purpose.
For file formats or data model changes, consult the relevant compatibility,
migration and feature memories; for Clojure, logging and translations, read
the corresponding guidance when relevant.

### Security

Evaluate relevant input boundaries, authorization, secrets, data handling and
external integrations. Consult project security guidance for the affected
technology; do not assume a generic framework or tool applies to this module.

### Performance

Assess relevant hot paths, resource bounds, data access and scaling. Explain
the workload or conditions in which a problem matters; do not flag speculative
optimizations without a concrete risk.

## Scope and dependencies

Review relevant generated files or lockfile changes when they affect the
contract, dependencies or security; do not skip a lockfile-only dependency
change. Check dependency changes against available changelogs, project tools
and relevant lockfile diffs. Treat size as a signal to assess reviewability,
not an automatic finding. Identify unrelated work in the target diff when it
materially impedes review; do not review unrelated working-tree changes.

## Findings and verdict

Assign each finding a stable ID (`F1`, `F2`, …), independent of severity.
Never renumber it within the review or follow-up; severity may change as
evidence changes. New findings receive unused IDs.

For each finding state severity, file and location, concrete circumstances,
impact, remedy and the tests needed to verify it; explain when no new test is
needed. If a consequential decision requires user input, give the real options,
consequences and recommendation within that finding, without pausing to ask.

- **Critical:** security incident, data loss or comparably severe breakage;
  blocks merge.
- **High:** a significant defect or missing required test; must be addressed.
- **Medium:** a substantive issue worth fixing but not a merge blocker.
- **Low / Suggestion:** an optional improvement with a concrete benefit.

Base severity on impact and project requirements. Missing tests required for
new functionality must be addressed; do not invent a test requirement for
changes where none applies.

Return a short summary, findings ordered by impact and a verdict with the
relevant IDs: **Approve**, **Approve with minor changes**, or **Request
changes**. State verification evidence and limitations. Do not duplicate
remedies or tests in separate sections. If no findings exist, say so. In
follow-up, retain original IDs and
report resolved, pending and not-addressed findings as `review-code` requires.
