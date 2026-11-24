# render-wasm

- `render-wasm/` compiles the Rust/Skia renderer to WebAssembly. Setters called from the CLJS bridge update shape state; `render_frame` draws it. Change exported signatures together with the frontend bridge.
- State, error codes and byte-buffer ownership: `mem:render-wasm/ffi`. Shape loading, tile invalidation and rendering: `mem:render-wasm/rendering`.
- Build commands, artifact locations and lock behavior: `render-wasm/README.md`. Generated JS/WASM and each target's `shared.js` are gitignored; rebuild, never edit or commit them.
- Build and cleanup scripts share a checkout lock, but manual Cargo commands do not. Checkouts that intentionally share a `CARGO_TARGET_DIR` must use the same `RENDER_WASM_LOCK_FILE`. Never run root `cargo clean` on the shared `target/`; use target-specific cleanup.
- For lint, native tests and formatting see `mem:scripts/ci`; native coverage excludes wasm-only `cfg` code. Cross-cutting test design: `mem:testing`.
