# Agent guide for `render-wasm`

Rust crate compiled to WebAssembly via Emscripten + Skia. Provides the 2D canvas rendering backend consumed by both the Penpot frontend (ClojureScript) and `skia-rs-wasm` (TypeScript).

## Commands

```bash
./build              # Compile Rust → WASM (sources _build_env automatically)
./build release      # Release build
./watch              # Incremental rebuild on file change (debug)
./watch release      # Watch in release mode
./test               # Run unit tests on x86_64-unknown-linux-gnu (no Emscripten needed)
./lint               # clippy -D warnings
cargo fmt --check    # Format check

# Run a targeted test
cargo test my_test_name    # by test function name
cargo test shapes::        # by module prefix
```

Build artifacts land in `../frontend/resources/public/js/` (`render-wasm.js` + `render-wasm.wasm`), consumed directly by the frontend dev server.

## Build environment

`_build_env` sets required env vars (Emscripten paths, `EMCC_CFLAGS`). `./build` sources it automatically.

Key Emscripten flags:
- Target: `wasm32-unknown-emscripten`
- Heap: 256 MB initial, geometric growth (0.8 step), dlmalloc
- Exported runtime: `GL, stringToUTF8, HEAPU8, HEAP32, HEAPU32, HEAPF32`
- Export name: `createRustSkiaModule` (ES6 module via `-sMODULARIZE=1 -sEXPORT_ES6=1`)
- WebGL 2 (`-sMAX_WEBGL_VERSION=2`)
- Custom JS bindings: `src/js/wapi.js` (requestAnimationFrame wrapper for both main thread and workers)

## Architecture

### Global state

Single `unsafe static mut STATE: Option<Box<State>>` — access **only** via the provided macros:

```rust
with_state!(|s| { … })        // immutable borrow
with_state_mut!(|s| { … })    // mutable borrow
with_current_shape!(|sh| …)   // borrow current shape (STATE.current_id)
with_current_shape_mut!(…)    // mutable borrow current shape
```

Never access `STATE` directly.

### `State` struct (`src/state.rs`)

```rust
pub struct State {
    pub render_state: RenderState,       // GPU surfaces, tiles, fonts, images, viewport
    pub text_editor_state: TextEditorState,
    pub current_id: Option<Uuid>,        // shape being set up by setter calls
    pub current_browser: u8,
    pub shapes: ShapesPool,              // all live shapes
    pub saved_shapes: Option<ShapesPool>, // snapshot for temp/undo objects
}
```

### ShapesPool (`src/state/shapes_pool.rs`)

Index-based design to avoid Rust lifetime issues with mutable references:
- `Vec<Shape>` — contiguous allocation (reserves 1.3× on growth)
- `HashMap<Uuid, usize>` — UUID → index lookup
- Auxiliary `HashMap<usize, …>` maps for overrides:
  - `modifiers` — `skia::Matrix` transform overrides
  - `structure` — layout `StructureEntry` changes
  - `scale_content` — per-shape scale overrides
  - `fill_modifiers` — `Vec<Fill>` for live fill preview
  - `modified_shape_cache` — `OnceCell<Shape>` lazily combines base + all overrides

`get(id)` returns the modified (combined) shape when overrides exist, otherwise the raw shape. This is the standard access path.

Temp objects: `start_temp_objects()` / `end_temp_objects()` swaps the pool for undo/redo preview.

### Tile-based rendering

Canvas divided into 512×512 px tiles. Only tiles within the viewport (plus a 3-tile border) are drawn each frame. `TileViewbox` tracks the visible + interest area. Touch tracking (`touch_shape()`) marks only changed tiles for redraw.

### Two-phase update model

1. **Setup phase**: JS calls exported setter functions (`use_shape`, `set_shape_fills`, `set_shape_transform`, …) to push data into `ShapesPool`.
2. **Render phase**: JS calls `render()` or `render_sync()` to trigger Skia draw calls.

Do not mix phases — setters do not trigger rendering.

## Key source modules

