use super::Matrix;
use crate::render::{RenderState, SurfaceId};
use crate::shapes::{BoolType, Path, Segment, Shape, StructureEntry, ToPath, Type};
use crate::state::ShapesPoolRef;
use crate::uuid::Uuid;
use bezier_rs::{Bezier, BezierHandles, ProjectionOptions, TValue};
use glam::DVec2;
use skia_safe as skia;
use std::cmp::Ordering;
use std::collections::HashMap;

const INTERSECT_THRESHOLD_SAME: f32 = 0.1;
const INTERSECT_THRESHOLD_DIFFERENT: f32 = 0.5;
const INTERSECT_ERROR: f64 = 0.1;
const INTERSECT_MIN_SEPARATION: f64 = 0.05;

const PROJECT_OPTS: ProjectionOptions = ProjectionOptions {
    lut_size: 20,
    convergence_epsilon: 0.01,
    convergence_limit: 10,
    iteration_limit: 20,
};

fn to_point(v: DVec2) -> skia::Point {
    skia::Point::new(v.x as f32, v.y as f32)
}

pub fn path_to_beziers(path: &Path) -> Vec<Bezier> {
    let mut start: Option<(f64, f64)> = None;
    let mut prev: Option<(f64, f64)> = None;

    path.segments()
        .iter()
        .filter_map(|s| match s {
            Segment::MoveTo((x, y)) => {
                let x = f64::from(*x);
                let y = f64::from(*y);
                prev = Some((x, y));
                start = Some((x, y));
                None
            }
            Segment::LineTo((x2, y2)) => {
                let (x1, y1) = prev?;
                let x2 = f64::from(*x2);
                let y2 = f64::from(*y2);
                let s = Bezier::from_linear_coordinates(x1, y1, x2, y2);
                prev = Some((x2, y2));
                Some(s)
            }
            Segment::CurveTo(((c1x, c1y), (c2x, c2y), (x2, y2))) => {
                let (x1, y1) = prev?;
                let x2 = f64::from(*x2);
                let y2 = f64::from(*y2);
                let c1x = f64::from(*c1x);
                let c1y = f64::from(*c1y);
                let c2x = f64::from(*c2x);
                let c2y = f64::from(*c2y);
                let s = Bezier::from_cubic_coordinates(x1, y1, c1x, c1y, c2x, c2y, x2, y2);
                prev = Some((x2, y2));
                Some(s)
            }
            Segment::Close => {
                let (x1, y1) = prev?;
                let (x2, y2) = start?;
                prev = Some((x2, y2));
                // Skip degenerate zero-length close segment: path already returned
                // to the start point via an explicit LineTo/CurveTo, so adding a
                // zero-length linear bezier here would confuse intersection detection.
                if (x1 - x2).abs() < 1e-6 && (y1 - y2).abs() < 1e-6 {
                    return None;
                }
                let s = Bezier::from_linear_coordinates(x1, y1, x2, y2);
                Some(s)
            }
        })
        .collect()
}

pub fn split_intersections(segment: Bezier, intersections: &[f64]) -> Vec<Bezier> {
    if intersections.is_empty() {
        return vec![segment];
    }

    let mut result = Vec::new();
    // Clamp to the valid parametric range: `intersections()`/`project()` can
    // return values a hair outside [0,1] due to float error, which would make
    // `split` panic on its `(0.0..=1.).contains(&t)` assertion below.
    let mut intersections: Vec<f64> = intersections.iter().map(|t| t.clamp(0.0, 1.0)).collect();
    intersections.sort_by(|a, b| a.partial_cmp(b).unwrap_or(Ordering::Equal));

    let mut prev = 0.0;
    let mut cur_segment = segment;

    for t_i in &intersections {
        // Skip duplicated split points (the same crossing can be reported by
        // two adjacent opposing segments that share an endpoint): re-splitting
        // at (almost) the same t would emit a zero-length sliver segment whose
        // midpoint containment test is unstable in union/difference/intersection.
        if *t_i - prev < 1e-6 {
            continue;
        }
        let denom = 1.0 - prev;
        // Degenerate split (prev already at the segment end); nothing left to cut.
        if denom <= f64::EPSILON {
            continue;
        }
        // Re-normalize the global t into the remaining segment, clamped so float
        // noise / out-of-order duplicates can never push it outside [0,1].
        let rti = ((t_i - prev) / denom).clamp(0.0, 1.0);
        let [s, rest] = cur_segment.split(TValue::Parametric(rti));
        prev = *t_i;
        cur_segment = rest;
        result.push(s);
    }

    result.push(cur_segment);
    result
}

