# Frontend Workspace Token Subtleties

## Token refs and visibility

- Workspace token refs intentionally hide the internal hidden theme from theme trees/lists and expose active tokens through `get-tokens-in-active-sets`.
- Token values stored on shapes are token names under `:applied-tokens`, not token ids. Renames/group renames must update those paths in common token logic.

## Token application

- Token application refuses to run while a text shape is in text-editing mode and shows a warning instead.
- Applying a token writes token names into shape `:applied-tokens`, resolves active tokens through Style Dictionary or `tokenscript` depending on feature flags, updates concrete shape attrs, and wraps the operation in an undo transaction.
- Applying composite typography removes atomic typography token attrs; applying atomic typography removes the composite typography token attr.
- Spacing tokens have a special split path: layout containers receive gap/padding updates, while immediate children of layouts receive margin updates.

## Propagation

- Token propagation resolves active tokens, buffers many `update-shapes` commits, walks the current page first then the remaining pages, clears affected frame/component thumbnails, and drops `:position-data` for text shapes on non-current pages so it can be regenerated.