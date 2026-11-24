# CLJS application UI

- Start at `frontend/src/app/main/ui` and `docs/technical-guide/developer/ui.md`. Rumext components normally use `mf/defc`, a `*` var suffix and `[:> component* props]`; private components use `::mf/private true`.
- Keep component ownership clear: use children for normal composition and slots for separately owned regions. Do not style or restructure child DOM the component does not own. When callers need layout customization, merge a `class` prop with `mf/spread-props` so Rumext transforms it to `className`.
- Avoid boolean prop names ending in `?` at the JS boundary; use `^boolean` where JS semantics matter. Dynamic inline `:style` values on native elements must be JS objects: a CLJS map passed at runtime reaches React unconverted, even though static literals can be converted by the macro.
- Resolve translations during render or render-time memoization so locale changes update labels; catalog and asset rules are in `mem:frontend/translations`.
- Use semantic HTML and appropriate ARIA patterns. Give icon-only controls accessible names and hide decorative images from assistive technology.
- Memoize expensive derived collections in hot render paths. `d/xf:add-index` attaches `:app.common.data/index` and requires associative items; it cannot index plain IDs or keywords. Avoid unnecessary callback and object allocation in hot renders.
- CLJS Design System components live in `frontend/src/app/main/ui/ds`: follow the UI guide for implementation, CSS module, Storybook story and export from `ds.cljs`. For SCSS and Design System tokens see `mem:frontend/ui-styles`; for the separate React package see `mem:frontend/ui-package`. For tests and compile diagnosis see `mem:frontend/testing` and `mem:frontend/compile-diagnostics`.
