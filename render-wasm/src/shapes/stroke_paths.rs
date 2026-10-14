use skia_safe::{self as skia};

use super::paths::Path;
use super::strokes::{Side, Stroke, StrokeCap, StrokeKind, StrokeStyle};
use super::svg_attrs::SvgAttrs;
use super::{Corners, Shape};
use crate::math::{Matrix, Point, Rect};
use skia::RRect;

/// Geometry shared by per-side rendering and stroke-to-path conversion.
pub struct PerSideStrokeGeometry {
    pub band: skia::Path,
    pub pattern: Vec<skia::Path>,
    pub wedges: Option<skia::Path>,
    pub clip_pattern: bool,
    pattern_failed: bool,
}

impl PerSideStrokeGeometry {
    pub fn new(
        stroke: &Stroke,
        rect: &Rect,
        corners: &Option<Corners>,
        widths: [f32; 4],
        scale: f32,
        miter: Option<[f32; 4]>,
        antialias: bool,
    ) -> Self {
        // Strokes sharing a border box are shaped by the whole box: a side this one
        // does not paint still sets the radii its corners curve to, as in CSS.
        let band_widths: [f32; 4] = match miter {
            Some(profile) => std::array::from_fn(|side| {
                if widths[side] > 0.0 {
                    widths[side]
                } else {
                    profile[side]
                }
            }),
            None => widths,
        };
        let [top, right, bottom, left] = band_widths;

        // Fraction of each side width growing outward / inward from the shape
        // boundary, per stroke alignment.
        let (out_f, in_f) = match stroke.kind {
            StrokeKind::Inner => (0.0, 1.0),
            StrokeKind::Center => (0.5, 0.5),
            StrokeKind::Outer => (1.0, 0.0),
        };

        let outer = Rect::from_ltrb(
            rect.left - left * out_f,
            rect.top - top * out_f,
            rect.right + right * out_f,
            rect.bottom + bottom * out_f,
        );
        let inner = Rect::from_ltrb(
            rect.left + left * in_f,
            rect.top + top * in_f,
            rect.right - right * in_f,
            rect.bottom - bottom * in_f,
        );
        // When a side width exceeds the shape dimension the inner rect collapses
        // and the whole outer rect is filled, like CSS collapsing borders.
        let has_hole = inner.width() > 0.0 && inner.height() > 0.0;

        let mut pb = skia::PathBuilder::new();
        let (outer_radii, inner_radii) = match corners {
            Some(radii) => {
                // Straight (zero-radius) corners stay sharp; rounded ones keep
                // their curvature parallel to the shape edge, like CSS borders.
                let grow = |radius: f32, delta: f32| {
                    if radius > 0.0 {
                        radius + delta
                    } else {
                        0.0
                    }
                };
                let shrink = |radius: f32, delta: f32| (radius - delta).max(0.0);
                // Adjacent side widths per corner, matching the Skia radii order:
                // [top-left, top-right, bottom-right, bottom-left].
                let side_deltas = [(left, top), (right, top), (right, bottom), (left, bottom)];
                let mut outer_radii = *radii;
                let mut inner_radii = *radii;
                for (i, (dx, dy)) in side_deltas.into_iter().enumerate() {
                    outer_radii[i].x = grow(outer_radii[i].x, dx * out_f);
                    outer_radii[i].y = grow(outer_radii[i].y, dy * out_f);
                    inner_radii[i].x = shrink(inner_radii[i].x, dx * in_f);
                    inner_radii[i].y = shrink(inner_radii[i].y, dy * in_f);
                }
                pb.add_rrect(RRect::new_rect_radii(outer, &outer_radii), None, None);
                if has_hole {
                    pb.add_rrect(RRect::new_rect_radii(inner, &inner_radii), None, None);
                }
                (Some(outer_radii), Some(inner_radii))
            }
            None => {
                pb.add_rect(outer, None, None);
                if has_hole {
                    pb.add_rect(inner, None, None);
                }
                (None, None)
            }
        };

        let mut band = pb.detach();
        band.set_fill_type(skia::PathFillType::EvenOdd);

        // Solid is the common case and the whole band is one fill. A collapsed
        // band has no sides left to walk, so it fills solid too.
        let style = stroke.with_width(stroke.max_width()).style_at_scale(scale);
        let (pattern, pattern_failed) = if style == StrokeStyle::Solid || !has_hole {
            (Vec::new(), false)
        } else {
            expand_side_patterns(
                stroke,
                style,
                rect,
                &outer,
                &inner,
                inner_radii,
                widths,
                antialias,
            )
        };

        // Sides this stroke shares with another per-side stroke meet on a diagonal,
        // so each one keeps only its own wedge of the corner.
        let wedges = miter.and_then(|profile| side_wedges(&outer, outer_radii, profile, widths));

        let rounded = corners.is_some_and(|radii| radii.iter().any(|r| r.x > 0.0 || r.y > 0.0));
        Self {
            band,
            pattern,
            wedges,
            clip_pattern: rounded || style == StrokeStyle::Dotted,
            pattern_failed,
        }
    }

