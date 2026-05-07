# File Map

Quick lookup of where things live. Organized by subsystem rather than by alphabet — when you're answering "where does X happen", scan the relevant section.

---

## WASM entry points + state

- `render-wasm/src/main.rs` — `init`, `set_browser`, `clean_up`, render driver, **state access macros** (lines 60-102: `with_current_shape!`, `with_current_shape_mut!`, `with_state_mut_current_shape!`)
- `render-wasm/src/state.rs` + `state/` — `State` struct, `ShapesPool`, `TextEditorState`
- `render-wasm/src/error.rs` — `Result<()>`, `#[wasm_error]` macro
- `render-wasm/src/mem.rs` — `write_bytes`, `free_bytes` (with global lock)
- `render-wasm/src/performance.rs` — `run_script!`, `gesture_record!`, perf markers
- `render-wasm/src/wapi.rs` — JS API helpers (request_animation_frame, cancel_animation_frame, etc.)

## Render path (Rust)

- `render-wasm/src/render.rs` — main render loop, `start_render_loop`, `process_animation_frame`, `render_shape_tree_partial`, tile cache, `rebuild_backbuffer_crop_cache`, `is_safe_for_drag_crop_cache` (read path)
- `render-wasm/src/render/surfaces.rs` — `SurfaceId` enum + `Surfaces` struct, surface alloc/canvas access
- `render-wasm/src/render/fills.rs`, `strokes.rs`, `shadows.rs` — per-effect drawing
- `render-wasm/src/render/filters.rs` — `render_into_filter_surface` (with `extra_downscale` for adaptive blur)
- `render-wasm/src/render/text.rs` — Skia text drawing
- `render-wasm/src/render/text_editor.rs` — V3 cursor + selection overlay (`render_overlay`, `calculate_cursor_rect`, `calculate_selection_rects`)
- `render-wasm/src/render/ui.rs` — `SurfaceId::UI` overlay compositing
- `render-wasm/src/render/grid_layout.rs` — grid layout debug viz
- `render-wasm/src/render/fonts.rs` — font management for rendering
- `render-wasm/src/render/images.rs` — image fill rendering
- `render-wasm/src/render/options.rs` — render options state
- `render-wasm/src/render/debug.rs` — debug overlay
- `render-wasm/src/render/gpu_state.rs` — Skia GPU context
- `render-wasm/src/tiles.rs` — tile rect math, tile index

## Shapes (Rust model)

- `render-wasm/src/shapes.rs` + `shapes/` — `Shape`, `Type`, properties
- `render-wasm/src/shapes/frames.rs`, `groups.rs`, `rects.rs`, `text.rs`, `bools.rs` — per-type
- `render-wasm/src/shapes/paths.rs` + `paths/` — path geometry
- `render-wasm/src/shapes/fills.rs`, `strokes.rs`, `shadows.rs`, `blurs.rs`, `blend.rs`, `corners.rs` — effects/properties
- `render-wasm/src/shapes/layouts.rs` + `modifiers.rs` + `modifiers/` (`flex_layout.rs`, `grid_layout.rs`) — layout computation
- `render-wasm/src/shapes/transform.rs` — shape transform (matrix)
- `render-wasm/src/shapes/text_paths.rs`, `stroke_paths.rs` — derived paths
- `render-wasm/src/shapes/svg_attrs.rs`, `svgraw.rs` — SVG-specific attrs

## WASM exports (the FFI surface)

- `render-wasm/src/wasm.rs` + `wasm/` — `#[no_mangle] extern "C"` functions grouped by subsystem
- `render-wasm/src/wasm/shapes/base_props.rs` — `set_shape_base_props` + `RawBasePropsData` (canonical binary-prop example)
- `render-wasm/src/wasm/text.rs`, `text/` — text shape exports
- `render-wasm/src/wasm/text_editor.rs` — V3 editor exports (lifecycle, cursor, selection, editing, navigation, render overlay)
- `render-wasm/src/wasm/text/helpers.rs` — V3 word boundary, cursor movement, deletion, insertion helpers
- `render-wasm/src/wasm/layouts.rs`, `layouts/grid.rs` — layout exports
- `render-wasm/src/wasm/paths.rs`, `paths/` — path exports
- `render-wasm/src/wasm/fills.rs`, `fills/` — fill exports
- `render-wasm/src/wasm/strokes.rs`, `shadows.rs`, `blurs.rs`, `blend.rs`, `transforms.rs`, `svg_attrs.rs`, `fonts.rs`, `mem.rs`