pub fn split_segments(path_a: &Path, path_b: &Path) -> (Vec<Bezier>, Vec<Bezier>) {
    let path_a = path_to_beziers(path_a);
    let path_b = path_to_beziers(path_b);

    let mut intersects_a = Vec::<Vec<f64>>::with_capacity(path_a.len());
    intersects_a.resize_with(path_a.len(), Default::default);

    let mut intersects_b = Vec::<Vec<f64>>::with_capacity(path_b.len());
    intersects_b.resize_with(path_b.len(), Default::default);

    // Broad-phase: precompute a conservative (control-hull) AABB per segment,
    // padded by the intersection tolerance. Two segments can only intersect if
    // their boxes overlap, so we skip the expensive `intersections()` call for
    // the (typically vast) majority of non-overlapping pairs. This turns the
    // O(A*B) inner loop from A*B curve-subdivision solves into A*B cheap box
    // tests plus only the handful of solves that can actually produce a hit.
    let bbox = |b: &Bezier| {
        let [min, max] = b.bounding_box_of_anchors_and_handles();
        [
            DVec2::new(min.x - INTERSECT_ERROR, min.y - INTERSECT_ERROR),
            DVec2::new(max.x + INTERSECT_ERROR, max.y + INTERSECT_ERROR),
        ]
    };
    let boxes_a: Vec<[DVec2; 2]> = path_a.iter().map(bbox).collect();
    let boxes_b: Vec<[DVec2; 2]> = path_b.iter().map(bbox).collect();

    for i in 0..path_a.len() {
        let [amin, amax] = boxes_a[i];
        for j in 0..path_b.len() {
            let [bmin, bmax] = boxes_b[j];
            // AABB overlap test; skip pairs that cannot intersect.
            if amin.x > bmax.x || bmin.x > amax.x || amin.y > bmax.y || bmin.y > amax.y {
                continue;
            }
            let segment_a = path_a[i];
            let segment_b = path_b[j];
            let mut intersections_a = segment_a.intersections(
                &segment_b,
                Some(INTERSECT_ERROR),
                Some(INTERSECT_MIN_SEPARATION),
            );

            // Clamp at the source: float error can report a t just outside
            // [0,1], and every `TValue::Parametric` consumer downstream
            // (`evaluate`, `project`, `split`) asserts `(0.0..=1.).contains(&t)`.
            for t in intersections_a.iter_mut() {
                *t = t.clamp(0.0, 1.0);
            }

            intersects_b[j].extend(intersections_a.iter().map(|t_a| {
                segment_b
                    .project(
                        segment_a.evaluate(TValue::Parametric(*t_a)),
                        Some(PROJECT_OPTS),
                    )
                    .clamp(0.0, 1.0)
            }));

            intersects_a[i].extend(intersections_a);
        }
    }

    let mut result_a = Vec::new();
    for i in 0..path_a.len() {
        let cur_segment = path_a[i];
        result_a.extend(split_intersections(cur_segment, &intersects_a[i]));
    }

    let mut result_b = Vec::new();
    for i in 0..path_b.len() {
        let cur_segment = path_b[i];
        result_b.extend(split_intersections(cur_segment, &intersects_b[i]));
    }
    (result_a, result_b)
}

fn union(
    path_a: &Path,
    segments_a: Vec<Bezier>,
    path_b: &Path,
    segments_b: Vec<Bezier>,
) -> Vec<(BezierSource, Bezier)> {
    let mut result = Vec::new();

    result.extend(
        segments_a
            .iter()
            .filter(|s| !path_b.contains(to_point(s.evaluate(TValue::Parametric(0.5)))))
            .copied()
            .map(|b| (BezierSource::A, b)),
    );

    result.extend(
        segments_b
            .iter()
            .filter(|s| !path_a.contains(to_point(s.evaluate(TValue::Parametric(0.5)))))
            .copied()
            .map(|b| (BezierSource::B, b)),
    );

    result
}

