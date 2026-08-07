# Composable component tests

A framework concept for systematically testing Penpot's component subsystem
(synchronisation/propagation, swaps, variant switches, nesting, overrides), implemented in TWO test
suites that share the principles below:

1. **ClojureScript suite** — in the frontend test tree (`frontend/test/frontend_tests/
   composable_tests/`), driving a minimally-assembled real app headlessly. The original.
2. **TypeScript suite** — a Penpot plugin (`plugins/apps/composable-test-suite/`), driving the FULL
   production app end-to-end through the Plugin API, with a slightly more elaborate set of
   abstractions. Runs interactively (panel), remotely (Playwright), and headlessly in CI. Its
   README is the authoritative operational reference.

## Shared core idea
A test is a **composition of operations** over a starting configuration, plus assertions. You
describe a test as data (a setup + a sequence of operations) rather than writing bespoke imperative
code, and coverage grows by COMPOSITION: a new variation is one combinator wrapped around existing
pieces, not a copied test. Choice points (one-of alternatives, optional steps) EXPAND the
composition into a full sweep of variants — one written case stands for a whole matrix of concrete
tests.

## Shared principles
- **Every producing object is the accessor interface to what it produces downstream.** An
  operation — and related objects such as content-creation strategies — is not merely an action:
  the SAME object instance the case holds is the typed interface through which everything it
  created or changed is later retrieved, checked, and asserted, parameterized by the situation. A
  foundation operation exposes accessors for the participants it built; an edit operation exposes
  its dual check (`assertHasChangedProperty` / `has-property-of`); a choice is recovered by asking
  the one-of object (`getChoice`/`get-choice`); "did this step run" is asked of the step
  (`wasApplied`/`applied?`). NEVER reach into a situation (or the document) for something an
  upstream object produced — ask the producer. This is what keeps sweeps sound (object identity
  ties the question to the exact node that ran) and what keeps retrieval logic in exactly one
  place. Particularly explicit in the TS OOP implementation, where these accessors are methods on
  the operation/strategy classes; repeatedly violating it (reading the document directly,
  duplicating retrieval) was the most common review correction while building the suites.
- **Operations are data with identity.** Each operation node has a unique id at construction and
  records what it did under that id; interrogation is by identity. Bind an operation to a value
  ONCE and reuse it in the composition and in every query about it.
- **Drive the real production pipeline.** Operations route through genuine Penpot logic — real
  change functions / real workspace events / the real Plugin API, never raw field writes — so the
  production watcher's AUTOMATIC propagation is what's under test.
- **Roles, not internals.** A starting configuration names its participants (roles). Role→id
  capture happens when the configuration is built; operation TARGETS resolve at apply-time and may
  be re-bound, so an operation targeting a role follows it as state-building ops re-point it —
  which lets a single operation be swept across depth.
- **Enumeration is authored, not exhaustive.** Compose only VALID cases, so outcomes are just
  pass / fail / error — no not-applicable cells.
- **Naming discipline.** Penpot domain nouns ("component", "variant") must not name framework
  abstractions; an operation may name the domain ACTION it performs.
