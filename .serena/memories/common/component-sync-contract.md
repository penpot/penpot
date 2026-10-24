# Component Touched/Sync Contract

Intended semantics of copies, `:touched` and component sync. This is the spec: where code disagrees, the code is the suspect, not the contract. Data model and ref chains: `mem:common/component-data-model`. Swap/switch internals: `mem:common/component-swap-pipeline`.

## Definitions

- Near main: the shape a copy shape's `:shape-ref` points at (inside the main of the closest enclosing copy head). Sync, reset and the override rule compare against it.
- Touched group: a `ctk/sync-attrs` group in `:touched`, plus the text groups (see Text overrides) and `:swap-group`. Meaning: the user authored this group's value on this shape, so sync must not overwrite it. `:swap-group` on a copy sub-head: the user chose its component, so sync must not put the near main's back.
- Authored write: a value the user set on that shape: sidebar edit, direct canvas transform of that shape, text content edit, explicit rename, applying a token to it.
- Derived write: a value computed from other shapes or from measurement: children placed by a transformed parent, constraint resize, layout reflow, text auto-grow, `:position-data`, automatic naming, token value propagation, library color/typography sync, repair, sync itself. Derived writes never touch.
- Expected geometry of a copy shape: the near main's geometry placed relative to the copy's parent and resized through the shape's constraints (`app.common.geom.shapes.constraints`), with layout-owned and grow-owned dimensions left to layout/measurement.

## Override rule (holds once sync has settled)

- Different means touched: a group whose value differs from the near main (geometry: from the expected geometry) must be touched. "Differs" uses `gsh/close-attrs?`: numbers, rects, points, position data with a tolerance (1 px for geometry); `:transform`/`:transform-inverse` with `gmt/close?`; path content segment by segment.
- Equal does not mean untouched: a group set back to the main's value stays touched. Only reset, update-main, detach, swap or variant switch clear groups; value equality never does.
- Only authored writes on copies set groups. Mains are never touched through `set-shape-attr`.
- The swap is the only structural override: copy children always follow the main's structure (add, delete, order), and a child with `:swap-group` keeps its own component in that position.

## Per operation

