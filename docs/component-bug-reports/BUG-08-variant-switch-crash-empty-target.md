# BUG-08 — `variant-switch` crashes on empty / out-of-range target (plugin-facing)

- **Category:** Correctness
- **Confidence:** High
- **Severity:** High — reproducible silent workspace crash, reachable from the public Plugin API
- **Hot path:** Yes — variant switch UI and Plugin API `switchVariant`

## Onboarding (fresh context — read first)

- Repo root for local file tools (Read/Edit/Write): `/home/alotor/kaleidos/penpot/penpot`
- Serena / REPL project root (devenv container): `/home/penpot/penpot`.
- Read first: `docs/component-subsystem-handoff.md` (§5.5 component swap / variant switch, §8 variants) and Serena memory `critical-info` (note the "silent crash" failure mode: `execute_code` returns OK but the workspace dies 1–2s later).
- Relevant memories: `mem:common/component-swap-pipeline`, `mem:frontend/plugin-api-to-cljs-binding`, `mem:frontend/handling-crashes`.
- Line numbers drift — re-locate symbols by name.

## Summary

`variant-switch` selects the nearest target variant with
`(apply min-key f valid-comps)`. When `valid-comps` is **empty**, this reduces to
`(min-key f)`, which has no matching arity → **ArityException**. The exception is
thrown *before* the `(when nearest-comp …)` guard that was written precisely to
handle "no match → do nothing", so that guard is dead in exactly its intended case.

Two reachable triggers, both via the public Plugin API `switchVariant(pos, value)`,
which validates only the *types* of its arguments (not that they reference an
existing property/value):

1. A `value` that no sibling variant has at `pos` → `valid-comps` empty → ArityException.
2. A `pos` ≥ number of variant properties → `(update pos assoc :value val)` does
   `(assoc vec out-of-range …)` → `IndexOutOfBoundsException` (and feeds
   `ctv/distance` a malformed property vector).

Both surface as a **silent workspace crash** from the plugin's perspective.

## Affected code

`frontend/src/app/main/data/workspace/variants.cljs` — `variant-switch` (~line 699):

```clojure
target-props (-> (:variant-properties component)
                 (update pos assoc :value val))           ; (2) out-of-range pos throws here
valid-comps  (->> variant-comps
                  (remove #(= (:id %) component-id))
                  (filter #(= (dm/get-in % [:variant-properties pos :value]) val))
                  (reverse))
nearest-comp (apply min-key #(ctv/distance target-props (:variant-properties %)) valid-comps)  ; (1) empty → ArityException
;; ...
(when nearest-comp ...)   ; ← intended "no match → do nothing" guard, never reached in the empty case
```

Plugin binding (no value/range validation):
`frontend/src/app/plugins/shape.cljs` — `:switchVariant` (~line 1397):

```clojure
:switchVariant
(fn [pos value]
  (cond
    (not (nat-int? pos)) (u/not-valid plugin-id :pos pos)
    (not (string? value)) (u/not-valid plugin-id :value value)
    :else
    (let [shape     (u/locate-shape file-id page-id id)
          component (u/locate-library-component file-id (:component-id shape))]
      (when (and component (ctk/is-variant? component))
        (st/emit! (-> (dwv/variants-switch {:shapes [shape] :pos pos :val value}) ...))))))
```

`variants-switch` (~line 741) fans out to `variant-switch` per shape.

## Established convention (use it for the fix)

`common/src/app/common/logic/variant_properties.cljc` — `generate-remove-property`
(~line 36) already range-checks before using `pos`:

```clojure
(if (and (seq props) (<= 0 pos) (< pos (count props)))
  ...
  changes)   ; out-of-range pos → safe no-op
```

`variant-switch` is the one place in the variant code that uses `pos` and an
empty-collection reduction **without** such guards. Match the existing convention.

## Proposed fix

1. **Guard the empty reduction** in `variant-switch` so it cannot throw and the
   existing `(when nearest-comp …)` becomes live:

   ```clojure
   nearest-comp (when (seq valid-comps)
                  (apply min-key #(ctv/distance target-props (:variant-properties %)) valid-comps))
   ```

2. **Range-check `pos`** before `(update pos assoc :value val)` — either bail early in
   `variant-switch` when `pos` is out of range for `(:variant-properties component)`,
   and/or reject it in the `:switchVariant` binding with `u/not-valid` (mirror the
   `nat-int?` check). Prefer validating in the binding for a clean plugin-facing error,
   plus a defensive guard in `variant-switch` since the workspace UI also calls it.

3. Make sure `nearest-comp-children` / `comps-nesting-loop?` (computed from
   `nearest-comp` just below) tolerate `nil` `nearest-comp` — moving them inside the
   `(when nearest-comp …)` is the cleanest option.

## Reproduction

Plugin (or REPL emulating the plugin path) on a variant copy head:

- `switchVariant(pos, "value-that-no-variant-has")` → ArityException → workspace Internal Error ~1–2s later.
- `switchVariant(99, "anything")` on a variant with < 100 properties → IndexOutOfBounds.

Detect the crash (per `mem:frontend/handling-crashes`) from `cljs_repl`:
`(some? (:exception @app.main.store/state))`.

## Test guardrails

Frontend unit test mirroring the Plugin API path. From `frontend/`:

```bash
pnpm run test:quiet -- --focus frontend-tests.logic.components-and-tokens
```

Add cases:
- switch to a property value with no matching sibling variant → no-op, no throw.
- switch with an out-of-range `pos` → no-op (and/or `u/not-valid` from the binding), no throw.
- existing valid switches still pick the same nearest variant (no regression).

New frontend test namespaces must be registered in `frontend_tests/runner.cljs`
(not needed if adding vars to an existing namespace).

## Acceptance criteria

- `switchVariant` with a non-existent value or out-of-range `pos` is a safe no-op (or a clean `u/not-valid`), never a crash.
- Valid switches behave exactly as before.
- A regression test covers both the empty-target and out-of-range-`pos` cases.

## Fix summary

**`frontend/src/app/main/data/workspace/variants.cljs` — `variant-switch`:**

- Extracted `props` from `(:variant-properties component)` into the outer `let`.
- Merged the existing `(not= val ...)` guard with a pos range check `(seq props) (<= 0 pos) (< pos (count props))`, mirroring the `generate-remove-property` convention. This prevents `IndexOutOfBoundsException` before `(update pos assoc :value val)` is ever reached.
- Guarded the `(apply min-key ...)` reduction with `(when (seq valid-comps) ...)`, so an empty candidate list yields `nil` instead of an ArityException.
- Moved `nearest-comp-children` and `comps-nesting-loop?` inside the `(when nearest-comp ...)` block, making the intended no-op guard live in the empty-collection case.

**`frontend/src/app/plugins/shape.cljs` — `:switchVariant`:**

- Added a pos range check in the `:else` branch after fetching the component: if `(>= pos (count (:variant-properties component)))`, calls `u/not-valid` immediately, giving the plugin caller a clean error before anything reaches `variant-switch`.

**`frontend/test/frontend_tests/logic/variant_switch_test.cljs`** (new file):

- Three tests: nonexistent value → no-op, out-of-range `pos` → no-op, valid switch → copy swaps to target component.
- Registered in `frontend/test/frontend_tests/runner.cljs`.
- All 284 frontend tests pass with 0 failures.
