# Penpot Component Subsystem — Engineering Hand-off

> **Audience:** agents/engineers tasked with fixing bugs in Penpot's component,
> copy, and variant machinery. This is a map of the territory, not a tutorial.
> It tells you *where* things live, *how* the main flows work, and *where the
> mines are buried*. Read the "Sharp edges & unclear areas" section before you
> touch sync logic.

All paths are repo-relative. Line numbers drift; treat them as starting points and
re-locate symbols by name.

---

## 1. What the subsystem does

A **component** is a reusable shape tree. Users instantiate it to get **copies**
(instances). A copy can diverge from its source ("overrides", tracked with
`:touched` flags). Changes can flow in two directions:

- **Copy → Main** ("update main" / inverse sync): push a copy's local changes back
  into the component definition.
- **Main → Copies** ("sync" / direct sync): propagate the component definition to
  every copy, in every page, even across files (remote libraries).

**Variants** are a newer layer on top: a set of related components grouped in a
*variant container* frame, distinguished by named properties, with a UI to switch
a copy from one variant to another (which is implemented as a *component swap*).

This is the most complex, most error-prone area of the codebase. It crosses the
`common`/`frontend`/`backend` boundary, mutates a shared tree, and has many
special cases (nested copies, swaps, fostered children, remote libraries, grid/flex
layout, text, paths).

---

## 2. Mental model & glossary

### Shape roles (a shape can hold several at once)

| Role | Marker attrs | Predicate |
|---|---|---|
| **Main instance / master** | `:main-instance true`, `:component-id`, `:component-file` | `ctk/main-instance?` |
| **Copy / non-main instance** | `:shape-ref` (points up the inheritance chain) | `ctk/in-component-copy?` (≈ `(some? (:shape-ref shape))`) |
| **Component root** | `:component-root true`, plus `:component-id` / `:component-file` | `ctk/instance-root?` |
| **Instance head** (top of a nested sub-instance) | — | `ctk/instance-head?`, `subinstance-head?`, `subcopy-head?` |
| **Variant master** | main instance + component root + `:variant-id` | `ctk/is-variant?` |
| **Variant container** | frame with `:is-variant-container true` | `ctk/is-variant-container?` |

> ⚠️ Roles are **not mutually exclusive**. A variant master is a main instance *and*
> a component root, and its descendants can themselves be copies (nested instances).
> Any logic that assumes "this shape is only a master" or "only a copy" is suspect.

### Key concepts

- **`:shape-ref`** — a copy shape points to the equivalent shape one level up the
  inheritance hierarchy (the "near main"). Chains can be multiple levels deep
  (nested components) and can **cross files** for remote libraries.
- **`:touched`** — a set of *override-group* keywords (`:geometry-group`,
  `:fill-group`, `:content-group`, `:name-group`, …). Presence means "this copy
  diverged from its main for attrs in that group, so don't overwrite them on sync".
- **swap slot** (`:swap-slot-*` via `ctk/get-swap-slot`/`set-swap-slot`) — records
  that a sub-instance was swapped for a different component, so sync knows the head
  no longer matches its original `:shape-ref` position.
- **direct vs remote copy** — `ctf/direct-copy?` distinguishes a copy whose
  `:shape-ref` points directly into the nearest component vs. one inheriting through
  intermediate components. Normal sync only touches direct/near mains.

### Namespace aliases you will see everywhere

| Alias | Namespace | Role |
|---|---|---|
| `ctk` | `app.common.types.component` | predicates, touched/swap-slot helpers, data model |
| `ctkl` | `app.common.types.components-list` | the library's component registry (CRUD over `:components`) |
| `ctf` | `app.common.types.file` | ref-shape resolution, component lookup across files |
| `ctn` | `app.common.types.container` | shape-tree access + `make-component-instance` |
| `cll` | `app.common.logic.libraries` | **the sync/instantiate/swap engine** |
| `clv` | `app.common.logic.variants` | variant switch / keep-touched logic |
| `clvp`| `app.common.logic.variant-properties` | variant property name/value edits |
| `ctv` | `app.common.types.variant` | variant data types |
| `cfv` | `app.common.files.variant` | variant container/file-level helpers |
| `pcb` | `app.common.files.changes_builder` | fluent change-set builder |
| `dwl` | `app.main.data.workspace.libraries` (frontend) | Potok events |

