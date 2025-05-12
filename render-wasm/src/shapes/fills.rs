use skia_safe::{self as skia, Rect};

use super::Color;
use crate::uuid::Uuid;

const MAX_GRADIENT_STOPS: usize = 16;
const BASE_GRADIENT_DATA_SIZE: usize = 28;
const RAW_GRADIENT_DATA_SIZE: usize =
    BASE_GRADIENT_DATA_SIZE + RAW_STOP_DATA_SIZE * MAX_GRADIENT_STOPS;

#[derive(Debug)]
#[repr(C)]
pub struct RawGradientData {
    start_x: f32,
    start_y: f32,
    end_x: f32,
    end_y: f32,
    opacity: f32,
    width: f32,
    stop_count: u32,
    stops: [RawStopData; MAX_GRADIENT_STOPS],
}

impl From<[u8; RAW_GRADIENT_DATA_SIZE]> for RawGradientData {
    fn from(bytes: [u8; RAW_GRADIENT_DATA_SIZE]) -> Self {
        Self {
            start_x: f32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]),
            start_y: f32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]),
            end_x: f32::from_le_bytes([bytes[8], bytes[9], bytes[10], bytes[11]]),
            end_y: f32::from_le_bytes([bytes[12], bytes[13], bytes[14], bytes[15]]),
            opacity: f32::from_le_bytes([bytes[16], bytes[17], bytes[18], bytes[19]]),
            width: f32::from_le_bytes([bytes[20], bytes[21], bytes[22], bytes[23]]),
            stop_count: u32::from_le_bytes([bytes[24], bytes[25], bytes[26], bytes[27]]),
            // FIXME: 2025-04-22: use `array_chunks` once the next release is out
            //        and we update our devenv.
            // See https://github.com/rust-lang/rust/issues/74985
            stops: bytes[28..]
                .chunks_exact(RAW_STOP_DATA_SIZE)
                .map(|chunk| RawStopData::try_from(chunk).unwrap())
                .collect::<Vec<_>>()
                .try_into()
                .unwrap(),
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

// FIXME: We won't need this once we use `array_chunks`. See comment above.
impl TryFrom<&[u8]> for RawStopData {
    type Error = String;

    fn try_from(bytes: &[u8]) -> Result<Self, Self::Error> {
        let data: [u8; RAW_STOP_DATA_SIZE] = bytes
            .try_into()
            .map_err(|_| "Invalid stop data".to_string())?;
        Ok(RawStopData::from(data))
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
    fn add_stops(&mut self, stops: &[(Color, f32)]) {
        let colors = stops.iter().map(|(color, _)| *color);
        let offsets = stops.iter().map(|(_, offset)| *offset);
        self.colors.extend(colors);
        self.offsets.extend(offsets);
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

impl From<RawGradientData> for Gradient {
    fn from(raw_gradient: RawGradientData) -> Self {
        let stops = raw_gradient
            .stops
            .iter()
            .take(raw_gradient.stop_count as usize)
            .map(|stop| (stop.color(), stop.offset()))
            .collect::<Vec<_>>();

        let mut gradient = Gradient {
            start: raw_gradient.start(),
            end: raw_gradient.end(),
            opacity: raw_gradient.opacity(),
            colors: vec![],
            offsets: vec![],
            width: raw_gradient.width(),
        };

        gradient.add_stops(&stops);

        gradient
    }
}

impl TryFrom<&[u8]> for Gradient {
    type Error = String;

    fn try_from(bytes: &[u8]) -> Result<Self, Self::Error> {
        let raw_gradient_bytes: [u8; RAW_GRADIENT_DATA_SIZE] = bytes[0..RAW_GRADIENT_DATA_SIZE]
            .try_into()
            .map_err(|_| "Invalid gradient data".to_string())?;
        let gradient = RawGradientData::from(raw_gradient_bytes).into();

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
                p.set_shader(gradient.to_linear_shader(rect));
                p.set_alpha((gradient.opacity * 255.) as u8);
                p.set_style(skia::PaintStyle::Fill);
                p.set_anti_alias(anti_alias);
                p.set_blend_mode(skia::BlendMode::SrcOver);
                p
            }
            Self::RadialGradient(gradient) => {
                let mut p = skia::Paint::default();
                p.set_shader(gradient.to_radial_shader(rect));
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
