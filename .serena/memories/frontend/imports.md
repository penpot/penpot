# Frontend imports

- Start at `app.main.data.imports`, `app.main.data.uploads` and `app.main.ui.dashboard.import`. Backend import formats, jobs and permission revalidation are in `mem:backend/binfile`.
- A v3 package has no per-file size in its manifest. Compute it from each file's entries plus the shared `objects/` referenced by its media and thumbnail metadata. A reference that cannot be read leaves that file's size unknown, not the whole package invalid.
- Read media and thumbnail metadata sequentially: each read opens a decompression stream, and parallel reads for large packages can exhaust Chromium. zip.js can mask the underlying error with `Cannot close a ERRORED writable stream`.
- Keep the original `File` or `Blob` from local imports and downloaded templates through analysis, then upload it in chunks without re-fetching the `blob:` URL. Templates with only a URL use the fetch path.
- An import entry's `:error` is already user-facing text, not an i18n key. The legacy SSE import remains on the backend for other clients.
