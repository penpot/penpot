use super::Matrix;
use crate::render::{RenderState, SurfaceId};
use crate::shapes::{BoolType, Path, Segment, Shape, StructureEntry, ToPath, Type};
use crate::state::ShapesPool;
use crate::uuid::Uuid;
use bezier_rs::{Bezier, BezierHandles, ProjectionOptions, TValue};
use glam::DVec2;
use indexmap::IndexSet;
use skia_safe as skia;
use std::cmp::Ordering;
use std::collections::{BTreeMap, HashMap};

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
                let s = Bezier::from_linear_coordinates(x1, y1, x2, y2);
                prev = Some((x2, y2));
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

#[derive(Debug, Clone)]
struct BezierStart(BezierSource, DVec2);

impl PartialEq for BezierStart {
    fn eq(&self, other: &Self) -> bool {
        let x1 = self.1.x as f32;
        let y1 = self.1.y as f32;
        let x2 = other.1.x as f32;
        let y2 = other.1.y as f32;

        if self.0 == other.0 {
            (x1 - x2).abs() <= INTERSECT_THRESHOLD_SAME
                && (y1 - y2).abs() <= INTERSECT_THRESHOLD_SAME
        } else {
            (x1 - x2).abs() <= INTERSECT_THRESHOLD_DIFFERENT
                && (y1 - y2).abs() <= INTERSECT_THRESHOLD_DIFFERENT
        }
    }
}

impl Eq for BezierStart {}

impl PartialOrd for BezierStart {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

impl Ord for BezierStart {
    fn cmp(&self, other: &Self) -> Ordering {
        let x1 = self.1.x as f32;
        let y1 = self.1.y as f32;
        let x2 = other.1.x as f32;
        let y2 = other.1.y as f32;

        let (equal_x, equal_y) = if self.0 == other.0 {
            (
                (x1 - x2).abs() <= INTERSECT_THRESHOLD_SAME,
                (y1 - y2).abs() <= INTERSECT_THRESHOLD_SAME,
            )
        } else {
            (
                (x1 - x2).abs() <= INTERSECT_THRESHOLD_DIFFERENT,
                (y1 - y2).abs() <= INTERSECT_THRESHOLD_DIFFERENT,
            )
        };

        if equal_x && equal_y {
            Ordering::Equal
        } else if equal_x && y1 > y2 || !equal_x && x1 > x2 {
            Ordering::Greater
        } else {
            Ordering::Less
        }
    }
}

type BM<'a> = BTreeMap<BezierStart, Vec<(BezierSource, Bezier)>>;

fn init_bm(beziers: &[(BezierSource, Bezier)]) -> BM<'_> {
    let mut bm = BM::default();
    for entry @ (source, bezier) in beziers.iter() {
        let value = *entry;
        let key = BezierStart(*source, bezier.start);
        if let Some(v) = bm.get_mut(&key) {
            v.push(value);
        } else {
            bm.insert(key, vec![value]);
        }
    }
    bm
}

fn find_next(tree: &mut BM, key: BezierStart) -> Option<(BezierSource, Bezier)> {
    let val = tree.get_mut(&key)?;
    let first = val.pop()?;

    if val.is_empty() {
        tree.remove(&key);
    }
    Some(first)
}

fn pop_first(tree: &mut BM) -> Option<(BezierSource, Bezier)> {
    let key = tree.keys().take(1).next()?.clone();
    let val = tree.get_mut(&key)?;
    let first = val.pop()?;

    if val.is_empty() {
        tree.remove(&key);
    }
    Some(first)
}

