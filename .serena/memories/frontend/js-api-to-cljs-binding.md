# How the Plugin JS API connects to ClojureScript

## Type Definitions
- `plugins/libs/plugin-types/index.d.ts` contains TypeScript type declarations (e.g. `ShapeBase`, `LibraryComponent`).
- These are **type-only** — no runtime code. The actual objects are constructed in ClojureScript.

## Runtime Shape Proxy
- `frontend/src/app/plugins/shape.cljs` builds the JS shape proxy via `obj/reify`.
- Each method/property from the TS interface (e.g. `:component`, `:isComponentRoot`, `:componentHead`) is defined as a keyword entry in the `obj/reify` form, with a ClojureScript function as the implementation.
- The proxy is created by the `shape-proxy` function, which takes `plugin-id`, `file-id`, `page-id`, and shape `id`, and closes over them.

## Library Proxies
- `frontend/src/app/plugins/library.cljs` defines proxies for library types like `LibraryComponentProxy` (via `lib-component-proxy`), also using `obj/reify`.
- The proxy satisfies the `LibraryComponent` TS interface, exposing `.id`, `.name`, `.path`, etc.

## Circular Dependency Resolution
- `shape.cljs` and `library.cljs` have circular dependencies (shapes reference library component proxies and vice versa).
- `shape.cljs` declares forward references as mutable `def nil` vars (e.g. `(def lib-component-proxy nil)`, line 144).
- `frontend/src/app/plugins.cljs` patches them at load time: `(set! shape/lib-component-proxy library/lib-component-proxy)`.
- Same pattern for `lib-typography-proxy?` and `variant-proxy`.

## Key Domain Namespaces
- `app.common.types.component` (aliased `ctk`) — component predicates: `instance-root?`, `instance-head?`, `in-component-copy?`, `is-variant?`
- `app.common.types.container` (aliased `ctn`) — container/tree operations: `in-any-component?`, `get-instance-root`, `get-head-shape`, `inside-component-main?`
- `app.common.types.file` (aliased `ctf`) — file-level operations: `resolve-component`, `get-ref-shape`
