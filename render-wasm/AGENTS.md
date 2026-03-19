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
exclusively through `with_state!` / `with_state_mut!` macros. Never
access it directly.

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
| `src/lib.rs` | WASM exports — all functions callable from JS |
| `src/state.rs` | Global `State` struct definition |
| `src/render/` | Tile rendering pipeline, Skia surface management |
| `src/shapes/` | Shape types and Skia draw logic per shape |
| `src/wasm/` | JS interop helpers (memory, string encoding) |

## Frontend Integration

The WASM module is loaded by `app.render-wasm.*` namespaces in the
frontend. ClojureScript calls exported Rust functions to push shape
data, then calls `render_frame`. Do not change export function
signatures without updating the ClojureScript bridge.
