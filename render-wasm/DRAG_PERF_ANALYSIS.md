# Drag performance analysis — branch `superalex-improve-modifiers-flickering`

Date: 2026-04-24
Scope: per-rAF cost of `set_modifiers` → `start_render_loop` when dragging
a shape inside a dense frame (~1400 shapes in one 1:1 atlas tile).

## TL;DR

- One change kept in this branch: in `rebuild_modifier_tiles`, skip
  `all_with_ancestors` during `is_interactive_transform()`. This removes a
  wide ancestor-driven tile eviction that was triggering full tile
  re-renders on every rAF.
- This helped for dragged shapes that do **not** themselves cover the
  dense tile. For the user's test case (dragged shape's own bbox covers
  tile (0,0) with ~1400 siblings), frames are still 400–580 ms.
- Remaining cost is structural: ~0.3 ms × ~1400 Skia draws per tile =
  ~400 ms to render the one tile that holds the dense cluster. The
  anti-flicker guard (render.rs:2043) forces the whole tile to complete
  in a single rAF during interactive transform, so the cost can't be
  amortised across frames without a visible regression.
- Options A (relax the anti-flicker guard), B/C (clip-based dirty-rect
  narrowing), and drag-sprite resurrection all fail for this shape.
  Remaining candidates are scroll-blit and sprite-of-dragged-shape,
  both carry risk.

## The one change kept

`render-wasm/src/render.rs` — `rebuild_modifier_tiles`:

```rust
pub fn rebuild_modifier_tiles(
    &mut self,
    tree: ShapesPoolMutRef<'_>,
    ids: Vec<Uuid>,
) -> Result<()> {
    if self.options.is_interactive_transform() {
        self.update_tiles_shapes(&ids, tree)?;
    } else {
        let ancestors = all_with_ancestors(&ids, tree, false);
        self.update_tiles_shapes(&ancestors, tree)?;
    }
    Ok(())
}
```

### Why this helps

`all_with_ancestors` walks up from the dragged shape to the root. For a
shape inside a frame, that pulls in the frame itself. `update_shape_tiles`
on the frame computes the union of the frame's old and new extrects and
calls `remove_cached_tile_surface` on every atlas tile the frame covers —
which, for a frame that covers the whole viewport, is *every* visible
tile, including dense ones with hundreds of non-dragged siblings.

The anti-flicker guard then forces those tiles to complete in the same
rAF, so a 1 ms per-tile cost balloons into hundreds of tiles × their
individual shape draw costs.

Skipping the ancestor walk during interactive transform keeps eviction
bounded to the dragged shape's own tiles. Ancestor *extrect* caches are
still invalidated separately by `ShapesPool::set_modifiers`
(`state/shapes_pool.rs:214` — `all_with_ancestors(..., true)` on
`modified_shape_cache`), so layout metadata is correct. Tile-index
reconciliation happens post-gesture via `rebuild_touched_tiles` when the
commit path runs.

### Where this is not enough

The dragged shape's own bbox still evicts every tile it covers. If that
shape is, say, a 3000×1000 rectangle sitting on top of a cluster of 1400
small siblings inside tile (0,0), the dragged shape's extrect is tile
(0,0), so `update_shape_tiles(dragged)` evicts (0,0) on every rAF. The
next render of (0,0) re-draws all ~1400 siblings from scratch. That's
the 400 ms.

## Structural cost ceiling

Per traces captured before cleanup:

```
SLOW_TILE (0,0) 376ms shapes=1319
SLOW_TILE (0,0) 403ms shapes=1424
SLOW_TILE (0,0) 583ms shapes=1426
```

Arithmetic: ~0.3 ms per Skia draw × shape count in the tile ≈ the slow
frame time. Tile cost scales linearly with tile density. There is no
algorithmic reduction available as long as the full tile must be
re-rasterised from a cold atlas.

### Anti-flicker guard

`render.rs:2043` (commit `98c8bb1746`): during interactive transform,
`start_render_loop` iterates until all visible tiles complete in the
same rAF, bypassing the normal per-rAF budget. This exists because
`apply_render_to_final_canvas` is atomic — it only publishes to the
Target surface on full tile completion, not on EARLY_RETURN. A tile
left half-drawn is invisible; a tile evicted mid-gesture with no
replacement renders as background colour.

Removing or loosening the guard makes the dragged shape appear frozen
at its old position for however many rAFs the evicted tile needs to
complete. At 28 ms per rAF chunk and 15 chunks that's ~400 ms of
visual freeze — the same total cost, just visible.

## Why the obvious workarounds don't apply

### Option A — relax the guard, spread cost across rAFs

