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

// #[derive(Debug, Clone, Copy)]
// pub struct Transform {
//     pub position: Vec2;
//     pub scale: Vec2;
//     pub skew: Vec2;
//     pub rotation: f32;
//     pub matrix: Matrix;
// }

#[derive(Clone, Copy)]
pub struct ColorChannels {
    r: u8,
    g: u8,
    b: u8,
    a: u8,
}

pub union Color {
    rgba: u32,
    channel: ColorChannels,
}

pub struct ColorStop {
    offset: f32,
    color: Color,
}

pub struct FillColor {
    color: Color,
}

pub struct FillLinearGradient {
    start: Point,
    end: Point,
    stops: Vec<ColorStop>,
}

pub struct FillRadialGradient {
    stops: Vec<ColorStop>,
}

pub struct FillImage {

}

pub enum Fill {
    Color(FillColor),
    LinearGradient(FillLinearGradient)
}

pub enum Stroke {

}

#[derive(Debug, Clone, Copy)]
pub struct Shape {
    pub id: Uuid,
    pub kind: Kind,
    pub selrect: Rect,
    pub transform: Matrix,
    pub rotation: f32,
    // pub fills: Vec<Fill>,
    // pub strokes: Vec<Stroke>,
    // pub pathData: PathData,
    // pub textContent: TextContent,
}

impl Shape {
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