fn intersection(
    path_a: &Path,
    segments_a: Vec<Bezier>,
    path_b: &Path,
    segments_b: Vec<Bezier>,
) -> Vec<(BezierSource, Bezier)> {
    let mut result = Vec::new();

    result.extend(
        segments_a
            .iter()
            .filter(|s| path_b.contains(to_point(s.evaluate(TValue::Parametric(0.5)))))
            .copied()
            .map(|b| (BezierSource::A, b)),
    );

    result.extend(
        segments_b
            .iter()
            .filter(|s| path_a.contains(to_point(s.evaluate(TValue::Parametric(0.5)))))
            .copied()
            .map(|b| (BezierSource::B, b)),
    );

    result
}

fn difference(
    path_a: &Path,
    segments_a: Vec<Bezier>,
    path_b: &Path,
    segments_b: Vec<Bezier>,
) -> Vec<(BezierSource, Bezier)> {
    let mut result = Vec::new();

    result.extend(
        segments_a
            .iter()
            .filter(|s| !path_b.contains(to_point(s.evaluate(TValue::Parametric(0.5)))))
            .copied()
            .map(|b| (BezierSource::A, b)),
    );

    result.extend(
        segments_b
            .iter()
            .filter(|s| path_a.contains(to_point(s.evaluate(TValue::Parametric(0.5)))))
            .copied()
            .map(|b| (BezierSource::B, b)),
    );

    result
}

fn exclusion(segments_a: Vec<Bezier>, segments_b: Vec<Bezier>) -> Vec<(BezierSource, Bezier)> {
    let mut result = Vec::new();
    result.extend(segments_a.iter().copied().map(|b| (BezierSource::A, b)));
    result.extend(segments_b.iter().copied().map(|b| (BezierSource::B, b)));
    result
}

// Mirrors `app.common.types.path.subpath/clockwise?`.
fn is_clockwise(path: &Path) -> bool {
    let mut points: Vec<(f32, f32)> = Vec::new();

    for segment in path.segments().iter() {
        match *segment {
            Segment::MoveTo(p) => {
                if !points.is_empty() {
                    break;
                }
                points.push(p);
            }
            Segment::LineTo(p) => points.push(p),
            Segment::CurveTo((_, _, p)) => points.push(p),
            Segment::Close => break,
        }
    }

    if points.len() < 3 {
        return false;
    }

    let mut signed_area = 0.0f64;
    for i in 0..points.len() {
        let (x1, y1) = points[i];
        let (x2, y2) = points[(i + 1) % points.len()];
        signed_area += f64::from(x1) * f64::from(y2) - f64::from(x2) * f64::from(y1);
    }

    signed_area > 0.0
}

// The kept pieces of B must point the same way round as the kept pieces of A. Not the
// `path.bool/content-bool-pair` rule, which reverses intersection on same winding and
// relies on `subpath/merge-paths` flipping subpaths when it joins them.
fn should_reverse_b(bool_type: BoolType, a_is_clockwise: bool, path_b: &Path) -> bool {
    let same_winding = a_is_clockwise == is_clockwise(path_b);
    match bool_type {
        BoolType::Union | BoolType::Intersection => !same_winding,
        BoolType::Difference | BoolType::Exclusion => same_winding,
    }
}

#[derive(Debug, Clone, PartialEq, Copy)]
enum BezierSource {
    A,
    B,
}

type BezierPool = Vec<Option<(BezierSource, Bezier)>>;

fn init_pool(beziers: &[(BezierSource, Bezier)]) -> BezierPool {
    beziers.iter().copied().map(Some).collect()
}

// Pop the first remaining entry from the pool (arbitrary start for a new subpath).
fn pop_first_from_pool(pool: &mut BezierPool) -> Option<(BezierSource, Bezier)> {
    pool.iter_mut().find_map(|e| e.take())
}

