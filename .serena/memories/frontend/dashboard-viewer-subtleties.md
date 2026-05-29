# Frontend Dashboard and Viewer Subtleties

## Dashboard

- Dashboard initialization fetches projects and fonts for the team, then listens to websocket messages only for global topic `uuid/zero` or the current profile id.
- Project fetch replaces each project map completely instead of merging, so fields such as `deleted-at` can disappear cleanly.
- Dashboard file/project mutations are often optimistic local updates with fire-and-forget RPC watchers. Bulk permanent delete/restore paths use SSE progress and progress notifications.
- File creation/duplication strips file `:data` before putting file summaries into dashboard state.

## Viewer

- Viewer initialization sets `:current-file-id`, `:current-share-id`, and `:viewer-local`, then fetches the view-only bundle. Comment threads are fetched only for logged-in users.
- Viewer bundle fetch sends the full supported feature set because anonymous shared viewers may not know team-enabled features.
- View-only bundles can contain pointer values in `:pages-index` and file data. Viewer resolves those fragments with `:get-file-fragment` before storing the bundle.
- `bundle-fetched` indexes pages and precomputes viewer frames/all-frames, stores libraries/users/thumbnails/permissions under `:viewer`, then navigates to frame id, query index, or auto-selected frame.
- Viewer zoom and interaction mode changes update both `:viewer-local` and the `:viewer` route query params.