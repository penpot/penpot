use skia_safe::{self as skia, Paint};

use crate::error::Result;
use crate::shapes::{Shape, Stroke};
use crate::render::vector::{draw_shape_geometry, ExportState, VectorRenderer, VectorTarget};

use super::document::SvgLayerCanvas;

const MASK_CANVAS: f32 = 100_000.0;

/// Registers a luminance `<mask>` (white canvas minus a black silhouette) and
/// returns its id, keeping only content *outside* the silhouette.
fn push_inverse_mask(
    builder: &mut SvgLayerCanvas,
    prefix: &str,
    matrix: &skia::Matrix,
    draw_silhouette: impl FnOnce(&skia::Canvas),
) -> String {
    let mask_id = builder.unique(prefix);
    let canvas = builder.new_fragment();
    {
        let cv: &skia::Canvas = &*canvas;
        let mut white = Paint::default();
        white.set_color(skia::Color::WHITE);
        cv.draw_rect(
            skia::Rect::from_ltrb(-MASK_CANVAS, -MASK_CANVAS, MASK_CANVAS, MASK_CANVAS),
            &white,
        );
        cv.save();
        cv.concat(matrix);
        draw_silhouette(cv);
        cv.restore();
    }
    builder.finish_mask_fragment(&mask_id, canvas);
    mask_id
}

/// Registers a `<clipPath>` from a leaf shape's geometry and returns its id.
pub(super) fn push_leaf_clip_path(
    builder: &mut SvgLayerCanvas,
    element: &Shape,
    matrix: &skia::Matrix,
) -> String {
    let clip_id = builder.unique("dclip");
    let canvas = builder.new_fragment();
    {
        let cv: &skia::Canvas = &*canvas;
        cv.concat(matrix);
        let mut black = Paint::default();
        black.set_anti_alias(true);
        black.set_color(skia::Color::BLACK);
        draw_shape_geometry(cv, element, &black);
    }
    builder.finish_clip_path_fragment(&clip_id, canvas);
    clip_id
}

/// Inverse-of-shape luminance mask: keeps only content outside the shape.
pub(super) fn push_inverse_shape_mask(
    builder: &mut SvgLayerCanvas,
    element: &Shape,
    matrix: &skia::Matrix,
    prefix: &str,
) -> String {
    push_inverse_mask(builder, prefix, matrix, |cv| {
        let mut black = Paint::default();
        black.set_anti_alias(true);
        black.set_color(skia::Color::BLACK);
        draw_shape_geometry(cv, element, &black);
    })
}

/// Inverse-of-glyph luminance mask: keeps only content outside the text glyphs.
pub(super) fn push_inverse_glyph_mask(
    builder: &mut SvgLayerCanvas,
    shared: &mut ExportState,
    element: &Shape,
    matrix: &skia::Matrix,
    scale: f32,
) -> Result<String> {
    let mask_id = builder.unique("tmask");
    let canvas = builder.new_fragment();
    {
        let cv: &skia::Canvas = &*canvas;
        let mut white = Paint::default();
        white.set_color(skia::Color::WHITE);
        cv.draw_rect(
            skia::Rect::from_ltrb(-MASK_CANVAS, -MASK_CANVAS, MASK_CANVAS, MASK_CANVAS),
            &white,
        );
        cv.save();
        cv.concat(matrix);
        let mut renderer = VectorRenderer::new(cv, shared, scale, VectorTarget::Svg);
        renderer.draw_text_glyph_silhouette(element)?;
        cv.restore();
    }
    builder.finish_mask_fragment(&mask_id, canvas);
    Ok(mask_id)
}

/// Alpha `<mask>` of a stroke's boundary-centered dot ring.
pub(super) fn push_dotted_ring_alpha_mask(
    builder: &mut SvgLayerCanvas,
    element: &Shape,
    stroke: &Stroke,
    matrix: &skia::Matrix,
) -> String {
    let svg_attrs = element.svg_attrs.as_ref();
    let mask_id = builder.unique("dimask");
    let canvas = builder.new_fragment();
    {
        let cv: &skia::Canvas = &*canvas;
        cv.concat(matrix);
        // Keep the dotted paint's dash effect and width, but paint it opaque so
        // the alpha mask captures the exact dot footprint (fill is irrelevant).
        let mut paint = stroke.to_paint(&element.selrect, svg_attrs, true);
        paint.set_shader(None);
        paint.set_color(skia::Color::BLACK);
        draw_shape_geometry(cv, element, &paint);
    }
    builder.finish_alpha_mask_fragment(&mask_id, canvas);
    mask_id
}