// Same-source candidates get a tighter threshold so we stay on the original path. A
// candidate that joins by its `end` points the wrong way, so we reverse it.
fn find_next_in_pool(
    pool: &mut BezierPool,
    end: DVec2,
    source: BezierSource,
) -> Option<(BezierSource, Bezier)> {
    let mut best: Option<(usize, bool)> = None;
    let mut best_dist_sq = f64::MAX;

    for (i, entry) in pool.iter().enumerate() {
        let Some((src, bezier)) = entry else {
            continue;
        };
        let threshold = if *src == source {
            INTERSECT_THRESHOLD_SAME as f64
        } else {
            INTERSECT_THRESHOLD_DIFFERENT as f64
        };
        for (reversed, point) in [(false, bezier.start), (true, bezier.end)] {
            let dx = point.x - end.x;
            let dy = point.y - end.y;
            let dist_sq = dx * dx + dy * dy;
            if dist_sq <= threshold * threshold && dist_sq < best_dist_sq {
                best_dist_sq = dist_sq;
                best = Some((i, reversed));
            }
        }
    }

    let (idx, reversed) = best?;
    pool[idx]
        .take()
        .map(|(src, bezier)| (src, if reversed { bezier.reverse() } else { bezier }))
}

fn push_bezier(result: &mut Vec<Segment>, bezier: &Bezier) {
    match bezier.handles {
        BezierHandles::Linear => {
            result.push(Segment::LineTo((bezier.end.x as f32, bezier.end.y as f32)));
        }
        BezierHandles::Quadratic { handle } => {
            let s = bezier.start;
            let e = bezier.end;
            let cp1x = s.x + (2.0 / 3.0) * (handle.x - s.x);
            let cp1y = s.y + (2.0 / 3.0) * (handle.y - s.y);
            let cp2x = e.x + (2.0 / 3.0) * (handle.x - e.x);
            let cp2y = e.y + (2.0 / 3.0) * (handle.y - e.y);
            result.push(Segment::CurveTo((
                (cp1x as f32, cp1y as f32),
                (cp2x as f32, cp2y as f32),
                (e.x as f32, e.y as f32),
            )));
        }
        BezierHandles::Cubic {
            handle_start,
            handle_end,
        } => {
            result.push(Segment::CurveTo((
                (handle_start.x as f32, handle_start.y as f32),
                (handle_end.x as f32, handle_end.y as f32),
                (bezier.end.x as f32, bezier.end.y as f32),
            )));
        }
    }
}

fn beziers_to_segments(beziers: &[(BezierSource, Bezier)]) -> Vec<Segment> {
    let mut result = Vec::new();
    let mut pool = init_pool(beziers);

    while let Some((mut cur_src, first_bezier)) = pop_first_from_pool(&mut pool) {
        let start = (first_bezier.start.x as f32, first_bezier.start.y as f32);
        result.push(Segment::MoveTo(start));
        push_bezier(&mut result, &first_bezier);
        let mut last_end = (first_bezier.end.x as f32, first_bezier.end.y as f32);
        let mut cur_end = first_bezier.end;

        loop {
            let Some((next_src, next_bezier)) = find_next_in_pool(&mut pool, cur_end, cur_src)
            else {
                break;
            };
            push_bezier(&mut result, &next_bezier);
            last_end = (next_bezier.end.x as f32, next_bezier.end.y as f32);
            cur_end = next_bezier.end;
            cur_src = next_src;
        }

        // Close the subpath if the last point is close to the start.
        if (last_end.0 - start.0).abs() < INTERSECT_THRESHOLD_SAME
            && (last_end.1 - start.1).abs() < INTERSECT_THRESHOLD_SAME
        {
            // Remove the redundant LineTo that goes back to start, if present.
            if let Some(Segment::LineTo(p)) = result.last() {
                if (p.0 - start.0).abs() < INTERSECT_THRESHOLD_SAME
                    && (p.1 - start.1).abs() < INTERSECT_THRESHOLD_SAME
                {
                    result.pop();
                }
            }
            result.push(Segment::Close);
        }
    }
    result
}

fn bool_beziers(
    bool_type: BoolType,
    path_a: &Path,
    a_is_clockwise: bool,
    path_b: &Path,
) -> (Vec<(BezierSource, Bezier)>, bool) {
    let (segs_a, mut segs_b) = split_segments(path_a, path_b);

    if should_reverse_b(bool_type, a_is_clockwise, path_b) {
        for segment in segs_b.iter_mut() {
            *segment = segment.reverse();
        }
    }

    let beziers = match bool_type {
        BoolType::Union => union(path_a, segs_a, path_b, segs_b),
        BoolType::Difference => difference(path_a, segs_a, path_b, segs_b),
        BoolType::Intersection => intersection(path_a, segs_a, path_b, segs_b),
        BoolType::Exclusion => exclusion(segs_a, segs_b),
    };

    (beziers, path_a.is_even_odd() || path_b.is_even_odd())
}

