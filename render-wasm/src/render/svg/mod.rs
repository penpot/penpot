use std::collections::HashSet;

use skia_safe::{self as skia};

use crate::error::Result;
use crate::render::vector::ExportState;
use crate::shapes::Type;
use crate::state::ShapesPoolRef;
use crate::uuid::Uuid;

use super::RenderState;

/// Collects the registered font aliases used by every text span in the subtree
/// rooted at `id`, so the exporter can emit `@font-face` rules for them.
fn collect_font_aliases(tree: ShapesPoolRef, id: &Uuid, out: &mut HashSet<String>) {
    let Some(shape) = tree.get(id) else {
        return;
    };

    if let Type::Text(_) = &shape.shape_type {
        for paragraph in shape.get_text_content().paragraphs() {
            for span in paragraph.children() {
                out.insert(format!("{}", span.font_family));
            }
        }
    }

    for child_id in shape.children_ids_iter_forward(true) {
        collect_font_aliases(tree, child_id, out);
    }
}

/// Renders a shape tree to an SVG document and returns the raw SVG bytes.
///
/// Dedicated vector-SVG render path. Leaf content (paths, text, fills, images)
/// is emitted as real SVG markup via short-lived Skia SVG canvases, while
/// composite effects (container/leaf opacity, blend mode, layer blur, masks)
/// are composed as native SVG `<g>` wrappers (`opacity`, `mix-blend-mode`,
/// `filter="feGaussianBlur"`, `clip-path`). This keeps everything vectorial and
/// faithful to the GPU/PDF output, sidestepping `SkSVGDevice`'s inability to
/// keep `save_layer` content.
///
/// Text is emitted as real `<text>` elements (no `CONVERT_TEXT_TO_PATHS`), so
/// the output stays selectable/editable. Skia's SVG backend does not embed
/// fonts, so we inject `@font-face` rules that reference the source URLs
/// registered at load time.
pub fn render_to_svg(
    shared: &mut RenderState,
    id: &Uuid,
    tree: ShapesPoolRef,
    scale: f32,
) -> Result<Vec<u8>> {
    let mut ctx = ExportState {
        fonts: &shared.fonts,
        images: Some(&mut shared.images as &mut dyn super::ImageProvider),
        sampling_options: shared.sampling_options,
    };
    render_tree_to_svg(&mut ctx, id, tree, scale)
}

/// Core SVG export, decoupled from `RenderState` via [`ExportState`] so it can
/// run on a plain CPU Skia canvas (and in headless native tests, which cannot
/// build a GPU-backed `RenderState`).
pub(crate) fn render_tree_to_svg(
    shared: &mut ExportState,
    id: &Uuid,
    tree: ShapesPoolRef,
    scale: f32,
) -> Result<Vec<u8>> {
    let text_ctx = shared.text_ctx(crate::utils::Browser::Chrome);

    let shape = tree
        .get(id)
        .ok_or_else(|| crate::error::Error::CriticalError("Shape not found for SVG".to_string()))?;
    let bounds = shape.extrect(tree, scale, &text_ctx);

    let page_w = bounds.width() * scale;
    let page_h = bounds.height() * scale;
    let rect = skia::Rect::from_xywh(0., 0., page_w, page_h);

    let (defs, body) =
        render_body(shared, id, tree, scale, rect, -bounds.left(), -bounds.top())?;

    // Emit @font-face rules for fonts used in the subtree so viewers can load them.
    let mut aliases = HashSet::new();
    collect_font_aliases(tree, id, &mut aliases);
    let font_css = shared.fonts.font_face_css_for_aliases(&aliases);

    let mut out = String::with_capacity(body.len() + defs.len() + font_css.len() + 256);
    out.push_str("<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n");
    out.push_str(&format!(
        "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" \
         width=\"{page_w}\" height=\"{page_h}\">"
    ));

    if !font_css.is_empty() || !defs.is_empty() {
        out.push_str("<defs>");
        if !font_css.is_empty() {
            out.push_str(&format!(
                "<style type=\"text/css\"><![CDATA[{font_css}]]></style>"
            ));
        }
        out.push_str(&defs);
        out.push_str("</defs>");
    }

    out.push_str(&body);
    out.push_str("</svg>");

    Ok(out.into_bytes())
}

mod document;
mod frames;
mod groups;
mod images;
mod masks;
mod shadows;
mod strokes;
mod text;

pub(crate) use document::SvgLayerCanvas;
pub(crate) use images::{
    emit_linked_image_fill, emit_linked_image_stroke,
};

use crate::render::vector::{render_leaf_content, VectorRenderer, VectorTarget};
use crate::shapes::Shape;
use document::effect_attrs;
use frames::render_frame;
use groups::render_group;
use shadows::{drop_shadow_attr, render_inner_shadows};
use strokes::{render_image_strokes, render_path_outer_strokes, render_rect_circle_dotted_strokes};
use text::{render_text_alpha_strokes, render_text_inner_strokes, render_text_outer_strokes};

