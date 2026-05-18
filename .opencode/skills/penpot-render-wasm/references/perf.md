# Performance Patterns

Recurring performance patterns in render-wasm and how to think about them. This isn't a bug catalog — it's the design lessons that have stuck.

---

## The frame budget

The render loop's frame budget is **32 ms** (two frames at 60Hz; nominal target is 16 ms but the loop tolerates the doubled budget). Anything that needs to happen "this frame" must fit, including:

- The full shape tree walk (or partial walk for the visible region)
- Any modifier fan-out and tile invalidation
- Skia draw calls for fills, strokes, shadows, blurs
- Compositing surfaces and submitting to GPU

When a single sync WASM call exceeds 32 ms on the main JS thread, DOM events queue and the user perceives a freeze. See "Main-thread blocking" below.

---

## Main-thread blocking from sync FFI

The single most common Penpot perf failure mode. `api.cljs` is on the main JS thread; any sync WASM call inside it blocks DOM events for the duration.

**Hot offenders:**

- `_set_view_end()` → `rebuild_tile_index()` (called via `render-finish`, debounced 100 ms). Walks all top-level shapes; cost scales with N.
- Shape upload pipeline (`process-object`, `process-shapes-chunk`). Chunks of ~100 shapes with `yield-to-browser` (MessageChannel-based, ~0 ms minimum) between batches, but each chunk blocks for its own duration.
- Per-pointermove sync calls (`text-editor-pointer-move`, hit testing). Cheap individually; lethal when other work is also blocking.

**Diagnostic signal:** events arrive in bursts after a quiet period.

**Solution patterns:** chunk + yield (`yield-to-browser` via `MessageChannel`), throttle the trigger, defer until interaction end. `OffscreenCanvas` + worker-side rendering is the heavy variant; nothing in render-wasm currently uses it.

---

## Tile cache invalidation

Two failure shapes:

**Over-invalidation** — modifier fan-out walks ancestors + descendants + tile coverage. If `expanded_count >> input_count` or `invalidated_tiles >> expected`, the bug is the walk.

**Under-rendering** — pending tiles don't reach the visible priority bucket fast enough. The 4-way split (`pending:cached`, `pending:uncached_visible`, `pending:uncached_interest`, `pending:total`) is what to instrument.

Files: `render-wasm/src/render.rs::rebuild_touched_tiles` (modifier-driven), `start_render_loop` (pending-tile partitioning).

---

## Backbuffer crop cache (drag fast path)

`backbuffer_crop_cache` (`render.rs::rebuild_backbuffer_crop_cache`) snapshots a region of `Backbuffer` at non-interactive frame time, then blits it during drag instead of re-rendering. Big perf win when correct.

Two failure shapes:

- **Capture-before-layout** — snapshot taken before async work (font load, paragraph layout) finishes. Result: cache holds the container's fill but no glyphs. *Visual: dragging a frame with text children shows a colored rect with no text.* Fix is capture-time gating, not read-time gating (the cache is never modified during drag, so use-time checks are too late).
- **Reading the cache when shape moved past invalidation** — stale tile reads. Less common.

Read-side gating: `is_safe_for_drag_crop_cache` in `shapes.rs` (rejects Type::Text, frames-with-overflow, blur, shadow, opacity, blend mode). It does NOT recurse into descendants — that's the trap with text children.

---

## Filter / blur / shadow

Historically: filters used `render_with_filter_surface`, which `surface_clone`s per filtered shape. Surface cloning is expensive; per-shape during pan/zoom dominates.

Current state (commit `337cfc2`, "Improve performance on shapes with blur"):

- **Layer blur via `save_layer`** — opens a `save_layer` with an `ImageFilter` on the Fills/Strokes/InnerShadows sub-surfaces directly. Avoids the surface clone, preserves clip correctness. (`render.rs:~793` `blur_sigma_for_layers`, `~1024` `save_layer` open.)
- **Drop shadow adaptive downscale** — `blur_downscale = (BLUR_DOWNSCALE_THRESHOLD / shadow.blur).max(MIN_BLUR_DOWNSCALE)` for blur > 8 px. Gaussian blur is scale-equivariant, so downscaling + smaller sigma yields identical output with ~k³ less GPU work. Constants: `BLUR_DOWNSCALE_THRESHOLD: f32 = 8.0`, `MIN_BLUR_DOWNSCALE: f32 = 0.125`.
- **`render_into_filter_surface(extra_downscale, ...)`** — generalized parameter passed by callers. `extra_downscale = 1.0` → no change; `< 1.0` → pre-scale the filter canvas before drawing. Constants `MIN_FIT_SCALE = 0.1` (per-axis overflow clamp), `MIN_COMBINED_SCALE = 0.03` (sub-pixel surface floor).
- **Fast mode** — `render_preview` engages `fast_mode` to skip blur/shadow during preview. Frame-level `frame_clip_layer_blur` is gated on `!fast_mode`.

