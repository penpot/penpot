# TO DO

- [x] Compile Skia.
- [x] Compile simple `renderer.wasm` with a exported function.
- [ ] Compile a `renderer.wasm` that uses a WebGL context.

## Notes

- I've used the Skia `main` branch and it looks that there's something missing from the last release (`chrome/m117`) so I tried to switch to that branch but now I have different issues.

- It is necessary to use the GL emscripten module to deal with WebGL contexts. See `js/preamble.js` and
