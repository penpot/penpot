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

- Raster `Fill::Image`: skip `save_layer` unless the shape has an image filter; plain
  Rect/Frame (no corners) also skip the container clip (`draw_image_fill` in fills.rs).
- `can_render_directly` paints onto Current (no Fills/Strokes blit) for plain geometry and
  for stroke-free text (SrcOver, no blur/shadows). Multi-style text is fine: span styles
  live in Paragraph `TextStyle`s. Text skips the `nested_fills` guard (fills are on spans).
  `draw_text` only `save_layer`s when stroke-group opacity is set; plain fill paint is direct.
- Plain text fill paint reuses `TextContent.layout` paragraphs when
  `has_usable_paint_layout` (paragraphs present + version match; during
  interactive transforms rotation/move skips width check via
  `modifier_changes_text_layout`, resize falls back to `layout_width` vs
  `get_width(selrect.width())`), via `text::try_paint_from_layout_cache`.
  The walker computes `text_layout_cache_rotation_only` from `tree` and
  passes it into `render_shape`; stroke/shadow paths pass `false`.
- `TextContentLayout` paragraphs are `Rc`-shared on `Clone` so modifier clones
  (rotate/pan) keep the paint cache; `needs_update` is paragraphs-empty only.
  Decorations are skipped when no span requests underline/strike.
- Zoom settle: visible tiles present via `FrameType::ViewportReady` before interest-ring
  work; crop-cache rebuild is deferred to the later `Full` so the soft→sharp snap is
  compose+present only.
- Interactive transforms are distinct from viewport fast mode. `set_modifiers_start` enables fast mode and interactive transform; interactive transform still flushes each animation frame.
- During interactive transform, modifier tile invalidation is deferred to `render()` once per rAF. Outside interactive transform, `set_modifiers` rebuilds modifier tiles immediately.
- `set_modifiers_end` disables fast/interactive state and cancels pending async render; the caller must request the final full-quality render.
- Plain viewport fast mode (`options.is_viewport_interaction()`) renders from cache and does not flush target output inside `process_animation_frame`; interactive transforms do flush.
- Zoom settle wipes the tile texture cache in `set_view_end`. Mid-zoom overlays
  key tiles by scale; shape edits must `invalidate_cached_tiles_intersecting`
  the old∪new extrect so those overlays do not keep pre-edit pixels.
- Pending tile priority is intentionally reversed by pop order; check the queue construction before changing tile scheduling.
- Frames with a fill may use `render_frame_container_drop_shadow` (direct rrect +
  blur saveLayer on `DropShadows`) when `uses_direct_container_drop_shadow` is true.