    /// Resolve the renderer's clips as vector intersections. A failed operation
    /// must fail the conversion rather than return a partially clipped stroke.
    pub fn to_path(&self) -> Option<skia::Path> {
        if self.pattern_failed {
            return None;
        }
        if self.pattern.is_empty() {
            return match &self.wedges {
                Some(wedges) => self.band.op(wedges, skia::PathOp::Intersect),
                None => Some(self.band.clone()),
            };
        }
        if !self.clip_pattern && self.wedges.is_none() {
            let mut pb = skia::PathBuilder::new();
            for part in &self.pattern {
                pb.add_path(part);
            }
            return Some(pb.detach());
        }
        // Intersect stamps separately: PathOps can drop overlapping dash and
        // corner contours when given the renderer's appended pattern path.
        let mut result: Option<skia::Path> = None;
        for part in &self.pattern {
            let mut path = if self.clip_pattern {
                part.op(&self.band, skia::PathOp::Intersect)?
            } else {
                part.clone()
            };
            if let Some(wedges) = &self.wedges {
                path = path.op(wedges, skia::PathOp::Intersect)?;
            }
            result = Some(match result {
                Some(result) => result.op(&path, skia::PathOp::Union)?,
                None => path,
            });
        }
        result
    }
}

/// Rect/frame geometry starts at selrect and enters document space once.
pub fn per_side_stroke_to_path(shape: &Shape, stroke: &Stroke) -> Option<Path> {
    let geometry = PerSideStrokeGeometry::new(
        stroke,
        &shape.selrect,
        &shape.shape_type.corners(),
        stroke.per_side_widths()?,
        f32::INFINITY,
        Stroke::per_side_profile(shape.visible_strokes()),
        true,
    );
    let center = shape.center();
    let mut transform = shape.transform;
    transform.post_translate(center);
    transform.pre_translate(-center);
    let path = geometry.to_path()?.make_transform(&transform);
    if !path.is_finite() || path.is_empty() {
        return None;
    }
    Some(Path::from_skia_path_accurate(path))
}

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
        assert!(
            bounds.left > -2.5 && bounds.right < 102.5,
            "bounds: {bounds:?}"
        );
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

    #[test]
    fn dashed_stroke_keeps_solid_caps() {
        let stroke = Stroke::new_center_stroke(
            4.,
            StrokeStyle::Dashed,
            Some(StrokeCap::Round),
            Some(StrokeCap::Round),
            Some(7.),
            Some(7.),
        );
        let selrect = Rect::from_xywh(0., 0., 100., 0.);
        let path = stroke_to_path(&stroke, &horizontal_line(), None, &selrect, None, false)
            .expect("stroke outline")
            .to_skia_path(None);

        assert!(path.contains((-1.5, 0.)), "start cap missing");
        assert!(path.contains((101.5, 0.)), "end cap missing");
    }

    #[test]
    fn dashed_stroke_marker_caps_are_not_dashed() {
        let stroke = Stroke::new_center_stroke(
            4.,
            StrokeStyle::Dashed,
            None,
            Some(StrokeCap::SquareMarker),
            Some(3.),
            Some(3.),
        );
        let selrect = Rect::from_xywh(0., 0., 100., 0.);
        let path = stroke_to_path(&stroke, &horizontal_line(), None, &selrect, None, false)
            .expect("stroke outline")
            .to_skia_path(None);

        for x in [100., 102., 104., 106., 107.5] {
            assert!(path.contains((x, 0.)), "marker has a gap at x = {x}");
        }
    }
}

