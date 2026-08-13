---
title: 3.12. Branching
desc: Learn how Penpot file branching works — isolated file copies, a three-way entity-level merge engine, conflict resolution, the RPC API and the workspace UI. View Penpot's technical guide for self-hosting, configuration and developer insights.
---

# File branching

Branching lets a user take an isolated, editable copy of a file (a **branch**),
work on it without touching the original (**main**), and later bring the changes
back through a **merge**. It is conceptually the same idea Git offers for source
code, applied to design files: the workspace even reuses a small set of `git-*`
icons (`git-branch`, `git-branch-plus`, `git-commit`, `git-merge`) to make the
mental model obvious.

The whole feature is gated behind the `:branching` product flag and was added in
the `2.16` API generation (all the RPC methods carry `::doc/added "2.16"`).

## Conceptual model

Branching always reasons about **three** versions of the same file:

 * **branch** — the isolated working state the user edits. A branch is
   **not a stored copy**: it is a reference to the merge base plus an
   append-only **op log** (`file_branch_change`), one change vector per
   branch save. The branch's `:data` is *derived* on every read by
   replaying that log over the base. Its file row still exists (the
   frontend keys on file ids), but it stores no data payload.
 * **merge base** — a frozen snapshot of main taken at the exact moment the
   branch was created.

Comparing two versions is not enough to decide who is right when a value
differs. With the third (the base) the engine can tell *who* changed *what*:

 * if main still equals the base, then the **branch** is the one that changed it;
 * if the branch still equals the base, then **main** is the one that changed it;
 * if **both** changed it, to different values, that is a **conflict** and a
   human must pick a side.

This is the classic **three-way merge** algorithm, applied at the granularity of
individual *entities* (a color, a shape, a typography, a token, …) rather than to
the file blob as a whole.

A second property makes the whole thing possible: the branch shares the
**internal `:data` ids** of main. Because a branch state starts as
main's state, "color X" in the branch and "color X" in main are the same
entity with the same id, and the engine can pair them up and diff them.
Only the ids created *on* the branch (new shapes, components, media)
carry branch-local ids; `remap-refs` normalizes those before diffing
and merging. Without stable ids an id-based merge would be impossible.

## Where the code lives

The feature spans the three subsystems. The core, shared logic lives in
`common` so it can run identically in the JVM and in JavaScript test runners.

```text
common/src/app/common/files/branch_merge.cljc          ; three-way diff + merge->changes engine
backend/src/app/rpc/commands/files_branch.clj          ; RPC commands (create/list/diff/merge/update)
backend/src/app/binfile/common.clj                     ; branch :data derivation (get-file* / branch-file-data)
backend/src/app/rpc/commands/files_update.clj          ; branch save routing (persist-branch-file!)
backend/src/app/migrations/sql/0155-add-file-branch-table.sql  ; schema (file_branch table + is_branch)
backend/src/app/migrations/sql/0157-add-file-branch-change-table.sql ; op log (file_branch_change)
frontend/src/app/main/data/workspace/branches.cljs     ; frontend state + RPC calls (ptk events)
frontend/src/app/main/ui/workspace/sidebar/branches.cljs       ; panels & dialogs
frontend/src/app/main/ui/dashboard/branches_popover.cljs       ; dashboard file-card popover
```

Tests:

```text
common/test/common_tests/files_branch_merge_test.cljc  ; unit tests for the diff/merge engine
backend/test/backend_tests/rpc_file_branch_test.clj    ; integration tests for the RPC commands
```

The branching commands closely mirror the patterns in
`app.rpc.commands.files-snapshot`, and reuse the production change pipeline
(`app.common.files.changes/process-changes`), the file snapshot machinery
(`app.features.file-snapshots`), and the file read/save paths
(`app.binfile.common`, `app.rpc.commands.files-update`).

## Data model

Two migrations add the branching schema: one column and one table on top of
the existing `file` machinery, plus the op log table.

```sql
ALTER TABLE file
  ADD COLUMN is_branch boolean NOT NULL DEFAULT false;

CREATE TABLE file_branch (
  id               uuid PRIMARY KEY,
  created_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at       timestamptz NOT NULL DEFAULT clock_timestamp(),

  branch_file_id   uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE DEFERRABLE,
  source_file_id   uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE DEFERRABLE,

  base_snapshot_id uuid NULL,

  -- main's revn when the base was (re)positioned
  base_revn        bigint NOT NULL,
  -- the BRANCH file's revn at the same moment (independent counter)
  base_branch_revn bigint NOT NULL DEFAULT 0,

  created_by       uuid NULL REFERENCES profile(id) ON DELETE SET NULL,

  name             text NOT NULL,
  description      text NULL,

  status           text NOT NULL DEFAULT 'open'
                      CHECK (status IN ('open', 'merged', 'archived')),

  merged_at        timestamptz NULL,
  merged_by        uuid NULL REFERENCES profile(id) ON DELETE SET NULL,
  deleted_at       timestamptz NULL
);
```