If a filter regression appears, first check whether `fast_mode` is being engaged on the path you'd expect, then check the `extra_downscale` value flowing into the filter surface.

Files: `render-wasm/src/render.rs` (`blur_sigma_for_layers`, `save_layer` open, `render_drop_black_shadow`), `render-wasm/src/render/filters.rs` (`render_into_filter_surface`).

---

## `gesture_record!` instrumentation

The `gesture_record!` macro (`render-wasm/src/performance.rs`) emits `(stage, value)` tuples via `run_script!` into `window.__penpotGestureRecord`. The JS side (`frontend/src/app/util/perf.cljs` when wired) buffers and reports.

Always compiled (no feature flag) because the call site is cheap when `__penpotGestureRecord` is undefined. Pattern at the call site:

```rust
let _instrument = self.options.is_interactive_transform();
let _t = if _instrument { performance::get_time_ms() } else { 0.0 };
// ... measured work ...
if _instrument {
    let dt = performance::get_time_ms() - _t;
    crate::gesture_record!("stage_name", dt);
}
```

Stages from a past instrumentation pass (since removed; the macro and these names are documented here so they can be restored quickly when needed):

- `render:rebuild_touched_tiles`, `render:start_render_loop`, `render:atlas_blit`, `render:pending_tiles_update`
- `render:shape_tree_partial`, `render:flush_and_submit`
- `render:tile_cached`, `render:tile_uncached_shapes`, `render:apply_to_canvas`
- `pending:total`, `pending:cached`, `pending:uncached_visible`, `pending:uncached_interest`
- `nodes:initial_queue`, `nodes:processed`, `nodes:skipped_hidden`, `nodes:skipped_invisible`
- `drag_subtree:modified`, `drag_subtree:total`
- `drag_backdrop:capture_tiles`, `drag_backdrop:capture_ms`
- `modifier_tiles:input_count`, `modifier_tiles:expanded_count`, `modifier_tiles:invalidated_tiles`

When restoring, gate on `is_interactive_transform()` unless you specifically want zoom/pan timing too. The CLJS-side receiver (`__penpotGestureRecord`) lived in `frontend/src/app/util/perf.cljs`; recover that file from history together with the Rust call sites if you bring back the full pipeline.

---

## Drag-sprite approach: abandoned

The drag-sprite approach (captured sprite + atlas backdrop) was tried and abandoned. It was too fragile — every tile-cache eviction path needed a "drag active?" guard, and the surface area for "we forgot one" was unbounded.

The decision was to optimize the normal drag render path instead (backbuffer crop cache, fast mode, filter perf work above). 439 lines were removed from `render-wasm/src/{render,main,render/surfaces}.rs` when this was abandoned.

**Lesson:** when a perf optimization requires invariants enforced across many call sites, count the call sites first. If you can't enumerate them, the optimization will leak.

---

## CLJS-side perf: subscription churn

Subscriptions that re-evaluate on every modifier or every frame can dominate sidebar interaction cost. Patterns to watch:

- A sub depends on `objects-modified` (changes every modifier tick) when it could depend on a coarser signal.
- A render component reads a sub that returns a fresh map/vector each call → equality breaks, re-renders cascade.
- `with-meta` wrapping a value upstream → defeats memoization. (`viewport_wasm.cljs`'s `frame-titles*` had a spurious `with-meta` removed in the blur perf commit.)
- Outline/overlay components rendering during pan when they could be hidden — gate on `(not panning)` like `show-frame-outline?` and `show-outlines?` in `viewport_wasm.cljs`.

---

## Decision principles

When optimizing render-wasm:

- **Prefer to make the slow path skip-able rather than make it faster.** `fast_mode`, `is_interactive_transform()`, and the cache check before render are all this pattern.
- **Cache at non-interactive moments; blit during interactive moments.** Backbuffer crop cache, tile cache, atlas all follow this.
- **Keep the JS main thread free.** Anything that doesn't need the main thread (worker queries, deferred WASM calls) should be off it. `OffscreenCanvas` is the eventual destination for the render loop itself if main-thread work becomes the dominant cost.
- **Measure before refactoring.** `gesture_record!` is cheap to wire up; speculative optimizations have a poor ROI in this codebase because the hot paths are usually obvious in the timings once instrumented.