/// Where a corner's miter line sits at the outer edge and at `depth` into the
/// side. It runs through the centre of curvature, so a corner splits on its arc.
fn miter_offsets(radius: (f32, f32), own: f32, adjacent: f32, depth: f32) -> (f32, f32) {
    if own <= 0.0 {
        return (0.0, adjacent);
    }
    // A corner with no neighbour to meet belongs to this side alone.
    let (along, across) = if adjacent > 0.0 { radius } else { (0.0, 0.0) };
    let slope = adjacent / own;
    (along - across * slope, along - (across - depth) * slope)
}

/// The CSS miter wedges for the sides this stroke paints, each narrowing to a
/// diagonal where it meets a neighbour. `None` when it already fills the profile.
fn side_wedges(
    outer: &Rect,
    outer_radii: Option<Corners>,
    profile: [f32; 4],
    widths: [f32; 4],
) -> Option<skia::Path> {
    if widths == profile {
        return None;
    }
    let [top, right, bottom, left] = profile;
    let radii = outer_radii.unwrap_or_default();

    let mut pb = skia::PathBuilder::new();
    let mut wedged = false;
    for side in Side::ALL {
        if widths[side.index()] <= 0.0 {
            continue;
        }
        // Each side runs from its own corner to the next one clockwise.
        let lead = side.index();
        let trail = (lead + 1) % 4;
        let (own, ccw, cw) = match side {
            Side::Top => (top, left, right),
            Side::Right => (right, top, bottom),
            Side::Bottom => (bottom, right, left),
            Side::Left => (left, bottom, top),
        };
        // A corner radius is an ellipse: the component along the side sets where
        // the miter meets it, the one across it sets how deep the corner runs.
        let (lead_radius, trail_radius) = match side {
            Side::Top | Side::Bottom => (
                (radii[lead].x, radii[lead].y),
                (radii[trail].x, radii[trail].y),
            ),
            Side::Right | Side::Left => (
                (radii[lead].y, radii[lead].x),
                (radii[trail].y, radii[trail].x),
            ),
        };
        // A rounded corner bulges past the straight strip, so the wedge reaches
        // deeper than the side is thick. The band trims whatever overshoots.
        let depth = own + lead_radius.1.max(trail_radius.1);
        let (a_out, a_in) = miter_offsets(lead_radius, own, ccw, depth);
        let (b_out, b_in) = miter_offsets(trail_radius, own, cw, depth);
        let quad = match side {
            Side::Top => [
                (outer.left + a_out, outer.top),
                (outer.right - b_out, outer.top),
                (outer.right - b_in, outer.top + depth),
                (outer.left + a_in, outer.top + depth),
            ],
            Side::Right => [
                (outer.right, outer.top + a_out),
                (outer.right, outer.bottom - b_out),
                (outer.right - depth, outer.bottom - b_in),
                (outer.right - depth, outer.top + a_in),
            ],
            Side::Bottom => [
                (outer.right - a_out, outer.bottom),
                (outer.left + b_out, outer.bottom),
                (outer.left + b_in, outer.bottom - depth),
                (outer.right - a_in, outer.bottom - depth),
            ],
            Side::Left => [
                (outer.left, outer.bottom - a_out),
                (outer.left, outer.top + b_out),
                (outer.left + depth, outer.top + b_in),
                (outer.left + depth, outer.bottom - a_in),
            ],
        };
        pb.add_polygon(&quad.map(Point::from), true);
        wedged = true;
    }
    wedged.then(|| pb.detach())
}

