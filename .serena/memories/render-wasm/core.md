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

- Linux builds require `flock` (util-linux).
- `build`, target cleanup, and each watch rebuild share `render-wasm/.render-wasm-build.lock` per checkout. The dispatcher for both targets does not hold the lock in its parent process.
- The lock covers setup, Cargo build, and artifact copy. The watch process releases it while waiting for changes. The operating system releases it on success, error, or signal; the file remains.
- The lock only coordinates repository scripts. Manual Cargo commands do not take it.
- Set `RENDER_WASM_LOCK_FILE` to the same path only when separate checkouts intentionally share a `CARGO_TARGET_DIR`.

## Commands

From `render-wasm/`:
- Build/copy frontend artifacts: `./build [frontend|export]`; no target builds frontend, then export.
- Clean one target: `./clean [frontend|export]`; never run root `cargo clean` on the shared `target/`.
- Watch rebuild: `./watch [frontend|export]`; its initial and change-triggered builds use `./build`.
- Rust tests: `./test` or `cargo test <name>`.
- Cross-cutting testing principles and anti-patterns: `mem:testing`.
- Lint: `./lint`.
- Format check: `cargo fmt --check`.

Do not change exported WASM function signatures without updating the corresponding frontend bridge and verifying the frontend renderer path.