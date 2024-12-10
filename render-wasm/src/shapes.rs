use crate::math;
use skia_safe as skia;
use uuid::Uuid;

use crate::render::BlendMode;

mod fills;
mod images;
mod paths;
mod renderable;
pub use fills::*;
pub use images::*;
pub use paths::*;

#[derive(Debug, Clone, PartialEq)]
pub enum Kind {
    Rect(math::Rect),
    Circle(math::Rect),
    Path(Path),
}

pub type Color = skia::Color;

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

#[derive(Debug, Clone)]
#[allow(dead_code)]
pub struct Shape {
    pub id: Uuid,
    pub children: Vec<Uuid>,
    pub kind: Kind,
    pub selrect: math::Rect,
    pub transform: Matrix,
    pub rotation: f32,
    pub clip_content: bool,
    fills: Vec<Fill>,
    pub blend_mode: BlendMode,
    pub opacity: f32,
    pub hidden: bool,
}

impl Shape {
    pub fn new(id: Uuid) -> Self {
        Self {
            id,
            children: Vec::<Uuid>::new(),
            kind: Kind::Rect(math::Rect::new_empty()),
            selrect: math::Rect::new_empty(),
            transform: Matrix::identity(),
            rotation: 0.,
            clip_content: true,
            fills: vec![],
            blend_mode: BlendMode::default(),
            opacity: 1.,
            hidden: false,
        }
    }

    pub fn set_selrect(&mut self, left: f32, top: f32, right: f32, bottom: f32) {
        self.selrect.set_ltrb(left, top, right, bottom);
        match self.kind {
            Kind::Rect(_) => {
                self.kind = Kind::Rect(self.selrect.to_owned());
            }
            Kind::Circle(_) => {
                self.kind = Kind::Circle(self.selrect.to_owned());
            }
            _ => {}
        };
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

    pub fn add_gradient_stops(&mut self, buffer: Vec<RawStopData>) -> Result<(), String> {
        let fill = self.fills.last_mut().ok_or("Shape has no fills")?;
        let gradient = match fill {
            Fill::LinearGradient(g) => Ok(g),
            Fill::RadialGradient(g) => Ok(g),
            _ => Err("Active fill is not a gradient"),
        }?;

        for stop in buffer.into_iter() {
            gradient.add_stop(stop.color(), stop.offset());
        }

        Ok(())
    }

    pub fn set_path_segments(&mut self, buffer: Vec<RawPathData>) -> Result<(), String> {
        let p = Path::try_from(buffer)?;
        self.kind = Kind::Path(p);
        Ok(())
    }

    pub fn set_blend_mode(&mut self, mode: BlendMode) {
        self.blend_mode = mode;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn any_shape() -> Shape {
        Shape::new(Uuid::nil())
    }

    #[test]
    fn add_fill_pushes_a_new_fill() {
        let mut shape = any_shape();
        assert_eq!(shape.fills.len(), 0);

        shape.add_fill(Fill::Solid(Color::TRANSPARENT));
        assert_eq!(shape.fills.get(0), Some(&Fill::Solid(Color::TRANSPARENT)))
    }
}
