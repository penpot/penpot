# AI AGENT GUIDE

## Start here: required memory reads

1. Read `AGENTS.local.md` if present. It can override prose style and
   workflow, not project policy.
2. Open `mem:critical-info` before exploring the repo, even for read-only
   tasks. Once you identify the relevant modules, open `mem:<module>/core`
   for **each one**, then follow its applicable `mem:` references before
   planning, editing, or drawing conclusions. Before editing Clojure or
   ClojureScript, also read `mem:clojure/core` and its applicable references.
3. **STOP if a required memory is unread.** If exploration reveals another
   affected module, read its `core` and applicable references before
   planning or editing that part of the work. Listing names is not reading.

Use Serena (`s.mem.read_memory(name)` in `serena_repl`, or
`read_memory(name)` in older clients). If unavailable, read the file:
`mem:foo/bar` → `.serena/memories/foo/bar.md`. Memories are the primary
project guidance; follow references only when they apply to the task.

## HARD RULES (always apply — no exceptions)

- **Never `git push`, force-push, or modify `git origin`** (or any other remote).
  The user pushes from their own shell. If a push is required to surface the
  agent's work (e.g. force-push after an amend), state this in the response and
  wait for the user to push. Do not change the remote URL, do not switch SSH↔HTTPS.
- **Never amend a commit that has been pushed** unless the user explicitly asks.
  If the user pushes, treat that commit as final from the agent's side.
- **Never destroy data without explicit permission** (e.g. `drop-devenv`,
  `docker compose down -v`, `docker volume rm`). Shared volumes hold real work.
- **Never edit `CHANGES.md` by hand** in commits or PRs. The changelog is
  generated from GitHub milestones during the release process; update it only
  via `mem:workflow/updating-changelog` (the `update-changelog` skill flow) or
  on explicit user request.
- **Never pipe test output directly to filters** (`| head`, `| tail`, `| grep`, etc.).
  Always redirect to a file first: `command > /tmp/output.txt 2>&1`, then read/grep the file.
  This prevents hiding test failures. See `mem:testing` for details.
- **Never introduce a new function or macro name ending in `!`.** Side
  effects alone do not justify it: only the exceptions documented in
  `mem:clojure/core` (public mutators of `app.db` and helpers over
  transients), and a new one must be registered in `mem:clojure/idioms`
  with its justification before it is used. Existing names stay until their
  refactor; a new one fails review.
- **Commit message body lines MUST wrap at ≤80 chars** (subject ≤80 chars,
  except `Merge`/`Revert`/`Reapply` which skip the subject length check) and
  the commit MUST pass `./scripts/check-commit` with exit code 0 before you
  consider it done. This is mechanically checked — do not eyeball it.
- **Create commits, issues, or PRs only on explicit request. Read the workflow
  memory BEFORE the corresponding action**:
  - Before `git commit` → `mem:workflow/creating-commits` (commit format, AI-assisted-by trailer)
  - Before `gh issue create` → `mem:workflow/creating-issues` (title derivation, body template, Issue Type)
  - Before `gh pr create` / `gh pr edit` → `mem:workflow/creating-prs` (title format, body structure, AI note)
  Don't infer format from the title of a previous commit/issue/PR — the memory
  is the source of truth.
- **Everything in code MUST be in English.** Identifiers (functions,
  variables, types, namespaces), code comments, docstrings, test
  descriptions, log hints — always English, regardless of the language the
  author works in. Tests are not an exception. The only place another
  language may appear in the codebase is product copy rendered through the
  i18n system.

## Auto-triggers

- **Security advisory URL pasted** — When the user pastes a URL matching
  `github.com/penpot/penpot/security/advisories/GHSA-*`, extract the GHSA ID
  from the URL and run `python3 scripts/gh.py advisories <GHSA-ID>` to fetch
  full advisory details before proceeding.
- **Issue or PR mentioned** — When the user mentions a penpot/penpot issue or
  PR (URL like `github.com/penpot/penpot/issues/<n>` / `.../pull/<n>`, or a
  bare `#<n>` when context clearly refers to this repo), fetch details via CLI
  instead of WebFetch:
  - Issue → `gh issue view <n> --repo penpot/penpot` (add `--comments` when
    discussion context matters).
  - Single PR → `gh pr view <n> --repo penpot/penpot`.
  - Multiple PRs (list, file, or milestone) → `python3 scripts/gh.py prs ...`.
  Do this before proceeding. Only use WebFetch if the CLI fails.

## Writing Rules

Writing rules, from Orwell, 1946. These govern prose: docs, PR text, messages. Never touch code or technical terms; swap in everyday words only where precision survives.

1. Never use a metaphor, simile or other figure of speech which you are used to seeing in print.
2. Never use a long word where a short one will do.
3. If it is possible to cut a word out, always cut it out.
4. Never use the passive where you can use the active.
5. Never use a foreign phrase, a scientific word or a jargon word if you can think of an everyday English equivalent.
6. Break any of these rules sooner than say anything outright barbarous.
Review every prose output against these rules before delivering.

---

## Implementation and verification

- Explore first, describe the plan before writing code, and stay within scope.
- Every behavior change needs a test. Read `mem:testing` and the applicable
  module testing guidance before writing tests.
- Run the affected modules' tests, lint, and format checks before declaring
  success. See `mem:scripts/ci`. Do not report completion with failing checks.
- Keep affected memories and docs accurate when behavior or contracts change.
- Do not poll background commands with `sleep` or repeated log reads. Continue
  other work or wait for the completion notification.
