use skia_safe::{self as skia, Rect};

use super::Color;
use crate::uuid::Uuid;

pub const RAW_FILL_DATA_SIZE: usize = 24;

#[derive(Debug)]
#[repr(C)]
pub struct RawGradientData {
    start_x: f32,
    start_y: f32,
    end_x: f32,
    end_y: f32,
    opacity: f32,
    width: f32,
}

impl From<[u8; RAW_FILL_DATA_SIZE]> for RawGradientData {
    fn from(bytes: [u8; RAW_FILL_DATA_SIZE]) -> Self {
        Self {
            start_x: f32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]),
            start_y: f32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]),
            end_x: f32::from_le_bytes([bytes[8], bytes[9], bytes[10], bytes[11]]),
            end_y: f32::from_le_bytes([bytes[12], bytes[13], bytes[14], bytes[15]]),
            opacity: f32::from_le_bytes([bytes[16], bytes[17], bytes[18], bytes[19]]),
            width: f32::from_le_bytes([bytes[20], bytes[21], bytes[22], bytes[23]]),
        }
    }
}

impl RawGradientData {
    pub fn start(&self) -> (f32, f32) {
        (self.start_x, self.start_y)
    }

    pub fn end(&self) -> (f32, f32) {
        (self.end_x, self.end_y)
    }

    pub fn opacity(&self) -> f32 {
        self.opacity
    }

    pub fn width(&self) -> f32 {
        self.width
    }
}

pub const RAW_STOP_DATA_SIZE: usize = 8;

#[derive(Debug)]
#[repr(C)]
pub struct RawStopData {
    color: u32,
    offset: f32,
}

impl RawStopData {
    pub fn color(&self) -> skia::Color {
        skia::Color::from(self.color)
    }

    pub fn offset(&self) -> f32 {
        self.offset
    }
}

impl From<[u8; RAW_STOP_DATA_SIZE]> for RawStopData {
    fn from(bytes: [u8; RAW_STOP_DATA_SIZE]) -> Self {
        Self {
            color: u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]),
            offset: f32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]),
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

impl TryFrom<&[u8]> for Gradient {
    type Error = String;

    fn try_from(bytes: &[u8]) -> Result<Self, Self::Error> {
        let raw_gradient_bytes: [u8; RAW_FILL_DATA_SIZE] = bytes[0..RAW_FILL_DATA_SIZE]
            .try_into()
            .map_err(|_| "Invalid gradient data".to_string())?;

        let raw_gradient = RawGradientData::from(raw_gradient_bytes);
        let stops: Vec<RawStopData> = bytes[RAW_FILL_DATA_SIZE..]
            .chunks(RAW_STOP_DATA_SIZE)
            .map(|chunk| {
                let data: [u8; RAW_STOP_DATA_SIZE] = chunk
                    .try_into()
                    .map_err(|_| "Invalid stop data".to_string())?;
                Ok(RawStopData::from(data))
            })
            .collect::<Result<Vec<_>, Self::Error>>()?;

        let mut gradient = Gradient {
            start: raw_gradient.start(),
            end: raw_gradient.end(),
            opacity: raw_gradient.opacity(),
            colors: vec![],
            offsets: vec![],
            width: raw_gradient.width(),
        };

        for stop in stops {
            gradient.add_stop(stop.color(), stop.offset());
        }

        Ok(gradient)
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
    pub fn new_image_fill(id: Uuid, opacity: u8, (width, height): (i32, i32)) -> Self {
        Self::Image(ImageFill {
            id,
            opacity,
            height,
            width,
        })
    }

    pub fn to_paint(&self, rect: &Rect, anti_alias: bool) -> skia::Paint {
        match self {
            Self::Solid(color) => {
                let mut p = skia::Paint::default();
                p.set_color(*color);
                p.set_style(skia::PaintStyle::Fill);
                p.set_anti_alias(anti_alias);
                p.set_blend_mode(skia::BlendMode::SrcOver);
                p
            }
            Self::LinearGradient(gradient) => {
                let mut p = skia::Paint::default();
                p.set_shader(gradient.to_linear_shader(&rect));
                p.set_alpha((gradient.opacity * 255.) as u8);
                p.set_style(skia::PaintStyle::Fill);
                p.set_anti_alias(anti_alias);
                p.set_blend_mode(skia::BlendMode::SrcOver);
                p
            }
            Self::RadialGradient(gradient) => {
                let mut p = skia::Paint::default();
                p.set_shader(gradient.to_radial_shader(&rect));
                p.set_alpha((gradient.opacity * 255.) as u8);
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
