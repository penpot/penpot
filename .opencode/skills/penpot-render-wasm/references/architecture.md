# Architecture

The mental model for render-wasm and its CLJS bridge. Read this when planning a refactor or trying to orient on a new subsystem.

---

## Overall shape

```
                ┌─────────────────────────────────────┐
                │ Penpot Workspace (CLJS, main thread)│
                │  Re-frame state, viewport, sidebar  │
                └────────────┬────────────────────────┘
                             │ frontend/src/app/render_wasm/api.cljs
                             │  (sync FFI calls, JS objects, h/call)
                             ▼
                ┌─────────────────────────────────────┐
                │ render-wasm (Rust → WASM)           │
                │  STATE: Box<State>                  │
                │   ├─ ShapesPool                     │
                │   ├─ RenderState                    │
                │   │    ├─ Surfaces (Skia)           │
                │   │    ├─ Tile cache                │
                │   │    ├─ backbuffer_crop_cache     │
                │   │    └─ TextEditorState (V3)      │
                │   └─ Modifiers, fonts, view, etc.   │
                └────────────┬────────────────────────┘
                             │
                             ▼ Skia GPU canvas → HTMLCanvasElement
```

There is exactly one `STATE` global on the Rust side, accessed only through the macros in `main.rs:60-102` (see `conventions.md`). The CLJS side serializes work into FFI calls; nothing on the Rust side runs in parallel with the JS main thread (except the dashboard thumbnail worker, which has its own WASM instance).

---

## Module layout (`render-wasm/src/`)

| Path | Role |
|---|---|
| `main.rs` | WASM entry points (init, set_browser, render driver); access macros |
| `state.rs`, `state/` | `State` struct (`shapes_pool.rs`, `text_editor.rs`); orchestration |
| `shapes.rs`, `shapes/` | Shape model: `Shape`, `Type`, layouts, modifiers, paths, fills, strokes, text, transform |
| `render.rs`, `render/` | Render loop, surfaces, fills/strokes/shadows/filters drawing, tile cache, UI overlay |
| `wasm/` | `#[no_mangle]` exports grouped by subsystem (shapes, text, layouts, paths, blurs, fills, strokes, etc.) |
| `tiles.rs` | Tile index and tile rect math |
| `mem.rs` | WASM memory allocation: `write_bytes`, `free_bytes` (with global lock) |
| `performance.rs` | `run_script!`, `gesture_record!`, perf markers |
| `error.rs` | `Result<()>` + `#[wasm_error]` macro |
| `view.rs` | Viewbox / zoom |
| `fonts/` | Font loading & registration |
| `math.rs`, `math/` | Geometry primitives (Rect, Point, Matrix), shared by shape and render code |

---

## CLJS bridge (`frontend/src/app/render_wasm/`)

| File | Role |
|---|---|
| `api.cljs` | The main bridge. `set-view-box`, `render-finish`, shape upload, sync-selection-rects!, update-text-rect! |
| `text_editor.cljs` | V3 text editor JS FFI wrappers |
| Other namespaces | Shape-type-specific exports, fonts, exports |

Workspace components that drive the bridge:

- `frontend/src/app/main/ui/workspace/viewport_wasm.cljs` — viewport component, calls `set-view-box`, manages `[@canvas-init? selected-shapes transform]` effect
- `frontend/src/app/main/ui/workspace/viewport/actions.cljs` — pointer event handlers (`on-pointer-move`, `schedule-zoom!`, `schedule-scroll!`)
- `frontend/src/app/main/ui/workspace/viewport/hooks.cljs` — `setup-hover-shapes`, hover stream coordination
- `frontend/src/app/worker.cljs` — worker bridge with `ask-buffered!` (dedupe-by-cmd, 1ms debounce)
- `frontend/src/app/worker/selection.cljs` — quadtree selection queries (worker-side)

---

## Render loop shape

Two main entry paths into the render loop:

1. **Non-interactive frame** — full render of all visible shapes into Backbuffer + tile cache, then composite to Target. Triggered after viewbox changes settle (`set_view_end` debounced), file open, edits.
2. **Interactive transform** (drag/resize/rotate) — fast path that uses the tile cache + backbuffer crop cache where possible. Avoids re-rendering shapes whose pixels can be blitted from the snapshot.

The loop body (in `render.rs::start_render_loop` and downstream) follows roughly:

