# Creating Commits

Commit only on explicit request. Before commit: `git status`; exclude unrelated user changes.

Do not guess or hallucinate git author information (Name or Email). Never include the
`--author` flag in git commands unless specifically instructed by the user for a unique
case; assume the local environment is already configured. Allow git commit to
automatically pull the identity from the local git config `user.name` and `user.email`.


## Message Format

```
:emoji: Subject line (imperative, capitalized, no period, <=70 chars)

Body explaining what changed and why.
Wrap lines at 76 characters — git log adds a
four-space indent, so 76 + 4 fits an 80-column
terminal. Keep each line concise.

AI-assisted-by: model-name
```

## HARD RULES (inexcusable)

These rules are not advisory. Do not commit until every one holds. A commit
that breaks them is wrong, even if the code is right.

- **Body lines MUST wrap at 76 characters or fewer.** Measure every line; do
  not eyeball it. This is the rule most often skipped. Rationale: `git log`
  indents the body four spaces, so 76 + 4 fits an 80-column terminal.
- **Subject MUST be ≤70 chars**, imperative, capitalized, no trailing period.
- **MUST be a blank line** between subject and body.
- **MUST run `scripts/check-commit` and get exit code 0 before finishing.**
  It mechanically validates the rules above; a failing run is a blocker.
  - It checks `HEAD` by default: `./scripts/check-commit`
  - For another commit: `./scripts/check-commit -c <ref>`
- **NEVER** hand-wave the body as "one long line". If a line exceeds 76,
  break it at a space.
- Exceptions inside the body (do not wrap these): `Signed-off-by:`,
  `Co-authored-by:`, `AI-assisted-by:` trailers, and lines carrying a URL.

**AI-assisted-by trailer rules:**
- Use only the model name, e.g. `mimo-v2.5`, `deepseek-v4-flash`
- Do NOT add prefixes like `opencode-go/` — use the bare model name

## Commit Type Emojis

`:bug:` bug fix · `:sparkles:` enhancement · `:tada:` new feature · `:recycle:` refactor · `:lipstick:` cosmetic · `:ambulance:` critical fix · `:books:` docs · `:construction:` WIP · `:boom:` breaking · `:wrench:` config · `:zap:` perf · `:whale:` docker · `:paperclip:` other · `:arrow_up:` dep upgrade · `:arrow_down:` dep downgrade · `:fire:` removal · `:globe_with_meridians:` translations · `:rocket:` epic/highlight

## Referencing Issues

Use `Closes #NNNN` (not `Fixes #NNNN`) to link a commit to a GitHub issue.
