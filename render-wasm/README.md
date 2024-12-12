# render-wasm

Canvas-based WebAssembly render engine for Penpot.

This is a Rust crate that targets [Emscripten](https://emscripten.org/) (`wasm32-unknown-emscripten`). Underneath, it uses Skia via [custom binaries](https://github.com/penpot/skia-binaries/releases/) of the [rust-skia crate](https://github.com/rust-skia/rust-skia).

## How to build

With the [Penpot Development Environment](https://help.penpot.app/technical-guide/developer/devenv/) running, create a new tab in the tmux.

```sh
cd penpot/render-wasm
./build
```

The build script will compile the project and copy the `.js` and `.wasm` files to their correct location within the frontend app.

Edit your local `frontend/resources/public/js/config.js` to add the following flags:

- `enable-feature-render-wasm` to enable this render engine.
- `enable-render-wasm-dpr` (optional), to enable using the device pixel ratio.

## Docs

- [Serialization](./docs/serialization.md)