```sql
CREATE TABLE file_branch_change (
  id          uuid PRIMARY KEY,
  branch_id   uuid NOT NULL REFERENCES file_branch(id) ON DELETE CASCADE DEFERRABLE,
  file_id     uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE DEFERRABLE,
  revn        bigint NOT NULL,
  changes     bytea NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at  timestamptz NULL,
  UNIQUE (file_id, revn)
);
```

Key points:

 * **`is_branch`** on `file` flags the branch file so it is hidden from the
   normal project/team file listings, the dashboard **search**, the team
   **trash** (and its restore path), and project **duplication/binfile
   export** — you don't want every branch mixed in with real files, nor
   restorable zombies without their `file_branch` row. When the *source*
   file is deleted, the `delete-object` cascade propagates the logical
   deletion to its branches (they would otherwise be unreachable forever).
 * **`branch_file_id`** points to the file row that *is* the branch; the row
   carries no data payload, but it anchors permissions, libraries, msgbus
   topics and media ownership. **`source_file_id`** points to main. A
   `UNIQUE` index on `branch_file_id` guarantees a file can be the branch of
   at most one source.
 * **`file_branch_change`** is the branch's op log: one row per branch save
   (the blob-encoded change vector, `UNIQUE (file_id, revn)`). The branch's
   `:data` is derived by replaying its rows in revn order over the base
   snapshot. Rows are replaced (squashed) by `update-branch-from-main` and
   cascade away when the branch is deleted.

 * **`base_snapshot_id`** references the merge-base snapshot. **`base_revn`**
   records MAIN's revision number and **`base_branch_revn`** the BRANCH file's
   one at the moment the base was (re)positioned — the two files carry
   independent revn counters, so each side's cheap "did anything change?" gate
   compares against its own counter (see *ahead/behind* below). The migration
   is intentionally idempotent (`IF NOT EXISTS` everywhere) so instances that
   applied it under its old development name pick up new columns on re-apply.
 * **`status`** moves through `open` → `merged` (after a successful integration)
   or `open` ↔ `archived` (a reversible "hide it" state). Logical deletion is
   handled the standard Penpot way via `deleted_at` rather than a hard delete.

## Lifecycle of a branch

### Creating a branch

The `::create-file-branch` command (`files_branch.clj`) refuses to branch a
branch (`:cannot-branch-a-branch`) and runs everything inside a single
transaction (with `SET CONSTRAINTS ALL DEFERRED`, because the new file and
its branch row reference each other) **under the file's advisory lock**
(`db/xact-lock!`, the same one update-file takes), so the base snapshot and
the branch's initial state (the empty op log over that base) are guaranteed
to come from the SAME state of main — a concurrent save between the two
would otherwise make the base differ from the branch's initial content and
manufacture phantom diffs later:
 1. **Materialize the merge base.** `fsnap/create!` takes a *system* snapshot of
    main labelled `branch-base/<name>`, with a `deleted-at` 3650 days in the
    future. The far-future deletion date keeps the snapshot from being pruned by
    the snapshot GC while the branch is open — the diff needs it for as long as
    the branch lives. The pin is **released** (rescheduled with the team's
    normal deletion delay) whenever the branch is merged, deleted or its base
    is repositioned, so closed branches do not leak multi-year snapshots. If
    the snapshot can ever not be resolved, merge/update/diff **refuse** with
    `:base-snapshot-missing` — silently falling back to main would degrade the
    three-way merge into "branch overwrites main" without conflicts.
 2. **Create the branch file row.** An empty file row (`ctf/make-file` with
    no pages) carries main's project, features (minus the `fdata/*` ones, the
    derived data is plain maps) and data version. No data, no media, no
    library copy: the read path derives the state, and the op log starts
    empty, so the branch's initial content equals the base by construction.
 3. **Mark the new file as a branch** (`is_branch = true`) so it stays out of
    the listings, grant the creator ownership (`file_profile_rel`, the same
    grant the old duplicate path made), and copy main's `file_library_rel`
    rows so shared-library components keep resolving on the derived state.
 4. **Insert the `file_branch` row** with `status = 'open'`, the base snapshot
    id and `base_revn = base_branch_revn = (:revn file)`.

