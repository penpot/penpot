# render-wasm FFI and Rendering Subtleties

## FFI state and errors

- The renderer uses one unsafe global `STATE`; the `with_state*` macros currently panic on invalid state pointer. Treat state pointer validity as critical, not recoverable.
- `#[wasm_error]` clears the error code on entry. Recoverable errors set code `0x01`, critical errors/panics set `0x02`, free the byte buffer, then panic so the CLJS bridge can catch and inspect `_read_error_code`.
- The frontend bridge maps `0x01` to `:non-blocking` and `0x02` to `:panic` in ex-data (`:type :wasm-error`). Check actual bridge code if changing names; older comments/docs may use different labels.
- WASM byte transfer is a single global slot. A caller that receives a pointer result must read and free it before another byte payload is written; errors free the slot via `#[wasm_error]`.

## Shape pool and loading

- Shapes are UUID-indexed, and hierarchy/structure is tracked separately. `ShapesPool::get` may return a cached modified clone when modifiers, structure, scale-content, or bool handling apply; `get_raw` bypasses those derived values.
- Bulk loading uses a `loading` flag. `touch_current` / `touch_shape` avoid tile invalidation while loading; text layouts and final view setup must happen after loading ends.
- Many setters mutate only the current shape selected by `use_shape` / current-shape APIs. If no current shape is selected, some mutation blocks are skipped silently.
- `set_parent_for_current_shape` only sets parent metadata and invalidates parent geometry; children must be updated separately to avoid duplicate children.
- Child deletion marks descendants deleted and removes them from all indexed tiles, preserving undo/redo while avoiding stale pixels after panning.

## Tile/render behavior

- Interactive transforms are distinct from viewport fast mode. `set_modifiers_start` enables fast mode and interactive transform; interactive transform still flushes each animation frame.
- During interactive transform, modifier tile invalidation is deferred to `render()` once per rAF. Outside interactive transform, `set_modifiers` rebuilds modifier tiles immediately.
- `set_modifiers_end` disables fast/interactive state and cancels pending async render; the caller must request the final full-quality render.
- Plain viewport fast mode (`options.is_viewport_interaction()`) renders from cache and does not flush target output inside `process_animation_frame`; interactive transforms do flush.
- Zoom changes rebuild the tile index while preserving cached tile textures. Avoid replacing that path with shallow rebuilds if blur/shadow cache preservation matters.
- Two-phase HiDPI zoom settle: (1) **Interactive** — one paint-once of **visible** tiles at DPR≤1 fill-rate (`tile_px=512`, `paint_scale≈zoom`); present + notify. (2) Deferred **Full** — `512*dpr` sprites, **visible+interest**, paint-once when the region fits (else per-tile). At DPR≤1 there is no Interactive phase. CTM must use `get_paint_scale()` / `get_raster_scale()`.
- Soft settle schedules `pending_sharp_promote` (next `continue`); do not stay Interactive forever.
- Partial frames **skip** GPU flush; hard sync in `present_frame` / ViewportReady.
- Paint-once **resizes Current** to the region, restores viewport pad after present.
- Zoom-gesture preview: **DocAtlas only** when `zoom_changed` (no tile-sprite overlay). `invalidate_tile_cache` clears sprites only — not `DocAtlas::reset()`. `continue_render_loop` no-ops while `fast_mode && zoom_changed`.
- Full-quality `tile_px=1024` → ~16 atlas slots; `TileTextureCache::add` must not panic (see harden rules below).
- `rebuild_backbuffer_crop_cache` caps crop windows to the backbuffer size (not `max_texture_size`) so crops use cheap Backbuffer `snapshot_rect`; doc-atlas snapshot + scratch are lazy and only for off-viewport fallback. Avoid eager 4096² scratch after pan/zoom Full frames.
- Cache surface growth must compare against the live `cache` width/height, not `get_cache_size(cached_viewbox)`. The latter re-created the same large cache on every progressive frame while `cached_viewbox` lagged.
- `start_render_loop` always sets `allow_stop=true` so tile work yields under `max_blocking_time_ms`. Do not gate yielding on `preserve_target && !zoom_changed`: that forced a full interest-area sync pass after pan and froze HiDPI deep-zoom sessions. `preserve_target` alone keeps the last frame visible during progressive fill-in.
- Pending tile priority is intentionally reversed by pop order; check the queue construction before changing tile scheduling.