## Frontend bridge (CLJS)

- `frontend/src/app/render_wasm/api.cljs` — bridge: `set-view-box`, `render-finish`, `set-shape-*`, `sync-selection-rects!`, `update-text-rect!`, shape upload pipeline (`process-object`, `process-shapes-chunk`, `process-next-chunk`, `yield-to-browser`)
- `frontend/src/app/render_wasm/text_editor.cljs` — V3 editor JS FFI wrappers
- `frontend/src/app/render_wasm/exports/wasm.cljs` — exports/snapshot wiring
- `frontend/src/app/render_wasm/...` — other bridge namespaces

## Workspace components (CLJS)

- `frontend/src/app/main/ui/workspace/viewport_wasm.cljs` — viewport, calls `set-view-box`, manages overlay effects, editor selection logic at lines 467-480 (V3→V2→V1)
- `frontend/src/app/main/ui/workspace/viewport/actions.cljs` — pointer/wheel handlers (`on-pointer-move`, `schedule-zoom!`, `schedule-scroll!`, `on-mouse-wheel`)
- `frontend/src/app/main/ui/workspace/viewport/hooks.cljs` — `setup-hover-shapes`, `over-shapes-stream`, hover query coordination
- `frontend/src/app/main/ui/workspace/shapes/text/v3_editor.cljs` — V3 contenteditable component (input wrapper)
- `frontend/src/app/main/ui/workspace/shapes/text/v2_editor.cljs` — V2 editor component
- `frontend/src/app/main/ui/workspace/shapes/text/v2_editor.scss` — V2/V3 editor styles
- `frontend/src/app/main/ui/workspace/shapes/text/editor.cljs` — V1 (legacy Draft-JS) editor

## Worker side

- `frontend/src/app/worker.cljs` — `ask-buffered!`, dedupe-by-cmd, 1ms debounce
- `frontend/src/app/worker/selection.cljs` — quadtree selection queries
- `frontend/src/app/util/worker.cljs` — `ask-buffered!` send wrapper

## Text content (CLJS)

- `frontend/src/app/util/text/content/to_dom.cljs` — DOM builder
- `frontend/src/app/util/text/content/styles.cljs` — style mapping
- `frontend/text-editor/src/editor/TextEditor.css` — standalone editor CSS

## Feature flags

- `frontend/src/app/main/features.cljs:38-41` — `render-wasm/v1`, `text-editor/v2`, `text-editor-wasm/v1` (V3)

## Tests

- `frontend/playwright/ui/render-wasm-specs/shapes.spec.js`, `texts.spec.js` — render-wasm Playwright tests
- `frontend/playwright/data/render-wasm/get-file-*.json` — transit-encoded test fixtures
- `frontend/playwright/ui/pages/WasmWorkspacePage.js` — page object
- `frontend/playwright.config.js` — render-wasm project config (1920x1080, 2x DPR)

---

## Symptom → file map

| If you're chasing... | Open first |
|---|---|
| A drag visual glitch | `render.rs::rebuild_backbuffer_crop_cache`, `shapes.rs::is_safe_for_drag_crop_cache` |
| A `wasm-critical` panic from a worker | `performance.rs::run_script!` call sites — guard `document` access |
| A binary-prop deserialization mismatch | The relevant `Raw*Data` struct + its `offset_of!` tests |
| A WASM call that hangs | `mem::write_bytes` without a preceding `mem::free_bytes` |
| A flex/grid sizing oddity that's exactly 0.01 off | `MIN_SIZE` sentinel in `flex_layout.rs:16` / `grid_layout.rs:15` — don't remove it |
| Hover/zoom/pan freezing the UI | `api.cljs::render-finish` → `_set_view_end` → `render.rs::rebuild_tile_index` (sync, main thread) |
| Filter / blur / shadow perf | `render.rs::render_drop_black_shadow`, `render/filters.rs::render_into_filter_surface` (with `extra_downscale`) |
| V3 cursor not where you expect | `render/text_editor.rs::calculate_cursor_rect`, Skia paragraph layout dependency |
| Selection rect drawing oddly | `render/ui.rs`, `api.cljs::sync-selection-rects!` |
| A text shape's measured rect being stale | `update-text-rect!` in `api.cljs` — hook point after WASM layout |
