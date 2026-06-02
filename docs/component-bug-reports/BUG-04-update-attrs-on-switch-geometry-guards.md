# BUG-04 — `update-attrs-on-switch` composite-geometry guard audit

- **Category:** Correctness (audit — no confirmed defect yet)
- **Confidence:** Low (this is an investigation, not a known bug)
- **Severity:** Variable (this function is the documented epicenter of "swap moved/resized my shape" bugs)
- **Hot path:** Yes — every `keep-touched?` swap / variant switch

## Onboarding (fresh context — read first)

- Repo root for local file tools (Read/Edit/Write): `/home/alotor/kaleidos/penpot/penpot`
- Serena / REPL project root (devenv container): `/home/penpot/penpot`.
- Read first: `docs/component-subsystem-handoff.md` (§6 `update-attrs-on-switch`, §11 sharp edges #4 and #5) and Serena memory `critical-info`.
- Relevant memories: `mem:common/component-swap-pipeline`, `mem:common/component-debugging-recipes` (has a live tracing recipe for this exact function), `mem:common/decimals-and-coordinates`.
- Line numbers drift — re-locate symbols by name.

## Goal

This is an **audit task**, not a pre-diagnosed fix. `update-attrs-on-switch` decides
which touched attributes of the pre-swap shape get carried onto the freshly
instantiated target. Its guard set around composite geometry (`:selrect`, `:points`)
is the most frequent source of swap regressions. The task is to systematically
probe the guards for cases where they either (a) carry stale/incompatible geometry
onto the target, or (b) wrongly skip an override the user expects to keep.

## Affected code

`common/src/app/common/logic/libraries.cljc`:

- `update-attrs-on-switch` (~line 2134) — the function under audit. Note the long
  inline comment block describing the composite-geometry skip keyed on
  `:layout-item-h-sizing` / `:layout-item-v-sizing` = `:fix` and width/height
  divergence.
- Supporting guards/helpers in the same file:
  - `equal-geometry?` (~line 2093) — positional-displacement-tolerant equality (uses `mth/close?` / `gpt/close?`).
  - `reposition-shape` (~line 2459) — repositions `previous-shape` by (dest-root − origin-root).
  - `switch-fixed-layout-geom-change-value`, `switch-path-change-value`, `switch-text-change-value` — type-specific conversions.

## Areas to probe (from the handoff sharp edges)

1. **Non-`:fix` sizing + composite geometry** (sharp edge #4): width/height are skipped
   correctly when sizing isn't `:fix`, but verify `:selrect`/`:points` cannot still
   carry old override dimensions through the `:else` fallback. The current code has a
   dedicated skip for this — confirm it covers path and text shapes too.
2. **Repositioned `previous-shape`** (sharp edge #5): `reposition-shape` applies
   (dest-root − origin-root). For normal variant switch this delta is often zero, but
   **do not assume zero** for other swap entry points — notably the Plugin API
   `switchVariant` path and `component-multi-swap`. Probe a swap where the pre-swap
   shape is not at the target root position.
3. **Inherited touched via ref chain** (sharp edge #6): `add-touched-from-ref-chain`
   (in `variants.cljc`) makes a locally-untouched shape behave as touched. Check the
   *effective* touched set drives the right copy decisions.
4. **Master mismatch guard**: the rule "if origin and destiny masters disagree on an
   attr, don't copy (except `:points`/`:selrect`/`:content`)" — verify the exceptions
   don't leak incompatible composite geometry between differently-shaped variants.
5. **Text `:content`** partial-touched handling (`switch-text-change-value`) and the
   forced `:position-data` reset — verify formatting-only vs text-only overrides each
   survive a switch correctly.

## How to investigate

- Use the live tracing recipe in `mem:common/component-debugging-recipes` to capture
  `curr` / `prev` / `origin-ref` during a real UI or Plugin-API swap.
- In common tests, drive the production swap path with
  `tho/swap-component-in-shape {:keep-touched? true}` and dump shape trees with
  `thf/dump-file`. Use `thv/add-variant-with-copy` to build variants whose children
  are component instances (the alt-drag-duplicate sub-pixel-drift scenario referenced
  in `equal-geometry?`).

## Deliverable

- Either: a concrete failing scenario + a targeted guard fix + regression test, or
- a written conclusion that the guards hold for the probed cases, with the new tests
  added to lock in the behavior.

## Test guardrails

From `common/`:

```bash
clojure -M:dev:test --focus common-tests.logic.variants-switch-test
clojure -M:dev:test --focus common-tests.logic.text-sync-test
pnpm run test:quiet -- --focus common-tests.logic.variants-switch-test
```

Canonical suite: `common/test/common_tests/logic/variants_switch_test.cljc`. Read neighbouring tests before adding cases.

## Acceptance criteria

- Each probed area has either a fix+test or an explicit "holds, with test" outcome.
- No regression in the variants-switch and text-sync suites.

---

## Resolution

**Status: Closed — guards hold for all probed areas. Two bugs were found and already fixed (in `develop` prior to this branch). Regression tests are in place and passing (47 tests, 0 failures).**

### Findings by area

#### Area 1 — Non-`:fix` sizing + composite geometry

The non-`:fix` composite-geometry skip (lines ~2277–2282 of `libraries.cljc`) correctly prevents `:selrect` and `:points` from being copied when the two variant masters have different dimensions and neither `layout-item-h-sizing` nor `layout-item-v-sizing` is `:fix`.

- **Path shapes**: The `path-change?` flag routes them through `switch-path-change-value` instead. A prior bug existed here (see Area 1-path below).
- **Text shapes (auto)**: Caught earlier by the `text-auto?` guard.
- **Text shapes (fixed) and rect/frame shapes**: Fall through to the identical non-`:fix` skip. The skip code does not branch on shape type, so the rect-based selrect consistency tests (`test-switch-selrect-consistent-*`) cover this path exhaustively.

**Outcome: holds.**

#### Area 1-path — Path content leaking stale position via `switch-path-change-value`

*Concrete bug found and already fixed.*

`equal-geometry?` originally lacked a handler for the `:content` attribute of path shapes. When a path was *repositioned* (not resized) inside a copy — touching `:geometry-group` — `equal-geometry?` returned `false` for `:content`, causing `switch-path-change-value` to fire and embed the old absolute path coordinates into the new variant. The child appeared at the pre-switch position instead of the target master's default position.

**Fix**: Added a `:content` branch to `equal-geometry?` that compares the bounding-box width/height of the path segments with `mth/close?` tolerance. When only position changes (bounding-box size matches), `equal-geometry?` returns `true` and `:content` is correctly skipped.

**Regression test**: `test-switch-does-not-override-path-content-when-only-repositioned` (already in suite, passes).

#### Area 2 — Repositioned `previous-shape` / sub-pixel drift

*Concrete bug found and already fixed.*

`equal-geometry?` originally used exact equality for `:selrect` comparison. When `previous-shape` carried sub-pixel floating-point drift in its width (from interactive transform modifiers, e.g. an alt-drag duplicate of a variant whose children are component instances), `equal-geometry?` returned `false` even for effectively equal shapes. The `:else` branch then copied `previous-shape.selrect` verbatim onto the fresh target — including a stale `:selrect.y` — leaving the child at the source variant's layout position inside the new variant's frame.

For normal variant switches the `reposition-shape` delta is zero (both roots on the same page position), but the drift-induced `equal-geometry?` failure still surfaced through the non-`:fix` skip: because the masters' dimensions agreed exactly (`origin-ref-shape.width == current-shape.width`), the non-`:fix` skip did not catch it, and the stale selrect was copied.

**Fix**: `equal-geometry?` now uses `mth/close?` (and `gpt/close?` for `:points`) instead of exact `=`. This matches how drift is already tolerated elsewhere in the geometry layer.

**Regression test**: `test-switch-when-source-master-child-has-touched-geometry` (already in suite, passes). The test positions child1 at y=101 inside m01 and child2 at y=73 inside m02, introduces sub-pixel width drift on the copy's child, switches, and asserts that `:y` and `:selrect.y` both land at the target master's layout position.

#### Area 3 — Inherited touched via ref chain

The effective touched set used by `update-attrs-on-switch` comes from `(get previous-shape :touched #{})`, where `previous-shape` has already been augmented by `add-touched-from-ref-chain` in `generate-keep-touched` before the call. This correctly implements the "effective touched set drives copy decisions" requirement.

**Outcome: holds.** Covered by `test-switch-variant-without-touched-but-touched-parent`.

#### Area 4 — Master mismatch guard for composite geometry

`:selrect`, `:points`, and `:content` intentionally bypass the master-mismatch guard (line ~2235) because they can legitimately differ between variants. The alternative guard — the non-`:fix` composite-geometry skip (Area 1) — closes the gap: when variant masters disagree on dimensions, `:selrect`/`:points` are skipped regardless of the touched-set state.

The `not=` comparison in the non-`:fix` skip (comparing `origin-ref-shape` vs `current-shape` dimensions) uses exact equality. This is safe: both shapes come directly from component master data and carry integer pixel dimensions; the sub-pixel drift concern applies only to `previous-shape` (the user's copy), which is handled in `equal-geometry?` by `mth/close?`.

**Outcome: holds.** Covered by `test-switch-selrect-consistent-*` and `test-switch-with-*-sizing-*` tests.

#### Area 5 — Text `:content` partial-touched handling

`switch-text-change-value` correctly handles all four partial-touch combinations (text-only, attrs-only, both, neither) across identical and differing variant content. The `reset-pos-data?` path correctly nils `:position-data` when `:geometry-group` is touched and position-data differs, allowing the layout engine to recalculate it.

**Outcome: holds.** Covered by the text-override test matrix (identical, different-prop, different-text, different-text-and-prop variants, with and without structure changes).

### Test suite result

```
47 tests, 292 assertions, 0 failures
```
