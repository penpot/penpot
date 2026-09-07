use skia_safe::{self as skia};

use std::collections::HashSet;

use crate::error::Result;
use crate::math::Bounds;
use crate::shapes::{Shape, Type};
use crate::state::ShapesPoolRef;
use crate::uuid::Uuid;

use super::vector::{render_leaf_content, VectorRenderer};
use super::RenderResources;

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

/// Bounds for the root `<svg>` width, height, and viewBox.
///
/// Text uses [`Shape::layer_bounds`]: glyph metrics, at least the `selrect`, and
/// room for strokes/shadows/blur. Other shapes use `extrect` for overflow.
fn svg_page_bounds(shape: &Shape, tree: ShapesPoolRef, scale: f32) -> skia::Rect {
    if matches!(shape.shape_type, Type::Text(_)) {
        let mut bounds = Bounds::from_rect(&shape.layer_bounds());
        if !shape.transform.is_identity() {
            let mut matrix = shape.transform;
            let center = shape.center();
            matrix.post_translate(center);
            matrix.pre_translate(-center);
            bounds.transform_mut(&matrix);
        }
        bounds.to_rect()
    } else {
        shape.extrect(tree, scale)
    }
}

/// Renders a shape tree to an SVG document and returns the raw SVG bytes.
///
/// Dedicated vector-SVG render path. Leaf content (paths, fills, …) is emitted
/// as real SVG markup via short-lived Skia SVG canvases, while composite
/// effects that `SkSVGDevice` would drop (`save_layer` opacity / blend) are
/// composed as native SVG `<g>` wrappers. Frame `clip content` uses a native
/// `<clipPath>`.
///
/// Special-case re-emission for shadows, layer blur, masks, text strokes, and
/// deferred strokes is intentionally out of scope for this cut.
pub fn render_to_svg(
    shared: &mut RenderResources,
    id: &Uuid,
    tree: ShapesPoolRef,
    scale: f32,
) -> Result<Vec<u8>> {
    render_tree_to_svg(shared, id, tree, scale)
}

/// Core SVG export, kept as a separate entry so headless native tests can call
/// it with a GPU-free [`RenderResources`].
pub(crate) fn render_tree_to_svg(
    shared: &mut RenderResources,
    id: &Uuid,
    tree: ShapesPoolRef,
    scale: f32,
) -> Result<Vec<u8>> {
    let shape = tree
        .get(id)
        .ok_or_else(|| crate::error::Error::CriticalError("Shape not found for SVG".to_string()))?;
    // Use the extended rect so unclipped frames grow to include overflowing
    // children (and leaf effects like shadows when those land). Clipped frames
    // still resolve to roughly their selrect because `extrect` skips joining
    // children when `clip_content` is on.
    let bounds = svg_page_bounds(shape, tree, scale);

    let page_w = bounds.width() * scale;
    let page_h = bounds.height() * scale;
    let rect = skia::Rect::from_xywh(0., 0., page_w, page_h);

    let (defs, body) = render_body(shared, id, tree, scale, rect, -bounds.left(), -bounds.top())?;

    let mut aliases = HashSet::new();
    collect_font_aliases(tree, id, &mut aliases);
    let font_css = shared.fonts.font_face_css_for_aliases(&aliases);

    let mut out = String::with_capacity(body.len() + defs.len() + font_css.len() + 256);
    out.push_str("<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n");
    out.push_str(&format!(
        "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" \
         width=\"{page_w}\" height=\"{page_h}\" viewBox=\"0 0 {page_w} {page_h}\">"
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
mod text;

use document::SvgLayerCanvas;
use frames::render_frame;
use groups::render_group;
use text::render_text_fill;

use document::effect_attrs;

/// Renders `id`'s subtree to an SVG body, returning `(defs, body)`.
fn render_body(
    shared: &mut RenderResources,
    id: &Uuid,
    tree: ShapesPoolRef,
    scale: f32,
    page_rect: skia::Rect,
    tx: f32,
    ty: f32,
) -> Result<(String, String)> {
    let mut builder = SvgLayerCanvas::new(scale, page_rect, tx, ty);
    render_tree(&mut builder, shared, id, tree, scale)?;
    builder.flush();
    Ok((builder.defs, builder.out))
}

fn render_tree(
    builder: &mut SvgLayerCanvas,
    shared: &mut RenderResources,
    id: &Uuid,
    tree: ShapesPoolRef,
    scale: f32,
) -> Result<()> {
    let Some(element) = tree.get(id) else {
        return Ok(());
    };
    if element.hidden {
        return Ok(());
    }

    match &element.shape_type {
        Type::Group(_) => render_group(builder, shared, element, tree, scale),
        Type::Frame(_) => render_frame(builder, shared, element, tree, scale),
        Type::Rect(_)
        | Type::Circle
        | Type::Path(_)
        | Type::Bool(_)
        | Type::Text(_)
        | Type::SVGRaw(_) => render_leaf(builder, shared, element, scale),
    }
}

fn render_leaf(
    builder: &mut SvgLayerCanvas,
    shared: &mut RenderResources,
    element: &Shape,
    scale: f32,
) -> Result<()> {
    let effects = effect_attrs(element);
    if let Some(attrs) = &effects {
        builder.open_group(attrs);
    }

    {
        if matches!(element.shape_type, Type::Text(_)) {
            render_text_fill(builder, element)?;
        } else {
            let matrix = element.centered_transform();
            let canvas = builder.canvas();
            canvas.save();
            canvas.concat(&matrix);
            let mut renderer = VectorRenderer::new(canvas, shared, scale, false);
            render_leaf_content(&mut renderer, element)?;
            canvas.restore();
        }
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
// Fast, headless native tests (`cargo test --bin render_wasm`) for the SVG
// exporter. They bypass the GPU/browser stack: shapes are built into a
// `ShapesPool` and rendered through [`render_tree_to_svg`] with a headless
// [`RenderResources`]. Output is checked with `insta` snapshots.
//
// To (re)generate snapshots after a deliberate change:
//   cargo insta test --accept --bin render_wasm
#[cfg(test)]
mod fixtures;

#[cfg(test)]
mod tests;