// Fold `paths` left to right; the first entry is the base operand.
fn bool_fold(bool_type: BoolType, paths: &[Path]) -> Path {
    let Some((first, rest)) = paths.split_first() else {
        return Path::default();
    };

    let mut current_path = first.clone();
    // Every fold step chains A's fragments, which keep their direction, so the
    // accumulated path keeps this winding. Carry it instead of re-reading it from the
    // emitted segment list, whose subpath order and direction fall out of pool ordering.
    let is_clockwise_a = is_clockwise(&current_path);

    for other_path in rest {
        let (beziers, is_even_odd) =
            bool_beziers(bool_type, &current_path, is_clockwise_a, other_path);

        current_path = Path::new(beziers_to_segments(&beziers)).with_even_odd(is_even_odd);
    }

    current_path
}

pub fn bool_from_shapes(bool_type: BoolType, children_ids: &[Uuid], shapes: ShapesPoolRef) -> Path {
    let paths: Vec<Path> = children_ids
        .iter()
        .rev()
        .filter_map(|id| shapes.get(id).map(|child| child.to_path(shapes)))
        .collect();

    bool_fold(bool_type, &paths)
}

pub fn update_bool_to_path(shape: &mut Shape, shapes: ShapesPoolRef) {
    let children_ids = shape.children_ids(true);

    let Type::Bool(bool_data) = &mut shape.shape_type else {
        return;
    };

    bool_data.path = bool_from_shapes(bool_data.bool_type, &children_ids, shapes);
}