- Edit on a copy shape (authored): sets the attr's group (text content: `:content-group` + the text groups it changes), clears `:remote-synced`. Descendants moved/resized only because of it stay untouched.
- Rename: an explicit rename touches `:name-group` on root or child. Automatic renames (text name following its content) are derived: the editors write the name with `:ignore-touched #{:name}`, and sync gives an untouched copy text the name of its own letters when the main's name is automatic. Untouched names sync from the near main; a touched root name on update-main renames the component.
- Edit on a main: no touched change on the main; triggers sync.
- Sync main→copy: every untouched group gets the near main's value (geometry: expected geometry); touched groups keep their values; never sets or clears `:touched` during attribute sync. If a main detaches a nested head, its images become ordinary shapes and lose their now-invalid `:swap-group`. Main child added → added clean to copies. Main child deleted → copy child removed, overrides or not. Main reorder → copies follow. A swapped sub-head pairs with the main child it replaces (its `:shape-ref`) and takes only that child's placement (position, rotation, flips, constraints, `layout-item-*`); everything else syncs from its own component's main. A main child deleted → its swapped copies go with it. A swap made in the main reaches copies without `:swap-group` and spares those with it.
- Sync cascade is eager: one sync runs to a fixpoint in memory (copies of A, including the A copy in B's main → copies of B → …) and commits once in the triggering op's undo group. The file is never left partly synced. Syncing only the components reported as changed equals a full-file sync. Code: `cll/generate-sync-file-cascade` (first pass as `generate-sync-file-changes`, then `generate-sync-components-cascade` over `cll/changed-components` of each pass); the frontend `sync-file` commits it to the synced file, and a library file's sync then syncs the current file's copies of the library components its cascade changed.
- Reset: the selected head's subtree becomes equal to its near main (expected geometry); all its touched groups cleared, `:swap-group` included: a swapped head turns back into the near main's component in place, keeping its id. Nothing outside that subtree changes.
- Update main (from a top copy head): the copy's differing values go to the main; the copy's touched cleared; other copies then sync and keep their own overrides. A swapped child replaces the main child it stands for, and neither keeps `:swap-group`. The main's authored groups are unioned with the copy's even on a repeated update; `:remote-synced` records an earlier update and must never clear those groups (F114).
- Detach: the subtree loses component links, `:touched`, `:remote-synced`. First-level nested heads stay copies, move one nesting level up and inherit the near main's touched groups, so values their old near main overrode become overrides. A swapped head left with no enclosing copy becomes a plain copy of its own component (`:shape-ref` = its main root, no `:swap-group`).
- Instantiate / nest a copy: the new subtree has no touched groups.
- Duplicate a copy: the new subtree keeps the source's values and touched; if its nesting level changes, touched is inherited as in detach. Paste and move out of a copy follow the same rule.
- Swap without keep-touched: the new subtree keeps `swap-keep-attrs` values; the new head keeps the old shape's id. A retained rotation or flip that differs from the new component's expected placement becomes a `:geometry-group` override so later sync preserves it, including orientation inherited from the old component. The swap authors that choice. Size-only overrides clear. Orientation inherited from the position source or
  the parent stays untouched, including references into deleted mains (F154). Other content overrides clear. Inside a copy it keeps the old `:shape-ref` and gets `:swap-group` (none when the new component is the near main's); inside a main it is a plain nested copy.
- Variant switch with keep-touched: an attr is carried from the old copy only when its group is touched and the old and new mains hold the same value for it. Resulting touched = groups of carried attrs (+ `:swap-group` when the head is inside a copy). The root also keeps a retained rotation or flip as a geometry override when it differs from the new expected placement, including orientation supplied by the old component (F146, F175). Placement still supplied by an enclosing copy remains inherited when no root geometry override was carried. A value equal to the new main is not carried and not touched. Texts: the same per text group (letters carried only when both mains have the same letters; a style group when both mains agree on it once placed on each other's letters).
- Delete component: copies keep their data. The deleted main cannot be edited, so it starts no sync, but changes from components nested in it still pass through: sync updates its stored shapes (`:objects` of the deleted component), its copies, and the components that nest it. Restore brings back an up-to-date main.
- Undo/redo: restores the exact prior state, `:touched` and `:remote-synced` included, of the op and the sync it caused together (same undo group, one step). Undo never needs a re-sync.

## Text overrides

- A text is letters (with line breaks) and a style per letter, all in `:content`. Its groups: `:text-content-text` (letters and line breaks) and one per style unit: `-family` (family, font id), `-weight` (weight, style, variant id), `-size`, `-line-height`, `-spacing`, `-decoration`, `-case`, `-fills`, `-typography` (typography reference), `-align`, `-direction` (paragraphs), `-valign` (root). Any of them implies `:content-group`. Splitting runs by styling is a style change, never a text override; an override covers the whole text, not single letters.
- Touch: compare old and new content. Letters differ -> `:text-content-text`. A style unit is touched when a shared letter changes its value or, if the letters changed, when a value appears that the text did not have (typed letters take a neighbour's style and touch nothing). A token on the copy touches the units of the attributes it sets.
- Merge (sync, and switch with the carried units): letters from the copy if overridden, else from the main. Each unit from the copy if overridden, else from the main (the source), placed on the result letters: (1) a source with one value across the text applies to every letter; (2) else letters both texts share (stretches of 3+ equal letters, by a letter diff) take the source's value; (3) else the value of the letter before, across line breaks; letters at the start take the first shared letter after them; with nothing shared, the source's first letter. Paragraph units follow the paragraph of the first shared letter, else the paragraph before. Adjacent runs with equal styles join.
- Typography reference: kept on the result only when every unit the typography sets comes from the same side as the reference; a run that loses it still follows typography updates through its main.
- Family and weight from different sides: sync keeps the weight side's variant id; the frontend then picks the closest variant of the family.
- Library color and typography syncs are derived writes: they never touch.

## Geometry ownership

- Copy root: position is its own; rotation/flip belong to it only when its `:geometry-group` is touched. Resizing it touches the root only.
- Content scaling is distinct from constraint resize. `:content-scale`
  stores the accumulated scale (default 1); expected placement scales
  styles by copy/main scale before syncing. Descendants remain untouched;
  later main style edits still reach them at that scale. Reset restores
  the near main's scale together with its geometry and styles.
- Children of a resized or transformed shape: derived (expected geometry), untouched, and still follow later main edits. Code: a transform touches only the shapes the user transformed (`clp/calculate-ignore-tree` takes their ids; the frontend keeps them in `:workspace-modifiers-ids`); sync places the main subtree of a copy shape with its own geometry like that shape before walking its children.
- Layout-owned (never touched; sync writes, then reflow): x/y of non-absolute children of flex/grid frames; width/height on axes sized fill, or auto on a layout frame.
- Grow-owned (never touched; sync writes, then re-measure on SVG and WASM): width+height of auto-width text, height of auto-height text. Every editor and resize path writes the measured size as derived; on WASM `sync-file` re-measures the auto-grow texts of the current page it wrote (texts on other pages keep the synced size until measured).
- `set-shape-attr`'s `ignore-geometry` does not cover `:width`/`:height`; a derived size write needs `ignore-touched` (transforms get it from the ignore tree).
- Repairs are not overrides: `files/repair` writes with `ignore-touched`; only the text repairs set groups, explicitly.

## Component modified marking

- A main is marked modified (`:modified-at`, drives library update prompts) by authored changes and by token-propagated values. `:position-data` regeneration never marks it.

## Change ops

- `{:type :set-touched}` replaces `:touched` wholesale and only on copies (an empty set is dropped). The builders write only it (`pcb/update-shapes` turns a `:touched` change into it); `{:type :set :attr :touched}`, a raw assoc with no copy guard, is still applied for stored changes.
- Builders that write to copies must emit the inverse `:set-touched` in undo (as `pcb/update-shapes` does).
