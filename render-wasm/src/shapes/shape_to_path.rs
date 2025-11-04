use skia_safe::Matrix;

use super::{Corners, Path, Segment, Shape, StructureEntry, Type};
use crate::math;

use crate::shapes::text_paths::TextPaths;
use crate::state::ShapesPool;
use crate::uuid::Uuid;
use std::collections::HashMap;

const BEZIER_CIRCLE_C: f32 = 0.551_915_05;

pub trait ToPath {
    fn to_path(
        &self,
        shapes: &ShapesPool,
        modifiers: &HashMap<Uuid, Matrix>,
        structure: &HashMap<Uuid, Vec<StructureEntry>>,
    ) -> Path;
}

enum CornerType {
    TopLeft,
    TopRight,
    BottomRight,
    BottomLeft,
}

fn make_corner(
    corner_type: CornerType,
    from: (f32, f32),
    to: (f32, f32),
    r: math::Point,
) -> Segment {
    let x = match &corner_type {
        CornerType::TopLeft => from.0,
        CornerType::TopRight => from.0 - r.x,
        CornerType::BottomRight => to.0 - r.x,
        CornerType::BottomLeft => to.0,
    };

    let y = match &corner_type {
        CornerType::TopLeft => from.1 - r.y,
        CornerType::TopRight => from.1,
        CornerType::BottomRight => to.1 - (r.y * 2.0),
        CornerType::BottomLeft => to.1 - r.y,
    };

    let width = r.x * 2.0;
    let height = r.y * 2.0;

    let c = BEZIER_CIRCLE_C;
    let c1x = x + (width / 2.0) * (1.0 - c);
    let c2x = x + (width / 2.0) * (1.0 + c);
    let c1y = y + (height / 2.0) * (1.0 - c);
    let c2y = y + (height / 2.0) * (1.0 + c);

    let h1 = match &corner_type {
        CornerType::TopLeft => (from.0, c1y),
        CornerType::TopRight => (c2x, from.1),
        CornerType::BottomRight => (from.0, c2y),
        CornerType::BottomLeft => (c1x, from.1),
    };

    let h2 = match &corner_type {
        CornerType::TopLeft => (c1x, to.1),
        CornerType::TopRight => (to.0, c1y),
        CornerType::BottomRight => (c2x, to.1),
        CornerType::BottomLeft => (to.0, c2y),
    };

    Segment::CurveTo((h1, h2, to))
}

pub fn rect_segments(shape: &Shape, corners: Option<Corners>) -> Vec<Segment> {
    let sr = shape.selrect;

    let segments = if let Some([r1, r2, r3, r4]) = corners {
        let p1 = (sr.x(), sr.y() + r1.y);
        let p2 = (sr.x() + r1.x, sr.y());
        let p3 = (sr.x() + sr.width() - r2.x, sr.y());
        let p4 = (sr.x() + sr.width(), sr.y() + r2.y);
        let p5 = (sr.x() + sr.width(), sr.y() + sr.height() - r3.y);
        let p6 = (sr.x() + sr.width() - r3.x, sr.y() + sr.height());
        let p7 = (sr.x() + r4.x, sr.y() + sr.height());
        let p8 = (sr.x(), sr.y() + sr.height() - r4.y);

        vec![
            Segment::MoveTo(p1),
            make_corner(CornerType::TopLeft, p1, p2, r1),
            Segment::LineTo(p3),
            make_corner(CornerType::TopRight, p3, p4, r2),
            Segment::LineTo(p5),
            make_corner(CornerType::BottomRight, p5, p6, r3),
            Segment::LineTo(p7),
            make_corner(CornerType::BottomLeft, p7, p8, r4),
            Segment::LineTo(p1),
        ]
    } else {
        let p1 = (sr.x(), sr.y());
        let p2 = (sr.x() + sr.width(), sr.y());
        let p3 = (sr.x() + sr.width(), sr.y() + sr.height());
        let p4 = (sr.x(), sr.y() + sr.height());
        vec![
            Segment::MoveTo(p1),
            Segment::LineTo(p2),
            Segment::LineTo(p3),
            Segment::LineTo(p4),
            Segment::Close,
        ]
    };

    transform_segments(segments, shape)
}

