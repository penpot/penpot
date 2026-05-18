# Build and Verification

How to build, test, and verify render-wasm changes. Authoritative source for module commands is `render-wasm/AGENTS.md`; this reference adds context that doesn't fit there.

---

## Module commands (`render-wasm/AGENTS.md`)

```bash
./build              # Compile Rust → WASM (requires Emscripten environment)
./watch              # Incremental rebuild on file change
./test               # Run Rust unit tests (cargo test)
./lint               # clippy -D warnings
cargo fmt --check    # Format check
```

Single-test invocations:

```bash
cargo test my_test_name      # by test function name
cargo test shapes::          # by module prefix
```

Build output lands in `../frontend/resources/public/js/` and is consumed directly by the frontend dev server.

The `_build_env` script sets the Emscripten paths and `EMCC_CFLAGS`. `./build` sources it automatically. The WASM heap is configured to 256 MB initial with geometric growth.

**About `cargo check`:** running `cargo check` on this crate tries to rebuild Skia from source, which is slow (minutes) and frequently fails on environment issues. The project scripts (`./build`, `./test`, `./lint`) drive the right toolchain and are the practical fast-feedback loop. Reach for `cargo check` only when you specifically want to validate something the project scripts don't cover.

---

## What changes require which rebuild

| Change | Required build |
|---|---|
| CLJS-only (e.g., `api.cljs` bridge logic, viewport components) | CLJS hot-reload picks it up |
| Rust in `render-wasm/src/` | `./build` (WASM rebuild) |
| WASM export signature change (`#[no_mangle] extern "C"` function) | `./build` AND update CLJS callers |
| Binary prop layout change (`Raw*Data` struct) | `./build` AND update JS-side encoder in lockstep |

**Binary prop layout changes are the highest-risk class** because the `offset_of!` tests catch Rust-side drift but there's no equivalent on the JS encoder side. Always touch both ends in the same change and call out the JS-encoder update explicitly in the commit/PR.

---

## Verifying frontend integration

For CLJS-only bridge changes (`frontend/src/app/render_wasm/api.cljs` and friends):

- CLJS shadow-cljs hot-reloads on edit; the dev server picks up the change.
- Reload the workspace tab to re-init the WASM module if you've changed init-time code.
- Playwright tests for render-wasm: `npx playwright test --project=render-wasm` from `frontend/`. Config at `frontend/playwright.config.js`; the render-wasm project runs at 1920x1080, 2x DPR. Fixtures at `frontend/playwright/data/render-wasm/get-file-*.json`.

---

## Debugging tools

- **Browser DevTools — Performance tab.** Capture during the gesture you're investigating. The flamegraph names the suspect functions directly.
- **`gesture_record!`** (see `perf.md`) — in-Rust per-stage timing emitted via `run_script!` to a JS receiver (`window.__penpotGestureRecord`). The macro is always compiled (cheap when the receiver is undefined); the CLJS-side buffer/report code lived at `frontend/src/app/util/perf.cljs` and is recoverable from history.
- **`#[wasm_error]`** macro on exports surfaces panics as JS exceptions. Without it, a panic crashes the whole module — the workspace dies.
- **`run_script!("console.log(...)")`** for ad-hoc Rust → console prints during dev. Don't ship these.

---

## When `./build` fails

Most likely causes, in order:

1. A new `#[no_mangle] extern "C"` export with a parameter type that doesn't cross the FFI boundary cleanly.
2. A `transmute` that violates layout assumptions — usually `RAW_*_SIZE` is wrong or a struct field was reordered. The `offset_of!` tests should catch this; if they pass and the build still fails, suspect the JS-side encoder mismatch.
3. A missing `?` propagation on a `Result` inside a `#[wasm_error]` function.
4. Toolchain drift, especially after a major Skia bump or Emscripten upgrade.

Read the build output before guessing — it usually points at the file directly.
