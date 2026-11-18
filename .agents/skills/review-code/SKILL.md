---
name: review-code
description: Code review flow — review a diff, PR, or code change, delegating the review to a subagent that follows the code-review-criteria skill. Use it when the user asks to review code or a PR, in any phrasing.
slash: true
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
   user specifies another agent. The prompt carries the **`code-review-criteria`**
   skill name, all user context, and these requirements:
   - Load `code-review-criteria` and follow its process and output format.
   - Read `AGENTS.md` (if present) and the module memories it points to for the
     code being reviewed.
   - Return the COMPLETE review, verbatim, exactly as the skill instructs it to
     be produced. Never summarize it.
   - Read-only: modify no file and create no commit. Reviewing never writes.
   - Do not invent problems: every finding must be real and actionable.
   - Missing tests are an issue, not a suggestion — a severity-tagged finding.
   - Prioritize by impact: one structural issue outweighs ten nits, and "this
     could be better" is not a finding — say why and how.
   - Skip generated files, lockfile-only changes and unrelated modifications
     unless they introduce security risks.
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

## Posting as a GitHub comment

Post only when the user asks for it explicitly; otherwise keep the review in the
session. PR comments go in English, with this minimal structure:

1. **Intro line** — one sentence: what was found and the overall tone. No
   greetings.
2. **`## Main issues`** — one subsection per required finding: what breaks, the
   failure circumstances, and the minimal fix (code snippet when useful). A
   missing test belongs here, not under minor suggestions.
3. **`## Minor suggestions`** — one bullet per minor finding: file/function,
   problem, concrete remedy.
4. **Closing** — optional and short. Cut it when it adds nothing.

Reference every finding by its ID (`R1`, `M1`, `S1`, … from
`code-review-criteria`) so follow-up replies can point to findings
unambiguously.

## User input, overrides and additional context

$ARGUMENTS
