use uuid::Uuid;

#[derive(Debug, Clone, Copy)]
pub enum ShapeKind {
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

// #[derive(Debug, Clone, Copy)]
// pub struct Transform {
//     pub position: Vec2;
//     pub scale: Vec2;
//     pub skew: Vec2;
//     pub rotation: f32;
//     pub matrix: Matrix;
// }

#[derive(Debug, Clone, Copy)]
pub struct Shape {
    pub id: Uuid,
    pub kind: ShapeKind,
    pub selrect: Rect,
    pub matrix: Matrix,
    // pub fills: Vec<ShapeFill>,
    // pub strokes: Vec<ShapeStroke>,
    // pub pathData: PathData,
    // pub textContent: TextContent,
}

impl Shape {
    #[inline]
    pub fn translation(&self) -> (f32, f32) {
        (self.matrix.e, self.matrix.f)
    }

    #[inline]
    pub fn scale(&self) -> (f32, f32) {
        (self.matrix.a, self.matrix.d)
    }

    #[inline]
    pub fn skew(&self) -> (f32, f32) {
        (self.matrix.c, self.matrix.b)
    }
}
