use skia_safe::{self as skia, Matrix};

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
                if destination == start {
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

    pub fn is_open(&self) -> bool {
        self.open
    }

    pub fn transform(&mut self, mtx: &Matrix) {
        self.skia_path.transform(mtx);
    }
}
