# BUG-06 — `d/index-of` linear scan inside per-child sync callbacks

- **Category:** Performance
- **Confidence:** High
- **Severity:** Medium
- **Hot path:** Yes — both directions of component sync, per child of every synced instance

## Onboarding (fresh context — read first)

- Repo root for local file tools (Read/Edit/Write): `/home/alotor/kaleidos/penpot/penpot`
- Serena / REPL project root (devenv container): `/home/penpot/penpot`.
- Read first: `docs/component-subsystem-handoff.md` (§5.2 direct sync, §5.3 inverse sync, §6 attribute-sync) and Serena memory `critical-info`.
- Relevant memories: `mem:common/component-data-model`, `mem:common/changes-architecture`.
- Line numbers drift — re-locate symbols by name.

## Summary

The direct and inverse recursive sync functions compute child indices with
`d/index-of`, which is a **linear scan**, inside callbacks that `compare-children`
invokes **once per child**. For an instance level with n children that undergoes m
additions/moves, this is O(m·n) → O(n²) on a full reorder, with children of large
boards/instances being the realistic hotspot. The fix is a pure index-lookup
precompute — no behavior change.

## Affected code

`common/src/app/common/logic/libraries.cljc`:

- `generate-sync-shape-direct-recursive` (~line 854):
  - `only-main` callback → `(d/index-of children-main child-main)` (~line 924)
  - `moved` callback → `(d/index-of children-inst child-inst)` + `(d/index-of children-main child-main)` (~lines 968–969)
- `generate-sync-shape-inverse-recursive` (~line 1045):
  - `only-inst` callback → `(d/index-of children-inst child-inst)` (~line 1106)
  - `moved` callback → `(d/index-of children-main …)` + `(d/index-of children-inst …)` (~lines 1145–1146)

`d/index-of` (`common/src/app/common/data.cljc` ~line 303) delegates to
`index-of-pred`, a linear scan.

Both functions already bind `children-inst` / `children-main` as vectors at the top
of their `let`, so the index source is stable for the duration of the callbacks.

## Proposed fix

Precompute an `{id → index}` map once per level, next to where `children-inst` /
`children-main` are bound, and look up by `:id` in the callbacks:

```clojure
children-inst       (vec (ctn/get-direct-children container shape-inst))
children-main       (vec (ctn/get-direct-children component-container shape-main))
children-inst-index (into {} (map-indexed (fn [i s] [(:id s) i])) children-inst)
children-main-index (into {} (map-indexed (fn [i s] [(:id s) i])) children-main)
```

then `(d/index-of children-main child-main)` → `(get children-main-index (:id child-main))`, etc. Apply the same in the inverse function.

## Risks / things to watch

- Very low risk: this is a value-preserving substitution (`index-of` returns the same int the map lookup returns), with no change to ordering or change generation.
- Confirm there are no duplicate shape ids within a single children vector (there should not be — they are sibling shapes); a map is correct under the invariant that ids are unique per level.
- Keep the precompute inside the `let` of each recursive call so it reflects the children of that specific level.

## Test guardrails

From `common/`:

```bash
clojure -M:dev:test --focus common-tests.logic.comp-sync-test
pnpm run test:quiet -- --focus common-tests.logic.comp-sync-test
```

Exercise reorder/add/remove cases specifically (the `moved` / `only-main` / `only-inst` branches). Run the full common suite before committing.

## Acceptance criteria

- No `d/index-of` calls remain inside the per-child sync callbacks.
- comp-sync tests pass unchanged (identical change output).
- Reordering many children of a synced instance is no longer quadratic.

## Fix

Applied in `common/src/app/common/logic/libraries.cljc`.

### `generate-sync-shape-direct-recursive`

Added two index maps immediately after the `children-inst` / `children-main` bindings:

```clojure
children-inst-index (into {} (map-indexed (fn [i s] [(:id s) i])) children-inst)
children-main-index (into {} (map-indexed (fn [i s] [(:id s) i])) children-main)
```

Replaced `d/index-of` calls:
- `only-main` callback: `(d/index-of children-main child-main)` → `(get children-main-index (:id child-main))`
- `moved` callback: both `(d/index-of children-inst child-inst)` and `(d/index-of children-main child-main)` replaced with their map equivalents.

### `generate-sync-shape-inverse-recursive`

Same pattern applied:
- Added `children-inst-index` and `children-main-index` after the `children-inst` / `children-main` bindings.
- `only-inst` callback: `(d/index-of children-inst child-inst)` → `(get children-inst-index (:id child-inst))`
- `moved` callback: both index-of calls replaced with map lookups.

### Test added

`test-inverse-sync-when-moving-shape` in `common/test/common_tests/logic/comp_sync_test.cljc` covers the inverse-sync `moved` branch (previously untested). It reorders the main component's children, runs inverse sync on the copy, and asserts the main is restored to match the instance's order.
