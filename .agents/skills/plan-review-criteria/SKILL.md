---
name: plan-review-criteria
description: Criteria for reviewing implementation plans, including evidence, severity and verdicts. Loaded by the review-plan subagent.
---

# Plan Review Criteria

Evaluate whether the plan can deliver its stated goal safely and whether a
skilled implementer can execute and verify it without guessing. Perfect plans
do not exist: do not block on personal preferences or missing sections that
add no useful information. Apply each criterion in proportion to the plan's
scope and risks; explain when a criterion is not applicable.

Follow `review-plan` for delegation, permitted edits, decisions and handover.
Do not run tests, builds or other state-changing checks during the review.

## Evidence and scope

- Read the plan, relevant code, project guidance and module memories. Check
  that cited paths, symbols and proposed commands exist and are appropriate;
  distinguish verified facts from assumptions and unknowns. Do not invent
  findings or claim checks were run when they were not.
- Check that tasks serve the stated goal and respect existing requirements and
  decisions. Identify scope creep, contradictions and dependencies on external
  work. A consequential change in scope, behavior or tradeoffs needs the user's
  decision.
- Evaluate the proposed approach even without code snippets: does it solve the
  problem, fit existing conventions and avoid unnecessary complexity? If the
  plan includes code, also check its types, signatures and examples.

## Six review axes

### 1. Completeness

Are the problem, intended outcome, affected areas and relevant constraints
clear? Does the plan explain important decisions and include an appropriate
testing strategy? Identify missing information that would change the outcome
or force an implementer to guess. Require risk, rollback, migration or
parallelization details only when relevant.

### 2. Task quality

Are tasks scoped, ordered and specific enough to execute? Check likely files,
dependencies, rationale, acceptance criteria and verification where relevant.
Acceptance criteria should describe observable behavior, including important
failure cases. Split work when it is too large to understand or verify safely,
not merely because it crosses an arbitrary task count.

### 3. Architecture and sequencing

Does the approach fit the existing architecture and reuse appropriate
facilities? Do dependencies precede their consumers, and can the work be
verified as it progresses? Prefer independently testable slices where useful;
use checkpoints where risk or coordination warrants them, not at a fixed
interval. Flag unrelated work or abstractions that add complexity without
helping the goal.

### 4. Risk coverage

Consider relevant edge cases, security, performance, compatibility, data
integrity, migrations and external dependencies. Where failure has meaningful
consequences, check detection, mitigation and rollback or recovery. Do not
invent risks or demand a rollback section for changes that do not need one.

### 5. Actionability and verification

Can the implementer identify the affected code, follow project conventions and
run the stated project-specific checks? Are commands and prerequisites correct?
Do tests and other checks demonstrate the intended behavior and important
failure cases? Surface unknowns honestly rather than presenting an unverified
command or assumption as fact.

### 6. Proposed solution quality

Assess correctness, clarity, architecture, security and performance of the
proposed solution, even when the plan contains no code. For any code sketches,
also check boundary cases, names, types and contracts. Preserve intended
behavior unless a change is explicitly agreed; prefer the simplest solution
that meets the requirements.

## Findings and verdict

Give each reported item a stable ID (`P1`, `P2`, …); retain it throughout the
review and any decision walkthrough. For each item state the evidence, impact,
concrete remedy and verification needed. For a consequential user decision,
include real options, consequences and a recommendation.

- **Blocking:** implementation cannot proceed safely or an essential user
  decision remains open. Explain what blocks it.
- **Improvement:** a clear correction that preserves the agreed goal, scope
  and behavior. Apply it under `review-plan`'s permitted-edit rules.
- **Optional:** a suggestion that does not prevent implementation. Do not
  silently expand scope or override the user's decisions.

Report applied improvements and any outstanding items with their IDs. Give a
provisional verdict before any walkthrough and a final verdict after applying
the decisions:

- **Ready:** no blocking issue or essential decision remains.
- **Awaiting decisions:** user decisions are needed before the plan is ready.
- **Blocked:** required work or evidence is missing and cannot be resolved by
  applying clear improvements or obtaining the current decisions alone.

Do not approve an unresolved blocker. A plan may be ready with optional
suggestions; identify them without treating them as required.
