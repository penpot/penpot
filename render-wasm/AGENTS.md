# render-wasm – Agent Instructions

This component compiles Rust to WebAssembly using Emscripten +
Skia. It is consumed by the frontend as a canvas renderer.

## Commands

```bash
./build          # Compile Rust → WASM (requires Emscripten environment)
./watch          # Incremental rebuild on file change
./test           # Run Rust unit tests (cargo test)
./lint           # clippy -D warnings
cargo fmt --check
```

Run a single test:
```bash
cargo test my_test_name          # by test function name
cargo test shapes::              # by module prefix
```

Build output lands in `../frontend/resources/public/js/` (consumed directly by the frontend dev server).

## Build Environment

The `_build_env` script sets required env vars (Emscripten paths,
`EMCC_CFLAGS`). `./build` sources it automatically. The WASM heap is
configured to 256 MB initial with geometric growth.

## Architecture

**Global state** — a single `unsafe static mut State` accessed
exclusively through the `with_state!`, `with_state_mut!`,
`with_current_shape!`, `with_current_shape_mut!`, and
`with_state_mut_current_shape!` macros (defined at the top of
`src/main.rs`). Never access the global directly. Use
`with_current_shape_mut!` when modifying a shape — it calls
`state.touch_current()` to mark the shape dirty for re-render;
read-modify-writes that bypass it will silently skip invalidation.

**Tile-based rendering** — only 512×512 tiles within the viewport
(plus a pre-render buffer) are drawn each frame. Tiles outside the
range are skipped.

**Two-phase updates** — shape data is written via exported setter
functions (called from ClojureScript), then a single `render_frame()`
triggers the actual Skia draw calls.

**Shape hierarchy** — shapes live in a flat pool indexed by UUID;
parent/child relationships are tracked separately.

## Key Source Modules

| Path | Role |
|------|------|
| `src/main.rs` | WASM entry points (`init`, `clean_up`, render driver) and the state-access macros |
| `src/state.rs`, `src/state/` | Global `State` struct, `ShapesPool`, `TextEditorState` |
| `src/wasm/` | `#[no_mangle] extern "C"` exports grouped by subsystem (shapes, text, layouts, paths, fills, ...) |
| `src/render.rs`, `src/render/` | Render loop, tile cache, Skia surface management |
| `src/shapes.rs`, `src/shapes/` | Shape types, layouts, modifiers, transforms, per-shape draw inputs |
| `src/mem.rs` | WASM memory allocation (`write_bytes`, `free_bytes`) used by the FFI |

## Frontend Integration

The WASM module is loaded by `app.render-wasm.*` namespaces in the
frontend. ClojureScript calls exported Rust functions to push shape
data, then calls `render_frame`. Do not change export function
signatures without updating the corresponding ClojureScript bridge.

## Deeper Context

For longer-form architecture, conventions, V3 text editor internals,
and performance design lessons, see the `penpot-render-wasm` skill at
`.opencode/skills/penpot-render-wasm/`. This `AGENTS.md` is the
short-form module guide; the skill carries the depth.
