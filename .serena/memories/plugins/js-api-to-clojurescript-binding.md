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

## Helper Utilities (`frontend/src/app/plugins/utils.cljs`)
- `locate-shape` — finds a shape by file-id, page-id, id
- `locate-objects` — gets the object tree for a page
- `locate-component` — finds the **outermost** instance root and resolves the component (uses `ctn/get-instance-root` + `ctf/resolve-component`). **Beware**: walks to outermost root, not nearest head.
- `locate-library-component` — direct lookup by file-id and component-id from file data
- `locate-file` — looks up a file by id from state

## Key Domain Namespaces
- `app.common.types.component` (aliased `ctk`) — component predicates: `instance-root?`, `instance-head?`, `in-component-copy?`, `is-variant?`
- `app.common.types.container` (aliased `ctn`) — container/tree operations: `in-any-component?`, `get-instance-root`, `get-head-shape`, `inside-component-main?`
- `app.common.types.file` (aliased `ctf`) — file-level operations: `resolve-component`, `get-ref-shape`

## Shape Component Data
- Component instance shapes carry `:component-id` and `:component-file` attributes directly on the shape map.
- `:component-root` flag indicates if a shape is the root of a component instance.
- `get-head-shape` finds the nearest component head (the topmost shape of the nearest component instance), while `get-instance-root` finds the outermost root.

## Pattern for Looking Up a Shape's Own Component
Use `ctn/get-head-shape` to find the nearest head, then read `:component-id` and `:component-file` from it:
```clojure
(let [head (ctn/get-head-shape objects shape)]
  (lib-component-proxy plugin-id (:component-file head) (:component-id head)))
```
Do NOT use `locate-component` / `get-instance-root` if you want the nearest component — those walk to the outermost ancestor.