/// Expands each side's dash or dot pattern into the geometry it paints, fitted
/// so the side begins and ends on a dash. Tracks failed sides for conversion;
/// rendering keeps its existing partial-pattern or solid-band fallback.
#[allow(clippy::too_many_arguments)]
fn expand_side_patterns(
    stroke: &Stroke,
    style: StrokeStyle,
    rect: &Rect,
    outer: &Rect,
    inner: &Rect,
    inner_radii: Option<Corners>,
    widths: [f32; 4],
    antialias: bool,
) -> (Vec<skia::Path>, bool) {
    let mut parts = Vec::new();
    let mut failed = false;

    let mut stamp_paint = skia::Paint::default();
    stamp_paint.set_style(skia::PaintStyle::Stroke);
    stamp_paint.set_stroke_cap(skia::paint::Cap::Butt);
    stamp_paint.set_anti_alias(antialias);

    for side in Side::ALL {
        let width = widths[side.index()];
        if width <= 0.0 {
            continue;
        }

        let mut side_stroke = stroke.with_width(width);
        side_stroke.style = style;

        // Where the uniform renderer would stroke this width, so inner/outer
        // dots still come out as half circles once the band clips them.
        let line = side_stroke.outer_rect(rect);
        let (start, end) = match side {
            Side::Top => ((outer.left, line.top), (outer.right, line.top)),
            Side::Right => ((line.right, outer.top), (line.right, outer.bottom)),
            Side::Bottom => ((outer.right, line.bottom), (outer.left, line.bottom)),
            Side::Left => ((line.left, outer.bottom), (line.left, outer.top)),
        };
        let length = match side {
            Side::Top | Side::Bottom => outer.width(),
            Side::Right | Side::Left => outer.height(),
        };

        let Some(effect) = side_stroke.path_effect_fitted(length) else {
            failed = true;
            continue;
        };
        stamp_paint.set_stroke_width(width);
        stamp_paint.set_path_effect(effect);

        let line_path = {
            let mut line_pb = skia::PathBuilder::new();
            line_pb.move_to(start);
            line_pb.line_to(end);
            line_pb.detach()
        };

        let mut outline = skia::Path::default();
        if skia::path_utils::fill_path_with_paint(
            &line_path,
            &stamp_paint,
            &mut outline,
            None,
            None,
        ) {
            parts.push(outline);
        } else {
            failed = true;
        }
    }

    // A rounded band's corner notches paint solid, as a browser does for uneven
    // widths. Dots are discrete and must not weld into a solid corner.
    if style != StrokeStyle::Dotted {
        if let Some(radii) = inner_radii {
            for patch in corner_patches(outer, inner, &radii) {
                parts.push(skia::Path::rect(patch, None));
            }
        }
    }

    (parts, failed)
}

/// The corner boxes of a rounded band, from each side's leading outer corner to
/// where that corner's inner arc meets the straight edges.
fn corner_patches(outer: &Rect, inner: &Rect, inner_radii: &Corners) -> Vec<Rect> {
    Side::ALL
        .into_iter()
        .filter_map(|side| {
            let radius = inner_radii[side.index()];
            if radius.x <= 0.0 && radius.y <= 0.0 {
                return None;
            }
            let (left, right) = match side {
                Side::Top | Side::Left => (outer.left, inner.left + radius.x),
                Side::Right | Side::Bottom => (inner.right - radius.x, outer.right),
            };
            let (top, bottom) = match side {
                Side::Top | Side::Right => (outer.top, inner.top + radius.y),
                Side::Bottom | Side::Left => (inner.bottom - radius.y, outer.bottom),
            };
            let patch = Rect::from_ltrb(left, top, right, bottom);
            (patch.width() > 0.0 && patch.height() > 0.0).then_some(patch)
        })
        .collect()
}

#[cfg(test)]
mod per_side_tests {
    use super::*;
    use crate::shapes::StrokeStyle;

