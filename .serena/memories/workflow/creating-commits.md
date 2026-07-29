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

AI-assisted-by: model-name
```

**AI-assisted-by trailer rules:**
- Use only the model name, e.g. `mimo-v2.5`, `deepseek-v4-flash`
- Do NOT add prefixes like `opencode-go/` — use the bare model name

## Commit Type Emojis

`:bug:` bug fix · `:sparkles:` enhancement · `:tada:` new feature · `:recycle:` refactor · `:lipstick:` cosmetic · `:ambulance:` critical fix · `:books:` docs · `:construction:` WIP · `:boom:` breaking · `:wrench:` config · `:zap:` perf · `:whale:` docker · `:paperclip:` other · `:arrow_up:` dep upgrade · `:arrow_down:` dep downgrade · `:fire:` removal · `:globe_with_meridians:` translations · `:rocket:` epic/highlight
