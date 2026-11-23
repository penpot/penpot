---
name: review-code
description: Code review flow — review a diff, PR, or code change, delegating the review to a subagent that follows the code-review-criteria skill. Use it when the user asks to review code or a PR, in any phrasing.
---

# Review Code

Act as a senior software engineer and perform a thorough code review.

## When to use

- The user asks to review code, in any phrasing: "review this diff",
  "review the PR", "check my changes", "code review" — or runs
  `@review-code`.
- A commit, branch, PR, or diff is ready and the user wants it assessed
  before merge.

## Instructions

1. **Determine what is being reviewed** from the user context: a working-tree
   diff, a commit range, a branch, a PR (number or URL), or specific files. If
   the target is ambiguous, ask before reviewing.
2. Delegate the review to `general` unless the user specifies another
   subagent. Pass all relevant user context, the read-only rules and isolated
   verification exception below, and these instructions:
   - Load `code-review-criteria` and follow its process and output format.
   - Read `AGENTS.md` (if present) and the module memories it points to for the
     code being reviewed.
   - Return the COMPLETE review, verbatim, exactly as the skill instructs it to
     be produced. Never summarize it.
   - Use prior results or CI evidence first; state any limits on verification.
   - Do not invent problems: every finding must be real and actionable.
   - Missing required tests are a finding, not a suggestion.
   - For each finding, give a concrete remedy and the verification it needs.
     If a user decision is needed, include the real options and a recommendation
     in that finding. Do not pause to ask during the review.
   - Prioritize by impact: one structural issue outweighs ten nits, and "this
     could be better" is not a finding — say why and how.
   - Inspect generated files and lockfiles when relevant to the change, its
     dependencies or security; exclude unrelated working-tree modifications.
3. When the subagent returns, output the review to the user verbatim. Do not
   summarize it and do not act on its findings. Retain the subagent session ID,
   review target and complete findings in the session context for follow-up;
   do not invent an ID if the tool does not provide one.
4. If there are no findings, say so and stop. Otherwise, use `question` if
   available (ask in the conversation if not) to offer these next steps:
   - **Walk through findings:** start `@issue-walkthrough` only if the user
     chooses it. Pass the findings and their IDs. Let the walkthrough offer
     planning or stopping once it has recorded the decisions.
   - **Prepare a plan directly:** offer `@make-a-plan` only when its scope and
     the project's rules allow it. On selection, hand over the findings and any
     existing decisions; do not treat undecided recommendations as agreed.
   - **Stop:** end the review without edits. Any direct correction belongs to
     a separate task after the review.

### Follow-up after implementation

When the user agrees to verify corrections, resume the original reviewer using
its session ID if the tool supports it. Give it the original review target and
findings, and the exact correction commit range or diff. Include decisions,
the plan, and verification evidence if they exist. Require it to inspect the
actual corrections and evidence, not rely on its earlier context alone.
State what cannot be verified rather than assuming a result. If the original
session is unavailable or cannot be resumed, tell the user and delegate to a
new reviewer with the same context; never claim it is the original reviewer.
Ask for missing information only if it is needed to identify the original
review or check the correction.

The reviewer follows `code-review-criteria` and remains read-only. It reports
each original finding with its unchanged ID as **resolved**, **pending**, or
**not addressed** (including the agreed reason); report any new problems with
new, non-conflicting IDs. For pending and new problems, give evidence, a remedy,
appropriate verification and any user decision as in the original review.
Summarize separately whether all agreed corrections are resolved and whether
any findings remain, including deferred findings. Do not equate deferred with
resolved. Present the result without implementing fixes; the user chooses
whether to iterate, request an independent review, or stop.

### Hard rule — read-only while reviewing

Never fix findings, implement changes, create commits, or alter the user's
working tree or index during a review or follow-up. Inspect the diff and use
existing test or CI results. Do not rerun the test suite.

If existing evidence cannot settle a specific finding, a focused empirical
check may run without asking in a temporary detached Git worktree created for
the review, subject to the usual tool permissions.
Use the exact version under review, including uncommitted changes if they can
be reproduced without altering the user's working tree or index. Keep any
check artifacts in that worktree and remove it afterward. Do not use a worktree
as a security sandbox. If the version cannot be reproduced or the check cannot
run in isolation, report the limit instead of running it in the user's worktree.

## Posting as a GitHub comment

Post only when the user asks for it explicitly; otherwise keep the review in the
session. PR comments go in English, with this minimal structure:

1. **Intro line** — one sentence: what was found and the overall tone. No
   greetings.
2. **`## Main issues`** — one subsection per required finding: what breaks, the
   failure circumstances, and the minimal fix (code snippet when useful).
   Missing required tests belong here, not under minor suggestions.
3. **`## Minor suggestions`** — one bullet per minor finding: file/function,
   problem, concrete remedy.
4. **Closing** — optional and short. Cut it when it adds nothing.

Reference every finding by its ID (`F1`, `F2`, … from
`code-review-criteria`) so follow-up replies can point to findings
unambiguously.

## User input, overrides and additional context

Take the review target, focus, and overrides from the user's message.