    fn rect_shape(kind: StrokeKind, widths: [f32; 4], style: StrokeStyle) -> Shape {
        let mut shape = Shape::new(crate::uuid::Uuid::nil());
        shape.selrect = Rect::from_xywh(0., 0., 140., 100.);
        let mut stroke = Stroke::new_inner_stroke(20., style, None, None, None, None);
        stroke.kind = kind;
        stroke.widths = Some(widths);
        shape.strokes.push(stroke);
        shape
    }

    fn converted(shape: &Shape) -> Path {
        per_side_stroke_to_path(shape, &shape.strokes[0]).expect("per-side conversion")
    }

    fn serialized(path: &Path) -> skia::Path {
        Path::new(path.segments().clone())
            .with_even_odd(path.is_even_odd())
            .to_skia_path(None)
    }

    #[test]
    fn inner_stroke_keeps_each_side_and_the_serialized_hole() {
        let shape = rect_shape(StrokeKind::Inner, [4., 12., 24., 40.], StrokeStyle::Solid);
        let path = converted(&shape);
        assert!(path.is_even_odd());
        let path = serialized(&path);
        for point in [(39.5, 50.), (128.5, 50.), (70., 3.5), (70., 76.5)] {
            assert!(path.contains(point), "missing side at {point:?}");
        }
        for point in [(40.5, 50.), (127.5, 50.), (70., 4.5), (70., 75.5)] {
            assert!(!path.contains(point), "hole filled at {point:?}");
        }
        assert_eq!(path.compute_tight_bounds(), shape.selrect);
        // Check the whole band and hole without the render mask's boundary tolerance.
        for y in -2..102 {
            for x in -2..142 {
                let in_rect = (0..140).contains(&x) && (0..100).contains(&y);
                let in_hole = (40..128).contains(&x) && (4..76).contains(&y);
                assert_eq!(
                    path.contains((x as f32 + 0.5, y as f32 + 0.5)),
                    in_rect && !in_hole,
                    "exact inner region at ({x}, {y})"
                );
            }
        }
    }

    #[test]
    fn center_and_outer_strokes_keep_their_band_edges() {
        for (kind, outer, inner) in [
            (
                StrokeKind::Center,
                (-20., -2., 146., 112.),
                (20., 2., 134., 88.),
            ),
            (
                StrokeKind::Outer,
                (-40., -4., 152., 124.),
                (0., 0., 140., 100.),
            ),
        ] {
            let shape = rect_shape(kind, [4., 12., 24., 40.], StrokeStyle::Solid);
            let path = serialized(&converted(&shape));
            assert_eq!(
                path.compute_tight_bounds(),
                Rect::from_ltrb(outer.0, outer.1, outer.2, outer.3)
            );
            for point in [
                (inner.0 - 0.5, 50.),
                (inner.2 + 0.5, 50.),
                (70., inner.1 - 0.5),
                (70., inner.3 + 0.5),
            ] {
                assert!(path.contains(point), "missing {kind:?} band at {point:?}");
            }
            assert!(!path.contains((70., 50.)));
        }
    }

    #[test]
    fn zero_width_side_remains_empty() {
        let shape = rect_shape(StrokeKind::Inner, [0., 12., 24., 40.], StrokeStyle::Solid);
        let path = serialized(&converted(&shape));
        assert!(!path.contains((70., 0.5)));
        assert!(path.contains((135., 50.)));
        assert!(path.contains((70., 90.)));
        assert!(path.contains((20., 50.)));
    }

    #[test]
    fn collapsed_hole_matches_the_renderers_solid_fallback() {
        for style in [
            StrokeStyle::Solid,
            StrokeStyle::Dashed,
            StrokeStyle::Dotted,
            StrokeStyle::Mixed,
        ] {
            let shape = rect_shape(StrokeKind::Inner, [60., 12., 60., 40.], style);
            let path = serialized(&converted(&shape));
            assert!(path.contains((70., 50.)), "collapsed {style:?} hole");
            assert!(!path.contains((-0.5, 50.)));
            assert_eq!(path.compute_tight_bounds(), shape.selrect);
        }
    }

