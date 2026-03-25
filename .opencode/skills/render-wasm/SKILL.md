---
name: penpot-render-wasm
description: Guidelines and workflows for the Penpot Rust to WebAssembly renderer.
---

# Penpot Render-WASM Skill

This skill provides guidelines and workflows for the Penpot Rust to WebAssembly renderer.

## Commands
- **Build:** `./build` (Compiles Rust → WASM. Requires Emscripten environment. Automatically sources `_build_env`)
- **Watch:** `./watch` (Incremental rebuild on file change)
- **Test (All):** `./test` (Runs cargo test)
- **Test (Single):** `cargo test my_test_name` or `cargo test shapes::`
- **Lint:** `./lint` (`clippy -D warnings`)
- **Format:** `cargo fmt --check`

## Architecture & Conventions
- **Global state:** Accessed EXCLUSIVELY through `with_state!` / `with_state_mut!` macros. Never access `unsafe static mut State` directly.
- **Tile-based rendering:** Only 512×512 tiles within the viewport are drawn each frame.
- **Two-phase updates:** Shape data is written via exported setter functions, then a single `render_frame()` triggers the actual Skia draw calls.
- **Frontend Integration:** The WASM module is loaded by `app.render-wasm.*` namespaces. Do not change export function signatures without updating the corresponding ClojureScript bridge.
