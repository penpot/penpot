use skia_safe::{self as skia, Matrix};

use crate::math;

mod subpaths;

type Point = (f32, f32);

#[derive(Debug, PartialEq, Copy, Clone)]
pub enum Segment {
    MoveTo(Point),
    LineTo(Point),
    CurveTo((Point, Point, Point)),
    Close,
}

impl Segment {}

#[derive(Debug, Clone, PartialEq)]
pub struct Path {
    segments: Vec<Segment>,
    skia_path: skia::Path,
    open: bool,
}

impl Default for Path {
    fn default() -> Self {
        Self::new(vec![])
    }
}

impl Path {
    pub fn new(segments: Vec<Segment>) -> Self {
        let mut pb = skia::PathBuilder::new();

        // Don't auto-close the Skia path when start ≈ end.
        // SVG treats these as open paths (caps apply at endpoints).
        // Auto-closing changes stroke behavior from caps to joins,
        // producing artifacts at self-intersection points.
        // Only explicit Segment::Close should close the Skia path.
        for segment in segments.iter() {
            match *segment {
                Segment::MoveTo(xy) => {
                    pb.move_to(xy);
                }
                Segment::LineTo(xy) => {
                    pb.line_to(xy);
                }
                Segment::CurveTo((c1, c2, xy)) => {
                    pb.cubic_to(c1, c2, xy);
                }
                Segment::Close => {
                    pb.close();
                }
            }
        }

        let skia_path = pb.detach();
        let open = subpaths::is_open_path(&segments);

        Self {
            segments,
            skia_path,
            open,
        }
    }

    pub fn from_skia_path(path: skia::Path) -> Self {
        let verbs = path.verbs();
        let points = path.points();

        let mut segments = Vec::new();

        let mut current_point = 0;
        for verb in verbs {
            match verb {
                skia::PathVerb::Move => {
                    let p = points[current_point];
                    segments.push(Segment::MoveTo((p.x, p.y)));
                    current_point += 1;
                }
                skia::PathVerb::Line => {
                    let p = points[current_point];
                    segments.push(Segment::LineTo((p.x, p.y)));
                    current_point += 1;
                }
                skia::PathVerb::Quad => {
                    let p1 = points[current_point];
                    let p2 = points[current_point + 1];
                    segments.push(Segment::CurveTo(((p1.x, p1.y), (p1.x, p1.y), (p2.x, p2.y))));
                    current_point += 2;
                }
                skia::PathVerb::Conic => {
                    // TODO: There is no way currently to access the conic weight
                    // to transform this correctly
                    let p1 = points[current_point];
                    let p2 = points[current_point + 1];
                    segments.push(Segment::CurveTo(((p1.x, p1.y), (p1.x, p1.y), (p2.x, p2.y))));
                    current_point += 2;
                }
                skia::PathVerb::Cubic => {
                    let p1 = points[current_point];
                    let p2 = points[current_point + 1];
                    let p3 = points[current_point + 2];
                    segments.push(Segment::CurveTo(((p1.x, p1.y), (p2.x, p2.y), (p3.x, p3.y))));
                    current_point += 3;
                }
                skia::PathVerb::Close => {
                    segments.push(Segment::Close);
                }
            }
        }

        Path::new(segments)
    }

    pub fn to_skia_path(&self) -> skia::Path {
        self.skia_path.snapshot()
    }

    pub fn contains(&self, p: skia::Point) -> bool {
        self.skia_path.contains(p)
    }

    pub fn is_open(&self) -> bool {
        self.open
    }

    pub fn transform(&mut self, mtx: &Matrix) {
        self.segments.iter_mut().for_each(|s| match s {
            Segment::MoveTo(p) => {
                let np = mtx.map_point(skia::Point::new(p.0, p.1));
                p.0 = np.x;
                p.1 = np.y;
            }
            Segment::LineTo(p) => {
                let np = mtx.map_point(skia::Point::new(p.0, p.1));
                p.0 = np.x;
                p.1 = np.y;
            }
            Segment::CurveTo((c1, c2, p)) => {
                let nc1 = mtx.map_point(skia::Point::new(c1.0, c1.1));
                c1.0 = nc1.x;
                c1.1 = nc1.y;

                let nc2 = mtx.map_point(skia::Point::new(c2.0, c2.1));
                c2.0 = nc2.x;
                c2.1 = nc2.y;

                let np = mtx.map_point(skia::Point::new(p.0, p.1));
                p.0 = np.x;
                p.1 = np.y;
            }
            _ => {}
        });

        self.skia_path = self.skia_path.make_transform(mtx);
    }

    pub fn segments(&self) -> &Vec<Segment> {
        &self.segments
    }

    pub fn bounds(&self) -> math::Bounds {
        math::Bounds::from_rect(self.skia_path.bounds())
    }
}