| Path | Role |
|------|------|
| `src/main.rs` | All WASM-exported `extern "C"` functions (≈863 lines) |
| `src/state.rs` | `State` struct, lifecycle (init, clean_up) |
| `src/state/shapes_pool.rs` | Index-based shape storage, modified shape cache |
| `src/render.rs` | Core tile rendering pipeline, Skia surface management |
| `src/shapes.rs` | `Shape` struct, `Type` enum, all shape properties |
| `src/shapes/fills.rs` | `Fill` enum (Solid, Linear/Radial/AngularGradient, Image) |
| `src/shapes/strokes.rs` | Stroke properties |
| `src/shapes/text.rs` | Rich text, paragraphs, spans, font metadata |
| `src/shapes/paths.rs` | Vector path commands, fill rules |
| `src/shapes/modifiers/` | Flex/grid layout engines, constraints |
| `src/wasm/fills.rs` | `RawFillData` C-repr enum for JS → Rust fill serialization |
| `src/wasm/` | All JS interop types and deserializers |
| `src/mem.rs` | `BUFFERU8` + `BUFFER_ERROR` — binary data exchange with JS |
| `src/tiles.rs` | Tile grid, viewport management |
| `src/math.rs` | Vector math, matrix ops, boolean shape ops |
| `src/performance.rs` | Timing macros (`begin_measure!`, `end_measure!`) |
| `src/js/wapi.js` | Emscripten JS library (RAF wrapper for thread-safe use) |
| `macros/` | Proc-macros: `#[wasm_error]`, `#[ToJs]` |

## Memory / JS interop model (`src/mem.rs`)

- **Data in**: JS writes bytes to `BUFFERU8` before calling a setter; Rust reads via `bytes()` / `bytes_or_empty()`.
- **Data out**: Rust writes to `BUFFERU8` via `write_bytes()`, returns pointer; JS reads from WASM heap.
- **Errors**: `BUFFER_ERROR` byte — `0x01` recoverable, `0x02` critical. JS reads via `read_error_code()` after every call.

## `#[wasm_error]` macro

Wraps every exported function body in `panic::catch_unwind()`. On `Err` or panic: writes error code to `BUFFER_ERROR`, then resumes unwind so ClojureScript/JS catch it. Always clears error code on entry.

## Fill serialization (`src/wasm/fills.rs`)

```rust
#[repr(C, u8, align(4))]
pub enum RawFillData {
    Solid(RawSolidData) = 0x00,
    Linear(RawGradientData) = 0x01,
    Radial(RawGradientData) = 0x02,
    Image(RawImageFillData) = 0x03,
    Angular(RawGradientData) = 0x04,
}
```

`set_shape_fills()` deserializes the buffer into `Vec<Fill>` and replaces all fills on the current shape. `add_shape_fill()` appends a single fill.

## Exported function groups

| Group | Key functions |
|-------|--------------|
| Lifecycle | `init(w,h)`, `clean_up()`, `resize_viewbox(w,h)` |
| Rendering | `render()`, `render_sync()`, `render_sync_shape(a,b,c,d)`, `render_from_cache()`, `process_animation_frame(ts)` |
| Shape setup | `init_shapes_pool(cap)`, `use_shape(a,b,c,d)`, `set_parent(…)`, `set_children_0..5(…)` |
| Properties | `set_shape_selrect`, `set_shape_rotation`, `set_shape_transform`, `set_shape_opacity`, `set_shape_hidden`, `set_shape_clip_content`, `set_shape_corners` |
| Fills/Strokes | `set_shape_fills()`, `add_shape_fill()` |
| View | `set_view(zoom,x,y)`, `set_view_start()`, `set_view_end()` |
| Modifiers | `set_structure_modifiers()`, `set_modifiers()` |
| Undo | `start_temp_objects()`, `end_temp_objects()` |
| Query | `get_selection_rect()`, `get_grid_coords()` |

**Never change export function signatures without updating the consuming bridge** (ClojureScript `app.render-wasm.*` namespaces, and `skia-rs-wasm` `src/lib/renderer/api/`).

## Key dependencies (`Cargo.toml`)

| Crate | Version | Purpose |
|-------|---------|---------|
| `skia-safe` | 0.93.1 | 2D graphics (gl, svg, textlayout, binary-cache, webp features) |
| `glam` | 0.24.2 | Vector/matrix math |
| `bezier-rs` | 0.4.0 | Bezier curve calculations |
| `uuid` | 1.11.0 | Shape identifiers (v4 + js features) |
| `indexmap` | 2.7.1 | Ordered hash maps |
| `thiserror` | 2.0.18 | `#[derive(Error)]` |
| `macros` (local) | — | `#[wasm_error]`, `#[ToJs]` proc-macros |
