# Creating Commits

Commit only on explicit request. Before commit: `git status`; exclude unrelated user changes.

## Message Format

```
:emoji: Subject line (imperative, capitalized, no period, <=70 chars)

Body explaining what changed and why.

Signed-off-by: Real Name <email>
Co-authored-by: <You (the LLM)>
```

The repository requires DCO signoff for code patches. Use `git commit -s` when possible so the `Signed-off-by` line matches the commit author. Documentation-only changes are excluded from the DCO requirement, but using signoff is still usually fine.

## Commit Type Emojis

`:bug:` bug fix · `:sparkles:` enhancement · `:tada:` new feature · `:recycle:` refactor · `:lipstick:` cosmetic · `:ambulance:` critical fix · `:books:` docs · `:construction:` WIP · `:boom:` breaking · `:wrench:` config · `:zap:` perf · `:whale:` docker · `:paperclip:` other · `:arrow_up:` dep upgrade · `:arrow_down:` dep downgrade · `:fire:` removal · `:globe_with_meridians:` translations · `:rocket:` epic/highlight

## Changelogs

For user-facing or notable changes, update the relevant changelog under the unreleased section:
- Main app/modules (`backend`, `frontend`, `common`, `render-wasm`, `exporter`, `mcp`): root `CHANGES.md`.
- Plugin subproject changes: `plugins/CHANGELOG.md`.

Entry format uses the matching category (`:sparkles:`, `:bug:`, etc.) and references the GitHub issue or Taiga story when available:

```
- Description of change [Github #NNNN](https://github.com/penpot/penpot/issues/NNNN)
- Description of change [Taiga #NNNN](https://tree.taiga.io/project/penpot/us/NNNN)
```

Plugin API changelog prefixes: type/signature -> `**plugin-types:**`; runtime behavior -> `**plugin-runtime:**` in `plugins/CHANGELOG.md`.