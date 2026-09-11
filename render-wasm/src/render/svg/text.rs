use std::collections::HashSet;

use skia_safe::Canvas;

use crate::error::Result;
use crate::render::text;
use crate::shapes::{Fill, ImageFill, Shape, SolidColor, Stroke, StrokeKind, Type};
use crate::uuid::Uuid;

use super::document::SvgLayerCanvas;
use super::images::{emit_linked_image_element, xml_escape_attr};
use crate::render::RenderResources;

/// Oversized white rect for inverse luminance masks (page units).
const MASK_CANVAS: f32 = 100_000.0;

/// Emits a text shape's fills for SVG export.
///
/// Linked image fills become `<image href>` clipped to the glyph silhouette;
/// other fills go through Skia as native `<text>`.
pub(super) fn render_text_fill(
    builder: &mut SvgLayerCanvas,
    shared: &RenderResources,
    element: &Shape,
) -> Result<()> {
    let text_content = element.get_text_content();
    let text_content = text_content.new_bounds(element.selrect());
    let max_layers = text_content.max_fill_layers();
    if max_layers == 0 {
        return Ok(());
    }

    let matrix = element.centered_transform();

    for layer in 0..max_layers {
        let linked = linked_image_fills_at_layer(&text_content, layer, shared);
        let skip_ids: HashSet<Uuid> = linked.iter().map(|img| img.id()).collect();

        for image_fill in &linked {
            emit_text_image_fill(builder, shared, element, image_fill, layer)?;
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
            canvas.concat(&matrix);
            text::paint_text_paragraphs(canvas, element, &mut paragraph_builders);
            canvas.restore();
        }
    }

    Ok(())
}

/// Emits text strokes that `SkSVGDevice` cannot keep via `save_layer`.
///
/// - **Image-filled:** linked `<image>` under a stroke luminance mask
///   (inner also under a glyph clip; outer also under an inverse-glyph mask).
/// - **Center + opaque:** stroked `<text>` through Skia.
/// - **Center + alpha:** same paint inside `<g opacity>`.
/// - **Inner:** glyph `<clipPath>` + opaque double-width stroke (+ opacity).
/// - **Outer:** inverse-glyph luminance `<mask>` + opaque double-width stroke (+ opacity).
pub(super) fn render_text_strokes(
    builder: &mut SvgLayerCanvas,
    shared: &RenderResources,
    element: &Shape,
) -> Result<()> {
    let matrix = element.centered_transform();
    // strokes[0] is topmost; draw bottom → top (matches GPU / VectorRenderer).
    for stroke in element.visible_strokes().rev() {
        if let Fill::Image(image_fill) = &stroke.fill {
            if shared.images.source_url(&image_fill.id()).is_some() {
                emit_image_text_stroke(builder, shared, element, stroke, image_fill, &matrix)?;
                continue;
            }
        }
        match stroke.render_kind(false) {
            StrokeKind::Center => emit_center_text_stroke(builder, element, stroke, &matrix)?,
            StrokeKind::Inner => emit_inner_text_stroke(builder, element, stroke, &matrix)?,
            StrokeKind::Outer => emit_outer_text_stroke(builder, element, stroke, &matrix)?,
        }
    }
    Ok(())
}

/// Linked `<image>` confined to the text-stroke silhouette.
///
/// SVG `clipPath` ignores strokes, so the silhouette is a luminance `<mask>`
/// (white stroke on black). Inner also clips to filled glyphs. Outer nests an
/// inverse-glyph mask (same as solid outer strokes) so only the exterior half
/// of the double-width stroke remains — punching glyphs inside the stroke mask
/// misaligns when fill/stroke paragraph paints differ.
fn emit_image_text_stroke(
    builder: &mut SvgLayerCanvas,
    shared: &RenderResources,
    element: &Shape,
    stroke: &Stroke,
    image_fill: &ImageFill,
    matrix: &skia_safe::Matrix,
) -> Result<()> {
    let Some(url) = shared.images.source_url(&image_fill.id()) else {
        return Ok(());
    };

    let kind = stroke.render_kind(false);
    let stroke_mask = push_text_stroke_mask(builder, element, stroke, matrix)?;

    let glyph_clip = if kind == StrokeKind::Inner {
        Some(push_glyph_clip(builder, element, matrix)?)
    } else {
        None
    };
    let outer_mask = if kind == StrokeKind::Outer {
        Some(push_inverse_glyph_mask(builder, element, matrix)?)
    } else {
        None
    };

    if let Some(mask_id) = &outer_mask {
        builder.open_group(&format!("mask=\"url(#{mask_id})\""));
    }
    if let Some(clip_id) = &glyph_clip {
        builder.open_group(&format!("clip-path=\"url(#{clip_id})\""));
    }
    builder.open_group(&format!("mask=\"url(#{stroke_mask})\""));

    let href = xml_escape_attr(url);
    // GPU text strokes paint the image via `get_fill_shader` onto `selrect`
    // with cover/center scaling — not `selrect + stroke.delta()` stretched.
    let dest = text_image_cover_dest(&element.selrect(), image_fill);
    emit_linked_image_element(builder, element, image_fill, dest, &href, None, true);

    builder.close_group();
    if glyph_clip.is_some() {
        builder.close_group();
    }
    if outer_mask.is_some() {
        builder.close_group();
    }
    Ok(())
}

