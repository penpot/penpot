use skia_safe::{self as skia, Paint, Rect};

pub use super::Color;
use crate::utils::get_image;
use crate::uuid::Uuid;

fn lerp_color(a: Color, b: Color, t: f32) -> Color {
    let r = (a.r() as f32 + (b.r() as f32 - a.r() as f32) * t).round() as u8;
    let g = (a.g() as f32 + (b.g() as f32 - a.g() as f32) * t).round() as u8;
    let bl = (a.b() as f32 + (b.b() as f32 - a.b() as f32) * t).round() as u8;
    let alpha = (a.a() as f32 + (b.a() as f32 - a.a() as f32) * t).round() as u8;
    Color::from_argb(alpha, r, g, bl)
}

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

    /// Build wrapped stops for angular gradients so the sweep transitions
    /// smoothly through the 0/1 seam instead of showing a hard color boundary.
    /// Inserts an interpolated color at offsets 0.0 and 1.0 so the seam is invisible.
    fn angular_wrapped_stops(&self) -> (Vec<Color>, Vec<f32>) {
        if self.colors.len() < 2 {
            return (self.colors.clone(), self.offsets.clone());
        }

        const EPSILON: f32 = 1e-6;

        let first_color = self.colors[0];
        let first_offset = self.offsets[0];
        let last_color = *self.colors.last().unwrap();
        let last_offset = *self.offsets.last().unwrap();

        let needs_start = first_offset > EPSILON;
        let needs_end = last_offset < 1.0 - EPSILON;

        if !needs_start && !needs_end {
            return (self.colors.clone(), self.offsets.clone());
        }

        // Gap is the angular region between last stop and first stop through the 0/1 boundary
        let gap = if needs_start && needs_end {
            (1.0 - last_offset) + first_offset
        } else if needs_start {
            first_offset
        } else {
            1.0 - last_offset
        };

        let mut colors = Vec::with_capacity(self.colors.len() + 2);
        let mut offsets = Vec::with_capacity(self.offsets.len() + 2);

        if needs_start {
            let seam_color = if needs_end && gap > EPSILON {
                // Interpolate between last and first colors proportional to the gap
                let t = (1.0 - last_offset) / gap;
                lerp_color(last_color, first_color, t)
            } else {
                // Last stop is already at 1.0, wrap from last color
                last_color
            };
            colors.push(seam_color);
            offsets.push(0.0);
        }

        colors.extend_from_slice(&self.colors);
        offsets.extend_from_slice(&self.offsets);

        if needs_end {
            let seam_color = if needs_start && gap > EPSILON {
                let t = (1.0 - last_offset) / gap;
                lerp_color(last_color, first_color, t)
            } else {
                // First stop is already at 0.0, wrap to first color
                first_color
            };
            colors.push(seam_color);
            offsets.push(1.0);
        }

        (colors, offsets)
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
        skia::gradient_shader::linear(
            (start, end),
            self.colors.as_slice(),
            Some(self.offsets.as_slice()),
            skia::TileMode::Clamp,
            None,
            None,
        )
    }

    pub fn to_radial_shader(&self, rect: &Rect) -> Option<skia::Shader> {
        // Center and radius use normalized 0–1 coords; local matrix maps to `rect` then applies
        // rotate + `width` ellipse scaling around the center (same units as the wire format).
        let dx = self.end.0 - self.start.0;
        let dy = self.end.1 - self.start.1;
        let r_norm_sq = dx * dx + dy * dy;
        if r_norm_sq < 1e-18 {
            return None;
        }
        let r_norm = r_norm_sq.sqrt().max(1e-6);
        let angle = dy.atan2(dx).to_degrees();
        let sx = self.start.0;
        let sy = self.start.1;

        let mut transform = skia::Matrix::new_identity();
        transform.pre_translate((rect.left, rect.top));
        transform.pre_scale((rect.width(), rect.height()), None);
        transform.pre_translate((sx, sy));
        transform.pre_rotate(angle + 90., skia::Point::new(0., 0.));
        transform.pre_scale((self.width, 1.), None);
        transform.pre_translate((-sx, -sy));

        skia::gradient_shader::radial(
            skia::Point::new(sx, sy),
            r_norm,
            self.colors.as_slice(),
            Some(self.offsets.as_slice()),
            skia::TileMode::Clamp,
            None,
            Some(&transform),
        )
    }

    pub fn to_angular_shader(&self, rect: &Rect) -> Option<skia::Shader> {
        let center = skia::Point::new(
            rect.left + self.start.0 * rect.width(),
            rect.top + self.start.1 * rect.height(),
        );
        // Convert the angle-zero point from normalized shape coords to pixel space,
        // then compute the angle there so it is correct for non-square shapes.
        let end = skia::Point::new(
            rect.left + self.end.0 * rect.width(),
            rect.top + self.end.1 * rect.height(),
        );
        let dir = end - center;
        let start_angle = dir.y.atan2(dir.x).to_degrees();
        let end_angle = start_angle + 360.0;

        // Ellipse aspect: width = 1 means circle; same convention as radial.
        let aspect = if self.width > 0.0 {
            self.width
        } else {
            1.0
        };

        let mut transform = skia::Matrix::new_identity();
        transform.pre_translate((center.x, center.y));
        transform.pre_rotate(start_angle, skia::Point::new(0., 0.));
        transform.pre_scale((1., aspect), None);
        transform.pre_rotate(-start_angle, skia::Point::new(0., 0.));
        transform.pre_translate((-center.x, -center.y));

        let (colors, offsets) = self.angular_wrapped_stops();

        skia::shader::Shader::sweep_gradient(
            center,
            colors.as_slice(),
            offsets.as_slice(),
            skia::TileMode::Clamp,
            Some((start_angle, end_angle)),
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
    AngularGradient(Gradient),
    Image(ImageFill),
}

impl Fill {
    pub fn opacity(&self) -> f32 {
        match self {
            Fill::Solid(SolidColor(color)) => color.a() as f32 / 255.0,
            Fill::LinearGradient(g) => g.opacity as f32 / 255.0,
            Fill::RadialGradient(g) => g.opacity as f32 / 255.0,
            Fill::AngularGradient(g) => g.opacity as f32 / 255.0,
            Fill::Image(i) => i.opacity as f32 / 255.0,
        }
    }

    pub fn with_full_opacity(&self) -> Fill {
        match self {
            Fill::Solid(SolidColor(color)) => Fill::Solid(SolidColor(skia::Color::from_argb(
                255,
                color.r(),
                color.g(),
                color.b(),
            ))),
            Fill::LinearGradient(g) => Fill::LinearGradient(Gradient {
                opacity: 255,
                ..g.clone()
            }),
            Fill::RadialGradient(g) => Fill::RadialGradient(Gradient {
                opacity: 255,
                ..g.clone()
            }),
            Fill::AngularGradient(g) => Fill::AngularGradient(Gradient {
                opacity: 255,
                ..g.clone()
            }),
            Fill::Image(i) => Fill::Image(ImageFill {
                opacity: 255,
                ..i.clone()
            }),
        }
    }

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
            Self::AngularGradient(gradient) => {
                let mut p = skia::Paint::default();
                p.set_shader(gradient.to_angular_shader(rect));
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
        Fill::AngularGradient(gradient) => gradient.to_angular_shader(bounding_box),
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
                // Use SrcOver and treat the newly encountered fill as the source (top),
                // overlaying it over the previously composed shader (destination/bottom).
                // This avoids edge bleed from underlying fills when anti-aliasing causes
                // fractional coverage at shape boundaries.
                Some(existing_shader) => Some(skia::shaders::blend(
                    skia::Blender::mode(skia::BlendMode::SrcOver),
                    shader,
                    existing_shader,
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
