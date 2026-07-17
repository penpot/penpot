# Composable component tests

A framework for systematically testing Penpot's component subsystem (synchronisation/propagation,
swaps, variant switches, nesting), plus the suite of cases built on it. Lives entirely in the
**frontend** test tree as `.cljs`; it is test-only code with a single consumer (the frontend test
suite, which runs the real app). There is nothing "common" about it — it is not under `app/common`.

## Core idea
A test is a **composition of operations** over a **situation**, plus assertions. You describe a
test as data (a setup + a sequence of operations) rather than writing bespoke imperative code, and
coverage grows by COMPOSITION: a new variation is one combinator wrapped around existing pieces,
not a copied test. One written case stands for a whole matrix of concrete cases.

- **Situation** — the in-memory Penpot file value, plus named **roles** (meaningful shapes, e.g.
  `:main-instance`/`:copy-instance`), a `:vars` map (named non-shape values), and an ordered
  **applied-log** of what ran.
- **Operation** — a step, reified as a DATA record implementing `IOperation` (single method
  `apply-to`; `apply` collides with core). Operations are printable, navigable, and enumerable.
  Most transform the situation; some do not — hence the genus is "operation", not "transformation"
  (`Test` asserts and returns the situation unchanged; `Skip` is a no-op).
- **Assertions** — inline `Test` operations placed in the sequence (assert at intermediate points)
  and/or a trailing asserter (a `situation -> any` lambda calling `t/is`). The runner makes NO
  judgment; it applies operations and returns the situation. Only *retrieval* helpers live outside
  the test (role accessors, `has-property-of`, `applied?`).

## Principles
- **Operations are data with identity.** Each node gets a unique id at construction (`assign-id`),
  records what it did under that id (`record-application`), and is interrogated by identity
  (`applied?`, `get-choice`). No flat keyword-tagged log.
- **Drive the real production pipeline.** Every operation routes through the actual production
  change functions (`generate-update-shapes`, `generate-component-swap`, `generate-reset-component`,
  `generate-sync-file-changes`, …) — never raw field writes, or propagation would have nothing to
  react to. The test exercises genuine Penpot logic, not a reimplementation.
- **The frontend runs the real app.** Synchronous file-ops apply directly to the store; event-ops
  dispatch the REAL workspace events and await settlement, so the production watcher's AUTOMATIC
  propagation is what's under test. Observed semantics are genuine.
- **Roles resolve to ids at setup time.** The global label→id map (`thi`) is shared and
  time-varying, so a role is captured as an id when the situation is built; resolving late (across
  enumerated variants) would be unsound. Absence throws a diagnostic (never silent nil).
- **Targets resolve at apply-time and may be rebound.** A target is a `(situation -> id)` FUNCTION,
  else a currently-bound ROLE, else a LABEL (`target-shape-id`). So one operation targeting a role
  follows that role as state-building ops re-point it — which is what lets a single operation be
  swept across depth.
- **Enumeration is authored, not exhaustive.** You compose only VALID cases (every `one-of` branch
  must be valid against the setup), so outcomes are just pass / fail / error — no not-applicable
  cells. Adding a variation across a matrix is a one-expression edit.
- **Naming discipline.** Penpot domain nouns ("component", "variant") must not name framework
  abstractions (they collide with real domain concepts). Framework vocabulary is testing concepts
  only; an operation may name the domain *action* it performs (`swap`, `propagate`).

## Composition operators
- `in-sequence` — ordered application, threads the situation; enumerates to the CARTESIAN PRODUCT
  of its steps' variants. Operations do not commute, so order is explicit. The workhorse.
- `one-of` — exactly one branch; applying it throws (must be enumerated); enumerates to the UNION
  of branches, each wrapped in a `RecordedChoice` so `get-choice` recovers which ran.
- `optional(X)` = `one-of([X, skip])` — sweeps "with and without X" (two variants). `skip` is the
  identity operation. The workhorse for adding a state-building step as an axis over a case.
