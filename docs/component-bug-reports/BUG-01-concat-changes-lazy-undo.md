# BUG-01 — `concat-changes` accumulates `:undo-changes` as a nested lazy `concat`

- **Category:** Stability + Performance
- **Confidence:** High
- **Severity:** Medium (latent `StackOverflowError` on large files; wasted realization cost)
- **Hot path:** Yes — every library/component sync (`generate-sync-file` / `generate-sync-library`)

## Onboarding (fresh context — read first)

- Repo root for local file tools (Read/Edit/Write): `/home/alotor/kaleidos/penpot/penpot`
- Serena / REPL project root (devenv container): `/home/penpot/penpot`. Use this prefix for Serena symbol tools and the CLJS REPL; use the local prefix for Read/Edit/Write.
- Read first: `docs/component-subsystem-handoff.md` (subsystem map, esp. §3 "data model", §5.2 "direct sync", and sharp edge #11) and Serena memory `critical-info`.
- Relevant memories: `mem:common/changes-architecture`, `mem:common/component-data-model`.
- Line numbers drift — re-locate symbols by name.

## Summary

`pcb/concat-changes` merges two change-sets. The `:redo-changes` side was converted
to an eager vector concat (`d/concat-vec`), but the `:undo-changes` side still uses
lazy `clojure.core/concat`. Because `concat-changes` is called **once per container
in a loop** during a full sync, the resulting `:undo-changes` becomes a chain of N
nested lazy `concat` thunks. Realizing it (on undo, serialization, or `count`) costs
O(N) stack depth → real `StackOverflowError` risk on large files (files routinely
have hundreds–thousands of components, and a library sync visits all of them).

## Affected code

`common/src/app/common/files/changes_builder.cljc` — `concat-changes` (~line 127):

```clojure
(defn concat-changes
  [changes1 changes2]
  (-> changes1
      (update :redo-changes d/concat-vec (:redo-changes changes2))   ; eager (transient vector) ✓
      (update :undo-changes #(concat (:undo-changes changes2) %))))   ; lazy clojure.core/concat ✗
```

Callers that loop (both carry a `;;TODO Remove concat changes` marker):

- `common/src/app/common/logic/libraries.cljc` — `generate-sync-file` (~line 514): loops `ctf/object-containers-seq` (pages + deleted components), `concat-changes` per container.
- `common/src/app/common/logic/libraries.cljc` — `generate-sync-library` (~line 557): loops `ctkl/components-seq`, `concat-changes` per component.

Other (non-loop) callers — lower risk, but verify any fix doesn't regress them:
`logic/variants.cljc` (~line 89), `logic/libraries.cljc` (~2354, ~2384, ~2457),
`files/repair.cljc` (~706).

## Root cause

`(concat a b)` returns a lazy seq. Nesting it N times — `(concat u_N (concat u_{N-1} (… u_1)))` — means traversal crosses N lazy boundaries; deep nesting overflows the stack when realized, and re-realization is non-trivial. `d/concat-vec` (`common/src/app/common/data.cljc` ~243) avoids this by building a transient vector eagerly, which is why redo is already safe.

## Constraints / why this is still a TODO

`:undo-changes` is built **elsewhere** with `(update :undo-changes conj …)` which relies on **list/prepend** semantics (newest-undo-first). You therefore cannot naively switch the accumulator to a vector — later `conj` would append instead of prepend and silently reverse undo order. The required merge order is: **`changes2`'s undos must come before `changes1`'s undos** (so undoing reverses redo application order).

## Proposed fix directions (pick one, preserve order + semantics)

1. **Eager, order-preserving, keep it a seq.** Replace the lazy `concat` with an eager build that yields the same order and the same collection type the rest of the code expects. Verify with a quick check of what `:undo-changes` is at the call sites and whether anything `conj`s onto the result of `concat-changes` afterwards.
2. **Restructure the loops.** Have `generate-sync-file` / `generate-sync-library` collect per-container change-sets into a vector and concat once at the end (single eager merge), instead of folding `concat-changes` each iteration. This removes the nesting entirely and reads cleaner.

Whatever the choice: keep `:redo-changes` and `:undo-changes` ordering identical to current behavior (undo is the reverse of redo).

## Reproduction / validation

- Build a large synthetic file (many pages and/or many deleted components, each holding component copies that need sync) and run a full `generate-sync-file`; force realization of `:undo-changes` (e.g. `count` or apply-undo). Pre-fix this should be slow / stack-overflow at high N; post-fix it should be linear and safe.
- A focused unit test that concats many small change-sets and asserts the merged `:undo-changes` realizes without overflow and in the correct order.

## Test guardrails

From `common/`:

```bash
clojure -M:dev:test --focus common-tests.logic.comp-sync-test
pnpm run test:quiet -- --focus common-tests.logic.comp-sync-test
```

Also run the full common suite once before committing, since `concat-changes` is shared:

```bash
clojure -M:dev:test
```

## Acceptance criteria

- `concat-changes` no longer produces nested lazy seqs for `:undo-changes`.
- Undo/redo ordering is unchanged (verified by existing comp-sync tests).
- The two `;;TODO Remove concat changes` markers are resolved or the TODO removed.
- No regression across the full common test suite.

## Solution applied

**Approach chosen:** Fix direction 1 — eager, order-preserving, keep it a seq.

### Change in `concat-changes` (`changes_builder.cljc`)

Replaced the lazy `clojure.core/concat` with an eager `into` + `reverse`:

```clojure
;; before — lazy, N nested thunks after N loop iterations:
(update :undo-changes #(concat (:undo-changes changes2) %))

;; after — eager, O(1) stack depth per call:
(update :undo-changes into (reverse (:undo-changes changes2)))
```

**Why `into` + `reverse` preserves order:**

`:undo-changes` is a list. `conj` on a list prepends. `into coll items` reduces with `conj`, so each item in `items` is prepended in sequence — meaning the *last* item of `items` ends up first in the result. To get `changes2`'s undos before `changes1`'s undos (the required order), we reverse `changes2`'s undo list first so that `into` re-reverses it back into the correct order:

```
changes1-undo = (u1a u1b)          ; u1a is newest
changes2-undo = (u2a u2b)          ; u2a is newest
reverse(changes2-undo) = (u2b u2a)
into (u1a u1b) (u2b u2a)
  → conj u2b → (u2b u1a u1b)
  → conj u2a → (u2a u2b u1a u1b)  ; ✓ changes2 undos first, order preserved
```

Because `generate-sync-container` always produces a fresh `(pcb/empty-changes nil)` result (never a nested lazy seq), `(:undo-changes changes2)` is always a concrete list — `reverse` on it is O(M) where M is the number of changes in that single container. The total work across the loop is O(total changes), same as before but now stack depth is O(1) per call instead of O(N).

The collection type remains a list, so all existing `conj`-based callers that rely on prepend semantics continue to work unchanged.

### Cleanup in `libraries.cljc`

Removed the two `;;TODO Remove concat changes` markers from `generate-sync-file` and `generate-sync-library` — the root cause is fixed in `concat-changes` itself, so the TODO no longer applies.

### Test added (`files_changes_test.cljc`)

`concat-changes-is-eager-and-order-preserving`: folds 500 single-op change-sets via `reduce pcb/concat-changes` (mirroring the sync loop pattern) and asserts:
- `:redo-changes` indices are 0…499 (insertion order)
- `:undo-changes` indices are 499…0 (reverse, so undoing reverses redo order)
- No `StackOverflowError` is thrown at N=500
