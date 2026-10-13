# Composable component tests

A framework concept for systematically testing Penpot's component subsystem
(synchronisation/propagation, swaps, variant switches, nesting, overrides), implemented in TWO test
suites that share the principles below:

1. **ClojureScript suite** — engine, nodes and setups in common
   (`common/src/app/common/test_helpers/composable/`, CLJC), interpreter and cases in the
   frontend test tree (`frontend/test/frontend_tests/composable_tests/`), driving a
   minimally-assembled real app headlessly. The original.
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

# ClojureScript suite

The engine, nodes and setups are CLJC test helpers in common and load on the JVM and in
CLJS; only the interpreter and the cases are frontend code. A **situation** =
the in-memory file value + named roles + `:vars` + an ordered applied-log. Operations are records
implementing `IOperation`/`apply-to` (`apply` collides with core). Assertions = inline `Test` ops
and/or a trailing asserter; the runner makes no judgment. Failures carry `describe-applied` (the
transcript), which is what makes a failing variant in a sweep identifiable.

Layout:
- common, `app.common.test-helpers.composable.*`: `core.cljc` (the domain-agnostic engine:
  situation, identity/transcript, roles/targets, operators, runners), `comp/setups.cljc`
  (setups + role accessors), `comp/nodes.cljc` (the component operations and their check
  duals), `comp/runner.cljc` (pure runner), `comp/cases.cljc` (THE cases, shared by both
  runners). No frontend requires allowed here.
- frontend, `frontend-tests.composable-tests.*`: `interpreter.cljs` (runs cases against the
  real frontend), `comp/sync_test.cljs` (one `t/async` deftest per shared case; registered in
  `frontend_tests/runner.cljs`).
- JVM/JS: `common-tests.logic.composable-sync-test` runs the shared cases through the pure
  runner; all except those that need frontend code: N, Z, AI and the rotation case (placement
  step), X (pending sync), AJ (reflow), AT..AX (variant action events).

