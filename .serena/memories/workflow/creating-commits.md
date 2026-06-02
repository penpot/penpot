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

Co-authored-by: <You (the LLM)>
```

## Commit Type Emojis

`:bug:` bug fix · `:sparkles:` enhancement · `:tada:` new feature · `:recycle:` refactor · `:lipstick:` cosmetic · `:ambulance:` critical fix · `:books:` docs · `:construction:` WIP · `:boom:` breaking · `:wrench:` config · `:zap:` perf · `:whale:` docker · `:paperclip:` other · `:arrow_up:` dep upgrade · `:arrow_down:` dep downgrade · `:fire:` removal · `:globe_with_meridians:` translations · `:rocket:` epic/highlight


## Changelogs

**IMPORTANT:** do not modify the changelog unless it explicitly asked.

For user-facing or notable changes, update the relevant changelog under the unreleased section:
- Main app/modules (`backend`, `frontend`, `common`, `render-wasm`, `exporter`, `mcp`): root `CHANGES.md`.
- Plugin subproject changes: `plugins/CHANGELOG.md`.

Entry format uses the matching category (`:sparkles:`, `:bug:`, etc.) and references the GitHub issue:

```
- Short description of change [#NNNN](https://github.com/penpot/penpot/issues/NNNN)
```

Plugin API changelog prefixes: type/signature -> `**plugin-types:**`; runtime behavior -> `**plugin-runtime:**` in `plugins/CHANGELOG.md`.
