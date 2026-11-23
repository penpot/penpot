# Exporter

- `exporter/` is a CLJS/Node service for exporting Penpot content. Start at `app.handlers.export`, `app.handlers.export-shapes` and `app.jobs.scheduler` for admission and delivery. Browser and headless WASM rendering boundaries: `mem:exporter/rendering`.
- HTTP session tokens come from the `auth-token` cookie; resource uploads use bearer auth and the exporter management shared key. Shape exports with `wait` false return a resource before upload and publish progress and outcome; `wait` true waits for upload. Frame export returns a resource and publishes progress separately. See `app.handlers.export` and `app.handlers.resources`.
- The scheduler limits work per process, not across replicas. It skips jobs whose profile is at capacity and limits admitted headless jobs to the number of render workers; otherwise jobs would report running while waiting inside the pool. Cancelled queued jobs release their slot and tracked resources.
- The exporter consumes `common/` and generated `render-wasm` artifacts. Never edit or commit the generated WASM or `shared.js`; rebuild the export target. See `mem:render-wasm/core`.
- For test namespace registration and focused runs see `mem:exporter/testing`. For module lint, tests and formatting see `mem:scripts/ci`.