Before any of this it enforces edition permissions on main and checks two quotas:
`::quotes/branches-per-file` and `::quotes/branches-per-team` (configurable via
`PENPOT_QUOTES_BRANCHES_PER_FILE` / `PENPOT_QUOTES_BRANCHES_PER_TEAM`,
registered in `app.config`).

On the frontend, `create-branch` (in `branches.cljs`) first force-persists any
pending local changes, then calls `:create-file-branch`, so the branch starts
from a fully-saved state of main.

### Working on a branch

Once created, main and the branch evolve independently: main keeps its
stored data, the branch keeps an op log. When a branch file is opened, the
backend derives its state (base + replayed ops) before serving it, and the
frontend fetches its context via `:get-file-branch-info` (events
`fetch-branches` / `fetch-branch-context`) and shows a
`branch-context-banner*` at the top of the workspace indicating "you are on a
branch", together with its *ahead* / *behind* counts and the merge / update
actions.

### Ahead / behind counts

`::get-file-branches` lists a file's branches and annotates each with
entity-level `ahead` / `behind` / `conflicts` counts — the same numbers the
compare dialog shows.

Computing these means running the full three-way diff, which is expensive, so
there is a two-level gate (`branch-diff-counts`):

 * Each file carries a monotonically increasing `revn`. The command first
   computes cheap revn deltas: `ahead-revn = branch_revn - base_branch_revn`,
   `behind-revn = source_revn - base_revn` (`revn-deltas`) — each side against
   its OWN counter; mixing them breaks after an update-from-main repositions
   the base.
 * If **both deltas are zero**, the branch is in sync and the diff is skipped
   entirely (`[0 0 0]`).
 * Otherwise main's `:data` is realized **once** and shared across all
   branches of the same source, while each diverged branch's state is
   **derived** (base snapshot + op log replay) on demand. `ahead` is the
   count of clean changes in the `:branch->main` direction, `behind` the
   count in the reverse direction, and `conflicts` is only non-zero when
   *both* sides diverged. Only **open** branches are diffed (merged/archived
   ones report `[0 0 0]`), and a listing never fails because of one corrupted
   branch: count errors degrade to zeros with a warning (the merge itself
   still refuses loudly).

The computed `[ahead behind conflicts]` summary is cached in-memory
(`files-branch::branch-summary-cache`) keyed by
`(base-snapshot-id, source-revn, branch-revn)`. The key names exactly the
inputs the computation reads, so a stale entry cannot exist and the cache
needs no invalidation logic: a cache miss is the cold path, and a repeated
listing with no intervening save performs no comparison work. The cache
bounds memory (`max-size`, `keepalive`) and never decides correctness, and
a failed computation is not stored, so a broken branch keeps degrading to
zeros with a warning on every listing.

## The three-way diff engine

`app.common.files.branch-merge` is the heart of the feature. It is a pure,
side-effect-free engine: it reads three `:data` blobs and returns a serializable
summary. It never mutates anything.

### Bounding the branch side to what the op log touched

`compute-merge` accepts `{:only-pages #{page-id …}}`, and every page pass
honours it, the presence pass included (that one strips the shapes of every
page it looks at, which is most of what a whole-file comparison costs).

