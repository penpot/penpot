use bezier_rs::{Bezier, BezierHandles, Cap, Identifier, Join, Subpath};
use skia_safe::{self as skia};

use super::paths::Path;
use super::strokes::{Stroke, StrokeKind, StrokeStyle};
use super::svg_attrs::SvgAttrs;
use super::{StrokeLineCap, StrokeLineJoin};
use crate::math::bools::path_to_beziers;
use crate::math::Rect;

/// Lightweight manipulator-group id for transient bezier-rs subpaths. We never
/// track manipulator identity here, so a zero-sized id is enough.
#[derive(Clone, Copy, PartialEq, Eq, Hash)]
struct StrokeId;

impl Identifier for StrokeId {
    fn new() -> Self {
        Self
    }
}

/// Converts a stroke into a filled path outline.
///
/// For solid strokes (and whenever `solid_outline` is requested) the outline is
/// computed analytically with bezier-rs (`Subpath::outline`), which produces a
/// path with the minimum number of curve nodes — see issue #10002. Dashed/dotted
/// strokes fall back to Skia's `fill_path_with_paint`, which is required to apply
/// the dash `PathEffect`.
///
/// For inner/outer alignment the (2×-width) outline is clipped against the
/// original shape via boolean ops. This is orientation-independent and avoids the
/// winding-sign pitfalls of directly offsetting the centerline.
///
/// The optional `path_transform` maps from local shape coords to the drawing
/// space (and back). When `solid_outline` is true, any dash/dot effect is
/// dropped so the result is a continuous region — useful for clipping (e.g. drag
/// crop cache) where dash gaps should not punch holes in the clip mask.
pub fn stroke_to_path(
    stroke: &Stroke,
    shape_path: &Path,
    path_transform: Option<&skia::Matrix>,
    selrect: &Rect,
    svg_attrs: Option<&SvgAttrs>,
    solid_outline: bool,
) -> Option<Path> {
    let is_open = shape_path.is_open();
    let render_kind = stroke.render_kind(is_open);

    let skia_shape_path = shape_path.to_skia_path(svg_attrs);
    let transformed_shape_path = if let Some(pt) = path_transform {
        skia_shape_path.make_transform(pt)
    } else {
        skia_shape_path.clone()
    };

    // Solid strokes use the analytic bezier-rs outline (clean, minimal nodes).
    // Dashed/dotted strokes need Skia's stroker to apply the dash effect.
    let use_bezier_outline = solid_outline || stroke.style == StrokeStyle::Solid;

    let mut stroke_outline = if use_bezier_outline {
        bezier_outline(stroke, shape_path, path_transform, render_kind, svg_attrs)?
    } else {
        skia_outline(
            stroke,
            &transformed_shape_path,
            selrect,
            svg_attrs,
            render_kind,
            solid_outline,
        )?
    };

    // For inner/outer strokes, use boolean ops to clip the 2×-width stroke
    // outline to the correct region. Set EvenOdd to preserve the annular ring's
    // inner hole, then as_winding() on the result fixes contour winding for
    // Penpot's NonZero fill rule.
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

    // If there was a path_transform, invert it back to local coords.
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

/// Analytic stroke outline via bezier-rs. Returns the (2×-width for inner/outer,
/// 1×-width for center) stroke region in drawing space, EvenOdd-filled.
fn bezier_outline(
    stroke: &Stroke,
    shape_path: &Path,
    path_transform: Option<&skia::Matrix>,
    render_kind: StrokeKind,
    svg_attrs: Option<&SvgAttrs>,
) -> Option<skia::Path> {
    // Build beziers in drawing space.
    let mut local = shape_path.clone();
    if let Some(pt) = path_transform {
        local.transform(pt);
    }
    let beziers = path_to_beziers(&local);
    if beziers.is_empty() {
        return None;
    }

    let closed = !shape_path.is_open() && beziers.len() > 1;
    let subpath = Subpath::<StrokeId>::from_beziers(&beziers, closed);

    // Center alignment paints ±width/2 around the centerline. Inner/outer paint
    // the full 2×-width band (±width), then get clipped to the correct half.
    let distance = match render_kind {
        StrokeKind::Center => (stroke.width / 2.0) as f64,
        StrokeKind::Inner | StrokeKind::Outer => stroke.width as f64,
    };

    let join = map_join(svg_attrs);
    let cap = map_cap(svg_attrs);
    let (pos, neg) = subpath.outline(distance, join, cap);

    let mut pb = skia::PathBuilder::new();
    append_subpath(&mut pb, &pos);
    if let Some(neg) = neg {
        // Closed shapes yield an inner contour that punches the hole.
        append_subpath(&mut pb, &neg);
    }
    let mut out = pb.detach();
    out.set_fill_type(skia::PathFillType::EvenOdd);
    Some(out)
}

/// Skia stroker fallback (preserves dash/dot effects).
fn skia_outline(
    stroke: &Stroke,
    transformed_shape_path: &skia::Path,
    selrect: &Rect,
    svg_attrs: Option<&SvgAttrs>,
    render_kind: StrokeKind,
    solid_outline: bool,
) -> Option<skia::Path> {
    let mut paint = stroke.to_paint(selrect, svg_attrs, true);

    if solid_outline {
        paint.set_path_effect(None);
    }

    if render_kind != StrokeKind::Center {
        paint.set_stroke_width(stroke.width * 2.0);
    }

    let mut stroke_outline = skia::Path::default();
    let success = skia::path_utils::fill_path_with_paint(
        transformed_shape_path,
        &paint,
        &mut stroke_outline,
        None,
        None,
    );

    if !success {
        return None;
    }
    Some(stroke_outline)
}

/// Append a (closed) bezier-rs subpath to a Skia path builder, preserving curves.
fn append_subpath(pb: &mut skia::PathBuilder, subpath: &Subpath<StrokeId>) {
    let beziers: Vec<Bezier> = subpath.iter().collect();
    let Some(first) = beziers.first() else {
        return;
    };
    pb.move_to(to_point(first.start));
    for b in &beziers {
        match b.handles {
            BezierHandles::Linear => {
                pb.line_to(to_point(b.end));
            }
            BezierHandles::Quadratic { handle } => {
                // Elevate the quadratic to a cubic (PathBuilder has no quad_to).
                let s = b.start;
                let e = b.end;
                let c1 = s + (2.0 / 3.0) * (handle - s);
                let c2 = e + (2.0 / 3.0) * (handle - e);
                pb.cubic_to(to_point(c1), to_point(c2), to_point(e));
            }
            BezierHandles::Cubic {
                handle_start,
                handle_end,
            } => {
                pb.cubic_to(to_point(handle_start), to_point(handle_end), to_point(b.end));
            }
        }
    }
    pb.close();
}

#[inline]
fn to_point(p: glam::DVec2) -> skia::Point {
    skia::Point::new(p.x as f32, p.y as f32)
}

fn map_join(svg_attrs: Option<&SvgAttrs>) -> Join {
    match svg_attrs.map(|a| a.stroke_linejoin) {
        Some(StrokeLineJoin::Round) => Join::Round,
        Some(StrokeLineJoin::Bevel) => Join::Bevel,
        // Miter limit defaults to 4 (SVG default) when None.
        Some(StrokeLineJoin::Miter) | None => Join::Miter(None),
    }
}

fn map_cap(svg_attrs: Option<&SvgAttrs>) -> Cap {
    match svg_attrs.map(|a| a.stroke_linecap) {
        Some(StrokeLineCap::Round) => Cap::Round,
        Some(StrokeLineCap::Square) => Cap::Square,
        Some(StrokeLineCap::Butt) | None => Cap::Butt,
    }
}
