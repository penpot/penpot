# Decimals and Coordinates in Penpot

Penpot stores all geometry as JS numbers (doubles in CLJS, doubles in
CLJ for the JVM-side common code). Several Penpot-specific facts
about how this plays out are not obvious from reading the code.

## Sub-pixel drift is routine

Coordinate values that "should" be integers are routinely off by ~1e-5
in production data. A `:width` of 107 will frequently appear as
`107.00001275539398` after the value has passed through:

- the modifier propagation pipeline (`apply-wasm-modifiers` and the
  Rust WASM transform engine)
- any rotation/scale composition
- repeated translations

This drift is invisible in the UI (the renderer rounds at draw time)
but defeats exact equality comparisons in business logic. It does NOT
appear in JVM-only test setups because the WASM pipeline isn't
involved — tests that build shapes via `setup-shape` and `add-sample-shape`
get clean integer values. Bugs that depend on drift will pass tests
but fire in production unless tests explicitly inject drift.

## Use the close? helpers, not `=`

For comparing coordinate-like floats, the established convention is:

- `app.common.math/close?` — scalar tolerance comparison.
  Default precision 0.001 (sub-pixel; tight enough to keep distinct
  shapes distinct, loose enough to absorb arithmetic noise).
  Two-arity uses default precision; three-arity takes a custom one.
- `app.common.geom.point/close?` — element-wise close on `gpt/Point`
  records. Compares :x and :y via `mth/close?`.
- `app.common.geom.matrix/close?` — close on transform matrices.
- `app.common.geom.shapes/close-attrs?` — used inside `set-shape-attr`
  to decide whether a re-assigned `:width`/`:height` should be treated
  as a no-op (suppresses spurious touched marking from drift).

Treat `=` on `:x`, `:y`, `:width`, `:height`, `:selrect`, or `:points`
fields as a code smell when the inputs may have flowed through any
transform. The `set-shape-attr`-style precedent (already using
`close-attrs?`) is the right model.

## The redundancy multiplies failure modes

A shape's position lives in `:x/:y`, `:selrect`, AND `:points` (see
`mem:common/geometry-invariants` memory). Each is a separate set of float
values. After any operation that touches geometry, all three should
agree, but each is computed by a different path and accumulates
its own drift. Comparing `:selrect.width` from shape A to
`:selrect.width` from shape B is comparing two values that
"semantically" should be equal but were computed via different
operation chains — exact equality will often be false.
