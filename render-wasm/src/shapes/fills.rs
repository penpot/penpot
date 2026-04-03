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
        // Reconstruct the full gradient inverse transform from center/end/width so the
        // sweep evaluates in gradient space.  This avoids both:
        //  • angle distortion on non-square shapes (old pixel-space atan2 bug)
        //  • the seam artifact from a rotated start_angle
        //
        // The exporter gives us:
        //   start (cx, cy) = M^-1 * (0.5, 0.5)   – center in normalized shape coords
        //   end   (ex, ey) = M^-1 * (1.0, 0.5)   – angle-zero point
        //   width          = radius_0 / radius_90  – aspect ratio
        //
        // From these we rebuild the 2×2 part of M^-1 (gradient→shape) assuming
        // orthogonal axes (rotation + non-uniform scale, no shear):
        //   col0 = 2·(dx, dy)                      maps (1,0) offset in grad space
        //   col1 = 2·(dy, -dx) / width             maps (0,1) offset
        //
        // Skia's sweep_gradient evaluates atan2 which increases counter-clockwise in
        // math convention.  Figma's angular gradient also increases CCW in its gradient
        // space (screen-CW would place offset 0.25 at the bottom, but Figma places it
        // at the top).  Using (dy, -dx) as the perpendicular matches Figma's winding.
        let dx = self.end.0 - self.start.0;
        let dy = self.end.1 - self.start.1;
        let w = if self.width > 0.0 { self.width } else { 1.0 };
        let cx = self.start.0;
        let cy = self.start.1;

        // M^-1 (2×2): gradient offsets → shape offsets
        let m_inv_2x2 = skia::Matrix::new_all(
            2.0 * dx,        2.0 * dy / w, 0.0,
            2.0 * dy,       -2.0 * dx / w, 0.0,
            0.0,             0.0,           1.0,
        );

        // Local matrix: gradient space → pixel space
        //   T(rect) · S(rect_size) · T(shape_center) · M^-1_2x2 · T(-grad_center)
        let mut transform = skia::Matrix::new_identity();
        transform.pre_translate((rect.left, rect.top));
        transform.pre_scale((rect.width(), rect.height()), None);
        transform.pre_translate((cx, cy));
        transform.pre_concat(&m_inv_2x2);
        transform.pre_translate((-0.5, -0.5));

        let (colors, offsets) = self.angular_wrapped_stops();

        // Sweep in gradient space: center at (0.5, 0.5), full circle from 0° to 360°.
        // No rotated start/end angles → the seam sits at 0° (3-o'clock) in gradient space
        // where angular_wrapped_stops guarantees color(0) == color(1).
        skia::shader::Shader::sweep_gradient(
            skia::Point::new(0.5, 0.5),
            colors.as_slice(),
            offsets.as_slice(),
            skia::TileMode::Clamp,
            Some((0.0, 360.0)),
            None,
            Some(&transform),
        )
    }

    pub fn to_diamond_shader(&self, rect: &Rect) -> Option<skia::Shader> {
        use std::cell::OnceCell;

        thread_local! {
            static EFFECT: OnceCell<skia::RuntimeEffect> = const { OnceCell::new() };
        }

        EFFECT.with(|cell| {
            let effect = cell.get_or_init(|| {
                skia::RuntimeEffect::make_for_shader(
                    "\
                    uniform float2 u_center;\n\
                    uniform float4 u_inv;\n\
                    uniform shader u_colors;\n\
                    \n\
                    half4 main(float2 coord) {\n\
                        float2 delta = coord - u_center;\n\
                        float2 local = float2(\n\
                            u_inv.x * delta.x + u_inv.y * delta.y,\n\
                            u_inv.z * delta.x + u_inv.w * delta.y\n\
                        );\n\
                        float t = clamp(abs(local.x) + abs(local.y), 0.0, 1.0);\n\
                        return u_colors.eval(float2(t, 0.5));\n\
                    }\n",
                    None,
                )
                .expect("diamond gradient SkSL compile failed")
            });

            // Work in normalized [0,1] coords so both axes use consistent units.
            // A local_matrix maps pixel coords → normalized before the shader runs.
            let dx = self.end.0 - self.start.0;
            let dy = self.end.1 - self.start.1;
            let r_norm = (dx * dx + dy * dy).sqrt();
            if r_norm < 1e-6 {
                return None;
            }

            let angle = dy.atan2(dx);
            let cos_a = angle.cos();
            let sin_a = angle.sin();
            let aspect = if self.width > 0.0 { self.width } else { 1.0 };

            // Inverse rotation + axis normalization baked together.
            // Maps normalized delta to diamond space where |x| + |y| = 1 at boundary.
            let inv_m00 = cos_a / r_norm;
            let inv_m01 = sin_a / r_norm;
            let inv_m10 = -sin_a / (r_norm * aspect);
            let inv_m11 = cos_a / (r_norm * aspect);

            // Pack uniform data using layout offsets from the compiled effect.
            let uniform_size = effect.uniform_size();
            let mut data = vec![0u8; uniform_size];

            fn write_f32(data: &mut [u8], offset: usize, val: f32) {
                data[offset..offset + 4].copy_from_slice(&val.to_ne_bytes());
            }

            for u in effect.uniforms().iter() {
                let name: &str = &u.name();
                match name {
                    "u_center" => {
                        write_f32(&mut data, u.offset(), self.start.0);
                        write_f32(&mut data, u.offset() + 4, self.start.1);
                    }
                    "u_inv" => {
                        write_f32(&mut data, u.offset(), inv_m00);
                        write_f32(&mut data, u.offset() + 4, inv_m01);
                        write_f32(&mut data, u.offset() + 8, inv_m10);
                        write_f32(&mut data, u.offset() + 12, inv_m11);
                    }
                    _ => {}
                }
            }

            // 1D color lookup gradient
            let color_shader = skia::gradient_shader::linear(
                ((0.0f32, 0.5f32), (1.0f32, 0.5f32)),
                self.colors.as_slice(),
                Some(self.offsets.as_slice()),
                skia::TileMode::Clamp,
                None,
                None,
            )?;

            let children: Vec<skia::runtime_effect::ChildPtr> =
                vec![skia::runtime_effect::ChildPtr::Shader(color_shader)];

            // Local matrix: normalized [0,1] → pixel space.
            // Skia inverts this so the shader receives normalized coords.
            let mut local_matrix = skia::Matrix::new_identity();
            local_matrix.pre_translate((rect.left, rect.top));
            local_matrix.pre_scale((rect.width(), rect.height()), None);

            effect.make_shader(
                skia::Data::new_copy(&data),
                &children,
                Some(&local_matrix),
            )
        })
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
    DiamondGradient(Gradient),
    Image(ImageFill),
}

impl Fill {
    pub fn opacity(&self) -> f32 {
        match self {
            Fill::Solid(SolidColor(color)) => color.a() as f32 / 255.0,
            Fill::LinearGradient(g) => g.opacity as f32 / 255.0,
            Fill::RadialGradient(g) => g.opacity as f32 / 255.0,
            Fill::AngularGradient(g) => g.opacity as f32 / 255.0,
            Fill::DiamondGradient(g) => g.opacity as f32 / 255.0,
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
            Fill::DiamondGradient(g) => Fill::DiamondGradient(Gradient {
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
            Self::DiamondGradient(gradient) => {
                let mut p = skia::Paint::default();
                p.set_shader(gradient.to_diamond_shader(rect));
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
        Fill::DiamondGradient(gradient) => gradient.to_diamond_shader(bounding_box),
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