---

## 3. Data model — where state lives

### In the file/library data (`:data`)

- **`:components`** — map of `component-id → component record`. Managed by `ctkl`
  (`app.common.types.components-list`). A component record holds:
  `:id :name :path :main-instance-id :main-instance-page`, optional
  `:variant-id :variant-properties`, `:deleted` (soft-delete / "deleted component"
  used by undo & detached-but-referenced copies), and for deleted components an
  embedded `:objects` snapshot.
- **The main instance lives in the page tree**, not inside the component record. The
  record only points to it via `:main-instance-id` + `:main-instance-page`. This
  indirection is a frequent source of "invalid main instance" validation errors —
  see `validate.cljc`.

### On shapes (page objects)

`:component-id :component-file :component-root :main-instance :shape-ref :touched`
plus swap-slot and variant attrs (`:variant-id :variant-name`).

### Component v2

Full referential/semantic validation only runs when the file's features contain
`"components/v2"`. v1 behavior is mostly legacy; on v2 the main instance is a real
shape in a page (note `make-component-instance` forces `:parent-id nil` /
`:frame-id uuid/zero` on the cloned component-shape "to behave like v1").

---

## 4. File map — the main files to check

### `common/` — the engine (runtime-agnostic, the real logic)

| File | What's in it |
|---|---|
| `common/src/app/common/logic/libraries.cljc` (~3100 lines) | **The core.** Instantiate, both sync directions, attribute-copy algorithm, swap, detach, reset, add/duplicate component. Start here for almost any bug. |
| `common/src/app/common/logic/variants.cljc` | Variant switch support: `generate-keep-touched`, `find-shape-ref-child-of`, `add-touched-from-ref-chain`, `generate-add-new-variant`. |
| `common/src/app/common/logic/variant_properties.cljc` | Variant property name/value generation. |
| `common/src/app/common/logic/shapes.cljc` | Generic shape ops that special-case copies (e.g. delete-inside-copy, swap). |
| `common/src/app/common/types/component.cljc` | Data model: predicates, `sync-attrs` (attr→group map), `swap-keep-attrs`, touched/swap-slot get/set, `detach-shape`, `diff-components`. |
| `common/src/app/common/types/components_list.cljc` | Component registry CRUD; soft delete (`mark-component-deleted/undeleted`), `update-component`, `set-component-modified`. |
| `common/src/app/common/types/file.cljc` | **Ref-shape resolution** (the chain walkers): `get-ref-shape`, `find-ref-shape`, `find-remote-shape`, `get-component-root`, `direct-copy?`, `find-swap-slot`, `get-ref-chain-until-target-ref`, `find-near-match`, `advance-shape-ref`. |
| `common/src/app/common/types/container.cljc` | `make-component-instance` (the clone-and-relink primitive), shape-tree accessors. |
| `common/src/app/common/types/variant.cljc` / `files/variant.cljc` | Variant types and container/file helpers. |
| `common/src/app/common/files/changes_builder.cljc` (`pcb`) | How every mutation is expressed; `:ignore-touched`, `set-translation?`, `update-shapes`. |
| `common/src/app/common/files/changes.cljc` | `process-operation` multimethod, `set-shape-attr`, second-pass touched handling. |
| `common/src/app/common/files/validate.cljc` (~980 lines) | All component invariants and the error codes they raise. **Read this to learn what "correct" means.** |

### `frontend/` — events & UI

| File | What's in it |
|---|---|
| `frontend/src/app/main/data/workspace/libraries.cljs` (`dwl`, ~1600 lines) | Potok events: `instantiate-component`, `detach-component(s)`, `reset-component(s)`, `update-component`, `update-component-sync`, `component-swap`, `component-multi-swap`, `sync-file`, `watch-component-changes`, restore/delete/duplicate/rename component. **The UI→common bridge.** |
| `frontend/src/app/main/data/workspace/variants.cljs` | Variant events: `variant-switch`, `variants-switch`, `add-new-variant`, `combine-as-variants`, property edits. Switch ultimately calls `dwl/component-swap`. |
| `frontend/src/app/main/ui/workspace/sidebar/options/menus/component.cljs` (~1340 lines) | The component options panel: update main, reset, detach, swap UI, show-main, annotations, variant property editor. The main user entry surface. |
| `frontend/src/app/main/ui/workspace/sidebar/assets/components.cljs` | The assets library panel (list, group, instantiate via drag, context menu). |
| `frontend/src/app/main/ui/inspect/.../variant*.cljs` | Inspect/codegen of variants. |

