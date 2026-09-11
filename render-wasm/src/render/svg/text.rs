use std::collections::HashSet;

use crate::error::Result;
use crate::render::text;
use crate::shapes::{Fill, ImageFill, Shape};
use crate::uuid::Uuid;

use super::document::SvgLayerCanvas;
use super::images::{emit_linked_image_element, xml_escape_attr};
use crate::render::RenderResources;

/// Emits a text shape's fills for SVG export.
///
/// Linked image fills become `<image href>` clipped to the glyph silhouette;
/// other fills go through Skia as native `<text>`. Strokes are a later PR.
///
/// `draw_matrix` is the leaf CTM (normally `centered_transform`). During a
/// parent drop-shadow silhouette pass it must include the geometric offset
/// (`silhouette_draw_matrix`); using only `centered_transform` leaves child
/// text unshifted while strokes/fills move.
pub(super) fn render_text_fill(
    builder: &mut SvgLayerCanvas,
    shared: &RenderResources,
    element: &Shape,
    draw_matrix: skia_safe::Matrix,
) -> Result<()> {
    let text_content = element.get_text_content();
    let text_content = text_content.new_bounds(element.selrect());
    let max_layers = text_content.max_fill_layers();
    if max_layers == 0 {
        return Ok(());
    }

    for layer in 0..max_layers {
        let linked = linked_image_fills_at_layer(&text_content, layer, shared);
        let skip_ids: HashSet<Uuid> = linked.iter().map(|img| img.id()).collect();

        for image_fill in &linked {
            emit_text_image_fill(builder, shared, element, image_fill, layer, draw_matrix)?;
        }

        if layer_has_skia_fills(&text_content, layer, &skip_ids) {
            let mut paragraph_builders = if skip_ids.is_empty() {
                text_content.paragraph_builder_group_for_fill_layer(layer)
            } else {
                text_content
                    .paragraph_builder_group_for_fill_layer_skipping_images(layer, &skip_ids)
            };
            let canvas = builder.canvas();
            canvas.save();
            canvas.concat(&draw_matrix);
            text::paint_text_paragraphs(canvas, element, &mut paragraph_builders);
            canvas.restore();
        }
    }

    Ok(())
}

fn linked_image_fills_at_layer<'a>(
    text_content: &'a crate::shapes::TextContent,
    layer: usize,
    shared: &RenderResources,
) -> Vec<&'a ImageFill> {
    let mut out = Vec::new();
    let mut seen = HashSet::new();
    for paragraph in text_content.paragraphs() {
        for span in paragraph.children() {
            if let Some(Fill::Image(img)) = span.fills_from_bottom(layer) {
                if shared.images.source_url(&img.id()).is_some() && seen.insert(img.id()) {
                    out.push(img);
                }
            }
        }
    }
    out
}

fn layer_has_skia_fills(
    text_content: &crate::shapes::TextContent,
    layer: usize,
    skip_ids: &HashSet<Uuid>,
) -> bool {
    text_content.paragraphs().iter().any(|paragraph| {
        paragraph
            .children()
            .iter()
            .any(|span| match span.fills_from_bottom(layer) {
                Some(Fill::Image(img)) if skip_ids.contains(&img.id()) => false,
                Some(_) => true,
                None => false,
            })
    })
}

/// Linked `<image>` clipped to the opaque glyph silhouette for this image layer.
fn emit_text_image_fill(
    builder: &mut SvgLayerCanvas,
    shared: &RenderResources,
    shape: &Shape,
    image_fill: &ImageFill,
    layer: usize,
    draw_matrix: skia_safe::Matrix,
) -> Result<()> {
    let Some(url) = shared.images.source_url(&image_fill.id()) else {
        return Ok(());
    };

    let clip_id = builder.unique("txtimgclip");
    let text_content = shape.get_text_content().new_bounds(shape.selrect());
    let mut paragraph_builders =
        text_content.paragraph_builder_group_opaque_for_image_layer(layer, image_fill.id());

    let canvas = builder.new_fragment();
    {
        let cv: &skia_safe::Canvas = &canvas;
        cv.save();
        cv.concat(&draw_matrix);
        text::paint_text_paragraphs(cv, shape, &mut paragraph_builders);
        cv.restore();
    }
    builder.finish_clip_path_fragment(&clip_id, canvas);

    let href = xml_escape_attr(url);
    emit_linked_image_element(
        builder,
        shape,
        image_fill,
        shape.selrect(),
        &href,
        &clip_id,
        draw_matrix,
    );
    Ok(())
}
