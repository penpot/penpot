# Creating Commits

Commit only on explicit request. Before commit: `git status`; exclude unrelated user changes.

**Never stage a plan file.** A plan is session bookkeeping, not part of the
change: the commit carries the code and the tests. The `implement-plan` flow
still closes the plan (`Status: done` plus one `Review Log` line with the issue
URL); that is not a file to ship, and its absence from the commit is correct,
not a missing piece.

Do not guess or hallucinate git author information (Name or Email). Never include the
`--author` flag in git commands unless specifically instructed by the user for a unique
case; assume the local environment is already configured. Allow git commit to
automatically pull the identity from the local git config `user.name` and `user.email`.


## Message Format

```
:emoji: Subject line (imperative, capitalized, no period, <=80 chars)

Body explaining what changed and why.
Wrap lines at 80 characters.
Keep each line concise.

AI-assisted-by: model-name
```

## HARD RULES (inexcusable)

These rules are not advisory. Do not commit until every one holds. A commit
that breaks them is wrong, even if the code is right.

- **Body lines MUST wrap at 80 characters or fewer.** Measure every line; do
  not eyeball it. This is the rule most often skipped.
- **Subject MUST be ≤80 chars**, imperative, capitalized, no trailing period.
  Merge/Revert/Reapply commits skip the length check: git prepends those
  prefixes to an existing subject.
- **MUST be a blank line** between subject and body.
- **MUST run `scripts/check-commit` and get exit code 0 before finishing.**
  It mechanically validates the rules above; a failing run is a blocker.
  - It checks `HEAD` by default: `./scripts/check-commit`
  - For another commit: `./scripts/check-commit -c <ref>`
- **NEVER** hand-wave the body as "one long line". If a line exceeds 80,
  break it at a space.
- **NEVER add a `Co-authored-by:` trailer.** It is forbidden; `scripts/check-commit` fails the commit when any line matches it (case-insensitive).
- CI runs `scripts/check-commit` on every new commit (`mem:workflow/creating-commits` is enforced by `.github/workflows/commit-checker.yml`), so a forbidden trailer fails the build.
- Exceptions inside the body (do not wrap these): `Signed-off-by:`,
  `AI-assisted-by:` trailers, and lines carrying a URL.

**AI-assisted-by trailer rules:**
- Use only the model name, e.g. `mimo-v2.5`, `deepseek-v4-flash`
- Do NOT add prefixes like `opencode-go/` — use the bare model name

## Commit Type Emojis

`:bug:` bug fix · `:sparkles:` enhancement · `:tada:` new feature · `:recycle:` refactor · `:lipstick:` cosmetic · `:ambulance:` critical fix · `:books:` docs · `:construction:` WIP · `:boom:` breaking · `:wrench:` config · `:zap:` perf · `:whale:` docker · `:paperclip:` other · `:arrow_up:` dep upgrade · `:arrow_down:` dep downgrade · `:fire:` removal · `:globe_with_meridians:` translations · `:rocket:` epic/highlight

## Referencing Issues

Use `Closes #NNNN` (not `Fixes #NNNN`) in commit and PR body text for readable context. The explicit issue-to-PR link is assigned with `python3 scripts/gh.py link-issue <ISSUE_NUMBER> <PR_NUMBER>` as described in `mem:workflow/creating-prs`; do not rely on the keyword to create that link.