- `test-that [assert-fn]` — an inline `Test`: asserts at this point in the sequence, situation
  unchanged. Lets checkpoints sit at intermediate steps. Engine stays clojure.test-free (it just
  calls the supplied fn).
- `applied? [situation operation]` — whether that exact node ran (identity-based). Composes with
  `optional`/`one-of` for free. REQUIREMENT: bind an operation to a value ONCE and reuse it (in the
  composition AND any `Test` querying it), so the id you ask about is the id that ran.

## Structure (frontend/test/frontend_tests/composable_tests/)
Two boundaries: the domain-agnostic **engine** and the **comp** subject library (about components;
naming the domain is correct there).

- `core.cljs` (ns `frontend-tests.composable-tests.core`) — the engine, one file:
  - situation: `make-situation`, `file`/`with-file`, `with-aux-files`/`aux-files` (carry extra
    files, e.g. a library for case H), the applied-log.
  - identity & transcript: `assign-id`, `node-id`, `record-application` (also stores a `::kind`
    from the record type), `node-data`, `describe-applied` (readable ordered transcript, attached
    to every failure so a failing variant in a sweep is identifiable).
  - roles & lookup: `role-shape` (strict, by stored id), `has-role?`, `rebind-role`/`rebind-role-id`
    (re-point a role — used by state-building ops), `target-shape-id` (fn | role | label),
    `shape-by-id` (read a shape whose id is held in a `:vars` object), `resolve-shape`/`-id`.
  - protocols: `IOperation`(`apply-to`); `IEnumerable`(`-enumerate`) + `enumerate`.
  - operators: `Sequence`/`in-sequence`; `OneOf`/`one-of`/`RecordedChoice`/`get-choice`;
    `Skip`/`skip`; `optional`; `Test`/`test-that`; `applied?`; vars `set-var`/`get-var`.
  - runners (clojure.test-free): `run-variant`, `run-all` (enumerate → vector, `thi/reset-idmap!`
    per variant).
  - per-op-interpreter helpers: `sequence-ops` (flatten a concrete variant — flattens `Sequence`,
    keeps `RecordedChoice` as a unit), `recorded-choice?`, `choice-of`, `choice-one-of-id`.
- `comp/setups.cljs` — setup fns returning a situation, + role accessors (`main-instance`/
  `copy-instance`/`main-root`/`copy-root`, `copy-child` 1-based): `simple-component-with-copy`,
  `simple-component-with-labeled-copy`, `component-with-many-children` (E, F),
  `nested-component-with-copy`, `cross-file-component-with-copy` (H: main in a linked library, copy
  in the consuming file; primary `:file` = consuming, aux = library), and `empty-situation` (empty
  file, no roles — the `:setup` for the sweep cases, whose first operation is `create-component`).
- `comp/nodes.cljs` — the component OPERATIONS. General edit/structure ops:
  `change-property [target property value]` (`:fills`/`:opacity`) with its dual `has-property-of`
  (`IPropertyCheck`); `change-attr`/`has-attr?` are aliases. `add-child`/`remove-child`/`move-child`
  (with `IStructuralCheck`). `sync-from-library` (H: `generate-sync-file-changes`, libraries =
  primary + aux). `undo` (I; frontend `dwu/undo`). Plus the scenario building blocks below.
- `interpreter.cljs` (ns `frontend-tests.composable-tests.interpreter`) — runs a case against the
  real frontend (see "Interpreter").
- `comp/sync_test.cljs` (ns `frontend-tests.composable-tests.comp.sync-test`) — the cases;
  registered in `frontend_tests/runner.cljs`.

## Scenario object model (behind the sweep cases)
Scenario ops track one or more named component LINEAGES as OBJECTS under `:vars :components` (keyed
by name, e.g. "main"). Grouping a lineage's fields into one object lets several lineages coexist
(a swap targets a DIFFERENT component) and makes each op "read object `name`, update, write back".

A lineage object has:
- `:main-component-id` — the component the next instantiate/nest uses (advances to the new OUTER
  component per `make-nested-component`).