    #[test]
    fn rounded_corners_survive_accurate_serialization() {
        let mut shape = rect_shape(StrokeKind::Inner, [4., 12., 24., 40.], StrokeStyle::Solid);
        shape.shape_type.set_corners([Point::new(48., 48.); 4]);
        let path = converted(&shape);
        assert!(path
            .segments()
            .iter()
            .any(|s| matches!(s, super::super::paths::Segment::CurveTo(_))));
        let path = serialized(&path);
        assert!(!path.contains((1., 1.)));
        assert!(path.contains((70., 2.)));
        assert!(!path.contains((70., 50.)));
    }

    #[test]
    fn rotation_and_reflection_apply_once_about_selrect_center() {
        for flip in [1., -1.] {
            let mut shape = rect_shape(StrokeKind::Inner, [4., 12., 24., 40.], StrokeStyle::Solid);
            shape.selrect.offset((200., 100.));
            shape.transform.pre_rotate(37., None);
            shape.transform.pre_scale((flip, 1.), None);
            let path = serialized(&converted(&shape));
            let center = shape.center();
            let mut transform = shape.transform;
            transform.post_translate(center);
            transform.pre_translate(-center);
            for (point, inside) in [
                ((220., 150.), true),
                ((270., 102.), true),
                ((270., 150.), false),
                ((190., 150.), false),
            ] {
                let mapped = transform.map_point(Point::from(point));
                assert_eq!(path.contains(mapped), inside, "flip {flip} at {point:?}");
            }
        }
    }

    #[test]
    fn frame_uses_its_border_without_reading_children() {
        let mut shape = rect_shape(StrokeKind::Inner, [4., 12., 24., 40.], StrokeStyle::Solid);
        let rect = converted(&shape);
        shape.shape_type = super::super::Type::Frame(super::super::Frame::default());
        shape.children.push(crate::uuid::Uuid::new_v4());
        assert_eq!(converted(&shape), rect);
    }

    #[test]
    fn uniform_widths_stay_on_the_existing_conversion_path() {
        let shape = rect_shape(StrokeKind::Inner, [20.; 4], StrokeStyle::Dashed);
        assert!(per_side_stroke_to_path(&shape, &shape.strokes[0]).is_none());
        let rect = Path::from_skia_path_accurate(skia::Path::rect(shape.selrect, None));
        let outline = |stroke: &Stroke| {
            stroke_to_path(stroke, &rect, None, &shape.selrect, None, false).unwrap()
        };
        assert_eq!(
            outline(&shape.strokes[0]),
            outline(&shape.strokes[0].with_width(20.))
        );
    }

    #[test]
    fn a_failed_side_rejects_conversion_instead_of_returning_a_partial_stroke() {
        let mut shape = rect_shape(StrokeKind::Inner, [4., 12., 24., 40.], StrokeStyle::Dashed);
        shape.strokes[0].dash = Some(f32::NAN);
        assert!(per_side_stroke_to_path(&shape, &shape.strokes[0]).is_none());
        assert_eq!(shape.strokes.len(), 1);
    }

    /// Outer-aligned radii for a 220x140 rect, radius 20, widths [10, 20, 15, 5]:
    /// each corner grows by its two adjacent widths, so they are wider than tall.
    fn outer_radii() -> Corners {
        [
            skia::Point::new(25.0, 30.0),
            skia::Point::new(40.0, 30.0),
            skia::Point::new(40.0, 35.0),
            skia::Point::new(25.0, 35.0),
        ]
    }

    /// Reads a wedge path back as a flat list of points.
    fn wedge_points(path: &skia::Path) -> Vec<(f32, f32)> {
        path.points().iter().map(|p| (p.x, p.y)).collect()
    }

    #[test]
    fn a_wedge_reads_the_radius_along_the_side_it_runs_on() {
        // The outer rect for an outer-aligned stroke: each edge pushed out by
        // that side's width.
        let outer = Rect::from_ltrb(-5.0, -10.0, 240.0, 155.0);
        let profile = [10.0, 20.0, 15.0, 5.0];

        // Only the right side is painted, as one stroke of a per-side colour set.
        let wedge = side_wedges(&outer, Some(outer_radii()), profile, [0.0, 20.0, 0.0, 0.0])
            .expect("the right side is painted, so it gets a wedge");

        // The corner ellipses are 40 wide and 30/35 tall, and a vertical side
        // reads the tall one, so the wedge meets the top edge at the corner.
        assert_eq!(
            wedge_points(&wedge),
            vec![(240.0, 0.0), (240.0, 150.0), (180.0, 105.0), (180.0, 30.0),]
        );
    }