### Backend

The backend stores and re-validates file data; it does **not** re-run component
sync logic, but it does run validation/repair on persisted files and an async
process that **remaps media references** after instantiation (see the WARNING in
`make-component-instance` — media refs are fixed up server-side, not at clone time).

### Tests (canonical behavior references)

- `common/test/common_tests/logic/comp_sync_test.cljc` — direct/inverse sync.
- `common/test/common_tests/logic/copying_and_duplicating_test.cljc`
- `common/test/common_tests/logic/text_sync_test.cljc`
- `common/test/common_tests/logic/variants_switch_test.cljc` — **canonical swap+touched suite.**
- `common/test/common_tests/logic/variants_test.cljc`
- `common/test/common_tests/types/components_test.cljc`, `types/variant_test.cljc`, `variant_test.cljc`
- `frontend/test/frontend_tests/logic/components_and_tokens.cljs`
- Test helpers: `common/src/app/common/test_helpers/{components,compositions,variants,files}.cljc`.
  Notably `compositions/swap-component-in-shape` drives the real swap pipeline; use
  `{:keep-touched? true}` to exercise variant-switch behavior. `thf/apply-changes`
  is the production applier analog and validates by default.

---

## 5. Core code flows

### 5.1 Instantiate a component
`dwl/instantiate-component` → `cll/generate-instantiate-component`
(`libraries.cljc:236`) → `ctn/make-component-instance` (`container.cljc:299`).

`make-component-instance` clones the component's shape tree, assigns new ids/names,
and for each cloned shape (`update-new-shape`):
- moves it by `delta` and **dissociates `:touched :variant-id :variant-name`** (clean copy);
- for main instances: sets `:main-instance`, drops `:shape-ref`;
- for copies: sets `:shape-ref` to the original shape id (the near instance);
- sets `:component-root`/`:component-id`/`:component-file` on the root only.

`generate-instantiate-component` then fixes parent/frame ids, handles dropping into
grid layouts, and emits `:add-obj` changes with `:ignore-touched true`.

> When debugging "instance came out wrong", determine **which clone path** produced
> it: `make-component-instance` (clean) vs `duplicate-component`/`generate-duplicate-*`
> (does **not** clean inherited attrs — source `:touched` etc. can leak in).

### 5.2 Direct sync (Main → Copies)
`cll/generate-sync-file` (`libraries.cljc:483`) iterates every container (pages +
deleted-component objects) → `generate-sync-container` → `generate-sync-shape`
(multimethod on asset type `:components`/`:colors`/`:typographies`) →
`generate-sync-shape-direct` (`:796`) → `generate-sync-shape-direct-recursive`.

- Only operates when `in-component-copy?` **and** (`direct-copy?` or `reset?`).
- `reset?` resolves the main against the **ref-shape** (`find-ref-shape`); normal
  sync resolves against the component's stored ref (`get-ref-shape`).
- Recursion compares children (`compare-children`) to add/remove/move shapes and
  calls `update-attrs` per matched pair.

Frontend triggers: `dwl/update-component-sync`, `dwl/sync-file`,
`dwl/watch-component-changes` (auto-sync after edits to a main), and the explicit
"Update main component" action.

### 5.3 Inverse sync (Copy → Main) — "Update main"
`dwl/update-component` → `cll/generate-sync-shape-inverse` (`:1010`) →
`generate-sync-shape-inverse-recursive`. Resolves the remote shape with
`ctf/find-remote-shape` (walks `:shape-ref` across files), pushes copy values up,
renames the component if `:name-group` is touched, and marks the component modified.

### 5.4 Reset / Detach
- **Reset** (`dwl/reset-component(s)`): direct sync with `reset? = true` — discards
  overrides by syncing the copy back to its ref-shape. `cll/generate-reset-component`.
