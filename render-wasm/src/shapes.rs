use euclid;
use skia_safe as skia;

use crate::render::{render_rect, State};

pub struct WorldSpace;
pub type TransformMatrix = euclid::Transform2D<f32, WorldSpace, WorldSpace>;
pub type Point = euclid::Point2D<f32, WorldSpace>;

#[derive(Debug, Clone, Copy, Default)]
pub struct Rect {
    pub x1: f32,
    pub y1: f32,
    pub x2: f32,
    pub y2: f32,
}

impl Rect {
    fn new(top_left: Point, bottom_right: Point) -> Self {
        Rect {
            x1: top_left.x,
            y1: top_left.y,
            x2: bottom_right.x,
            y2: bottom_right.y,
        }
    }

    #[inline]
    fn top_left(&self) -> Point {
        Point::new(self.x1, self.y1)
    }

    #[inline]
    fn bottom_right(&self) -> Point {
        Point::new(self.x2, self.y2)
    }
}

#[derive(Debug, Clone, Copy)]
pub struct Transform {
    pub a: f32,
    pub b: f32,
    pub c: f32,
    pub d: f32,
    pub e: f32,
    pub f: f32,
}

impl Into<TransformMatrix> for Transform {
    fn into(self) -> TransformMatrix {
        TransformMatrix::new(self.a, self.b, self.c, self.d, self.e, self.f)
    }
}

#[derive(Debug, Clone, Copy)]
pub struct Shape {
    pub selrect: Rect,
    pub transform: Transform,
}

impl Shape {
    pub fn transformed_selrect(&self) -> Rect {
        let matrix: TransformMatrix = self.transform.into();
        let top_left = matrix.transform_point(self.selrect.top_left());
        let bottom_right = matrix.transform_point(self.selrect.bottom_right());

        Rect::new(top_left, bottom_right)
    }
}

pub static mut SHAPES_BUFFER: [Shape; 2048] = [Shape {
    selrect: Rect {
        x1: 0.0,
        y1: 0.0,
        x2: 0.0,
        y2: 0.0,
    },
    transform: Transform {
        a: 0.0,
        b: 0.0,
        c: 0.0,
        d: 0.0,
        e: 0.0,
        f: 0.0,
    },
}; 2048];

pub(crate) fn draw_all(state: &mut State) {
    let shapes;
    unsafe {
        shapes = SHAPES_BUFFER.iter();
    }

    for shape in shapes {
        let selrect = shape.transformed_selrect();
        let r = skia::Rect::new(selrect.x1, selrect.y1, selrect.x2, selrect.y2);

        render_rect(&mut state.surface, r, skia::Color::RED);
    }
}