- `:remote-head`/`:remote-rect` — the FIXED deepest origin (the original component everything is
  derived from). For a plain lineage this is never re-pointed; a variant nesting DOES re-point it
  (see below).
- `:main-head`/`:main-rect` — the current outer main (advances per nesting).
- `:nesting-count`; `:copies`, `:copy-head`/`:copy-rect` (from `instantiate-copy`).
- `:nesting-data` — vector, one entry per level i: `{:main-head, :nested-head, :nested-rect,
  :nested-head-parent}`.
  - `:nested-head` is the DEEPEST instance at level i — the descendant of the level's copy head
    that corresponds to `:remote-head`, found by descending the `:shape-ref` chain (at level 0 the
    inner copy itself; deeper, the corresponding shape nested within the outer wrapper). This is THE
    SWAP / SWITCH TARGET; it carries a `:component-id`.
  - `:nested-head-parent` is the SWAP-STABLE anchor: a swap/switch replaces the head in place but
    keeps its parent, so assertions re-resolve parent → current head → rect.

Accessors / targets (in nodes): `lineage-component-id`, `lineage-rect`, `lineage-copy-rect`,
`lineage-nesting`, `level-rect`/`level-rect-of` (level i's CURRENT rect via the parent anchor);
target-fns `remote-rect-of`/`main-rect-of`/`copy-rect-of` and `nested-head-of` (return a
`(situation -> id)` for use as an operation target). "Corresponds to" across layers follows the
`:shape-ref` CHAIN, matching on chain MEMBERSHIP not terminus (a copy rect refs its near-main,
which carries a further `:shape-ref`, so the terminus over-walks). Single-file setups, so the chain
resolves in the local page objects.

Scenario operations (each takes a lineage `name`):
- `create-component [name color]` — a component (frame + rect child of `color`); remote == main,
  count 0.
- `make-nested-component [name]` — wrap `name`'s component in a NEW OUTER component whose main
  contains a COPY of it inside a board (add-frame → instantiate inside → make-component); the OUTER
  becomes `:main-component-id`; advance `:main-*`; append a `:nesting-data` entry; bump count.
  ITERABLE: `×N` = board-within-board nesting with one rect at the bottom. Each level's
  `:nested-head` is the deepest instance there, so swapping/switching it propagates (via the
  watcher) outward to copies of it in OUTER levels.
- `instantiate-copy [name]` — instantiate `name`'s current component; track `:copy-head` and the
  rect corresponding to its main rect as `:copy-rect`.
- `reset-copy-instance [name]` — reset overrides on `:copy-head` (production
  `generate-reset-component`, `:validate? false` — a file-op on the frontend too: the real reset
  event reads browser globals and cannot run headless).
- `swap-component [name level target & {:keys [keep-touched?]}]` — swap level `level`'s
  `:nested-head` for lineage `target`'s component, via production `generate-component-swap`.
  Frontend = the REAL `dwl/component-swap` event, so the watcher AUTOMATICALLY propagates the swap
  to copies (incl. deeper levels). A swap replaces the head in place: Penpot keeps the head id,
  rewrites it to the new component, stamps a `:swap-slot-<uuid>` touched group. `keep-touched?`
  default false (discards overrides); true is the variant-switch flavour.
- Shared nesting helper `nest-in-new-outer-component [situation name op seek-rect-id seek-head-id
  instantiate-inner-fn]` — the contain-outward mechanism behind BOTH nesting ops. Adds the outer
  frame, calls `instantiate-inner-fn` to place the inner instance, makes the outer a component,
  then computes this level's `:nested-rect`/`:nested-head` as the IMAGES (inside the new inner copy)
  of `seek-rect-id`/`seek-head-id` — the FIXED deepest origin — and does the bookkeeping. A flavour
  supplies only the two origin ids + the instantiate fn. Seeking the FIXED origin (not the advancing
  `:main-*`) is what makes `:nested-head` land on the deepest instance at every level.
  `self-or-descendant-corresponding-to` is the chain-descent that also matches the head itself
  (needed at level 0, where the inner copy head IS the origin's image).

## Variant operations
A variant switch IS a keep-touched swap whose target is resolved by a property VALUE (the
production `variants-switch`/`variant-switch` reduces to `component-swap … keep-touched? true`), so
it routes through the SAME `generate-component-swap` and the watcher auto-propagates it across
nesting levels exactly like a swap.
- `make-variant-container [name members]` (sync-op) — build a variant SET synchronously, mirroring
  the test-helpers' `add-variant` idiom: a container frame (`:is-variant-container`), each `members`
  entry `[value color]` becoming a member component whose ROOT is a child of the container carrying
  the shared `:variant-id`/`:variant-name`, then `update-component` stamps `:variant-id` +
  `:variant-properties [{:name "Property 1" :value value}]` on the component. Read the container id
  via `(thi/id container-label)` only AFTER adding the container frame (`thi/id` returns nil before
  the shape exists). Records the set in `:vars` (`variant-set`/`variant-member`/
  `variant-member-component-id` read it back).
- `make-nested-component-with-variant [name set-name value]` (sync-op) — nest a chosen member via
  the shared nesting helper, AND re-point the lineage's `:remote-head`/`:remote-rect` to that
  member's root/rect: nesting a variant makes the member the new deepest origin, so subsequent plain
  `make-nested-component` descends to the variant's image (its `:nested-head`) at every level.
- `switch-variant [target value]` (frontend event) — switch the variant copy head bound to `target`
  to the sibling member with property value `value`, via the REAL `dwv/variants-switch` event (which
  DISCOVERS the sibling in the container via `find-variant-components`). `target` uses the standard
  resolution (role | label | fn), so the op knows nothing about nesting; cases supply
  `nested-head-of name i`.

## Interpreter (interpreter.cljs)
Drives the real app:
- `op->events [op situation]` maps each event-dispatching operation to its real workspace event(s):
  `ChangeProperty`→`dwsh/update-shapes` (the SHARED `set-property`, target via `target-shape-id`);
  `MoveChild`→`dwsh/relocate-shapes`; `RemoveChild`→`dwsh/delete-shapes`; `AddChild`→`dwsh/add-shape`;
  `SyncFromLibrary`→`dwl/sync-file`; `Undo`→`dwu/undo`; `SwapComponent`→`dwl/component-swap`;
  `SwitchVariant`→`dwv/variants-switch {:shapes [head] :pos 0 :val value}`. All real events, so the
  watcher auto-propagates.
- `sync-op?` ops (`MakeNestedComponent`, `CreateComponent`, `InstantiateCopy`, `ResetCopyInstance`,
  `MakeVariantContainer`, `MakeNestedComponentWithVariant`, `Skip`, `Test`) are NOT dispatched as
  events: `run-sync-op` runs the shared `apply-to` against a situation whose `:file` is the live
  store file, then writes back synchronously. The property under test is still exercised by the
  subsequent real-event ops + the watcher.
- It installs the situation's files into the global `st/state` store (primary as current; aux files
  tagged `:library-of` the current file so the library-sync machinery treats them as linked), starts
  the real `watch-component-changes` and the harness `watch-undo-stack`, then folds the operations:
  dispatch events → await settlement → re-read the current file into `:file` → record. Re-reading
  `:file` each step is why the shared role accessors keep working.
- `watch-undo-stack` mirrors the production undo-append subscription from `initialize-workspace`
  (which the harness does not run); without it `dwu/undo` has an empty stack.
- Settlement (`await-settle`): subscribe to the commit stream, resolve on the first 60ms idle gap
  after a commit (captures the edit commit and the watcher's follow-up sync commit), 2000ms timeout.
  Debounce-based, not a deterministic per-op stopper. After settling, `op-grace-ms` adds a per-op
  grace wait (currently only `SyncFromLibrary`, ~3.2s — see the Running note).
- Thumbnail rendering is stubbed for this suite (`install-thumbnail-noop!` no-ops
  `dwth/update-thumbnail`): the propagation watcher schedules thumbnail renders that reach `window`,
  absent headless.
- `check [done case-map asserter]` enumerates, runs each variant via the async fold, wraps the
  asserter in `describe-applied`, and calls `done`. Per-variant isolation = id-map reset + global
  state re-install (the global `st/state` is a shared `defonce`).
- STORE-SWAP IMMUNITY (`original-store`/`restore-global-store!`): many plugins-suite namespaces
  `set!` `st/state`/`st/stream` to isolated stores and never restore them. The `app.main.refs`
  lenses (through which the watcher observes commits) are okulary lenses bound to the ORIGINAL
  atom instance at load time — after such a swap, events commit to a store the watcher cannot see
  and ALL propagation dies silently (assertions see base/unsynced state; no error). The interpreter
  therefore captures `st/state`/`st/stream` at namespace-LOAD time (before any test runs) and
  re-`set!`s them at the start of every variant, making the harness immune to run order. Diagnosed
  by bisecting the runner's deterministic execution order (it is NOT the `test-namespaces` vector
  order: `t/test-vars-block` groups vars by namespace, and the group-by hash order decides — same
  order locally and in CI).

## Cases
Asserters are inline; no `doseq`, no count assertions.
- **B** — an override on the copy survives a later main change (override present + `:touched`
  contains the fill group + `:shape-ref` present).
- **C** — attribute sweep via `one-of {fills, opacity}`: assert `(has-attr? (get-choice …) copy)`
  per enumerated variant.
- **D** — `add-child` to the main gives the copy a ref-integral child (`is-main-of?` +
  `parent-of?` + untouched).
- **E** — `remove-child` of the MIDDLE of three: survivors keep order `[child1, child3]`,
  `:shape-ref` intact, untouched (middle removal is where index maintenance is tested).
- **F** — `move-child` of child1 to index 2: copy mirrors `[child2, child1, child3]`, identity
  preserved.
- **H** — locality (cross-file): main in a linked library, copy in the consuming (current) file,
  library main diverged. The in-file watcher does not cross a library boundary; the cross-file
  mechanism is the library-update action (`sync-from-library` → `sync-file file-id library-id`).
  Setup order matters: instantiate the copy first (captures the old value), diverge the library main
  after.
- **I** — undo: an edit then `undo`; copy and main return to baseline, copy untouched. A single
  `dwu/undo` reverses the whole logical action — the edit AND its auto-propagation — because the two
  commits share an undo-group.
- **K** — SYNC-SCENARIO SWEEP (the flagship). On `empty-situation`: `create-component` → two
  `(optional make-nested-component)` (depths 0/1/2) → `instantiate-copy` → three `(optional change-*)`
  over remote/main/copy → INLINE checkpoints: (1) override-precedence at the copy (copy wins, else
  main, else remote — branch via `applied?`), (2) force a copy override and confirm it wins, (3)
  after `reset-copy-instance`, copy reverts to main's value if main changed, else remote's. No
  explicit propagate (the watcher auto-propagates at all depths, incl. chained remote→deep-copy).
- **L** — SWAP SWEEP. On `empty-situation`: `create-component` (base) + one swap-target lineage per
  level → three `make-nested-component` → three `(optional (swap-component "main" i target_i))` →
  one `Test` asserting each level's colour. A swap at level i auto-propagates to level i and every
  OUTER level until a higher swap overrides; colour at level i = applied swap at highest j<=i, else
  base.
- **M** — VARIANT-SWITCH SWEEP (case L with a variant switch instead of the plain swap, driving the
  REAL variant-switch machinery). On `empty-situation`: `create-component` (base lineage "main") +
  `make-variant-container` (4 peer members `v0..v3`) → ONE `make-nested-component-with-variant
  "main" "vset" "v0"` (introduce the variant innermost) + two plain `make-nested-component`
  (progressive wraps, so each outer level CONTAINS the one below) → three
  `(optional (switch-variant (nested-head-of "main" i) v_{i+1}))` → one `Test` with case L's exact
  precedence asserter. Because the single variant instance has a switchable `:nested-head` at EVERY
  level and the levels are progressively nested, a switch at level i propagates outward like a swap.
  NOTE on structure: ONE variant + plain wraps is required for cross-level propagation (the levels
  nest WITHIN each other). Three independent `make-nested-component-with-variant` would nest SIBLING
  variants (none a descendant of another), so switches would not propagate between them — the right
  construction for a different test (one asserting switches DON'T cross unrelated instances).

Running: `cd frontend && pnpm run build:test` then `node target/tests/test.js --focus
frontend-tests.composable-tests.comp.sync-test`. To run one case, use var-level focus, e.g.
`…/case-m-variant-switch-scenarios`. NOTE: the production `sync-file` event (case H) additionally
schedules a 3s-delayed `update-file-library-sync-status` RPC, which fails headless (no backend; a
swallowed URL-parse trace is benign). The interpreter absorbs it: `op-grace-ms` makes the run wait
~3.2s after a `SyncFromLibrary` settles, so the failure lands inside case H instead of leaking into
(and potentially destabilising) whichever test runs next.

## Frontend fidelity — read before extending
The frontend runs the REAL production logic from the dispatched event onward, so observed semantics
are genuine. But it drives a MINIMALLY-ASSEMBLED app: it installs a file into the global store and
starts only the watchers known to be needed. The real app assembles its workspace via
`initialize-workspace`, which wires many subscriptions; the harness reproduces only some.

The risk is SILENT UNDER-WIRING — a behaviour that works in the real app can be silently absent in
the harness with no error (e.g. the undo stack is empty unless `watch-undo-stack` is started).
Therefore: when adding a case needing app behaviour beyond a raw edit (undo, persistence, selection,
layout, thumbnails, library auto-detection), first check whether that behaviour lives in an
`initialize-workspace` subscription the harness has not wired — and verify by PROBING store state,
not by trusting a green assertion. The harness hand-wires two stand-ins (`install-file-event`,
`watch-undo-stack`); track them for drift. A durable fix would be to drive the real
`initialize-workspace` headlessly (not done — full init may pull in machinery that doesn't run
cleanly headless).

## Other caveats
- Cross-namespace leaks land in this suite first because it (correctly) uses the real global
  store: besides the store swap above, `frontend-tests.helpers.wasm/teardown-wasm-mocks!` used to
  `set!` every WASM fn to nil when run against an empty snapshot (double teardown / async misuse of
  `with-wasm-mocks*`), which a leaked debounced `resize-wasm-text` event then tripped over during
  our cases (a "Store error: initialized? is not a function"). The teardown is now guarded
  (no-op on empty snapshot). If a new inexplicable full-run-only failure appears here, suspect
  leaked global state from a preceding namespace before suspecting the framework.
- INLINE `Test` exceptions on the frontend are UNCAUGHT (they run during the async fold, not under
  `check`'s try): a throwing checkpoint crashes the whole runner rather than failing one test.
- A `RecordedChoice` wrapping a `Sequence` (i.e. `(optional (in-sequence […]))`) is NOT flattened by
  `sequence-ops`, so `op->events` chokes on the `Sequence`. Use independent optionals instead (as
  case K does with two `(optional make-nested-component)`), or have the interpreter recurse into a
  choice's composite alternative.
- The Serena symbol index / clj-kondo cache for `nodes.cljs` can go STALE and report PHANTOM symbols
  or spurious "unresolved symbol" errors against code that compiles — TRUST THE BUILD (a real
  `pnpm run build:test`), not the lint or the symbol overview, for this file.
- Label-after-the-fact resolution: `add-child`'s `added-shape` resolves `:new-label` via `thi/id`,
  relying on the global label map still reflecting that run's setup — unsound if a structural node
  is swept via `one-of`. Fix when needed by capturing the created shape's id at apply-time (as roles
  already do).

## Substrate
`mem:common/test-setup`, `mem:common/component-data-model`, `mem:common/component-swap-pipeline`,
`mem:frontend/testing`.
