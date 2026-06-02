# Component / Variant subsystem — bug reports

Each report below is **self-contained** and meant to be tackled independently in a
fresh agent context. They came out of the audit captured in
[`../component-subsystem-handoff.md`](../component-subsystem-handoff.md) (§14).

Read the handoff doc and the Serena memory `critical-info` before starting any of
them.

| # | Title | Category | Confidence | Severity | On hot path? |
|---|---|---|---|---|---|
| [BUG-01](./BUG-01-concat-changes-lazy-undo.md) | `concat-changes` accumulates `:undo-changes` as nested lazy `concat` | Stability + Perf | High | Medium (latent crash on large files) | Yes (library sync) |
| [BUG-02](./BUG-02-refchain-walking-memoization.md) | Un-memoized ref-chain walking during variant switch | Perf | Medium | Low–Medium | Yes (variant switch) |
| [BUG-03](./BUG-03-compare-children-quadratic.md) | `compare-children` fallback is O(n²) with expensive constant | Perf | Medium | Low–Medium | Yes (sync) |
| [BUG-04](./BUG-04-update-attrs-on-switch-geometry-guards.md) | `update-attrs-on-switch` composite-geometry guard audit | Correctness | Low (audit) | Variable | Yes (swap) |
| [BUG-05](./BUG-05-direct-sync-silent-noop-diagnostics.md) | Silent no-op vs. bug ambiguity in direct sync | Diagnosability | Low | Low | Yes (sync) |
| [BUG-06](./BUG-06-index-of-quadratic-sync-callbacks.md) | `d/index-of` linear scan inside per-child sync callbacks | Perf | High | Medium | Yes (sync) |
| [BUG-07](./BUG-07-inverse-sync-remap-changes.md) | Inverse sync re-maps the entire change list at every tree node | Perf + Stability | High | Medium | Yes ("Update main") |
| [BUG-08](./BUG-08-variant-switch-crash-empty-target.md) | `variant-switch` crashes on empty / out-of-range target (plugin-facing) | Correctness | High | **High (reproducible crash)** | Yes (Plugin API) |

## Suggested order

1. **BUG-08** — reproducible plugin-triggered crash, small guard fix, clear test. Most aligned with the current `alotor-fix-plugins-problems` branch.
2. **BUG-06** — pure perf, mechanical, no behavior change.
3. **BUG-01 / BUG-07** — the lazy-`concat` stability pair (real crash risk on large files; needs care to preserve undo order).
4. **BUG-02 / BUG-03** — profile first, then optimize.
5. **BUG-04 / BUG-05** — audit / diagnosability, lower urgency.
