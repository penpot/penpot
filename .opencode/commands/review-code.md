---
description: Code review — review a diff, PR, or code change with the code-review skill (read-only while reviewing)
agent: build
---

Act as a senior software engineer and perform a thorough code review.

## Instructions

1. **Determine what is being reviewed** from the user context or arguments: a
   working-tree diff, a commit range, a branch, a PR (number or URL), or
   specific files. If the target is ambiguous, ask before reviewing.
2. Delegate the review to the `general` subagent (via the task tool), unless the
   user specifies another agent. Include in the prompt the **`code-review`**
   skill name and all user context.
3. When the subagent returns, output the review to the user verbatim. Do not
   summarize it and do not act on its findings.
4. Right after the review, suggest how to proceed based on the findings. These
   are suggestions — the user decides:
   - **Approve (no required changes):** say so — there is nothing to address.
   - **Minor findings (nits):** applying them directly as-is is fine once the
     review is done — no plan needed.
   - **Substantive findings:** suggest `/make-a-plan` to make a plan to address
     them.

### Hard rule — read-only while reviewing

This command is read-only **for the duration of the review**: from the moment it
starts until the user considers the review finished (including any feedback,
questions, or clarifications about it). During that period, never fix,
implement, edit files or create commits — not even "obvious" fixes derived from
the findings. Once the user explicitly states the review is done (or moves on to
a different task), this rule no longer applies and you act as a normal build
agent again.

## Instructions for the subagent

1. Load the **`code-review`** skill and follow its process and output format.
2. Read `AGENTS.md` (if present) and follow its instructions for finding and
   reading all related testing documentation from memories before reviewing.
3. Return in your final message the COMPLETE review, verbatim, exactly as the
   skill instructs it to be produced. Do not summarize it — include the full
   structured review.

### Strong rules for the subagent

1. Do not invent problems. Every finding must be real and actionable.
2. Read-only: do not modify any file and do not create a commit — this command
   only reviews.
3. Be specific and constructive. "This could be better" is not helpful — explain
   why and how.
4. Prioritize by impact. One structural issue outweighs ten nits.
5. Missing tests are an issue, not a suggestion. Report as a severity-tagged
   finding — never as a recommendation.
6. Skip generated files, lockfile-only changes, and unrelated modifications
   unless they introduce security risks.

## User input, overrides and additional context

$ARGUMENTS
