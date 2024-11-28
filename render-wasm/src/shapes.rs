use crate::math;
use skia_safe as skia;
use uuid::Uuid;

#[derive(Debug, Clone, Copy)]
pub enum Kind {
    Rect,
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
pub struct Gradient {
    colors: Vec<Color>,
    offsets: Vec<f32>,
    opacity: f32,
    start: (f32, f32),
    end: (f32, f32),
}

impl Gradient {
    fn to_shader(&self, rect: &math::Rect) -> skia::Shader {
        let start = (
            rect.left + self.start.0 * rect.width(),
            rect.top + self.start.1 * rect.height(),
        );
        let end = (
            rect.left + self.end.0 * rect.width(),
            rect.top + self.end.1 * rect.height(),
        );
        let shader = skia::shader::Shader::linear_gradient(
            (start, end),
            self.colors.as_slice(),
            self.offsets.as_slice(),
            skia::TileMode::Clamp,
            None,
            None,
        )
        .unwrap();
        shader
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum Fill {
    Solid(Color),
    LinearGradient(Gradient),
}

impl From<Color> for Fill {
    fn from(value: Color) -> Self {
        Self::Solid(value)
    }
}

impl Fill {
    pub fn new_linear_gradient(start: (f32, f32), end: (f32, f32), opacity: f32) -> Self {
        Self::LinearGradient(Gradient {
            start,
            end,
            opacity,
            colors: vec![],
            offsets: vec![],
        })
    }

    pub fn to_paint(&self, rect: &math::Rect) -> skia::Paint {
        match self {
            Self::Solid(color) => {
                let mut p = skia::Paint::default();
                p.set_color(*color);
                p.set_style(skia::PaintStyle::Fill);
                p.set_anti_alias(true);
                p.set_blend_mode(skia::BlendMode::SrcOver);
                p
            }
            Self::LinearGradient(gradient) => {
                let mut p = skia::Paint::default();
                p.set_shader(gradient.to_shader(&rect));
                p.set_alpha((gradient.opacity * 255.) as u8);
                p.set_style(skia::PaintStyle::Fill);
                p.set_blend_mode(skia::BlendMode::SrcOver);
                p
            }
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy)]
pub struct BlendMode(skia::BlendMode);

impl Default for BlendMode {
    fn default() -> Self {
        BlendMode(skia::BlendMode::SrcOver)
    }
}

impl From<i32> for BlendMode {
    fn from(value: i32) -> Self {
        if value <= skia::BlendMode::Luminosity as i32 {
            unsafe { Self(std::mem::transmute(value)) }
        } else {
            Self::default()
        }
    }
}

impl Into<skia::BlendMode> for BlendMode {
    fn into(self) -> skia::BlendMode {
        match self {
            Self(skia_blend) => skia_blend,
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
    fills: Vec<Fill>,
    pub blend_mode: BlendMode,
    pub opacity: f32,
}

impl Shape {
    pub fn new(id: Uuid) -> Self {
        Self {
            id,
            children: Vec::<Uuid>::new(),
            kind: Kind::Rect,
            selrect: math::Rect::new_empty(),
            transform: Matrix::identity(),
            rotation: 0.,
            fills: vec![],
            blend_mode: BlendMode::default(),
            opacity: 1.,
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

    pub fn add_gradient_stop(&mut self, color: skia::Color, offset: f32) -> Result<(), String> {
        let fill = self.fills.last_mut().ok_or("Shape has no fills")?;
        let gradient = match fill {
            Fill::LinearGradient(g) => Ok(g),
            _ => Err("Active fill is not a gradient"),
        }?;

        gradient.colors.push(color);
        gradient.offsets.push(offset);

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
