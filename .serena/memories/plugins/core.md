# Plugins

- `plugins/README.md` covers local development; `plugins/package.json` defines the commands. For test design see `mem:testing`; for local checks see `mem:scripts/ci`.
- `libs/plugin-types/index.d.ts` declares the public Plugin API; it does not implement it. For Penpot's CLJS proxies, permissions and event behavior, see `mem:frontend/plugin-api-to-cljs-binding`. For sandbox startup and plugin lifetime in `libs/plugins-runtime`, see `mem:plugins/runtime`.
- Update `plugins/CHANGELOG.md` for public type or runtime changes. Label entries with `**plugin-types:**` for declarations and `**plugins-runtime:**` for runtime behavior.
- Keep `libs/plugins-styles/styles.css` at the package root. When pnpm links the workspace source instead of the published package, `@penpot/plugin-styles/styles.css` must still resolve to the same styles that `plugins/tools/scripts/build-css.mjs` publishes.
