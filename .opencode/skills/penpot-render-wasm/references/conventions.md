# Conventions and Gotchas

The recurring rules to follow when writing render-wasm code. Each section names the invariant, *why* it exists (so you can judge edge cases), and where to look in the codebase for examples.

---

## State access macros (`render-wasm/src/main.rs:60-102`)

The Rust side stores a single `STATE: Option<Box<State>>` global. Direct access requires `unsafe`; do not write that yourself. Use the macros:

| Macro | Mutability | When to use |
|---|---|---|
| `with_state!(state, { ... })` | read-only state | Reading state without touching the current shape |
| `with_state_mut!(state, { ... })` | mutable state | Mutating state (render options, view, etc.) without touching a shape |
| `with_current_shape!(state, \|shape: &Shape\| { ... })` | read-only shape, read-only state | Querying a shape's properties |
| `with_current_shape_mut!(state, \|shape: &mut Shape\| { ... })` | mutable shape, mutable state, **calls `state.touch_current()`** | Modifying a shape — this is what most `set_shape_*` exports use |
| `with_state_mut_current_shape!(state, \|shape: &Shape\| { ... })` | read-only shape, mutable state | Reading a shape but needing mutable state for something else (no auto-touch) |

The `touch_current()` call in `with_current_shape_mut!` marks the shape dirty for re-render. If you bypass this (using `_state_mut_current_shape!` for a read-modify-write), the shape will not invalidate. That's almost always a bug.

**Why these exist:** the alternative is `unsafe { STATE.as_mut() }` everywhere. Centralizing the access pattern keeps the unsafe footprint small and makes the dirty-tracking automatic.

---

## Binary prop deserialization (`render-wasm/src/wasm/shapes/base_props.rs`, `wasm/text/effect_props.rs`)

Many WASM exports take a binary blob from JS instead of individual numeric arguments. The convention is:

1. Define a `RawXxxData` struct mirroring the JS-side binary layout. Use `#[repr(C)] #[repr(align(4))]`.
2. Implement `From<[u8; SIZE]>` via `unsafe { std::mem::transmute(bytes) }`.
3. Add helper methods on the struct for flag parsing, enum conversion, etc. — keep the struct dumb, put logic in helpers.
4. Write tests that assert:
   - `std::mem::size_of::<RawXxxData>()` matches expected size
   - `std::mem::align_of::<RawXxxData>()` is 4
   - `std::mem::offset_of!(RawXxxData, field)` for each field — guards against silent reordering
   - A round-trip `from_bytes(known_bytes) → RawXxxData` produces expected field values

**Worked example:** `RawBasePropsData` at `render-wasm/src/wasm/shapes/base_props.rs:19-100`, with the test fixture starting at line 175.

**Why so strict:** the JS side encodes these blobs by layout-aware bit packing. Any silent struct reordering (e.g., a Rust compiler decision to move a `u8` field for alignment) corrupts every shape upload without a compile error. The `offset_of!` tests are the only thing that catches this before runtime.

---

## WASM memory: `mem::write_bytes` and `mem::free_bytes` (`render-wasm/src/mem.rs`)

`mem::write_bytes(bytes)` allocates a buffer in WASM memory and returns a pointer the JS side reads. **It uses a global lock.** You must call `mem::free_bytes()` before the next `write_bytes` call, or the lock blocks.

The pattern (e.g., `wasm/text.rs:307-343`):

```rust
mem::free_bytes()?;          // release any prior allocation
let ptr = mem::write_bytes(bytes)?;  // allocate + copy + return pointer
// ... JS reads from ptr ...
// next time: free_bytes() before write_bytes() again
```

If you forget the `free_bytes`, the second call deadlocks (or panics, depending on the path) and the symptom is a frozen WASM call from JS. If you double-`free_bytes`, that's a no-op and harmless.

---

## Worker-safe `run_script!` calls (`render-wasm/src/performance.rs`)

The `run_script!` macro evaluates a JS expression from Rust. It's used heavily for performance markers, console logs, and the `gesture_record!` instrumentation.

**`render_sync` and `render_sync_shape` run inside the dashboard thumbnail worker thread**, where `document` is undefined. Any `run_script!` that references `document` will crash with `wasm-critical`.

The fix is to guard inside the JS expression itself:

```rust
run_script!(format!(
    "typeof document !== 'undefined' && document.{}",
    something
));
```

**Why this is easy to miss:** the `run_script!` invocation looks identical between main-thread and worker call paths. The crash only triggers when a thumbnail is rendered, which may not happen in interactive testing — so a missing guard can ship and only fail when a teammate hits the dashboard.

---

## Layout `MIN_SIZE` sentinel (`shapes/modifiers/flex_layout.rs:16`, `shapes/modifiers/grid_layout.rs:15`)

Both flex and grid layout use `const MIN_SIZE: f32 = 0.01;` as a non-zero sentinel for tracks/lines. **Don't suggest replacing it with `0.0`.** The 0.01 is intentional: division and clamping later in the layout pipeline produce NaN or infinities at exactly zero, and propagating a small positive sentinel through the math is cheaper than scattering `if x == 0.0` guards.

If the value annoys you (it propagates into rounded sizes that show up off-by-a-pixel), compensate around it (snap to integer at the boundary), don't remove it.

