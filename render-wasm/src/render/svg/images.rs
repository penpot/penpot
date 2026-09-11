use crate::error::Result;
use crate::math::Rect as MathRect;
use crate::render::get_dest_rect;
use crate::render::get_image_dest_rect;
use crate::render::shape_renderer::ShapeRenderer;
use crate::render::vector::{paint_svg_stroke_silhouette, VectorRenderer};
use crate::shapes::{Fill, ImageFill, Shape, Stroke};
use crate::state::ShapesPoolRef;

use super::document::SvgLayerCanvas;
use crate::render::RenderResources;

/// Emits fills bottom -> top for SVG export.
///
/// Non-image fills go through Skia's SVG canvas. Image fills with a registered
/// source URL become native linked `<image>` elements (see `store_image_url`);
/// without a URL they fall back to Skia (base64-embed) when a CPU image exists.
///
/// `draw_matrix` is the leaf CTM (container drop silhouettes pass a local-offset
/// matrix so linked images move with solid fills).
pub(super) fn emit_fills(
    builder: &mut SvgLayerCanvas,
    shared: &mut RenderResources,
    shape: &Shape,
    fills: &[Fill],
    tree: ShapesPoolRef,
    scale: f32,
    draw_matrix: Option<skia_safe::Matrix>,
) -> Result<()> {
    if fills.is_empty() {
        return Ok(());
    }

    let matrix = draw_matrix.unwrap_or_else(|| shape.centered_transform());
    // fills[0] is the topmost layer; draw bottom → top.
    for fill in fills.iter().rev() {
        match fill {
            Fill::Image(image_fill) if shared.images.source_url(&image_fill.id()).is_some() => {
                emit_image_fill(builder, shared, shape, image_fill, tree, matrix)?;
            }
            fill => {
                let canvas = builder.canvas();
                canvas.save();
                canvas.concat(&matrix);
                let mut renderer = VectorRenderer::new(canvas, shared, scale, false);
                renderer.draw_fills(shape, std::slice::from_ref(fill))?;
                canvas.restore();
            }
        }
    }
    Ok(())
}

/// Emits a linked SVG `<image>` clipped to the shape geometry.
///
/// Skia's SVG backend would base64-embed a PNG from `draw_image_rect`; we emit
/// a native `<image href="...">` instead so the export stays linked to the
/// registered media URL (see `store_image_url`).
fn emit_image_fill(
    builder: &mut SvgLayerCanvas,
    shared: &RenderResources,
    shape: &Shape,
    image_fill: &ImageFill,
    tree: ShapesPoolRef,
    draw_matrix: skia_safe::Matrix,
) -> Result<()> {
    let Some(url) = shared.images.source_url(&image_fill.id()) else {
        return Ok(());
    };

    let clip_id = builder.unique("imgclip");
    // Clip uses builder.silhouette_offset (same space as draw_matrix during a
    // container drop silhouette pass).
    builder.push_clip_path(&clip_id, shape, tree);
    let href = xml_escape_attr(url);
    let dest_rect = get_image_dest_rect(&shape.selrect(), image_fill);
    emit_linked_image_element(
        builder,
        shape,
        image_fill,
        dest_rect,
        &href,
        &clip_id,
        draw_matrix,
    );
    Ok(())
}

/// Emits strokes bottom -> top for SVG export.
///
/// Image strokes with a registered URL become a linked `<image>` clipped to the
/// stroke silhouette (Skia drops the GPU save_layer + SrcIn path). Other strokes
/// go through [`VectorRenderer`].
///
/// `draw_matrix` overrides the leaf CTM (container drop silhouettes pass a
/// local-offset matrix; `None` uses `centered_transform`).
pub(super) fn emit_strokes(
    builder: &mut SvgLayerCanvas,
    shared: &mut RenderResources,
    shape: &Shape,
    strokes: &[&Stroke],
    scale: f32,
    draw_matrix: Option<skia_safe::Matrix>,
) -> Result<()> {
    if strokes.is_empty() {
        return Ok(());
    }

    let matrix = draw_matrix.unwrap_or_else(|| shape.centered_transform());
    // strokes[0] is topmost; draw bottom -> top.
    for stroke in strokes.iter().rev() {
        match &stroke.fill {
            Fill::Image(image_fill) if shared.images.source_url(&image_fill.id()).is_some() => {
                emit_image_stroke(builder, shared, shape, stroke, image_fill, scale, matrix)?;
            }
            _ => {
                let canvas = builder.canvas();
                canvas.save();
                canvas.concat(&matrix);
                let mut renderer = VectorRenderer::new(canvas, shared, scale, false);
                renderer.draw_strokes(shape, std::slice::from_ref(stroke))?;
                canvas.restore();
            }
        }
    }
    Ok(())
}