- **Operator algebra** (same in both suites): sequence (cartesian product of the steps' variants),
  one-of (union, choice recorded), optional(X) = one-of([X, skip]), inline assertion ops, trailing
  asserters.
- **Case authoring:** a case carries a CamelCase identifier and a plain-terms description in three
  parts — situation setup, actions/variations, asserted requirement.

---

# ClojureScript suite (frontend test tree)

Test-only `.cljs` code in the frontend test tree (nothing "common" about it). A **situation** =
the in-memory file value + named roles + `:vars` + an ordered applied-log. Operations are records
implementing `IOperation`/`apply-to` (`apply` collides with core). Assertions = inline `Test` ops
and/or a trailing asserter; the runner makes no judgment. Failures carry `describe-applied` (the
transcript), which is what makes a failing variant in a sweep identifiable.

Layout: `core.cljs` (the domain-agnostic engine: situation, identity/transcript, roles/targets,
operators, runners), `comp/setups.cljs` (setups + role accessors), `comp/nodes.cljs` (the component
operations and their check duals), `interpreter.cljs` (runs cases against the real frontend),
`comp/sync_test.cljs` (the cases; registered in `frontend_tests/runner.cljs`). Case letters B..N;
the sweeps (K: depth × edit-precedence; L: swaps; M: variant switches; N: rotated-instance
geometry, on the #10109 fix branch until merged) are the flagship pattern — read them before
writing a new sweep.

**Scenario lineage model** (behind the sweeps): scenario ops track named component lineages as
objects under `:vars`, each holding the FIXED deepest origin (`:remote-*`), the ADVANCING outer
main (`:main-*`), and per-nesting-level data whose `:nested-head` (the deepest instance at that
level, found by descending the `:shape-ref` chain — matching chain MEMBERSHIP, not terminus) is
the swap/switch target, anchored by its swap-stable parent. Nesting seeks the FIXED origin, not
the advancing main — that is what makes each level's `:nested-head` land on the deepest instance.
A variant nesting re-points the lineage's remote to the chosen member. Construction lesson:
cross-level propagation requires progressively NESTED levels (one variant + plain wraps); sibling
nestings do not propagate between each other.

**Interpreter:** installs the situation's files into the global `st/state` (aux files tagged
`:library-of`), starts the real `watch-component-changes` (+ harness `watch-undo-stack`), maps
event-ops to REAL workspace events (`dwsh/update-shapes`, `dwl/component-swap`,
`dwv/variants-switch`, `dwt/increase-rotation` — which runs the `check-delta` placement
classification — `dwt/update-dimensions`, `dwu/undo`, `dwl/sync-file`, …) and runs sync-ops'
`apply-to` against the live store file; awaits settlement (idle-gap heuristic + per-op grace) and
re-reads `:file` each step so the shared accessors keep working.
STORE-SWAP IMMUNITY: other test namespaces `set!` `st/state`/`st/stream` and never restore, while
the `app.main.refs` lenses stay bound to the ORIGINAL atoms — propagation then dies silently. The
interpreter captures the atoms at namespace-load time and re-`set!`s them per variant.

Running: `cd frontend && pnpm run build:test`, then
`node target/tests/test.js --focus frontend-tests.composable-tests.comp.sync-test`
(var-level focus for one case).

**Fidelity warning:** the harness drives a MINIMALLY-ASSEMBLED app — only some
`initialize-workspace` subscriptions are wired. Risk = SILENT UNDER-WIRING (e.g. undo needs the
harness `watch-undo-stack`). When a case needs app behaviour beyond a raw edit, check for an
unwired subscription and verify by PROBING store state, not by trusting a green assertion.

**Caveats:** inline `Test` exceptions are UNCAUGHT on the frontend (crash the runner — assert in
the trailing asserter). `(optional (in-sequence …))` is not flattened for the interpreter — use
independent optionals. The Serena/clj-kondo cache for `nodes.cljs` goes stale (phantom symbols) —
trust the build. Cross-namespace global-state leaks land in this suite first; suspect them before
the framework on inexplicable full-run-only failures. Case H's `sync-file` schedules a delayed RPC
that fails headless (benign; absorbed by per-op grace).

---

# TypeScript suite (the plugin) — full e2e

`plugins/apps/composable-test-suite/` — same principles against the FULL production app through the
Plugin API (real frontend, real propagation). Continuation of the CLJS suite per issue #10584.
Operational details (build/run, connect URL, remote control, reading logs, auto-reload, CI): the
plugin README.

Distinguishing abstractions (the OOP articulation of the shared principles):
- `TestCase {identifier, description, operation}` with the three-part description mandated in the
  constructor docstring.
- The accessor-interface principle is class-level: foundation operations (e.g.
  `OpCreateSimpleComponentWithCopy`) expose the roles they build; **content-creation strategies**
  (pluggable: what content a foundation builds around) expose accessors for the content they
  created; edit operations expose their checks (`OpChangeProperty.assertHasChangedProperty`);
  `OpOneOf`/`OpOptional` are queried for what ran. Tests never grope the document for something a
  producer can be asked for.
- `ShapeProp` model: property duals with numeric tolerance; rotation is a writable attr, height
  goes via resize (readonly in the Plugin API).
- `TestSuite` enumerates cases into a `TestTree` with stable per-test ids;
  `run(ids, TestRunObserver)` is the ONLY output channel — the framework is UI-free by
  construction. `plugin.ts` (panel adapter), `main.ts` (panel UI) and `src/ci/headless.ts`
  (CI adapter) are three thin consumers.
- Cases live in `src/composable-tests/cases/` as `case<Identifier>.ts` (e.g. `MainEditSyncs` — the
  sweep that found #10109).
- Panel checkboxes carry stable DOM ids (case identifier / `Identifier-N` composites) for remote
  control via Playwright; recipe in the README.

## CI
Headless per-PR gate: `.github/workflows/tests-composable-suite.yml` runs
`pnpm --filter composable-test-suite run test:ci` — mocked backend (frontend e2e static server +
Playwright RPC fixtures, no backend/login), the in-sandbox bundle injected via `ɵloadPlugin`,
results streamed via console markers, `TEST_FILTER` by identifier substring. The mocked backend is
NOT a limitation for this suite (everything asserted is frontend store logic; empirically
confirmed against the interactive runs). Architecture mirrors `plugin-api-test-suite`'s CI driver;
the mock harness exists in THREE places that must stay in sync (provenance note in `ci/run-ci.ts`).
Details: README, "Running in CI".

## Substrate
`mem:common/test-setup`, `mem:common/component-data-model`, `mem:common/component-swap-pipeline`,
`mem:frontend/testing`.
