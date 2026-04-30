---
name: backport-commit
description: Port changes from a specific Git commit to the current branch by manually applying the diff, avoiding cherry-pick when it would introduce complex conflicts.
---

# Backport Commit

Port changes from a specific Git commit to the current branch by manually
applying the diff, avoiding `git cherry-pick` when it would introduce
complex conflicts.

## When to Use

Use this skill whenever the user asks to backport a commit, especially when:

- The commit touches multiple modules or files with significant divergence
- `git cherry-pick` is explicitly ruled out ("do not use cherry-pick")
- The target commit is old enough that conflicts are likely
- The commit introduces both source changes AND new files (tests, etc.)
- You need full control over how each hunk is applied

## Workflow

### 1. Identify the target commit

```bash
# Verify the commit exists and understand what it does
git log --oneline -1 <commit-sha>

# Get the full diff (including new/deleted files)
git show <commit-sha>

# Capture the original commit message for later reuse
git log --format='%B' -1 <commit-sha>
```

### 2. Identify affected modules

From the file paths in the diff, determine which Penpot modules are affected
(frontend, backend, common, render-wasm, etc.) and read their `AGENTS.md`
files **before** making any changes. If a module has no `AGENTS.md`, skip
that step — verify with `ls <module>/AGENTS.md` first.

### 3. Read the current state of each affected file

For every file the diff touches, read the current version on disk to understand
context and ensure correct placement before editing.

### 4. Apply changes manually (the core of this approach)

Process every hunk in the diff using the appropriate tool:

| Diff action | Tool to use |
|-------------|-------------|
| Modify existing file | `edit` — use enough surrounding context in `oldString` to uniquely match the location |
| Add new file | `write` — include proper license header and namespace conventions matching project style |
| Delete file | `bash rm <path>` |
| Rename/move file | `bash mv <old> <new>`, then apply any content changes with `edit` |

> **Tip:** Group nearby hunks from the same file into a single `edit` call.
> Use separate calls when hunks are far apart to keep `oldString` short and
> unambiguous.

Repeat until **all** hunks in the diff are ported.

### 5. Validate

Run **lint**, **check-fmt**, and **tests** for every affected module (see each
module's `AGENTS.md` for the exact commands). If the formatter auto-fixes
indentation, verify the logic is still semantically correct. All checks must
pass before moving on.

### 6. Port the changelog entry (if any)

If the original commit added or modified a `CHANGES.md` entry, port that entry
too — adapting wording and version references for the target branch.

### 7. Commit

Ask the `commiter` sub-agent to create a commit. Stage all relevant files
(exclude unrelated untracked files) and provide the original commit message as
a reference, adapting it as needed for the target branch context.

## Key Principles

- **Context matters** — always read files before editing; never guess
  indentation or surrounding code
- **Lint + format + test** — never skip validation before committing
- **Preserve intent** — keep the original commit message meaning; the
  `commiter` agent handles formatting
