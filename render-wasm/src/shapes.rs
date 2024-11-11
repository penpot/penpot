use uuid::Uuid;

#[derive(Debug, Clone, Copy)]
pub enum Kind {
    None,
    Text,
    Path,
    SVGRaw,
    Image,
    Circle,
    Rect,
    Bool,
    Group,
    Frame,
}

pub struct Point {
    pub x: f32,
    pub y: f32,
}

#[derive(Debug, Clone, Copy, Default)]
pub struct Rect {
    pub x1: f32,
    pub y1: f32,
    pub x2: f32,
    pub y2: f32,
}

#[derive(Debug, Clone, Copy)]
pub struct Matrix {
    pub a: f32,
    pub b: f32,
    pub c: f32,
    pub d: f32,
    pub e: f32,
    pub f: f32,
}

impl Matrix {
    pub fn identity() -> Self {
        Self {
            a: 1.,
            b: 0.,
            c: 0.,
            d: 1.,
            e: 0.,
            f: 0.,
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub struct Shape {
    pub id: Uuid,
    pub kind: Kind,
    pub selrect: Rect,
    pub transform: Matrix,
    pub rotation: f32,
}

impl Shape {
    pub fn new(id: Uuid) -> Self {
        Self {
            id,
            kind: Kind::Rect,
            selrect: Rect::default(),
            transform: Matrix::identity(),
            rotation: 0.,
        }
    }

    #[inline]
    pub fn translation(&self) -> (f32, f32) {
        (self.transform.e, self.transform.f)
    }

    #[inline]
    pub fn scale(&self) -> (f32, f32) {
        (self.transform.a, self.transform.d)
    }

    #[inline]
    pub fn skew(&self) -> (f32, f32) {
        (self.transform.c, self.transform.b)
    }
}