- **Detach** (`dwl/detach-component(s)`): `cll/generate-detach-component` /
  `generate-detach-recursive` / `generate-detach-immediate` — strips `:shape-ref`,
  component attrs, swap slots via `ctk/detach-shape`. `advance-nesting-level` handles
  nested cases.

### 5.5 Component swap (and variant switch)
Single swap workhorse: `dwl/component-swap` → `cll/generate-component-swap`. It
removes the old shape and instantiates the target in place
(`generate-new-shape-for-swap` → `generate-instantiate-component` →
`make-component-instance`), preserving identity (swap slot) so sync still works.

`component-multi-swap` batches and calls `component-swap` with `keep-touched? = false`.

**`keep-touched? = true`** (single swap / variant switch) additionally runs
`clv/generate-keep-touched` (`variants.cljc`):
1. walk pre-swap children, union chain-derived touched via `add-touched-from-ref-chain`;
2. for each, find the equivalent target child via `find-shape-ref-child-of`;
3. call `cll/update-attrs-on-switch` to carry user overrides onto the fresh copy.

**Variant switch:** `variants.cljs/variant-switch` / `variants-switch` (driven by the
property-toggle UI and the Plugin API `switchVariant`) compute the target component
and delegate to `dwl/component-swap` with `keep-touched? true`.

### 5.6 Add component from selection
`dwl/add-component` → `add-component2` → `cll/generate-add-component` /
`generate-add-component-changes`: converts selected shapes into a main instance,
registers the component in `:components`, and (for variants) may create a variant
container. Dropping a shape into a variant container can auto-convert it to a
variant via `generate-make-shapes-variant` — **treat drag/drop into a variant
container as a component operation, not a plain reparent.**

---

## 6. The attribute-sync algorithm (`update-attrs`, `libraries.cljc:1790`)

The heart of direct/inverse sync. For a `(dest-shape, origin-shape)` pair it loops
`updatable-attrs` and copies origin→dest, with these rules:

- **Geometry is relative:** `reposition-shape` moves `origin-shape` so its root
  aligns with `dest-root` before comparison (coordinates are absolute, but only the
  relative position should sync). For subinstances, comparison is always against the
  *near* component.
- `omit-touched?` true ⇒ skip attrs whose `sync-group` is in dest's `:touched`.
- Skip when origin == dest for that attr.
- **`:position-data`** is derived: reset to `nil` (recomputed) when geometry is
  touched and text position-data changed.
- **Text `:content`** is special: text *and* formatting share one `:content` attr;
  on partial change it merges (`text-change-value`) and forces a position-data reset.
- After the loop: `check-detached-main`, `check-swapped-main`, and token sync
  (`generate-update-tokens`).

`add-update-attr-changes`/`add-update-attr-operations` build the `:mod-obj`
`:set` operations (with inverse ops for undo).

