//! Liquid glass: a backdrop effect that refracts what is behind the shape
//! through a curved bevel along its edge, splits colour inside that bevel,
//! frosts it and lights the rim. Parameters follow Figma's Glass effect so
//! values can be copied from a design.
//!
//! The effect is an SkSL runtime shader applied as a backdrop image filter,
//! on the same `save_layer` path as background blur. Its input is the
//! backdrop blurred by `frost`; the shader reads it at refracted positions.

use skia_safe::{self as skia, ImageFilter, Matrix, RuntimeEffect};

use super::{radius_to_sigma, Shape, Type};

const SKSL: &str = r#"
uniform shader backdrop;
uniform float3x3 to_local;   // device px -> shape units, origin at the centre
uniform float3x3 to_device;  // inverse of to_local
uniform float2 half_size;
uniform float4 radii;        // top-left, top-right, bottom-right, bottom-left
uniform float kind;          // 0 frost only, 1 rounded rect, 2 ellipse
uniform float depth;         // bevel width in shape units
uniform float refraction;
uniform float dispersion;
uniform float2 to_light;     // unit vector toward the light, y down
uniform float light;
uniform float splay;
uniform float px;            // device px per shape unit
uniform float max_offset;    // device px a sample may move (tile margin)

const float ETA = 1.5;

// Rounded box with an analytic normal. Inside the straight section the normal
// is softened across the diagonal so the bevel does not crease at corners.
float3 sd_rrect(float2 p) {
    float r = p.x < 0.0 ? (p.y < 0.0 ? radii.x : radii.w)
                        : (p.y < 0.0 ? radii.y : radii.z);
    r = min(r, min(half_size.x, half_size.y));
    float2 sg = float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
    float2 w = abs(p) - (half_size - float2(r));
    float g = max(w.x, w.y);
    if (g > 0.0) {
        float2 q = max(w, float2(0.0));
        float l = length(q);
        return float3(l - r, sg * q / max(l, 1e-6));
    }
    float2 e = exp((w - float2(g)) / max(0.35 * depth, 1e-3));
    return float3(g - r, sg * normalize(e));
}

float3 sd_ellipse(float2 p) {
    float2 h = max(half_size, float2(1e-3));
    float2 g = p / (h * h);
    float gl = length(g);
    return float3((length(p / h) - 1.0) * min(h.x, h.y),
                  gl > 1e-6 ? g / gl : float2(0.0, -1.0));
}

// Slope of the bevel profile h(t) = (1 - (1 - t)^4)^(1/4).
float bevel_slope(float t) {
    float u = 1.0 - t;
    float u3 = u * u * u;
    return u3 * pow(max(1.0 - u3 * u, 1e-5), -0.75);
}

// Sideways travel, in glass thicknesses, of a vertical ray refracted by a
// surface tilted by atan(slope).
float refract_offset(float slope, float eta) {
    float s = slope / sqrt(1.0 + slope * slope);
    return tan(asin(s) - asin(s / eta));
}

half4 sample_at(float2 p, float off, float2 n) {
    float2 q = p - n * min(off, max_offset / px);
    return backdrop.eval((to_device * float3(q, 1.0)).xy);
}

half4 main(float2 coord) {
    if (kind < 0.5) {
        return backdrop.eval(coord);
    }
    float2 p = (to_local * float3(coord, 1.0)).xy;
    float3 sd = kind > 1.5 ? sd_ellipse(p) : sd_rrect(p);
    float d = sd.x;
    float2 n = sd.yz;
    float bevel = max(min(depth, min(half_size.x, half_size.y)), 1e-3);
    float t = clamp(-d / bevel, 0.0, 1.0);

    half4 c;
    if (t < 1.0 && refraction > 0.0) {
        float slope = bevel_slope(t);
        float reach = refraction * bevel;
        float disp = dispersion * 0.25;
        c = sample_at(p, reach * refract_offset(slope, ETA), n);
        if (disp > 0.0) {
            c.r = sample_at(p, reach * refract_offset(slope, ETA - disp), n).r;
            c.b = sample_at(p, reach * refract_offset(slope, ETA + disp), n).b;
        }
    } else {
        c = backdrop.eval(coord);
    }

    // A crisp line just inside the edge, brightest facing the light and weaker
    // on the opposite edge; splay spreads it to the flanks. It is a brightened,
    // saturated copy of the backdrop, so it takes the colour behind it.
    float facing = dot(n, to_light);
    float dir = facing > 0.0 ? splay + (1.0 - splay) * facing * facing
                             : splay + (0.4 - splay) * facing * facing;
    float width = max(0.25 * (1.0 + splay) * px, 0.75);
    float line = exp(min(d, 0.0) * px / width);
    half3 lum = half3(dot(c.rgb, half3(0.3, 0.59, 0.11)));
    half3 highlight = (lum + (c.rgb - lum) * 2.2) * 1.45 + half3(0.05) * c.a;
    c.rgb = mix(c.rgb, min(highlight, half3(c.a)), half(clamp(line * dir * light * 2.5, 0.0, 1.0)));
    return c;
}
"#;

