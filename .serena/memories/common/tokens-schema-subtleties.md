# Common Tokens and Schema Subtleties

## Tokens

- `TokensLib` always ensures an internal hidden theme exists and defaults active themes to that hidden theme path. That hidden theme represents the UI state where active sets are controlled without modifying a user-created theme.
- `get-tokens-in-active-sets` merges tokens from sets selected by active themes in set order, so later active sets with the same token name override earlier ones.
- Activating a real theme in `common.logic.tokens` removes the hidden theme from the active-theme set unless it is the only active theme. Toggling active sets directly copies current active sets into the hidden theme.
- DTCG import/export deliberately hides the internal hidden theme: exports omit it from `$themes` and activeThemes, while `activeSets` records the hidden/current active sets.
- Single-set DTCG/legacy imports throw if no supported tokens are found. Multi-set import normalizes set names, keeps `tokenSetOrder`, rejects conflicting token path names, discards unsupported token types, and validates theme sets against existing sets.
- Token values stored on shapes are token names in `:applied-tokens`, not token ids. Renames and group renames must update those name paths.
- Token serialization has both Transit handlers for frontend/backend transport and Fressian handlers for internal file-data storage, with migrations for older token-lib internal versions.

## Schema

- `app.common.schema/json-transformer` has custom map-of key decoding/encoding, so map keys can be transformed based on the key schema instead of only the value schema.
- `check-fn` throws `ex-info` with default `:type :assertion`, `:code :data-validation`, and `::explain`. Prefer reusable `check-fn`/lazy validators in hot or repeated paths; `sm/check` creates a checker every call.
- `coercer` decodes with the JSON transformer and then checks. This is the common pattern for accepting external JSON-shaped data into internal types.