/// Linked `<image>` clipped to the stroke outline (opaque filled path).
fn emit_image_stroke(
    builder: &mut SvgLayerCanvas,
    shared: &RenderResources,
    shape: &Shape,
    stroke: &Stroke,
    image_fill: &ImageFill,
    scale: f32,
    draw_matrix: skia_safe::Matrix,
) -> Result<()> {
    let Some(url) = shared.images.source_url(&image_fill.id()) else {
        return Ok(());
    };

    let clip_id = builder.unique("imgstrokeclip");
    let canvas = builder.new_fragment();
    {
        let cv: &skia_safe::Canvas = &canvas;
        cv.save();
        cv.concat(&draw_matrix);
        if !paint_svg_stroke_silhouette(cv, shape, stroke, scale) {
            cv.restore();
            return Ok(());
        }
        cv.restore();
    }
    builder.finish_clip_path_fragment(&clip_id, canvas);

    let href = xml_escape_attr(url);
    let dest = image_stroke_dest_rect(shape, stroke);
    emit_linked_image_element(
        builder,
        shape,
        image_fill,
        dest,
        &href,
        &clip_id,
        draw_matrix,
    );
    Ok(())
}

/// Where to place the linked image for an image-filled stroke.
///
/// Starts from the same dest as the GPU path (`selrect` + `stroke.delta()`), then
/// grows on open paths so marker caps are still covered by the `<image>`.
fn image_stroke_dest_rect(shape: &Shape, stroke: &Stroke) -> MathRect {
    let mut dest = get_dest_rect(&shape.selrect(), stroke.delta());
    if !shape.is_open() {
        return dest;
    }
    let cap_margin = stroke.cap_bounds_margin();
    if cap_margin <= 0.0 {
        return dest;
    }
    let mut with_caps = shape.selrect();
    with_caps.inset((-cap_margin, -cap_margin));
    dest.join(with_caps);
    dest
}

/// Emits `<g clip-path>` + `<image href>` at `dest_rect`, under the page CTM.
///
/// `draw_matrix` is the shape-local CTM (must include container drop silhouette
/// offset when drawing under a parent shadow filter).
pub(super) fn emit_linked_image_element(
    builder: &mut SvgLayerCanvas,
    _shape: &Shape,
    image_fill: &ImageFill,
    dest_rect: MathRect,
    href: &str,
    clip_id: &str,
    draw_matrix: skia_safe::Matrix,
) {
    let opacity = image_fill.opacity() as f32 / 255.0;
    let preserve = if image_fill.keep_aspect_ratio() {
        "xMidYMid slice"
    } else {
        "none"
    };
    let transform = builder.page_draw_matrix_attr(&draw_matrix);

    let opacity_attr = if (opacity - 1.0).abs() < f32::EPSILON {
        String::new()
    } else {
        format!(r#" opacity="{opacity}""#)
    };

    builder.open_group(&format!("clip-path=\"url(#{clip_id})\""));
    builder.push_raw(&format!(
        r#"<image href="{href}" x="{}" y="{}" width="{}" height="{}" preserveAspectRatio="{preserve}"{opacity_attr} transform="{transform}"/>"#,
        dest_rect.left(),
        dest_rect.top(),
        dest_rect.width(),
        dest_rect.height(),
    ));
    builder.close_group();
}

pub(super) fn xml_escape_attr(s: &str) -> String {
    s.replace('&', "&amp;")
        .replace('"', "&quot;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}