### `update-attrs-on-switch` (`libraries.cljc`, swap-specific)
Separate from `update-attrs`. Compares three shapes: `current-shape` (fresh target
copy), `previous-shape` (pre-swap shape with chain-derived touched), `origin-ref-shape`
(source variant master's equivalent). Loops `sync-attrs` except `swap-keep-attrs`,
copying overrides through several guards (skip equal values, skip equal composite
geometry, require the touched group, require source/target masters to agree, dedicated
fixed-layout geometry handling, specialized text/path conversion). The generic
fallback copies from `previous-shape` — **this is where most swap bugs surface** when
a guard fails to reject incompatible geometry or a master mismatch.

---

## 7. Touched flags & overrides

- `sync-attrs` (`component.cljc`) maps every syncable attr to its touched group.
  **Any new syncable shape attr must be added here** or sync silently ignores it.
- `set-touched-group` is the only legitimate setter; the central `set-shape-attr`
  path calls it only for copies and only when ignore flags allow.
- Masters are *not normally* touched, but touched flags can leak onto masters via
  clone/duplicate paths, and `add-touched-from-ref-chain` unions ancestors' touched
  into a copy — so a shape that looks untouched locally may behave as touched.
- Width/height are **excluded** from the `is-geometry?` ignore branch in
  `set-shape-attr` — don't assume all geometry-group attrs behave identically.
- Geometry differences under ~1px are treated as equal (approximate equality) for
  touched purposes. See `mem:common/decimals-and-coordinates`.

---

## 8. Variants

- A variant container is a frame `:is-variant-container true`; children are variant
  masters carrying `:variant-id` (→ container) and `:variant-name` (the value).
- Component records carry `:variant-properties`.
- Predicates are deliberately broad: `ctk/is-variant?` matches both variant master
  shapes and component rows; `is-variant-container?` checks the frame flag.
- Switch flow: §5.5. Property edits: `variant_properties.cljc` + `variants.cljs`.
- `find-shape-ref-child-of` walks the ref chain to find the equivalent master child
  in the target variant — central to mapping overrides across a switch.

---

## 9. Validation & repair (`common/src/app/common/files/validate.cljc`)

Runs full referential/semantic checks only on `components/v2` files. It is the best
single source of "what invariants must hold". Error codes worth knowing (line ~30–67):

`:component-not-main`, `:component-main-external`, `:component-not-found`,
`:invalid-main-instance-id/-page/-instance`, `:component-main`, `:shape-ref-in-main`,
`:component-id-mismatch`, `:component-nil-objects-not-allowed`, `:shape-ref-cycle`,
`:component-duplicate-slot`.

`repair-file` does **not** mutate directly — it reduces validation errors into redo
changes via `changes-builder`; callers must apply/persist them. When a fix "doesn't
stick", check that the repair changes were actually applied.

---

## 10. Debugging recipes (from `mem:common/component-debugging-recipes`)

Two runtimes are in play for live work: `mcp__penpot__execute_code` (Plugin API,
can crash silently) and `mcp__penpot__cljs_repl` (raw REPL, survives crashes). Detect
a crash with `(some? (:exception @app.main.store/state))`.

**Inspect what a UI action emitted** (last undo entries / `:mod-obj` operations):
walk `[:workspace-undo :items]` in the store — snippet in the debugging-recipes memory.

**Trace `update-attrs-on-switch`** during a real swap by runtime-patching the var in
`cljs_repl` (capture `curr`/`prev`/`origin-ref`), trigger the UI action, inspect the
buffer, then restore. Runtime patching beats source instrumentation (no recompile).

**Tests:** `thf/dump-file file :keys [...]` prints a shape tree; prefer
production-path helpers (`cls/generate-update-shapes` + `thf/apply-changes`) over ad
hoc map mutation; use `tho/swap-component-in-shape {:keep-touched? true}` for swaps.

> ⚠️ The Serena project root resolves to `/home/penpot/penpot` (devenv container),
> but local file tools use `/home/alotor/kaleidos/penpot/penpot`. Use the right
> prefix per tool.

---

## 11. Sharp edges & areas not obvious from a superficial read

1. **Role overlap.** Variant masters are simultaneously main + root, and copies
   nest. Never assume exclusivity. Bugs hide in code that branches on a single role.
2. **Two clone paths with different cleanliness.** `make-component-instance` produces
   *clean* copies (drops `:touched`/variant attrs); `duplicate-component` /
   `generate-duplicate-*` do **not** — inherited source attrs survive. Identify the
   path before changing sync logic.
3. **`update-attrs` vs `update-attrs-on-switch` are different functions** with
   different guard sets. Fixing one doesn't fix the other.
4. **Composite geometry (`:selrect`, `:points`) bypasses the simple
   different-master skip** in swap; width/height checks catch some but not all
   positional mismatches. Classic source of "swap moved/resized my shape".
5. **`previous-shape` may be repositioned** (destination-root minus origin-root)
   before copy. Often zero for variant switch — do **not** assume zero for other swap
   entry points (Plugin API, multi-swap).
6. **Inherited touched via ref chain.** `add-touched-from-ref-chain` makes a locally
   untouched shape behave as touched. Check the *effective* touched set, not the
   stored one.
7. **Cross-file ref chains.** `:shape-ref` and `find-remote-shape` walk across remote
   libraries. A missing/unlinked library makes the main "not found" and sync silently
   no-ops (`generate-sync-shape-direct` returns `changes` unchanged). Distinguish
   "no-op because unlinked" from "no-op because of a bug".
8. **Swap slots** are the only thing that keeps a swapped sub-instance syncing
   correctly. If a swap slot is missing/duplicated, expect `:component-duplicate-slot`
   or wrong-position syncs. See `find-swap-slot`, `match-swap-slot?`.
9. **The main instance lives in a page, referenced indirectly** by
   `:main-instance-id`/`-page`. Moving/deleting/duplicating pages or the main shape
   can desync the component record → `:invalid-main-instance-*`.
10. **Media references are remapped asynchronously on the backend** after
    instantiation, not at clone time. A freshly instantiated copy may have
    "unreferenced" fills/strokes until the backend fixes them — don't "fix" this in
    the clone path.
11. **`generate-sync-file` early-return & `concat-changes`** carry a `TODO Remove
    concat changes`; the change-set plumbing here is known-awkward.
12. **`set-translation? true`** marks a change set translation-only so sync skips
    expensive work — a wrong/missing flag changes whether sync runs.
13. **Touched second pass.** Change application does a *second pass* for collected
    touched changes (`process-touched-change`), which can mark a component modified
    from a plain shape op. See `mem:common/file-change-validation-migration-subtleties`.
14. **Tokens interact with sync** (`generate-update-tokens` inside `update-attrs`).
    Token application/propagation has its own subtleties — see
    `mem:frontend/workspace-token-subtleties`, `mem:common/tokens-schema-subtleties`.

---

## 12. Where to start, by symptom

| Symptom | Start here |
|---|---|
| Override lost / overwritten on sync | `update-attrs` + `:touched`/`sync-attrs`; check `omit-touched?` and the touched group. |
| Swap moves/resizes/loses overrides | `update-attrs-on-switch` guards; `generate-keep-touched`; composite geometry skip. |
| "Update main" doesn't propagate | `generate-sync-shape-inverse` / `find-remote-shape`; was component marked modified? |
| Copy doesn't update from main | `generate-sync-shape-direct`; `direct-copy?`; is the library linked? |
| Validation errors on save | `validate.cljc` error code → the specific `check-*` fn; then `repair-file`. |
| Variant switch wrong child | `find-shape-ref-child-of`, `add-touched-from-ref-chain` in `variants.cljc`. |
| New attr not syncing | add it to `ctk/sync-attrs` with the right group. |
| Nested / fostered / swapped child weirdness | ref-chain walkers in `types/file.cljc`; swap slots in `ctk`. |

---

## 13. Authoritative references

Serena memories (read before deep work — they are the project's primary guidance):
`mem:common/component-data-model`, `mem:common/component-swap-pipeline`,
`mem:common/component-debugging-recipes`, `mem:common/changes-architecture`,
`mem:common/file-change-validation-migration-subtleties`,
`mem:common/data-model-change-checklist`, `mem:common/core`, `mem:frontend/core`.

The canonical behavioral spec is the test suite under
`common/test/common_tests/logic/` — read neighboring tests before adding a case.

---

## 14. Findings — correctness / stability / performance audit

The findings from auditing this subsystem have been split into self-contained,
independently-actionable bug reports under
[`component-bug-reports/`](./component-bug-reports/) (see its `README.md` for the
index, suggested order, and severity table). Summary:

| Report | Title | Category | Confidence |
|---|---|---|---|
| BUG-01 | `concat-changes` accumulates `:undo-changes` as nested lazy `concat` | Stability + Perf | 🟢 High |
| BUG-02 | Un-memoized ref-chain walking during variant switch | Perf | 🟡 Medium |
| BUG-03 | `compare-children` fallback is O(n²) with an expensive constant | Perf | 🟡 Medium |
| BUG-04 | `update-attrs-on-switch` composite-geometry guard audit | Correctness (audit) | 🔵 Low |
| BUG-05 | Silent no-op vs. bug ambiguity in direct sync | Diagnosability | 🔵 Low |
| BUG-06 | `d/index-of` linear scan inside per-child sync callbacks | Perf | 🟢 High |
| BUG-07 | Inverse sync re-maps the entire change list at every tree node | Perf + Stability | 🟢 High |
| BUG-08 | `variant-switch` crashes on empty / out-of-range target (plugin-facing) | Correctness | 🟢 High |

Each report is self-contained (onboarding pointers, affected code, root cause, fix
direction, reproduction, test guardrails, acceptance criteria) so it can be picked up
cold in a separate context. Update a report's status there as it is fixed.
