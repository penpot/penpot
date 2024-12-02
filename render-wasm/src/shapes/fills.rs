use skia_safe as skia;

use super::Color;
use crate::math;
use uuid::Uuid;

#[derive(Debug)]
#[repr(C)]
pub struct RawStopData {
    color: [u8; 4],
    offset: u8,
}

impl RawStopData {
    pub fn color(&self) -> skia::Color {
        skia::Color::from_argb(self.color[3], self.color[0], self.color[1], self.color[2])
    }

    pub fn offset(&self) -> f32 {
        self.offset as f32 / 100.0
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
pub struct ImageFill {
    pub id: Uuid,
    pub alpha: u8,
    pub height: f32,
    pub width: f32,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Fill {
    Solid(Color),
    LinearGradient(Gradient),
    Image(ImageFill),
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

    pub fn new_image_fill(id: Uuid, alpha: u8, height: f32, width: f32) -> Self {
        Self::Image(ImageFill {
            id,
            alpha,
            height,
            width,
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
            Self::Image(image_fill) => {
                let mut p = skia::Paint::default();
                p.set_style(skia::PaintStyle::Fill);
                p.set_anti_alias(true);
                p.set_blend_mode(skia::BlendMode::SrcOver);
                p.set_alpha(image_fill.alpha);
                p
            }
        }
    }
}
