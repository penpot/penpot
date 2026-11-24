# Design tokens

- Start at `app.common.types.tokens-lib`, `app.common.types.tokens-status`, `app.common.files.tokens` and `app.main.data.workspace.tokens`. Current file activation is stored separately in `TokensStatus` as theme and set IDs. Legacy `TokensLib` activation and its hidden theme support import and migration; workspace theme lists hide that internal theme.
- `get-tokens-in-active-sets` resolves the status's active set IDs in library order, so later active sets override earlier tokens with the same name. Activating a theme deactivates other themes in its group and recomputes active sets; directly changing a set clears active themes. When migrating legacy data, `make-tokens-status-from-lib` converts active paths and sets to IDs.
- DTCG export omits the internal hidden theme; the status-aware path derives `activeThemes` and `activeSets` from `TokensStatus`, while the legacy overload derives them from the library. Single-set imports reject input with no supported tokens. Multi-set imports normalize names, omit unsupported token types and ignore theme references to missing sets; `import-decoded-json` reports dropped or unknown input.
- Shapes store token names in `:applied-tokens`, not IDs. Token and group renames must update those paths. Transport uses Transit handlers; internal file-data storage uses Fressian handlers and migrations for older token-lib versions.

## Workspace application and propagation

- Applying a token is blocked while a text shape is being edited. Otherwise the workspace records its name, resolves active tokens through Style Dictionary or tokenscript according to feature flags, updates concrete attrs and groups the operation in an undo transaction.
- Composite and atomic typography token attrs replace one another. Spacing application targets gap/padding on layout containers and margins on immediate layout children.
- Propagation resolves active tokens and buffers `update-shapes` commits, processing the current page before other pages. It clears affected frame/component thumbnails and drops text `:position-data` on non-current pages so it can be regenerated.
- For workspace state and undo see `mem:frontend/workspace-store` and `mem:frontend/workspace-commits`; for geometry and text data see `mem:common/geometry` and `mem:common/text`.
