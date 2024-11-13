use skia_safe as skia;
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

type Color = skia::Color;

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

#[derive(Debug, Clone, PartialEq)]
pub enum Fill {
    Solid(Color), // TODO: add more fills here
}

impl From<Color> for Fill {
    fn from(value: Color) -> Self {
        Self::Solid(value)
    }
}

impl Fill {
    pub fn to_paint(&self) -> skia::Paint {
        match self {
            Self::Solid(color) => {
                let mut p = skia::Paint::default();
                p.set_color(*color);
                p.set_style(skia::PaintStyle::Fill);
                p.set_anti_alias(true);
                // TODO: get proper blend mode. See https://tree.taiga.io/project/penpot/task/9275
                p.set_blend_mode(skia::BlendMode::SrcOver);
                p
            }
        }
    }
}

#[derive(Debug, Clone)]
pub struct Shape {
    pub id: Uuid,
    pub children: Vec::<Uuid>,
    pub kind: Kind,
    pub selrect: Rect,
    pub transform: Matrix,
    pub rotation: f32,
    fills: Vec<Fill>,
}

impl Shape {
    pub fn new(id: Uuid) -> Self {
        Self {
            id,
            children: Vec::<Uuid>::new(),
            kind: Kind::Rect,
            selrect: Rect::default(),
            transform: Matrix::identity(),
            rotation: 0.,
            fills: vec![],
        }
    }

    pub fn translation(&self) -> (f32, f32) {
        (self.transform.e, self.transform.f)
    }

    pub fn scale(&self) -> (f32, f32) {
        (self.transform.a, self.transform.d)
    }

    pub fn skew(&self) -> (f32, f32) {
        (self.transform.c, self.transform.b)
    }

    pub fn fills(&self) -> std::slice::Iter<Fill> {
        self.fills.iter()
    }

    pub fn add_fill(&mut self, f: Fill) {
        self.fills.push(f)
    }

    pub fn clear_fills(&mut self) {
        self.fills.clear();
    }
}
