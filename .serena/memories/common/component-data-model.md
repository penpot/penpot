# Component and Variant Data Model

## Shape roles relative to components

A shape can occupy multiple roles at once:

1. Master/main instance: defines a component and has `:main-instance true` plus `:component-id`.
2. Copy/non-main instance: produced by instantiating a component and carries `:shape-ref` pointing at the master shape. `(ctk/in-component-copy? shape)` is essentially `(some? (:shape-ref shape))`.
3. Component root: topmost shape of an instance, marked `:component-root true` and carrying surface attrs such as `:component-id` and `:component-file`.

Variant masters are main instances and component roots. Their descendants may themselves be component copies, so master/copy logic must handle nested instances rather than assuming those roles are exclusive.

## :shape-ref chains

`:shape-ref` walks up the inheritance hierarchy and can cross files for remote libraries. `find-ref-shape` and `get-ref-chain-until-target-ref` in `app.common.types.file` follow this chain.

`find-shape-ref-child-of` in `app.common.logic.variants` walks the chain looking for the first ref-shape whose ancestors include a specific parent. Variant switch uses this to locate the equivalent master child in the target variant.

## :touched flags

`:touched` is a set of override-group keywords such as `:geometry-group` and `:fill-group`. It means a copy diverged from its master for attrs in that sync group. A copy text adds `:content-group` plus one group for its letters (`:text-content-text`) and one per style unit (`:text-content-fills`, `-size`, ...; list and merge rules in `mem:common/component-sync-contract`, unit table `cttx/style-units`). Files older than the feature `components/text-overrides` used `:text-content-attribute`/`:text-content-structure` instead; the migration recomputes them against the near main.

The intended per-operation rules (which writes touch, what sync, reset, update-main, detach and switch do to touched groups) are in `mem:common/component-sync-contract`.

`sync-attrs` in `app.common.types.component` maps attrs to groups. `set-touched-group` is the legitimate setter; the central `set-shape-attr` path calls it only for copies and only when ignore flags allow it.

Masters are not normally touched through `set-shape-attr`, but touched flags can appear on master shapes through cloning/duplication paths. `add-touched-from-ref-chain` in `app.common.logic.variants` unions touched flags from ancestors into the copy being processed, so upstream/master touched state can affect downstream switch behavior.

## Swapped heads and positional matching

- A copy sub-head the user SWAPPED to another component keeps `:shape-ref` on the near-main child it replaces (its *position*); `:component-id`/`:component-file` name the component its *content* comes from; the touched group `:swap-group` marks the swap. Its children refer into its own component's main.
- So for a swapped head the position shape (`find-ref-shape`, X in the near main) and the content shape (its component's main root) differ. `ctf/find-ref-shape` gives position (parent heads are tried outermost first); code that needs the content of a swapped head goes through the content helper, never through `get-ref-shape` with the head's own component.
- A copy sub-head whose component differs from its near main's component must have `:swap-group`; one that matches must not.
- A head takes a position only through `ctf/take-position` (swap in a copy, keep-touched switch, update-main into a copy, the `:ref-not-in-near-main` repair): it refers to the position, is swapped iff its component differs, and otherwise its descendants are remapped to the position's descendants. Setting `:shape-ref`/`:swap-group` by hand leaves the children referring to the wrong main, which validation does not catch and the next sync turns into delete + re-add.
- Copy children are paired to main children by `shape-ref` (`is-main-of?`, seek, not index), swapped or not. `find-near-match` (positional) is only a validator/repair heuristic; validity requires membership of the ref among the near-main parent's children, not index equality (`mem:common/file-change-validation-migration-subtleties`).
- Copy child ORDER converges to the main's via the async sync (`moved` branch of `compare-children`); local code must never reorder copy children directly (guards in `:mov-objects`/`:reorder-children`).
- Files older than the feature `components/swap-group` stored the swap as a slot (`swap-slot-<uuid>` touched group, `:shape-ref` on the new component's root); the migration converts them. Shape-level slot helpers: `app.common.files.swap-slots`; ref-walking ones and the conversion: `app.common.files.comp-processors`.

## Cloning paths

`make-component-instance` in `app.common.types.container` produces a clean component copy through `update-new-shape`, dissociating attrs such as `:touched`, `:remote-synced`, `:variant-id`, and `:variant-name` on cloned shapes.

With `:main-instance? true` (library absorption), nested copies keep their
`:touched` and `:remote-synced`; they remain copies inside the new main.

`:content-scale` is an optional positive shape number (absent means 1),
accumulated by scale-content transforms on both renderers. It belongs to
`:geometry-group`, survives clone/serialization, and leaves the data when
the accumulated scale returns to 1. Sync derives styles from the ratio of
the copy's scale to its main's, separately from constraint resizing.
Old files default to 1; historical scale gestures cannot be reconstructed.

`duplicate-component` in `app.common.logic.duplicate` creates a new component master by cloning existing component shapes, setting component metadata, and applying a position delta. It does not have the same clean-copy semantics as `make-component-instance`, so inherited attrs on the source can matter.

When a bug depends on touched state, identify which cloning path produced the shape before changing sync logic.

## Variant containers

A variant container is a frame with `:is-variant-container true`. Its children are variant masters with `:variant-id` pointing at the container and `:variant-name` naming the variant value. Component records in the library carry `:variant-properties`.

Predicates are broad: `ctk/is-variant?` checks `:variant-id` and applies to both variant master shapes and component rows; `ctk/is-variant-container?` checks the container shape flag.

Moving/dropping a shape into a variant container through the move-to-frame path can auto-convert it into a variant via `generate-make-shapes-variant`, which may duplicate the underlying component. Treat drag/drop into variant containers as a component/variant operation, not a plain reparent.

- `ctk/remove-swap-group` removes `:touched` when it removes the final group.
  New main clones must use the same empty-state representation as
  `process-operation :set-touched`; otherwise a later move+undo changes
  `:touched #{}` to a missing field and cannot restore the exact state (F156).

Content ref chains (get-ref-chain-until-target-ref, including touched walks)
take find-content-ref-shape at every link. A swapped intermediate head
follows its own main; collecting its replaced position's content overrides
creates phantom groups in copies of that swap (F165). Position advancement
still uses advance-shape-ref and find-ref-shape.

A swapped head pushed into a main can become a plain nested copy of its own
component. If the old position supplied rotation or flips that differ from
that component's expected parent placement, the new nested copy needs a
geometry-group override. Otherwise a later sync discards the orientation
that update-main just pushed (F168). The source copy clears its overrides.
