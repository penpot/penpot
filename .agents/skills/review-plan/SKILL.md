---
name: review-plan
description: Plan review flow — evaluate an implementation plan before it is executed, delegating the review to a subagent that follows the plan-review-criteria skill. Use it when the user asks to review a plan, in any phrasing.
---

# Review Plan

Act as a senior software engineer and perform a thorough review of an
implementation plan.

## When to use

- The user asks to review a plan, in any phrasing: "review this plan",
  "does this plan look right?", "second opinion on the plan" — or runs
  `/review-plan`.
- A plan was just produced (typically by `/make-a-plan`) and the user
  wants it evaluated before executing it.

## Instructions

1. **Determine the plan under review** from the session context (for example, a
   plan just produced by `/make-a-plan`) or from a plan file path given by the
   user (typically under `.agents/plans/`). If a file path is given, read the
   file first so the complete plan is in context.
2. Delegate the review to the `general` subagent (via the task tool), unless the
   user specifies another agent. Include in the prompt the
   **`plan-review-criteria`** skill name and all user context.
3. When the subagent returns, output the review to the user verbatim. Do not
   summarize it and do not act on its findings.
4. Right after the review, suggest the next step based on the verdict. These
   are suggestions — the user decides, and any instruction overrides them:
   - **Approve** → suggest `/implement-plan` to execute it.
   - **Request changes** → suggest `/make-a-plan` to make a plan to address the
     findings.

### Hard rule — read-only while reviewing

This flow is read-only **for the duration of the review**: from the moment it
starts until the user considers the review finished (including any feedback,
questions, or clarifications about it). During that period, never fix,
implement, edit files or create commits — not even "obvious" fixes derived from
the findings. Once the user explicitly states the review is done (or moves on to
a different task), this rule no longer applies and you act as a normal build
agent again.

## Instructions for the subagent

1. Load the **`plan-review-criteria`** skill and follow its process and output
   format.
2. Read `AGENTS.md` (if present) and follow its instructions for finding and
   reading all related documentation and testing memories before reviewing.
3. Return in your final message the COMPLETE review, verbatim, exactly as the
   skill instructs it to be produced. Do not summarize it — include the full
   structured review.

### Strong rules for the subagent

1. Do not invent problems. Every finding must be real and actionable.
2. Read-only: do not modify any file and do not create a commit — reviewing
   never writes.
3. Be specific and constructive. "This could be better" is not helpful — explain
   why and how.
4. Prioritize by impact. One structural issue outweighs ten nits.
5. Judge the plan as the implementer would: every task executable without
   guessing, ordering follows the dependency graph, risks named.

## User context

Extra context in the user's invocation (the message that triggered this skill)
plays the role command arguments play elsewhere: for example, a plan file path
to review, or a different agent to run the review.
