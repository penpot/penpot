# Shared UI package

- `frontend/packages/ui` builds `@penpot/ui`, a React/Vite library separate from the CLJS Design System. Keep reusable primitives independent of the Penpot app store. React and ReactDOM are peers and must remain external to the library build.
- The Vite build emits ESM and type declarations and copies `dist/ui.css` to `frontend/resources/public/css/ui.css`. If shared styles are stale, check the package build and copy before debugging CLJS.
- From `frontend/packages/ui`, use `pnpm run build`, `pnpm run watch` or `pnpm run test`. From `frontend/`, `pnpm run build:app:libs` builds app-side packages; `pnpm run build:storybook`, `watch:storybook` and `test:storybook` cover Storybook. Validate changed exports in both the package and consuming app or Storybook.
- Other packages under `frontend/packages/` are workspace dependencies and need not implement behavior in CLJS. App components and the CLJS Design System are described in `mem:frontend/ui-cljs`; text editing is `mem:frontend/text-editor`.
