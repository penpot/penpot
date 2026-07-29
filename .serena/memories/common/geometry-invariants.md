# Geometry Invariants in Penpot Shapes

Core invariant: shape position is stored redundantly, and all geometry fields must stay coherent.

## Redundant fields

For a shape at `(x, y)` with width `w` and height `h`:

- `:x`, `:y`, `:width`, `:height`: top-left and dimensions.
- `:selrect`: `{:x :y :width :height :x1 :y1 :x2 :y2}`, where `x2 = x + w` and `y2 = y + h`.
- `:points`: four corners for an axis-aligned rect, clockwise from top-left.
- `:transform` and `:transform-inverse`: identity for axis-aligned shapes; populated for transformed shapes.

After a geometric mutation, equivalent fields such as `:y`, `(:y :selrect)`, and the first point's `:y` should agree. The renderer and hit-testing read `:selrect` / `:points`, so a shape can render or select incorrectly even when `:x` / `:y` look right.

## Helpers that preserve the invariant

- `gsh/move`: translates by delta and updates geometry consistently.
- `gsh/absolute-move`: moves to an absolute position by computing a delta from the current selrect.
- `gsh/transform-shape`: applies a full transform.
- `cts/setup-shape`: initializes geometry for new shapes; variant test helpers such as `thv/add-variant-with-child` use it.

## Edits that break the invariant

- `(assoc shape :x ...)` or `(assoc shape :y ...)`: updates only one field and leaves `:selrect` / `:points` stale.
- `ths/update-shape file label :y val`: goes through `set-shape-attr`, but does not repair all position fields for `:y` alone.
- Direct `update-in` edits to `:selrect`, `:points`, or dimensions.

## Test setup warning

When positioning test shapes, use `gsh/absolute-move`, `gsh/move`, or production change helpers. Do not set only `:x` / `:y`.

```clojure
(cls/generate-update-shapes
  (pcb/empty-changes nil page-id)
  #{(:id child)}
  #(gsh/absolute-move % (gpt/point (:x %) 101))
  (:objects page)
  {})
```

Using `(ths/update-shape file label :y 101)` leaves `:selrect.y` stale. Downstream code that reads `:selrect` can then fail in ways that look like product bugs but are only invalid test setup.

## :touched and geometry mutation

When a copy shape changes geometry through the proper pipeline (`set-shape-attr` via `process-operation :set`), `:touched` gains `:geometry-group` unless ignored. Tests can either drive the production update with `cls/generate-update-shapes`, or inject `(assoc shape :touched #{:geometry-group})` when only touched state matters.

If a test needs both a new position and touched state, move the shape first with geometry-preserving helpers, then inject or assert touched state.