/// Cover-centered dest matching [`crate::shapes::get_fill_shader`] for images.
fn text_image_cover_dest(selrect: &skia_safe::Rect, image_fill: &ImageFill) -> skia_safe::Rect {
    let image_width = image_fill.width() as f32;
    let image_height = image_fill.height() as f32;
    let scale_x = selrect.width() / image_width;
    let scale_y = selrect.height() / image_height;
    let scale = scale_x.max(scale_y);
    let scaled_width = image_width * scale;
    let scaled_height = image_height * scale;
    let pos_x = selrect.left() - (scaled_width - selrect.width()) / 2.0;
    let pos_y = selrect.top() - (scaled_height - selrect.height()) / 2.0;
    skia_safe::Rect::from_xywh(pos_x, pos_y, scaled_width, scaled_height)
}

fn push_glyph_clip(
    builder: &mut SvgLayerCanvas,
    element: &Shape,
    matrix: &skia_safe::Matrix,
) -> Result<String> {
    let clip_id = builder.unique("tclip");
    let canvas = builder.new_fragment();
    {
        let cv: &Canvas = &canvas;
        cv.save();
        cv.concat(matrix);
        paint_text_glyph_silhouette(cv, element)?;
        cv.restore();
    }
    builder.finish_clip_path_fragment(&clip_id, canvas);
    Ok(clip_id)
}

/// Luminance mask of the text-stroke silhouette (masks honor `stroke`; clipPaths do not).
///
/// Black canvas + white stroke. Outer confinement (exterior half only) is applied
/// by nesting [`push_inverse_glyph_mask`], matching solid outer text strokes.
fn push_text_stroke_mask(
    builder: &mut SvgLayerCanvas,
    element: &Shape,
    stroke: &Stroke,
    matrix: &skia_safe::Matrix,
) -> Result<String> {
    let mask_id = builder.unique("txtstrokemask");
    let canvas = builder.new_fragment();
    {
        let cv: &Canvas = &canvas;
        let mut black = skia_safe::Paint::default();
        black.set_color(skia_safe::Color::BLACK);
        cv.draw_rect(
            skia_safe::Rect::from_ltrb(-MASK_CANVAS, -MASK_CANVAS, MASK_CANVAS, MASK_CANVAS),
            &black,
        );
        cv.save();
        cv.concat(matrix);
        let mut silhouette = stroke.clone();
        silhouette.fill = Fill::Solid(SolidColor(skia_safe::Color::WHITE));
        paint_text_stroke_opaque(cv, element, &silhouette)?;
        cv.restore();
    }
    builder.finish_mask_fragment(&mask_id, canvas);
    Ok(mask_id)
}

fn emit_center_text_stroke(
    builder: &mut SvgLayerCanvas,
    element: &Shape,
    stroke: &Stroke,
    matrix: &skia_safe::Matrix,
) -> Result<()> {
    let opacity = stroke.fill.opacity();
    if opacity < 1.0 {
        builder.open_group(&format!("opacity=\"{opacity}\""));
    }
    {
        let canvas = builder.canvas();
        canvas.save();
        canvas.concat(matrix);
        paint_text_stroke_opaque(canvas, element, stroke)?;
        canvas.restore();
    }
    if opacity < 1.0 {
        builder.close_group();
    }
    Ok(())
}