Would seem to amortise the tile re-render. Fails because the atomic
publish semantics mean Target doesn't update until the tile completes.
The dragged shape would visually freeze for the duration. Same total
cost, worse UX.

### Option B/C — clip-based dirty-rect re-rendering of the tile

The idea: instead of re-rasterising the whole tile, clip to the union
of `old_bbox ∪ new_bbox` and only re-render shapes intersecting the
dirty rect. Fails for translation of a wide shape: the union is
approximately the whole shape bbox, and in the user's test case the
shape bbox already covers the whole tile — so every sibling still
intersects and still draws. The clip doesn't narrow the work.

Clip-based narrowing is only beneficial when the dragged shape is
small relative to the tile. In that case the task-17 change already
suffices (ancestor eviction skipped, only the shape's own tiles are
touched, and the shape's tiles are small enough that full re-render
is cheap).

### Option D — resurrect the drag-sprite fast path

Documented as abandoned on 2026-04-24 in
`memory/drag-sprite-abandoned.md`. Summary: capturing the dragged
subtree into a GPU Image + rewriting the atlas with "scene minus
shape" worked in principle but was structurally fragile — every tile
eviction path (`rebuild_touched_tiles`, `with_current_shape_mut!` →
`touch_current()`, any other `remove_cached_tile_surface` caller)
would need its own "is drag active?" guard. The sprite maintains a
parallel cache the rest of the renderer doesn't know about.

## Remaining workaround candidates

### 1. Scroll-blit for pure translation

If the modifier is a pure translation (no rotation, no scale, no
layout reflow, no fill change), the tile's pre-drag content is still
valid for all pixels except:

- the strip the shape is vacating (needs "scene minus shape" fill)
- the strip the shape is entering (needs old atlas + shape at new
  position)

Implementation sketch: snapshot the tile's atlas at drag start, blit
it translated by `−delta`, then redraw only the dragged shape at its
new position and redraw whatever siblings intersect the exposed
strips.

Risk: identical correctness fragility to the drag-sprite approach —
any atlas-touching code path during the drag invalidates the
snapshot. Plus it only handles pure translation; the moment the user
triggers a rotation or a layout change the fast path falls off.

### 2. Sprite-of-dragged-shape only (no atlas rewrite)

Capture just the dragged shape into a GPU Image at drag start.
Per rAF: re-render the tile normally from the atlas (shape drawn
at old position — which is still correct in the atlas since we
didn't evict it), then paint the sprite on top at the
modifier-transformed position, with the shape's atlas contribution
somehow masked out.

Problem: masking out the shape's own contribution to the atlas
without a separate "scene minus shape" capture is hard. If the
shape has transparency, strokes that extend beyond fills, blend
modes, or effects, there's no clean mask. You'd end up with the
shape painted twice — once at the old position (from the atlas)
and once at the new (sprite).

Same fragility as option D for any path that evicts the shape's
tiles from the atlas mid-drag.

### 3. Deferred render of non-dragged shapes

Render the dragged shape every frame, but only re-render the
surrounding siblings every N-th frame or after pointer idle. The
dragged shape stays responsive; the backdrop updates at a lower
rate. Visual artifact: siblings that overlap the dragged shape
will appear stale (stacking-order glitches).

Acceptable UX if the overlap is small, ugly if the shape is being
dragged across a dense cluster.

## Measurement hooks preserved

None in the current tree. The `performance::measure!("set_modifiers")`
wrapper referenced in `memory/drag-sprite-abandoned.md` was also
removed during cleanup. If re-adding instrumentation, the key stages
to time are:

- `set_modifiers` whole-function (main.rs, inside `with_state_mut!`)
- `rebuild_modifier_tiles` (render.rs:3375)
- per-tile cost inside the `start_render_loop` tile walk (render.rs,
  search for `render_shape_tree_partial` and the tile iteration)
- `apply_render_to_final_canvas` (only fires on full-tile completion)

The dominant cost for this test case is the per-tile shape walk.
`rebuild_modifier_tiles` is sub-millisecond after task 17.

## Files

- `render-wasm/src/render.rs:3375` — the kept change
  (`rebuild_modifier_tiles` branching on `is_interactive_transform()`)
- `render-wasm/src/render.rs:2043` — anti-flicker guard
  (single-rAF completion during interactive transform)
- `render-wasm/src/render.rs:1716` — atlas backdrop composition during
  interactive transform
- `render-wasm/src/state/shapes_pool.rs:214` — `ShapesPool::set_modifiers`
  invalidates ancestor extrect caches (independent of the render-state
  walk, so task 17 doesn't break layout metadata)
- `render-wasm/src/main.rs` — `set_modifiers` / `set_modifiers_end` (now
  back to a clean minimum after drag-sprite removal)
