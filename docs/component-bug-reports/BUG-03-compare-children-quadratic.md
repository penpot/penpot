# BUG-03 — `compare-children` fallback is O(n²) with an expensive constant

- **Category:** Performance
- **Confidence:** Medium (profile before committing)
- **Severity:** Low–Medium
- **Hot path:** Yes — both sync directions, for any instance whose children are reordered/inserted/removed

## Onboarding (fresh context — read first)

- Repo root for local file tools (Read/Edit/Write): `/home/alotor/kaleidos/penpot/penpot`
- Serena / REPL project root (devenv container): `/home/penpot/penpot`.
- Read first: `docs/component-subsystem-handoff.md` (§5.2 direct sync, sharp edge #8 swap slots) and Serena memory `critical-info`.
- Relevant memories: `mem:common/component-data-model`, `mem:common/component-swap-pipeline`.
- Line numbers drift — re-locate symbols by name.

## Summary

`compare-children` matches an instance's children against the main's children to
decide add/remove/move/sync. The aligned case is a clean O(n) zipper, but any
misalignment (reorder/insert/remove) drops into a `d/seek` fallback that linearly
scans **both** child lists *and* calls `ctf/match-swap-slot?` (a cross-file
ref-chain lookup) inside each predicate, then rebuilds a list with `(remove …)`. Net
O(n²) comparisons, each potentially doing file-crossing work; `match-swap-slot?` is
also recomputed for the same pair multiple times.

This is a **profile-first** item — the matching algorithm is correctness-critical, so
only optimize if profiling shows it's a real cost.

## Affected code

`common/src/app/common/logic/libraries.cljc` — `compare-children` (~line 1182).

Observations:
- First branch tests `ctk/is-main-of?` and `ctf/match-swap-slot?` on the head pair.
- On mismatch it does two `d/seek`s over `children-inst` and `children-main`, each
  predicate re-invoking `match-swap-slot?`.
- Then `(remove #(= (:id %) …) children-inst|main)` rebuilds the list each step → the
  structural O(n²).

`ctf/match-swap-slot?` lives in `common/src/app/common/types/file.cljc` (cross-file
ref-chain / swap-slot resolution — relatively expensive).

## Proposed fix directions (after profiling)

- **Precompute matching indexes** for the level: maps from `is-main-of?` key and from
  swap-slot to the candidate inst/main child, so each match is a hash lookup instead
  of a `d/seek` + linear `remove`. Track "consumed" children with a set of ids rather
  than rebuilding the list with `remove`.
- **Memoize `match-swap-slot?`** per `(child-main-id, child-inst-id)` within the call
  to eliminate the repeated recomputation in the head test + both seeks.

## Risks / things to watch

- This is the core child-matching algorithm for sync in **both** directions (it's
  passed `inverse?` and `reset?` and a set of callbacks). A subtle change in match
  order or in which child is "consumed" changes add/remove/move output. Treat as
  higher-risk; keep the match semantics identical.
- Swap slots are what keep swapped sub-instances syncing correctly (sharp edge #8) —
  `match-swap-slot?` is guarded by `(not reset?)` in the current code; preserve that.
- Only pursue after BUG-06 (the cheap, safe `index-of` win in the same file).

## Validation

- Profile a sync where a large instance has many children reordered/inserted.
- Assert byte-identical change output before/after across the full comp-sync and variants-switch suites.

## Test guardrails

From `common/`:

```bash
clojure -M:dev:test --focus common-tests.logic.comp-sync-test
clojure -M:dev:test --focus common-tests.logic.variants-switch-test
clojure -M:dev:test --focus common-tests.logic.copying-and-duplicating-test
```

Run the full common suite before committing — this function underpins a lot.

## Acceptance criteria

- Profiling shows reduced cost on a reordered large instance.
- Add/remove/move/sync output is identical to pre-change behavior across the sync suites.
- `match-swap-slot?` is not recomputed for the same pair within a single match step.

## Fix applied

**Commit:** on branch `alotor-component-polishing`
**File:** `common/src/app/common/logic/libraries.cljc` — `compare-children`

Three changes, all confined to `compare-children`:

1. **Precomputed slot maps** — `inst-slots` and `main-slots` are built once before the
   loop by calling `ctf/find-swap-slot` for each child (O(n) total). When `reset?` is
   true the maps stay `nil`, preserving the existing swap-slot guard.

2. **Local `match-pair?` predicate** — replaces every `ctk/is-main-of?` +
   `ctf/match-swap-slot?` pair in the `d/seek` predicates with a direct map lookup,
   eliminating repeated cross-file ref-chain traversals for the same pair.

3. **Consumed-set instead of `(remove …)`** — two sets `consumed-inst` /
   `consumed-main` track out-of-order matched IDs. A `drop-while` at the head of each
   loop iteration skips consumed items; seeks exclude them via `(not (consumed-… (:id %)))`;
   the nil-case reductions filter them with `remove`. This replaces the O(n) list rebuild
   that occurred on every misaligned step.

All three comp-sync / variants-switch / copying-and-duplicating test suites pass
(68 + 292 + 7 assertions, 0 failures).
