# Component Touched/Sync Contract

Intended semantics of copies, `:touched` and component sync. This is the spec: where code disagrees, the code is the suspect, not the contract. Data model and ref chains: `mem:common/component-data-model`. Swap/switch internals: `mem:common/component-swap-pipeline`.

## Definitions

- Near main: the shape a copy shape's `:shape-ref` points at (inside the main of the closest enclosing copy head). Sync, reset and the override rule compare against it.
- Touched group: a `ctk/sync-attrs` group in `:touched`, plus text subgroups `:text-content-text|attribute|structure` and `:swap-slot-<uuid>`. Meaning: the user authored this group's value on this shape, so sync must not overwrite it.
- Authored write: a value the user set on that shape: sidebar edit, direct canvas transform of that shape, text content edit, explicit rename, applying a token to it.
- Derived write: a value computed from other shapes or from measurement: children placed by a transformed parent, constraint resize, layout reflow, text auto-grow, `:position-data`, automatic naming, token value propagation, library color/typography sync, repair, sync itself. Derived writes never touch.
- Expected geometry of a copy shape: the near main's geometry placed relative to the copy's parent and resized through the shape's constraints (`app.common.geom.shapes.constraints`), with layout-owned and grow-owned dimensions left to layout/measurement.

## Override rule (holds once sync has settled)

- Different means touched: a group whose value differs from the near main (geometry: from the expected geometry) must be touched.
- Equal does not mean untouched: a group set back to the main's value stays touched. Only reset, update-main, detach, swap or variant switch clear groups; value equality never does.
- Only authored writes on copies set groups. Mains are never touched through `set-shape-attr`.
- No structural override exists: copy children always follow the main's structure (add, delete, order). Swap slots are the only per-child structural state.

## Per operation

- Edit on a copy shape (authored): sets the attr's group (text content: `:content-group` + diff subgroups), clears `:remote-synced`. Descendants moved/resized only because of it stay untouched.
- Rename: an explicit rename touches `:name-group` on root or child. Automatic renames (text name following its content) are derived. Untouched names sync from the near main; a touched root name on update-main renames the component.
- Edit on a main: no touched change on the main; triggers sync.
- Sync main→copy: every untouched group gets the near main's value (geometry: expected geometry); touched groups keep their values; never sets or clears `:touched`. Main child added → added clean to copies. Main child deleted → copy child removed, overrides or not. Main reorder → copies follow. Swapped sub-heads pair by slot and sync from their own component.
- Sync cascade is eager: one sync runs to a fixpoint in memory (copies of A, including the A copy in B's main → copies of B → …) and commits once in the triggering op's undo group. The file is never left partly synced. Syncing only the components reported as changed equals a full-file sync.
- Reset: the selected head's subtree becomes equal to its near main (expected geometry); all its touched groups cleared, swap slots included (swaps undone). Nothing outside that subtree changes.
- Update main (from a top copy head): the copy's differing values go to the main; the copy's touched cleared; other copies then sync and keep their own overrides.
- Detach: the subtree loses component links, `:touched`, `:remote-synced`. First-level nested heads stay copies, move one nesting level up and inherit the near main's touched groups, so values their old near main overrode become overrides.
- Instantiate / nest a copy: the new subtree has no touched groups.
- Duplicate a copy: the new subtree keeps the source's values and touched; if its nesting level changes, touched is inherited as in detach.
- Swap without keep-touched: the new subtree is clean; the root gets a swap slot only when inside a component and keeps `swap-keep-attrs` values.
- Variant switch with keep-touched: an attr is carried from the old copy only when its group is touched and the old and new mains hold the same value for it. Resulting touched = groups of carried attrs (+ swap slot). A value equal to the new main is not carried and not touched.
- Delete component: copies keep their data and do not sync. Restore brings sync back.
- Undo/redo: restores the exact prior state, `:touched` and `:remote-synced` included, of the op and the sync it caused together (same undo group, one step). Undo never needs a re-sync.

## Geometry ownership

- Copy root: position is its own; rotation/flip belong to it only when its `:geometry-group` is touched. Resizing it touches the root only.
- Children of a resized or transformed shape: derived (expected geometry), untouched, and still follow later main edits.
- Layout-owned (never touched; sync writes, then reflow): x/y of non-absolute children of flex/grid frames; width/height on axes sized fill, or auto on a layout frame.
- Grow-owned (never touched; sync writes, then re-measure on SVG and WASM): width+height of auto-width text, height of auto-height text.
- `set-shape-attr`'s `ignore-geometry` does not cover `:width`/`:height`; a derived size write needs `ignore-touched` or per-dimension exclusion.

## Component modified marking

- A main is marked modified (`:modified-at`, drives library update prompts) by authored changes and by token-propagated values. `:position-data` regeneration never marks it.

## Change ops

- `{:type :set-touched}` replaces `:touched` wholesale and only on copies. `{:type :set :attr :touched}` is a raw assoc with no copy guard; prefer `:set-touched`.
- Builders that write to copies must emit the inverse `:set-touched` in undo (as `pcb/update-shapes` does).