fn emit_inner_text_stroke(
    builder: &mut SvgLayerCanvas,
    element: &Shape,
    stroke: &Stroke,
    matrix: &skia_safe::Matrix,
) -> Result<()> {
    let clip_id = builder.unique("tclip");
    let canvas = builder.new_fragment();
    {
        let cv: &Canvas = &canvas;
        cv.save();
        cv.concat(matrix);
        paint_text_glyph_silhouette(cv, element)?;
        cv.restore();
    }
    builder.finish_clip_path_fragment(&clip_id, canvas);

    let opacity = stroke.fill.opacity();
    let mut attrs = format!("clip-path=\"url(#{clip_id})\"");
    if opacity < 1.0 {
        attrs.push_str(&format!(" opacity=\"{opacity}\""));
    }
    builder.open_group(&attrs);
    {
        let canvas = builder.canvas();
        canvas.save();
        canvas.concat(matrix);
        paint_text_stroke_opaque(canvas, element, stroke)?;
        canvas.restore();
    }
    builder.close_group();
    Ok(())
}

fn emit_outer_text_stroke(
    builder: &mut SvgLayerCanvas,
    element: &Shape,
    stroke: &Stroke,
    matrix: &skia_safe::Matrix,
) -> Result<()> {
    let mask_id = push_inverse_glyph_mask(builder, element, matrix)?;

    let opacity = stroke.fill.opacity();
    let mut attrs = format!("mask=\"url(#{mask_id})\"");
    if opacity < 1.0 {
        attrs.push_str(&format!(" opacity=\"{opacity}\""));
    }
    builder.open_group(&attrs);
    {
        let canvas = builder.canvas();
        canvas.save();
        canvas.concat(matrix);
        paint_text_stroke_opaque(canvas, element, stroke)?;
        canvas.restore();
    }
    builder.close_group();
    Ok(())
}

/// White canvas minus black glyphs: keeps only content outside the glyphs.
fn push_inverse_glyph_mask(
    builder: &mut SvgLayerCanvas,
    element: &Shape,
    matrix: &skia_safe::Matrix,
) -> Result<String> {
    let mask_id = builder.unique("tmask");
    let canvas = builder.new_fragment();
    {
        let cv: &Canvas = &canvas;
        let mut white = skia_safe::Paint::default();
        white.set_color(skia_safe::Color::WHITE);
        cv.draw_rect(
            skia_safe::Rect::from_ltrb(-MASK_CANVAS, -MASK_CANVAS, MASK_CANVAS, MASK_CANVAS),
            &white,
        );
        cv.save();
        cv.concat(matrix);
        paint_text_glyph_silhouette(cv, element)?;
        cv.restore();
    }
    builder.finish_mask_fragment(&mask_id, canvas);
    Ok(mask_id)
}

/// Opaque glyph fill silhouette (clip/mask source).
fn paint_text_glyph_silhouette(canvas: &Canvas, shape: &Shape) -> Result<()> {
    let Type::Text(text_content) = &shape.shape_type else {
        return Ok(());
    };
    let text_content = text_content.new_bounds(shape.selrect());
    let mut mask_builders = text_content.paragraph_builder_group_opaque();
    text::render_overlay_emoji(canvas, shape, &mut mask_builders, None, None, None, None)?;
    Ok(())
}

/// Single text stroke at full paint opacity (no save_layer opacity / masks).
///
/// Inner/Outer still paint at double width; the SVG compositor supplies
/// clipPath / mask. Builders already peel stroke-fill alpha into `layer_opacity`.
fn paint_text_stroke_opaque(canvas: &Canvas, shape: &Shape, stroke: &Stroke) -> Result<()> {
    let Type::Text(text_content) = &shape.shape_type else {
        return Ok(());
    };
    let text_content = text_content.new_bounds(shape.selrect());
    let stroke_blur_outset = Stroke::max_bounds_width(shape.visible_strokes(), false);
    let (mut stroke_paragraphs, _) = text::stroke_paragraph_builder_group_from_text(
        &text_content,
        stroke,
        &shape.selrect(),
        None,
    );
    text::render_with_bounds_outset_overlay_emoji(
        canvas,
        shape,
        &mut stroke_paragraphs,
        None,
        None,
        stroke_blur_outset,
        None,
        None,
    )?;
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
        let cv: &Canvas = &canvas;
        cv.save();
        cv.concat(&shape.centered_transform());
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
        Some(&clip_id),
        true,
    );
    Ok(())
}
