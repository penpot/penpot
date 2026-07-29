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

`:touched` is a set of override-group keywords such as `:geometry-group`, `:fill-group`, and `:text-content-group`. It means a copy diverged from its master for attrs in that sync group.

`sync-attrs` in `app.common.types.component` maps attrs to groups. `set-touched-group` is the legitimate setter; the central `set-shape-attr` path calls it only for copies and only when ignore flags allow it.

Masters are not normally touched through `set-shape-attr`, but touched flags can appear on master shapes through cloning/duplication paths. `add-touched-from-ref-chain` in `app.common.logic.variants` unions touched flags from ancestors into the copy being processed, so upstream/master touched state can affect downstream switch behavior.

## Cloning paths

`make-component-instance` in `app.common.types.container` produces a clean component copy through `update-new-shape`, dissociating attrs such as `:touched`, `:variant-id`, and `:variant-name` on cloned shapes.

`duplicate-component` in `app.common.logic.libraries` creates a new component master by cloning existing component shapes, setting component metadata, and applying a position delta. It does not have the same clean-copy semantics as `make-component-instance`, so inherited attrs on the source can matter.

When a bug depends on touched state, identify which cloning path produced the shape before changing sync logic.

## Variant containers

A variant container is a frame with `:is-variant-container true`. Its children are variant masters with `:variant-id` pointing at the container and `:variant-name` naming the variant value. Component records in the library carry `:variant-properties`.

Predicates are broad: `ctk/is-variant?` checks `:variant-id` and applies to both variant master shapes and component rows; `ctk/is-variant-container?` checks the container shape flag.

Moving/dropping a shape into a variant container through the move-to-frame path can auto-convert it into a variant via `generate-make-shapes-variant`, which may duplicate the underlying component. Treat drag/drop into variant containers as a component/variant operation, not a plain reparent.