/// Renders `id`'s subtree to an SVG body, returning `(defs, body)`.
fn render_body(
    shared: &mut ExportState,
    id: &Uuid,
    tree: ShapesPoolRef,
    scale: f32,
    page_rect: skia::Rect,
    tx: f32,
    ty: f32,
) -> Result<(String, String)> {
    let mut builder = SvgLayerCanvas::new(scale, page_rect, tx, ty);
    render_tree(&mut builder, shared, id, tree, scale, true)?;
    builder.flush();
    Ok((builder.defs, builder.out))
}

/// `render_shadows` is `false` while building a container's drop-shadow
/// silhouette: descendants must contribute their *shape* alpha but not their own
/// drop shadows (matching the GPU, which clears nested shadows when rendering a
/// container shadow). It is `true` for the normal document render.
fn render_tree(
    builder: &mut SvgLayerCanvas,
    shared: &mut ExportState,
    id: &Uuid,
    tree: ShapesPoolRef,
    scale: f32,
    render_shadows: bool,
) -> Result<()> {
    let Some(element) = tree.get(id) else {
        return Ok(());
    };
    if element.hidden {
        return Ok(());
    }

    match &element.shape_type {
        Type::Group(group) => {
            render_group(builder, shared, element, group.masked, tree, scale, render_shadows)
        }
        Type::Frame(_) => render_frame(builder, shared, element, tree, scale, render_shadows),
        Type::Rect(_)
        | Type::Circle
        | Type::Path(_)
        | Type::Bool(_)
        | Type::Text(_)
        | Type::SVGRaw(_) => render_leaf(builder, shared, element, scale, render_shadows),
    }
}

fn render_leaf(
    builder: &mut SvgLayerCanvas,
    shared: &mut ExportState,
    element: &Shape,
    scale: f32,
    render_shadows: bool,
) -> Result<()> {
    let effects = effect_attrs(builder, element, scale);
    if let Some(attrs) = &effects {
        builder.open_group(attrs);
    }

    // Drop shadows sit behind (and below the opacity/blend layer of) the shape.
    // `SkSVGDevice` drops the GPU/PDF `save_layer` shadow, so emit a native SVG
    // filter and let it produce the shadow + the shape on top. Suppressed while
    // rendering a parent container's shadow silhouette (`render_shadows` false).
    let shadow = if render_shadows {
        drop_shadow_attr(builder, element, scale, true)
    } else {
        None
    };
    if let Some(attrs) = &shadow {
        builder.open_group(attrs);
    }

    {
        let matrix = element.centered_transform();
        let mut renderer =
            VectorRenderer::new_svg_layer(builder, shared, scale, VectorTarget::Svg);
        {
            let canvas = renderer.skia_canvas();
            canvas.save();
            canvas.concat(&matrix);
        }
        render_leaf_content(&mut renderer, element)?;
        {
            let canvas = renderer.skia_canvas();
            canvas.restore();
        }
    }

    // Inner shadows compose inside a `save_layer` the SVG backend drops; re-emit
    // them natively over the fill (matches the GPU fill/inner-shadow/stroke order).
    render_inner_shadows(builder, shared, element, scale)?;

    // Outer strokes on closed paths/bools are deferred by the shared renderer on
    // SVG (their `save_layer` + `Clear` composition is dropped by `SkSVGDevice`);
    // re-emit them as a nested `<g>` clipped to the shape's exterior.
    render_path_outer_strokes(builder, element, scale)?;

    // Dotted inner/outer strokes on rect/circle are likewise deferred (their
    // boundary-ring clip lives in a dropped `save_layer`); re-emit them here
    // clipped/masked to the shape interior/exterior.
    render_rect_circle_dotted_strokes(builder, element)?;

    // Image-filled strokes are deferred too (their `SrcIn` composition lives in a
    // dropped `save_layer`); re-emit the texture masked to the stroke region.
    render_image_strokes(builder, shared, element, scale)?;

    // Semi-transparent text strokes are skipped by the shared renderer on SVG
    // (their opacity layer is a dropped `save_layer`); re-emit them here inside
    // a native `<g opacity>` wrapping the fully-opaque stroke.
    if let Type::Text(_) = &element.shape_type {
        render_text_alpha_strokes(builder, shared, element, scale)?;
        render_text_inner_strokes(builder, shared, element, scale)?;
        render_text_outer_strokes(builder, shared, element, scale)?;
    }

    if shadow.is_some() {
        builder.close_group();
    }
    if effects.is_some() {
        builder.close_group();
    }
    Ok(())
}

// ===========================================================================
// Tests
// ===========================================================================
//
// These are fast, headless native tests (`cargo test --bin render_wasm`) for
// the SVG exporter. They bypass the GPU/browser stack entirely: shapes are
// built directly into a `ShapesPool` and rendered through
// [`render_tree_to_svg`] with a GPU-free [`ExportState`] (standalone
// [`FontStore`]; image tests use [`FakeImages`] via [`render_with_images`]).
// Output is checked with `insta` snapshots.
//
// To (re)generate snapshots after a deliberate change:
//   cargo insta test --accept --bin render_wasm
// (or run the tests and `cargo insta accept`).
#[cfg(test)]
mod fixtures;

#[cfg(test)]
mod tests;

