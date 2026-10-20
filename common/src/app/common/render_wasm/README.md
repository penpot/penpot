# `app.common.render-wasm.*`

The host-agnostic ClojureScript side of the render-wasm binary protocol: byte
layouts, memory helpers and serializers that turn Penpot shapes into the buffers
`render-wasm` consumes.

The workspace drives it from `app.render-wasm.*`, the headless exporter from
`app.wasm.*` — same code underneath, so the two cannot drift.

Font knowledge is *not* here even though both hosts need it for rendering: it is
not specific to the wasm backend, so the google fonts catalog (baked from
`common/resources/fonts/gfonts.*.json`), the bundled builtin family and the
emoji/script fallback tables live in `app.common.fonts`. Likewise the image-id
enumeration lives in `app.common.types.shape.images`.

`shared.js` is not here: it is a per-build artifact, so each host compiles
against the copy from its own render-wasm build and passes it to
`wasm/init-serializers!` (see `app.render-wasm.api.enums`, `app.wasm.enums`).

## Rules for anything added here

**Nothing here may depend on a browser (no DOM, no WebGL, no app state) or on
`frontend/src`.** Dependencies are `app.common.*` and this subtree only. It also
has to run under plain Node — a `js/document` here breaks the exporter.
