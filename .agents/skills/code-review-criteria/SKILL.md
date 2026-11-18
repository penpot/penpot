---
name: code-review-criteria
description: Code review criteria — the five review axes, core principles, severity format, and verdict for reviewing code changes. Loaded by the reviewer subagent of the review-code flow. Not a user-facing flow — to review code, use the review-code flow.
---

# Code Review Criteria and Quality

## Overview

Multi-dimensional code review with quality gates. Every change gets reviewed before merge — no exceptions. Review covers five axes: correctness, readability, architecture, security, and performance.

**The approval standard:** Approve a change when it definitely improves overall code health, even if it isn't perfect. Perfect code doesn't exist — the goal is continuous improvement. Don't block a change because it isn't exactly how you would have written it. If it improves the codebase and follows the project's conventions, approve it.

## When to Use

- The reviewer subagent of the `review-code` flow loads this skill to perform the review of a code change.
- To review code, always go through the `review-code` flow — never load this skill directly for that. This is the criteria reference, not the flow.

## Core Principles

These principles underpin every axis. When in doubt, default to them.

- **DRY (Don't Repeat Yourself):** Every piece of knowledge has one authoritative representation. Flag duplicated logic as a required change — it is not "just similar", it is drift that will diverge.
- **KISS (Keep It Simple, Stupid):** The simplest solution that works is the best solution. Complexity must earn its place: if it needs more than a sentence to explain, push for simplification before merge.
- **YAGNI (You Aren't Gonna Need It):** No abstractions, hooks or generalizations for hypothetical future use cases. Generalize on the third occurrence, not the first.
- **Don't invent problems:** Every finding must be a real risk, a real readability barrier, or a real architectural concern — not a hypothetical or a stylistic preference disguised as a problem.

## The Five-Axis Review

Every review evaluates code across these dimensions.

### 1. Correctness

Does the code do what it claims to do?

- Does it match the spec or task requirements?
- Are edge cases handled (null, empty, boundary values)?
- Are error paths handled (not just the happy path)?
- Do the tests cover the changed behavior, including its edge cases?
- Are there off-by-one errors, race conditions, or state inconsistencies?

### 2. Readability & Simplicity

Can another engineer (or agent) understand this code without the author explaining it?

- Are names descriptive and consistent with project conventions? (No `temp`, `data`, `result` without context)
- Is the control flow straightforward (avoid nested ternaries, deep callbacks)?
- Are there "clever" tricks that should be simplified? Could this be done in fewer lines? (1000 lines where 100 suffice is a failure)
- Is a new conditional bolted onto an unrelated flow? Push the logic into its own helper, state, or policy.
- Do repeated conditionals on the same shape appear? They signal a missing model or dispatcher.
- Are there dead code artifacts: no-op variables, backwards-compat shims, or `// removed` comments?

### 3. Architecture

Does the change fit the system's design?

- Does it follow existing patterns or introduce a new one? If new, is it justified?
- Does it keep clean module boundaries, with dependencies flowing the right way (no cycles)?
- Is the abstraction level appropriate (not over-engineered, not too coupled)?
- Is there existing code that already does this? Reuse the canonical helper instead of a near-duplicate, and collapse branches that do nearly the same thing.
- Is feature-specific logic leaking into a shared or general-purpose module?
- Are type boundaries explicit? Question gratuitous `any`/`unknown`/optional/casts and silent fallbacks.
- Does the change reduce complexity or relocate it? Count the concepts a reader must hold: prefer the restructuring that makes whole branches disappear, and deleting an abstraction over polishing it.
- **Give the remedy, not only the problem:** replace conditionals with dispatchers, separate orchestration from business logic, extract helpers, split large files. Prefer the remedy that removes moving pieces over one that spreads the same complexity around.

### 4. Security

For detailed security guidance, see `security-and-hardening`.

- Is user input validated and sanitized?
- Are secrets kept out of code, logs, and version control?
- Is authentication/authorization checked where needed?
- Are SQL queries parameterized (no string concatenation)?
- Are outputs encoded to prevent XSS?
- Are dependencies from trusted sources with no known vulnerabilities?
- Is data from external sources (APIs, logs, user content, config files) treated as untrusted?

### 5. Performance

- Any N+1 query patterns?
- Any unbounded loops or unconstrained data fetching?
- Any synchronous operations that should be async?
- Any unnecessary re-renders in UI components?
- Any missing pagination on list endpoints?
- Any large objects created in hot paths?

## Review Process

1. **Understand the intent** — What is this change trying to accomplish? What spec or task does it implement?
2. **Review tests first** — Tests reveal intent and coverage. Do they test behavior, not implementation details? Are edge cases covered?
3. **Review the implementation** — Walk through each file with the five axes in mind.
4. **Categorize findings** — Label every comment with its severity:

| Prefix | Meaning | Author Action |
|--------|---------|---------------|
| **Critical:** | Blocks merge | Security vulnerability, data loss, broken functionality |
| **High:** | Required change | Must address before merge |
| **Medium:** | Should fix | Strongly recommended, not a blocker |
| **Low:** | Minor, optional | Author may ignore — formatting, style preferences |
| **Suggestion:** | Worth considering | Not required, but improves the code |

**Finding IDs.** Assign every finding a stable identifier by severity class: `R1`, `R2`, … for required findings (Critical, High), `M1`, `M2`, … for minor findings (Medium, Low), and `S1`, `S2`, … for suggestions. Number within each class in review order. Use the ID everywhere the finding is mentioned — in section headers, in the verdict, in follow-up discussion. Never renumber within a review. Example: `**R2** — `app/validate.cljs:42` — duplicate branch logic…`.

For each finding, describe the circumstances under which it could fail: specific inputs, load conditions, timing, or user actions that trigger the problem. "This crashes when input is null" is actionable; "this might crash" is not.

Lead with what matters: correctness and security first, then structural issues, then everything else. A few high-conviction comments beat a long list.

5. **Check the evidence, do not re-run it** — Which tests cover the change? What did the author run, and what did CI report? Never re-run the full suite or a build yourself: the author's run and CI already cover that, and repeating it only lengthens the review. Run at most the single test file that settles a specific doubt. When no checks have been run at all (a change you did not see tested), run the module's checks before the verdict.

## Change Sizing

Small, focused changes are easier to review, faster to merge, and safer to deploy. Flag the size when a diff exceeds ~300 lines, or when it grows a file past ~1000 total lines, and name the remedy: split it, and separate refactoring from feature work. This is a finding about the change, not work to do inside the review.

## Dependencies

Before accepting a new dependency: does the existing stack already solve it, how large is the bundle impact, is it maintained, does `npm audit` report anything, is the license compatible. Prefer the standard library and existing utilities — every dependency is a liability.

For upgrades: read the changelog, not just the version number; one dependency per change; a green suite before and after; review the lockfile diff and never hand-edit it. Supply-chain triage follows `security-and-hardening`.

## Review Output

Structure every review using this format:

### Summary

Briefly explain what the code does and give an overall assessment.

### Critical and High-Priority Issues

List problems that could cause security incidents, data loss, crashes, incorrect behavior, or major performance degradation. Each finding gets its unique ID (`R1`, `R2`, …). For each: state the severity, identify the file/function/code section, explain why it's a problem, describe failure circumstances, and provide a concrete improvement with corrected code when useful.

### Other Findings

List medium- and low-priority issues, including maintainability and design concerns. Use `M1`, `M2`, … for Medium/Low and `S1`, `S2`, … for Suggestions.

### Suggested Refactoring

Provide focused code changes or revised snippets. Preserve existing behavior unless a behavior change is explicitly justified.

### Testing Recommendations

Identify missing tests and describe specific test cases, including edge cases and failure scenarios.

### Positive Observations

Mention implementation choices that are clear, safe, efficient, or well designed. This is not fluff — it reinforces good patterns and tells the author what to keep doing.

### Final Verdict

Choose one:

- **Approve** — Ready to merge
- **Approve with minor changes** — Good to merge after addressing low/medium issues
- **Request changes** — Critical or high issues must be resolved before merge

List the finding IDs the verdict depends on (e.g. "Request changes: R1, R2").

## Red Flags

- A change merged without any review.
- A review that only checks whether the tests pass.
- Security-sensitive changes without a security-focused review.
- A change too large to review properly: split it.

## Verification

Before emitting the verdict, confirm the evidence the review rests on. This covers the state of the code at review time, not the later fixing of findings:

- [ ] The suite is green, and the evidence is the author's run or CI — not your own re-run. Say which one you relied on.
- [ ] New behavior has tests that cover the edge cases in the diff.
- [ ] The verification story is stated: what changed and how it was verified.
- [ ] Dependency changes were reviewed against the changelog and the lockfile diff.
