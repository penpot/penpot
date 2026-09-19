---
name: review-code
description: Code review flow — review a diff, PR, or code change, delegating the review to a subagent that follows the code-review-criteria skill. Use it when the user asks to review code or a PR, in any phrasing.
---

# Review Code

Act as a senior software engineer and perform a thorough code review.

## When to use

- The user asks to review code, in any phrasing: "review this diff",
  "review the PR", "check my changes", "code review" — or runs
  `/review-code`.
- A commit, branch, PR, or diff is ready and the user wants it assessed
  before merge.

## Instructions

1. **Determine what is being reviewed** from the user context: a working-tree
   diff, a commit range, a branch, a PR (number or URL), or specific files. If
   the target is ambiguous, ask before reviewing.
2. Delegate the review to the `general` subagent (via the task tool), unless the
   user specifies another agent. Include in the prompt the
   **`code-review-criteria`** skill name and all user context.
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

This flow is read-only **for the duration of the review**: from the moment it
starts until the user considers the review finished (including any feedback,
questions, or clarifications about it). During that period, never fix,
implement, edit files or create commits — not even "obvious" fixes derived from
the findings. Once the user explicitly states the review is done (or moves on to
a different task), this rule no longer applies and you act as a normal build
agent again.

## Instructions for the subagent

1. Load the **`code-review-criteria`** skill and follow its process and output
   format.
2. Read `AGENTS.md` (if present) and follow its instructions for finding and
   reading all related testing documentation from memories before reviewing.
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
5. Missing tests are an issue, not a suggestion. Report as a severity-tagged
   finding — never as a recommendation.
6. Skip generated files, lockfile-only changes, and unrelated modifications
   unless they introduce security risks.

## User context

Extra context in the user's invocation (the message that triggered this skill)
plays the role command arguments play elsewhere: for example, a PR number or
URL, a commit range, specific files, or a different agent to run the review.
