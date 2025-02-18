use skia_safe::{self as skia, Rect};

use super::Color;
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

    pub fn from_bytes(bytes: [u8; 5]) -> Self {
        Self {
            color: [bytes[0], bytes[1], bytes[2], bytes[3]],
            offset: bytes[4],
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
    width: f32,
}

impl Gradient {
    pub fn add_stop(&mut self, color: Color, offset: f32) {
        self.colors.push(color);
        self.offsets.push(offset);
    }

    fn to_linear_shader(&self, rect: &Rect) -> Option<skia::Shader> {
        let start = (
            rect.left + self.start.0 * rect.width(),
            rect.top + self.start.1 * rect.height(),
        );
        let end = (
            rect.left + self.end.0 * rect.width(),
            rect.top + self.end.1 * rect.height(),
        );
        skia::shader::Shader::linear_gradient(
            (start, end),
            self.colors.as_slice(),
            self.offsets.as_slice(),
            skia::TileMode::Clamp,
            None,
            None,
        )
    }

    fn to_radial_shader(&self, rect: &Rect) -> Option<skia::Shader> {
        let center = skia::Point::new(
            rect.left + self.start.0 * rect.width(),
            rect.top + self.start.1 * rect.height(),
        );
        let end = skia::Point::new(
            rect.left + self.end.0 * rect.width(),
            rect.top + self.end.1 * rect.height(),
        );

        let direction = end - center;
        let distance = (direction.x.powi(2) + direction.y.powi(2)).sqrt();
        let angle = direction.y.atan2(direction.x).to_degrees();

        // Based on the code from frontend/src/app/main/ui/shapes/gradients.cljs
        let mut transform = skia::Matrix::new_identity();
        transform.pre_translate((center.x, center.y));
        transform.pre_rotate(angle + 90., skia::Point::new(0., 0.));
        // We need an extra transform, because in skia radial gradients are circular and we need them to be ellipses if they must adapt to the shape
        transform.pre_scale((self.width * rect.width() / rect.height(), 1.), None);
        transform.pre_translate((-center.x, -center.y));

        skia::shader::Shader::radial_gradient(
            center,
            distance,
            self.colors.as_slice(),
            self.offsets.as_slice(),
            skia::TileMode::Clamp,
            None,
            Some(&transform),
        )
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct ImageFill {
    id: Uuid,
    opacity: u8,
    height: i32,
    width: i32,
}

impl ImageFill {
    pub fn size(&self) -> (i32, i32) {
        (self.width, self.height)
    }

    pub fn id(&self) -> Uuid {
        self.id
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum Fill {
    Solid(Color),
    LinearGradient(Gradient),
    RadialGradient(Gradient),
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
            width: 0.,
        })
    }
    pub fn new_radial_gradient(
        start: (f32, f32),
        end: (f32, f32),
        opacity: f32,
        width: f32,
    ) -> Self {
        Self::RadialGradient(Gradient {
            start,
            end,
            opacity,
            colors: vec![],
            offsets: vec![],
            width,
        })
    }

    pub fn new_image_fill(id: Uuid, opacity: u8, (width, height): (i32, i32)) -> Self {
        Self::Image(ImageFill {
            id,
            opacity,
            height,
            width,
        })
    }

    pub fn to_paint(&self, rect: &Rect) -> skia::Paint {
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
                p.set_shader(gradient.to_linear_shader(&rect));
                p.set_alpha((gradient.opacity * 255.) as u8);
                p.set_style(skia::PaintStyle::Fill);
                p.set_anti_alias(true);
                p.set_blend_mode(skia::BlendMode::SrcOver);
                p
            }
            Self::RadialGradient(gradient) => {
                let mut p = skia::Paint::default();
                p.set_shader(gradient.to_radial_shader(&rect));
                p.set_alpha((gradient.opacity * 255.) as u8);
                p.set_style(skia::PaintStyle::Fill);
                p.set_anti_alias(true);
                p.set_blend_mode(skia::BlendMode::SrcOver);
                p
            }
            Self::Image(image_fill) => {
                let mut p = skia::Paint::default();
                p.set_style(skia::PaintStyle::Fill);
                p.set_anti_alias(true);
                p.set_blend_mode(skia::BlendMode::SrcOver);
                p.set_alpha(image_fill.opacity);
                p
            }
        }
    }
}