fn push_bezier(result: &mut Vec<Segment>, bezier: &Bezier) {
    match bezier.handles {
        BezierHandles::Linear => {
            result.push(Segment::LineTo((bezier.end.x as f32, bezier.end.y as f32)));
        }
        BezierHandles::Quadratic { handle } => {
            result.push(Segment::CurveTo((
                (handle.x as f32, handle.y as f32),
                (handle.x as f32, handle.y as f32),
                (bezier.end.x as f32, bezier.end.y as f32),
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

    let mut bm = init_bm(beziers);

    while let Some(bezier) = pop_first(&mut bm) {
        result.push(Segment::MoveTo((
            bezier.1.start.x as f32,
            bezier.1.start.y as f32,
        )));
        push_bezier(&mut result, &bezier.1);
        let mut next_p = BezierStart(bezier.0, bezier.1.end);

        loop {
            let Some(next) = find_next(&mut bm, next_p) else {
                break;
            };
            push_bezier(&mut result, &next.1);
            next_p = BezierStart(next.0, next.1.end);
        }
    }
    result
}

pub fn bool_from_shapes(
    bool_type: BoolType,
    children_ids: &IndexSet<Uuid>,
    shapes: &ShapesPool,
    modifiers: &HashMap<Uuid, Matrix>,
    structure: &HashMap<Uuid, Vec<StructureEntry>>,
) -> Path {
    if children_ids.is_empty() {
        return Path::default();
    }

    let Some(child) = shapes.get(&children_ids[children_ids.len() - 1]) else {
        return Path::default();
    };

    let mut current_path = child.to_path(shapes, modifiers, structure);

    for idx in (0..children_ids.len() - 1).rev() {
        let Some(other) = shapes.get(&children_ids[idx]) else {
            continue;
        };
        let other_path = other.to_path(shapes, modifiers, structure);

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

pub fn update_bool_to_path(
    shape: &Shape,
    shapes: &ShapesPool,
    modifiers: &HashMap<Uuid, Matrix>,
    structure: &HashMap<Uuid, Vec<StructureEntry>>,
) -> Shape {
    let mut shape = shape.clone();
    let children_ids = shape.modified_children_ids(structure.get(&shape.id), true);

    let Type::Bool(bool_data) = &mut shape.shape_type else {
        return shape;
    };
    bool_data.path = bool_from_shapes(
        bool_data.bool_type,
        &children_ids,
        shapes,
        modifiers,
        structure,
    );
    shape
}

#[allow(dead_code)]
// Debug utility for boolean shapes
pub fn debug_render_bool_paths(
    render_state: &mut RenderState,
    shape: &Shape,
    shapes: &ShapesPool,
    modifiers: &HashMap<Uuid, Matrix>,
    structure: &HashMap<Uuid, Vec<StructureEntry>>,
) {
    let canvas = render_state.surfaces.canvas(SurfaceId::Strokes);

    let mut shape = shape.clone();

    let children_ids = shape.modified_children_ids(structure.get(&shape.id), true);

    let Type::Bool(bool_data) = &mut shape.shape_type else {
        return;
    };

    if children_ids.is_empty() {
        return;
    }

    let Some(child) = shapes.get(&children_ids[children_ids.len() - 1]) else {
        return;
    };

    let mut current_path = child.to_path(shapes, modifiers, structure);

    for idx in (0..children_ids.len() - 1).rev() {
        let Some(other) = shapes.get(&children_ids[idx]) else {
            continue;
        };
        let other_path = other.to_path(shapes, modifiers, structure);

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

                let mut path = skia::Path::default();
                path.move_to((b.1.start.x as f32, b.1.start.y as f32));

                match b.1.handles {
                    BezierHandles::Linear => {
                        path.line_to((b.1.end.x as f32, b.1.end.y as f32));
                    }
                    BezierHandles::Quadratic { handle } => {
                        path.quad_to(
                            (handle.x as f32, handle.y as f32),
                            (b.1.end.x as f32, b.1.end.y as f32),
                        );
                    }
                    BezierHandles::Cubic {
                        handle_start,
                        handle_end,
                    } => {
                        path.cubic_to(
                            (handle_start.x as f32, handle_start.y as f32),
                            (handle_end.x as f32, handle_end.y as f32),
                            (b.1.end.x as f32, b.1.end.y as f32),
                        );
                    }
                }
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
