# Common Layout and Grid Subtleties

## Layout metadata

- Layout container data and child layout-item data are removed by different helpers. Do not assume clearing a layout frame also clears all child layout metadata.
- Layout data can affect both container attrs and immediate child attrs; validate behavior for both sides when changing cleanup or propagation.

## Grid assignment

- Grid `assign-cells` ensures at least one column and row, skips absolute-position children, creates non-tracked rows/cols when children exceed tracked cells, and asserts that assigned cells do not overlap.
- `position-absolute?` counts HIDDEN shapes as absolute: hiding a grid child frees its cell on the next `assign-cells`.
- `reorder-grid-children` rewrites the parent's `:shapes` to the REVERSE of the sorted cell order, but children with no cell (hidden/absolute) keep their original index — do not "fix" this into moving them to an end; that broke copy/main positional slot alignment (referential-integrity crash).
- The `:reorder-children` change it emits is refused on parents inside component copies unless `allow-altering-copies` (same rule as `:mov-objects`); `pcb/reorder-grid-children` also skips copy grids producer-side. Copy child order is owned by the component sync engine.
- Grid deassignment removes cells for shapes that are no longer direct children or have become absolute-positioned.
- Auto-positioning is not just sorting: some auto cells are converted to manual when empty/manual/span state would break the auto sequence, then auto single-span items can be compacted.
- `fix-overlaps` is marked dev-only and removes one overlapping cell, preferring empty cells first. Avoid depending on it as normal production repair.