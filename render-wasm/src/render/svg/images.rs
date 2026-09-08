use crate::error::Result;
use crate::render::shape_renderer::ShapeRenderer;
use crate::render::vector::VectorRenderer;
use crate::shapes::{Fill, ImageFill, Shape};
use crate::state::ShapesPoolRef;

use super::document::SvgLayerCanvas;
use crate::render::RenderResources;

/// Emits fills bottom -> top for SVG export.
///
/// Non-image fills go through Skia's SVG canvas. Image fills with a registered
/// source URL become native linked `<image>` elements (see `store_image_url`);
/// without a URL they fall back to Skia (base64-embed) when a CPU image exists.
pub(super) fn emit_fills(
    builder: &mut SvgLayerCanvas,
    shared: &mut RenderResources,
    shape: &Shape,
    fills: &[Fill],
    tree: ShapesPoolRef,
    scale: f32,
) -> Result<()> {
    if fills.is_empty() {
        return Ok(());
    }

    // fills[0] is the topmost layer; draw bottom → top.
    for fill in fills.iter().rev() {
        match fill {
            Fill::Image(image_fill) if shared.images.source_url(&image_fill.id()).is_some() => {
                emit_image_fill(builder, shared, shape, image_fill, tree)?;
            }
            fill => {
                let matrix = shape.centered_transform();
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
) -> Result<()> {
    let Some(url) = shared.images.source_url(&image_fill.id()) else {
        return Ok(());
    };

    let clip_id = builder.unique("imgclip");
    builder.push_clip_path(&clip_id, shape, tree);
    let href = xml_escape_attr(url);
    emit_linked_image_element(builder, shape, image_fill, &href, &clip_id);
    Ok(())
}

/// Emits `<g clip-path>` + `<image href>` using the shape selrect and page CTM.
pub(super) fn emit_linked_image_element(
    builder: &mut SvgLayerCanvas,
    shape: &Shape,
    image_fill: &ImageFill,
    href: &str,
    clip_id: &str,
) {
    let selrect = shape.selrect();
    let opacity = image_fill.opacity() as f32 / 255.0;
    let preserve = if image_fill.keep_aspect_ratio() {
        "xMidYMid slice"
    } else {
        "none"
    };
    let transform = builder.page_shape_matrix_attr(shape);

    let opacity_attr = if (opacity - 1.0).abs() < f32::EPSILON {
        String::new()
    } else {
        format!(r#" opacity="{opacity}""#)
    };

    builder.open_group(&format!("clip-path=\"url(#{clip_id})\""));
    builder.push_raw(&format!(
        r#"<image href="{href}" x="{}" y="{}" width="{}" height="{}" preserveAspectRatio="{preserve}"{opacity_attr} transform="{transform}"/>"#,
        selrect.left(),
        selrect.top(),
        selrect.width(),
        selrect.height(),
    ));
    builder.close_group();
}

pub(super) fn xml_escape_attr(s: &str) -> String {
    s.replace('&', "&amp;")
        .replace('"', "&quot;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}
