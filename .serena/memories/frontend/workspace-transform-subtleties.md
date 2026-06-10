# Frontend Workspace Transform Subtleties

## Preview vs committed transforms

- High-frequency previews use `app.main.streams/wasm-modifiers` and `workspace-selrect` behavior subjects instead of normal store commits; components consume them through refs that wrap plain atoms.
- `apply-modifiers*` is the lower-level commit path once object/text modifiers are ready. It updates frame guides, frame comment threads, and then emits `update-shapes` with `:reg-objects? true`.
- Transform commits restrict diff attrs to `transform-attrs` to avoid scanning unrelated shape attrs.
- Text transforms may carry derived `:position-data`; `assoc-position-data` attaches it while preserving the original text shape context.

## Component-copy touched suppression

- `calculate-ignore-tree` walks modified shapes and descendants to decide per copy-shape `ignore-geometry?`.
- `check-delta` compares a copy's relative position/rotation to its component root before and after transform. If relative movement is under about 1px and size/rotation are effectively unchanged, geometry touching is suppressed.
- This logic is why pure translations of component copies can avoid marking every descendant as geometry-touched, while resizes/rotations still propagate touched state.

## WASM bridge details

- WASM modifier updates set plugin/local props with parsed geometry/structure modifiers rather than directly mutating file data.
- The position-data recomputation watcher ignores commits tagged `:position-data`; keep that tag when adding derived position-data commits.
- Rotation has separate WASM and non-WASM event paths. Check both when changing rotation modifier semantics.