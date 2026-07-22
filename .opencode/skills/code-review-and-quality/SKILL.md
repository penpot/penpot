---
name: code-review-and-quality
description: Conducts multi-axis code review. Use before merging any change. Use when reviewing code written by yourself, another agent, or a human. Use when you need to assess code quality across multiple dimensions before it enters the main branch.
---

# Code Review and Quality

## Overview

Multi-dimensional code review with quality gates. Every change gets reviewed before merge — no exceptions. Review covers five axes: correctness, readability, architecture, security, and performance.

**The approval standard:** Approve a change when it definitely improves overall code health, even if it isn't perfect. Perfect code doesn't exist — the goal is continuous improvement. Don't block a change because it isn't exactly how you would have written it. If it improves the codebase and follows the project's conventions, approve it.

## When to Use

- Before merging any PR or change
- After completing a feature implementation
- When another agent or model produced code you need to evaluate
- When refactoring existing code
- After any bug fix (review both the fix and the regression test)

## Core Principles

These principles underpin every axis. When in doubt, default to them.

- **DRY (Don't Repeat Yourself):** Every piece of knowledge has one authoritative representation. If the same logic appears in two places, extract it into a shared helper, model, or type. Reviewers: flag duplicated logic as a required change — it's not "just similar," it's drift that will diverge.
- **KISS (Keep It Simple, Stupid):** The simplest solution that works is the best solution. Complexity must earn its place. Reviewers: if you need more than one sentence to explain what a piece of code does, it's too complex — push for simplification before merge.
- **YAGNI (You Aren't Gonna Need It):** Don't add abstractions, hooks, or generalizations for hypothetical future use cases. Generalize on the third occurrence, not the first. Reviewers: delete speculative generality.
- **Don't invent problems:** Do not manufacture issues to produce more feedback. Every finding must be a real risk, a real readability barrier, or a real architectural concern — not a hypothetical or a stylistic preference disguised as a problem.

## The Five-Axis Review

Every review evaluates code across these dimensions.

### 1. Correctness

Does the code do what it claims to do?

- Does it match the spec or task requirements?
- Are edge cases handled (null, empty, boundary values)?
- Are error paths handled (not just the happy path)?
- Does it pass all tests? Are the tests actually testing the right things?
- Are there off-by-one errors, race conditions, or state inconsistencies?

### 2. Readability & Simplicity

Can another engineer (or agent) understand this code without the author explaining it?

- Are names descriptive and consistent with project conventions? (No `temp`, `data`, `result` without context)
- Is the control flow straightforward (avoid nested ternaries, deep callbacks)?
- Are there any "clever" tricks that should be simplified?
- **KISS check:** Is this the simplest approach that solves the problem? A 20-line straightforward function beats a 5-line clever one that requires a comment to explain.
- Could this be done in fewer lines? (1000 lines where 100 suffice is a failure)
- Are abstractions earning their complexity? (Don't generalize until the third use case)
- Is a new conditional bolted onto an unrelated flow? Push the logic into its own helper, state, or policy.
- Do repeated conditionals on the same shape appear? They signal a missing model or dispatcher.
- Are there dead code artifacts: no-op variables, backwards-compat shims, or `// removed` comments?

### 3. Architecture

Does the change fit the system's design?

- Does it follow existing patterns or introduce a new one? If new, is it justified?
- Does it maintain clean module boundaries?
- **DRY check:** Is there existing code that does the same thing? Reuse the canonical helper instead of writing a near-duplicate. If two branches do nearly the same thing, collapse them.
- Are dependencies flowing in the right direction (no circular dependencies)?
- Is the abstraction level appropriate (not over-engineered, not too coupled)?
- Does this refactor reduce complexity or just relocate it? Count the concepts a reader must hold. Prefer the restructuring that makes whole branches disappear over one that re-centralizes the same logic. Prefer deleting an abstraction to polishing it.
- Is feature-specific logic leaking into a shared or general-purpose module?
- Are type boundaries explicit? Question gratuitous `any`/`unknown`/optional/casts and silent fallbacks.
- **Structural remedies:** When you flag a problem, propose the move — not just the problem. Replace conditionals with dispatchers, collapse duplicate branches, separate orchestration from business logic, extract helpers, split large files. Prefer the remedy that removes moving pieces over one that spreads the same complexity around.

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

For each finding, describe the circumstances under which it could fail: specific inputs, load conditions, timing, or user actions that trigger the problem. "This crashes when input is null" is actionable; "this might crash" is not.

Lead with what matters: correctness and security first, then structural issues, then everything else. A few high-conviction comments beat a long list.

5. **Verify the verification** — What tests were run? Did the build pass? Was the change tested manually? Screenshots for UI changes?

## Review Output

Structure every review using this format:

### Summary

Briefly explain what the code does and give an overall assessment.

### Critical and High-Priority Issues

List problems that could cause security incidents, data loss, crashes, incorrect behavior, or major performance degradation. For each: state the severity, identify the file/function/code section, explain why it's a problem, describe failure circumstances, and provide a concrete improvement with corrected code when useful.

### Other Findings

List medium- and low-priority issues, including maintainability and design concerns.

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

## Change Sizing

Small, focused changes are easier to review, faster to merge, and safer to deploy.

```
~100 lines changed   → Good. Reviewable in one sitting.
~300 lines changed   → Acceptable if it's a single logical change.
~1000 lines changed  → Too large. Split it.
```

**Watch file size, not just diff size.** Around 1000 *total* lines in a single file is a common inspection signal. When a change materially grows an already-large file, decompose first.

**Splitting strategies:**

| Strategy | How | When |
|----------|-----|------|
| **Stack** | Submit a small change, start the next one based on it | Sequential dependencies |
| **By file group** | Separate changes for groups needing different reviewers | Cross-cutting concerns |
| **Horizontal** | Create shared code/stubs first, then consumers | Layered architecture |
| **Vertical** | Break into smaller full-stack slices of the feature | Feature work |

**Separate refactoring from feature work.** A change that refactors and adds new behavior is two changes — submit them separately.

## Change Descriptions

- **First line:** Short, imperative, standalone. "Delete the FizzBuzz RPC" not "Deleting the FizzBuzz RPC."
- **Body:** What is changing and why. Include context and reasoning not visible in the code itself.
- **Anti-patterns:** "Fix bug," "Fix build," "Add patch," "Phase 1."

## Dependencies

Before adding any dependency:

1. Does the existing stack solve this? (Often it does.)
2. How large is the dependency? (Check bundle impact.)
3. Is it actively maintained? (Check last commit, open issues.)
4. Does it have known vulnerabilities? (`npm audit`)
5. What's the license? (Must be compatible with the project.)

**Rule:** Prefer standard library and existing utilities over new dependencies. Every dependency is a liability.

**Upgrading dependencies:**

- Read the changelog, not just the version number. Semver is a promise the maintainer may not have kept.
- One dependency per change. When a bulk bump breaks the build, you've lost which package did it.
- Let the tests decide — a green suite before *and* after, not just "it installed."
- Review the lockfile diff, not just `package.json`. Commit it and never hand-edit it.

For supply-chain risk triage, follow the `security-and-hardening` skill.

## Common Rationalizations

| Rationalization | Reality |
|---|---|
| "It works, that's good enough" | Working code that's unreadable, insecure, or architecturally wrong creates debt that compounds. |
| "I wrote it, so I know it's correct" | Authors are blind to their own assumptions. Every change benefits from another set of eyes. |
| "We'll clean it up later" | Later never comes. The review is the quality gate — use it. |
| "AI-generated code is probably fine" | AI code needs more scrutiny, not less. It's confident and plausible, even when wrong. |
| "The tests pass, so it's good" | Tests are necessary but not sufficient. They don't catch architecture, security, or readability problems. |
| "The refactor makes it cleaner" | Relocating complexity isn't reducing it. If the reader still holds the same number of concepts, the structure didn't improve. |
| "It's only a small addition to this file" | Small diffs still push files past healthy size and bolt branches onto unrelated flows. |
| "It's just a version bump" | A bump is a behavior change you didn't write. Read the changelog. |
| "I'll upgrade everything in one PR" | A bulk bump hides which package broke the build. One per change. |
| "It's duplicated but it's only two places" | Two becomes three becomes five. Extract now, before the copies diverge. |
| "The abstraction is future-proof" | YAGNI. Delete speculative generality — generalize on the third occurrence, not the first. |
| "It's clever but efficient" | Cleverness is a readability tax. If it needs a comment to understand, simplify it. |

## Red Flags

- PRs merged without any review
- Review that only checks if tests pass (ignoring other axes)
- "LGTM" without evidence of actual review
- Security-sensitive changes without security-focused review
- Large PRs that are "too big to review properly" (split them)
- No regression tests with bug fix PRs
- Accepting "I'll fix it later" — it never happens
- A refactor that moves code around without reducing the number of concepts a reader must hold
- New conditionals scattered into unrelated code paths (a missing abstraction)
- A bespoke helper that duplicates an existing canonical one
- A bulk "bump dependencies" PR with no changelog review

## Verification

After review is complete:

- [ ] All Critical issues are resolved
- [ ] All Required (no-prefix) changes are resolved or explicitly deferred with justification
- [ ] Tests pass
- [ ] Build succeeds
- [ ] The verification story is documented (what changed, how it was verified)
- [ ] Dependency upgrades reviewed against changelog, isolated per package, verified by green suite

## Multi-Model Review Pattern

Use different models for different review perspectives:

```
Model A writes the code → Model B reviews → Model A addresses feedback → Human makes the final call
```

Different models have different blind spots.

## See Also

- For detailed security review guidance, see `security-and-hardening`
