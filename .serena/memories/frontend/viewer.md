# Frontend viewer

- Start at `app.main.ui` for route parsing, `app.main.ui.viewer` and `app.main.data.viewer`. The route parses `file-id` with `uuid/parse*`; `viewer-page*` raises `:not-found` / `:missing-file-id` in an effect for invalid IDs and mounts `viewer*` only for a UUID, avoiding initialization with nil.
- Viewer initialization fetches a view-only bundle and sends all supported features because an anonymous viewer may not know team-enabled features. Comment threads are fetched only for logged-in users.
- The viewer resolves pointer values in file data and `:pages-index` through `app.main.features.pointer-map/resolve-pointer` with share-link scope before storing the bundle. Membership and share-link bundles have different fragment permissions; an unresolvable fragment rejects rather than silently dropping a page. See `mem:backend/permissions`.
- `bundle-fetched` indexes pages, precomputes frames and navigates to the requested or selected frame. Zoom and interaction mode also live in route query params. `update-zoom-querystring` must compare current and expected zoom before replacing the route: otherwise loading and navigation can loop into React's maximum update depth error.
- Dashboard behavior is `mem:frontend/dashboard`; shared routing rules are `mem:frontend/routing-app-shell-subtleties`.
