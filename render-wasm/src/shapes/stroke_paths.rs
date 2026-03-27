use skia_safe::{self as skia};

use super::paths::Path;
use super::strokes::{Stroke, StrokeKind};
use super::svg_attrs::SvgAttrs;
use crate::math::Rect;

/// Converts a stroke into a filled path outline.
///
/// Uses Skia's `fill_path_with_paint` to expand the stroke into a filled region,
/// then clips it via boolean ops for inner/outer alignment. The optional
/// `path_transform` maps from local shape coords to the drawing space (and back).
pub fn stroke_to_path(
    stroke: &Stroke,
    shape_path: &Path,
    path_transform: Option<&skia::Matrix>,
    selrect: &Rect,
    svg_attrs: Option<&SvgAttrs>,
) -> Option<Path> {
    let skia_shape_path = shape_path.to_skia_path();

    let transformed_shape_path = if let Some(pt) = path_transform {
        skia_shape_path.make_transform(pt)
    } else {
        skia_shape_path.clone()
    };

    let is_open = shape_path.is_open();
    let mut paint = stroke.to_paint(selrect, svg_attrs, true);

    let render_kind = stroke.render_kind(is_open);
    if render_kind != StrokeKind::Center {
        paint.set_stroke_width(stroke.width * 2.0);
    }

    let mut stroke_outline = skia::Path::default();
    let success = skia::path_utils::fill_path_with_paint(
        &transformed_shape_path,
        &paint,
        &mut stroke_outline,
        None,
        None,
    );

    if !success {
        return None;
    }

    // For inner/outer strokes, use boolean ops to clip
    // the 2×-width stroke outline to the correct region.
    // Set EvenOdd to preserve the annular ring's inner hole,
    // then as_winding() on the result fixes contour winding
    // for Penpot's NonZero fill rule.
    let final_path = match render_kind {
        StrokeKind::Inner => {
            stroke_outline.set_fill_type(skia::PathFillType::EvenOdd);
            let inner = stroke_outline
                .op(&transformed_shape_path, skia::PathOp::Intersect)
                .unwrap_or(stroke_outline);
            inner.as_winding().unwrap_or(inner)
        }
        StrokeKind::Outer => {
            stroke_outline.set_fill_type(skia::PathFillType::EvenOdd);
            let outer = stroke_outline
                .op(&transformed_shape_path, skia::PathOp::Difference)
                .unwrap_or(stroke_outline);
            outer.as_winding().unwrap_or(outer)
        }
        StrokeKind::Center => {
            stroke_outline.set_fill_type(skia::PathFillType::EvenOdd);
            stroke_outline.as_winding().unwrap_or(stroke_outline)
        }
    };

    // If there was a path_transform, invert it back to local coords
    let final_path = if let Some(pt) = path_transform {
        if let Some(inv) = pt.invert() {
            final_path.make_transform(&inv)
        } else {
            final_path
        }
    } else {
        final_path
    };

    Some(Path::from_skia_path_accurate(final_path))
}
