# figma-adapter

Translation layer from Figma plugin events to Penpot/skia-rs-wasm–compatible data. Consumed by plugin UIs (e.g. `figma_plugin_fe`) to turn Figma updates—such as `PageNode.on('change')` or `documentchange`—into `PenpotPage` and `Change[]` for the canvas.

## Build

From this directory:

```bash
npm install
npm run build
```

Output is in `dist/` (ES and CJS bundles plus `index.d.ts`).
