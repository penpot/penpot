# Degenerate (collapsed) shape geometry: points vs selrect

A shape can end up with a **degenerate `:points`** (collapsed to a zero-length basis — all corners
coincident on a line/point) while its `:selrect` still has a clamped non-zero size (e.g. width
`0.01`). This happens to auto-sized flex/grid containers that collapse to ~0 (e.g. after hiding all
their children). Such a shape is "frozen": you cannot resize it and it won't hug content, because
**scaling a zero-length basis stays zero** — applying any transform to its points is a no-op.

## Where this bites and the fixes

- Applying a transform/modifier to a shape: `app.common.geom.shapes.transforms/apply-transform-generic`
  transforms the shape's `:points`. If they are degenerate it can never grow the shape. Fix: fall
  back to `grc/rect->points (:selrect shape)` (a non-zero basis) when the points are degenerate, so
  the transform lands and the points get repaired. This is the path the WASM renderer uses too:
  `apply-wasm-modifiers` (frontend `data/workspace/modifiers.cljs`) computes the modifier in WASM,
  then applies it on the CLJS side via `gsh/apply-transform` → `apply-transform-generic`.
- Layout auto-size content computation (SVG/CLJS path): `geom.bounds-map/objects->bounds-map`
  derives a shape's bounds from `gco/shape->points` (its `:points`); a degenerate basis there makes
  the projected content size ~0. Fix: fall back to the `selrect` when the points are degenerate.

## render-wasm is NOT affected by the degenerate-points part

render-wasm (`Shape::calculate_bounds` in `render-wasm/src/shapes.rs`) builds bounds from the
**`selrect`** (x/y/width/height), not from the stored points, so it computes the correct content
size and auto-resize scale even when the CLJS-side points are degenerate. Verified live via wasm
debug logging: for a collapsed auto container it reported `basis_x=0.01`, `auto_main=122`,
`after_w=122` — i.e. the WASM result was correct; the failure was purely the CLJS-side application
onto degenerate points. So a CLJS geometry bug here does NOT necessarily need a render-wasm change —
check which source (points vs selrect) each side uses before duplicating a fix.

The flex/grid + bounds geometry is still duplicated between CLJC (`app.common.geom.shapes.**`,
`geom/modifiers.cljc`) and Rust (`render-wasm/src/math.rs` `Bounds`, `src/shapes/modifiers/**`); keep
that in mind, but confirm a layer is actually broken before changing it. See `mem:render-wasm/core`.
