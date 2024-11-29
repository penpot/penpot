use skia_safe as skia;

use super::Color;
use crate::math;

#[derive(Debug, Clone, PartialEq)]
pub struct Gradient {
    colors: Vec<Color>,
    offsets: Vec<f32>,
    opacity: f32,
    start: (f32, f32),
    end: (f32, f32),
}

impl Gradient {
    pub fn add_stop(&mut self, color: Color, offset: f32) {
        self.colors.push(color);
        self.offsets.push(offset);
    }

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