fn transform_point(p: (f32, f32), matrix: &skia_safe::Matrix) -> (f32, f32) {
    let pt = skia_safe::Point::new(p.0, p.1);
    let tp = matrix.map_point(pt);
    (tp.x, tp.y)
}

pub fn circle_segments(shape: &Shape) -> Vec<Segment> {
    let sr = shape.selrect;
    let c = BEZIER_CIRCLE_C;
    let c1x = sr.x() + (sr.width() / 2.0 * (1.0 - c));
    let c2x = sr.x() + (sr.width() / 2.0 * (1.0 + c));
    let c1y = sr.y() + (sr.height() / 2.0 * (1.0 - c));
    let c2y = sr.y() + (sr.height() / 2.0 * (1.0 + c));

    let mx = sr.x() + sr.width() / 2.0;
    let my = sr.y() + sr.height() / 2.0;
    let ex = sr.x() + sr.width();
    let ey = sr.y() + sr.height();

    let p1 = (mx, sr.y());
    let p2 = (ex, my);
    let p3 = (mx, ey);
    let p4 = (sr.x(), my);

    let segments = vec![
        Segment::MoveTo(p1),
        Segment::CurveTo(((c2x, p1.1), (p2.0, c1y), p2)),
        Segment::CurveTo(((p2.0, c2y), (c2x, p3.1), p3)),
        Segment::CurveTo(((c1x, p3.1), (p4.0, c2y), p4)),
        Segment::CurveTo(((p4.0, c1y), (c1x, p1.1), p1)),
    ];

    transform_segments(segments, shape)
}

fn join_paths(path: Path, other: Path) -> Path {
    let mut segments = path.segments().clone();
    segments.extend(other.segments().iter());
    Path::new(segments)
}

fn transform_segments(segments: Vec<Segment>, shape: &Shape) -> Vec<Segment> {
    let mut matrix = shape.transform;
    let center = shape.center();
    matrix.post_translate(center);
    matrix.pre_translate(-center);

    if !matrix.is_identity() {
        segments
            .into_iter()
            .map(|seg| match seg {
                Segment::MoveTo(p) => Segment::MoveTo(transform_point(p, &matrix)),
                Segment::LineTo(p) => Segment::LineTo(transform_point(p, &matrix)),
                Segment::CurveTo((h1, h2, p)) => Segment::CurveTo((
                    transform_point(h1, &matrix),
                    transform_point(h2, &matrix),
                    transform_point(p, &matrix),
                )),
                Segment::Close => Segment::Close,
            })
            .collect()
    } else {
        segments
    }
}

impl ToPath for Shape {
    fn to_path(
        &self,
        shapes: &ShapesPool,
        modifiers: &HashMap<Uuid, Matrix>,
        structure: &HashMap<Uuid, Vec<StructureEntry>>,
    ) -> Path {
        let shape = self.transformed(modifiers.get(&self.id));
        match shape.shape_type {
            Type::Frame(ref frame) => {
                let children = shape.modified_children_ids(structure.get(&shape.id), true);
                let mut result = Path::new(rect_segments(&shape, frame.corners));
                for id in children {
                    let Some(shape) = shapes.get(&id) else {
                        continue;
                    };
                    result = join_paths(result, shape.to_path(shapes, modifiers, structure));
                }
                result
            }

            Type::Group(_) => {
                let children = shape.modified_children_ids(structure.get(&shape.id), true);
                let mut result = Path::default();
                for id in children {
                    let Some(shape) = shapes.get(&id) else {
                        continue;
                    };
                    result = join_paths(result, shape.to_path(shapes, modifiers, structure));
                }
                // Force closure of the group path
                let mut segments = result.segments().clone();
                segments.push(Segment::Close);
                Path::new(segments)
            }

            Type::Bool(bool_data) => bool_data.path,

            Type::Rect(ref rect) => Path::new(rect_segments(&shape, rect.corners)),

            Type::Path(path_data) => path_data,

            Type::Circle => Path::new(circle_segments(&shape)),

            Type::SVGRaw(_) => Path::default(),

            Type::Text(ref text) => {
                let text_paths = TextPaths::new(text.clone());
                let mut result = Path::default();
                for (path, _) in text_paths.get_paths(true) {
                    result = join_paths(result, Path::from_skia_path(path));
                }

                Path::new(transform_segments(result.segments().clone(), &shape))
            }
        }
    }
}
