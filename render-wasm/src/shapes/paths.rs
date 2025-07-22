use skia_safe::{self as skia, Matrix};

use crate::math;

type Point = (f32, f32);

#[derive(Debug, PartialEq, Copy, Clone)]
pub enum Segment {
    MoveTo(Point),
    LineTo(Point),
    CurveTo((Point, Point, Point)),
    Close,
}

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
        let mut open = true;
        let mut skia_path = skia::Path::new();
        let mut start = None;

        for segment in segments.iter() {
            let destination = match *segment {
                Segment::MoveTo(xy) => {
                    start = Some(xy);
                    skia_path.move_to(xy);
                    None
                }
                Segment::LineTo(xy) => {
                    skia_path.line_to(xy);
                    Some(xy)
                }
                Segment::CurveTo((c1, c2, xy)) => {
                    skia_path.cubic_to(c1, c2, xy);
                    Some(xy)
                }
                Segment::Close => {
                    skia_path.close();
                    open = false;
                    None
                }
            };

            if let (Some(start), Some(destination)) = (start, destination) {
                if math::is_close_to(destination.0, start.0)
                    && math::is_close_to(destination.1, start.1)
                {
                    skia_path.close();
                    open = false;
                }
            }
        }

        Self {
            segments,
            skia_path,
            open,
        }
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

        self.skia_path.transform(mtx);
    }

    pub fn segments(&self) -> &Vec<Segment> {
        &self.segments
    }
}
