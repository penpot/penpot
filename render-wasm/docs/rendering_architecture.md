# Rendering Architecture: Live (GPU) vs Vector (PDF) Export

Penpot's WASM engine has **two render paths** that must produce the same picture:

| Path | Purpose | Backend | Code |
|------|---------|---------|------|
| **Live / GPU** | On-screen workspace, thumbnails, PNG export | WebGL surfaces + Skia | `render.rs::render_shape` (+ `render/{fills,strokes,shadows,text,...}.rs`) |
| **Vector** | True vector PDF (and future SVG) export | Single CPU Skia canvas (no GPU) | `render/vector.rs` → `render/pdf.rs` |

They share the same shape tree and the same low-level drawing primitives, but
compose them differently. Keeping them in sync is the whole game — see
[Parity guards](#parity-guards).

## Why two paths?

The live path draws each shape into **many intermediate GPU surfaces** (fills,
strokes, shadows, …) and composites them. Compositing rasterises. That is fine
for the screen and for PNG, but a PDF made that way would be a bitmap.

The vector path bypasses the GPU surface system and draws **directly onto a
Skia PDF canvas**, so paths, text and fills come out as real PDF vector
operations. Only inherently pixel-based effects (blur, blurred shadows) are
rasterised — by Skia's PDF backend, by design.

## The two pipelines

```mermaid
flowchart TB
    tree["Shape tree (ShapesPool)"]

    subgraph GPU["Live / GPU path — render.rs"]
        direction TB
        g0["render_shape(shape)"]
        g1["fast_mode? can_render_directly?<br/>tiles, clip stacks, nested fills/blurs"]
        gF["fills::render → Surface::Fills"]
        gS["strokes::render → Surface::Strokes"]
        gI["shadows::* → Surface::InnerShadows"]
        gD["drop shadows (tree level)<br/>→ Surface::DropShadows"]
        gC["draw_shape_surface_stack_into<br/>composite surfaces → final z-order"]
        g0 --> g1 --> gF --> gS --> gI --> gC
        gD --> gC
    end

    subgraph SHARED["Shared primitives (one source of truth)"]
        p1["draw_stroke_on_rect / draw_stroke_on_circle"]
        p2["handle_stroke_caps (arrows, markers)"]
        p3["render_inner_stroke / render_overlay_emoji (text)"]
    end

    subgraph VEC["Vector path — render/vector.rs"]
        direction TB
        v0["render_to_pdf → render_tree(shape)"]
        v1["render_group / render_frame / render_leaf<br/>concat centered_transform, save_layer for opacity/blur"]
        v2["draw_drop_shadows (inline)"]
        v3["render_leaf_content&lt;R: ShapeRenderer&gt;<br/>fills → fill inner shadows → strokes → stroke inner shadows"]
        v4["one Skia PDF canvas<br/>final z = draw call order"]
        v0 --> v1 --> v2 --> v3 --> v4
    end

    tree --> g0
    tree --> v0

    gS -.uses.-> SHARED
    v3 -.uses.-> SHARED
```

### Key differences

| Aspect | Live / GPU | Vector |
|--------|-----------|--------|
| Drawing target | Many GPU surfaces, then composited | One Skia PDF canvas |
| Final z-order | Surface composite order (`draw_shape_surface_stack_into`) | Order of draw calls |
| Drop shadows | Rendered at tree level into a separate surface (`render_element_drop_shadows_and_composite`) | Drawn inline per shape/container (`draw_drop_shadows` / `render_container_drop_shadows`) |
| Images | GPU textures | CPU image copies (`get_cpu_image`) |
| Blur / blurred shadow | GPU filter passes | Rasterised by Skia's PDF backend |
| Perf machinery | tiles, `fast_mode`, `can_render_directly` | none (one-shot export) |

## Transforms (important)

Both paths place a shape at the same world coordinates, but reach it
differently. For Path/Bool shapes:

- `centered_transform` (**C**) and `to_path_transform` (**P**) are exact
  inverses: `C · P = I`.
- `get_skia_path()` already bakes **P** into the geometry (`P · raw`).
- Live: canvas carries **C**, draws `get_skia_path()` → `C · P · raw = raw`.
- Vector: same — `render_leaf` concats **C**, `draw_shape_geometry` draws
  `get_skia_path()` directly. **Do not re-apply `to_path_transform`** — that
  double-counts and mis-positions/rotates the shape.

## Parity guards

Three compile-time guards plus shared code keep the two paths from drifting.
The contract is documented on the `ShapeRenderer` trait
(`render/shape_renderer.rs`).

1. **Capability guard.** `ShapeRenderer` is the single declaration of per-shape
   rendering capabilities (`draw_fills`, `draw_strokes`, `draw_drop_shadows`,
   …). A new effect MUST be added as a trait method, not inline in
   `render_shape`. Adding a method fails to compile until the vector backend
   handles it — so a feature can never be silently missing from PDF.
2. **Type guard.** Every `match` on `shape.shape_type` in `vector.rs` is
   exhaustive (no `_ =>`). A new `Type` variant fails to compile until handled.
3. **Order guard.** Leaf content draw order/gating lives in exactly one place:
   `vector::render_leaf_content<R: ShapeRenderer>`. It is generic over the
   trait so the GPU backend can reuse it verbatim once it implements
   `ShapeRenderer`.
4. **Shared primitives.** Prefer reusing the live-render functions over
   mirroring them: `draw_stroke_on_rect`, `draw_stroke_on_circle`,
   `handle_stroke_caps`, `render_inner_stroke`, `render_overlay_emoji`.
   Whatever is still duplicated is the remaining drift surface.

### Not yet done — full unification

The end goal is for `render_shape` to also implement `ShapeRenderer` (a
`GpuShapeRenderer` delegating to `fills::render` / `strokes::render` /
`shadows::*`) and route its leaf rendering through `render_leaf_content`, so
both paths share order and gating by construction. This is a large refactor of
the live hot path (tiles, `fast_mode`, surface compositing, tree-level drop
shadows) and **should be gated by golden PNG-vs-PDF-raster parity tests** — do
not refactor the live path without that safety net.

## File map

| What | Where |
|------|-------|
| Vector entry / PDF | `render/pdf.rs`, `render/vector.rs` |
| Parity trait | `render/shape_renderer.rs` |
| Order seam | `render/vector.rs::render_leaf_content` |
| Live shape render | `render.rs::render_shape` |
| Surface compositing | `render.rs::draw_shape_surface_stack_into` |
| Shared stroke geometry / caps | `render/strokes.rs` |
| Shared text render | `render/text.rs` |