// Debug utility for boolean shapes
#[allow(dead_code)]
pub fn debug_render_bool_paths(
    render_state: &mut RenderState,
    shape: &Shape,
    shapes: ShapesPoolRef,
    _modifiers: &HashMap<Uuid, Matrix>,
    _structure: &HashMap<Uuid, Vec<StructureEntry>>,
) {
    let canvas = render_state.surfaces.canvas(SurfaceId::Strokes);

    let mut shape = shape.clone();

    let children_ids = shape.children_ids(true);

    let Type::Bool(bool_data) = &mut shape.shape_type else {
        return;
    };

    if children_ids.is_empty() {
        return;
    }

    let Some(child) = shapes.get(&children_ids[children_ids.len() - 1]) else {
        return;
    };

    let mut current_path = child.to_path(shapes);
    let is_clockwise_a = is_clockwise(&current_path);

    for idx in (0..children_ids.len() - 1).rev() {
        let Some(other) = shapes.get(&children_ids[idx]) else {
            continue;
        };
        let other_path = other.to_path(shapes);

        let (beziers, is_even_odd) = bool_beziers(
            bool_data.bool_type,
            &current_path,
            is_clockwise_a,
            &other_path,
        );
        current_path = Path::new(beziers_to_segments(&beziers)).with_even_odd(is_even_odd);

        if idx == 0 {
            for b in &beziers {
                let mut paint = skia::Paint::default();
                paint.set_color(skia::Color::RED);
                paint.set_alpha_f(1.0);
                paint.set_style(skia::PaintStyle::Stroke);

                let path = {
                    let mut pb = skia::PathBuilder::new();
                    pb.move_to((b.1.start.x as f32, b.1.start.y as f32));
                    match b.1.handles {
                        BezierHandles::Linear => {
                            pb.line_to((b.1.end.x as f32, b.1.end.y as f32));
                        }
                        BezierHandles::Quadratic { handle } => {
                            pb.quad_to(
                                (handle.x as f32, handle.y as f32),
                                (b.1.end.x as f32, b.1.end.y as f32),
                            );
                        }
                        BezierHandles::Cubic {
                            handle_start,
                            handle_end,
                        } => {
                            pb.cubic_to(
                                (handle_start.x as f32, handle_start.y as f32),
                                (handle_end.x as f32, handle_end.y as f32),
                                (b.1.end.x as f32, b.1.end.y as f32),
                            );
                        }
                    }
                    pb.detach()
                };
                canvas.draw_path(&path, &paint);

                let mut v1 = b.1.normal(TValue::Parametric(1.0));
                v1 *= 0.5;
                let v2 = v1.perp();

                let p1 = b.1.end + v1 + v2;
                let p2 = b.1.end - v1 + v2;

                canvas.draw_line(
                    (b.1.end.x as f32, b.1.end.y as f32),
                    (p1.x as f32, p1.y as f32),
                    &paint,
                );

                canvas.draw_line(
                    (b.1.end.x as f32, b.1.end.y as f32),
                    (p2.x as f32, p2.y as f32),
                    &paint,
                );

                let v3 = b.1.normal(TValue::Parametric(0.0));
                let p3 = b.1.start + v3;
                let p4 = b.1.start - v3;

                canvas.draw_line(
                    (b.1.start.x as f32, b.1.start.y as f32),
                    (p3.x as f32, p3.y as f32),
                    &paint,
                );

                canvas.draw_line(
                    (b.1.start.x as f32, b.1.start.y as f32),
                    (p4.x as f32, p4.y as f32),
                    &paint,
                );
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn linear(from: (f64, f64), to: (f64, f64)) -> Bezier {
        Bezier::from_linear_coordinates(from.0, from.1, to.0, to.1)
    }

    fn polygon(points: &[(f32, f32)]) -> Path {
        let mut segments = vec![Segment::MoveTo(points[0])];
        segments.extend(points[1..].iter().map(|p| Segment::LineTo(*p)));
        segments.push(Segment::Close);
        Path::new(segments)
    }

    fn count(segments: &[Segment], f: fn(&Segment) -> bool) -> usize {
        segments.iter().filter(|s| f(s)).count()
    }

    fn is_move_to(s: &Segment) -> bool {
        matches!(s, Segment::MoveTo(_))
    }

    fn is_close(s: &Segment) -> bool {
        matches!(s, Segment::Close)
    }

    fn ring_area(ring: &[(f32, f32)]) -> f64 {
        let mut area = 0.0f64;
        for i in 0..ring.len() {
            let (x1, y1) = ring[i];
            let (x2, y2) = ring[(i + 1) % ring.len()];
            area += f64::from(x1) * f64::from(y2) - f64::from(x2) * f64::from(y1);
        }
        area / 2.0
    }

    fn signed_area(segments: &[Segment]) -> f64 {
        let mut total = 0.0f64;
        let mut ring: Vec<(f32, f32)> = Vec::new();

        for segment in segments {
            match *segment {
                Segment::MoveTo(p) => {
                    total += ring_area(&ring);
                    ring.clear();
                    ring.push(p);
                }
                Segment::LineTo(p) | Segment::CurveTo((_, _, p)) => ring.push(p),
                Segment::Close => {
                    total += ring_area(&ring);
                    ring.clear();
                }
            }
        }

        total + ring_area(&ring)
    }

    // Operands and expected results taken from the CLJS bool (`app.common.types.path.bool`)
    // run on the same shapes: A clockwise, B and C counter-clockwise, all overlapping.
    const A_CW: [(f32, f32); 4] = [
        (100.0, 100.0),
        (300.0, 100.0),
        (300.0, 300.0),
        (100.0, 300.0),
    ];
    const B_CCW: [(f32, f32); 4] = [
        (200.0, 200.0),
        (200.0, 400.0),
        (400.0, 400.0),
        (400.0, 200.0),
    ];
    const C_CCW: [(f32, f32); 4] = [(60.0, 240.0), (60.0, 360.0), (260.0, 360.0), (260.0, 240.0)];

    #[test]
    fn test_is_clockwise() {
        let cw = Path::new(vec![
            Segment::MoveTo((0.0, 0.0)),
            Segment::LineTo((10.0, 0.0)),
            Segment::LineTo((10.0, 10.0)),
            Segment::LineTo((0.0, 10.0)),
            Segment::Close,
        ]);
        assert!(is_clockwise(&cw));

        let ccw = Path::new(vec![
            Segment::MoveTo((0.0, 0.0)),
            Segment::LineTo((0.0, 10.0)),
            Segment::LineTo((10.0, 10.0)),
            Segment::LineTo((10.0, 0.0)),
            Segment::Close,
        ]);
        assert!(!is_clockwise(&ccw));
    }

    #[test]
    fn test_should_reverse_b_only_depends_on_relative_winding() {
        let cw = Path::new(vec![
            Segment::MoveTo((0.0, 0.0)),
            Segment::LineTo((10.0, 0.0)),
            Segment::LineTo((10.0, 10.0)),
            Segment::LineTo((0.0, 10.0)),
            Segment::Close,
        ]);
        let ccw = Path::new(vec![
            Segment::MoveTo((0.0, 0.0)),
            Segment::LineTo((0.0, 10.0)),
            Segment::LineTo((10.0, 10.0)),
            Segment::LineTo((10.0, 0.0)),
            Segment::Close,
        ]);

        assert!(should_reverse_b(BoolType::Difference, true, &cw));
        assert!(!should_reverse_b(BoolType::Difference, true, &ccw));
        assert!(!should_reverse_b(BoolType::Union, true, &cw));
        assert!(should_reverse_b(BoolType::Union, true, &ccw));
    }

    // Fragments from #11482: two point the wrong way, so joining them start-to-start only
    // left five open subpaths.
    #[test]
    fn test_beziers_to_segments_closes_reversed_fragments() {
        let beziers = vec![
            (
                BezierSource::A,
                linear((2764.00, -240.00), (2834.74, -110.71)),
            ),
            (
                BezierSource::A,
                linear((2809.29, -85.26), (2693.26, -201.29)),
            ),
            (
                BezierSource::A,
                linear((2718.71, -226.74), (2764.00, -240.00)),
            ),
            (
                BezierSource::B,
                linear((2718.71, -226.74), (2834.74, -110.71)),
            ),
            (
                BezierSource::B,
                linear((2809.29, -85.26), (2693.26, -201.29)),
            ),
        ];

        let segments = beziers_to_segments(&beziers);

        let moves = segments
            .iter()
            .filter(|s| matches!(s, Segment::MoveTo(_)))
            .count();
        let closes = segments
            .iter()
            .filter(|s| matches!(s, Segment::Close))
            .count();

        assert_eq!(moves, 2);
        assert_eq!(closes, 2);
        // 3 fragments in the first subpath, 2 in the second, each dropping its closing LineTo.
        assert_eq!(segments.len(), 7);
    }

    // #11482: A clockwise, B counter-clockwise. Reference (CLJS):
    // M100,100 L300,100 L300,200 L200,200 L200,300 L100,300 Z
    #[test]
    fn test_difference_with_opposite_winding_operand() {
        let result = bool_fold(BoolType::Difference, &[polygon(&A_CW), polygon(&B_CCW)]);
        let segments = result.segments();

        assert_eq!(count(segments, is_move_to), 1);
        assert_eq!(count(segments, is_close), 1);
        assert!((signed_area(segments) - 30000.0).abs() < 1.0);
        assert!(is_clockwise(&result));
    }

    // Reference (CLJS):
    // M100,100 L300,100 L300,200 L400,200 L400,400 L200,400 L200,300 L100,300 Z
    #[test]
    fn test_union_with_opposite_winding_operand() {
        let result = bool_fold(BoolType::Union, &[polygon(&A_CW), polygon(&B_CCW)]);
        let segments = result.segments();

        assert_eq!(count(segments, is_move_to), 1);
        assert_eq!(count(segments, is_close), 1);
        assert!((signed_area(segments) - 70000.0).abs() < 1.0);
        assert!(is_clockwise(&result));
    }

    // The second fold step must compare against A's winding, not against the winding of
    // the intermediate path, whose subpath order and direction fall out of pool ordering.
    // Reference (CLJS): M100,100 L300,100 L300,200 L200,200 L200,240 L100,240 Z
    #[test]
    fn test_difference_folds_three_opposite_winding_operands() {
        let result = bool_fold(
            BoolType::Difference,
            &[polygon(&A_CW), polygon(&B_CCW), polygon(&C_CCW)],
        );
        let segments = result.segments();

        assert_eq!(count(segments, is_move_to), 1);
        assert_eq!(count(segments, is_close), 1);
        assert!((signed_area(segments) - 24000.0).abs() < 1.0);
        assert!(is_clockwise(&result));
    }
}
