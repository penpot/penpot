use crate::math;
use skia_safe as skia;
use uuid::Uuid;

use crate::render::BlendMode;

mod fills;
mod images;
mod matrix;
mod paths;
mod renderable;
mod svgraw;

pub use fills::*;
pub use images::*;
use matrix::*;
pub use paths::*;
pub use svgraw::*;

#[derive(Debug, Clone, PartialEq)]
pub enum Kind {
    Rect(math::Rect),
    Circle(math::Rect),
    Path(Path),
    SVGRaw(SVGRaw),
}

pub type Color = skia::Color;

#[derive(Debug, Clone)]
#[allow(dead_code)]
pub struct Shape {
    id: Uuid,
    children: Vec<Uuid>,
    kind: Kind,
    selrect: math::Rect,
    transform: Matrix,
    rotation: f32,
    clip_content: bool,
    fills: Vec<Fill>,
    blend_mode: BlendMode,
    opacity: f32,
    hidden: bool,
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

    pub fn set_kind(&mut self, kind: Kind) {
        self.kind = kind;
    }

    pub fn set_clip(&mut self, value: bool) {
        self.clip_content = value;
    }

    pub fn set_rotation(&mut self, angle: f32) {
        self.rotation = angle;
    }

    pub fn set_transform(&mut self, a: f32, b: f32, c: f32, d: f32, e: f32, f: f32) {
        self.transform = Matrix::new(a, b, c, d, e, f);
    }

    pub fn set_opacity(&mut self, opacity: f32) {
        self.opacity = opacity;
    }

    pub fn set_hidden(&mut self, value: bool) {
        self.hidden = value;
    }

    pub fn add_child(&mut self, id: Uuid) {
        self.children.push(id);
    }

    pub fn clear_children(&mut self) {
        self.children.clear();
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

    pub fn set_path_attr(&mut self, name: String, value: String) {
        match &mut self.kind {
            Kind::Path(p) => {
                p.set_attr(name, value);
            },
            Kind::Rect(_) | Kind::Circle(_) | Kind::SVGRaw(_) => todo!()
        };
    }

    pub fn set_svg_raw_content(&mut self, content: String) -> Result<(), String> {
        self.kind = Kind::SVGRaw(SVGRaw::from_content(content));
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