---

## Build commands and verification (`references/build.md` for the full version)

The render-wasm crate has its own build/test/lint commands documented in `render-wasm/AGENTS.md` (`./build`, `./test`, `./lint`, `cargo fmt --check`). `cargo check` against this crate is slow because it rebuilds Skia from source — prefer the project scripts. CLJS-side bridge changes are verified by CLJS compilation; Rust changes need a WASM rebuild before the frontend picks them up.

---

## Error handling: `Result<()>` and `#[wasm_error]`

WASM exports return `Result<()>` and are wrapped in `#[wasm_error]` (see `render-wasm/src/error.rs`). The macro converts a Rust error into a JS-visible exception. Inside an export, prefer `?` propagation; only return `Ok(())` at the end.

For panics (which shouldn't happen but do): they crash the WASM module, taking the workspace with them. If you're tempted to `unwrap()` something speculative, prefer `if let Some(...)` and an early return.

---

## Surface layering and `SurfaceId`

`SurfaceId` (`render-wasm/src/render/surfaces.rs:31`) names the layered Skia surfaces. The relevant ones:

- `SurfaceId::Target` — the final canvas the browser sees
- `SurfaceId::Backbuffer` — full-frame composite of all shapes
- `SurfaceId::Current` — per-shape sub-surface used during shape rendering
- `SurfaceId::UI` — overlays composited on top of `Target` each frame (selection rects, drag visuals, debug)
- `SurfaceId::Atlas`, `SurfaceId::Tiles` — the tile cache surfaces
- `SurfaceId::Fills`, `SurfaceId::Strokes`, `SurfaceId::InnerShadows` — sub-surfaces for shape effects

**Convention:** `SurfaceId::UI` is composited on top of the render target each frame in `render/ui.rs`. Anything drawn there should be world-space-transformed before draw (apply `scale(zoom*dpr) + translate(-vbox.left,-vbox.top)`). Selection rects use `skia::Path::polygon` + `canvas.draw_path`.

The CLJS side syncs UI overlays via `sync-selection-rects!` in `api.cljs` (clears + adds 4 corners per selected shape), driven by an effect in `viewport_wasm.cljs` that watches `[@canvas-init? selected-shapes transform]`.

---

## Dual rendering: DOM transparent + Skia visible

Text is rendered twice. The DOM contenteditable tree (V2 or V3) has `color: transparent` — it exists to capture native input/IME, selection ranges, and (in V2) cursor blink. Skia draws the visible glyphs on the render target.

**Implications:**

- DOM measurements (getBoundingClientRect, range rects) and Skia paragraph layout must stay in sync. The hook point is `update-text-rect!` in `api.cljs`, called when WASM layout is up-to-date.
- The editor instance lives at `:workspace-editor` in app state; the root DOM is `(.-root editor)`.
- `TextContentLayout.paragraphs` is `Vec<Vec<skia::textlayout::Paragraph>>`; the first inner element has the geometry the renderer uses.
- For V3 (`text-editor-wasm/v1`), the cursor and selection are *not* DOM — they're Skia overlays drawn on `SurfaceId::UI`, driven by `TextEditorState`. See `references/v3-text-editor.md`.

---

## Drag rendering: tile cache vs backbuffer crop cache

Two caches are involved during drag:

- **Tile cache** (`render-wasm/src/render.rs`, `tiles.rs`) — chunks the frame into tiles, caches rendered tiles, invalidates on modifier fan-out.
- **Backbuffer crop cache** (`render::rebuild_backbuffer_crop_cache`, `is_safe_for_drag_crop_cache`) — for non-overlapping top-level shapes, snapshots a region of `Backbuffer` once at drag start and blits it during the drag instead of re-rendering the whole shape.

`is_safe_for_drag_crop_cache` (`shapes.rs:1394`) gates whether a candidate's cached image is *used*. It rejects: shape itself is `Type::Text`, frames with `clip_content=false` whose visible content exceeds bounds, shapes with blur, shadows, opacity != 1.0, or non-default blend mode.

**Historical pitfall:** a frame containing text *children* passes the gate (it's `Type::Frame`, not `Type::Text`), but the snapshot may have been taken before the text's paragraph layout completed — yielding a glyph-less fill rect during drag. The lesson is that `is_safe_for_drag_crop_cache` doesn't recurse through descendants; if the snapshot timing matters for what's inside, you need additional capture-time gating, not read-time gating.

---

## Don't add features beyond what's asked

This isn't a render-wasm-specific rule, but it bites here often: render-wasm has half-finished WIPs (PDF export, abandoned drag-sprite work). Don't extrapolate from one example to a generalization in your edit. If a function does X and the task is "do Y", do Y; don't refactor the X path "while you're here". See the root `AGENTS.md` for the broader principle.

---

## Pointer hygiene and `unsafe`

The Rust side has several `unsafe` blocks (state global, transmute for binary props, FFI call sites). When adding more:

- Document the safety invariant inline (`// SAFETY: ...`) — what makes this `unsafe` block sound.
- Prefer wrapping into a safe macro/function so the `unsafe` is in one place.
- For binary deserialization, the safety invariant is "the byte buffer matches the struct layout" — and the `offset_of!` tests are what enforces that invariant.