Judging (both runners): each variant is judged before the next one runs (`r/run-each`, and the
interpreter's `check`), while the label map still names its shapes; a variant whose inline
tests and asserter make no assertion fails; a known undo failure that applies to no variant of
a runner fails (`uc/applying-marks`, `uc/check-marks-applied!`); `tm/one-of` refuses an empty
list and `tm/known-failure` a pin equal to the expected value. The asserter sees the state the
operations produced, not the one the end-of-variant round trip left. The pure runner throws on
an operation with no known kind and validates the final file; a round-trip error is recorded
with its phase and `[:error <ex-data :type>]`. Signatures tell a nil value from an absent key.

A case is a zero-arg fn in `cases.cljc` returning `{:setup :operation :asserter}`; assertions
use `clojure.test` (aliased to `cljs.test` in CLJS), inline (`test-that`) or in `:asserter`.
`ftm/check done case-map` reads `:asserter` from the map. Write a new case there, then add a
wrapper deftest in BOTH test namespaces (or only the frontend one if it needs frontend code).

Two pure runners (no store, JVM + CLJS):
- `core/run-variant`/`run-all`: apply nodes only (no sync, `n/undo` throws). For engine tests.
- `comp.runner/run-variant`/`run-all`: the case runner. Every comp node declares its kind via
  `n/IComponentOperation` (`op-kind`: `:assembly`, `:user`, `:undo`, `:redo`, `:frontend-only`); the
  runner and the interpreter's `sync-op?` dispatch on it. A NEW node MUST implement it. User ops
  record their production changes (`n/record-changes`, nil when nothing changed); the runner
  throws if a `:user` op records nothing or another kind records changes. After a user op the
  runner syncs to a fixpoint (`ch/components-changed` + `generate-sync-file-changes`, one pass
  per cascade level, like the frontend watcher) and pushes op + syncs as one undo group.
  `n/undo` (`(n/undo k)` for k groups) reverts the latest group and moves it to a redo stack; `n/redo` applies it again
  (interpreter: `dwu/redo`); neither re-syncs. A user op that pushes a group empties the redo
  stack; one that changed nothing keeps it. The runner validates the file (against the
  situation's files) once settled: after the sync fixpoint and after an undo group; never
  between cascade passes, where an outer copy still points at the component its main dropped
  (`:component-id-mismatch`), nor with `{:sync? false}`. Assembly ops (create/instantiate/nest/variant
  container): no sync, no undo group. `n/reset` (`reset-copy-instance` resets a lineage's latest copy) is a user op (sync +
  undo); the interpreter sends `dwl/reset-component`. `{:sync? false}` disables sync. `Rotate`/`ChangeHeight`/`MoveShape`
  are `:frontend-only` and throw `::runner/frontend-only` (their modifiers come from frontend events; the placement step itself is common, `app.common.logic.placement`). Swap and switch nodes use the production
  `clv/generate-component-swap-in-place` (+ `clv/find-switch-target` for switch), the same code the
  frontend events call. Not modelled: the `:modified-at` bump, and the swap/switch event follow-ups
  (token propagation, WASM text resize, layout update). Tests:
  `common-tests.logic.composable-runner-test`.
- Undo/redo round trip (`comp/undo_check.cljc`, alias `uc`), on by default in the pure runner
  (`:undo-check?`): after each `:user` step that pushed a group, undo must give back the
  normalized file before it and redo the file after it (the variant continues from the redone
  file); a user step that pushed nothing must change nothing (`:no-undo-entry`); at the end,
  undo back to the last assembly step (the baseline; a case's own undo below it moves it) and
  redo all. Files compare through `app.common.test-helpers.normalize` (`thn/normalize-file`:
  drops file/component `:modified-at`, optionally `:position-data`; shapes as plain maps).
  Failures are recorded in the situation with labelled diffs (labels die with the variant, so
  they are resolved when recorded) and a `:signature`, the set of what differs
  (`uc/signature`: `[:object <label> attr]`, `:missing`/`:extra`, `:page`/`:component` alike,
  `[:undo-index n]`, `[:error]`; unlabelled shapes are `:unlabelled`); `uc/check!` asserts after
  the asserter and prints the signature. A case map's `:undo-check` takes `{:off "reason"}` or
  `{:known-failures [{:bug "F41" :fails {phase signature} :op <the case's node> :runners
  #{:pure :frontend} :when pred}]}`: strict expected failures. A mark applies to variants of its
  runners (`uc/with-runner`) where its `:op` ran and `:when` holds; it covers a failure only
  when the signature is exactly the pinned one, and a pinned phase that does not fail as pinned
  fails the case. Marks without `:fails` throw. Never edit a pin to make a case pass: a pin
  changes only when the failure is understood, and the mark goes only in the fix commit. The interpreter runs the same round trip on every `:user` and
  `:frontend-only` event step through the real `dwu/undo`/`dwu/redo`, and also checks the
  workspace undo index (`:undo-index`/`:redo-index` phases): a step must add exactly one undo
  entry or group. File installs (assembly ops) are its baselines; a skipped optional and an inline `Test` are not. The interpreter
  empties `:workspace-undo` per variant. Frontend snapshots drop `:position-data` under WASM.
  Undo groups get lost in derived work: `initialize-shape-layout` buffers `:layout/update`
  without its `:undo-group` (F43), so a swap's reflow lands in entries of its own. Match
  steps by node identity, never by record type or rendered name: `n/MoveChild` does not resolve
  through an alias on the JVM, and the JS test build strips record names (transcripts show
  `node`).
- The interpreter records an event op's application as the op's own fields, not what its
  `apply-to` would record, so an accessor that must work in both runners reads the file
  structure (e.g. `level-rect`, `n/deleted-component-id`), never the node's recorded data.
  Shapes off the current page: `tm/find-shape`, `tm/role-shape-in-file` (setup
  `simple-component-with-copies-on-two-pages`). When an accessor needs what an op created
  (e.g. the duplicate's head), the node implements `n/IAppliedResult` (read from the file
  before and after); the pure node and the interpreter's `record-op` both record it.
- `n/create-component` builds 0.01-sized frames unless given `:size`. The transform math
  writes float noise into those sizes, so frontend syncs emit reflows whose commits lose
  their undo group (F43) at timing-dependent points (`initialize-shape-layout` buffers
  `:layout/update` in 100 ms windows): undo pins over such reflows are nondeterministic.
  New cases that are not about geometry pass `:size` (nesting sizes the outer frames too).
  F43 from float noise lives in case AJ only (frontend only, round trip off): it pins the
  value one undo leaves, which does not depend on how many entries the reflow makes. Case AN
  pins F43 from a real reflow (a flex main's margin edit, synced): stable in repeated runs.
- Case assertions a known bug breaks use `tm/known-failure bug expected pinned actual`: a
  strict expected failure at assertion level (passes only while `actual` is the pinned wrong
  value). The undo-check marks cover only round-trip failures.
- Text: `n/edit-text` with `n/text-edit-commit`, which includes the editor's automatic rename
  when the name was generated from the old content. Edits: `:text`/`:font-size` of the first
  span, `:fills`/`:font-family` of all of the text, `:content` (the whole text in the
  notation of `app.common.test-helpers.texts`, alias `thtx`; read back with
  `(n/text-span-value shape :content)`). Setups `simple-text-component-with-copy` (text named
  after its content) and `two-style-text-component-with-copy` ("Price: " + bold "$10",
  written as the editors write it).
- The interpreter unwraps nested choices (`(tm/optional (tm/one-of …))`); `(optional
  (in-sequence …))` is still not flattened.
- Depth sweeps: `depth-sweep` in `cases.cljc`, a one-of over skip,
  `(n/make-nested-component name)` and `(n/make-nested-component name 2)`; asserters read
  `n/nesting-depth`. Two independent nesting optionals run depth 1 twice.
- General target-based nodes: `n/swap-shape target to` (`to` a lineage name or a component label), `n/move-to-cell board child row column`, `n/reset target` (`dwl/reset-component` in the interpreter). `n/swap-component name level to` is `swap-shape` on `n/nested-head-of`. `n/nested-head-in name` (and the general `n/nested-head-under parent-target`) finds a copy's nested
  head through its parent, so it survives swaps and switches (they keep the head id anyway).
  Careful: `n/lineage-component-id` is the OUTER component after nesting.
- Variant properties: `n/add-variant-property`, `remove-`, `rename-`, `reorder-variant-properties`
  and `set-variant-property-value` are one node (`EditVariantProperty`); `n/variant-property-call`
  resolves the edit to the arguments both the `clvp/generate-*` generator and the `dwv/*` event take.
  A member is named by the value it was built with; read its current state from the file
  (`n/member-properties`, `n/member-component`, `n/member-root`). `n/switch-variant` takes `:pos`
  (a number or a `(situation -> pos)` fn) for sets with several properties.
  `n/add-variant set member` duplicates a member (`clv/generate-add-new-variant`; `dwv/add-new-variant`,
  whose delayed DOM focus `check` neutralises by stubbing `dom/get-element`); ask the node for the
  new component (`n/added-variant-component-id`).
  Frontend-only variant actions are one node, `n/VariantAction` (`n/transform-in-variant`,
  `combine-as-variants`, `rename-variant`, `rename-variant-in-layers`, `remove-empty-properties`,
  `set-variant-error`, `variant-shortcut`); the interpreter's `variant-action-events` sends them.
  A set they build has no `variant-set` record: read it with `n/component-of` and
  `n/variant-component-ids`; `switch-variant` takes the value as a fn for it.
- Tokens: `n/add-tokens` (assembly: an active set of border radius tokens), `n/apply-token`,
  `n/unapply-token`, check `n/has-token?`. The interpreter sends `dwta/apply-token`, which resolves
  tokens asynchronously and opens no pending work (F54), so `await-step` takes a `ready?` predicate
  (`op-ready`: the token shows on the shape) that must hold before it awaits the pending work.
- `n/paste-to-root target`: the clipboard's copy (shape-refs of nested heads advanced, the skipped ref
  chain's touched merged) and paste at the root; the interpreter pastes `thp/simulate-copy-shape`'s data.
  The node, that helper and `copy-selected` all advance through `cld/advance-copied-objects` (`app.common.logic.duplicate`). A duplicate inside a copy is
  refused by `ctk/allow-duplicate?`, and an alt-duplication with no move makes the next edit join
  its undo group (it expects the drag's move), so neither models a move out of a main.
- `n/undo-before-sync` (frontend-only) emits an edit and `dwu/undo` in one step, so the undo
  commits while the watcher still holds the edit's sync.
- Label map gotcha: running a second variant re-registers labels, so assert on a situation
  before running the next one (`thi/id` then points at the new run's shapes); `run-each` does,
  `run-all` does not.
- `change-property` with `:name` renames as the layers panel does (`dw/end-rename-shape` in the
  interpreter): the path is cleaned and a main root's component is renamed too. Case letters B..Z, then AA..;
the sweeps (K: depth × edit-precedence; L: swaps; M: variant switches; N: rotated-instance
geometry, on the #10109 fix branch until merged; O: switch of an overridden copy, mains
agreeing or not; Q: detach at depth 0..2, nested copies stay linked; R: update-main at depth 0..2 with two copies; S: delete/restore with a copy on another page; T: duplicate a copy at depth 0..2; U: move a copy into a main with a copy; V: new edit after undo empties redo; W: `(n/undo 3)` over nested cascades; X: undo before the edit's sync, frontend only; AJ: F43, one undo after a nested sync reverts only the reflow, frontend only; Y: text override × main text change, per subgroup; Z: main child move, frontend only; AA..AE: swap outcomes asserted on values, not slot storage, so they survive the Phase 2b swap model; AF: grid cell move vs a swapped copy child, setup `grid-component-with-copy`; AG/AH/AI: ports of the plugin's slot-alignment cases, setup `component-with-sibling-instances`, check `setup/misaligned-sub-heads`; AK: update main into a grid main, F49 pinned; AL: update main renames the component (`change-property` takes `:name`); AM: update main of a swap inside a group, setup `grouped-component-with-copy`, F16 pinned; AN: flex child margin override through main edit, reset and update main, setup `flex-component-with-copy`; `change-property` takes `:margin`, which the interpreter sends as `dwsl/update-layout-child`; AO: a copy dropped into a grid cell of a main (`nest-copy` with `:cell`, sent as `dwt/move-shapes-to-frame`), F41 pinned; AP: a library main's middle child removal reaches a copy in another file (in one file, deleting a main child deletes the copies' children itself, so sync never meets the copy child), setup `cross-file-component-with-many-children`; AQ: main change reaches a copy nested in a sub-board, setup `component-nested-in-a-sub-board`; AR: variant property edits keep a member's copies as they are, then a switch finds its member by the edited properties; AS: an added variant joins the set and is a switch target; AT..AX, frontend only: transform into a variant, combine, rename a variant (F6 pinned: the transaction keeps the container commit's undo group, the copies' sync takes the main renames'), rename in layers + remove empty properties + member error, the shortcuts that add members; AY: a border radius token on a main reaches untouched copies and its removal too, F55 pinned; AZ: a nested copy pasted out of its main keeps the main's overrides; BA: a copy text's style override against a main style change, then reset or update main, F72 pinned) are the flagship pattern — read them before
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
`:library-of`), starts the production watchers the app starts — `dw/initialize-edit-watchers`
(undo stack, layout, text reflow, WASM text measurement; stopped by `dw/finalize-edit-watchers`,
replaced when started again) and `dwl/watch-library-changes` (component + token watchers;
replaced when started again) — then maps
event-ops to REAL workspace events (`dwsh/update-shapes`, `dwl/component-swap` (also `n/swap-shape`, any copy head),
`dwv/variants-switch`, `dwt/increase-rotation` — which runs the `check-delta` placement
classification — `dwt/update-dimensions`, `dwl/detach-component`, `dwl/update-component-sync`, `dwl/delete-component`, `dwl/restore-component`, `dws/duplicate-shapes`, `dwsh/relocate-shapes` (nest-copy), `dwtx/v2-update-text-shape-content` (edit-text, the editor's on-blur commit with its automatic rename), `dwu/undo`, `dwl/sync-file`, …) and runs sync-ops'
`apply-to` against the live store file; each event step goes through `ftm/await-step`, which
resolves once `wrf/settled nil` (the watcher's `:sync-file` wait, opened for EVERY local commit,
plus the sync and layout/text reflow it starts) or reports `{:timeout pending}` after 10s; a
timed-out step fails the test and skips the asserter. Potok catches an error thrown by an event
and only logs it, so a step during which the store reports an error (`st/on-error`) fails too.
`ftm/fixture` stops the watchers `install!` started (`ftm/uninstall!`). Contract: producers must open their
pending work synchronously while the events are processed (Potok processes sub-events
synchronously); an async hop without a pending task makes steps resolve early. Tests:
`frontend-tests.composable-tests.completion-contract-test` (slowed-down sync via a mocked
`dwl/sync-file`), `frontend-tests.data.workspace-sync-barrier-test`. Re-reads `:file` each step
so the shared accessors keep working.
WASM-SHAPE IMMUNITY: a test that starts the WASM renderer leaves `cts/wasm-enabled?` on, which
makes every later shape a proxy that leaves out nil and false attributes; `fixture` turns it off
for the composable tests and restores it after, so they run on records in any order.
STORE-SWAP IMMUNITY: other test namespaces `set!` `st/state`/`st/stream` and never restore, while
the `app.main.refs` lenses stay bound to the ORIGINAL atoms — propagation then dies silently. The
interpreter captures the atoms at namespace-load time and re-`set!`s them per variant.

Running (JVM): `cd common && clojure -M:dev:test --focus common-tests.logic.composable-sync-test`.
Running (frontend): `cd frontend && pnpm run build:test`, then
`node target/tests/test.js --focus frontend-tests.composable-tests.comp.sync-test`
(var-level focus for one case).

**Golden master:** `check` records the store's files after each user, frontend-only, undo
and redo step and at the end of each variant, keyed by the test var's name, and compares them
with `golden_master.edn` next to the interpreter (see `mem:common/testing`, "Golden master").
A new case fails until recorded with `PENPOT_GOLDEN_MASTER=record`.

**Fidelity warning:** the harness drives a MINIMALLY-ASSEMBLED app: no file loading, no
persistence, no viewport. Edit-reacting subscriptions come from the shared events above; a NEW
edit-reacting subscription belongs inside `initialize-edit-watchers` (or
`watch-library-changes`), never wired inline in `initialize-workspace`, or the harness silently
misses it. Headless there is no DOM text measurement: `install!` starts
`headless-text-measurement`, a stand-in that opens and closes a `:text-measure` task for each
`:text/reflow` (geometry and `:position-data` unchanged), so text steps settle. It does not
reproduce what the real measurement commits (F37: `commit-position-data`).

**Caveats:** an exception in a frontend variant (inline `Test`, asserter, accessor) is reported
as an error of its case and the next variant runs. `(optional (in-sequence …))` is not flattened for the interpreter — use
independent optionals (nested choices are fine). The Serena/clj-kondo cache for `nodes.cljc` goes stale (phantom symbols) —
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
- Roles are bound BY NAME in the situation: two foundation ops with the same role names overwrite
  each other. `OpCreateNestableComponent(strategy, name)` prefixes its roles with `name`; give
  every extra lineage in a case a name.
- `OpDetach`/`OpSwap`/`OpReset` wait for pending propagation before acting (a script could
  otherwise act inside the sync window, which no user can). `swapComponent` keeps touched (F33).
  The suite pins published `@penpot/plugin-types` 1.4.2, which lacks `resetOverrides` (typed cast).
- Known bugs: `Assert.knownFailure(bug, expected, pinned, actual, equals)` (same order as `tm/known-failure`), the counterpart of
  `tm/known-failure`.
- Panel checkboxes carry stable DOM ids (case identifier / `Identifier-N` composites) for remote
  control via Playwright; recipe in the README.

## CI
Headless per-PR gate: the `composable-test-suite` job in
`.github/workflows/tests-e2e.yml` runs `pnpm --filter composable-test-suite run test:ci` — mocked
backend (frontend e2e static server +
Playwright RPC fixtures, no backend/login), the in-sandbox bundle injected via `ɵloadPlugin`,
results streamed via console markers, `TEST_FILTER` by identifier substring. The mocked backend is
NOT a limitation for this suite (everything asserted is frontend store logic; empirically
confirmed against the interactive runs). Architecture mirrors `plugin-api-test-suite`'s CI driver;
the mock harness exists in THREE places that must stay in sync (provenance note in `ci/run-ci.ts`).
The frontend bundle is built once per SHA and restored by this job (`mem:frontend/e2e-ci-workflow`).
Details: README, "Running in CI".

## Substrate
`mem:common/test-setup`, `mem:common/component-data-model`, `mem:common/component-swap-pipeline`,
`mem:frontend/testing`.
