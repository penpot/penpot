# Frontend UI styles

- Start with `docs/technical-guide/developer/ui.md` and the owning component. Prefer co-located SCSS modules, `app.main.style/stl`, and Design System tokens and mixins over legacy global selectors or deprecated `resources/styles/common/refactor/spacing.scss`. Keep specificity low and style only DOM the component owns.
- Use named tokens for colors, spacing, fixed sizes, borders and typography; prefer Design System typography components and mixins. Component-local CSS custom properties can express variants and theme states.
- Prefer logical properties for directional layout, but avoid logical and physical declarations that compete for the same dimension. For a dynamically sized element keep the stylesheet and inline declaration consistent; the import progress bar illustrates the conflict. Dynamic inline styles on native CLJS UI elements must be JS objects; see `mem:frontend/ui-cljs`.
- For package-generated CSS and a stale shared stylesheet see `mem:frontend/ui-package`. For text editor styles and runtime boundaries see `mem:frontend/text-editor`.