The caller that supplies it is `files-branch::branch-affected-pages`. It reads
the branch's op log (`binfile::get-branch-changes`) and folds
`validate::extract-affected-ids` over it, which is sound here for a reason
specific to this storage model: a branch's `:data` **is** the base snapshot
plus that log, so a log touching only those pages cannot differ from base
anywhere else. The forward pass (the branch's own changes) is bounded; the
reverse pass reports MAIN's changes and stays whole-file, because nothing on
this side knows which pages main touched.

The bound is dropped, and the comparison stays whole-file, whenever the log
holds one change the reducer cannot scope to a page or a component: a page or
component deletion, a page reorder, a colour, a typography, a token, or
file-level plugin data. That is not a nicety. Those omissions are safe when
the set bounds a *validation* and unsafe when it bounds a *diff*, so the
precondition is checked per change (`scoped-change?`) rather than assumed.

Measured on a 21,169-shape file with a one-page edit: the comparison drops
from 402 ms to 3 ms and touches 1 page instead of 48, while reading the op
log and folding it costs 1 ms. The rest of a compare is realizing the two
sides plus the base, which pruning does not address.

One consequence to know about: after `update-branch-from-main`, the squashed
log is value-derived and re-emits most of the file, so those branches fall
back to the whole-file comparison.

### `three-way-entities` — the per-entity truth table

`three-way-entities` diffs one indexed collection (`id -> value`) across
base / theirs / ours and returns `{:changes [...] :conflicts [...]}`. For each id
it applies this decision table (`theirs` is main, `ours` is the branch, in the
default `:branch->main` direction):

| Situation                                   | Outcome                                  |
| ------------------------------------------- | ---------------------------------------- |
| deleted in both                             | nothing                                  |
| deleted in branch, main intact (`= base`)   | clean `:deleted`                         |
| deleted in main, branch intact (`= base`)   | nothing (already gone in main)           |
| deleted in branch / modified in main        | **conflict** `:delete-modify`            |
| modified in branch / deleted in main        | **conflict** `:modify-delete`            |
| new in branch only                          | clean `:added`                           |
| new in main only                            | nothing                                  |
| added on both, same value                   | nothing                                  |
| added on both, different value              | **conflict** `:add-add`                  |
| present in all three, branch `= base`       | nothing (main wins)                      |
| present in all three, main `= base`         | clean `:modified` (branch wins)          |
| present in all three, branch `= main`       | nothing (converged)                      |
| present in all three, all three differ      | **conflict** `:modify-modify`            |

So there are exactly **four conflict reasons**: `:delete-modify`,
`:modify-delete`, `:add-add` and `:modify-modify`. Every conflict descriptor
carries `:base`, `:main` and `:branch` values plus a `:changed-attrs` map
(attribute → `{:main v :branch v}`) so the UI can render a side-by-side diff.

Shapes are diffed **stripped** of their derived attrs (`strip-shapes`:
`:shapes`, `:touched`, `:selrect`, `:points`) and the page root frame
(`uuid/zero`) is ignored. Stripping happens at CLASSIFICATION level, not just
display: this is what prevents false conflicts — both sides adding children to
the same frame only differ on `:shapes`, and a library sync on main only flips
`:touched`; neither is a user change, so neither may turn a clean branch edit
into a modify-modify/delete conflict. Containment (`:parent-id`/`:frame-id`)
is deliberately kept: reparenting is a real, user-meaningful change. The same
principle strips the `:modified-at` bookkeeping stamp from colors,
typographies and components (`strip-modified-at`): the apply pipeline
re-`touch`es them, so after an update-from-main the copied entities would
otherwise differ from main forever.

Pages and token sets get a **content-valued presence pass** (`presence-only`):
presence is diffed over the normalized content instead of a bare marker, so
deleting a page or token set the other side edited raises a proper
`:delete-modify`/`:modify-delete` conflict instead of silently dropping those
edits; content-only modifications are filtered out (the granular passes own
them) and the conflict payloads are slimmed for the wire.

### `compute-merge` — across the whole file

`compute-merge` runs `three-way-entities` over every mergeable collection in the
file and concatenates the results:

 * **colors**, **typographies**, **components** (row metadata), **media** —
   straightforward indexed collections.
 * **pages** (`diff-pages`) — page add/delete, page metadata
   (name/background/grid), per-page guides, flows, default-grids, plugin-data,
   and the shapes (`:objects`) of every common page. Residual page attributes
   that no pass handles are surfaced under the `:page-attrs` kind so they are
   *refused* rather than silently dropped.
 * **tokens** (`diff-tokens`) — token-set add/delete, set rename, set order,
   themes, active theme paths, active-set toggles, and per-token values.

The result is `{:changes [...] :conflicts [...] :stats {...}}`, where `:stats`
counts added / modified / deleted / conflicts. The direction argument swaps which
side is "theirs" and which is "ours": `:branch->main` (merge / compare) vs
`:main->branch` (update from main).

### `remap-refs` — the local-reference fix-up

The derived branch data starts as main's data, so base entities already
reference main's file id and main's media ids. What carries **branch-local**
ids is everything the branch created: components get `:component-file` set to
the branch file id, and media added on the branch gets its own
`file_media_object` rows with fresh ids. If diffed as-is, those entities
would look modified, inflating the diff and conflicts; worse, after a merge
the references could not be resolved in the target file — repair would
silently *detach* components and images would break when the branch file is
GC'd.

`bm/remap-refs [data id-map]` rewrites exactly that local-reference surface
(`:component-file`, `:fill-color-ref-file`, `:stroke-color-ref-file`,
`:typography-ref-file`, shadow/grid `:file-id`, and the media refs), through
an `{old-id -> new-id}` map; ids not in
the map (external libraries) are untouched. The backend builds that map per
operation: the branch file id plus the **media pairs** (`media-pairs`, rows
matched across the two files by storage object + name + dimensions + mtype).
Media *added* on the source side of a merge/update gets fresh ids
pre-allocated before the diff, and its `file_media_object` rows are **copied
into the target file** when the operation applies — so merged shapes always
reference rows owned by the file that outlives the branch.

## Merging a branch into main

`::merge-file-branch` integrates the branch back into main. Only an **editor of
main** may do it (same rule as Figma). The whole operation runs in a transaction
guarded by `db/xact-lock!` on **both** file ids (in stable order, to avoid
deadlocks) — without the branch lock, edits saved to the branch while the merge
runs would be silently lost when the branch is marked merged and deleted.

The flow:

 1. **Load** main, the branch (normalized), and the base (from the snapshot).
 2. **Optimistic concurrency check.** If the caller passed `expected-main-revn`
    and it no longer matches main's `revn`, it raises `:file-modified` ("recompute
    the diff and retry"). The frontend always sends it (taken from the diff's
    `:meta :main-revn`), so resolutions computed against a stale diff can never
    apply; on `:file-modified` it re-fetches the diff and asks the user to
    review again.
 3. **Conflict gate.** `compute-merge` is run; any conflict not resolved in the
    `resolutions` map aborts with `{:status :conflicts :conflicts [...]}`.
 4. **Unsupported gate.** `compute-changes` is run; if it reports any
    `:unsupported` kinds it aborts with `{:status :unsupported :kinds [...]}` —
    nothing is applied, so no change is ever silently lost.
 5. **No-op shortcut.** If there are no changes to apply (the branch already
    matches main), the branch is closed without touching main.
 6. **Apply.** Otherwise it:
     * takes a **safety snapshot** of pre-merge main (`pre-merge/<name>`, with the
       team's normal deletion delay) so the merge can be rolled back via the
       version history;
     * applies the change vector through `cpc/process-changes`, bumping `revn`;
     * **validates** the merged file (`cfv/validate-file`) and, if it finds
       problems, runs `cfr/repair-file` and applies the repair changes;
     * copies the `file_media_object` rows of media added on the branch into
       main, under the pre-allocated ids the changes reference;
     * writes a `file_change` (xlog) row, persists the file
       (`fupd/persist-file!`), marks the branch `merged`;
     * publishes a `:file-merged` message on the msgbus topic of main so every
       open client reloads the file in real time.

Closing the branch (`finish-branch!`) always releases the pinned base
snapshot and, **unless the caller passes `:keep-branch true`**, logically
deletes the branch and its file in the SAME transaction (`delete-branch!`) —
merged copies do not pile up, and there is no client-driven second call that
could be lost mid-way. The `:file-deleted` msgbus message carries the
`session-id`, so the merging client (which is navigating to main on its own)
is not raced into the dashboard by its own deletion.

### Resolutions

Conflict resolutions are a simple map of **entity id → `:main` | `:branch`**.
The id is usually a uuid, but structural conflicts use keyword ids
(`:active-themes`, `:active-sets`, `:page-order`, `:token-set-order` — each
distinct, so resolving one can never accidentally satisfy another). `:branch`
takes the branch side, `:main` (or absent) leaves main untouched. Resolving a
delete conflict to `:branch` really restores: a shape comes back with its whole
surviving subtree, a page with its full contents, a token set with its tokens. The frontend builds this
map through `set-conflict-resolution` (one entity) and `set-all-resolutions`
(bulk), then passes it to the merge command.

## From merge to change ops: `compute-changes`

A diff summary is not directly applicable; it has to become the same kind of raw
change maps the editor produces. `compute-changes` does that translation and
returns `{:changes [...] :unsupported #{...}}`. The RPCs pass it the
`compute-merge` summary they already computed for the conflict gate, so the
full three-way diff is not run a second time just to derive `:unsupported`.

### Mergeable vs. unsupported kinds

`mergeable-kinds` is the source of truth for what can be translated:

```clojure
#{:color :typography :media :shape :token :token-set :token-set-rename :token-set-order
  :token-theme :token-active-themes :token-active-sets
  :page :page-order :page-guide :page-flow :page-grid :page-plugin :component}
```

`unsupported-kinds` returns any kind present in the diff that is *not* in this set
(notably `:page-attrs`, the residual page attributes). When that set is non-empty
the merge is refused — the engine prefers to refuse rather than drop a change it
cannot faithfully reproduce.

### Per-kind translation

`flat-changes` is the shared helper that walks a flat `id -> value` collection and
emits add / modify / delete ops, honouring resolutions (a conflicting entity is
emitted only when its resolution is `:branch`). The concrete op types it produces:

 * **colors / typographies / media** — `:add-color` / `:mod-color` /
   `:del-color`, and the analogous `:*-typography` / `:*-media` ops.
 * **shapes** (`page-shape-changes`) — the most involved pass:
     * modifications become `:mod-obj` with `:set` operations for the attrs that
       differ (structural attrs `:shapes` / `:parent-id` / `:frame-id` are
       excluded — they are applied via moves, not plain sets); per-attr
       resolutions that take a geometry attr also carry the branch's
       `:selrect`/`:points` so position and caches stay consistent;
     * deletions become `:del-obj`;
     * additions become `:add-obj`, emitted in **topological order** so a newly
       added parent is created before its newly added children; a
       modify-delete conflict resolved to `:branch` re-adds the shape's whole
       surviving subtree the same way;
     * reparenting of existing shapes becomes `:mov-objects`, emitted **last**
       (after new containers exist) and ordered by depth so a container moves
       before the shapes moved into it. Reparenting cannot be a `:set :parent-id`
       op — that would leave the shape loose in its old parent and let file repair
       duplicate it.
 * **pages** — `:add-page` (full page incl. objects) / `:del-page`, `:mod-page`
   (name/background/grid), `:mov-page` (reorder), plus per-page `:set-guide`,
   `:set-flow`, `:set-default-grid` and `:set-plugin-data`.
 * **components** — `:add-component` / `:mod-component` / `:del-component` (a
   branch soft-delete, `:deleted true`, is surfaced as a real `:del-component`).
   Components are emitted *before* shapes so `:del-component` can capture the
   main-instance objects before `:del-obj` removes them.
 * **tokens** — `:set-token` (per-token values), `:set-token-set` (create/delete
   and rename), `:move-token-set` (reorder), `:set-token-theme`,
   `:set-active-token-themes`.

## Updating a branch from main

`::update-branch-from-main` is the **reverse** direction: it brings into the
branch the changes main received since the merge base. It needs edition
permissions on the *branch* file, locks the *branch* file id, and applies main's
changes (plus any conflicts resolved to `:main`) into the branch.

It works like a merge with `compute-merge`/`compute-changes` run with the sides
swapped, the same `:conflicts` / `:unsupported` gates, and the same safety
snapshot. Two extra steps at the end keep the op log sound:

 * it **squashes the op log**: the repositioned base already carries
   everything main contributed, so the log is replaced with the net
   branch-only changes (`compute-changes` with base = main's current state
   and branch = the updated branch state). Replaying those over the new base
   reproduces the updated branch exactly, and main-side ops never accumulate
   in the log. If that net cannot be translated into replayable ops the
   update refuses (`:unsupported-update-squash`), so nothing is persisted;
 * it **repositions the merge base**: the base snapshot is moved forward to
   the current state of main (releasing the previous one), and both
   `base_revn`/`base_branch_revn` are refreshed. When the branch carried its
   own changes, `base_branch_revn` is stored one revn short on purpose: the
   new base equals MAIN, so the branch still differs from it even if its
   revn never moves again — the trick keeps the cheap gate open and the diff
   reports the real counts.

UI resolutions arrive in main/branch terms and are inverted server-side
(per-attr maps included) before translation.

## Materializing a branch: the exit door

`::materialize-file-branch` turns a branch into an ordinary file. It takes
the branch FILE id, derives the state once (base snapshot + op log), and in
one transaction under the file's advisory lock:

 * persists that state as the file's own payload through the ordinary write
   path (`files-update::persist-file!`, so the data lands in a `file_data`
   row of type `main` exactly as any other save would),
 * clears `is_branch`, which is the flag both the read path
   (`binfile::get-file*`) and the write path (`files-update::update-file*`)
   switch on, so from that point nothing derives anything,
 * deletes the op log rows, closes any open pull request over the branch,
   releases the pinned base snapshot, and archives the `file_branch` row
   (archived rather than deleted, because the pull requests reference it
   with `ON DELETE CASCADE`).

It is **idempotent** because it keys on the file rather than on the branch
metadata: a file with no live branch row is already materialised, and the
command reports `{:status :materialized :changed false}` without touching
anything.

Two properties are worth stating for whoever removes this feature later.
The materialised file keeps the branch file's feature set, which excludes
`fdata/objects-map` and `fdata/pointer-map` (branch creation removed them),
so it is stored as one payload rather than as an objects map. And the
operation does not bump `revn`: the state a client already holds is the
state that gets persisted.

## Size gates and what each operation records

Before this, nothing in the feature limited size, so the failure mode at
enterprise scale was a timeout: an operation that never returns and a user
who cannot tell whether it is working. Three config keys hold the limits,
and each default is a measured cost divided into a 30 s budget and halved:

| key | default | where it comes from |
| --- | --- | --- |
| `branching-max-shapes` | 50,000 | a merge on 21,169 shapes costs about 6 s, so 0.28 ms per shape |
| `branching-max-pages` | 500 | a whole-file comparison of 48 pages costs 402 ms, so 8.4 ms per page |
| `branching-max-oplog-changes` | 100,000 | one value-derived squash already emits about 19,000 changes |

`check-file-size-limits!` runs on branch creation, compare, merge and
update-from-main, against the realized file; `check-oplog-depth-limit!` runs
on compare, merge and update. A refusal is a `:restriction` whose data
carries `:code`, `:limit`, `:actual` and `:operation`, and whose hint names
what to do instead: split the file, or materialise the branch and keep
working on it as an ordinary file. **No gate truncates anything** — each one
either refuses the whole operation or lets it through.

The listing is deliberately not gated. It renders the branch panel, so
refusing it would take the panel away rather than protect it, and its cost
is bounded by the summary cache instead.

Every branch operation attaches `:branch-operation`, `:branch-outcome` and
`:branch-duration-ms` to its own audit event (`audited`), because the
generic RPC event records who called what and not how long it took, and
duration is the number the first enterprise trial will be asked about. Every
outcome carries them, the no-op merge and the two refusal shapes included.

## RPC API summary

All commands live in `app.rpc.commands.files-branch`, are gated by
`check-branching-enabled!`, and were added in `2.16`.

| Command                       | Kind     | Purpose                                              |
| ----------------------------- | -------- | --------------------------------------------------- |
| `::create-file-branch`        | mutation | Snapshot main + create the branch file and op log   |
| `::get-file-branches`         | query    | List branches with ahead/behind/conflicts counts    |
| `::get-branch-diff`           | query    | Read-only three-way diff (either direction)         |
| `::merge-file-branch`         | mutation | Integrate a branch into main (resolutions, `expected-main-revn`, `keep-branch`) |
| `::update-branch-from-main`   | mutation | Pull main's changes into the branch                 |
| `::materialize-file-branch`   | mutation | Turn a branch into an ordinary file (idempotent)    |
| `::get-file-branch-info`      | query    | Branch metadata when opening a branch file          |
| `::update-file-branch`        | mutation | Rename / edit description                            |
| `::archive-file-branch`       | mutation | Archive / restore a branch                          |
| `::delete-file-branch`        | mutation | Logically delete a branch                           |

## Frontend integration

### State and events

`app.main.data.workspace.branches` holds the state and wraps the RPC calls as
`ptk` events. The relevant pieces:

 * State keys: `workspace-branches` (the list + status), `workspace-branch-diff`
   (the diff result + selected change + resolutions) and `workspace-branch-context`
   (metadata when editing a branch).
 * Events: `fetch-branches`, `create-branch`, `open-branch`, `fetch-branch-diff`,
   `set-conflict-resolution`, `set-all-resolutions`, `merge-branch`,
   `update-branch-from-main`, `fetch-branch-context`, plus `rename-branch`,
   `archive-branch`, `delete-branch`. Each maps to the matching `rp/cmd!` call
   (`:create-file-branch`, `:get-file-branches`, `:get-branch-diff`,
   `:merge-file-branch`, `:update-branch-from-main`, …).
 * `reload-file-window` / `show-merge-result` handle the post-merge UX (reacting
   to the `:file-merged` msgbus notification and showing the result).

### UI components

`app.main.ui.workspace.sidebar.branches` provides the workspace UI:

 * **`branches-toolbox*`** — the sidebar panel listing the current, open and
   archived branches; **`branch-entry*`** is a single branch card with rename /
   archive / delete actions.
 * **`create-branch-dialog*`** — the create modal (name + description).
 * **`branch-compare-dialog*`** — a read-only diff viewer, filterable by category
   (pages / components / colors / tokens) and status (added / modified / deleted).
 * **`branch-conflicts-dialog*`** — the conflict-resolution UI, showing
   base / main / branch side by side and letting the user pick a side per
   conflict.
 * **`branch-context-banner*`** — the top-of-workspace banner shown while editing
   a branch, with the ahead/behind counts and the merge / update actions.
 * `confirm-merge!` / `confirm-update!` guard the irreversible operations with a
   confirmation modal.

`app.main.ui.dashboard.branches_popover` adds a popover to the dashboard file
card so branches can be opened or created without entering the file.

## Testing

The pure engine is covered by `common-tests.files-branch-merge-test`, which
exercises `three-way-entities` and `compute-merge` across the add / modify /
delete / conflict scenarios and can be run in both the JVM and JavaScript
runners. The RPC layer is covered end-to-end by
`backend-tests.rpc-file-branch-test` (create / list / diff / merge). See the
[Unit tests](/technical-guide/developer/common/#unit-tests) section for how to run
them.

## Pull requests

On top of branching, **pull requests** add a review step before the merge:
the author of a branch asks one or more team members (**reviewers**) to
evaluate their changes. The feature is part of branching and is gated
behind the same `:branching` product flag; it lives in:

```text
backend/src/app/migrations/sql/0156-add-file-pull-request-tables.sql
backend/src/app/rpc/commands/files_pull_request.clj
frontend/src/app/main/data/workspace/pull_requests.cljs
frontend/src/app/main/ui/workspace/sidebar/pull_requests.cljs
backend/test/backend_tests/rpc_file_pull_request_test.clj
```

### Model

A pull request is **not** a file copy. The `file_pull_request` row points
to its `file_branch` and to a **pinned review snapshot** of the branch
file (label `pr-review/<title>`, the same ~10-year pin/release mechanics
as the merge-base snapshot). There is at most one open pull request per
branch (partial unique index).

Opening a review (`?pr-id=` on the workspace url) opens the **live
branch file as a completely normal workspace file** — navigable and
editable, so reviewers can try and validate anything (edits go to the
branch, as always) — decorated with the review banner and actions. The
pinned snapshot backs two things instead of the canvas: the cheap
"outdated" gate (`branch_revn > review_revn`) and the prototype
**viewer**: `::get-view-only-bundle` accepts an optional `pr-id` and
serves the snapshot overlay read-only (permissions taken from the target
file, edition stripped; share-links do not apply), reachable from the
review banner's "Try interactions" action.
`::get-pull-request-bundle` serves the same snapshot overlay as a
workspace-shaped file bundle for API consumers; it refuses once the pull
request is closed, so the pinned state stops resolving when the review
ends (closing, merging or deleting the branch releases the pin).

Because the sandbox is pinned, the author can keep editing the branch;
publishing the new state to reviewers is an explicit action
(`::update-pull-request-snapshot`) that repositions the snapshot.
`branch_revn > review_revn` is the cheap "outdated" gate, mirroring the
ahead/behind revn gates.

### Reviews

Reviewers are stored in `file_pull_request_review` (one row per profile,
`pending` / `approved` / `changes-requested`). A verdict records the
`review_revn` it was issued over, so it goes **stale** (not deleted) when
the author publishes newer changes. The aggregate `review-state` is
derived, never stored: current `changes-requested` beats current
`approved`; stale verdicts only count as "in review". Approval is
**informative**: merging keeps requiring edition permissions on main and
goes through the exact same `::merge-file-branch` gates.

Stored status is only `open` → `merged`/`closed` (with reopen). All the
UI states (approved, changes requested, outdated, conflicts) are derived
per request from the reviews and the existing diff-count helpers.

Accepting a pull request is offered from the sandbox banner (and the
sidebar entries) to any user with edition permissions on main, but it is
just an entry point into the exact same branch merge dialog and
`::merge-file-branch` flow — including conflict resolution and the
safety snapshot. Note that merging always integrates the LIVE branch
state: an outdated pull request merges the newest changes, not the
pinned review snapshot.

### Lifecycle hooks

The branch lifecycle closes its pull request: merge marks it `merged`,
branch delete/archive marks it `closed` (see
`close-branch-pull-requests!` in `files_branch.clj`), and the
`delete-object` cascade closes it when the branch file dies through
other paths (the row itself survives as history; it is only logically
deleted when MAIN dies). Reviewers are notified by email
(`review-request` template) and through the dashboard notifications
dropdown (`::get-profile-pending-reviews`: open pull requests where the
profile has no current verdict); `:pull-request-*` msgbus messages on
main's topic keep open clients fresh.

## Current scope and limitations

The feature was built in phases, and the engine refuses anything it cannot yet
reproduce faithfully rather than risk losing work:

 * The merge is **id-based** and assumes base, main and branch share the same file
   data version; explicit data-version normalization before diffing is a later
   refinement.
 * Residual page attributes (`:page-attrs`) are surfaced but **refused**, so a
   merge that touches them returns `{:status :unsupported}`.
 * Comments / comment-thread positions are deliberately **not** migrated across a
   merge (the same choice Figma makes).
 * The merge base snapshot is kept alive for the whole life of the branch; the
   far-future `deleted-at` is what protects it from the snapshot GC.
 * Branch state is **derived** (base snapshot + op log replay) on every read,
   so a long-lived branch replays one blob per save; `update-branch-from-main`
   squashes the log, but there is no standalone compaction yet. The derived
   data also inherits the base's data version, so a branch reads
   `:base-snapshot-missing` loudly if its pin was released.
