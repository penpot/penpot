# Component Swap and Variant Switch Pipeline

## Entry points

Frontend entry points under `frontend/src/app/main/data/workspace/`:
- `variants.cljs`: `variants-switch` and `variant-switch` events feed property-toggle UI and Plugin API `switchVariant` behavior into `dwl/component-swap`.
- `libraries.cljs`: `component-swap` is the single-swap workhorse; `component-multi-swap` batches swaps and calls `component-swap` with `keep-touched? = false`.

`keep-touched? = true` is the discriminator for preserving user overrides during variant switch. Batch/multi-swap paths intentionally bypass that logic.

## Common-side pipeline

For a single swap with `keep-touched? = true`:

1. `cll/generate-component-swap` in `common/src/app/common/logic/libraries.cljc` builds the base changes: remove old shape and instantiate the target component in its place through `generate-new-shape-for-swap`, `generate-instantiate-component`, and `make-component-instance`.
2. `clv/generate-keep-touched` in `common/src/app/common/logic/variants.cljc` walks pre-swap children, augments each with chain-derived touched flags through `add-touched-from-ref-chain`, finds the equivalent target shape through `find-shape-ref-child-of`, then calls `update-attrs-on-switch`.
3. `update-attrs-on-switch` in `app.common.logic.libraries` decides which touched attrs from the previous shape should be copied onto the freshly instantiated target shape.

## update-attrs-on-switch hazards

The routine compares `current-shape` (fresh target copy), `previous-shape` (pre-swap shape with chain-derived touched), and `origin-ref-shape` (source variant master's equivalent shape). It loops over sync attrs except `swap-keep-attrs` and copies only attrs that pass several guards:

- skip equal previous/current values;
- skip equal composite geometry for selected attrs;
- require the corresponding touched group;
- for most attrs, require source and target masters to agree;
- for fixed-size selrect/points/width/height, use dedicated fixed-layout geometry handling;
- text and path shapes have specialized value conversion paths.

The generic fallback branch copies from `previous-shape`. It represents the intended "carry user override through switch" behavior, but bugs usually appear when guards fail to reject incompatible geometry or master differences before reaching that branch.

## Known sharp edges

- Composite `:selrect` and `:points` bypass the simple different-master skip; width/height checks catch some but not all positional differences.
- `previous-shape` may be repositioned by destination-root minus origin-root before copying. For normal variant switch this is often zero, but do not assume it for all swap entry points.
- Touched flags can be inherited through ref chains, so a shape that looks untouched locally may still behave as touched after `add-touched-from-ref-chain`.

## Test harness

`common/src/app/common/test_helpers/compositions.cljc` has `swap-component-in-shape`, which drives `generate-component-swap` plus `generate-keep-touched` with the production `keep-touched?` flag. Use it for focused common tests of variant-switch behavior.

`common/test/common_tests/logic/variants_switch_test.cljc` is the canonical reference suite for swap+touched scenarios. Read nearby tests before adding another case.