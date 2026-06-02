# BUG-05 — Silent no-op vs. bug ambiguity in direct sync

- **Category:** Diagnosability (no behavior change intended)
- **Confidence:** Low
- **Severity:** Low
- **Hot path:** Yes — direct sync (`generate-sync-shape-direct`)

## Onboarding (fresh context — read first)

- Repo root for local file tools (Read/Edit/Write): `/home/alotor/kaleidos/penpot/penpot`
- Serena / REPL project root (devenv container): `/home/penpot/penpot`.
- Read first: `docs/component-subsystem-handoff.md` (§5.2 direct sync, §11 sharp edge #7 cross-file ref chains) and Serena memory `critical-info`.
- Relevant memories: `mem:common/component-data-model`, `mem:common/changes-architecture`.
- Line numbers drift — re-locate symbols by name.

## Summary

When a copy's main can't be resolved — e.g. a remote library is unlinked or the ref
is lost — `generate-sync-shape-direct` returns `changes` unchanged (a silent no-op).
There is currently nothing that distinguishes an **intentional** no-op ("library is
legitimately unlinked") from a **bug** ("we lost the ref chain and silently stopped
syncing"). Field reports of "my copy stopped updating from main" are hard to
diagnose because the failure leaves no trace.

This is a small **diagnosability** improvement, not a behavior change: add a
debug/trace-level log on the not-found path so the condition is observable.

## Affected code

`common/src/app/common/logic/libraries.cljc` — `generate-sync-shape-direct`
(~line 796). Trace the path where the resolved main/ref-shape is `nil` and the
function returns `changes` without generating sync ops.

Resolution helpers involved (for context): `ctf/get-ref-shape` (normal sync) /
`ctf/find-ref-shape` (reset path) in `common/src/app/common/types/file.cljc`.

There is already a logging facility in this namespace (`shape-log`,
`container-log`, with `log-shape-ids` / `log-container-ids` toggles near the top of
the file) — reuse it rather than introducing a new mechanism.

## Proposed change

- On the "main/ref not found → no-op" branch, emit a `:debug` (or `:trace`) log via the
  existing `shape-log`, including: the shape id, container id, the component-id /
  component-file it tried to resolve, and whether the library is linked. Keep it at a
  level that is silent by default.
- Optionally add a coarse-grained signal that callers/telemetry could use to
  distinguish "skipped: library unlinked" from "skipped: ref not found", but do **not**
  change the returned changes or the sync outcome.

## Risks / things to watch

- Must be a pure observability change — identical change output, no perf regression
  (don't add expensive lookups just to log; reuse values already computed on that path).
- Respect the existing log-level gating so normal runs stay quiet.

## Validation

- Construct a copy whose remote library is unlinked and one with a genuinely lost ref;
  confirm both still no-op (unchanged) but now log distinctly at debug level.
- Confirm no change in comp-sync test output (logging is side-channel).

## Test guardrails

From `common/`:

```bash
clojure -M:dev:test --focus common-tests.logic.comp-sync-test
pnpm run test:quiet -- --focus common-tests.logic.comp-sync-test
```

## Acceptance criteria

- The not-found / unlinked direct-sync no-op is observable via the existing logging facility at a default-silent level.
- No change to sync behavior or change output; comp-sync suite passes unchanged.

## Fix summary

**File:** `common/src/app/common/logic/libraries.cljc`

Two `:debug`-level log calls were added via the existing `shape-log` macro — no new logging mechanism introduced.

### Change 1 — `generate-sync-shape-direct`: component not found (library unlinked / master deleted)

In the inner `else` branch of `(if component ...)` (previously a bare `changes` return), a `do` form now emits a `shape-log :debug` before returning `changes`. The log includes: `shape-id`, `component-id`, `component-file`, and `library-linked?` (derived from whether `library` is non-nil — a value already computed on this path). This distinguishes "library was unlinked" (`library-linked? false`) from "library present but component deleted" (`library-linked? true`).

### Change 2 — `generate-sync-shape-direct-recursive`: ref-shape not found

The `(if (nil? shape-main) changes ...)` guard (which already carried the comment "This should not occur, but protect against it in any case") was expanded into a `do` form that logs at `:debug` before returning `changes`. The log includes: `shape-id`, `component-id`, and `component-name`. This covers the case where the component is present but `get-ref-shape` / `find-ref-shape` failed to resolve the main shape.

Both changes are pure observability additions: identical change output, no new computations on the hot path, and gated by the existing `enabled-shape?` filter so normal runs remain silent.
