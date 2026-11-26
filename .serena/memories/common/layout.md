# Common layout and grid

- Layout container data and child layout-item data are removed by different helpers. Check both the container and immediate children when changing cleanup or propagation.
- `assign-cells` skips absolute-positioned children. Only when eligible children still need cells does it ensure at least one row and column, add tracks if needed and assert that the parent grid cells do not overlap. `position-absolute?` also treats hidden shapes as absolute, so hiding a grid child frees its cell.
- `reorder-grid-children` stores cell children in reverse visual cell order while preserving the indices of children without cells, including hidden and absolute shapes. Moving cell-less children to an end can break component alignment.
- The resulting `:reorder-children` is guarded inside component copies unless `allow-altering-copies`; the builder also skips copy grids. Copy child order belongs to component sync. See `mem:common/components`.
- `check-deassigned-cells` removes shape IDs from cell `:shapes` when they are no longer direct children or become absolute; it keeps the cells. Auto-positioning can turn auto cells manual when empty, manual or spanning cells break the sequence; eligible single-span auto cells can then be compacted.
- `fix-overlaps` is marked for development in the source and removes an overlapping cell. It tests whether the first cell map is empty, not whether its `:shapes` is empty; do not assume it prefers cells without shapes or depend on it as normal production repair.
