---
description: Resolve local git conflicts and stage the resolved files with git add — never continues the rebase
agent: build
---

# Fix Git Conflicts

Resolve conflicts in the local repository. The user handles finishing the
rebase themselves — you must **never** run `git rebase --continue`,
`git rebase --skip`, `git merge --continue`, or anything similar.

## Phase 1 — Understand the problem (read-only)

1. Run `git status` to detect the conflict state (rebase, merge, cherry-pick, etc.) and list conflicted files.
2. For each conflicted (unmerged) file, understand the situation **without modifying anything**:
   - Read the file and identify the conflict markers (`<<<<<<<`, `=======`, `>>>>>>>`).
   - Inspect both sides — `git show <ours>:<file>` and `git show <theirs>:<file>` — plus `git log`/`git show` on the commits involved to understand intent.
   - Identify what each side changed and why, and how they should be combined.

## Phase 2 — Present the resolution plan

3. **Present a clear plan to the user before touching any file.** For each conflicted file, state:
   - What each side changed and why.
   - Your proposed resolution and the reasoning behind it.
   - How the two sides are combined (both additive → merge; both modify the same code → keep the semantically correct version, merging intent from both sides when clear from code and context).
4. **Ask the user only when genuinely unclear.** Do not ask about anything you can determine yourself from the code, commit messages, or context. Only decisions that are not determinable and change the outcome (e.g. conflicting product decisions, which side to discard) warrant a question. **Collect all such questions together in an "Open Questions" section at the end of the plan**, so the user has full context to answer them properly.
5. **Wait for the user to accept the plan** (and answer any open questions) before editing, staging, or otherwise modifying anything.

## Phase 3 — Execute

6. Resolve each conflicted file by editing the file to the agreed merged content and removing all conflict markers.

## Phase 4 — Stage and verify

7. **Stage every resolved file** with `git add <file>`. Do not stage unrelated untracked files unless clearly part of the resolution.
8. Verify no conflict markers remain (search for `<<<<<<<` / `>>>>>>>` in resolved files) and that `git status` shows no unmerged paths.

## Phase 5 — Report

9. Briefly report the conflict state, how each conflicted file was resolved (and any answers received to open questions), and stop — do **not** run `git rebase --continue` or any other continuation command.
