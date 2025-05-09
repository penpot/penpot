use skia_safe::{self as skia, Rect};

pub use super::Color;
use crate::uuid::Uuid;

#[derive(Debug, Clone, PartialEq)]
pub struct Gradient {
    start: (f32, f32),
    end: (f32, f32),
    opacity: u8,
    width: f32,
    colors: Vec<Color>,
    offsets: Vec<f32>,
}

impl Gradient {
    pub fn new(
        start: (f32, f32),
        end: (f32, f32),
        opacity: u8,
        width: f32,
        stops: &[(Color, f32)],
    ) -> Self {
        let mut gradient = Gradient {
            start,
            end,
            opacity,
            colors: vec![],
            offsets: vec![],
            width,
        };

        gradient.add_stops(stops);
        gradient
    }

    fn add_stops(&mut self, stops: &[(Color, f32)]) {
        let colors = stops.iter().map(|(color, _)| *color);
        let offsets = stops.iter().map(|(_, offset)| *offset);
        self.colors.extend(colors);
        self.offsets.extend(offsets);
    }

    pub fn to_linear_shader(&self, rect: &Rect) -> Option<skia::Shader> {
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

    pub fn to_radial_shader(&self, rect: &Rect) -> Option<skia::Shader> {
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
    width: i32,
    height: i32,
}

impl ImageFill {
    pub fn new(id: Uuid, opacity: u8, width: i32, height: i32) -> Self {
        Self {
            id,
            opacity,
            width,
            height,
        }
    }

    pub fn size(&self) -> (i32, i32) {
        (self.width, self.height)
    }

    pub fn id(&self) -> Uuid {
        self.id
    }

    pub fn opacity(&self) -> u8 {
        self.opacity
    }
}

#[derive(Debug, Clone, PartialEq, Copy)]
pub struct SolidColor(pub Color);

#[derive(Debug, Clone, PartialEq)]
pub enum Fill {
    Solid(SolidColor),
    LinearGradient(Gradient),
    RadialGradient(Gradient),
    Image(ImageFill),
}

impl Fill {
    pub fn to_paint(&self, rect: &Rect, anti_alias: bool) -> skia::Paint {
        match self {
            Self::Solid(SolidColor(color)) => {
                let mut p = skia::Paint::default();
                p.set_color(*color);
                p.set_style(skia::PaintStyle::Fill);
                p.set_anti_alias(anti_alias);
                p.set_blend_mode(skia::BlendMode::SrcOver);
                p
            }
            Self::LinearGradient(gradient) => {
                let mut p = skia::Paint::default();
                p.set_shader(gradient.to_linear_shader(rect));
                p.set_alpha(gradient.opacity);
                p.set_style(skia::PaintStyle::Fill);
                p.set_anti_alias(anti_alias);
                p.set_blend_mode(skia::BlendMode::SrcOver);
                p
            }
            Self::RadialGradient(gradient) => {
                let mut p = skia::Paint::default();
                p.set_shader(gradient.to_radial_shader(rect));
                p.set_alpha(gradient.opacity);
                p.set_style(skia::PaintStyle::Fill);
                p.set_anti_alias(anti_alias);
                p.set_blend_mode(skia::BlendMode::SrcOver);
                p
            }
            Self::Image(image_fill) => {
                let mut p = skia::Paint::default();
                p.set_style(skia::PaintStyle::Fill);
                p.set_anti_alias(anti_alias);
                p.set_blend_mode(skia::BlendMode::SrcOver);
                p.set_alpha(image_fill.opacity);
                p
            }
        }
    }
}
