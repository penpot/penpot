use skia_safe::{self as skia};

use super::paths::Path;
use super::strokes::{Stroke, StrokeCap, StrokeKind};
use super::svg_attrs::SvgAttrs;
use crate::math::{Matrix, Point, Rect};

/// Converts a stroke into a filled path outline.
///
/// Uses Skia's `fill_path_with_paint` to expand the stroke into a filled region,
/// then clips it via boolean ops for inner/outer alignment. The optional
/// `path_transform` maps from local shape coords to the drawing space (and back).
///
/// When `solid_outline` is true, any dash/dot PathEffect is stripped so the result
/// is a continuous stroke region — useful for clipping (e.g. drag crop cache) where
/// dash gaps should not punch holes in the clip mask.
pub fn stroke_to_path(
    stroke: &Stroke,
    shape_path: &Path,
    path_transform: Option<&skia::Matrix>,
    selrect: &Rect,
    svg_attrs: Option<&SvgAttrs>,
    solid_outline: bool,
) -> Option<Path> {
    let skia_shape_path = shape_path.to_skia_path(svg_attrs);

    let transformed_shape_path = if let Some(pt) = path_transform {
        skia_shape_path.make_transform(pt)
    } else {
        skia_shape_path.clone()
    };

    let is_open = shape_path.is_open();
    let mut paint = stroke.to_paint(selrect, svg_attrs, true);

    if solid_outline {
        paint.set_path_effect(None);
    }

    let render_kind = stroke.render_kind(is_open);
    if render_kind != StrokeKind::Center {
        paint.set_stroke_width(stroke.width * 2.0);
    }

    // Round/Round and Square/Square caps are drawn natively by Skia; the rest
    // are added below as extra geometry.
    if let Some(cap) = stroke.to_skia_linecap() {
        paint.set_stroke_cap(cap);
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
    // then switch to Winding for Penpot's NonZero fill rule.
    // Use set_fill_type instead of as_winding() because as_winding()
    // decomposes self-intersecting geometry, which removes points
    // at intersections of straight lines in closed paths.
    // Center strokes skip the conversion: fill_path_with_paint
    // already produces correctly-wound contours.
    let final_path = match render_kind {
        StrokeKind::Inner => stroke_outline
            .simplify()
            .unwrap()
            .op(&transformed_shape_path, skia::PathOp::Intersect)
            .unwrap_or(stroke_outline),
        StrokeKind::Outer => stroke_outline
            .simplify()
            .unwrap()
            .op(&transformed_shape_path, skia::PathOp::Difference)
            .unwrap_or(stroke_outline),
        StrokeKind::Center => stroke_outline.simplify().unwrap_or(stroke_outline),
    };

    // Markers and arrow heads are painted on top of the stroke by
    // `handle_stroke_caps`, so they are not part of the outline above.
    let final_path = match is_open
        .then(|| stroke_caps_to_path(&transformed_shape_path, stroke))
        .flatten()
    {
        Some(caps) => final_path
            .op(&caps, skia::PathOp::Union)
            .unwrap_or(final_path),
        None => final_path,
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

/// Builds the square/diamond cap quad centered on `center`, rotated to look
/// towards `direction` plus `extra_rotation` degrees.
pub fn square_cap_path(
    center: &Point,
    direction: &Point,
    size: f32,
    extra_rotation: f32,
) -> skia::Path {
    let angle = (direction.y - center.y).atan2(direction.x - center.x);

    let mut matrix = Matrix::new_identity();
    matrix.pre_rotate(
        angle.to_degrees() + extra_rotation,
        Point::new(center.x, center.y),
    );

    let half_size = size / 2.0;
    let rect = Rect::from_xywh(center.x - half_size, center.y - half_size, size, size);

    let points = [
        Point::new(rect.left(), rect.top()),
        Point::new(rect.right(), rect.top()),
        Point::new(rect.right(), rect.bottom()),
        Point::new(rect.left(), rect.bottom()),
    ];

    let mut transformed_points = points;
    matrix.map_points(&mut transformed_points, &points);

    let mut pb = skia::PathBuilder::new();
    pb.move_to(transformed_points[0]);
    pb.line_to(transformed_points[1]);
    pb.line_to(transformed_points[2]);
    pb.line_to(transformed_points[3]);
    pb.close();
    pb.detach()
}

/// Builds the (open) line-arrow polyline: the two arrow sides plus the stem
/// back to `center`. Meant to be painted/expanded with a stroke paint.
pub fn arrow_cap_path(center: &Point, direction: &Point, size: f32) -> skia::Path {
    let mut pb = skia::PathBuilder::new();
    let points = arrow_head_points(center, direction, size);
    pb.move_to(points[1]);
    pb.line_to(points[0]);
    pb.line_to(points[2]);
    pb.move_to(Point::new(center.x, center.y));
    pb.line_to(points[0]);
    pb.detach()
}

/// Builds the closed triangle-arrow cap.
pub fn triangle_cap_path(center: &Point, direction: &Point, size: f32) -> skia::Path {
    let mut pb = skia::PathBuilder::new();
    let points = arrow_head_points(center, direction, size);
    pb.move_to(points[0]);
    pb.line_to(points[1]);
    pb.line_to(points[2]);
    pb.close();
    pb.detach()
}

/// Tip and the two base corners of an arrow head of `size`, pointing from
/// `center` towards `direction`.
fn arrow_head_points(center: &Point, direction: &Point, size: f32) -> [Point; 3] {
    let angle = (direction.y - center.y).atan2(direction.x - center.x);

    let mut matrix = Matrix::new_identity();
    matrix.pre_rotate(angle.to_degrees() - 90., Point::new(center.x, center.y));

    let half_height = size / 2.;
    let points = [
        Point::new(center.x, center.y - half_height),
        Point::new(center.x - size, center.y + half_height),
        Point::new(center.x + size, center.y + half_height),
    ];

    let mut transformed_points = points;
    matrix.map_points(&mut transformed_points, &points);
    transformed_points
}

/// Expands an open path into its filled stroke region of `width`.
fn stroke_region(path: &skia::Path, width: f32) -> Option<skia::Path> {
    let mut paint = skia::Paint::default();
    paint.set_style(skia::PaintStyle::Stroke);
    paint.set_stroke_width(width);

    let mut outline = skia::Path::default();
    skia::path_utils::fill_path_with_paint(path, &paint, &mut outline, None, None)
        .then_some(outline)
}

/// Filled geometry of a single stroke cap, matching what `handle_stroke_caps`
/// paints on the canvas.
fn cap_path(cap: StrokeCap, width: f32, p1: &Point, p2: &Point) -> Option<skia::Path> {
    let path = match cap {
        StrokeCap::LineArrow => {
            // The square cap fills the gap between the path and the arrow.
            let base = square_cap_path(p1, p2, width, 0.);
            let arrow = stroke_region(&arrow_cap_path(p1, p2, width * 4.), width)?;
            base.op(&arrow, skia::PathOp::Union)?
        }
        StrokeCap::TriangleArrow => triangle_cap_path(p1, p2, width * 4.),
        StrokeCap::SquareMarker => square_cap_path(p1, p2, width * 4., 0.),
        StrokeCap::CircleMarker => skia::Path::circle(*p1, width * 2., None),
        StrokeCap::DiamondMarker => square_cap_path(p1, p2, width * 4., 45.),
        StrokeCap::Round => skia::Path::circle(*p1, width / 2., None),
        StrokeCap::Square => square_cap_path(p1, p2, width, 0.),
    };
    Some(path)
}

/// Filled region covered by the start/end caps of an open path.
///
/// Returns `None` when there is nothing to add: closed-ish paths with less than
/// two points, no caps set, or caps Skia already draws natively on the stroke
/// paint (`Round/Round`, `Square/Square`, see [`Stroke::to_skia_linecap`]).
pub fn stroke_caps_to_path(path: &skia::Path, stroke: &Stroke) -> Option<skia::Path> {
    if stroke.to_skia_linecap().is_some() {
        return None;
    }

    // Curves can have duplicated points, so let's remove consecutive duplicated points
    let mut points = path.points().to_vec();
    points.dedup();

    let [first_point, .., last_point] = points.as_slice() else {
        return None;
    };

    let caps = [
        (stroke.cap_start, first_point, &points[1]),
        (stroke.cap_end, last_point, &points[points.len() - 2]),
    ];

    let mut acc: Option<skia::Path> = None;
    for (cap, p1, p2) in caps {
        let Some(cap) = cap else { continue };
        let Some(path) = cap_path(cap, stroke.width, p1, p2) else {
            continue;
        };
        acc = Some(match acc {
            Some(acc) => acc.op(&path, skia::PathOp::Union).unwrap_or(acc),
            None => path,
        });
    }

    acc
}

#[cfg(test)]
mod tests {
    use super::super::paths::Segment;
    use super::super::strokes::StrokeStyle;
    use super::*;

    fn horizontal_line() -> Path {
        Path::new(vec![Segment::MoveTo((0., 0.)), Segment::LineTo((100., 0.))])
    }

    fn outline_bounds(cap_start: Option<StrokeCap>, cap_end: Option<StrokeCap>) -> Rect {
        let stroke =
            Stroke::new_center_stroke(4., StrokeStyle::Solid, cap_start, cap_end, None, None);
        let path = horizontal_line();
        let selrect = Rect::from_xywh(0., 0., 100., 0.);
        stroke_to_path(&stroke, &path, None, &selrect, None, false)
            .expect("stroke outline")
            .to_skia_path(None)
            .compute_tight_bounds()
    }

    #[test]
    fn outline_without_caps_stays_within_the_path() {
        let bounds = outline_bounds(None, None);
        assert!(bounds.right <= 100.5, "bounds: {bounds:?}");
    }

    #[test]
    fn outline_includes_the_arrow_head() {
        // Arrow size is width * 4, so the tip sticks out ~8px past the end.
        let bounds = outline_bounds(None, Some(StrokeCap::TriangleArrow));
        assert!(bounds.right > 104., "bounds: {bounds:?}");
    }

    /// Farthest point from `center` still inside `path` along `angle`,
    /// probed between 0 and `max_radius`.
    fn radius_at(path: &skia::Path, center: (f32, f32), angle: f32, max_radius: f32) -> f32 {
        let (mut lo, mut hi) = (0., max_radius);
        for _ in 0..40 {
            let mid = (lo + hi) / 2.;
            let p = (center.0 + mid * angle.cos(), center.1 + mid * angle.sin());
            if path.contains(p) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        lo
    }

    #[test]
    fn round_cap_stays_round() {
        // Round/Round is drawn by Skia's own linecap, which also goes through
        // the conic conversion on the way back into a Penpot path.
        let stroke = Stroke::new_center_stroke(
            10.,
            StrokeStyle::Solid,
            Some(StrokeCap::Round),
            Some(StrokeCap::Round),
            None,
            None,
        );
        let selrect = Rect::from_xywh(0., 0., 100., 0.);
        let path = stroke_to_path(&stroke, &horizontal_line(), None, &selrect, None, false)
            .expect("stroke outline")
            .to_skia_path(None);

        // The cap is a half disc of radius width / 2 around the path end.
        let expected = 5.;
        for step in [-1, 0, 1] {
            let angle = std::f32::consts::FRAC_PI_4 * step as f32;
            let radius = radius_at(&path, (100., 0.), angle, expected * 2.);
            assert!(
                (radius - expected).abs() < expected * 0.01,
                "radius at {angle}rad: {radius}"
            );
        }
    }

    #[test]
    fn closed_paths_get_no_caps() {
        // Caps only exist at sub-path ends, so a closed path must come back
        // as the plain outline, arrow cap or not.
        let stroke = Stroke::new_center_stroke(
            4.,
            StrokeStyle::Solid,
            Some(StrokeCap::TriangleArrow),
            Some(StrokeCap::TriangleArrow),
            None,
            None,
        );
        let square = Path::new(vec![
            Segment::MoveTo((0., 0.)),
            Segment::LineTo((100., 0.)),
            Segment::LineTo((100., 100.)),
            Segment::LineTo((0., 100.)),
            Segment::Close,
        ]);
        let selrect = Rect::from_xywh(0., 0., 100., 100.);
        let bounds = stroke_to_path(&stroke, &square, None, &selrect, None, false)
            .expect("stroke outline")
            .to_skia_path(None)
            .compute_tight_bounds();

        // Center stroke of width 4 grows the square by 2 on every side.
        assert!(bounds.left > -2.5 && bounds.right < 102.5, "bounds: {bounds:?}");
    }

    #[test]
    fn square_marker_cap_matches_the_canvas_size() {
        // SquareMarker draws a width * 4 square centered on the path end, so
        // it sticks out width * 2 past it.
        let bounds = outline_bounds(None, Some(StrokeCap::SquareMarker));
        assert!((bounds.right - 108.).abs() < 0.5, "bounds: {bounds:?}");
    }

    #[test]
    fn circle_marker_cap_stays_round() {
        // Regression: conics were converted to a single quad each, so circle
        // markers bulged ~6% at the arc midpoints and looked like squircles.
        let stroke = Stroke::new_center_stroke(
            4.,
            StrokeStyle::Solid,
            None,
            Some(StrokeCap::CircleMarker),
            None,
            None,
        );
        let selrect = Rect::from_xywh(0., 0., 100., 0.);
        let path = stroke_to_path(&stroke, &horizontal_line(), None, &selrect, None, false)
            .expect("stroke outline")
            .to_skia_path(None);

        // CircleMarker radius is width * 2, centered on the path end.
        let expected = 8.;
        for step in 0..8 {
            // Skip the direction pointing back along the line, where the cap
            // merges into the stroke band.
            if step == 4 {
                continue;
            }
            let angle = std::f32::consts::FRAC_PI_4 * step as f32;
            let radius = radius_at(&path, (100., 0.), angle, expected * 2.);
            assert!(
                (radius - expected).abs() < expected * 0.01,
                "radius at {}rad: {radius}",
                angle
            );
        }
    }

    #[test]
    fn outline_includes_mixed_round_and_arrow_caps() {
        // Regression for #10825: a round start plus an arrow end used to be
        // dropped entirely by stroke-to-path.
        let bounds = outline_bounds(Some(StrokeCap::Round), Some(StrokeCap::LineArrow));
        assert!(bounds.left < -1., "bounds: {bounds:?}");
        assert!(bounds.right > 104., "bounds: {bounds:?}");
    }
}
