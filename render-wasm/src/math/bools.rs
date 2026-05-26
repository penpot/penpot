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
    let mut intersections = intersections.to_owned();
    intersections.sort_by(|a, b| a.partial_cmp(b).unwrap_or(Ordering::Equal));

    let mut prev = 0.0;
    let mut cur_segment = segment;

    for t_i in &intersections {
        let rti = (t_i - prev) / (1.0 - prev);
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

    for i in 0..path_a.len() {
        for j in 0..path_b.len() {
            let segment_a = path_a[i];
            let segment_b = path_b[j];
            let intersections_a = segment_a.intersections(
                &segment_b,
                Some(INTERSECT_ERROR),
                Some(INTERSECT_MIN_SEPARATION),
            );

            intersects_b[j].extend(intersections_a.iter().map(|t_a| {
                segment_b.project(
                    segment_a.evaluate(TValue::Parametric(*t_a)),
                    Some(PROJECT_OPTS),
                )
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
            .map(|s| s.reverse())
            .map(|b| (BezierSource::B, b)),
    );

    result
}

fn exclusion(segments_a: Vec<Bezier>, segments_b: Vec<Bezier>) -> Vec<(BezierSource, Bezier)> {
    let mut result = Vec::new();
    result.extend(segments_a.iter().copied().map(|b| (BezierSource::A, b)));
    result.extend(
        segments_b
            .iter()
            .copied()
            .map(|s| s.reverse())
            .map(|b| (BezierSource::B, b)),
    );
    result
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

// Find and remove the segment whose start point is closest to `end` within the
// appropriate threshold. Same-source segments use a tight threshold
// (INTERSECT_THRESHOLD_SAMEd) so we prefer staying on the same original path;
// cross-source segments use a wider threshold (INTERSECT_THRESHOLD_DIFFERENT)
// to allow switching paths at intersection points.
fn find_next_in_pool(
    pool: &mut BezierPool,
    end: DVec2,
    source: BezierSource,
) -> Option<(BezierSource, Bezier)> {
    let mut best_idx: Option<usize> = None;
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
        let dx = bezier.start.x - end.x;
        let dy = bezier.start.y - end.y;
        let dist_sq = dx * dx + dy * dy;
        if dist_sq <= threshold * threshold && dist_sq < best_dist_sq {
            best_dist_sq = dist_sq;
            best_idx = Some(i);
        }
    }

    best_idx.and_then(|i| pool[i].take())
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

pub fn bool_from_shapes(bool_type: BoolType, children_ids: &[Uuid], shapes: ShapesPoolRef) -> Path {
    if children_ids.is_empty() {
        return Path::default();
    }

    let Some(child) = shapes.get(&children_ids[children_ids.len() - 1]) else {
        return Path::default();
    };

    let mut current_path = child.to_path(shapes);

    for idx in (0..children_ids.len() - 1).rev() {
        let Some(other) = shapes.get(&children_ids[idx]) else {
            continue;
        };
        let other_path = other.to_path(shapes);

        let (segs_a, segs_b) = split_segments(&current_path, &other_path);

        let beziers = match bool_type {
            BoolType::Union => union(&current_path, segs_a, &other_path, segs_b),
            BoolType::Difference => difference(&current_path, segs_a, &other_path, segs_b),
            BoolType::Intersection => intersection(&current_path, segs_a, &other_path, segs_b),
            BoolType::Exclusion => exclusion(segs_a, segs_b),
        };

        current_path = Path::new(beziers_to_segments(&beziers));
    }

    current_path
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

    for idx in (0..children_ids.len() - 1).rev() {
        let Some(other) = shapes.get(&children_ids[idx]) else {
            continue;
        };
        let other_path = other.to_path(shapes);

        let (segs_a, segs_b) = split_segments(&current_path, &other_path);

        let beziers = match bool_data.bool_type {
            BoolType::Union => union(&current_path, segs_a, &other_path, segs_b),
            BoolType::Difference => difference(&current_path, segs_a, &other_path, segs_b),
            BoolType::Intersection => intersection(&current_path, segs_a, &other_path, segs_b),
            BoolType::Exclusion => exclusion(segs_a, segs_b),
        };
        current_path = Path::new(beziers_to_segments(&beziers));

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
