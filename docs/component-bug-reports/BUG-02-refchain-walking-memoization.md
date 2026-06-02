# BUG-02 — Un-memoized ref-chain walking during variant switch

- **Category:** Performance
- **Confidence:** Medium (profile before committing to an optimization)
- **Severity:** Low–Medium
- **Hot path:** Yes — variant switch / `keep-touched?` swap (`clv/generate-keep-touched`)

## Onboarding (fresh context — read first)

- Repo root for local file tools (Read/Edit/Write): `/home/alotor/kaleidos/penpot/penpot`
- Serena / REPL project root (devenv container): `/home/penpot/penpot`.
- Read first: `docs/component-subsystem-handoff.md` (§5.5 swap/variant switch, §6 `update-attrs-on-switch`, §8 variants, sharp edges #6) and Serena memory `critical-info`.
- Relevant memories: `mem:common/component-swap-pipeline`, `mem:common/component-data-model`.
- Line numbers drift — re-locate symbols by name.

## Summary

`generate-keep-touched` (the variant-switch override-preservation step) walks the
`:shape-ref` chain repeatedly, once per touched child, with no caching. Each walk
re-resolves the component + component-file and re-derives parent sets. For variants
with many children and/or deep nesting this is roughly O(N · D · cost(find-ref-shape))
with repeated identical component lookups.

This is a **profile-first** item: confirm it's a measurable cost on a realistic large
variant before optimizing, since the walkers are correctness-sensitive.

## Affected code

`common/src/app/common/logic/variants.cljc`:

- `generate-keep-touched` (~line 146): for each touched original child it calls
  - `add-touched-from-ref-chain` (~line 139) → `ctf/get-touched-from-ref-chain-until-target-ref` (walks the whole chain), and
  - `find-shape-ref-child-of` (~line 122) → **recursive** `ctf/find-ref-shape`.

`common/src/app/common/types/file.cljc`:

- `find-ref-shape` (~line 397): for each call walks `ctn/get-parent-heads` and, per head, `find-component-file` + `ctkl/get-component` + `get-ref-shape`.
- `get-ref-chain-until-target-ref` (~line 1228): loops `find-ref-shape`.
- `find-remote-shape` (~line 457): recursive; re-derives `get-component-shape` / `get-in libraries` / `get-component` / `get-ref-shape` at each level.

## Proposed fix directions (after profiling)

- **Scoped memo** within a single `generate-keep-touched` call: memoize component/file
  resolution keyed on `(:component-file head, :component-id head)`, and/or memoize
  `find-ref-shape` per `(container-id, shape-id)`. Scope the memo to the call so stale
  entries can't leak across switches (cf. how `validate.cljc` scopes
  `*ref-shape-cache*` per page — a good precedent for a safe, page/call-local cache).
- **Precompute** the ref map for the involved subtrees once, before the reduce over
  `orig-touched`, instead of resolving per child.

## Risks / things to watch

- Ref resolution can cross files (remote libraries) and involves swap slots / fostered children; a cache key must capture everything that affects resolution. Get this wrong and you return the wrong ref shape → corrupted overrides on switch.
- Prefer a call-local (dynamically-bound or passed) cache over any global memoization to avoid staleness across edits.
- This is lower priority than BUG-06/BUG-01/BUG-07; only pursue if profiling shows it matters.

## Validation

- Profile `generate-keep-touched` on a large variant (many children, nested instances) before/after — e.g. via `tho/swap-component-in-shape {:keep-touched? true}` in a common test, or live with the `update-attrs-on-switch` tracing recipe in `mem:common/component-debugging-recipes`.
- Assert switch output is byte-identical before/after the memo.

## Test guardrails

From `common/`:

```bash
clojure -M:dev:test --focus common-tests.logic.variants-switch-test
pnpm run test:quiet -- --focus common-tests.logic.variants-switch-test
```

`common/test/common_tests/logic/variants_switch_test.cljc` is the canonical swap+touched suite. Run the full common suite before committing.

## Acceptance criteria

- Profiling demonstrates a real reduction in redundant ref-chain resolution on a large variant.
- Variant-switch output (changes + resulting touched/overrides) is identical to pre-change behavior.
- Any cache is call/page-scoped, never global.

## Resolution

**Status:** Fixed — `perf: call-scoped memoization of find-ref-shape in generate-keep-touched (BUG-02)`

### Approach

Added a call-local dynamic var `*find-ref-shape-cache*` (a `volatile!` map, nil by default) to
`app.common.types.file/find-ref-shape`. When bound, every call is memoized by
`[shape-id include-deleted? with-context?]`, using `contains?` for cache-miss detection (avoids
the CLJS keyword interning issue documented in `validate.cljc`).

`generate-keep-touched` in `app.common.logic.variants` wraps its body with:

```clojure
(binding [ctf/*find-ref-shape-cache* (volatile! {})] ...)
```

This activates the cache for the entire switch call — including the inner loops of
`get-ref-chain-until-target-ref` (called via `add-touched-from-ref-chain`) and the recursive
`find-shape-ref-child-of` — reducing redundant ref-chain resolution from O(N·D·cost) to O(1)
after the first resolution per shape.

The cache is strictly call-scoped: a fresh `volatile!` is created at the start of each
`generate-keep-touched` invocation, so stale entries can never leak across variant switches.

### Files changed

- `common/src/app/common/types/file.cljc`: added `*find-ref-shape-cache*` dynamic var; modified
  `find-ref-shape` to check and populate it when bound.
- `common/src/app/common/logic/variants.cljc`: bind the cache in `generate-keep-touched`.

### Validation

Full common test suite: **1055 tests, 24374 assertions, 0 failures.**
Focused variant-switch suite: **47 tests, 292 assertions, 0 failures.**
