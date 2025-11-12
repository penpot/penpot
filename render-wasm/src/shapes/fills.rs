use skia_safe::{self as skia, Paint, Rect};

pub use super::Color;
use crate::utils::get_image;
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
    keep_aspect_ratio: bool,
}

impl ImageFill {
    pub fn new(id: Uuid, opacity: u8, width: i32, height: i32, keep_aspect_ratio: bool) -> Self {
        Self {
            id,
            opacity,
            width,
            height,
            keep_aspect_ratio,
        }
    }

    pub fn id(&self) -> Uuid {
        self.id
    }

    pub fn opacity(&self) -> u8 {
        self.opacity
    }

    pub fn keep_aspect_ratio(&self) -> bool {
        self.keep_aspect_ratio
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

pub fn get_fill_shader(fill: &Fill, bounding_box: &Rect) -> Option<skia::Shader> {
    match fill {
        Fill::Solid(SolidColor(color)) => Some(skia::shaders::color(*color)),
        Fill::LinearGradient(gradient) => gradient.to_linear_shader(bounding_box),
        Fill::RadialGradient(gradient) => gradient.to_radial_shader(bounding_box),
        Fill::Image(image_fill) => {
            let mut image_shader = None;
            let image = get_image(&image_fill.id);
            if let Some(image) = image {
                let sampling_options =
                    skia::SamplingOptions::new(skia::FilterMode::Linear, skia::MipmapMode::Nearest);

                // FIXME no image ratio applied, centered to the current rect
                let tile_modes = (skia::TileMode::Clamp, skia::TileMode::Clamp);
                let image_width = image_fill.width as f32;
                let image_height = image_fill.height as f32;
                let scale_x = bounding_box.width() / image_width;
                let scale_y = bounding_box.height() / image_height;
                let scale = scale_x.max(scale_y);
                let scaled_width = image_width * scale;
                let scaled_height = image_height * scale;
                let pos_x = bounding_box.left() - (scaled_width - bounding_box.width()) / 2.0;
                let pos_y = bounding_box.top() - (scaled_height - bounding_box.height()) / 2.0;

                let mut matrix = skia::Matrix::new_identity();
                matrix.pre_translate((pos_x, pos_y));
                matrix.pre_scale((scale, scale), None);

                let opacity = image_fill.opacity();
                let alpha_color = skia::Color4f::new(1.0, 1.0, 1.0, opacity as f32 / 255.0);
                let alpha_shader = skia::shaders::color(alpha_color.to_color());

                image_shader = image.to_shader(tile_modes, sampling_options, &matrix);
                if let Some(shader) = image_shader {
                    image_shader = Some(skia::shaders::blend(
                        skia::Blender::mode(skia::BlendMode::DstIn),
                        shader,
                        alpha_shader,
                    ));
                }
            }
            image_shader
        }
    }
}

pub fn merge_fills(fills: &[Fill], bounding_box: Rect) -> skia::Paint {
    let mut combined_shader: Option<skia::Shader> = None;
    let mut fills_paint = skia::Paint::default();

    if fills.is_empty() {
        combined_shader = Some(skia::shaders::color(skia::Color::TRANSPARENT));
        fills_paint.set_shader(combined_shader);
        return fills_paint;
    }

    for fill in fills {
        let shader = get_fill_shader(fill, &bounding_box);

        if let Some(shader) = shader {
            combined_shader = match combined_shader {
                Some(existing_shader) => Some(skia::shaders::blend(
                    skia::Blender::mode(skia::BlendMode::DstOver),
                    existing_shader,
                    shader,
                )),
                None => Some(shader),
            };
        }
    }

    fills_paint.set_shader(combined_shader.clone());
    fills_paint
}

pub fn set_paint_fill(paint: &mut Paint, fill: &Fill, bounding_box: &Rect, remove_alpha: bool) {
    if remove_alpha {
        paint.set_color(skia::Color::BLACK);
        paint.set_alpha(255);
        return;
    }
    let shader = get_fill_shader(fill, bounding_box);
    if let Some(shader) = shader {
        paint.set_shader(shader);
    }
}
