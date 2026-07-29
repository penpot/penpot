# Frontend UI Conventions and Style System

## CLJS app UI

- Main app components live under `frontend/src/app/main/ui*` and normally use Rumext `mf/defc` with a `*` suffix for component vars and `[:> component* props]` call sites.
- Components should have clear ownership. Use `children` for normal composition; use slotted props only when separate owned regions are needed. Do not style or structurally manipulate child DOM that the component did not instantiate.
- Accept and merge a `class` prop when callers reasonably need layout/positioning customization. Use `mf/spread-props` so Rumext prop transformations such as `:class` -> `className` still apply.
- Avoid boolean prop names ending in `?`; they do not translate cleanly to JavaScript props. Use type hints such as `^boolean` where JS truthiness/semantics matter.
- Split large components into smaller private components when useful; `::mf/private true` is the local convention for private Rumext components.

## Styling

- Co-located SCSS modules are preferred. Use `app.main.style/stl` helpers from CLJS and design-system SCSS tokens/mixins instead of legacy global selectors or high-specificity nesting.
- Keep CSS specificity low. Avoid nested selectors unless they target elements the component owns; CSS Modules already prevent class-name collisions.
- Prefer CSS logical properties for directional spacing/layout (`padding-inline-start`, etc.). Physical `width`/`height` are still acceptable where they are clearer.
- Use named design-system variables/tokens for spacing, borders, fixed dimensions, colors, and typography. Avoid hardcoded px/rem values and deprecated `resources/styles/common/refactor/spacing.scss` variables.
- Use component-local CSS custom properties for variants and theming instead of one-off Sass variables when a component has multiple visual states.
- Prefer DS typography components (`heading*`, `text*`) and typography mixins instead of plain text wrappers or deprecated typography mixins.

## Accessibility

- Prefer semantic HTML first: anchors for navigation/download/email links, buttons for actions, correct heading levels, and keyboard-focusable controls.
- If native elements cannot be used, apply appropriate ARIA roles/patterns. Follow WAI-ARIA APG patterns for standard widgets.
- Icon-only controls need an accessible name via surrounding text, `aria-label`, `alt`, or equivalent. Decorative images/icons should be hidden from assistive tech.

## I18n

- Translations must be resolved during render or render-time memoization, not at namespace load time. For static option lists, memoize inside render so locale changes still update labels.
- Translation files live in `frontend/translations/*.po`. Translation changes are bundled into `index.html`; refresh the browser after changing translations because there is no hot reload for translation strings.
- Run `pnpm run translations` from `frontend/` after adding/updating translation text.
- Adding a new supported locale requires updates in both `frontend/src/app/util/i18n.cljs` (`supported-locales`) and `frontend/scripts/_helpers.js` (`langs`).

## Performance

- Keep expensive derived data in refs, memoized selectors, or pure helpers. In hot render paths, prefer existing `app.common.data.macros` helpers where local code already uses them.
- Avoid creating new callback functions/objects inside hot renders when a named function, memoized callback, data attribute, or precomputed JS props object works.
- Destructure props/state values used repeatedly. Avoid repeated deref/property access in render loops.

## Shared React UI package

- `frontend/packages/ui` is the shared React/Vite package. It should remain framework-neutral relative to the CLJS app store; reusable primitives belong here only when they do not depend on Potok/Rumext app state.
- Package styles are emitted through the package build and copied into `frontend/resources/public/css/ui.css`; stale shared styles are often a build artifact issue.
- Storybook is the primary visual harness for shared UI/package behavior. Use `mem:frontend/ui-packages-text-editor-workflow` for package build/test commands.

## Choosing a location

- Put editor/dashboard/viewer workflow logic in CLJS app namespaces close to the owning feature.
- Put reusable presentational React primitives in `frontend/packages/ui` when they can be consumed without Penpot app state.
- Put CLJS design-system components under `frontend/src/app/main/ui/ds`; new DS components need implementation, CSS module, Storybook story, optional MDX docs, and export from `frontend/src/app/main/ui/ds.cljs` with a JavaScript-friendly name.
- Put text editing internals in `frontend/text-editor` when the behavior belongs to the JS editor package; use `mem:common/text-subtleties` for shared text data-model behavior.

## Validation

- For CLJS app UI, use `mem:frontend/testing`, `mem:frontend/compile-diagnostics`, and live browser/REPL checks when behavior depends on store or canvas state.
- For shared UI package changes, run the package build plus Storybook/component tests when relevant.
- For text editor changes, run `frontend/text-editor` tests and refresh/copy WASM artifacts if render-wasm output is involved.