    /// Whether `point` sits on the infinite line through `a` and `b`.
    fn on_line(a: (f32, f32), b: (f32, f32), point: (f32, f32)) -> bool {
        let cross = (b.0 - a.0) * (point.1 - a.1) - (b.1 - a.1) * (point.0 - a.0);
        cross.abs() < 1e-3
    }

    #[test]
    fn adjacent_wedges_share_one_miter_line() {
        let outer = Rect::from_ltrb(-5.0, -10.0, 240.0, 155.0);
        let profile = [10.0, 20.0, 15.0, 5.0];
        let radii = Some(outer_radii());

        let top = wedge_points(
            &side_wedges(&outer, radii, profile, [10.0, 0.0, 0.0, 0.0]).expect("top wedge"),
        );
        let right = wedge_points(
            &side_wedges(&outer, radii, profile, [0.0, 20.0, 0.0, 0.0]).expect("right wedge"),
        );

        // Both edges facing the top-right corner run through its centre of
        // curvature, so the two sides abut with neither a gap nor an overlap.
        let centre = (240.0 - 40.0, -10.0 + 30.0);
        assert!(on_line(top[1], top[2], centre), "top edge missed: {top:?}");
        assert!(
            on_line(right[0], right[3], centre),
            "right edge missed: {right:?}"
        );
        // And they are the same line, not merely two lines through one point.
        assert!(
            on_line(top[1], top[2], right[0]),
            "not collinear: {right:?}"
        );
    }

    /// Inner-aligned pattern for a 235x175 rect with a nominal 30/30 dash:
    /// neither side holds whole periods, so each stretches by its own amount.
    fn dashed_pattern() -> skia::Path {
        let rect = Rect::from_ltrb(0.0, 0.0, 235.0, 175.0);
        let widths = [16.0, 16.0, 16.0, 24.0];
        let mut stroke = Stroke::new_inner_stroke(
            16.0,
            StrokeStyle::Dashed,
            None,
            None,
            Some(30.0),
            Some(30.0),
        );
        stroke.widths = Some(widths);
        let inner = Rect::from_ltrb(24.0, 16.0, 235.0 - 16.0, 175.0 - 16.0);
        let (parts, failed) = expand_side_patterns(
            &stroke,
            StrokeStyle::Dashed,
            &rect,
            &rect,
            &inner,
            None,
            widths,
            true,
        );
        assert!(!failed);
        let mut pb = skia::PathBuilder::new();
        for part in parts {
            pb.add_path(&part);
        }
        pb.detach()
    }

    #[test]
    fn each_side_starts_and_ends_on_a_dash() {
        let pattern = dashed_pattern();

        // Top side, over x. Sampled clear of the left and right strips so the
        // reading is the top side's own pattern.
        assert!(pattern.contains((30.0, 8.0)), "top must open with a dash");
        assert!(pattern.contains((210.0, 8.0)), "top must close with a dash");

        // Right side, over y, at the vertical stroke line (235 - 16 / 2).
        assert!(
            pattern.contains((227.0, 30.0)),
            "right must open with a dash"
        );
        assert!(
            pattern.contains((227.0, 145.0)),
            "right must close with a dash"
        );
    }

    #[test]
    fn sides_keep_their_own_gaps() {
        let pattern = dashed_pattern();
        // 235 stretches to 4 dashes of 33.57: the first gap is 33.57..67.14.
        assert!(!pattern.contains((50.0, 8.0)), "top gap was filled");
        // 175 stretches to 3 dashes of 35: the first gap is 35..70.
        assert!(!pattern.contains((227.0, 50.0)), "right gap was filled");
    }
}
