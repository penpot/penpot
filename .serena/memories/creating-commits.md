# Creating Commits

## Message Format

```
:emoji: Subject line (imperative, capitalized, no period, ≤70 chars)

Body (clear, concise description)

Co-authored-by: <You (the LLM)>
```

## Commit Type Emojis

`:bug:` bug fix · `:sparkles:` enhancement · `:tada:` new feature · `:recycle:` refactor · `:lipstick:` cosmetic · `:ambulance:` critical fix · `:books:` docs · `:construction:` WIP · `:boom:` breaking · `:wrench:` config · `:zap:` perf · `:whale:` docker · `:paperclip:` other · `:arrow_up:` dep upgrade · `:arrow_down:` dep downgrade · `:fire:` removal · `:globe_with_meridians:` translations · `:rocket:` epic/highlight

## Changelog (CHANGES.md)

Update `CHANGES.md` for user-facing or notable changes. Add entry under the current unreleased version in the matching section (`### :boom:`, `### :sparkles:`, `### :bug:`, etc.).

Entry format:
```
- Description of change [Taiga #NNNN](https://tree.taiga.io/project/penpot/us/NNNN)
```
or for GitHub issues/PRs:
```
- Description of change [Github #NNNN](https://github.com/penpot/penpot/issues/NNNN)
```

Changes that affect the JavaScript plugin API must additionally be documented in `plugins/CHANGELOG.md`:
  * Add an entry at the top of the file (unreleased section)
  * Prefix entries that change the types/signatures in the API with `**plugin-types:**` and changes affecting the runtime with `**plugin-runtime:**`.
