# Frontend Workspace Transform Subtleties

## Preview vs committed transforms

- High-frequency previews use `app.main.streams/wasm-modifiers` and `workspace-selrect` behavior subjects instead of normal store commits; components consume them through refs that wrap plain atoms.
- `apply-modifiers*` is the lower-level commit path once object/text modifiers are ready. It updates frame guides, frame comment threads, and then emits `update-shapes` with `:reg-objects? true`.
- Transform commits restrict diff attrs to `transform-attrs` to avoid scanning unrelated shape attrs.
- Text transforms may carry derived `:position-data`; `assoc-position-data` attaches it while preserving the original text shape context.
- A drag commits when its `mse/drag-stopper` fires, `:interrupt` (Escape) included. The save-failure lock emits `::plk/cancel-gestures` first: drags built on `plk/gesture-stopper` (start-move, start-resize, start-rotate, start-move-line-point, box/curve/line drawing) see a `cancelled?` flag set before the stop and emit `dwt/cancel-transform` (`clear-local-transform` + `finish-transform`) or `cancel-drawing` instead. A new drag gesture must use it (or, in a component, drop the drag when read-only turns on): `commit-changes` refuses the commit anyway, but the preview (WASM modifiers/props) would stay.

## Component-copy touched suppression

- `calculate-ignore-tree` walks modified shapes and descendants to decide per copy-shape `ignore-geometry?`.
- `check-delta` compares a copy's relative position/rotation to its component root before and after transform. If relative movement is under about 1px and size/rotation are effectively unchanged, geometry touching is suppressed.
- This logic is why pure translations of component copies can avoid marking every descendant as geometry-touched, while resizes/rotations still propagate touched state.

## WASM bridge details

- WASM modifier updates set plugin/local props with parsed geometry/structure modifiers rather than directly mutating file data.
- The position-data recomputation watcher ignores commits tagged `:position-data`; keep that tag when adding derived position-data commits.
- Rotation has separate WASM and non-WASM event paths. Check both when changing rotation modifier semantics.