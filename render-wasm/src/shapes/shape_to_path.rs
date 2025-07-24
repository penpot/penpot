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

    if let Some([r1, r2, r3, r4]) = corners {
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
        vec![
            Segment::MoveTo((sr.x(), sr.y())),
            Segment::LineTo((sr.x() + sr.width(), sr.y())),
            Segment::LineTo((sr.x() + sr.width(), sr.y() + sr.height())),
            Segment::LineTo((sr.x(), sr.y() + sr.height())),
            Segment::Close,
        ]
    }
}

pub fn circle_segments(shape: &Shape) -> Vec<Segment> {
    let sr = shape.selrect;
    let mx = sr.x() + sr.width() / 2.0;
    let my = sr.y() + sr.height() / 2.0;
    let ex = sr.x() + sr.width();
    let ey = sr.y() + sr.height();

    let c = BEZIER_CIRCLE_C;
    let c1x = sr.x() + (sr.width() / 2.0 * (1.0 - c));
    let c2x = sr.x() + (sr.width() / 2.0 * (1.0 + c));
    let c1y = sr.y() + (sr.height() / 2.0 * (1.0 - c));
    let c2y = sr.y() + (sr.height() / 2.0 * (1.0 + c));

    let p1x = mx;
    let p1y = sr.y();
    let p2x = ex;
    let p2y = my;
    let p3x = mx;
    let p3y = ey;
    let p4x = sr.x();
    let p4y = my;

    vec![
        Segment::MoveTo((p1x, p1y)),
        Segment::CurveTo(((c2x, p1y), (p2x, c1y), (p2x, p2y))),
        Segment::CurveTo(((p2x, c2y), (c2x, p3y), (p3x, p3y))),
        Segment::CurveTo(((c1x, p3y), (p4x, c2y), (p4x, p4y))),
        Segment::CurveTo(((p4x, c1y), (c1x, p1y), (p1x, p1y))),
    ]
}

fn join_paths(path: Path, other: Path) -> Path {
    let mut segments = path.segments().clone();
    segments.extend(other.segments().iter());
    Path::new(segments)
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
                result
            }

            Type::Bool(bool_data) => bool_data.path,

            Type::Rect(ref rect) => Path::new(rect_segments(&shape, rect.corners)),

            Type::Path(path_data) => path_data,

            Type::Circle => Path::new(circle_segments(&shape)),

            Type::SVGRaw(_) => Path::default(),

            Type::Text(text) => {
                let text_paths = TextPaths::new(text);
                let mut result = Path::default();
                for (path, _) in text_paths.get_paths(true) {
                    result = join_paths(result, Path::from_skia_path(path));
                }
                result
            }
        }
    }
}
