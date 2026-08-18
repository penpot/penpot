---
name: plan-review
description: Reviews implementation plans for quality, completeness, and actionability. Use after a plan is produced by the planner skill, before starting implementation. Use when evaluating a plan written by yourself, another agent, or a human.
---

# Plan Review

## Overview

Multi-dimensional plan review with quality gates. Every plan gets reviewed before implementation starts — no exceptions. Review covers six axes: completeness, task quality, architecture & sequencing, risk coverage, actionability, and proposed code quality.

**The approval standard:** Approve a plan when it is specific enough that a skilled implementer could execute it without guessing, the task ordering is sound, and risks are acknowledged. Perfect plans don't exist — the goal is confidence that implementation won't derail. Don't block a plan because it isn't exactly how you would have structured it. If it's executable and well-organized, approve it.

## When to Use

- After the planner skill produces a plan
- Before starting implementation on any non-trivial task
- When reviewing a plan written by another agent or a human
- When a plan feels too large, vague, or risky to start

**Do NOT use for:** Single-file changes with obvious scope, or when the task is trivial enough to just do.

## The Six-Axis Review

Every plan gets evaluated across these dimensions:

### 1. Completeness

Does the plan cover everything needed to implement successfully?

- Is the **context** clear? (What problem, why now, what's the goal?)
- Are **affected modules** identified with paths?
- Are **architecture decisions** documented with rationale?
- Is there a **testing strategy**?
- Are **verification commands** explicit (not "run the tests")?
- Are **open questions** listed (not buried in someone's head)?
- Is there a **parallelization** assessment for multi-task plans?

**Missing any of these is a gap, not a nit.**

### 2. Task Quality

Are the tasks well-defined and independently executable?

- Does every task have **acceptance criteria**? (Testable, not vague)
- Does every task have **verification steps**?
- Are tasks **sized appropriately**? (XS–M is ideal, L is acceptable, XL must be split)
- Are **dependencies** between tasks explicitly stated?
- Are **files likely touched** listed?
- Is each task a **single, self-contained change**? (Not "implement the whole feature")
- Could a skilled implementer pick up any task and execute it without asking clarifying questions?

### 3. Architecture & Sequencing

Is the plan structured so implementation flows correctly?

- Does implementation order follow the **dependency graph** (foundations first)?
- Are tasks **vertically sliced** (feature paths) rather than horizontally layered?
- Does each task leave the system in a **working state**?
- Are there **checkpoints** between major phases?
- Are **high-risk tasks early** (fail fast)?
- Is the total plan a reasonable number of tasks? (More than ~15 tasks suggests the scope should be split into multiple plans)

### 4. Risk Coverage

Are the hard parts acknowledged and mitigated?

- Are **edge cases** identified?
- Are **breaking changes** or **migration concerns** noted?
- Are **security implications** considered?
- Are **performance implications** considered?
- Are **external dependencies** or integration risks flagged?
- Is there a plan for **rollback** if something goes wrong?
- Are **data integrity** risks addressed (what happens if a migration fails mid-way)?

### 5. Actionability

Can an implementer actually execute this?

- Are **file paths** specific (not "update the relevant files")?
- Are **function/method names** mentioned where applicable?
- Are **verification commands** copy-pasteable (not "run the linter")?
- Are **test commands** project-specific (not generic)?
- Is the **code shape** described where the implementation isn't obvious?
- Are **conventions** referenced (naming, patterns, existing utilities to reuse)?
- Does the plan reference **existing code** the implementer should read first?

### 6. Proposed Code Quality *(when the plan includes implementation details)*

If the plan proposes code shapes, function signatures, data structures, or API designs, evaluate those proposals against `code-review-and-quality` criteria:

- **Correctness:** Do the proposed types/signatures handle edge cases (null, empty, boundaries)?
- **Readability:** Are proposed names descriptive and consistent with project conventions?
- **Architecture:** Do proposed abstractions follow existing patterns? Are they justified (not over-engineered)?
- **Security:** Do proposed APIs validate input at boundaries? Any injection/XSS vectors in the design?
- **Performance:** Do proposed data structures avoid N+1 patterns? Any unbounded operations in the design?

**When to apply:** Only when the plan includes specific code snippets, type definitions, API contracts, or function signatures. Plans that only describe "what" without showing "how" skip this axis.

## Structural Remedies

When you flag a structural problem in a plan, propose the fix — not just the problem:

- **A task is too large (XL):** Split it into vertical slices. Each slice should be independently testable.
- **Missing acceptance criteria:** Draft 2–3 specific, testable conditions for the task.
- **Wrong sequencing:** Identify the dependency and propose the correct order.
- **No checkpoints:** Suggest where checkpoints should go (typically after every 2–3 tasks).
- **Vague verification:** Replace "run tests" with the actual project command.
- **Horizontal slicing:** Restructure into vertical feature paths.
- **Missing risk section:** Draft the risks you can identify from the plan content.

Prefer the remedy that makes the plan immediately actionable over one that just flags the gap.

## Plan Sizing

Plans should be scoped to a single deliverable:

```
1–5 tasks    → Good. A focused feature or bug fix.
6–10 tasks   → Acceptable for a moderate feature.
11–15 tasks  → Large. Consider splitting into phases.
15+ tasks    → Too large. Split into multiple plans.
```

**What counts as "one plan":** A self-contained set of changes that delivers a single coherent capability. If you can describe the goal in one sentence, it's one plan.

## Categorize Findings

Label every comment with its severity so the author knows what's required vs optional:

| Prefix | Meaning | Author Action |
|--------|---------|---------------|
| *(no prefix)* | Required change | Must address before implementation starts |
| **Critical:** | Blocks implementation | Missing security consideration, data integrity risk, fundamentally wrong approach |
| **Nit:** | Minor, optional | Author may ignore — wording, formatting |
| **Optional:** / **Consider:** | Suggestion | Worth considering but not required |
| **FYI** | Informational only | No action needed — context for future reference |

**Lead with what matters.** Order findings by leverage: missing risks and wrong sequencing first, then task quality gaps, then completeness, then nits. If you have one critical sequencing problem and ten nits, the sequencing problem *is* the review.

## Review Process

### Step 1: Understand the Goal

Before evaluating structure, understand intent:

```
- What is this plan trying to accomplish?
- What problem does it solve?
- What does "done" look like?
```

### Step 2: Check Completeness First

Scan for missing sections before diving into content:

```
- Context present?
- Affected modules listed?
- Architecture decisions documented?
- Risks acknowledged?
- Testing strategy defined?
- Verification commands explicit?
```

### Step 3: Review Task Quality

Walk through each task:

```
For each task:
1. Can I tell exactly what to build?
2. Are acceptance criteria specific and testable?
3. Is the size reasonable (not XL)?
4. Are dependencies clear?
5. Would I know which files to touch?
```

### Step 4: Validate Sequencing

Check the dependency graph:

```
- Are foundations built first?
- Does each task leave the system working?
- Are checkpoints placed correctly?
- Are high-risk items early?
- Is it vertically sliced?
```

### Step 5: Assess Actionability

Put yourself in the implementer's shoes:

```
- Could I pick up task 1 and start coding without asking any questions?
- Are the verification commands copy-pasteable?
- Are file paths and function names specific?
- Is existing code referenced where I'd need to read it?
```

### Step 6: Verify the Verification Story

Check that the plan can actually confirm it worked:

```
- What tests should pass after implementation?
- What build/compile commands are relevant?
- What manual checks are needed?
- How do we know the feature works end-to-end?
```

### Step 7: Evaluate Proposed Code Quality *(if applicable)*

If the plan includes code snippets, types, or API designs:

```
- Load code-review-and-quality skill for criteria
- Check proposed signatures for edge cases
- Verify naming follows project conventions
- Confirm abstractions follow existing patterns
- Scan for security vectors in proposed APIs
- Check for performance issues in proposed data structures
```

## Review Checklist

```markdown
## Review: [Plan title]

### Completeness
- [ ] Context explains the problem and goal
- [ ] Affected modules are listed with paths
- [ ] Architecture decisions have rationale
- [ ] Testing strategy is defined
- [ ] Verification commands are explicit and project-specific
- [ ] Open questions are listed

### Task Quality
- [ ] Every task has acceptance criteria
- [ ] Every task has verification steps
- [ ] Tasks are sized XS–M (L acceptable, XL must be split)
- [ ] Task dependencies are stated
- [ ] Files likely touched are listed

### Architecture & Sequencing
- [ ] Order follows dependency graph (foundations first)
- [ ] Vertically sliced (not horizontal layers)
- [ ] Each task leaves system working
- [ ] Checkpoints exist between phases
- [ ] High-risk tasks are early

### Risk Coverage
- [ ] Edge cases identified
- [ ] Breaking changes / migrations noted
- [ ] Security implications considered
- [ ] Performance implications considered
- [ ] Rollback strategy exists (if applicable)

### Actionability
- [ ] File paths are specific
- [ ] Verification commands are copy-pasteable
- [ ] Existing code to read is referenced
- [ ] Conventions and patterns are noted

### Proposed Code Quality *(if plan includes implementation details)*
- [ ] Proposed types/signatures handle edge cases
- [ ] Proposed names follow project conventions
- [ ] Proposed abstractions follow existing patterns
- [ ] No security vectors in proposed APIs
- [ ] No performance issues in proposed structures

### Verdict
- [ ] **Approve** — Ready to implement
- [ ] **Request changes** — Gaps must be addressed
```

## Common Rationalizations

| Rationalization | Reality |
|---|---|
| "I'll figure out the details during implementation" | That's how you discover blocking dependencies mid-task. Surface them now. |
| "The tasks are obvious, no need for criteria" | Write them anyway. Explicit criteria surface hidden assumptions. |
| "It's just a small feature, it doesn't need a plan" | Small features have edge cases too. 3 tasks with criteria takes 5 minutes. |
| "The plan is good enough" | "Good enough" without acceptance criteria means the implementer defines "done" — and they might define it differently. |
| "I'll add verification steps later" | Later never comes. The plan is the contract — define verification now. |
| "Risks are minimal" | Every change has risks. If you can't name them, you haven't thought about them. |
| "The file paths are obvious" | They're obvious to the author. The implementer might not know the codebase. |
| "The code in the plan is fine, it'll get reviewed later" | Plan-level code review catches design problems before implementation — fixing them after coding is more expensive. |

## Red Flags

- No acceptance criteria on any task
- Tasks that say "implement the feature" without specifics
- No verification steps anywhere in the plan
- All tasks are XL-sized
- No checkpoints between phases
- Dependency order isn't considered (e.g., API handler before domain model)
- No testing strategy
- Verification commands are generic ("run tests") instead of project-specific
- Plan has 20+ tasks (scope too large for one plan)
- No risk section on a plan with migrations, breaking changes, or security implications
- Horizontal slicing (all domain, then all services, then all API)
- File paths are vague ("update the relevant files")
- Missing open questions section despite stated unknowns
- Proposed code ignores project conventions or existing patterns
- Proposed types use gratuitous `any`/`unknown`/optional without justification
- Proposed APIs don't validate input at boundaries

## See Also

- For producing plans, use the `planner` skill
- For reviewing implemented code, use `code-review-and-quality` — also the criteria source for axis 6
- For security-specific concerns, see `security-and-hardening`
- For testing strategy guidance, see `testing`
