---
name: penpot-render-wasm
description: Penpot render-wasm context loader — architecture, conventions, gotchas, and file map for the Rust/WASM render layer (`render-wasm/`) and its ClojureScript bridge (`frontend/src/app/render_wasm/`). Trigger this aggressively whenever the user is working on, planning, reviewing, debugging, or asking about anything render-wasm in Penpot. Specifically fire on: writing or editing Rust files under `render-wasm/src/`; writing or editing CLJS files under `frontend/src/app/render_wasm/` or workspace viewport/shape rendering code; planning a render-wasm refactor or feature (PDF export, drag/render path, binary props, WASM bindings, V3 text editor, tile cache, atlas); debugging render-wasm bugs (rendering glitches, crashes, panics, perf regressions, layout bugs, drag/zoom/pan bugs, text cursor/selection bugs); answering "how does X work" questions about the render layer; reviewing render-wasm diffs or PRs. Adjacent topics that should also fire: WASM↔CLJS FFI, Skia, dual-rendering (DOM transparent text + Skia visible), `SurfaceId`, `with_current_shape!` / `with_current_shape_mut!`, binary prop alignment / `transmute` conventions, `mem::write_bytes` / `mem::free_bytes`, the V3 text editor (`text-editor-wasm/v1`), drag/atlas/tile cache, `gesture_record!` instrumentation. Lean toward firing — if the user is in this codebase and the topic touches rendering, fire.
---

# Penpot render-wasm

A pre-loaded context bundle for working on Penpot's render-wasm layer: the Rust + Skia render engine compiled to WebAssembly and the ClojureScript code that drives it.

## When this skill fires

Aggressively. The repo has several intertwined subsystems where a small change in one corner breaks invariants in another. The point of loading this skill is to put the relevant invariants in front of you *before* you write code, plan a refactor, or chase a bug.

Concretely: render-wasm work is anything under `render-wasm/`, `frontend/src/app/render_wasm/`, the workspace viewport renderer, or the shape/text rendering pipeline. If you're editing those, planning changes, debugging issues, or even just answering questions about how things work, this skill is in scope.

## Mental model in one paragraph

There is a single Rust render engine compiled to WASM. It owns a `State` (with a `ShapesPool`, a `RenderState`, and friends), drives Skia to render shapes onto layered surfaces, and exposes `#[no_mangle] extern "C"` functions to JS. The ClojureScript side (`frontend/src/app/render_wasm/api.cljs` and friends) calls these functions synchronously from the main thread to push shape data, drive interactive transforms, and request renders. Text is *dual-rendered*: a contenteditable DOM tree provides editing/selection input with `color: transparent`, while Skia draws the visible glyphs. Cursor and selection overlays are drawn on a dedicated `SurfaceId::UI` surface composited on top each frame. Drag/zoom/pan use a tile cache plus an interactive backbuffer crop cache; the V3 text editor (behind `text-editor-wasm/v1`) moves all editing state and overlays into Rust + Skia.

## Decide what to read

This skill bundles six references. Don't read them all unless you're orienting from scratch — pick by what you're about to do.

| You are about to... | Read |
|---|---|
| Write or edit Rust in `render-wasm/src/` | `references/conventions.md`, then any subsystem-specific reference |
| Add a `#[no_mangle]` WASM export | `references/conventions.md` (binary props, `with_current_shape!`, `mem::write_bytes`/`free_bytes`) |
| Add or modify a CLJS↔WASM bridge call | `references/conventions.md` + `references/file-map.md` (api.cljs section) |
| Plan a render-wasm refactor | `references/architecture.md`, then `references/file-map.md` |
| Debug a rendering glitch / crash / perf | `references/architecture.md` + `references/perf.md` |
| Touch the V3 text editor | `references/v3-text-editor.md` |
| Touch drag/zoom/pan/atlas/tile cache | `references/perf.md` (drag instrumentation, backbuffer crop cache pitfalls, why the drag-sprite approach was abandoned) |
| Touch blur/shadow/filter rendering | `references/perf.md` (blur perf section) |
| Verify a change builds | `references/build.md` (project commands; rebuild matrix per change type) |
| Answer "where is X" without making changes | `references/file-map.md` |

## Workflow shapes

### Writing or editing render-wasm code

1. **Identify the subsystem you're touching.** Use `references/file-map.md` to find the canonical files.
2. **Load the conventions for that subsystem** — at minimum the global ones in `references/conventions.md`. If you're touching binary deserialization, layout (flex/grid), text, or anything that runs on the worker thread, read the matching subsection in `conventions.md`.
3. **Make the edit.** Cite invariants when you choose between approaches (e.g., "using `with_current_shape!` because we only need to read"; "guarding `document` access because this can be called from the dashboard worker").
4. **Build using the project scripts.** Use `./build`, `./test`, `./lint` from `render-wasm/` (see `render-wasm/AGENTS.md` and `references/build.md`). Avoid `cargo check` — it tries to rebuild Skia from source and is much slower than the project scripts. CLJS-side bridge changes are verified by CLJS compilation; Rust changes need a `./build` before the frontend picks them up.

### Planning a refactor

1. **Read `references/architecture.md`** to refresh the mental model — especially state ownership, surface layering, and the render loop shape.
2. **Read the subsystem reference(s)** for whatever the refactor touches.
3. **Write a plan.** Call out which invariants the refactor preserves and which it changes. Convention violations are a source of bugs that don't show in tests; surface them explicitly.

### Debugging

1. **Triage by symptom** in `references/perf.md` (perf) or by file in `references/file-map.md`.
2. **Cross-check known pitfalls** in `references/conventions.md` — many "weird" bugs are convention violations (binary prop alignment, missing `free_bytes`, calling `document` from a worker).
3. **Use the `gesture_record!` macro** if you need per-stage timings during a gesture; the macro lives in `render-wasm/src/performance.rs`. See `references/perf.md` for the stage list and how to wire the CLJS receiver back in.
4. **Static analysis is usually enough.** Penpot's render-wasm is well-instrumented and well-named; reading the relevant call sites with citations is faster than capturing a profile in most cases.

## Relationship to AGENTS.md

This skill complements the per-module `AGENTS.md` files (`render-wasm/AGENTS.md`, `frontend/AGENTS.md`). AGENTS.md is the authoritative source for module commands (build, test, lint) and short-form module orientation; the skill carries the deeper architecture, conventions, V3 internals, and perf design lessons that don't fit AGENTS.md's brevity. Read AGENTS.md for "how to work in this module"; read the references here for "how the render layer actually works".

## What this skill does NOT cover

- **Backend (Clojure / Postgres) and the asset pipeline.** Different codebase, different conventions.
- **Pure-CLJS UI work outside the render pipeline** (sidebar forms, modal dialogs, etc.). Use Penpot's general patterns; this skill is render-specific.
- **Standalone text editor (`frontend/text-editor/`)** unless it interacts with V3 — the embedded editor is the V3 case.
