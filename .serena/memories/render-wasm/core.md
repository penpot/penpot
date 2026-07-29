# render-wasm Architecture and Workflow

`render-wasm/`: Rust crate compiled to WebAssembly via Emscripten/Skia; frontend loads generated JS/WASM renderer. FFI/memory/tile behavior: `mem:render-wasm/ffi-rendering-subtleties`.

## Stable Architecture

- Exported functions live around `src/main.rs` / `src/wapi.rs` and are called from ClojureScript bridge namespaces under `frontend/src/app/render_wasm*`.
- Updates are two-phase: ClojureScript calls exported setters to push shape data, then `render_frame()` performs Skia drawing.
- Rendering is tile-based and shape data is stored separately from hierarchy.

## Source Areas

- `src/state*`: renderer state structures.
- `src/render/` and `src/render.rs`: tile/surface render pipeline.
- `src/shapes/` and `src/shapes.rs`: shape data and Skia drawing.
- `src/wasm/`, `src/wasm.rs`, `src/mem.rs`: JS/WASM memory and interop helpers.
- `src/math/` and `src/view.rs`: geometry and viewport helpers.

## Build Environment

`./build` sources `_build_env`, which sets the Emscripten paths and `EMCC_CFLAGS`. The WASM heap starts at 256 MB and uses geometric growth.

## Commands

From `render-wasm/`:
- Build/copy frontend artifacts: `./build`.
- Watch rebuild: `./watch`.
- Rust tests: `./test` or `cargo test <name>`.
- Cross-cutting testing principles and anti-patterns: `mem:testing`.
- Lint: `./lint`.
- Format check: `cargo fmt --check`.

Do not change exported WASM function signatures without updating the corresponding frontend bridge and verifying the frontend renderer path.