thread_local! {
    static EFFECT: Option<RuntimeEffect> = RuntimeEffect::make_for_shader(SKSL, None)
        .inspect_err(|e| eprintln!("glass shader: {e}"))
        .ok();
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Glass {
    pub hidden: bool,
    /// Bevel displacement strength, `0..=1`.
    pub refraction: f32,
    /// Bevel width in shape units: how far the curved edge reaches inward.
    pub depth: f32,
    /// Colour split inside the bevel, `0..=1`.
    pub dispersion: f32,
    /// Backdrop blur radius, same unit as background blur.
    pub frost: f32,
    /// How widely the rim light spreads around the edge, `0..=1`.
    pub splay: f32,
    /// Rim light strength, `0..=1`.
    pub light_intensity: f32,
    /// Direction the light comes from, in degrees; 0 is the top.
    pub light_angle: f32,
}

impl Glass {
    pub fn scale_content(&mut self, value: f32) {
        self.depth *= value;
        self.frost *= value;
    }

    /// Builds the backdrop filter for `shape`. `to_device` maps shape
    /// coordinates to the device pixels the backdrop is read in; `max_sigma`
    /// and `max_offset` (device px) keep reads inside the tile margin.
    pub fn backdrop_filter(
        &self,
        shape: &Shape,
        to_device: &Matrix,
        max_sigma: f32,
        max_offset: f32,
    ) -> Option<ImageFilter> {
        let mut to_device = *to_device;
        to_device.pre_translate(shape.center());
        let to_local = to_device.invert()?;
        let px = (to_device.scale_x() * to_device.scale_y()
            - to_device.skew_x() * to_device.skew_y())
        .abs()
        .sqrt();

        let (kind, radii) = match &shape.shape_type {
            Type::Rect(data) => (1.0, corner_radii(data.corners.as_ref())),
            Type::Frame(data) => (1.0, corner_radii(data.corners.as_ref())),
            Type::Circle => (2.0, [0.0; 4]),
            // Paths, text and groups have no analytic edge: frost only.
            _ => (0.0, [0.0; 4]),
        };
        let selrect = shape.selrect;
        let angle = self.light_angle.to_radians();

        let effect = EFFECT.with(|e| e.clone())?;
        let mut builder = skia::runtime_effect::RuntimeShaderBuilder::new(effect);
        let uniforms: [(&str, &[f32]); 13] = [
            ("to_local", &column_major(&to_local)),
            ("to_device", &column_major(&to_device)),
            (
                "half_size",
                &[selrect.width() / 2.0, selrect.height() / 2.0],
            ),
            ("radii", &radii),
            ("kind", &[kind]),
            ("depth", &[self.depth.max(0.0)]),
            ("refraction", &[self.refraction.clamp(0.0, 1.0)]),
            ("dispersion", &[self.dispersion.clamp(0.0, 1.0)]),
            ("to_light", &[angle.sin(), -angle.cos()]),
            ("light", &[self.light_intensity.clamp(0.0, 1.0)]),
            ("splay", &[self.splay.clamp(0.0, 1.0)]),
            ("px", &[px.max(1e-6)]),
            ("max_offset", &[max_offset]),
        ];
        for (name, data) in uniforms {
            builder.set_uniform_float(name, data).ok()?;
        }

        let sigma = radius_to_sigma(self.frost * px).min(max_sigma);
        let frost = (sigma > 0.0)
            .then(|| skia::image_filters::blur((sigma, sigma), skia::TileMode::Clamp, None, None))
            .flatten();
        skia::image_filters::runtime_shader(&builder, "backdrop", frost)
    }
}

/// Per-corner radii in shader order; Penpot corners are elliptical
/// `(rx, ry)` pairs, the shader takes one radius per corner.
fn corner_radii(corners: Option<&super::Corners>) -> [f32; 4] {
    corners.map_or([0.0; 4], |c| c.map(|r| r.x.min(r.y)))
}

/// Skia matrices are row-major; SkSL `float3x3` uniforms are column-major.
fn column_major(m: &Matrix) -> [f32; 9] {
    let mut r = [0.0; 9];
    m.get_9(&mut r);
    [r[0], r[3], r[6], r[1], r[4], r[7], r[2], r[5], r[8]]
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::shapes::Type;

    fn glass() -> Glass {
        Glass {
            hidden: false,
            refraction: 0.7,
            depth: 20.0,
            dispersion: 0.0,
            frost: 0.0,
            splay: 0.2,
            light_intensity: 0.0,
            light_angle: 0.0,
        }
    }

    #[test]
    fn shader_compiles() {
        RuntimeEffect::make_for_shader(SKSL, None).unwrap();
    }

    #[test]
    fn column_major_transposes() {
        let m = Matrix::new_all(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0);
        assert_eq!(
            column_major(&m),
            [1.0, 4.0, 7.0, 2.0, 5.0, 8.0, 3.0, 6.0, 9.0]
        );
    }

    #[test]
    fn scale_content_scales_lengths_only() {
        let mut g = glass();
        g.scale_content(2.0);
        assert_eq!((g.depth, g.frost, g.refraction), (40.0, 0.0, 0.7));
    }

    /// Draws vertical stripes, applies glass to a 100×60 rect over them and
    /// checks that the flat centre shows the backdrop unchanged while the
    /// bevel shows it moved.
    #[test]
    fn refracts_only_inside_the_bevel() {
        let mut surface = skia::surfaces::raster_n32_premul((200, 120)).unwrap();
        let canvas = surface.canvas();
        let mut paint = skia::Paint::default();
        for x in (0..200).step_by(8) {
            paint.set_color(if x % 16 == 0 {
                skia::Color::BLACK
            } else {
                skia::Color::WHITE
            });
            canvas.draw_rect(skia::Rect::from_xywh(x as f32, 0.0, 8.0, 120.0), &paint);
        }
        let before = surface.image_snapshot();

        let mut shape = Shape::new(crate::uuid::Uuid::nil());
        shape.set_shape_type(Type::Rect(Default::default()));
        shape.set_selrect(50.0, 30.0, 150.0, 90.0);
        let filter = glass()
            .backdrop_filter(&shape, &Matrix::new_identity(), f32::MAX, f32::MAX)
            .unwrap();

        let canvas = surface.canvas();
        canvas.save();
        canvas.clip_rect(shape.selrect, None, false);
        let mut src = skia::Paint::default();
        src.set_blend_mode(skia::BlendMode::Src);
        canvas.save_layer(
            &skia::canvas::SaveLayerRec::default()
                .backdrop(&filter)
                .paint(&src),
        );
        canvas.restore();
        canvas.restore();
        let after = surface.image_snapshot();

        let px = |img: &skia::Image, x: i32, y: i32| {
            let info = skia::ImageInfo::new_n32_premul((1, 1), None);
            let mut out = [0u8; 4];
            img.read_pixels(&info, &mut out, 4, (x, y), skia::image::CachingHint::Allow);
            out
        };
        assert_eq!(
            px(&before, 100, 60),
            px(&after, 100, 60),
            "flat centre is untouched"
        );
        let moved = (52..60).any(|x| px(&before, x, 60) != px(&after, x, 60));
        assert!(moved, "bevel pulls the backdrop from further inside");
        assert_eq!(
            px(&before, 20, 60),
            px(&after, 20, 60),
            "outside the shape is untouched"
        );
    }
}
