# render-wasm FFI

- For shape loading, tile invalidation and rendering see `mem:render-wasm/rendering`.
- The unsafe global `STATE` must be valid before calling the `with_state*` macros; invalid state is a panic, not a recoverable error. Inspect `#[wasm_error]` and the frontend bridge together when changing error handling: the macro clears the code on entry, frees the byte slot on failure and distinguishes recoverable `0x01` from critical `0x02`; the bridge maps them to `:non-blocking` and `:panic`.
- WASM byte transfer uses one global slot. Read and free a returned pointer before another payload is written, or the first payload is lost. Keep this ownership rule consistent in the frontend and exporter bridges.