```
start_render_loop
  ├─ if non-interactive: rebuild_backbuffer_crop_cache (snapshot for next drag)
  ├─ partition pending tiles: cached / uncached_visible / uncached_interest
  ├─ for each pending tile (visible first):
  │    ├─ if cached: blit
  │    └─ if uncached: render_shape_tree_partial → flush_and_submit
  └─ atlas_blit (atlas → target composite)
```

The frame budget is 32 ms; the loop yields between batches in some places but not all (visible tiles render without yielding, by design — they need to be on screen *now*).

For drag-specific paths, see `references/perf.md`.

---

## State + shape ownership

`State` owns:

- `ShapesPool` — the canonical shape store, indexed by `Uuid`. Lookups via `tree.get(&uuid)`.
- `RenderState` — render-side caches and Skia surfaces (separate from the shape data itself).
- `current_shape_id` — the "selected" shape for `_set_shape_*` exports (the FFI is one-shape-at-a-time).
- View, modifiers, fonts, options.

The CLJS side does NOT mirror the shape pool — it pushes shapes into WASM via `_set_shape_*` exports, then queries the pool indirectly through render results. This is one-way data flow; the Rust side is authoritative once shapes are uploaded.

---

## Surface layering (`render-wasm/src/render/surfaces.rs:31`)

The Skia surfaces, in roughly the order they're composited:

```
SurfaceId::Tiles    ← per-tile cached renders
SurfaceId::Atlas    ← composited tiles for blitting
SurfaceId::Current  ← per-shape sub-surface (Fills/Strokes/InnerShadows below)
SurfaceId::Fills    ← shape fill (used inside save_layer for layer blur)
SurfaceId::Strokes  ← shape stroke
SurfaceId::InnerShadows ← inner shadow effect surface
SurfaceId::Backbuffer ← full-frame composite source
SurfaceId::Target   ← what the browser sees
SurfaceId::UI       ← overlays drawn on top each frame
```

Filter surfaces (for blur/shadow) come from `render::filters.rs::render_into_filter_surface`.

---

## Text rendering (high level)

Text is split between WASM and CLJS:

- **Layout & glyph drawing** in WASM: `shapes/text.rs`, `render/text.rs`, `render/fonts.rs`. Uses `skia::textlayout::Paragraph`. Output stored in `TextContentLayout.paragraphs: Vec<Vec<Paragraph>>`.
- **DOM tree** in CLJS: `frontend/src/app/util/text/content/to_dom.cljs`, `styles.cljs`. Renders contenteditable with `color: transparent`, providing input/IME and selection ranges.

For V2 editor: cursor and selection are native DOM (the contenteditable does it). For V3 editor: cursor and selection are Skia overlays drawn from `TextEditorState`. See `references/v3-text-editor.md`.

---

## What "interactive transform" means

The render path branches on whether a transform is in flight (`is_interactive_transform()`). When true:

- Backbuffer is NOT refreshed (would invalidate the crop snapshot).
- Cached drag crop images are blitted in place of full re-render for shapes that pass `is_safe_for_drag_crop_cache`.
- `gesture_record!` instrumentation (when wired) only fires under this gate.

This is the fast-but-fragile path; bugs here usually take the form of "stale pixels stick around because we cached too eagerly" — see `references/perf.md` for the canonical example (frames with text descendants).

---

## What "fast mode" means

`fast_mode` is a flag on the render state set during view changes (`set_view_start` enables it; `set_view_end` disables it) and during preview rendering. While on:

- Frame-level blur (`frame_clip_layer_blur`) is skipped.
- `render_preview` engages it explicitly to skip blur/shadow during thumbnail or scroll preview.
- Shape rendering uses cheaper paths where available.

Fast mode is what lets pan/zoom be smooth on large files; without it, every frame would re-render shadows/blurs.

---

## CLJS-side state coordination

Re-frame state holds the workspace data; the WASM bridge reads from subscriptions and pushes into Rust. Key effects to be aware of:

- `mf/with-effect [vbox zoom]` in `viewport_wasm.cljs` — fires on viewport changes, calls `set-view-box`.
- `mf/with-effect [@canvas-init? selected-shapes transform]` — drives `sync-selection-rects!` for the UI overlay.
- `setup-hover-shapes` hook in `viewport/hooks.cljs` — subscribes to `move-stream`, sends `ask-buffered!` queries to the worker.

Backpressure is handled mostly by `ask-buffered!` (drop stale by command) and rAF-coalescing for zoom/pan. Hot paths to watch are anything that fires on every pointermove or every modifier tick.
